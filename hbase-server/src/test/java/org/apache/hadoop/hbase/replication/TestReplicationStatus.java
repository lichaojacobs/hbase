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
package org.apache.hadoop.hbase.replication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.ServerLoad;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.ReplicationTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ReplicationTests.class, MediumTests.class})
public class TestReplicationStatus extends TestReplicationBase {
  private static final Log LOG = LogFactory.getLog(TestReplicationStatus.class);
  private static final String PEER_ID = "2";

  /**
   * Test for HBASE-9531
   * put a few rows into htable1, which should be replicated to htable2
   * create a ClusterStatus instance 'status' from HBaseAdmin
   * test : status.getLoad(server).getReplicationLoadSourceList()
   * test : status.getLoad(server).getReplicationLoadSink()
   * * @throws Exception
   */
  @Test(timeout = 300000)
  public void testReplicationStatus() throws Exception {
    LOG.info("testReplicationStatus");

    try (Admin hbaseAdmin = utility1.getConnection().getAdmin()) {
      // Wait roll log request in setUp() to finish
      Thread.sleep(5000);

      // disable peer
      admin.disablePeer(PEER_ID);

      final byte[] qualName = Bytes.toBytes("q");
      Put p;

      for (int i = 0; i < NB_ROWS_IN_BATCH; i++) {
        p = new Put(Bytes.toBytes("row" + i));
        p.addColumn(famName, qualName, Bytes.toBytes("val" + i));
        htable1.put(p);
      }

      ClusterStatus status = hbaseAdmin.getClusterStatus();
      long globalSizeOfLogQueue = 0;

      for (JVMClusterUtil.RegionServerThread thread : utility1.getHBaseCluster()
          .getRegionServerThreads()) {
        ServerName server = thread.getRegionServer().getServerName();
        ServerLoad sl = status.getLoad(server);
        List<ReplicationLoadSource> rLoadSourceList = sl.getReplicationLoadSourceList();
        ReplicationLoadSink rLoadSink = sl.getReplicationLoadSink();

        // check SourceList only has one entry
        assertTrue("failed to get ReplicationLoadSourceList", (rLoadSourceList.size() == 1));
        globalSizeOfLogQueue += rLoadSourceList.get(0).getSizeOfLogQueue();

        // check Sink exist only as it is difficult to verify the value on the fly
        assertTrue("failed to get ReplicationLoadSink.AgeOfLastShippedOp ",
          (rLoadSink.getAgeOfLastAppliedOp() >= 0));
        assertTrue("failed to get ReplicationLoadSink.TimeStampsOfLastAppliedOp ",
          (rLoadSink.getTimeStampsOfLastAppliedOp() >= 0));
      }

      // Stop one rs
      utility1.getHBaseCluster().getRegionServer(1).stop("Stop RegionServer");
      Thread.sleep(5000);
      status = hbaseAdmin.getClusterStatus();
      ServerName server = utility1.getHBaseCluster().getRegionServer(0).getServerName();
      ServerLoad sl = status.getLoad(server);
      List<ReplicationLoadSource> rLoadSourceList = sl.getReplicationLoadSourceList();
      // check SourceList only has one entry
      assertTrue("failed to get ReplicationLoadSourceList", (rLoadSourceList.size() == 1));
      // Another rs has one queue and one recovery queue from died rs
      assertEquals(globalSizeOfLogQueue, rLoadSourceList.get(0).getSizeOfLogQueue());
    } finally {
      admin.enablePeer(PEER_ID);
      utility1.getHBaseCluster().getRegionServer(1).start();
    }
  }
}