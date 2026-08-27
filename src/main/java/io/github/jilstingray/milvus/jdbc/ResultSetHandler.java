package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ResultSetHandler implements InvocationHandler {
    private final List<Map<String, Object>> rows;
    private final List<ColumnInfo> columns;
    private int cursor = -1;
    private boolean closed;
    private Object lastValue;

    ResultSetHandler(List<Map<String, Object>> rows) {
        this(rows, null);
    }

    ResultSetHandler(List<Map<String, Object>> rows, List<ColumnInfo> columns) {
        this.rows = rows == null ? List.of() : rows;
        this.columns = columns == null ? inferColumns(this.rows) : columns;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "next":
                ensureOpen();
                if (cursor + 1 < rows.size()) {
                    cursor++;
                    return true;
                }
                cursor = rows.size();
                return false;
            case "close":
                closed = true;
                return null;
            case "isClosed":
                return closed;
            case "wasNull":
                return lastValue == null;
            case "getMetaData":
                return JdbcProxy.resultSetMetaData(columns);
            case "findColumn":
                return findColumn((String) args[0]) + 1;
            case "getObject":
                lastValue = value(args[0]);
                return lastValue;
            case "getString":
                lastValue = value(args[0]);
                return lastValue == null ? null : String.valueOf(lastValue);
            case "getInt":
                return number(args[0]).intValue();
            case "getLong":
                return number(args[0]).longValue();
            case "getDouble":
                return number(args[0]).doubleValue();
            case "getFloat":
                return number(args[0]).floatValue();
            case "getBoolean":
                lastValue = value(args[0]);
                return lastValue instanceof Boolean ? lastValue : Boolean.parseBoolean(String.valueOf(lastValue));
            case "beforeFirst":
                cursor = -1;
                return null;
            case "getRow":
                return cursor + 1;
            case "isBeforeFirst":
                return cursor < 0;
            case "isAfterLast":
                return cursor >= rows.size();
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("Cannot unwrap to " + ((Class<?>) args[0]).getName());
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                Object defaultValue = Unsupported.defaultValue(method.getReturnType());
                if (defaultValue != null || method.getReturnType().isPrimitive()) {
                    return defaultValue;
                }
                throw Unsupported.feature("ResultSet." + name);
        }
    }

    private Number number(Object key) throws SQLException {
        lastValue = value(key);
        if (lastValue == null) {
            return 0;
        }
        if (lastValue instanceof Number) {
            return (Number) lastValue;
        }
        return Double.parseDouble(String.valueOf(lastValue));
    }

    private Object value(Object key) throws SQLException {
        ensureOpen();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("Cursor is not positioned on a row");
        }
        if (key instanceof Integer) {
            int index = (Integer) key - 1;
            if (index < 0 || index >= columns.size()) {
                throw new SQLException("Column index out of bounds: " + key);
            }
            return rows.get(cursor).get(columns.get(index).name);
        }
        return rows.get(cursor).get(columns.get(findColumn((String) key)).name);
    }

    private int findColumn(String label) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(label)) {
                return i;
            }
        }
        throw new SQLException("Unknown column: " + label);
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed");
        }
    }

    private static List<ColumnInfo> inferColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
            columns.add(new ColumnInfo(entry.getKey(), entry.getValue()));
        }
        return columns;
    }
}
