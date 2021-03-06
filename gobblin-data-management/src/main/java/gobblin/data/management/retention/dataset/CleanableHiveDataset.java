/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.data.management.retention.dataset;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.thrift.TException;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;

import gobblin.data.management.copy.hive.HiveDataset;
import gobblin.data.management.policy.SelectBeforeTimeBasedPolicy;
import gobblin.data.management.policy.VersionSelectionPolicy;
import gobblin.data.management.version.HiveDatasetVersion;
import gobblin.data.management.version.finder.DatePartitionHiveVersionFinder;
import gobblin.data.management.version.finder.AbstractHiveDatasetVersionFinder;
import gobblin.hive.HiveMetastoreClientPool;
import gobblin.util.AutoReturnableObject;
import gobblin.util.ConfigUtils;
import gobblin.util.reflection.GobblinConstructorUtils;

/**
 * <p>
 * A {@link HiveDataset} used for Retention. A {@link HiveDataset} represents a hive table and a {@link HiveDatasetVersion}
 * represents a hive partition of this table.
 * </p>
 *
 * <ul>
 * <li>A version finder at {@value #VERSION_FINDER_CLASS_KEY} is used to find all the partitions the dataset
 * <li>A selection policy at {@value #SELECTION_POLICY_CLASS_KEY} is appliced on all these partitions to get the partitions to be deleted.
 * <li>These selected partitions are dropped in the hive metastore and all the data on FileSystem is also deleted
 * </ul>
 *
 */
@Slf4j
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CleanableHiveDataset extends HiveDataset implements CleanableDataset {

  private static final String SHOULD_DELETE_DATA_KEY = "gobblin.retention.hive.shouldDeleteData";
  private static final String SHOULD_DELETE_DATA_DEFAULT = Boolean.toString(true);

  private static final String VERSION_FINDER_CLASS_KEY = "version.finder.class";
  private static final String DEFAULT_VERSION_FINDER_CLASS = DatePartitionHiveVersionFinder.class.getName();

  private static final String SELECTION_POLICY_CLASS_KEY = "selection.policy.class";
  private static final String DEFAULT_SELECTION_POLICY_CLASS = SelectBeforeTimeBasedPolicy.class.getName();

  private final VersionSelectionPolicy hiveSelectionPolicy;
  private final AbstractHiveDatasetVersionFinder hiveDatasetVersionFinder;

  private final boolean simulate;
  private final boolean shouldDeleteData;
  private final FsCleanableHelper fsCleanableHelper;

  public CleanableHiveDataset(FileSystem fs, HiveMetastoreClientPool clientPool, Table table, Properties jobProps, Config datasetConfig) throws IOException {
    super(fs, clientPool, table, jobProps, datasetConfig);

    try {
      this.hiveSelectionPolicy =
          (VersionSelectionPolicy) GobblinConstructorUtils.invokeFirstConstructor(
              Class.forName(ConfigUtils.getString(datasetConfig, SELECTION_POLICY_CLASS_KEY, DEFAULT_SELECTION_POLICY_CLASS)),
              ImmutableList.<Object> of(datasetConfig, jobProps), ImmutableList.<Object> of(datasetConfig), ImmutableList.<Object> of(jobProps));

      this.hiveDatasetVersionFinder =
          (AbstractHiveDatasetVersionFinder) GobblinConstructorUtils.invokeFirstConstructor(
              Class.forName(ConfigUtils.getString(datasetConfig, VERSION_FINDER_CLASS_KEY, DEFAULT_VERSION_FINDER_CLASS)),
              ImmutableList.<Object> of(this.fs, datasetConfig), ImmutableList.<Object> of(this.fs, jobProps));
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException | ClassNotFoundException e) {
      throw new IllegalArgumentException(e);
    }

    this.fsCleanableHelper = new FsCleanableHelper(fs, jobProps, datasetConfig, log);

    this.shouldDeleteData = Boolean.valueOf(jobProps.getProperty(SHOULD_DELETE_DATA_KEY, SHOULD_DELETE_DATA_DEFAULT));
    this.simulate = Boolean.valueOf(jobProps.getProperty(FsCleanableHelper.SIMULATE_KEY, FsCleanableHelper.SIMULATE_DEFAULT));
  }

  /**
   * Drops the partitions selected by {@link #hiveSelectionPolicy}. Also deletes the data associated with it.
   * <p>
   * If an {@link Exception} occurs while processing a {@link Partition}, other {@link Partition}s will still be deleted.
   * However, a {@link RuntimeException} is thrown at the end if there was at least one {@link Exception}.
   * </p>
   */
  @Override
  public void clean() throws IOException {

    List versions = Lists.newArrayList(this.hiveDatasetVersionFinder.findDatasetVersions(this));

    if (versions.isEmpty()) {
      log.warn(String.format("No dataset version can be found. Ignoring %s", this.getTable().getCompleteName()));
      return;
    }

    Collections.sort(versions, Collections.reverseOrder());

    Collection<HiveDatasetVersion> deletableVersions = this.hiveSelectionPolicy.listSelectedVersions(versions);

    List<Exception> exceptions = Lists.newArrayList();
    Set<Path> possiblyEmptyDirectories = new HashSet<>();

    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {

      for (HiveDatasetVersion hiveDatasetVersion : deletableVersions) {
        Partition partition = hiveDatasetVersion.getPartition();
        try {
          if (!this.simulate) {
            client.get().dropPartition(partition.getTable().getDbName(), partition.getTable().getTableName(), partition.getValues(), false);
            log.info("Successfully dropped partition " + partition.getCompleteName());
          } else {
            log.info("Simulating drop partition " + partition.getCompleteName());
          }
          if (this.shouldDeleteData) {
            this.fsCleanableHelper.clean(hiveDatasetVersion, possiblyEmptyDirectories);
          }
        } catch (TException | IOException e) {
          log.warn(String.format("Failed to completely delete partition %s.", partition.getCompleteName()), e);
          exceptions.add(e);
        }
      }

      this.fsCleanableHelper.cleanEmptyDirectories(possiblyEmptyDirectories, this);
    }

    if (!exceptions.isEmpty()) {
      throw new RuntimeException(String.format("Deletion failed for %s partitions", exceptions.size()));
    }
  }

  @Override
  public Path datasetRoot() {
    return super.getTable().getDataLocation();
  }
}
