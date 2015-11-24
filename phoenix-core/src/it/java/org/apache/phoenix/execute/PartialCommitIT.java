/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 *distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you maynot use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicablelaw or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.execute;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.singletonList;
import static org.apache.phoenix.util.PhoenixRuntime.JDBC_PROTOCOL;
import static org.apache.phoenix.util.PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR;
import static org.apache.phoenix.util.PhoenixRuntime.JDBC_PROTOCOL_TERMINATOR;
import static org.apache.phoenix.util.PhoenixRuntime.PHOENIX_TEST_DRIVER_URL_PARAM;
import static org.apache.phoenix.util.TestUtil.LOCALHOST;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.coprocessor.SimpleRegionObserver;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.hbase.index.Indexer;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

@RunWith(Parameterized.class)
public class PartialCommitIT extends BaseTest {
    
	private final String A_SUCESS_TABLE;
	private final String B_FAILURE_TABLE;
	private final String C_SUCESS_TABLE;
    private final String UPSERT_TO_FAIL;
    private final String UPSERT_SELECT_TO_FAIL;
    private final String DELETE_TO_FAIL;
    private static final String TABLE_NAME_TO_FAIL = "B_FAILURE_TABLE";
    private static final byte[] ROW_TO_FAIL_UPSERT_BYTES = Bytes.toBytes("fail me upsert");
    private static final byte[] ROW_TO_FAIL_DELETE_BYTES = Bytes.toBytes("fail me delete");
    private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
    private static Driver driver;
    
    @BeforeClass
    public static void setupCluster() throws Exception {
      Configuration conf = TEST_UTIL.getConfiguration();
      setUpConfigForMiniCluster(conf);
      conf.setClass("hbase.coprocessor.region.classes", FailingRegionObserver.class, RegionObserver.class);
      conf.setBoolean("hbase.coprocessor.abortonerror", false);
      conf.setBoolean(Indexer.CHECK_VERSION_CONF_KEY, false);
      TEST_UTIL.startMiniCluster();
      String clientPort = TEST_UTIL.getConfiguration().get(QueryServices.ZOOKEEPER_PORT_ATTRIB);
      url = JDBC_PROTOCOL + JDBC_PROTOCOL_SEPARATOR + LOCALHOST + JDBC_PROTOCOL_SEPARATOR + clientPort
              + JDBC_PROTOCOL_TERMINATOR + PHOENIX_TEST_DRIVER_URL_PARAM;

      Map<String, String> props = Maps.newHashMapWithExpectedSize(1);
      // Must update config before starting server
      driver = initAndRegisterDriver(url, ReadOnlyProps.EMPTY_PROPS);
      clusterInitialized = true;
      setupTxManager();
      createTablesWithABitOfData();
    }
    
    @Parameters(name="transactional = {0}")
    public static Collection<Boolean> data() {
        return Arrays.asList(false, true);
    }
    
    public PartialCommitIT(boolean transactional) {
		if (transactional) {
			A_SUCESS_TABLE = "A_SUCCESS_TABLE_TXN";
			B_FAILURE_TABLE = TABLE_NAME_TO_FAIL+"_TXN";
			C_SUCESS_TABLE = "C_SUCCESS_TABLE_TXN";
		}
		else {
			A_SUCESS_TABLE = "A_SUCCESS_TABLE";
			B_FAILURE_TABLE = TABLE_NAME_TO_FAIL;
			C_SUCESS_TABLE = "C_SUCCESS_TABLE";
		}
	    UPSERT_TO_FAIL = "upsert into " + B_FAILURE_TABLE + " values ('" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "', 'boom!')";
	    UPSERT_SELECT_TO_FAIL = "upsert into " + B_FAILURE_TABLE + " select k, c from a_success_table";
	    DELETE_TO_FAIL = "delete from " + B_FAILURE_TABLE + "  where k='" + Bytes.toString(ROW_TO_FAIL_DELETE_BYTES) + "'";
	}
    
    private static void createTablesWithABitOfData() throws Exception {
        try (Connection con = driver.connect(url, new Properties())) {
            Statement sta = con.createStatement();
            sta.execute("create table a_success_table (k varchar primary key, c varchar)");
            sta.execute("create table b_failure_table (k varchar primary key, c varchar)");
            sta.execute("create table c_success_table (k varchar primary key, c varchar)");
            sta.execute("create table a_success_table_txn (k varchar primary key, c varchar) TRANSACTIONAL=true");
            sta.execute("create table b_failure_table_txn (k varchar primary key, c varchar) TRANSACTIONAL=true");
            sta.execute("create table c_success_table_txn (k varchar primary key, c varchar) TRANSACTIONAL=true");
            con.commit();
        }

        try (Connection con = driver.connect(url, new Properties())) {
            con.setAutoCommit(false);
            Statement sta = con.createStatement();
            for (String table : newHashSet("a_success_table", "b_failure_table", "c_success_table", 
            		"a_success_table_txn", "b_failure_table_txn", "c_success_table_txn")) {
                sta.execute("upsert into " + table + " values ('z', 'z')");
                sta.execute("upsert into " + table + " values ('zz', 'zz')");
                sta.execute("upsert into " + table + " values ('zzz', 'zzz')");
            }
            con.commit();
        }
    }
    
