package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

final class JdbcProxy {
    private JdbcProxy() {
    }

    static Connection connection(MilvusConnection connection) {
        return proxy(Connection.class, new ConnectionHandler(connection));
    }

    static Statement statement(MilvusConnection connection) {
        return proxy(Statement.class, new StatementHandler(connection));
    }

    static PreparedStatement preparedStatement(MilvusConnection connection, String sql) {
        return proxy(PreparedStatement.class, new PreparedStatementHandler(connection, sql));
    }

    static ResultSet resultSet(List<Map<String, Object>> rows) {
        return proxy(ResultSet.class, new ResultSetHandler(rows));
    }

    static ResultSet resultSet(QueryResult result) {
        return proxy(ResultSet.class, new ResultSetHandler(result.rows(), result.columns()));
    }

    static ResultSet resultSet(List<Map<String, Object>> rows, List<ColumnInfo> columns) {
        return proxy(ResultSet.class, new ResultSetHandler(rows, columns));
    }

    static ResultSetMetaData resultSetMetaData(List<ColumnInfo> columns) {
        return proxy(ResultSetMetaData.class, new ResultSetMetaDataHandler(columns));
    }

    static DatabaseMetaData databaseMetaData(MilvusConnection connection) {
        return proxy(DatabaseMetaData.class, new DatabaseMetaDataHandler(connection));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, handler);
    }
}
