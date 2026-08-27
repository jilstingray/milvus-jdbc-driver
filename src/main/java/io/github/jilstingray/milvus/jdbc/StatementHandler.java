package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;

class StatementHandler implements InvocationHandler {
    protected final MilvusConnection connection;
    protected ResultSet currentResultSet;
    protected int updateCount = -1;
    private boolean closed;
    private int maxRows;
    private int queryTimeout;

    StatementHandler(MilvusConnection connection) {
        this.connection = connection;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "execute":
                return execute((String) args[0]).hasResultSet();
            case "executeQuery":
                QueryResult query = execute((String) args[0]);
                if (!query.hasResultSet()) {
                    throw new SQLException("SQL did not produce a ResultSet");
                }
                return currentResultSet;
            case "executeUpdate":
            case "executeLargeUpdate":
                QueryResult update = execute((String) args[0]);
                if (update.hasResultSet()) {
                    throw new SQLException("SQL produced a ResultSet");
                }
                return method.getReturnType() == Long.TYPE ? (long) update.updateCount() : update.updateCount();
            case "getResultSet":
                return currentResultSet;
            case "getUpdateCount":
                return updateCount;
            case "getLargeUpdateCount":
                return (long) updateCount;
            case "close":
                closed = true;
                currentResultSet = null;
                return null;
            case "isClosed":
                return closed;
            case "getConnection":
                return JdbcProxy.connection(connection);
            case "setMaxRows":
                maxRows = ((Number) args[0]).intValue();
                return null;
            case "getMaxRows":
                return maxRows;
            case "setQueryTimeout":
                queryTimeout = ((Number) args[0]).intValue();
                return null;
            case "getQueryTimeout":
                return queryTimeout;
            case "cancel":
            case "clearWarnings":
            case "clearBatch":
            case "closeOnCompletion":
                return null;
            case "getWarnings":
                return null;
            case "isCloseOnCompletion":
                return false;
            case "getMoreResults":
                currentResultSet = null;
                updateCount = -1;
                return false;
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Cannot unwrap to " + ((Class<?>) args[0]).getName());
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            case "toString":
                return "MilvusStatement";
            default:
                if (name.startsWith("set") || name.startsWith("add")) {
                    return null;
                }
                Object defaultValue = Unsupported.defaultValue(method.getReturnType());
                if (defaultValue != null || method.getReturnType().isPrimitive()) {
                    return defaultValue;
                }
                throw Unsupported.feature("Statement." + name);
        }
    }

    protected QueryResult execute(String sql) throws SQLException {
        ensureOpen();
        QueryResult result = connection.executor().execute(sql);
        currentResultSet = result.hasResultSet() ? JdbcProxy.resultSet(result) : null;
        updateCount = result.hasResultSet() ? -1 : result.updateCount();
        return result;
    }

    protected void ensureOpen() throws SQLException {
        connection.ensureOpen();
        if (closed) {
            throw new SQLException("Statement is closed");
        }
    }
}
