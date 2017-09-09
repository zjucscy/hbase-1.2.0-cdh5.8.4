/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.NamespaceNotFoundException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.master.cleaner.TimeToLiveHFileCleaner;
import org.apache.hadoop.hbase.master.snapshot.SnapshotManager;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.snapshot.MobSnapshotTestingUtils;
import org.apache.hadoop.hbase.snapshot.SnapshotDoesNotExistException;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test clone snapshots from the client
 */
@Category(LargeTests.class)
public class TestMobCloneSnapshotFromClient {

  private static boolean delayFlush = false;
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  private final byte[] FAMILY = Bytes.toBytes("cf");

  private byte[] emptySnapshot;
  private byte[] snapshotName0;
  private byte[] snapshotName1;
  private byte[] snapshotName2;
  private int snapshot0Rows;
  private int snapshot1Rows;
  private TableName tableName;
  private Admin admin;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.getConfiguration().setBoolean(SnapshotManager.HBASE_SNAPSHOT_ENABLED, true);
    TEST_UTIL.getConfiguration().setBoolean("hbase.online.schema.update.enable", true);
    TEST_UTIL.getConfiguration().setInt("hbase.hstore.compactionThreshold", 10);
    TEST_UTIL.getConfiguration().setInt("hbase.regionserver.msginterval", 100);
    TEST_UTIL.getConfiguration().setInt("hbase.client.pause", 250);
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 6);
    TEST_UTIL.getConfiguration().setBoolean(
        "hbase.master.enabletable.roundrobin", true);
    TEST_UTIL.getConfiguration().setLong(TimeToLiveHFileCleaner.TTL_CONF_KEY, 0);
    TEST_UTIL.getConfiguration().setInt(MobConstants.MOB_FILE_CACHE_SIZE_KEY, 0);
    TEST_UTIL.getConfiguration().setInt("hfile.format.version", 3);
    TEST_UTIL.startMiniCluster(3);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  /**
   * Initialize the tests with a table filled with some data
   * and two snapshots (snapshotName0, snapshotName1) of different states.
   * The tableName, snapshotNames and the number of rows in the snapshot are initialized.
   */
  @Before
  public void setup() throws Exception {
    this.admin = TEST_UTIL.getHBaseAdmin();

    long tid = System.currentTimeMillis();
    tableName = TableName.valueOf("testtb-" + tid);
    emptySnapshot = Bytes.toBytes("emptySnaptb-" + tid);
    snapshotName0 = Bytes.toBytes("snaptb0-" + tid);
    snapshotName1 = Bytes.toBytes("snaptb1-" + tid);
    snapshotName2 = Bytes.toBytes("snaptb2-" + tid);

    // create Table and disable it
    createMobTable(TEST_UTIL, tableName, SnapshotTestingUtils.getSplitKeys(), getNumReplicas(),
      FAMILY);
    delayFlush = false;

    admin.disableTable(tableName);

    // take an empty snapshot
    admin.snapshot(emptySnapshot, tableName);

    HTable table = new HTable(TEST_UTIL.getConfiguration(), tableName);
    try {
      // enable table and insert data
      admin.enableTable(tableName);
      SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 20, FAMILY);
      snapshot0Rows = MobSnapshotTestingUtils.countMobRows(table);
      admin.disableTable(tableName);

      // take a snapshot
      admin.snapshot(snapshotName0, tableName);

      // enable table and insert more data
      admin.enableTable(tableName);
      SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 20, FAMILY);
      snapshot1Rows = MobSnapshotTestingUtils.countMobRows(table);
      admin.disableTable(tableName);

      // take a snapshot of the updated table
      admin.snapshot(snapshotName1, tableName);

      // re-enable table
      admin.enableTable(tableName);
    } finally {
      table.close();
    }
  }

  protected int getNumReplicas() {
    return 1;
  }

  @After
  public void tearDown() throws Exception {
    if (admin.tableExists(tableName)) {
      TEST_UTIL.deleteTable(tableName);
    }
    SnapshotTestingUtils.deleteAllSnapshots(admin);
    SnapshotTestingUtils.deleteArchiveDirectory(TEST_UTIL);
  }

  @Test(expected=SnapshotDoesNotExistException.class)
  public void testCloneNonExistentSnapshot() throws IOException, InterruptedException {
    String snapshotName = "random-snapshot-" + System.currentTimeMillis();
    TableName tableName = TableName.valueOf("random-table-" + System.currentTimeMillis());
    admin.cloneSnapshot(snapshotName, tableName);
  }

  @Test(expected = NamespaceNotFoundException.class)
  public void testCloneOnMissingNamespace() throws IOException, InterruptedException {
    TableName clonedTableName = TableName.valueOf("unknownNS:clonetb");
    admin.cloneSnapshot(snapshotName1, clonedTableName);
  }

  @Test
  public void testCloneSnapshot() throws IOException, InterruptedException {
    TableName clonedTableName = TableName.valueOf("clonedtb-" + System.currentTimeMillis());
    testCloneSnapshot(clonedTableName, snapshotName0, snapshot0Rows);
    testCloneSnapshot(clonedTableName, snapshotName1, snapshot1Rows);
    testCloneSnapshot(clonedTableName, emptySnapshot, 0);
  }

  private void testCloneSnapshot(final TableName tableName, final byte[] snapshotName,
      int snapshotRows) throws IOException, InterruptedException {
    // create a new table from snapshot
    admin.cloneSnapshot(snapshotName, tableName);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, tableName, snapshotRows);

    verifyReplicasCameOnline(tableName);
    TEST_UTIL.deleteTable(tableName);
  }

  protected void verifyReplicasCameOnline(TableName tableName) throws IOException {
    SnapshotTestingUtils.verifyReplicasCameOnline(tableName, admin, getNumReplicas());
  }

  @Test
  public void testCloneSnapshotCrossNamespace() throws IOException, InterruptedException {
    String nsName = "testCloneSnapshotCrossNamespace";
    admin.createNamespace(NamespaceDescriptor.create(nsName).build());
    TableName clonedTableName =
        TableName.valueOf(nsName, "clonedtb-" + System.currentTimeMillis());
    testCloneSnapshot(clonedTableName, snapshotName0, snapshot0Rows);
    testCloneSnapshot(clonedTableName, snapshotName1, snapshot1Rows);
    testCloneSnapshot(clonedTableName, emptySnapshot, 0);
  }

  /**
   * Verify that tables created from the snapshot are still alive after source table deletion.
   */
  @Test
  public void testCloneLinksAfterDelete() throws IOException, InterruptedException {

    // delay the flush to make sure
    delayFlush = true;
    SnapshotTestingUtils.loadData(TEST_UTIL, tableName, 20, FAMILY);
    long tid = System.currentTimeMillis();
    byte[] snapshotName3 = Bytes.toBytes("snaptb3-" + tid);
    TableName clonedTableName3 = TableName.valueOf("clonedtb3-" + System.currentTimeMillis());
    admin.snapshot(snapshotName3, tableName);
    delayFlush = false;
    int snapshot3Rows = -1;
    try (Table table = TEST_UTIL.getConnection().getTable(tableName)) {
      snapshot3Rows = TEST_UTIL.countRows(table);
    }
    admin.cloneSnapshot(snapshotName3, clonedTableName3);
    admin.deleteSnapshot(snapshotName3);


    // Clone a table from the first snapshot
    TableName clonedTableName = TableName.valueOf("clonedtb1-" + System.currentTimeMillis());
    admin.cloneSnapshot(snapshotName0, clonedTableName);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName, snapshot0Rows);

    // Take a snapshot of this cloned table.
    admin.disableTable(clonedTableName);
    admin.snapshot(snapshotName2, clonedTableName);

    // Clone the snapshot of the cloned table
    TableName clonedTableName2 = TableName.valueOf("clonedtb2-" + System.currentTimeMillis());
    admin.cloneSnapshot(snapshotName2, clonedTableName2);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName2, snapshot0Rows);
    admin.disableTable(clonedTableName2);

    // Remove the original table
    TEST_UTIL.deleteTable(tableName);
    waitCleanerRun();

    // Verify the first cloned table
    admin.enableTable(clonedTableName);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName, snapshot0Rows);

    // Verify the second cloned table
    admin.enableTable(clonedTableName2);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName2, snapshot0Rows);
    admin.disableTable(clonedTableName2);

    // Delete the first cloned table
    TEST_UTIL.deleteTable(clonedTableName);
    waitCleanerRun();

    // Verify the second cloned table
    admin.enableTable(clonedTableName2);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName2, snapshot0Rows);

    // Clone a new table from cloned
    TableName clonedTableName4 = TableName.valueOf("clonedtb4-" + System.currentTimeMillis());
    admin.cloneSnapshot(snapshotName2, clonedTableName4);
    MobSnapshotTestingUtils.verifyMobRowCount(TEST_UTIL, clonedTableName4, snapshot0Rows);

    verifyRowCount(TEST_UTIL, clonedTableName3, snapshot3Rows);
    // Delete the cloned tables
    TEST_UTIL.deleteTable(clonedTableName2);
    TEST_UTIL.deleteTable(clonedTableName3);
    admin.deleteSnapshot(snapshotName2);
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  private void waitCleanerRun() throws InterruptedException {
    TEST_UTIL.getMiniHBaseCluster().getMaster().getHFileCleaner().choreForTesting();
  }


  private void verifyRowCount(final HBaseTestingUtility util, final TableName tableName,
    long expectedRows) throws IOException {
    MobSnapshotTestingUtils.verifyMobRowCount(util, tableName, expectedRows);
  }
  /**
   * This coprocessor is used to delay the flush.
   */
  public static class DelayFlushCoprocessor extends BaseRegionObserver {
    @Override
    public void preFlush(ObserverContext<RegionCoprocessorEnvironment> e) throws IOException {
      if (delayFlush) {
        try {
          if (Bytes.compareTo(e.getEnvironment().getRegionInfo().getStartKey(),
              HConstants.EMPTY_START_ROW) != 0) {
            Thread.sleep(100);
          }
        } catch (InterruptedException e1) {
          throw new InterruptedIOException(e1.getMessage());
        }
      }
      super.preFlush(e);
    }
  }

  private void createMobTable(final HBaseTestingUtility util, final TableName tableName,
      final byte[][] splitKeys, int regionReplication, final byte[]... families) throws IOException,
      InterruptedException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.setRegionReplication(regionReplication);
    htd.addCoprocessor(DelayFlushCoprocessor.class.getName());
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family);
      hcd.setMobEnabled(true);
      hcd.setMobThreshold(0L);
      htd.addFamily(hcd);
    }
    util.getHBaseAdmin().createTable(htd, splitKeys);
    SnapshotTestingUtils.waitForTableToBeOnline(util, tableName);
    assertEquals((splitKeys.length + 1) * regionReplication,
        util.getHBaseAdmin().getTableRegions(tableName).size());
  }

}