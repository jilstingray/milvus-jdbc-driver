package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;

final class ResultSetMetaDataHandler implements InvocationHandler {
    private final List<ColumnInfo> columns;

    ResultSetMetaDataHandler(List<ColumnInfo> columns) {
        this.columns = columns;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getColumnCount":
                return columns.size();
            case "getColumnName":
            case "getColumnLabel":
                return column(args).name;
            case "getColumnType":
                return column(args).jdbcType;
            case "getColumnTypeName":
                return column(args).typeName;
            case "getTableName":
            case "getSchemaName":
            case "getCatalogName":
                return "";
            case "isNullable":
                return column(args).nullable;
            case "isAutoIncrement":
            case "isCaseSensitive":
            case "isCurrency":
            case "isDefinitelyWritable":
            case "isReadOnly":
            case "isSearchable":
            case "isSigned":
            case "isWritable":
                return false;
            case "getPrecision":
                return column(args).precision;
            case "getScale":
                return column(args).scale;
            case "getColumnDisplaySize":
                return column(args).precision > 0 ? column(args).precision : 32;
            case "getColumnClassName":
                return Object.class.getName();
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Cannot unwrap to " + ((Class<?>) args[0]).getName());
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                throw Unsupported.feature("ResultSetMetaData." + name);
        }
    }

    private ColumnInfo column(Object[] args) throws SQLException {
        int index = ((Integer) args[0]) - 1;
        if (index < 0 || index >= columns.size()) {
            throw new SQLException("Column index out of bounds: " + args[0]);
        }
        return columns.get(index);
    }
}
