package io.github.jilstingray.milvus.jdbc;

import java.sql.Types;

final class ColumnInfo {
    final String name;
    final int jdbcType;
    final String typeName;
    final int precision;
    final int scale;
    final int nullable;

    ColumnInfo(String name, Object sample) {
        this(name, jdbcType(sample), typeName(sample), 0, 0, java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    ColumnInfo(String name, int jdbcType, String typeName) {
        this(name, jdbcType, typeName, 0, 0, java.sql.ResultSetMetaData.columnNullableUnknown);
    }

    ColumnInfo(String name, int jdbcType, String typeName, int precision, int scale, int nullable) {
        this.name = name;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.precision = precision;
        this.scale = scale;
        this.nullable = nullable;
    }

    private static int jdbcType(Object sample) {
        if (sample instanceof Integer) return Types.INTEGER;
        if (sample instanceof Long) return Types.BIGINT;
        if (sample instanceof Float || sample instanceof Double) return Types.DOUBLE;
        if (sample instanceof Boolean) return Types.BOOLEAN;
        if (sample instanceof java.util.List) return Types.ARRAY;
        return Types.VARCHAR;
    }

    private static String typeName(Object sample) {
        if (sample instanceof Integer) return "INTEGER";
        if (sample instanceof Long) return "BIGINT";
        if (sample instanceof Float || sample instanceof Double) return "DOUBLE";
        if (sample instanceof Boolean) return "BOOLEAN";
        if (sample instanceof java.util.List) return "ARRAY";
        return "VARCHAR";
    }
}
