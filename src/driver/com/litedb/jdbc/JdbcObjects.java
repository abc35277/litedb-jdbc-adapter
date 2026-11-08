package com.litedb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdbcObjects {
    private static final Pattern SELECT_STAR_PATTERN = Pattern.compile(
            "^\\s*select\\s+\\*\\s+from\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT_TOP_PATTERN = Pattern.compile(
            "^\\s*select\\s+top\\s+(\\d+)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SQLITE_LIMIT_PATTERN = Pattern.compile(
            "(?i)\\blimit\\s+(\\d+)\\s*,\\s*(\\d+)(\\s*;?\\s*)$");
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?i)\\blimit\\s+\\d+\\b");
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile(
            "[$_\\p{L}][$_\\p{L}\\p{N}]*");
    private static final Pattern DELETE_FROM_PATTERN = Pattern.compile(
            "(?is)^\\s*delete\\s+from\\s+([^\\s;]+)\\s*(.*)$");
    private static final Pattern IS_NULL_PATTERN = Pattern.compile(
            "(?i)\\bis\\s+(not\\s+)?null\\b");

    private JdbcObjects() {
    }

    static Connection newConnection(LiteDbConfig config, BridgeClient client) {
        ConnectionState state = new ConnectionState(config, client);
        Connection proxy = proxy(Connection.class, new ConnectionHandler(state));
        state.proxy = proxy;
        return proxy;
    }

    private static Statement newStatement(ConnectionState state) {
        StatementHandler handler = new StatementHandler(state, null);
        Statement proxy = proxy(Statement.class, handler);
        handler.statementProxy = proxy;
        return proxy;
    }

    private static PreparedStatement newPreparedStatement(ConnectionState state, String sql) {
        StatementHandler handler = new StatementHandler(state, sql);
        PreparedStatement proxy = proxy(PreparedStatement.class, handler);
        handler.statementProxy = proxy;
        return proxy;
    }

    private static DatabaseMetaData newDatabaseMetaData(ConnectionState state) {
        return proxy(DatabaseMetaData.class, new DatabaseMetaDataHandler(state));
    }

    private static ResultSet newResultSet(QueryResult result) {
        return proxy(ResultSet.class, new ResultSetHandler(result, null));
    }

    private static ResultSet newResultSet(QueryResult result, StatementHandler owner) {
        return proxy(ResultSet.class, new ResultSetHandler(result, owner));
    }

    private static ResultSetMetaData newResultSetMetaData(QueryResult result) {
        return proxy(ResultSetMetaData.class, new ResultSetMetaDataHandler(result.columns));
    }

    private static ParameterMetaData newParameterMetaData(int count) {
        return proxy(ParameterMetaData.class, new ParameterMetaDataHandler(count));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
        return type.cast(value);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, Object target) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return target.toString();
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return proxy == args[0];
        }
        return null;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0f;
        }
        if (returnType == Double.TYPE) {
            return 0d;
        }
        return null;
    }

    private static SQLException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message);
    }

    private static final class ConnectionState {
        final LiteDbConfig config;
        BridgeClient client;
        final Properties clientInfo = new Properties();
        Connection proxy;
        volatile boolean closed;
        volatile boolean autoCommit = true;
        volatile boolean transactionActive;
        volatile boolean readOnly;
        volatile int networkTimeout;
        SQLWarning firstWarning;
        SQLWarning lastWarning;

        void addWarning(String message) {
            SQLWarning warning = new SQLWarning(message, "01000");
            synchronized (this) {
                if (firstWarning == null) {
                    firstWarning = warning;
                } else if (lastWarning != null) {
                    lastWarning.setNextWarning(warning);
                }
                lastWarning = warning;
            }
        }

        ConnectionState(LiteDbConfig config, BridgeClient client) {
            this.config = config;
            this.client = client;
            this.readOnly = config.readOnly;
        }

        void checkOpen() throws SQLException {
            if (closed) {
                throw new SQLException("Connection is closed.", "08003");
            }
        }

        /**
         * Routes a request through the bridge, transparently reopening a fresh
         * bridge process after a query timeout destroyed the hung one. Recovery is
         * only allowed while no transaction is active: a timeout inside a
         * transaction terminates the connection because the transaction is lost.
         */
        Object request(String method, Map<String, Object> parameters, long timeoutMillis) throws SQLException {
            checkOpen();
            try {
                return client.request(method, parameters, timeoutMillis);
            } catch (SQLException ex) {
                if ("HYT01".equals(ex.getSQLState()) && !transactionActive) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // The old process is being torn down anyway.
                    }
                    client = BridgeClient.open(config);
                    return client.request(method, parameters, timeoutMillis);
                }
                throw ex;
            }
        }

        BridgeClient.StreamedResult requestStream(String method, Map<String, Object> parameters, long timeoutMillis)
                throws SQLException {
            checkOpen();
            try {
                return client.requestStream(method, parameters, timeoutMillis);
            } catch (SQLException ex) {
                if ("HYT01".equals(ex.getSQLState()) && !transactionActive) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // The old process is being torn down anyway.
                    }
                    client = BridgeClient.open(config);
                    return client.requestStream(method, parameters, timeoutMillis);
                }
                throw ex;
            }
        }

        void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                client.close();
            } catch (IOException ex) {
                throw new SQLException("Failed to close LiteDB bridge: " + ex.getMessage(), "08006", ex);
            }
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final ConnectionState state;

        ConnectionHandler(ConnectionState state) {
            this.state = state;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "LiteDB connection " + state.config.databasePath);
            }

            try {
                return invokeConnection(proxy, method, args);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC connection error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
            synchronized (state) {
                return invokeConnectionLocked(proxy, method, args);
            }
        }

        private Object invokeConnectionLocked(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                state.close();
                return null;
            }
            if ("isClosed".equals(name)) {
                return state.closed;
            }
            if ("isValid".equals(name)) {
                int timeoutSeconds = (Integer) args[0];
                if (timeoutSeconds < 0) {
                    throw new SQLException("Illegal timeout value: " + timeoutSeconds, "HY000");
                }
                if (state.closed) {
                    return false;
                }
                try {
                    state.request("ping", Map.of(), timeoutSeconds * 1000L);
                    return true;
                } catch (SQLException ex) {
                    return false;
                }
            }

            state.checkOpen();

            if ("createStatement".equals(name)) {
                StatementHandler handler = new StatementHandler(state, null);
                if (args != null && args.length > 0 && args[0] instanceof Integer) {
                    handler.setResultSetType((Integer) args[0]);
                }
                Statement statement = proxy(Statement.class, handler);
                handler.statementProxy = statement;
                return statement;
            }
            if ("prepareStatement".equals(name)) {
                StatementHandler handler = new StatementHandler(state, (String) args[0]);
                if (args != null && args.length >= 3 && args[1] instanceof Integer) {
                    handler.setResultSetType((Integer) args[1]);
                }
                PreparedStatement prepared = proxy(PreparedStatement.class, handler);
                handler.statementProxy = prepared;
                return prepared;
            }
            if ("getMetaData".equals(name)) {
                return newDatabaseMetaData(state);
            }
            if ("nativeSQL".equals(name)) {
                return args[0];
            }
            if ("setAutoCommit".equals(name)) {
                boolean autoCommit = (Boolean) args[0];
                if (state.autoCommit && !autoCommit) {
                    state.client.request("begin", Map.of());
                    state.transactionActive = true;
                } else if (!state.autoCommit && autoCommit) {
                    try {
                        state.client.request("commit", Map.of());
                    } catch (SQLException ex) {
                        state.client.request("rollback", Map.of());
                        throw ex;
                    } finally {
                        state.transactionActive = false;
                    }
                }
                state.autoCommit = autoCommit;
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return state.autoCommit;
            }
            if ("commit".equals(name)) {
                if (state.autoCommit) {
                    throw new SQLException("Cannot commit in auto-commit mode.", "25000");
                }
                state.client.request("commit", Map.of());
                state.client.request("begin", Map.of());
                state.transactionActive = true;
                return null;
            }
            if ("rollback".equals(name) && (args == null || args.length == 0)) {
                if (state.autoCommit) {
                    throw new SQLException("Cannot roll back in auto-commit mode.", "25000");
                }
                state.client.request("rollback", Map.of());
                state.client.request("begin", Map.of());
                state.transactionActive = true;
                return null;
            }
            if ("setSavepoint".equals(name) || "releaseSavepoint".equals(name)
                    || "rollback".equals(name)) {
                throw unsupported("Savepoints are not supported by LiteDB.");
            }
            if ("setReadOnly".equals(name)) {
                if (state.transactionActive) {
                    throw new SQLException("Cannot set read-only during a transaction.", "25000");
                }
                state.readOnly = (Boolean) args[0];
                return null;
            }
            if ("isReadOnly".equals(name)) {
                return state.readOnly;
            }
            if ("getCatalog".equals(name)) {
                return "main";
            }
            if ("setCatalog".equals(name) || "setSchema".equals(name)) {
                return null;
            }
            if ("clearWarnings".equals(name)) {
                state.firstWarning = null;
                state.lastWarning = null;
                return null;
            }
            if ("getSchema".equals(name)) {
                return null;
            }
            if ("getWarnings".equals(name)) {
                return state.firstWarning;
            }
            if ("getTransactionIsolation".equals(name)) {
                return Connection.TRANSACTION_SERIALIZABLE;
            }
            if ("setTransactionIsolation".equals(name)) {
                int level = (Integer) args[0];
                if (level == Connection.TRANSACTION_NONE) {
                    throw new SQLException("TRANSACTION_NONE cannot be used.", "HY000");
                }
                if (level != Connection.TRANSACTION_SERIALIZABLE) {
                    throw new SQLException(
                            "Only TRANSACTION_SERIALIZABLE is supported; requested level " + level + ".", "HY000");
                }
                return null;
            }
            if ("getHoldability".equals(name)) {
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            if ("setHoldability".equals(name)) {
                return null;
            }
            if ("getTypeMap".equals(name)) {
                return new HashMap<String, Class<?>>();
            }
            if ("setTypeMap".equals(name)) {
                return null;
            }
            if ("setClientInfo".equals(name)) {
                if (args.length == 2) {
                    state.clientInfo.setProperty((String) args[0], String.valueOf(args[1]));
                    state.addWarning("Client info property '" + args[0] + "' is not recognized by this driver and is stored only.");
                } else if (args[0] instanceof Properties) {
                    state.clientInfo.putAll((Properties) args[0]);
                    state.addWarning("Client info properties are stored but not recognized by this driver.");
                }
                return null;
            }
            if ("getClientInfo".equals(name)) {
                if (args == null || args.length == 0) {
                    return state.clientInfo;
                }
                return state.clientInfo.getProperty((String) args[0]);
            }
            if ("abort".equals(name)) {
                if (args == null || args.length == 0 || args[0] == null) {
                    throw new SQLException("Connection.abort requires a non-null Executor.", "HY000");
                }
                state.close();
                return null;
            }
            if ("setNetworkTimeout".equals(name)) {
                int milliseconds = (Integer) args[1];
                if (milliseconds < 0) {
                    throw new SQLException("Illegal network timeout: " + milliseconds, "HY000");
                }
                state.networkTimeout = milliseconds;
                return null;
            }
            if ("getNetworkTimeout".equals(name)) {
                return state.networkTimeout;
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }
            if ("prepareCall".equals(name)) {
                throw unsupported("Stored procedures are not supported by LiteDB.");
            }

            return defaultValue(method.getReturnType());
        }
    }

    private static final class StatementHandler implements InvocationHandler {
        private final ConnectionState state;
        private final String preparedSql;
        private final List<Object> parameters = new ArrayList<>();
        private final Set<Integer> boundParameters = new HashSet<>();
        private Statement statementProxy;
        private boolean closed;
        private int maxRows;
        private boolean maxRowsSet;
        private int fetchSize;
        private int maxFieldSize;
        private int queryTimeoutSeconds;
        private int requestedResultSetType = ResultSet.TYPE_FORWARD_ONLY;

        void setResultSetType(int type) {
            if (type != ResultSet.TYPE_FORWARD_ONLY && type != ResultSet.TYPE_SCROLL_INSENSITIVE) {
                throw new IllegalArgumentException("Unsupported result set type: " + type);
            }
            requestedResultSetType = type;
        }
        private boolean closeOnCompletion;
        private boolean executed;
        private boolean lastWasUpdate;
        private long lastUpdateCount;
        // Compiled SQL form for prepared statements (all call sites run under
        // synchronized(state), so plain lazy fields are safe). Plain statements have
        // varying SQL and are never cached.
        private String cachedNormalizedSql;
        private SqlParameterBinder.Rewrite cachedRewrite;
        private boolean cachedInsert;
        private boolean cachedProducesRows;

        private SqlParameterBinder.Rewrite rewriteOfPrepared() {
            if (cachedRewrite == null) {
                cachedNormalizedSql = rewriteCommonSql(preparedSql);
                cachedRewrite = SqlParameterBinder.rewrite(cachedNormalizedSql);
                cachedInsert = "insert".equals(firstSqlKeyword(preparedSql));
                cachedProducesRows = statementProducesRows(preparedSql);
            }
            return cachedRewrite;
        }

        private boolean producesRowsOf(String sql) {
            if (preparedSql != null) {
                rewriteOfPrepared();
                return cachedProducesRows;
            }
            return statementProducesRows(sql);
        }
        private QueryResult currentResult;
        private ResultSet currentResultSet;
        private final List<BatchEntry> batch = new ArrayList<>();

        StatementHandler(ConnectionState state, String preparedSql) {
            this.state = state;
            this.preparedSql = preparedSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, preparedSql == null ? "LiteDB statement" : "LiteDB prepared statement");
            }

            String name = method.getName();
            // cancel() must stay lock-free: it is called from another thread while an
            // execute* call may be blocked waiting on the bridge.
            if ("cancel".equals(name)) {
                state.client.cancelActiveRequest();
                return null;
            }
            if ("setQueryTimeout".equals(name)) {
                int seconds = (Integer) args[0];
                if (seconds < 0) {
                    throw new SQLException("Illegal query timeout: " + seconds, "HY000");
                }
                queryTimeoutSeconds = seconds;
                return null;
            }

            try {
                return invokeStatement(proxy, method, args, name);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC statement error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeStatement(Object proxy, Method method, Object[] args, String name) throws SQLException {
            synchronized (state) {
                return invokeStatementLocked(proxy, method, args, name);
            }
        }

        private Object invokeStatementLocked(Object proxy, Method method, Object[] args, String name) throws SQLException {
            if ("close".equals(name)) {
                closeInternal();
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }

            checkOpen();

            if ("executeQuery".equals(name)) {
                return executeQuery(args);
            }
            if ("execute".equals(name)) {
                if (preparedSql != null && args != null && args.length > 0 && args[0] instanceof String) {
                    throw new SQLException("SQL may not be passed to a PreparedStatement's execute method.", "HY000");
                }
                executeInternal(resolveSql(args), resolveParameters(args));
                return currentResultSet != null;
            }
            if ("executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
                if (preparedSql != null && args != null && args.length > 0 && args[0] instanceof String) {
                    throw new SQLException("SQL may not be passed to a PreparedStatement's executeUpdate method.", "HY000");
                }
                String sql = resolveSql(args);
                if (producesRowsOf(sql)) {
                    throw new SQLException("Statement produces a result set; use executeQuery: " + preview(sql), "HY000");
                }
                executeInternal(sql, resolveParameters(args));
                if (method.getReturnType() == Long.TYPE) {
                    return lastUpdateCount;
                }
                return (int) Math.min(lastUpdateCount, Integer.MAX_VALUE);
            }
            if ("getResultSet".equals(name)) {
                return currentResultSet;
            }
            if ("getUpdateCount".equals(name)) {
                if (!executed) {
                    return -1;
                }
                return lastWasUpdate ? (int) Math.min(lastUpdateCount, Integer.MAX_VALUE) : -1;
            }
            if ("getLargeUpdateCount".equals(name)) {
                if (!executed) {
                    return -1L;
                }
                return lastWasUpdate ? lastUpdateCount : -1L;
            }
            if ("getMoreResults".equals(name)) {
                closeCurrentResultSet();
                executed = false;
                lastWasUpdate = false;
                return false;
            }
            if ("getConnection".equals(name)) {
                return state.proxy;
            }
            if ("getStatement".equals(name)) {
                return statementProxy;
            }
            if ("setMaxRows".equals(name)) {
                int value = (Integer) args[0];
                if (value < 0) {
                    throw new SQLException("Illegal maxRows value: " + value, "HY000");
                }
                maxRows = value;
                maxRowsSet = true;
                return null;
            }
            if ("getMaxRows".equals(name)) {
                return effectiveMaxRows();
            }
            if ("setLargeMaxRows".equals(name)) {
                long value = (Long) args[0];
                if (value < 0) {
                    throw new SQLException("Illegal maxRows value: " + value, "HY000");
                }
                maxRows = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
                maxRowsSet = true;
                return null;
            }
            if ("getLargeMaxRows".equals(name)) {
                return maxRowsSet ? (long) maxRows : (long) state.config.defaultMaxRows;
            }
            if ("setFetchSize".equals(name)) {
                int value = (Integer) args[0];
                if (value < 0) {
                    throw new SQLException("Illegal fetch size: " + value, "HY000");
                }
                fetchSize = value;
                return null;
            }
            if ("setEscapeProcessing".equals(name) || "setCursorName".equals(name)) {
                return null;
            }
            if ("setMaxFieldSize".equals(name)) {
                int value = (Integer) args[0];
                if (value < 0) {
                    throw new SQLException("Illegal max field size: " + value, "HY000");
                }
                maxFieldSize = value;
                return null;
            }
            if ("getFetchSize".equals(name)) {
                return fetchSize;
            }
            if ("getMaxFieldSize".equals(name)) {
                return maxFieldSize;
            }
            if ("getFetchDirection".equals(name)) {
                return ResultSet.FETCH_FORWARD;
            }
            if ("setFetchDirection".equals(name) || "clearWarnings".equals(name)
                    || "setPoolable".equals(name)) {
                return null;
            }
            if ("getQueryTimeout".equals(name)) {
                return queryTimeoutSeconds;
            }
            if ("getWarnings".equals(name)) {
                return null;
            }
            if ("getResultSetType".equals(name)) {
                return requestedResultSetType;
            }
            if ("getResultSetConcurrency".equals(name)) {
                return ResultSet.CONCUR_UPDATABLE;
            }
            if ("getResultSetHoldability".equals(name)) {
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            if ("isPoolable".equals(name)) {
                return false;
            }
            if ("closeOnCompletion".equals(name)) {
                closeOnCompletion = true;
                return null;
            }
            if ("isCloseOnCompletion".equals(name)) {
                return closeOnCompletion;
            }
            if ("getGeneratedKeys".equals(name)) {
                // Honest contract: supportsGetGeneratedKeys reports false, so requesting
                // generated keys is a feature request that must fail (JDBC spec).
                throw unsupported("Generated keys are not supported by LiteDB.");
            }
            if ("clearParameters".equals(name)) {
                parameters.clear();
                boundParameters.clear();
                return null;
            }
            if ("setNull".equals(name)) {
                setParameter((Integer) args[0], null);
                return null;
            }
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                setParameter((Integer) args[0], convertSetValue(name, args));
                return null;
            }
            if ("getParameterMetaData".equals(name)) {
                return newParameterMetaData(rewriteOfPrepared().parameterOrder.size());
            }
            if ("addBatch".equals(name)) {
                addBatch(args);
                return null;
            }
            if ("clearBatch".equals(name)) {
                batch.clear();
                return null;
            }
            if ("executeBatch".equals(name)) {
                return executeBatch();
            }
            if ("executeLargeBatch".equals(name)) {
                int[] counts = executeBatch();
                long[] largeCounts = new long[counts.length];
                for (int i = 0; i < counts.length; i++) {
                    largeCounts[i] = counts[i];
                }
                return largeCounts;
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }

            return defaultValue(method.getReturnType());
        }

        private ResultSet executeQuery(Object[] args) throws SQLException {
            if (preparedSql != null && args != null && args.length > 0 && args[0] instanceof String) {
                throw new SQLException("SQL may not be passed to a PreparedStatement's executeQuery method.", "HY000");
            }
            String sql = resolveSql(args);
            if (!producesRowsOf(sql)) {
                throw new SQLException("Statement does not produce a result set; use executeUpdate: " + preview(sql), "HY000");
            }
            executeInternal(sql, resolveParameters(args));
            return currentResultSet;
        }

        private void executeInternal(String sql, List<Object> values) throws SQLException {
            closeCurrentResultSet();
            String normalizedSql;
            boolean insert;
            if (preparedSql != null) {
                // Prepared statements reuse their compiled form across executions.
                rewriteOfPrepared();
                normalizedSql = cachedNormalizedSql;
                insert = cachedInsert;
            } else {
                normalizedSql = rewriteCommonSql(sql);
                insert = "insert".equals(firstSqlKeyword(sql));
            }
            String bridgeSql;
            List<Object> bridgeParameters;
            if (insert && !values.isEmpty()) {
                // LiteDB 5.0.21 rejects @N placeholders inside INSERT (Unexpected token '@'),
                // so parameterized INSERT inlines extended-JSON literals in place of '?'.
                bridgeSql = SqlParameterBinder.inlineParameters(normalizedSql, normalizeParameters(values));
                bridgeParameters = Collections.emptyList();
            } else {
                bridgeSql = (preparedSql != null ? rewriteOfPrepared() : SqlParameterBinder.rewrite(normalizedSql)).sql;
                bridgeParameters = normalizeParameters(values);
            }

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("sql", bridgeSql);
            request.put("parameters", bridgeParameters);
            request.put("maxRows", effectiveMaxRows());
            if (fetchSize > 0) {
                // Tools like DBeaver set fetchSize to their page size; use it as the
                // bridge's chunk granularity so stream batches match the reader.
                request.put("chunkSize", fetchSize);
            }
            executed = true;
            if (producesRowsOf(sql)) {
                BridgeClient.StreamedResult streamed = state.requestStream("execute", request, requestTimeoutMillis());
                currentResult = QueryResult.fromStream(state.client, streamed,
                        requestedResultSetType != ResultSet.TYPE_FORWARD_ONLY, requestTimeoutMillis());
                currentResultSet = newResultSet(currentResult, this);
                lastWasUpdate = false;
            } else {
                currentResult = null;
                currentResultSet = null;
                lastWasUpdate = true;
                lastUpdateCount = drainUpdateCount(request);
            }
        }

        /** DML responses stream too; drain the chunks and surface the affected count. */
        private long drainUpdateCount(Map<String, Object> request) throws SQLException {
            BridgeClient.StreamedResult streamed = state.requestStream("execute", request, requestTimeoutMillis());
            long count = 0;
            while (true) {
                Object chunk = state.client.pollChunk(streamed.collector, requestTimeoutMillis());
                if (chunk == null) {
                    return count;
                }
                count = extractUpdateCount(chunk, count);
            }
        }

        /**
         * LiteDB's Execute reports the affected document count for DML as the first
         * row ([[n]] with a "value" column); surface it as the JDBC update count
         * instead of discarding it.
         */
        private static long extractUpdateCount(Object chunk, long fallback) {
            try {
                List<Object> rows = asList(asMap(chunk).get("rows"));
                if (!rows.isEmpty()) {
                    List<Object> first = asList(rows.get(0));
                    if (!first.isEmpty() && first.get(0) instanceof Number) {
                        return Math.max(0L, ((Number) first.get(0)).longValue());
                    }
                }
            } catch (SQLException ignored) {
                // Malformed envelope: fall back to the previous count.
            }
            return fallback;
        }

        private String resolveSql(Object[] args) {
            if (preparedSql != null && (args == null || args.length == 0 || !(args[0] instanceof String))) {
                return preparedSql;
            }
            return (String) args[0];
        }

        private List<Object> resolveParameters(Object[] args) throws SQLException {
            if (preparedSql == null || (args != null && args.length > 0 && args[0] instanceof String)) {
                return Collections.emptyList();
            }
            SqlParameterBinder.Rewrite rewrite = rewriteOfPrepared();
            int count = rewrite.parameterOrder.size();
            List<Object> values = new ArrayList<>();
            for (int occurrence = 0; occurrence < count; occurrence++) {
                if (!boundParameters.contains(occurrence)) {
                    throw new SQLException("Parameter " + (occurrence + 1) + " has not been set.", "HY000");
                }
                values.add(occurrence < parameters.size() ? parameters.get(occurrence) : null);
            }
            return values;
        }

        /** Normalizes stream parameters and honors setObject's target SQL type. */
        private Object convertSetValue(String name, Object[] args) throws SQLException {
            Object value = args[1];
            if (value instanceof java.io.Reader) {
                return readReaderFully((java.io.Reader) value);
            }
            if (value instanceof java.io.InputStream) {
                boolean binary = name.toLowerCase(Locale.ROOT).contains("binary");
                return readStreamFully((java.io.InputStream) value, binary);
            }
            if ("setObject".equals(name) && args.length >= 3 && args[2] instanceof Integer) {
                return convertForSqlType(value, (Integer) args[2]);
            }
            return value;
        }

        private static Object convertForSqlType(Object value, int sqlType) throws SQLException {
            try {
                if (value == null) {
                    return null;
                }
                switch (sqlType) {
                    case Types.TIMESTAMP:
                        return value instanceof String ? toTimestamp(value) : value;
                    case Types.DATE:
                        return value instanceof String ? new Date(toTimestamp(value).getTime()) : value;
                    case Types.TIME:
                        return value instanceof String ? new Time(toTimestamp(value).getTime()) : value;
                    case Types.DECIMAL:
                    case Types.NUMERIC:
                        return value instanceof String || value instanceof Number
                                ? new BigDecimal(String.valueOf(value))
                                : value;
                    case Types.INTEGER:
                        if (value instanceof Number) {
                            return ((Number) value).intValue();
                        }
                        return value instanceof String ? Integer.parseInt((String) value) : value;
                    case Types.BIGINT:
                        if (value instanceof Number) {
                            return ((Number) value).longValue();
                        }
                        return value instanceof String ? Long.parseLong((String) value) : value;
                    case Types.DOUBLE:
                    case Types.FLOAT:
                        if (value instanceof Number) {
                            return ((Number) value).doubleValue();
                        }
                        return value instanceof String ? Double.parseDouble((String) value) : value;
                    case Types.BOOLEAN:
                    case Types.BIT:
                        return value instanceof String ? Boolean.parseBoolean((String) value) : value;
                    default:
                        return value;
                }
            } catch (RuntimeException ex) {
                throw new SQLException(
                        "Cannot convert parameter to SQL type " + sqlType + ": " + ex.getMessage(), "HY000", ex);
            }
        }

        private void setParameter(int index, Object value) {
            while (parameters.size() < index) {
                parameters.add(null);
            }
            parameters.set(index - 1, value);
            boundParameters.add(index - 1);
        }

        private long requestTimeoutMillis() {
            if (queryTimeoutSeconds > 0) {
                return queryTimeoutSeconds * 1000L;
            }
            return state.networkTimeout;
        }

        private int effectiveMaxRows() {
            return maxRowsSet ? maxRows : state.config.defaultMaxRows;
        }

        void resultSetClosed() {
            if (closeOnCompletion) {
                closeInternal();
            }
        }

        private void closeInternal() {
            if (closed) {
                return;
            }
            closed = true;
            closeCurrentResultSet();
            batch.clear();
        }

        private void closeCurrentResultSet() {
            if (currentResultSet != null) {
                try {
                    currentResultSet.close();
                } catch (SQLException ignored) {
                    // The result set is being discarded anyway.
                }
                currentResultSet = null;
            }
            currentResult = null;
        }

        private static boolean statementProducesRows(String sql) {
            String keyword = firstSqlKeyword(sql);
            return "select".equals(keyword) || "explain".equals(keyword);
        }

        private static String firstSqlKeyword(String sql) {
            if (sql == null) {
                return "";
            }
            int index = 0;
            int length = sql.length();
            while (index < length) {
                while (index < length && Character.isWhitespace(sql.charAt(index))) {
                    index++;
                }
                if (index + 1 < length && sql.charAt(index) == '-' && sql.charAt(index + 1) == '-') {
                    while (index < length && sql.charAt(index) != '\n') {
                        index++;
                    }
                    continue;
                }
                if (index + 1 < length && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                    index += 2;
                    while (index + 1 < length && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                        index++;
                    }
                    index = Math.min(length, index + 2);
                    continue;
                }
                break;
            }
            int start = index;
            while (index < length && Character.isLetter(sql.charAt(index))) {
                index++;
            }
            return sql.substring(start, index).toLowerCase(Locale.ROOT);
        }

        private static String preview(String sql) {
            if (sql == null) {
                return "";
            }
            String trimmed = sql.strip();
            return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "...";
        }

        private void addBatch(Object[] args) throws SQLException {
            batch.add(new BatchEntry(resolveSql(args), new ArrayList<>(resolveParameters(args))));
        }

        private int[] executeBatch() throws SQLException {
            int[] counts = new int[batch.size()];
            List<Integer> completed = new ArrayList<>();
            try {
                for (int i = 0; i < batch.size(); i++) {
                    BatchEntry entry = batch.get(i);
                    executeInternal(entry.sql, entry.parameters);
                    completed.add(0);
                }
            } catch (SQLException ex) {
                batch.clear();
                int[] done = new int[completed.size()];
                for (int i = 0; i < done.length; i++) {
                    done[i] = completed.get(i);
                }
                throw new BatchUpdateException(ex.getMessage(), ex.getSQLState(), done, ex);
            }
            batch.clear();
            return counts;
        }

        private void checkOpen() throws SQLException {
            state.checkOpen();
            if (closed) {
                throw new SQLException("Statement is closed.");
            }
        }
    }

    private static final class BatchEntry {
        final String sql;
        final List<Object> parameters;

        BatchEntry(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final QueryResult result;
        private final StatementHandler owner;
        private int row = -1;
        private int lastColumnIndex;
        private boolean closed;
        private boolean lastWasNull;
        private boolean onInsertRow;
        private final Map<Integer, Object> updateBuffer = new LinkedHashMap<>();
        private final Map<Integer, Object> insertBuffer = new LinkedHashMap<>();

        ResultSetHandler(QueryResult result, StatementHandler owner) {
            this.result = result;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "LiteDB result set");
            }

            String name = method.getName();
            if ("close".equals(name)) {
                if (!closed) {
                    closed = true;
                    if (owner != null) {
                        owner.resultSetClosed();
                    }
                }
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }

            try {
                return invokeResultSet(proxy, method, args, name);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC result set error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeResultSet(Object proxy, Method method, Object[] args, String name) throws SQLException {
            synchronized (this) {
                return invokeResultSetLocked(proxy, method, args, name);
            }
        }

        private Object invokeResultSetLocked(Object proxy, Method method, Object[] args, String name) throws SQLException {
            checkOpen();

            if ("next".equals(name)) {
                if (row + 1 < result.rows.size()) {
                    row++;
                    return true;
                }
                if (result.needsFetch()) {
                    // Position after EVERY absorbed chunk: the final (done) chunk still
                    // carries rows, so the loop must re-check the row index each pass.
                    while (true) {
                        boolean more = result.fetchMore();
                        if (row + 1 < result.rows.size()) {
                            row++;
                            return true;
                        }
                        if (!more) {
                            break;
                        }
                    }
                }
                row = result.rows.size();
                return false;
            }
            // Hot path: name-based getters sit right behind next() so each call skips
            // the full dispatch chain. The explicit no-arg get* methods (getRow,
            // getWarnings, getMetaData, ...) below are intentionally NOT captured by
            // this wildcard - its guard requires at least one argument.
            if (name.startsWith("get") && args != null && args.length >= 1) {
                int columnIndex = args[0] instanceof Integer
                        ? (Integer) args[0] - 1
                        : result.findColumn((String) args[0]);
                Map<Integer, Object> buffer = onInsertRow ? insertBuffer : updateBuffer;
                if (buffer.containsKey(columnIndex)) {
                    // getXxx reflects pending updateXxx/insertXxx values.
                    lastColumnIndex = columnIndex;
                    Object buffered = buffer.get(columnIndex);
                    lastWasNull = buffered == null;
                    try {
                        return convertValue(buffered, method.getReturnType(), args, name);
                    } catch (SQLException ex) {
                        throw ex;
                    } catch (RuntimeException ex) {
                        throw new SQLException("Cannot convert buffered value for " + name + ": " + ex.getMessage(), "HY000", ex);
                    }
                }
                Object value = columnValue(args[0]);
                try {
                    return convertValue(value, method.getReturnType(), args, name);
                } catch (SQLException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw new SQLException("Cannot convert column value for " + name + ": " + ex.getMessage(), "HY000", ex);
                }
            }
            if ("getMetaData".equals(name)) {
                return newResultSetMetaData(result);
            }
            if ("findColumn".equals(name)) {
                return result.findColumn((String) args[0]) + 1;
            }
            if ("wasNull".equals(name)) {
                return lastWasNull;
            }
            if ("getRow".equals(name)) {
                return row >= 0 && row < result.rows.size() ? row + 1 : 0;
            }
            if ("beforeFirst".equals(name)) {
                requireScrollable("beforeFirst");
                row = -1;
                return null;
            }
            if ("afterLast".equals(name)) {
                requireScrollable("afterLast");
                row = result.rows.size();
                return null;
            }
            if ("first".equals(name)) {
                requireScrollable("first");
                row = result.rows.isEmpty() ? result.rows.size() : 0;
                return !result.rows.isEmpty();
            }
            if ("last".equals(name)) {
                requireScrollable("last");
                row = result.rows.isEmpty() ? result.rows.size() : result.rows.size() - 1;
                return !result.rows.isEmpty();
            }
            if ("absolute".equals(name)) {
                requireScrollable("absolute");
                int target = (Integer) args[0];
                int nextRow = target > 0 ? target - 1 : result.rows.size() + target;
                if (nextRow >= 0 && nextRow < result.rows.size()) {
                    row = nextRow;
                    return true;
                }
                row = nextRow < 0 ? -1 : result.rows.size();
                return false;
            }
            if ("relative".equals(name)) {
                requireScrollable("relative");
                int target = row + (Integer) args[0];
                if (target >= 0 && target < result.rows.size()) {
                    row = target;
                    return true;
                }
                row = target < 0 ? -1 : result.rows.size();
                return false;
            }
            if ("previous".equals(name)) {
                requireScrollable("previous");
                if (row - 1 >= 0) {
                    row--;
                    return true;
                }
                row = -1;
                return false;
            }
            if ("isBeforeFirst".equals(name)) {
                return row < 0 && !result.rows.isEmpty();
            }
            if ("isAfterLast".equals(name)) {
                return row >= result.rows.size() && !result.rows.isEmpty();
            }
            if ("isFirst".equals(name)) {
                return row == 0 && !result.rows.isEmpty();
            }
            if ("isLast".equals(name)) {
                return row == result.rows.size() - 1 && !result.rows.isEmpty();
            }
            if ("getType".equals(name)) {
                return result.declaredType();
            }
            if ("getConcurrency".equals(name)) {
                return ResultSet.CONCUR_UPDATABLE;
            }
            if ("getHoldability".equals(name)) {
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            if ("rowUpdated".equals(name) || "rowInserted".equals(name) || "rowDeleted".equals(name)) {
                return false;
            }
            if ("getStatement".equals(name)) {
                return owner == null ? null : owner.statementProxy;
            }
            if ("getWarnings".equals(name)) {
                return null;
            }
            if ("getCursorName".equals(name)) {
                return null;
            }
            if ("clearWarnings".equals(name) || "setFetchDirection".equals(name) || "setFetchSize".equals(name)) {
                return null;
            }
            if ("getFetchDirection".equals(name)) {
                return ResultSet.FETCH_FORWARD;
            }
            if ("getFetchSize".equals(name)) {
                return 0;
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }
            if ("moveToInsertRow".equals(name)) {
                requireUpdatableRow("moveToInsertRow");
                onInsertRow = true;
                insertBuffer.clear();
                updateBuffer.clear();
                return null;
            }
            if ("moveToCurrentRow".equals(name)) {
                onInsertRow = false;
                insertBuffer.clear();
                updateBuffer.clear();
                return null;
            }
            if ("insertRow".equals(name)) {
                insertBufferedRow();
                return null;
            }
            if ("updateRow".equals(name)) {
                updateBufferedRow();
                return null;
            }
            if ("deleteRow".equals(name)) {
                deleteCurrentRow();
                return null;
            }
            if ("cancelRowUpdates".equals(name)) {
                updateBuffer.clear();
                return null;
            }
            if ("refreshRow".equals(name)) {
                // Rows come straight from the document store; nothing stale to refresh.
                return null;
            }
            if (name.startsWith("update") && args != null && args.length >= 2) {
                int columnIndex = args[0] instanceof Integer
                        ? (Integer) args[0] - 1
                        : result.findColumn((String) args[0]);
                Object value = args[1];
                if (value instanceof java.io.Reader) {
                    value = readReaderFully((java.io.Reader) value);
                } else if (value instanceof java.io.InputStream) {
                    value = readStreamFully((java.io.InputStream) value,
                            name.toLowerCase(Locale.ROOT).contains("binary"));
                }
                (onInsertRow ? insertBuffer : updateBuffer).put(columnIndex, value);
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private Object columnValue(Object selector) throws SQLException {
            if (row < 0 || row >= result.rows.size()) {
                throw new SQLException("Cursor is not positioned on a row.");
            }

            int index = selector instanceof Integer
                    ? ((Integer) selector) - 1
                    : result.findColumn((String) selector);
            if (index < 0 || index >= result.columns.size()) {
                throw new SQLException("Column not found: " + selector);
            }

            lastColumnIndex = index;
            List<Object> currentRow = result.rows.get(row);
            // Rows captured before a late column appeared are narrower than the final
            // master column list; missing cells read as NULL.
            Object value = index < currentRow.size() ? currentRow.get(index) : null;
            lastWasNull = value == null;
            return value;
        }

        private Object convertValue(Object value, Class<?> returnType, Object[] args, String methodName) throws SQLException {
            if (returnType == Object.class && args != null && args.length >= 2 && args[1] instanceof Class) {
                return convertToType(value, (Class<?>) args[1]);
            }
            if (value == null) {
                return defaultValue(returnType);
            }
            if (returnType == Object.class) {
                // getObject must return the declared column type (getColumnClassName contract):
                // the wire format delivers numbers as Long/BigDecimal regardless of the
                // declared column type, so convert here.
                if (value instanceof BigDecimal) {
                    BigDecimal big = (BigDecimal) value;
                    if (lastColumnIndex >= 0 && lastColumnIndex < result.columns.size()) {
                        int declared = result.columns.get(lastColumnIndex).sqlType;
                        if (declared == Types.DOUBLE) {
                            return big.doubleValue();
                        }
                        if (declared == Types.FLOAT) {
                            return Float.valueOf(big.floatValue());
                        }
                        if (declared == Types.INTEGER && big.scale() <= 0 && fitsInt(big)) {
                            return big.intValueExact();
                        }
                        if (declared == Types.BIGINT && big.scale() <= 0 && fitsLong(big)) {
                            return big.longValueExact();
                        }
                    }
                    return big;
                }
                if (value instanceof Long) {
                    long numeric = (Long) value;
                    if (lastColumnIndex >= 0 && lastColumnIndex < result.columns.size()
                            && result.columns.get(lastColumnIndex).sqlType == Types.INTEGER
                            && numeric >= Integer.MIN_VALUE && numeric <= Integer.MAX_VALUE) {
                        return (int) numeric;
                    }
                }
                return value;
            }
            if (returnType == String.class) {
                return String.valueOf(value);
            }
            if (returnType == Boolean.TYPE || returnType == Boolean.class) {
                return toBoolean(value);
            }
            if (returnType == Byte.TYPE || returnType == Byte.class) {
                return toNumber(value).byteValue();
            }
            if (returnType == Short.TYPE || returnType == Short.class) {
                return toNumber(value).shortValue();
            }
            if (returnType == Integer.TYPE || returnType == Integer.class) {
                return toNumber(value).intValue();
            }
            if (returnType == Long.TYPE || returnType == Long.class) {
                return toNumber(value).longValue();
            }
            if (returnType == Float.TYPE || returnType == Float.class) {
                return toNumber(value).floatValue();
            }
            if (returnType == Double.TYPE || returnType == Double.class) {
                return toNumber(value).doubleValue();
            }
            if (returnType == BigDecimal.class) {
                return value instanceof BigDecimal ? value : new BigDecimal(String.valueOf(value));
            }
            if (returnType == Date.class) {
                return new Date(toTimestamp(value).getTime());
            }
            if (returnType == Time.class) {
                return new Time(toTimestamp(value).getTime());
            }
            if (returnType == Timestamp.class) {
                return toTimestamp(value);
            }
            if (returnType == byte[].class) {
                return value instanceof byte[] ? value : Base64.getDecoder().decode(String.valueOf(value));
            }
            if (returnType == java.sql.Clob.class || returnType == java.sql.Blob.class
                    || returnType == java.sql.Array.class || returnType == java.sql.SQLXML.class
                    || returnType == java.sql.NClob.class || returnType == java.sql.Ref.class) {
                throw unsupported(returnType.getSimpleName() + " values are not supported by LiteDB.");
            }
            if (returnType == java.io.InputStream.class) {
                if (methodName.toLowerCase(Locale.ROOT).contains("binary")) {
                    byte[] bytes = value instanceof byte[]
                            ? (byte[]) value
                            : Base64.getDecoder().decode(String.valueOf(value));
                    return new ByteArrayInputStream(bytes);
                }
                return new ByteArrayInputStream(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            }
            if (returnType == java.io.Reader.class) {
                return new StringReader(String.valueOf(value));
            }

            return value;
        }

        @SuppressWarnings("unchecked")
        private Object convertToType(Object value, Class<?> type) throws SQLException {
            if (value == null) {
                return null;
            }
            if (type.isInstance(value)) {
                return value;
            }
            if (type == String.class) {
                return String.valueOf(value);
            }
            if (type == Integer.class) {
                return toNumber(value).intValue();
            }
            if (type == Long.class) {
                return toNumber(value).longValue();
            }
            if (type == Double.class) {
                return toNumber(value).doubleValue();
            }
            if (type == Boolean.class) {
                return toBoolean(value);
            }
            if (type == Timestamp.class) {
                return toTimestamp(value);
            }
            throw new SQLException("Cannot convert column value to " + type.getName());
        }

        private void checkOpen() throws SQLException {
            if (closed) {
                throw new SQLException("ResultSet is closed.");
            }
        }

        private void requireUpdatableRow(String operation) throws SQLException {
            if (owner == null) {
                throw unsupported(operation + " requires a query result set, not a metadata result set.");
            }
            if (sourceCollection() == null || idColumnIndex() < 0) {
                throw unsupported(operation + " requires a result set from a single collection with an _id column.");
            }
        }

        private String sourceCollection() {
            String found = null;
            for (Column column : result.columns) {
                if (!column.tableName.isEmpty()) {
                    if (found != null && !found.equalsIgnoreCase(column.tableName)) {
                        return null;
                    }
                    found = column.tableName;
                }
            }
            return found;
        }

        private int idColumnIndex() {
            for (int i = 0; i < result.columns.size(); i++) {
                if ("_id".equalsIgnoreCase(result.columns.get(i).name)) {
                    return i;
                }
            }
            return -1;
        }

        /** Wraps ObjectId/Guid _id values as extended JSON so deletes/updates match. */
        private Object idWireValue() throws SQLException {
            int index = idColumnIndex();
            List<Object> currentRow = result.rows.get(row);
            Object value = index < currentRow.size() ? currentRow.get(index) : null;
            if (value == null) {
                throw new SQLException("The current row has no _id value.", "HY000");
            }
            Column column = result.columns.get(index);
            if ("OBJECT_ID".equalsIgnoreCase(column.typeName)) {
                Map<String, Object> typed = new LinkedHashMap<>();
                typed.put("$oid", String.valueOf(value));
                return typed;
            }
            if ("GUID".equalsIgnoreCase(column.typeName)) {
                Map<String, Object> typed = new LinkedHashMap<>();
                typed.put("$guid", String.valueOf(value));
                return typed;
            }
            return value;
        }

        private Map<String, Object> rowRequest(String method, Map<String, Object> extra) throws SQLException {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("collection", sourceCollection());
            params.putAll(extra);
            return asMap(owner.state.request(method, params, owner.requestTimeoutMillis()));
        }

        private void deleteCurrentRow() throws SQLException {
            requireUpdatableRow("deleteRow");
            if (row < 0 || row >= result.rows.size()) {
                throw new SQLException("Cursor is not positioned on a row.", "HY000");
            }
            rowRequest("deletedocuments", Map.of("ids", List.of(idWireValue())));
            result.rows.remove(row);
            row--;
        }

        private void updateBufferedRow() throws SQLException {
            requireUpdatableRow("updateRow");
            if (onInsertRow) {
                throw new SQLException("Cursor is on the insert row; call insertRow instead.", "HY000");
            }
            if (updateBuffer.isEmpty()) {
                return;
            }
            Map<String, Object> document = new LinkedHashMap<>();
            for (Map.Entry<Integer, Object> entry : updateBuffer.entrySet()) {
                document.put(result.columns.get(entry.getKey()).name, normalizeParameter(entry.getValue()));
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("id", idWireValue());
            extra.put("document", Json.write(document));
            rowRequest("updatedocument", extra);
            for (Map.Entry<Integer, Object> entry : updateBuffer.entrySet()) {
                List<Object> currentRow = result.rows.get(row);
                while (currentRow.size() < result.columns.size()) {
                    currentRow.add(null);
                }
                currentRow.set(entry.getKey(), entry.getValue());
            }
            updateBuffer.clear();
        }

        private void insertBufferedRow() throws SQLException {
            requireUpdatableRow("insertRow");
            if (!onInsertRow) {
                throw new SQLException("Call moveToInsertRow before insertRow.", "HY000");
            }
            Map<String, Object> document = new LinkedHashMap<>();
            for (Map.Entry<Integer, Object> entry : insertBuffer.entrySet()) {
                document.put(result.columns.get(entry.getKey()).name, normalizeParameter(entry.getValue()));
            }
            rowRequest("insertdocument", Map.of("document", Json.write(document)));
            insertBuffer.clear();
            onInsertRow = false;
        }

        private void requireScrollable(String operation) throws SQLException {
            if (result.declaredType() == ResultSet.TYPE_FORWARD_ONLY) {
                throw new SQLException(
                        operation + " is not allowed on a TYPE_FORWARD_ONLY result set; "
                                + "request TYPE_SCROLL_INSENSITIVE from createStatement/prepareStatement.",
                        "HY000");
            }
        }
    }

    private static final class ResultSetMetaDataHandler implements InvocationHandler {
        private final List<Column> columns;

        ResultSetMetaDataHandler(List<Column> columns) {
            this.columns = columns;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "LiteDB result set metadata");
            }

            try {
                return invokeResultSetMetaData(proxy, method, args);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC result set metadata error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeResultSetMetaData(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getColumnCount".equals(name)) {
                return columns.size();
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }

            Column column = args != null && args.length > 0 && args[0] instanceof Integer
                    ? columnAt((Integer) args[0])
                    : null;
            if ("getColumnName".equals(name) || "getColumnLabel".equals(name)) {
                return column.name;
            }
            if ("getColumnType".equals(name)) {
                return column.sqlType;
            }
            if ("getColumnTypeName".equals(name)) {
                return column.typeName;
            }
            if ("getTableName".equals(name)) {
                return column.tableName;
            }
            if ("getSchemaName".equals(name) || "getCatalogName".equals(name)) {
                return "";
            }
            if ("getColumnClassName".equals(name)) {
                return columnClassName(column.sqlType);
            }
            if ("isNullable".equals(name)) {
                return column.nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
            }
            if ("getPrecision".equals(name) || "getColumnDisplaySize".equals(name)) {
                return displaySize(column.sqlType);
            }
            if ("getScale".equals(name)) {
                return column.sqlType == Types.DECIMAL ? 10 : 0;
            }
            if ("isAutoIncrement".equals(name) || "isCurrency".equals(name) || "isWritable".equals(name)
                    || "isDefinitelyWritable".equals(name)) {
                return false;
            }
            if ("isCaseSensitive".equals(name) || "isSearchable".equals(name) || "isReadOnly".equals(name)) {
                return true;
            }
            if ("isSigned".equals(name)) {
                return isNumeric(column.sqlType);
            }

            return defaultValue(method.getReturnType());
        }

        private Column columnAt(int index) throws SQLException {
            if (index < 1 || index > columns.size()) {
                throw new SQLException("Column index out of range: " + index);
            }
            return columns.get(index - 1);
        }
    }

    private static final class DatabaseMetaDataHandler implements InvocationHandler {
        private final ConnectionState state;

        DatabaseMetaDataHandler(ConnectionState state) {
            this.state = state;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "LiteDB database metadata");
            }

            try {
                return invokeMetadata(proxy, method, args);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC metadata error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeMetadata(Object proxy, Method method, Object[] args) throws Throwable {
            synchronized (state) {
                return invokeMetadataLocked(proxy, method, args);
            }
        }

        private Object invokeMetadataLocked(Object proxy, Method method, Object[] args) throws Throwable {
            state.checkOpen();

            String name = method.getName();
            if ("getConnection".equals(name)) {
                return state.proxy;
            }
            if ("getURL".equals(name)) {
                return state.config.url;
            }
            if ("getUserName".equals(name)) {
                return "";
            }
            if ("getDriverName".equals(name)) {
                return "LiteDB JDBC Adapter";
            }
            if ("getDriverVersion".equals(name)) {
                return LiteDbDriver.VERSION;
            }
            if ("getDriverMajorVersion".equals(name)) {
                return LiteDbDriver.MAJOR_VERSION;
            }
            if ("getDriverMinorVersion".equals(name)) {
                return LiteDbDriver.MINOR_VERSION;
            }
            if ("getDatabaseProductName".equals(name)) {
                return "LiteDB";
            }
            if ("getDatabaseProductVersion".equals(name)) {
                return String.valueOf(info().getOrDefault("liteDbVersion", "unknown"));
            }
            if ("getJDBCMajorVersion".equals(name)) {
                return 4;
            }
            if ("getJDBCMinorVersion".equals(name)) {
                return 2;
            }
            if ("getResultSetHoldability".equals(name)) {
                return ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            if ("supportsResultSetHoldability".equals(name)) {
                return ((Integer) args[0]) == ResultSet.CLOSE_CURSORS_AT_COMMIT;
            }
            if ("supportsBatchUpdates".equals(name)) {
                return true;
            }
            if ("supportsSavepoints".equals(name) || "supportsNamedParameters".equals(name)
                    || "supportsMultipleOpenResults".equals(name) || "supportsGetGeneratedKeys".equals(name)
                    || "supportsMultipleResultSets".equals(name) || "supportsDataDefinitionAndDataManipulationTransactions".equals(name)
                    || "supportsDataManipulationTransactionsOnly".equals(name)) {
                return false;
            }
            if ("allProceduresAreCallable".equals(name) || "supportsStoredProcedures".equals(name)
                    || "supportsOuterJoins".equals(name) || "supportsFullOuterJoins".equals(name)
                    || "supportsLimitedOuterJoins".equals(name)
                    || "supportsLikeEscapeClause".equals(name)) {
                return false;
            }
            if ("supportsMixedCaseQuotedIdentifiers".equals(name)
                    || "storesMixedCaseQuotedIdentifiers".equals(name)) {
                // LiteDB field names are case-sensitive, and the driver normalizes quoted
                // identifiers instead of folding case, so quoted identifiers keep their case.
                return true;
            }
            if ("allTablesAreSelectable".equals(name) || "supportsExpressionsInOrderBy".equals(name)
                    || "supportsOrderByUnrelated".equals(name) || "supportsGroupBy".equals(name)
                    || "supportsGroupByUnrelated".equals(name) || "supportsGroupByBeyondSelect".equals(name)
                    || "supportsUnion".equals(name)
                    || "supportsUnionAll".equals(name) || "supportsTransactions".equals(name)
                    || "supportsSelectForUpdate".equals(name) || "supportsStatementPooling".equals(name)) {
                return true;
            }
            if ("isReadOnly".equals(name)) {
                return state.readOnly;
            }
            if ("nullsAreSortedLow".equals(name) || "nullsAreSortedAtStart".equals(name)) {
                // LiteDB sorts Null before every other BsonType in ascending order.
                return true;
            }
            if ("nullsAreSortedHigh".equals(name) || "nullsAreSortedAtEnd".equals(name)) {
                return false;
            }
            if ("getIdentifierQuoteString".equals(name)) {
                return " ";
            }
            if ("getSQLKeywords".equals(name)) {
                return "COLLECTION,EXPLAIN,INDEX";
            }
            if ("getNumericFunctions".equals(name) || "getStringFunctions".equals(name)
                    || "getSystemFunctions".equals(name) || "getTimeDateFunctions".equals(name)) {
                return "";
            }
            if ("getSearchStringEscape".equals(name)) {
                return "\\";
            }
            if ("getExtraNameCharacters".equals(name)) {
                return "_$";
            }
            if ("getCatalogSeparator".equals(name)) {
                return ".";
            }
            if ("getCatalogTerm".equals(name)) {
                return "database";
            }
            if ("getSchemaTerm".equals(name)) {
                return "schema";
            }
            if ("getProcedureTerm".equals(name)) {
                return "procedure";
            }
            if ("getMaxConnections".equals(name) || "getMaxStatements".equals(name)
                    || "getMaxColumnsInTable".equals(name) || "getMaxColumnsInSelect".equals(name)
                    || "getMaxTableNameLength".equals(name) || "getMaxColumnNameLength".equals(name)) {
                return 0;
            }
            if ("getDefaultTransactionIsolation".equals(name)) {
                return Connection.TRANSACTION_SERIALIZABLE;
            }
            if ("supportsTransactionIsolationLevel".equals(name)) {
                return ((Integer) args[0]) == Connection.TRANSACTION_SERIALIZABLE;
            }
            if ("supportsResultSetType".equals(name)) {
                int type = (Integer) args[0];
                return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
            }
            if ("supportsResultSetConcurrency".equals(name)) {
                int concurrency = (Integer) args[1];
                return concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE;
            }
            if ("ownUpdatesAreVisible".equals(name) || "ownDeletesAreVisible".equals(name)
                    || "ownInsertsAreVisible".equals(name) || "othersUpdatesAreVisible".equals(name)
                    || "othersDeletesAreVisible".equals(name) || "othersInsertsAreVisible".equals(name)
                    || "updatesAreDetected".equals(name) || "deletesAreDetected".equals(name)
                    || "insertsAreDetected".equals(name)) {
                return false;
            }
            if ("getTables".equals(name)) {
                return getTables(args);
            }
            if ("getColumns".equals(name)) {
                return getColumns(args);
            }
            if ("getSchemas".equals(name)) {
                // LiteDB has no schema concept; report none so getSchema()=null,
                // getSchemas()=empty and getTables TABLE_SCHEM=null stay consistent.
                return resultSet(
                        metaColumns(
                                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 1),
                                column("TABLE_CATALOG", Types.VARCHAR, "VARCHAR", true, 2)),
                        Collections.emptyList());
            }
            if ("getCatalogs".equals(name)) {
                return resultSet(
                        metaColumns(column("TABLE_CAT", Types.VARCHAR, "VARCHAR", false, 1)),
                        rowsOf(row("main")));
            }
            if ("getTableTypes".equals(name)) {
                return resultSet(
                        metaColumns(column("TABLE_TYPE", Types.VARCHAR, "VARCHAR", false, 1)),
                        rowsOf(row("TABLE"), row("SYSTEM TABLE")));
            }
            if ("getPrimaryKeys".equals(name)) {
                return getPrimaryKeys(args);
            }
            if ("getIndexInfo".equals(name)) {
                return getIndexInfo(args);
            }
            if ("getTypeInfo".equals(name)) {
                return getTypeInfo();
            }
            if ("getImportedKeys".equals(name) || "getExportedKeys".equals(name) || "getCrossReference".equals(name)) {
                return resultSet(foreignKeyMetadataColumns(), Collections.emptyList());
            }
            if ("getProcedures".equals(name)) {
                return resultSet(procedureMetadataColumns(), Collections.emptyList());
            }
            if ("getProcedureColumns".equals(name)) {
                return resultSet(procedureColumnMetadataColumns(), Collections.emptyList());
            }
            if ("getFunctions".equals(name)) {
                return resultSet(functionMetadataColumns(), Collections.emptyList());
            }
            if ("getFunctionColumns".equals(name)) {
                return resultSet(functionColumnMetadataColumns(), Collections.emptyList());
            }
            if ("getTablePrivileges".equals(name)) {
                return resultSet(tablePrivilegeMetadataColumns(), Collections.emptyList());
            }
            if ("getColumnPrivileges".equals(name)) {
                return resultSet(columnPrivilegeMetadataColumns(), Collections.emptyList());
            }
            if ("getBestRowIdentifier".equals(name)) {
                return getBestRowIdentifier(args);
            }
            if ("getVersionColumns".equals(name)) {
                return resultSet(versionColumnMetadataColumns(), Collections.emptyList());
            }
            if ("getPseudoColumns".equals(name)) {
                return resultSet(pseudoColumnMetadataColumns(), Collections.emptyList());
            }
            if ("getClientInfoProperties".equals(name)) {
                return resultSet(clientInfoMetadataColumns(), Collections.emptyList());
            }
            if ("getSuperTables".equals(name)) {
                return resultSet(superTableMetadataColumns(), Collections.emptyList());
            }
            if ("getSuperTypes".equals(name)) {
                return resultSet(superTypeMetadataColumns(), Collections.emptyList());
            }
            if ("getUDTs".equals(name)) {
                return resultSet(udtMetadataColumns(), Collections.emptyList());
            }
            if ("getAttributes".equals(name)) {
                return resultSet(attributeMetadataColumns(), Collections.emptyList());
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }

            return defaultValue(method.getReturnType());
        }

        private Map<String, Object> info() throws SQLException {
            Object response = state.request("info", Map.of(), 0L);
            return asMap(response);
        }

        private ResultSet getTables(Object[] args) throws SQLException {
            String tablePattern = args == null || args.length < 3 ? null : (String) args[2];
            Set<String> typeFilter = tableTypeFilter(args == null || args.length < 4 ? null : (String[]) args[3]);
            Object response = state.client.request("collections", Map.of());
            List<List<Object>> rows = new ArrayList<>();
            for (Object item : asList(response)) {
                Map<String, Object> table = asMap(item);
                String name = stringValue(table.get("name"));
                String type = stringValue(table.get("type"));
                if (!matchesPattern(name, tablePattern) || (!typeFilter.isEmpty() && !typeFilter.contains(type))) {
                    continue;
                }
                rows.add(row("main", null, name, type, stringValue(table.get("remarks")), null, null, null, null, null));
            }

            return resultSet(
                    tableMetadataColumns(),
                    rows);
        }

        private ResultSet getColumns(Object[] args) throws SQLException {
            String tablePattern = args == null || args.length < 3 ? null : (String) args[2];
            String columnPattern = args == null || args.length < 4 ? null : (String) args[3];
            List<List<Object>> rows = new ArrayList<>();
            // Fast path: a pattern without '%' is (case-insensitively) a concrete table
            // name in every practical caller; pre-filter server-side instead of sampling
            // every collection. Patterns containing '_' keep JDBC wildcard semantics, so
            // fall back to the full scan when the exact filter matched nothing.
            boolean exactPrefilter = tablePattern != null && tablePattern.indexOf('%') < 0;
            if (exactPrefilter) {
                collectColumns(tablePattern, tablePattern, columnPattern, rows);
                if (rows.isEmpty() && tablePattern.indexOf('_') >= 0) {
                    collectColumns(tablePattern, null, columnPattern, rows);
                }
            } else {
                collectColumns(tablePattern, null, columnPattern, rows);
            }

            return resultSet(
                    columnMetadataColumns(),
                    rows);
        }

        private void collectColumns(String tablePattern, String collectionFilter, String columnPattern,
                List<List<Object>> rows) throws SQLException {
            Map<String, Object> request = new LinkedHashMap<>();
            if (collectionFilter != null) {
                request.put("collection", collectionFilter);
            }
            request.put("sampleSize", state.config.columnSampleSize);

            Object response = state.client.request("columns", request);
            for (Object item : asList(response)) {
                Map<String, Object> column = asMap(item);
                String table = stringValue(column.get("collection"));
                String name = stringValue(column.get("name"));
                String typeName = stringValue(column.get("typeName"));
                int type = sqlType(typeName);
                boolean nullable = booleanValue(column.get("nullable"));
                int ordinal = intValue(column.get("ordinal"), rows.size() + 1);
                if (!matchesPattern(table, tablePattern) || !matchesPattern(name, columnPattern)) {
                    continue;
                }
                rows.add(row(
                        "main",
                        null,
                        table,
                        name,
                        type,
                        typeName,
                        displaySize(type),
                        null,
                        type == Types.DECIMAL ? 10 : 0,
                        10,
                        nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls,
                        "",
                        null,
                        0,
                        0,
                        displaySize(type),
                        ordinal,
                        nullable ? "YES" : "NO",
                        null,
                        null,
                        null,
                        null,
                        "_id".equalsIgnoreCase(name) ? "YES" : "NO",
                        "NO"));
            }
        }

        private ResultSet getPrimaryKeys(Object[] args) throws SQLException {
            String tableName = args == null || args.length < 3 ? null : (String) args[2];
            List<List<Object>> rows = new ArrayList<>();
            if (tableName == null || tableName.isBlank() || tableName.indexOf('%') >= 0 || tableName.indexOf('_') >= 0) {
                Object response = state.client.request("collections", Map.of());
                for (Object item : asList(response)) {
                    Map<String, Object> table = asMap(item);
                    String name = stringValue(table.get("name"));
                    if (matchesPattern(name, tableName)) {
                        rows.add(row("main", null, name, "_id", 1, name + "_pk"));
                    }
                }
            } else {
                rows.add(row("main", null, tableName, "_id", 1, tableName + "_pk"));
            }
            return resultSet(primaryKeyMetadataColumns(), rows);
        }

        private ResultSet getIndexInfo(Object[] args) throws SQLException {
            String tableName = args == null || args.length < 3 ? null : (String) args[2];
            Map<String, Object> request = new LinkedHashMap<>();
            if (tableName != null && !tableName.isBlank()) {
                request.put("collection", tableName);
            }
            Object response = state.client.request("indexes", request);
            List<List<Object>> rows = new ArrayList<>();
            for (Object item : asList(response)) {
                Map<String, Object> index = asMap(item);
                String collection = stringValue(index.get("collection"));
                String name = stringValue(index.get("name"));
                String expression = stringValue(index.get("expression"));
                String columnName = firstNonBlank(stringValue(index.get("column")), cleanIndexExpression(expression));
                boolean unique = booleanValue(index.get("unique"));
                int ordinal = intValue(index.get("ordinal"), 1);
                String direction = stringValue(index.get("direction"));
                rows.add(row("main", null, collection, !unique, null, name, DatabaseMetaData.tableIndexOther,
                        ordinal, columnName, direction.isBlank() ? "A" : direction, 0, 0, expression));
            }

            return resultSet(
                    indexMetadataColumns(),
                    rows);
        }

        private ResultSet getBestRowIdentifier(Object[] args) {
            String tableName = args == null || args.length < 3 ? null : (String) args[2];
            List<List<Object>> rows = new ArrayList<>();
            if (tableName != null && !tableName.isBlank()) {
                rows.add(row(DatabaseMetaData.bestRowSession, "_id", Types.VARCHAR, "VARCHAR",
                        displaySize(Types.VARCHAR), null, null, DatabaseMetaData.bestRowNotPseudo));
            }
            return resultSet(bestRowIdentifierMetadataColumns(), rows);
        }

        private ResultSet getTypeInfo() {
            List<List<Object>> rows = new ArrayList<>();
            addTypeInfo(rows, "VARCHAR", Types.VARCHAR, 65535, "'", "'");
            addTypeInfo(rows, "INTEGER", Types.INTEGER, 10, null, null);
            addTypeInfo(rows, "BIGINT", Types.BIGINT, 19, null, null);
            addTypeInfo(rows, "DOUBLE", Types.DOUBLE, 15, null, null);
            addTypeInfo(rows, "DECIMAL", Types.DECIMAL, 34, null, null);
            addTypeInfo(rows, "BOOLEAN", Types.BOOLEAN, 1, null, null);
            addTypeInfo(rows, "TIMESTAMP", Types.TIMESTAMP, 27, "'", "'");
            addTypeInfo(rows, "JSON", Types.VARCHAR, 65535, "'", "'");
            addTypeInfo(rows, "OBJECT_ID", Types.VARCHAR, 24, "'", "'");
            addTypeInfo(rows, "GUID", Types.VARCHAR, 36, "'", "'");

            return resultSet(
                    typeInfoMetadataColumns(),
                    rows);
        }

        private void addTypeInfo(List<List<Object>> rows, String name, int type, int precision, String prefix, String suffix) {
            rows.add(row(name, type, precision, prefix, suffix, null, DatabaseMetaData.typeNullable,
                    type == Types.VARCHAR, DatabaseMetaData.typeSearchable, !isNumeric(type),
                    false, false, name, 0, type == Types.DECIMAL ? 10 : 0, 0, 0, 10));
        }
    }

    private static final class ParameterMetaDataHandler implements InvocationHandler {
        private final int count;

        ParameterMetaDataHandler(int count) {
            this.count = count;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "LiteDB parameter metadata");
            }
            try {
                return invokeParameterMetaData(proxy, method, args);
            } catch (SQLException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new SQLException("LiteDB JDBC parameter metadata error: " + ex.getMessage(), "HY000", ex);
            }
        }

        private Object invokeParameterMetaData(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getParameterCount".equals(name)) {
                return count;
            }
            if ("isNullable".equals(name)) {
                return ParameterMetaData.parameterNullableUnknown;
            }
            if ("getParameterType".equals(name)) {
                return Types.VARCHAR;
            }
            if ("getParameterTypeName".equals(name) || "getParameterClassName".equals(name)) {
                return "java.lang.String";
            }
            if ("getParameterMode".equals(name)) {
                return ParameterMetaData.parameterModeIn;
            }
            if ("isSigned".equals(name)) {
                return true;
            }
            if ("getPrecision".equals(name) || "getScale".equals(name)) {
                return 0;
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            if ("unwrap".equals(name)) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Not a wrapper for " + type.getName());
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static ResultSet resultSet(List<Column> columns, List<List<Object>> rows) {
        return newResultSet(new QueryResult(columns, rows));
    }

    private static List<Column> metaColumns(Column... columns) {
        return new ArrayList<>(Arrays.asList(columns));
    }

    private static Column column(String name, int sqlType, String typeName, boolean nullable, int ordinal) {
        return new Column(name, typeName, sqlType, nullable, ordinal, "");
    }

    private static List<Column> tableMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("TABLE_TYPE", Types.VARCHAR, "VARCHAR", false, 4),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 5),
                column("TYPE_CAT", Types.VARCHAR, "VARCHAR", true, 6),
                column("TYPE_SCHEM", Types.VARCHAR, "VARCHAR", true, 7),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", true, 8),
                column("SELF_REFERENCING_COL_NAME", Types.VARCHAR, "VARCHAR", true, 9),
                column("REF_GENERATION", Types.VARCHAR, "VARCHAR", true, 10));
    }

    private static List<Column> columnMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 5),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 6),
                column("COLUMN_SIZE", Types.INTEGER, "INTEGER", true, 7),
                column("BUFFER_LENGTH", Types.INTEGER, "INTEGER", true, 8),
                column("DECIMAL_DIGITS", Types.INTEGER, "INTEGER", true, 9),
                column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER", true, 10),
                column("NULLABLE", Types.INTEGER, "INTEGER", false, 11),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 12),
                column("COLUMN_DEF", Types.VARCHAR, "VARCHAR", true, 13),
                column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER", true, 14),
                column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER", true, 15),
                column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER", true, 16),
                column("ORDINAL_POSITION", Types.INTEGER, "INTEGER", false, 17),
                column("IS_NULLABLE", Types.VARCHAR, "VARCHAR", false, 18),
                column("SCOPE_CATALOG", Types.VARCHAR, "VARCHAR", true, 19),
                column("SCOPE_SCHEMA", Types.VARCHAR, "VARCHAR", true, 20),
                column("SCOPE_TABLE", Types.VARCHAR, "VARCHAR", true, 21),
                column("SOURCE_DATA_TYPE", Types.SMALLINT, "SMALLINT", true, 22),
                column("IS_AUTOINCREMENT", Types.VARCHAR, "VARCHAR", false, 23),
                column("IS_GENERATEDCOLUMN", Types.VARCHAR, "VARCHAR", false, 24));
    }

    private static List<Column> primaryKeyMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("KEY_SEQ", Types.SMALLINT, "SMALLINT", false, 5),
                column("PK_NAME", Types.VARCHAR, "VARCHAR", true, 6));
    }

    private static List<Column> indexMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("NON_UNIQUE", Types.BOOLEAN, "BOOLEAN", false, 4),
                column("INDEX_QUALIFIER", Types.VARCHAR, "VARCHAR", true, 5),
                column("INDEX_NAME", Types.VARCHAR, "VARCHAR", true, 6),
                column("TYPE", Types.SMALLINT, "SMALLINT", false, 7),
                column("ORDINAL_POSITION", Types.SMALLINT, "SMALLINT", false, 8),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", true, 9),
                column("ASC_OR_DESC", Types.VARCHAR, "VARCHAR", true, 10),
                column("CARDINALITY", Types.BIGINT, "BIGINT", true, 11),
                column("PAGES", Types.BIGINT, "BIGINT", true, 12),
                column("FILTER_CONDITION", Types.VARCHAR, "VARCHAR", true, 13));
    }

    private static List<Column> typeInfoMetadataColumns() {
        return metaColumns(
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 1),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 2),
                column("PRECISION", Types.INTEGER, "INTEGER", true, 3),
                column("LITERAL_PREFIX", Types.VARCHAR, "VARCHAR", true, 4),
                column("LITERAL_SUFFIX", Types.VARCHAR, "VARCHAR", true, 5),
                column("CREATE_PARAMS", Types.VARCHAR, "VARCHAR", true, 6),
                column("NULLABLE", Types.SMALLINT, "SMALLINT", false, 7),
                column("CASE_SENSITIVE", Types.BOOLEAN, "BOOLEAN", false, 8),
                column("SEARCHABLE", Types.SMALLINT, "SMALLINT", false, 9),
                column("UNSIGNED_ATTRIBUTE", Types.BOOLEAN, "BOOLEAN", true, 10),
                column("FIXED_PREC_SCALE", Types.BOOLEAN, "BOOLEAN", false, 11),
                column("AUTO_INCREMENT", Types.BOOLEAN, "BOOLEAN", false, 12),
                column("LOCAL_TYPE_NAME", Types.VARCHAR, "VARCHAR", true, 13),
                column("MINIMUM_SCALE", Types.SMALLINT, "SMALLINT", false, 14),
                column("MAXIMUM_SCALE", Types.SMALLINT, "SMALLINT", false, 15),
                column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER", true, 16),
                column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER", true, 17),
                column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER", true, 18));
    }

    private static List<Column> foreignKeyMetadataColumns() {
        return metaColumns(
                column("PKTABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("PKTABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("PKTABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("PKCOLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("FKTABLE_CAT", Types.VARCHAR, "VARCHAR", true, 5),
                column("FKTABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 6),
                column("FKTABLE_NAME", Types.VARCHAR, "VARCHAR", false, 7),
                column("FKCOLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 8),
                column("KEY_SEQ", Types.SMALLINT, "SMALLINT", false, 9),
                column("UPDATE_RULE", Types.SMALLINT, "SMALLINT", true, 10),
                column("DELETE_RULE", Types.SMALLINT, "SMALLINT", true, 11),
                column("FK_NAME", Types.VARCHAR, "VARCHAR", true, 12),
                column("PK_NAME", Types.VARCHAR, "VARCHAR", true, 13),
                column("DEFERRABILITY", Types.SMALLINT, "SMALLINT", true, 14));
    }

    private static List<Column> bestRowIdentifierMetadataColumns() {
        return metaColumns(
                column("SCOPE", Types.SMALLINT, "SMALLINT", true, 1),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 2),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 3),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("COLUMN_SIZE", Types.INTEGER, "INTEGER", true, 5),
                column("BUFFER_LENGTH", Types.INTEGER, "INTEGER", true, 6),
                column("DECIMAL_DIGITS", Types.SMALLINT, "SMALLINT", true, 7),
                column("PSEUDO_COLUMN", Types.SMALLINT, "SMALLINT", true, 8));
    }

    private static List<Column> versionColumnMetadataColumns() {
        return bestRowIdentifierMetadataColumns();
    }

    private static List<Column> tablePrivilegeMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("GRANTOR", Types.VARCHAR, "VARCHAR", true, 4),
                column("GRANTEE", Types.VARCHAR, "VARCHAR", true, 5),
                column("PRIVILEGE", Types.VARCHAR, "VARCHAR", false, 6),
                column("IS_GRANTABLE", Types.VARCHAR, "VARCHAR", true, 7));
    }

    private static List<Column> columnPrivilegeMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("GRANTOR", Types.VARCHAR, "VARCHAR", true, 5),
                column("GRANTEE", Types.VARCHAR, "VARCHAR", true, 6),
                column("PRIVILEGE", Types.VARCHAR, "VARCHAR", false, 7),
                column("IS_GRANTABLE", Types.VARCHAR, "VARCHAR", true, 8));
    }

    private static List<Column> procedureMetadataColumns() {
        return metaColumns(
                column("PROCEDURE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("PROCEDURE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("PROCEDURE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("reserved1", Types.VARCHAR, "VARCHAR", true, 4),
                column("reserved2", Types.VARCHAR, "VARCHAR", true, 5),
                column("reserved3", Types.VARCHAR, "VARCHAR", true, 6),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 7),
                column("PROCEDURE_TYPE", Types.SMALLINT, "SMALLINT", false, 8),
                column("SPECIFIC_NAME", Types.VARCHAR, "VARCHAR", true, 9));
    }

    private static List<Column> procedureColumnMetadataColumns() {
        return metaColumns(
                column("PROCEDURE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("PROCEDURE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("PROCEDURE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("COLUMN_TYPE", Types.SMALLINT, "SMALLINT", false, 5),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 6),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 7),
                column("PRECISION", Types.INTEGER, "INTEGER", true, 8),
                column("LENGTH", Types.INTEGER, "INTEGER", true, 9),
                column("SCALE", Types.SMALLINT, "SMALLINT", true, 10),
                column("RADIX", Types.SMALLINT, "SMALLINT", true, 11),
                column("NULLABLE", Types.SMALLINT, "SMALLINT", true, 12),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 13),
                column("COLUMN_DEF", Types.VARCHAR, "VARCHAR", true, 14),
                column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER", true, 15),
                column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER", true, 16),
                column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER", true, 17),
                column("ORDINAL_POSITION", Types.INTEGER, "INTEGER", true, 18),
                column("IS_NULLABLE", Types.VARCHAR, "VARCHAR", true, 19),
                column("SPECIFIC_NAME", Types.VARCHAR, "VARCHAR", true, 20));
    }

    private static List<Column> functionMetadataColumns() {
        return metaColumns(
                column("FUNCTION_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("FUNCTION_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("FUNCTION_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 4),
                column("FUNCTION_TYPE", Types.SMALLINT, "SMALLINT", false, 5),
                column("SPECIFIC_NAME", Types.VARCHAR, "VARCHAR", true, 6));
    }

    private static List<Column> functionColumnMetadataColumns() {
        return metaColumns(
                column("FUNCTION_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("FUNCTION_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("FUNCTION_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("COLUMN_TYPE", Types.SMALLINT, "SMALLINT", false, 5),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 6),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 7),
                column("PRECISION", Types.INTEGER, "INTEGER", true, 8),
                column("LENGTH", Types.INTEGER, "INTEGER", true, 9),
                column("SCALE", Types.SMALLINT, "SMALLINT", true, 10),
                column("RADIX", Types.SMALLINT, "SMALLINT", true, 11),
                column("NULLABLE", Types.SMALLINT, "SMALLINT", true, 12),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 13),
                column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER", true, 14),
                column("ORDINAL_POSITION", Types.INTEGER, "INTEGER", true, 15),
                column("IS_NULLABLE", Types.VARCHAR, "VARCHAR", true, 16),
                column("SPECIFIC_NAME", Types.VARCHAR, "VARCHAR", true, 17));
    }

    private static List<Column> pseudoColumnMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("COLUMN_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 5),
                column("COLUMN_SIZE", Types.INTEGER, "INTEGER", true, 6),
                column("DECIMAL_DIGITS", Types.INTEGER, "INTEGER", true, 7),
                column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER", true, 8),
                column("COLUMN_USAGE", Types.VARCHAR, "VARCHAR", true, 9),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 10),
                column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER", true, 11),
                column("IS_NULLABLE", Types.VARCHAR, "VARCHAR", false, 12));
    }

    private static List<Column> clientInfoMetadataColumns() {
        return metaColumns(
                column("NAME", Types.VARCHAR, "VARCHAR", false, 1),
                column("MAX_LEN", Types.INTEGER, "INTEGER", false, 2),
                column("DEFAULT_VALUE", Types.VARCHAR, "VARCHAR", true, 3),
                column("DESCRIPTION", Types.VARCHAR, "VARCHAR", true, 4));
    }

    private static List<Column> superTableMetadataColumns() {
        return metaColumns(
                column("TABLE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TABLE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("SUPERTABLE_NAME", Types.VARCHAR, "VARCHAR", false, 4));
    }

    private static List<Column> superTypeMetadataColumns() {
        return metaColumns(
                column("TYPE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TYPE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("SUPERTYPE_CAT", Types.VARCHAR, "VARCHAR", true, 4),
                column("SUPERTYPE_SCHEM", Types.VARCHAR, "VARCHAR", true, 5),
                column("SUPERTYPE_NAME", Types.VARCHAR, "VARCHAR", false, 6));
    }

    private static List<Column> udtMetadataColumns() {
        return metaColumns(
                column("TYPE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TYPE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("CLASS_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 5),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 6),
                column("BASE_TYPE", Types.SMALLINT, "SMALLINT", true, 7));
    }

    private static List<Column> attributeMetadataColumns() {
        return metaColumns(
                column("TYPE_CAT", Types.VARCHAR, "VARCHAR", true, 1),
                column("TYPE_SCHEM", Types.VARCHAR, "VARCHAR", true, 2),
                column("TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 3),
                column("ATTR_NAME", Types.VARCHAR, "VARCHAR", false, 4),
                column("DATA_TYPE", Types.INTEGER, "INTEGER", false, 5),
                column("ATTR_TYPE_NAME", Types.VARCHAR, "VARCHAR", false, 6),
                column("ATTR_SIZE", Types.INTEGER, "INTEGER", true, 7),
                column("DECIMAL_DIGITS", Types.INTEGER, "INTEGER", true, 8),
                column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER", true, 9),
                column("NULLABLE", Types.INTEGER, "INTEGER", false, 10),
                column("REMARKS", Types.VARCHAR, "VARCHAR", true, 11),
                column("ATTR_DEF", Types.VARCHAR, "VARCHAR", true, 12),
                column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER", true, 13),
                column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER", true, 14),
                column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER", true, 15),
                column("ORDINAL_POSITION", Types.INTEGER, "INTEGER", false, 16),
                column("IS_NULLABLE", Types.VARCHAR, "VARCHAR", false, 17),
                column("SCOPE_CATALOG", Types.VARCHAR, "VARCHAR", true, 18),
                column("SCOPE_SCHEMA", Types.VARCHAR, "VARCHAR", true, 19),
                column("SCOPE_TABLE", Types.VARCHAR, "VARCHAR", true, 20),
                column("SOURCE_DATA_TYPE", Types.SMALLINT, "SMALLINT", true, 21));
    }

    private static List<List<Object>> rowsOf(List<Object> first) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(first);
        return rows;
    }

    private static List<List<Object>> rowsOf(List<Object> first, List<Object> second) {
        List<List<Object>> rows = rowsOf(first);
        rows.add(second);
        return rows;
    }

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static Set<String> tableTypeFilter(String[] types) {
        if (types == null || types.length == 0) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (String type : types) {
            if (type != null) {
                set.add(type.toUpperCase(Locale.ROOT));
            }
        }
        return set;
    }

    private static List<Object> normalizeParameters(List<Object> values) {
        List<Object> normalized = new ArrayList<>();
        for (Object value : values) {
            normalized.add(normalizeParameter(value));
        }
        return normalized;
    }

    /**
     * Typed parameters travel as LiteDB extended-JSON nodes instead of degraded
     * strings: the bridge deserializes JSON objects through the official
     * LiteDB JsonSerializer, so {$date}, {$numberDecimal}, {$binary} and
     * {$numberLong} bind losslessly. Plain strings/numbers/booleans stay as-is.
     */
    static Object normalizeParameter(Object value) {
        if (value instanceof java.util.Date) {
            Instant instant = ((java.util.Date) value).toInstant();
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("$date", DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(instant));
            return typed;
        }
        if (value instanceof BigDecimal) {
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("$numberDecimal", ((BigDecimal) value).toPlainString());
            return typed;
        }
        if (value instanceof byte[]) {
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("$binary", Base64.getEncoder().encodeToString((byte[]) value));
            return typed;
        }
        if (value instanceof Long
                && (((Long) value) > Integer.MAX_VALUE || ((Long) value) < Integer.MIN_VALUE)) {
            Map<String, Object> typed = new LinkedHashMap<>();
            typed.put("$numberLong", value.toString());
            return typed;
        }
        return value;
    }

    static String readReaderFully(java.io.Reader reader) throws SQLException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];
        try (java.io.Reader source = reader) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw new SQLException("Failed to read character stream parameter: " + ex.getMessage(), "HY000", ex);
        }
        return builder.toString();
    }

    static Object readStreamFully(java.io.InputStream stream, boolean binary) throws SQLException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (java.io.InputStream source = stream) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw new SQLException("Failed to read binary stream parameter: " + ex.getMessage(), "HY000", ex);
        }
        if (binary) {
            return output.toByteArray();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String rewriteCommonSql(String sql) {
        String rewritten = normalizeQuotedIdentifiers(sql);
        rewritten = rewriteSelectTop(rewritten);
        rewritten = rewriteSqliteLimit(rewritten);
        rewritten = rewriteDeleteFrom(rewritten);
        rewritten = rewriteIsNull(rewritten);
        Matcher matcher = SELECT_STAR_PATTERN.matcher(rewritten);
        return matcher.matches() ? "select $ from " + matcher.group(1) : rewritten;
    }

    /**
     * LiteDB SQL has no "DELETE FROM table" form; the standard SQL spelling must
     * become "DELETE table". The rewrite is anchored to the statement head so
     * string literals containing "delete from" are untouched.
     */
    private static String rewriteDeleteFrom(String sql) {
        Matcher matcher = DELETE_FROM_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }
        String table = matcher.group(1);
        String rest = matcher.group(2) == null ? "" : matcher.group(2);
        return rest.isBlank() ? "delete " + table : "delete " + table + " " + rest;
    }

    /**
     * LiteDB has no IS [NOT] NULL; DBeaver's null filter generates it. Rewrite to
     * "= null" / "!= null" outside string literals.
     */
    private static String rewriteIsNull(String sql) {
        if (!IS_NULL_PATTERN.matcher(sql).find()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean singleQuote = false;
        boolean doubleQuote = false;
        Matcher matcher = IS_NULL_PATTERN.matcher(sql);
        int copied = 0;
        while (matcher.find()) {
            for (int i = copied; i < matcher.start(); i++) {
                char c = sql.charAt(i);
                if (c == '\'' && !doubleQuote) {
                    singleQuote = !singleQuote;
                } else if (c == '"' && !singleQuote) {
                    doubleQuote = !doubleQuote;
                }
            }
            out.append(sql, copied, matcher.start());
            if (singleQuote || doubleQuote) {
                out.append(matcher.group());
            } else if (matcher.group(1) == null) {
                out.append("= null");
            } else {
                out.append("!= null");
            }
            copied = matcher.end();
        }
        out.append(sql.substring(copied));
        return out.toString();
    }

    private static String rewriteSelectTop(String sql) {
        Matcher matcher = SELECT_TOP_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }

        String body = matcher.group(2).trim();
        if (LIMIT_PATTERN.matcher(body).find()) {
            return sql;
        }

        String top = matcher.group(1);
        String suffix = "";
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
            suffix = ";";
        }

        return "select " + body + " limit " + top + suffix;
    }

    private static String rewriteSqliteLimit(String sql) {
        Matcher matcher = SQLITE_LIMIT_PATTERN.matcher(sql);
        return matcher.find()
                ? matcher.replaceFirst("limit " + matcher.group(2) + " offset " + matcher.group(1) + matcher.group(3))
                : sql;
    }

    private static String normalizeQuotedIdentifiers(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean singleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (singleQuote) {
                out.append(c);
                if (c == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append(sql.charAt(++i));
                } else if (c == '\'') {
                    singleQuote = false;
                }
                continue;
            }

            if (c == '\'') {
                singleQuote = true;
                out.append(c);
                continue;
            }

            if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = findQuotedIdentifierEnd(sql, i + 1, close);
                if (end > i) {
                    String identifier = sql.substring(i + 1, end);
                    if (isSafeIdentifier(identifier) && isIdentifierContext(sql, i, c != '"')) {
                        out.append(identifier);
                        i = end;
                        continue;
                    }
                }
            }

            out.append(c);
        }

        return out.toString();
    }

    private static int findQuotedIdentifierEnd(String sql, int start, char close) {
        for (int i = start; i < sql.length(); i++) {
            if (sql.charAt(i) != close) {
                continue;
            }
            if (i + 1 < sql.length() && sql.charAt(i + 1) == close && close != ']') {
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean isSafeIdentifier(String identifier) {
        return SAFE_IDENTIFIER_PATTERN.matcher(identifier).matches();
    }

    private static boolean isIdentifierContext(String sql, int quoteStart, boolean forceIdentifier) {
        if (forceIdentifier) {
            return true;
        }

        int index = quoteStart - 1;
        while (index >= 0 && Character.isWhitespace(sql.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return true;
        }

        char previous = sql.charAt(index);
        if (previous == '=' || previous == '<' || previous == '>' || previous == '!') {
            return false;
        }
        if (previous == ',' || previous == '(' || previous == '.' || previous == '+' || previous == '-'
                || previous == '*' || previous == '/') {
            return true;
        }
        if (!Character.isLetter(previous)) {
            return false;
        }

        int wordEnd = index + 1;
        while (index >= 0 && (Character.isLetter(sql.charAt(index)) || sql.charAt(index) == '_')) {
            index--;
        }
        String word = sql.substring(index + 1, wordEnd).toLowerCase(Locale.ROOT);
        return isIdentifierKeyword(word);
    }

    private static boolean isIdentifierKeyword(String word) {
        return "select".equals(word) || "from".equals(word) || "join".equals(word) || "where".equals(word)
                || "and".equals(word) || "or".equals(word) || "by".equals(word) || "order".equals(word)
                || "group".equals(word) || "having".equals(word) || "on".equals(word) || "as".equals(word)
                || "update".equals(word) || "into".equals(word) || "set".equals(word) || "delete".equals(word)
                || "distinct".equals(word);
    }

    private static boolean matchesPattern(String value, String sqlPattern) {
        if (sqlPattern == null || sqlPattern.isEmpty() || "%".equals(sqlPattern)) {
            return true;
        }
        if (value == null) {
            return false;
        }

        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < sqlPattern.length(); i++) {
            char c = sqlPattern.charAt(i);
            if (c == '\\' && i + 1 < sqlPattern.length()) {
                // Honor the declared escape character (getSearchStringEscape) so tools
                // can match literal '_' / '%' / '\' in collection and column names.
                char escaped = sqlPattern.charAt(i + 1);
                if (escaped == '_' || escaped == '%' || escaped == '\\') {
                    regex.append(Pattern.quote(String.valueOf(escaped)));
                    i++;
                    continue;
                }
            }
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(value).matches();
    }

    private static String cleanIndexExpression(String expression) {
        String text = expression == null ? "" : expression.trim();
        while (text.startsWith("$")) {
            text = text.substring(1).trim();
            if (text.startsWith(".")) {
                text = text.substring(1).trim();
            }
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1);
        }
        return text.isBlank() ? expression : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Map<String, Object> asMap(Object value) throws SQLException {
        if (!(value instanceof Map)) {
            throw new SQLException("Expected object from LiteDB bridge.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static List<Object> asList(Object value) throws SQLException {
        if (!(value instanceof List)) {
            throw new SQLException("Expected array from LiteDB bridge.");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0d;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Timestamp toTimestamp(Object value) {
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        String text = String.valueOf(value);
        try {
            return Timestamp.from(Instant.parse(text));
        } catch (RuntimeException firstTry) {
            try {
                return Timestamp.from(java.time.OffsetDateTime.parse(text).toInstant());
            } catch (RuntimeException secondTry) {
                return Timestamp.valueOf(text.replace('T', ' ').replace("Z", ""));
            }
        }
    }

    private static int sqlType(String typeName) {
        String type = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        if ("INTEGER".equals(type)) {
            return Types.INTEGER;
        }
        if ("SMALLINT".equals(type)) {
            return Types.SMALLINT;
        }
        if ("BIGINT".equals(type)) {
            return Types.BIGINT;
        }
        if ("DOUBLE".equals(type)) {
            return Types.DOUBLE;
        }
        if ("DECIMAL".equals(type)) {
            return Types.DECIMAL;
        }
        if ("BOOLEAN".equals(type)) {
            return Types.BOOLEAN;
        }
        if ("TIMESTAMP".equals(type)) {
            return Types.TIMESTAMP;
        }
        if ("BINARY".equals(type)) {
            return Types.VARBINARY;
        }
        return Types.VARCHAR;
    }

    private static String columnClassName(int type) {
        if (type == Types.INTEGER) {
            return Integer.class.getName();
        }
        if (type == Types.BIGINT) {
            return Long.class.getName();
        }
        if (type == Types.DOUBLE) {
            return Double.class.getName();
        }
        if (type == Types.DECIMAL) {
            return BigDecimal.class.getName();
        }
        if (type == Types.BOOLEAN) {
            return Boolean.class.getName();
        }
        if (type == Types.TIMESTAMP) {
            return Timestamp.class.getName();
        }
        if (type == Types.VARBINARY) {
            return byte[].class.getName();
        }
        return String.class.getName();
    }

    private static boolean isNumeric(int type) {
        return type == Types.INTEGER || type == Types.BIGINT || type == Types.DOUBLE || type == Types.DECIMAL;
    }

    private static boolean fitsInt(BigDecimal value) {
        try {
            value.intValueExact();
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private static boolean fitsLong(BigDecimal value) {
        try {
            value.longValueExact();
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private static int displaySize(int type) {
        if (type == Types.INTEGER) {
            return 10;
        }
        if (type == Types.BIGINT) {
            return 19;
        }
        if (type == Types.DOUBLE || type == Types.DECIMAL) {
            return 34;
        }
        if (type == Types.BOOLEAN) {
            return 5;
        }
        if (type == Types.TIMESTAMP) {
            return 27;
        }
        if (type == Types.VARBINARY) {
            return 65535;
        }
        return 65535;
    }

    private static final class Column {
        final String name;
        String typeName;
        int sqlType;
        final boolean nullable;
        final int ordinal;
        String tableName;

        Column(String name, String typeName, int sqlType, boolean nullable, int ordinal, String tableName) {
            this.name = name;
            this.typeName = typeName;
            this.sqlType = sqlType;
            this.nullable = nullable;
            this.ordinal = ordinal;
            this.tableName = tableName;
        }
    }

    private static final class QueryResult {
        final List<Column> columns;
        final List<List<Object>> rows;
        private ChunkSource chunkSource;
        private boolean exhausted;
        private int declaredType = ResultSet.TYPE_SCROLL_INSENSITIVE;

        QueryResult(List<Column> columns, List<List<Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        static QueryResult empty() {
            return new QueryResult(Collections.emptyList(), Collections.emptyList());
        }

        /**
         * Builds the result from a chunked bridge stream. Scroll-insensitive callers
         * absorb every chunk immediately; forward-only callers keep the chunk source
         * and pull rows lazily in {@code next()}.
         */
        static QueryResult fromStream(BridgeClient client, BridgeClient.StreamedResult streamed,
                boolean eager, long timeoutMillis) throws SQLException {
            QueryResult result = new QueryResult(new ArrayList<>(), new ArrayList<>());
            result.declaredType = eager ? ResultSet.TYPE_SCROLL_INSENSITIVE : ResultSet.TYPE_FORWARD_ONLY;
            BridgeClient.StreamCollector collector = streamed.collector;
            Object chunk = client.pollChunk(collector, timeoutMillis);
            boolean done = !result.absorbChunk(chunk);
            if (eager) {
                while (!done) {
                    chunk = client.pollChunk(collector, timeoutMillis);
                    done = !result.absorbChunk(chunk);
                }
                result.exhausted = true;
            } else {
                result.chunkSource = new ChunkSource(client, collector, timeoutMillis);
            }
            return result;
        }

        /** Appends one chunk; returns false when it was the final (done) chunk. */
        private boolean absorbChunk(Object chunk) throws SQLException {
            if (chunk == null) {
                exhausted = true;
                return false;
            }
            Map<String, Object> map = asMap(chunk);

            // Absorb the final chunk's payload BEFORE honoring done: the last chunk
            // carries rows too (bridge flushes the remainder with done=true).
            List<Object> chunkColumns = asList(map.get("columns"));
            for (Object item : chunkColumns) {
                Map<String, Object> column = asMap(item);
                String name = stringValue(column.get("name"));
                String typeName = stringValue(column.get("typeName"));
                boolean known = false;
                for (Column existing : columns) {
                    if (existing.name.equals(name)) {
                        known = true;
                        // The bridge keeps merging types as later rows arrive; adopt its
                        // converged value so mixed columns (INTEGER+DOUBLE) do not stay
                        // pinned to the first chunk's type.
                        if (!existing.typeName.equals(typeName)) {
                            existing.typeName = typeName;
                            existing.sqlType = sqlType(typeName);
                        }
                        break;
                    }
                }
                if (!known) {
                    columns.add(new Column(name, typeName, sqlType(typeName),
                            booleanValue(column.get("nullable")),
                            columns.size() + 1, ""));
                }
            }

            List<Object> chunkRows = asList(map.get("rows"));
            for (Object item : chunkRows) {
                List<Object> sourceRow = asList(item);
                if (sourceRow.size() == columns.size()) {
                    // Fast path: the bridge pads rows to the chunk's column width, and
                    // Json.parse rows are mutable ArrayLists - store them as-is so
                    // FIX-503 updateRow can mutate them in place.
                    rows.add(sourceRow);
                } else {
                    // Defensive path for width drift (bridge version skew): pad to the
                    // current master width; reads of missing cells return null.
                    List<Object> padded = new ArrayList<>(Collections.nCopies(columns.size(), null));
                    for (int i = 0; i < Math.min(sourceRow.size(), columns.size()); i++) {
                        padded.set(i, sourceRow.get(i));
                    }
                    rows.add(padded);
                }
            }

            if (Boolean.TRUE.equals(map.get("done"))) {
                String tableName = singleSourceCollection(map.get("sourceCollections"));
                if (!tableName.isEmpty()) {
                    for (Column column : columns) {
                        if (column.tableName.isEmpty()) {
                            column.tableName = tableName;
                        }
                    }
                }
                exhausted = true;
                return false;
            }
            return true;
        }

        boolean needsFetch() {
            return chunkSource != null && !exhausted;
        }

        boolean fetchMore() throws SQLException {
            if (!needsFetch()) {
                return false;
            }
            return absorbChunk(chunkSource.fetch());
        }

        int declaredType() {
            return declaredType;
        }

        private static final class ChunkSource {
            private final BridgeClient client;
            private final BridgeClient.StreamCollector collector;
            private final long timeoutMillis;

            ChunkSource(BridgeClient client, BridgeClient.StreamCollector collector, long timeoutMillis) {
                this.client = client;
                this.collector = collector;
                this.timeoutMillis = timeoutMillis;
            }

            Object fetch() throws SQLException {
                return client.pollChunk(collector, timeoutMillis);
            }
        }

        static QueryResult fromBridge(Object response) throws SQLException {
            Map<String, Object> map = asMap(response);
            String tableName = singleSourceCollection(map.get("sourceCollections"));
            List<Column> columns = new ArrayList<>();
            for (Object item : asList(map.get("columns"))) {
                Map<String, Object> column = asMap(item);
                String name = stringValue(column.get("name"));
                String typeName = stringValue(column.get("typeName"));
                columns.add(new Column(
                        name,
                        typeName,
                        sqlType(typeName),
                        booleanValue(column.get("nullable")),
                        intValue(column.get("ordinal"), columns.size() + 1),
                        tableName));
            }

            List<List<Object>> rows = new ArrayList<>();
            for (Object item : asList(map.get("rows"))) {
                rows.add(asList(item));
            }

            return new QueryResult(columns, rows);
        }

        private static String singleSourceCollection(Object value) throws SQLException {
            if (value == null) {
                return "";
            }

            List<Object> collections = asList(value);
            return collections.size() == 1 ? stringValue(collections.get(0)) : "";
        }

        private Map<String, Integer> lookup;
        private int lookupWatermark;

        int findColumn(String name) throws SQLException {
            if (lookup == null || lookupWatermark != columns.size()) {
                Map<String, Integer> rebuilt = new HashMap<>(columns.size() * 2);
                for (int i = 0; i < columns.size(); i++) {
                    // putIfAbsent preserves the JDBC "first matching column wins" rule.
                    rebuilt.putIfAbsent(columns.get(i).name.toLowerCase(Locale.ROOT), i);
                }
                lookup = rebuilt;
                lookupWatermark = columns.size();
            }
            Integer index = lookup.get(name.toLowerCase(Locale.ROOT));
            if (index != null && columns.get(index).name.equalsIgnoreCase(name)) {
                return index;
            }
            // Exact-semantics fallback for fold divergences (e.g. U+0130).
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name.equalsIgnoreCase(name)) {
                    return i;
                }
            }
            throw new SQLException("Column not found: " + name);
        }
    }

    private static final class SqlParameterBinder {
        private SqlParameterBinder() {
        }

        static Rewrite rewrite(String sql) {
            if (sql == null || sql.indexOf('?') < 0) {
                return new Rewrite(sql, Collections.emptyList());
            }

            StringBuilder rewritten = new StringBuilder(sql.length() + 8);
            List<Integer> order = new ArrayList<>();
            boolean singleQuote = false;
            boolean doubleQuote = false;
            int parameter = 0;

            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'' && !doubleQuote) {
                    singleQuote = !singleQuote;
                    rewritten.append(c);
                } else if (c == '"' && !singleQuote) {
                    doubleQuote = !doubleQuote;
                    rewritten.append(c);
                } else if (c == '?' && !singleQuote && !doubleQuote) {
                    rewritten.append('@').append(parameter);
                    order.add(parameter);
                    parameter++;
                } else {
                    rewritten.append(c);
                }
            }

            return new Rewrite(rewritten.toString(), order);
        }

        static int countParameters(String sql) {
            return rewrite(sql).parameterOrder.size();
        }

        /**
         * Replaces each '?' outside string literals with the JSON text of the
         * corresponding parameter (extended-JSON nodes for typed values). Used for
         * INSERT, whose LiteDB grammar rejects @N placeholders.
         */
        static String inlineParameters(String sql, List<Object> values) throws SQLException {
            int placeholders = countParameters(sql);
            if (placeholders != values.size()) {
                throw new SQLException(
                        "Parameter count mismatch: statement has " + placeholders
                                + " placeholders but " + values.size() + " values were bound.",
                        "HY000");
            }

            StringBuilder out = new StringBuilder(sql.length() + 16);
            boolean singleQuote = false;
            boolean doubleQuote = false;
            int index = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'' && !doubleQuote) {
                    singleQuote = !singleQuote;
                    out.append(c);
                } else if (c == '"' && !singleQuote) {
                    doubleQuote = !doubleQuote;
                    out.append(c);
                } else if (c == '?' && !singleQuote && !doubleQuote) {
                    out.append(Json.write(values.get(index++)));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static final class Rewrite {
            final String sql;
            final List<Integer> parameterOrder;

            Rewrite(String sql, List<Integer> parameterOrder) {
                this.sql = sql;
                this.parameterOrder = parameterOrder;
            }
        }
    }
}
