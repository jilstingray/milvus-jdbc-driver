package io.github.jilstingray.milvus.jdbc;

import java.sql.SQLFeatureNotSupportedException;

final class Unsupported {
    private Unsupported() {
    }

    static SQLFeatureNotSupportedException feature(String method) {
        return new SQLFeatureNotSupportedException(method + " is not supported by the Milvus JDBC driver yet");
    }

    static Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        if (type == Character.TYPE) return '\0';
        return null;
    }
}
