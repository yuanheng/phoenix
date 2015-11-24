/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.jdbc;

import static java.util.Collections.emptyMap;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.text.Format;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.apache.hadoop.hbase.HConstants;
import org.apache.phoenix.call.CallRunner;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.CommitException;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.expression.function.FunctionArgumentType;
import org.apache.phoenix.hbase.index.util.KeyValueBuilder;
import org.apache.phoenix.iterate.ParallelIteratorFactory;
import org.apache.phoenix.jdbc.PhoenixStatement.PhoenixStatementParser;
import org.apache.phoenix.parse.PFunction;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.DelegateConnectionQueryServices;
import org.apache.phoenix.query.MetaDataMutated;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PMetaData;
import org.apache.phoenix.schema.PMetaData.Pruner;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableRef;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.schema.types.PArrayDataType;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDate;
import org.apache.phoenix.schema.types.PDecimal;
import org.apache.phoenix.schema.types.PTime;
import org.apache.phoenix.schema.types.PTimestamp;
import org.apache.phoenix.schema.types.PUnsignedDate;
import org.apache.phoenix.schema.types.PUnsignedTime;
import org.apache.phoenix.schema.types.PUnsignedTimestamp;
import org.apache.phoenix.trace.util.Tracing;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.JDBCUtil;
import org.apache.phoenix.util.NumberUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SQLCloseable;
import org.apache.phoenix.util.SQLCloseables;
import org.cloudera.htrace.Sampler;
import org.cloudera.htrace.TraceScope;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Lists;


/**
 * 
 * JDBC Connection implementation of Phoenix.
 * Currently the following are supported:
 * - Statement
 * - PreparedStatement
 * The connection may only be used with the following options:
 * - ResultSet.TYPE_FORWARD_ONLY
 * - Connection.TRANSACTION_READ_COMMITTED
 * 
 * 
 * @since 0.1
 */
public class PhoenixConnection implements Connection, org.apache.phoenix.jdbc.Jdbc7Shim.Connection, MetaDataMutated{
    private final String url;
    private final ConnectionQueryServices services;
    private final Properties info;
    private List<SQLCloseable> statements = new ArrayList<SQLCloseable>();
    private final Map<PDataType<?>, Format> formatters = new HashMap<>();
    private final MutationState mutationState;
    private final int mutateBatchSize;
    private final Long scn;
    private boolean isAutoCommit = false;
    private PMetaData metaData;
    private final PName tenantId;
    private final String datePattern; 
    private final String timePattern;
    private final String timestampPattern;
    private int statementExecutionCounter;
    private TraceScope traceScope = null;
    private boolean isClosed = false;
    private Sampler<?> sampler;
    private boolean readOnly = false;
    private Map<String, String> customTracingAnnotations = emptyMap();
    private final boolean isRequestLevelMetricsEnabled;
    private final boolean isDescVarLengthRowKeyUpgrade;
    private ParallelIteratorFactory parallelIteratorFactory;
    
    static {
        Tracing.addTraceMetricsSource();
    }