    @AfterClass
    public static void teardownCluster() throws Exception {
      TEST_UTIL.shutdownMiniCluster();
    }
    
    @Test
    public void testNoFailure() {
        testPartialCommit(singletonList("upsert into " + A_SUCESS_TABLE + " values ('testNoFailure', 'a')"), 0, new int[0], false,
                                        singletonList("select count(*) from " + A_SUCESS_TABLE + " where k='testNoFailure'"), singletonList(new Integer(1)));
    }
    
    @Test
    public void testUpsertFailure() {
        testPartialCommit(newArrayList("upsert into " + A_SUCESS_TABLE + " values ('testUpsertFailure1', 'a')", 
                                       UPSERT_TO_FAIL, 
                                       "upsert into " + A_SUCESS_TABLE + " values ('testUpsertFailure2', 'b')"), 
                                       1, new int[]{1}, true,
                                       newArrayList("select count(*) from " + A_SUCESS_TABLE + " where k like 'testUpsertFailure_'",
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = '" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "'"), 
                                       newArrayList(new Integer(2), new Integer(0)));
    }
    
    @Test
    public void testUpsertSelectFailure() throws SQLException {
        try (Connection con = driver.connect(url, new Properties())) {
            con.createStatement().execute("upsert into " + A_SUCESS_TABLE + " values ('" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "', 'boom!')");
            con.commit();
        }
        
        testPartialCommit(newArrayList("upsert into " + A_SUCESS_TABLE + " values ('testUpsertSelectFailure', 'a')", 
                                       UPSERT_SELECT_TO_FAIL), 
                                       1, new int[]{1}, true,
                                       newArrayList("select count(*) from " + A_SUCESS_TABLE + " where k in ('testUpsertSelectFailure', '" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "')",
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = '" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "'"), 
                                       newArrayList(new Integer(2), new Integer(0)));
    }
    
    @Test
    public void testDeleteFailure() {
        testPartialCommit(newArrayList("upsert into " + A_SUCESS_TABLE + " values ('testDeleteFailure1', 'a')", 
                                       DELETE_TO_FAIL,
                                       "upsert into " + A_SUCESS_TABLE + " values ('testDeleteFailure2', 'b')"), 
                                       1, new int[]{1}, true,
                                       newArrayList("select count(*) from " + A_SUCESS_TABLE + " where k like 'testDeleteFailure_'",
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = 'z'"), 
                                       newArrayList(new Integer(2), new Integer(1)));
    }
    
    /**
     * {@link MutationState} keeps mutations ordered lexicographically by table name.
     */
    @Test
    public void testOrderOfMutationsIsPredicatable() {
        testPartialCommit(newArrayList("upsert into " + C_SUCESS_TABLE + " values ('testOrderOfMutationsIsPredicatable', 'c')", // will fail because c_success_table is after b_failure_table by table sort order
                                       UPSERT_TO_FAIL, 
                                       "upsert into " + A_SUCESS_TABLE + " values ('testOrderOfMutationsIsPredicatable', 'a')"), // will succeed because a_success_table is before b_failure_table by table sort order
                                       2, new int[]{0,1}, true,
                                       newArrayList("select count(*) from " + C_SUCESS_TABLE + " where k='testOrderOfMutationsIsPredicatable'",
                                                    "select count(*) from " + A_SUCESS_TABLE + " where k='testOrderOfMutationsIsPredicatable'",
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = '" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "'"), 
                                       newArrayList(new Integer(0), new Integer(1), new Integer(0)));
    }
    
    @Test
    public void testStatementOrderMaintainedInConnection() {
        testPartialCommit(newArrayList("upsert into " + A_SUCESS_TABLE + " values ('testStatementOrderMaintainedInConnection', 'a')", 
                                       "upsert into " + A_SUCESS_TABLE + " select k, c from " + C_SUCESS_TABLE,
                                       DELETE_TO_FAIL,
                                       "select * from " + A_SUCESS_TABLE + "", 
                                       UPSERT_TO_FAIL), 
                                       2, new int[]{2,4}, true,
                                       newArrayList("select count(*) from " + A_SUCESS_TABLE + " where k='testStatementOrderMaintainedInConnection' or k like 'z%'", // rows left: zz, zzz, checkThatAllStatementTypesMaintainOrderInConnection
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = '" + Bytes.toString(ROW_TO_FAIL_UPSERT_BYTES) + "'",
                                                    "select count(*) from " + B_FAILURE_TABLE + " where k = 'z'"), 
                                       newArrayList(new Integer(4), new Integer(0), new Integer(1)));
    }
    
    private void testPartialCommit(List<String> statements, int failureCount, int[] expectedUncommittedStatementIndexes, boolean willFail,
                                   List<String> countStatementsForVerification, List<Integer> expectedCountsForVerification) {
        Preconditions.checkArgument(countStatementsForVerification.size() == expectedCountsForVerification.size());
        
        try (Connection con = getConnectionWithTableOrderPreservingMutationState()) {
            con.setAutoCommit(false);
            Statement sta = con.createStatement();
            for (String statement : statements) {
                sta.execute(statement);
            }
            try {
                con.commit();
                if (willFail) {
                    fail("Expected at least one statement in the list to fail");
                } else {
                    assertEquals(0, con.unwrap(PhoenixConnection.class).getStatementExecutionCounter()); // should have been reset to 0 in commit()
                }
            } catch (SQLException sqle) {
                if (!willFail) {
                    fail("Expected no statements to fail");
                }
                assertEquals(CommitException.class, sqle.getClass());
                int[] uncommittedStatementIndexes = ((CommitException)sqle).getUncommittedStatementIndexes();
                assertEquals(failureCount, uncommittedStatementIndexes.length);
                assertArrayEquals(expectedUncommittedStatementIndexes, uncommittedStatementIndexes);
            }
            
            // verify data in HBase
            for (int i = 0; i < countStatementsForVerification.size(); i++) {
                String countStatement = countStatementsForVerification.get(i);
                ResultSet rs = sta.executeQuery(countStatement);
                if (!rs.next()) {
                    fail("Expected a single row from count query");
                }
                assertEquals(expectedCountsForVerification.get(i).intValue(), rs.getInt(1));
            }
        } catch (SQLException e) {
            fail(e.toString());
        }
    }
    
    private PhoenixConnection getConnectionWithTableOrderPreservingMutationState() throws SQLException {
        Connection con = driver.connect(url, new Properties());
        PhoenixConnection phxCon = new PhoenixConnection(con.unwrap(PhoenixConnection.class));
        final Map<TableRef,Map<ImmutableBytesPtr,MutationState.RowMutationState>> mutations = Maps.newTreeMap(new TableRefComparator());
        // passing a null mutation staate forces the connection.newMutationState() to be used to create the MutationState
        return new PhoenixConnection(phxCon, null) {
            @Override
            protected MutationState newMutationState(int maxSize) {
                return new MutationState(maxSize, this, mutations, null);
            };
        };
    }
    
    public static class FailingRegionObserver extends SimpleRegionObserver {
        @Override
        public void prePut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit,
                final Durability durability) throws HBaseIOException {
            if (shouldFail(c, put)) {
                // throwing anything other than instances of IOException result
                // in this coprocessor being unloaded
                // DoNotRetryIOException tells HBase not to retry this mutation
                // multiple times
                throw new DoNotRetryIOException();
            }
        }
        
        @Override
        public void preDelete(ObserverContext<RegionCoprocessorEnvironment> c,
                Delete delete, WALEdit edit, Durability durability) throws IOException {
            if (shouldFail(c, delete)) {
                // throwing anything other than instances of IOException result
                // in this coprocessor being unloaded
                // DoNotRetryIOException tells HBase not to retry this mutation
                // multiple times
                throw new DoNotRetryIOException();
            }
        }
        
        private boolean shouldFail(ObserverContext<RegionCoprocessorEnvironment> c, Mutation m) {
            String tableName = c.getEnvironment().getRegion().getRegionInfo().getTable().getNameAsString();
            // deletes on transactional tables are converted to put, so use a single helper method
            return tableName.contains(TABLE_NAME_TO_FAIL) && 
            		(Bytes.equals(ROW_TO_FAIL_UPSERT_BYTES, m.getRow()) || Bytes.equals(ROW_TO_FAIL_DELETE_BYTES, m.getRow()));
        }
        
    }
    
    /**
     * Used for ordering {@link MutationState#mutations} map.
     */
    private static class TableRefComparator implements Comparator<TableRef> {
        @Override
        public int compare(TableRef tr1, TableRef tr2) {
            return tr1.getTable().getPhysicalName().getString().compareTo(tr2.getTable().getPhysicalName().getString());
        }
    }
}
