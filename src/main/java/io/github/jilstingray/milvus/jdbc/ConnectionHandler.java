package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;

final class ConnectionHandler implements InvocationHandler {
    private final MilvusConnection connection;

    ConnectionHandler(MilvusConnection connection) {
        this.connection = connection;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "createStatement":
                connection.ensureOpen();
                return JdbcProxy.statement(connection);
            case "prepareStatement":
                connection.ensureOpen();
                return JdbcProxy.preparedStatement(connection, (String) args[0]);
            case "getMetaData":
                return JdbcProxy.databaseMetaData(connection);
            case "close":
                connection.close();
                return null;
            case "isClosed":
                return connection.isClosed();
            case "isValid":
                return !connection.isClosed();
            case "getCatalog":
            case "getSchema":
                return connection.database();
            case "setCatalog":
            case "setSchema":
                connection.setDatabase((String) args[0]);
                return null;
            case "getAutoCommit":
                return connection.autoCommit();
            case "setAutoCommit":
                connection.setAutoCommit((Boolean) args[0]);
                return null;
            case "commit":
            case "rollback":
                return null;
            case "isReadOnly":
                return connection.isReadOnly();
            case "setReadOnly":
                connection.setReadOnly((Boolean) args[0]);
                return null;
            case "nativeSQL":
                return args[0];
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) {
                    return proxy;
                }
                if (((Class<?>) args[0]).isInstance(connection)) {
                    return connection;
                }
                throw new SQLException("Cannot unwrap to " + ((Class<?>) args[0]).getName());
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy) || ((Class<?>) args[0]).isInstance(connection);
            case "getWarnings":
                return null;
            case "clearWarnings":
            case "abort":
                if ("abort".equals(name)) {
                    connection.close();
                }
                return null;
            case "getNetworkTimeout":
                return 0;
            case "setNetworkTimeout":
                return null;
            case "getTypeMap":
                return new HashMap<>();
            case "setTypeMap":
                return null;
            case "toString":
                return "MilvusConnection[" + connection.jdbcUrl() + "]";
            default:
                if (name.startsWith("set") || name.startsWith("release")) {
                    return null;
                }
                throw Unsupported.feature("Connection." + name);
        }
    }
}