    private static Properties newPropsWithSCN(long scn, Properties props) {
        props = new Properties(props);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(scn));
        return props;
    }

    public PhoenixConnection(PhoenixConnection connection, boolean isDescRowKeyOrderUpgrade) throws SQLException {
        this(connection.getQueryServices(), connection.getURL(), connection.getClientInfo(), connection.metaData, connection.getMutationState(), isDescRowKeyOrderUpgrade);
        this.isAutoCommit = connection.isAutoCommit;
        this.sampler = connection.sampler;
        this.statementExecutionCounter = connection.statementExecutionCounter;
    }

    public PhoenixConnection(PhoenixConnection connection) throws SQLException {
        this(connection, connection.isDescVarLengthRowKeyUpgrade());
    }
    
    public PhoenixConnection(PhoenixConnection connection, MutationState mutationState) throws SQLException {
        this(connection.getQueryServices(), connection.getURL(), connection.getClientInfo(), connection.getMetaDataCache(), mutationState, connection.isDescVarLengthRowKeyUpgrade());
    }
    
    public PhoenixConnection(PhoenixConnection connection, long scn) throws SQLException {
        this(connection.getQueryServices(), connection, scn);
    }
    
    public PhoenixConnection(ConnectionQueryServices services, PhoenixConnection connection, long scn) throws SQLException {
        this(services, connection.getURL(), newPropsWithSCN(scn,connection.getClientInfo()), connection.metaData, connection.getMutationState(), connection.isDescVarLengthRowKeyUpgrade());
        this.isAutoCommit = connection.isAutoCommit;
        this.sampler = connection.sampler;
        this.statementExecutionCounter = connection.statementExecutionCounter;
    }
    
    public PhoenixConnection(ConnectionQueryServices services, String url, Properties info, PMetaData metaData) throws SQLException {
        this(services, url, info, metaData, null, false);
    }
    
    public PhoenixConnection(PhoenixConnection connection, ConnectionQueryServices services, Properties info) throws SQLException {
        this(services, connection.url, info, connection.metaData, null, connection.isDescVarLengthRowKeyUpgrade());
    }
    
    public PhoenixConnection(ConnectionQueryServices services, String url, Properties info, PMetaData metaData, MutationState mutationState, boolean isDescVarLengthRowKeyUpgrade) throws SQLException {
        this.url = url;
        this.isDescVarLengthRowKeyUpgrade = isDescVarLengthRowKeyUpgrade;
        // Copy so client cannot change
        this.info = info == null ? new Properties() : PropertiesUtil.deepCopy(info);
        final PName tenantId = JDBCUtil.getTenantId(url, info);
        if (this.info.isEmpty() && tenantId == null) {
            this.services = services;
        } else {
            // Create child services keyed by tenantId to track resource usage for
            // a tenantId for all connections on this JVM.
            if (tenantId != null) {
                services = services.getChildQueryServices(tenantId.getBytesPtr());
            }
            ReadOnlyProps currentProps = services.getProps();
            final ReadOnlyProps augmentedProps = currentProps.addAll(filterKnownNonProperties(this.info));
            this.services = augmentedProps == currentProps ? services : new DelegateConnectionQueryServices(services) {
                @Override
                public ReadOnlyProps getProps() {
                    return augmentedProps;
                }
            };
        }
        
        Long scnParam = JDBCUtil.getCurrentSCN(url, this.info);
        checkScn(scnParam);
        this.scn = scnParam;
        this.isAutoCommit = JDBCUtil.getAutoCommit(
                url, this.info,
                this.services.getProps().getBoolean(
                        QueryServices.AUTO_COMMIT_ATTRIB,
                        QueryServicesOptions.DEFAULT_AUTO_COMMIT));
        this.tenantId = tenantId;
        this.mutateBatchSize = JDBCUtil.getMutateBatchSize(url, this.info, this.services.getProps());
        datePattern = this.services.getProps().get(QueryServices.DATE_FORMAT_ATTRIB, DateUtil.DEFAULT_DATE_FORMAT);
        timePattern = this.services.getProps().get(QueryServices.TIME_FORMAT_ATTRIB, DateUtil.DEFAULT_TIME_FORMAT);
        timestampPattern = this.services.getProps().get(QueryServices.TIMESTAMP_FORMAT_ATTRIB, DateUtil.DEFAULT_TIMESTAMP_FORMAT);
        String numberPattern = this.services.getProps().get(QueryServices.NUMBER_FORMAT_ATTRIB, NumberUtil.DEFAULT_NUMBER_FORMAT);
        int maxSize = this.services.getProps().getInt(QueryServices.MAX_MUTATION_SIZE_ATTRIB,QueryServicesOptions.DEFAULT_MAX_MUTATION_SIZE);
        Format dateFormat = DateUtil.getDateFormatter(datePattern);
        Format timeFormat = DateUtil.getDateFormatter(timePattern);
        Format timestampFormat = DateUtil.getDateFormatter(timestampPattern);
        formatters.put(PDate.INSTANCE, dateFormat);
        formatters.put(PTime.INSTANCE, timeFormat);
        formatters.put(PTimestamp.INSTANCE, timestampFormat);
        formatters.put(PUnsignedDate.INSTANCE, dateFormat);
        formatters.put(PUnsignedTime.INSTANCE, timeFormat);
        formatters.put(PUnsignedTimestamp.INSTANCE, timestampFormat);
        formatters.put(PDecimal.INSTANCE, FunctionArgumentType.NUMERIC.getFormatter(numberPattern));
        // We do not limit the metaData on a connection less than the global one,
        // as there's not much that will be cached here.
        Pruner pruner = new Pruner() {

            @Override
            public boolean prune(PTable table) {
                long maxTimestamp = scn == null ? HConstants.LATEST_TIMESTAMP : scn;
                return (table.getType() != PTableType.SYSTEM && 
                        (  table.getTimeStamp() >= maxTimestamp || 
                         ! Objects.equal(tenantId, table.getTenantId())) );
            }
            
            @Override
            public boolean prune(PFunction function) {
                long maxTimestamp = scn == null ? HConstants.LATEST_TIMESTAMP : scn;
                return ( function.getTimeStamp() >= maxTimestamp ||
                         ! Objects.equal(tenantId, function.getTenantId()));
            }
        };
        this.isRequestLevelMetricsEnabled = JDBCUtil.isCollectingRequestLevelMetricsEnabled(url, info, this.services.getProps());
        this.mutationState = mutationState == null ? newMutationState(maxSize) : new MutationState(mutationState);
        this.metaData = metaData.pruneTables(pruner);
        this.metaData = metaData.pruneFunctions(pruner);
        this.services.addConnection(this);

        // setup tracing, if its enabled
        this.sampler = Tracing.getConfiguredSampler(this);
        this.customTracingAnnotations = getImmutableCustomTracingAnnotations();
    }
    
    private static void checkScn(Long scnParam) throws SQLException {
        if (scnParam != null && scnParam < 0) {
            throw new SQLExceptionInfo.Builder(SQLExceptionCode.INVALID_SCN).build().buildException();
        }
    }

    private static Properties filterKnownNonProperties(Properties info) {
        Properties prunedProperties = info;
        if (info.contains(PhoenixRuntime.CURRENT_SCN_ATTRIB)) {
            if (prunedProperties == info) {
                prunedProperties = PropertiesUtil.deepCopy(info);
            }
            prunedProperties.remove(PhoenixRuntime.CURRENT_SCN_ATTRIB);
        }
        if (info.contains(PhoenixRuntime.TENANT_ID_ATTRIB)) {
            if (prunedProperties == info) {
                prunedProperties = PropertiesUtil.deepCopy(info);
            }
            prunedProperties.remove(PhoenixRuntime.TENANT_ID_ATTRIB);
        }
        return prunedProperties;
    }

    private ImmutableMap<String, String> getImmutableCustomTracingAnnotations() {
    	Builder<String, String> result = ImmutableMap.builder();
    	result.putAll(JDBCUtil.getAnnotations(url, info));
    	if (getSCN() != null) {
    		result.put(PhoenixRuntime.CURRENT_SCN_ATTRIB, getSCN().toString());
    	}
    	if (getTenantId() != null) {
    		result.put(PhoenixRuntime.TENANT_ID_ATTRIB, getTenantId().getString());
    	}
    	return result.build();
    }

    public Sampler<?> getSampler() {
        return this.sampler;
    }
    
    public void setSampler(Sampler<?> sampler) throws SQLException {
        this.sampler = sampler;
    }

    public Map<String, String> getCustomTracingAnnotations() {
        return customTracingAnnotations;
    }

    public int executeStatements(Reader reader, List<Object> binds, PrintStream out) throws IOException, SQLException {
        int bindsOffset = 0;
        int nStatements = 0;
        PhoenixStatementParser parser = new PhoenixStatementParser(reader);
        try {
            while (true) {
                PhoenixPreparedStatement stmt = null;
                try {
                    stmt = new PhoenixPreparedStatement(this, parser);
                    ParameterMetaData paramMetaData = stmt.getParameterMetaData();
                    for (int i = 0; i < paramMetaData.getParameterCount(); i++) {
                        stmt.setObject(i+1, binds.get(bindsOffset+i));
                    }
                    long start = System.currentTimeMillis();
                    boolean isQuery = stmt.execute();
                    if (isQuery) {
                        ResultSet rs = stmt.getResultSet();
                        if (!rs.next()) {
                            if (out != null) {
                                out.println("no rows selected");
                            }
                        } else {
                            int columnCount = 0;
                            if (out != null) {
                                ResultSetMetaData md = rs.getMetaData();
                                columnCount = md.getColumnCount();
                                for (int i = 1; i <= columnCount; i++) {
                                    int displayWidth = md.getColumnDisplaySize(i);
                                    String label = md.getColumnLabel(i);
                                    if (md.isSigned(i)) {
                                        out.print(displayWidth < label.length() ? label.substring(0,displayWidth) : Strings.padStart(label, displayWidth, ' '));
                                        out.print(' ');
                                    } else {
                                        out.print(displayWidth < label.length() ? label.substring(0,displayWidth) : Strings.padEnd(md.getColumnLabel(i), displayWidth, ' '));
                                        out.print(' ');
                                    }
                                }
                                out.println();
                                for (int i = 1; i <= columnCount; i++) {
                                    int displayWidth = md.getColumnDisplaySize(i);
                                    out.print(Strings.padStart("", displayWidth,'-'));
                                    out.print(' ');
                                }
                                out.println();
                            }
                            do {
                                if (out != null) {
                                    ResultSetMetaData md = rs.getMetaData();
                                    for (int i = 1; i <= columnCount; i++) {
                                        int displayWidth = md.getColumnDisplaySize(i);
                                        String value = rs.getString(i);
                                        String valueString = value == null ? QueryConstants.NULL_DISPLAY_TEXT : value;
                                        if (md.isSigned(i)) {
                                            out.print(Strings.padStart(valueString, displayWidth, ' '));
                                        } else {
                                            out.print(Strings.padEnd(valueString, displayWidth, ' '));
                                        }
                                        out.print(' ');
                                    }
                                    out.println();
                                }
                            } while (rs.next());
                        }
                    } else if (out != null){
                        int updateCount = stmt.getUpdateCount();
                        if (updateCount >= 0) {
                            out.println((updateCount == 0 ? "no" : updateCount) + (updateCount == 1 ? " row " : " rows ") + stmt.getUpdateOperation().toString());
                        }
                    }
                    bindsOffset += paramMetaData.getParameterCount();
                    double elapsedDuration = ((System.currentTimeMillis() - start) / 1000.0);
                    out.println("Time: " + elapsedDuration + " sec(s)\n");
                    nStatements++;
                } finally {
                    if (stmt != null) {
                        stmt.close();
                    }
                }
            }
        } catch (EOFException e) {
        }
        return nStatements;
    }

    public @Nullable PName getTenantId() {
        return tenantId;
    }
    
    public Long getSCN() {
        return scn;
    }
    
    public int getMutateBatchSize() {
        return mutateBatchSize;
    }
    
    public PMetaData getMetaDataCache() {
        return metaData;
    }
    
    public PTable getTable(PTableKey key) throws TableNotFoundException {
    	return metaData.getTableRef(key).getTable();
    }
    
    public PTableRef getTableRef(PTableKey key) throws TableNotFoundException {
    	return metaData.getTableRef(key);
    }

    protected MutationState newMutationState(int maxSize) {
        return new MutationState(maxSize, this); 
    }
    
    public MutationState getMutationState() {
        return mutationState;
    }
    
    public String getDatePattern() {
        return datePattern;
    }
    
    public Format getFormatter(PDataType type) {
        return formatters.get(type);
    }
    
    public String getURL() {
        return url;
    }
    
    public ConnectionQueryServices getQueryServices() {
        return services;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
    }

    private void closeStatements() throws SQLException {
        List<SQLCloseable> statements = this.statements;
        // create new list to prevent close of statements
        // from modifying this list.
        this.statements = Lists.newArrayList();
        try {
            mutationState.rollback();
        } catch (SQLException e) {
            // ignore any exceptions while rolling back
        } finally {
            try {
                SQLCloseables.closeAll(statements);
            } finally {
                statements.clear();
            }
        }
    }
    
    @Override
    public void close() throws SQLException {
        if (isClosed) {
            return;
        }
        try {
            clearMetrics();
            try {
                if (traceScope != null) {
                    traceScope.close();
                }
                closeStatements();
            } finally {
                services.removeConnection(this);
            }
        } finally {
            isClosed = true;
        }
    }

    @Override
    public void commit() throws SQLException {
        CallRunner.run(new CallRunner.CallableThrowable<Void, SQLException>() {
            @Override
            public Void call() throws SQLException {
                mutationState.commit();
                return null;
            }
        }, Tracing.withTracing(this, "committing mutations"));
        statementExecutionCounter = 0;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    	PDataType arrayPrimitiveType = PDataType.fromSqlTypeName(typeName);
    	return PArrayDataType.instantiatePhoenixArray(arrayPrimitiveType, elements);
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Statement createStatement() throws SQLException {
        PhoenixStatement statement = new PhoenixStatement(this);
        statements.add(statement);
        return statement;
    }

    /**
     * Back-door way to inject processing into walking through a result set
     * @param statementFactory
     * @return PhoenixStatement
     * @throws SQLException
     */
    public PhoenixStatement createStatement(PhoenixStatementFactory statementFactory) throws SQLException {
        PhoenixStatement statement = statementFactory.newStatement(this);
        statements.add(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException();
        }
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLFeatureNotSupportedException();
        }
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return isAutoCommit;
    }

    @Override
    public String getCatalog() throws SQLException {
        return "";
    }

    @Override
    public Properties getClientInfo() throws SQLException { 
        // Defensive copy so client cannot change
        return new Properties(info);
    }

    @Override
    public String getClientInfo(String name) {
        return info.getProperty(name);
    }

    @Override
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return new PhoenixDatabaseMetaData(this);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return Collections.emptyMap();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return readOnly; 
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        // TODO: run query here or ping
        return !isClosed;
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        PhoenixPreparedStatement statement = new PhoenixPreparedStatement(this, sql);
        statements.add(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException();
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLFeatureNotSupportedException();
        }
        return prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void rollback() throws SQLException {
        CallRunner.run(new CallRunner.CallableThrowable<Void, SQLException>() {
            @Override
            public Void call() throws SQLException {
                mutationState.rollback();
                return null;
            }
        }, Tracing.withTracing(this, "rolling back"));
        statementExecutionCounter = 0;
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setAutoCommit(boolean isAutoCommit) throws SQLException {
        this.isAutoCommit = isAutoCommit;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        if (!this.getCatalog().equalsIgnoreCase(catalog)) {
            // allow noop calls to pass through.
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        this.readOnly=readOnly;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        if (level != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!iface.isInstance(this)) {
            throw new SQLExceptionInfo.Builder(SQLExceptionCode.CLASS_NOT_UNWRAPPABLE)
                .setMessage(this.getClass().getName() + " not unwrappable from " + iface.getName())
                .build().buildException();
        }
        return (T)this;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public String getSchema() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }
    
    @Override
    public PMetaData addTable(PTable table, long resolvedTime) throws SQLException {
        metaData = metaData.addTable(table, resolvedTime);
        //Cascade through to connectionQueryServices too
        getQueryServices().addTable(table, resolvedTime);
        return metaData;
    }
    
    @Override
    public PMetaData updateResolvedTimestamp(PTable table, long resolvedTime) throws SQLException {
    	metaData = metaData.updateResolvedTimestamp(table, resolvedTime);
    	//Cascade through to connectionQueryServices too
        getQueryServices().updateResolvedTimestamp(table, resolvedTime);
        return metaData;
    }
    
    @Override
    public PMetaData addFunction(PFunction function) throws SQLException {
        // TODO: since a connection is only used by one thread at a time,
        // we could modify this metadata in place since it's not shared.
        if (scn == null || scn > function.getTimeStamp()) {
            metaData = metaData.addFunction(function);
        }
        //Cascade through to connectionQueryServices too
        getQueryServices().addFunction(function);
        return metaData;
    }

    @Override
    public PMetaData addColumn(PName tenantId, String tableName, List<PColumn> columns, long tableTimeStamp, long tableSeqNum, boolean isImmutableRows, boolean isWalDisabled, boolean isMultitenant, boolean storeNulls, boolean isTransactional, long resolvedTime)
            throws SQLException {
        metaData = metaData.addColumn(tenantId, tableName, columns, tableTimeStamp, tableSeqNum, isImmutableRows, isWalDisabled, isMultitenant, storeNulls, isTransactional, resolvedTime);
        //Cascade through to connectionQueryServices too
        getQueryServices().addColumn(tenantId, tableName, columns, tableTimeStamp, tableSeqNum, isImmutableRows, isWalDisabled, isMultitenant, storeNulls, isTransactional, resolvedTime);
        return metaData;
    }

    @Override
    public PMetaData removeTable(PName tenantId, String tableName, String parentTableName, long tableTimeStamp) throws SQLException {
        metaData = metaData.removeTable(tenantId, tableName, parentTableName, tableTimeStamp);
        //Cascade through to connectionQueryServices too
        getQueryServices().removeTable(tenantId, tableName, parentTableName, tableTimeStamp);
        return metaData;
    }

    @Override
    public PMetaData removeFunction(PName tenantId, String functionName, long tableTimeStamp) throws SQLException {
        metaData = metaData.removeFunction(tenantId, functionName, tableTimeStamp);
        //Cascade through to connectionQueryServices too
        getQueryServices().removeFunction(tenantId, functionName, tableTimeStamp);
        return metaData;
    }

    @Override
    public PMetaData removeColumn(PName tenantId, String tableName, List<PColumn> columnsToRemove, long tableTimeStamp,
            long tableSeqNum, long resolvedTime) throws SQLException {
        metaData = metaData.removeColumn(tenantId, tableName, columnsToRemove, tableTimeStamp, tableSeqNum, resolvedTime);
        //Cascade through to connectionQueryServices too
        getQueryServices().removeColumn(tenantId, tableName, columnsToRemove, tableTimeStamp, tableSeqNum, resolvedTime);
        return metaData;
    }

    protected boolean removeStatement(PhoenixStatement statement) throws SQLException {
        return statements.remove(statement);
   }

    public KeyValueBuilder getKeyValueBuilder() {
        return this.services.getKeyValueBuilder();
    }
    
    /**
     * Used to track executions of {@link Statement}s and {@link PreparedStatement}s that were created from this connection before
     * commit or rollback. 0-based. Used to associate partial save errors with SQL statements
     * invoked by users.
     * @see CommitException
     * @see #incrementStatementExecutionCounter()
     */
    public int getStatementExecutionCounter() {
		return statementExecutionCounter;
	}
    
    public void incrementStatementExecutionCounter() {
        statementExecutionCounter++;
    }

    public TraceScope getTraceScope() {
        return traceScope;
    }

    public void setTraceScope(TraceScope traceScope) {
        this.traceScope = traceScope;
    }
    
    public Map<String, Map<String, Long>> getMutationMetrics() {
        return mutationState.getMutationMetricQueue().aggregate();
    }
    
    public Map<String, Map<String, Long>> getReadMetrics() {
        return mutationState.getReadMetricQueue() != null ? mutationState.getReadMetricQueue().aggregate() : Collections.<String, Map<String, Long>>emptyMap();
    }
    
    public boolean isRequestLevelMetricsEnabled() {
        return isRequestLevelMetricsEnabled;
    }
    
    public void clearMetrics() {
        mutationState.getMutationMetricQueue().clearMetrics();
        if (mutationState.getReadMetricQueue() != null) {
            mutationState.getReadMetricQueue().clearMetrics();
        }
    }

    /**
     * Returns true if this connection is being used to upgrade the
     * data due to PHOENIX-2067 and false otherwise.
     * @return
     */
    public boolean isDescVarLengthRowKeyUpgrade() {
        return isDescVarLengthRowKeyUpgrade;
    }
    
    /**
     * Added for tests only. Do not use this elsewhere.
     */
    public ParallelIteratorFactory getIteratorFactory() {
        return parallelIteratorFactory;
    }
    
    /**
     * Added for testing purposes. Do not use this elsewhere.
     */
    public void setIteratorFactory(ParallelIteratorFactory parallelIteratorFactory) {
        this.parallelIteratorFactory = parallelIteratorFactory;
    }
}
