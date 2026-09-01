package io.github.jilstingray.milvus.jdbc;

import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DatabaseMetaDataHandler implements InvocationHandler {
    private final MilvusConnection connection;

    DatabaseMetaDataHandler(MilvusConnection connection) {
        this.connection = connection;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getURL":
                return connection.jdbcUrl();
            case "getUserName":
                return "";
            case "getDriverName":
                return "Milvus JDBC Driver";
            case "getDriverVersion":
                return MilvusDriver.MAJOR_VERSION + "." + MilvusDriver.MINOR_VERSION;
            case "getDriverMajorVersion":
                return MilvusDriver.MAJOR_VERSION;
            case "getDriverMinorVersion":
                return MilvusDriver.MINOR_VERSION;
            case "getDatabaseProductName":
                return "Milvus";
            case "getDatabaseProductVersion":
                return connection.serverVersion();
            case "getDatabaseMajorVersion":
                return versionPart(connection.serverVersion(), 0);
            case "getDatabaseMinorVersion":
                return versionPart(connection.serverVersion(), 1);
            case "getConnection":
                return JdbcProxy.connection(connection);
            case "getTables":
                return JdbcProxy.resultSet(tables((String) args[0], (String) args[2]));
            case "getColumns":
                return JdbcProxy.resultSet(columns((String) args[0], (String) args[2], (String) args[3]), columnMetadataColumns());
            case "getCatalogs":
                return JdbcProxy.resultSet(catalogs());
            case "getSchemas":
                return args == null
                    ? JdbcProxy.resultSet(schemas(null, null))
                    : JdbcProxy.resultSet(schemas((String) args[0], (String) args[1]));
            case "supportsTransactions":
                return false;
            case "getDefaultTransactionIsolation":
                return Connection.TRANSACTION_NONE;
            case "storesLowerCaseIdentifiers":
            case "storesUpperCaseIdentifiers":
                return false;
            case "storesMixedCaseIdentifiers":
            case "supportsMixedCaseIdentifiers":
                return true;
            case "getIdentifierQuoteString":
                return "`";
            case "getSearchStringEscape":
                return "\\";
            case "getTableTypes":
                return JdbcProxy.resultSet(List.of(row("TABLE_TYPE", "COLLECTION")));
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
                throw Unsupported.feature("DatabaseMetaData." + name);
        }
    }

    private QueryResult catalogs() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String database : allDatabases()) {
            rows.add(row("TABLE_CAT", database));
        }
        return QueryResult.rows(rows);
    }

    private QueryResult schemas(String catalog, String schemaPattern) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String database : databases(catalog)) {
            if (matches(database, schemaPattern)) {
                rows.add(row("TABLE_SCHEM", database, "TABLE_CATALOG", database));
            }
        }
        return QueryResult.rows(rows);
    }

    private QueryResult tables(String catalog, String tableNamePattern) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String database : databases(catalog)) {
            QueryResult listed = connection.executor().showCollections(database);
            for (Map<String, Object> table : listed.rows()) {
                String name = String.valueOf(table.get("TABLE_NAME"));
                if (matches(name, tableNamePattern)) {
                    rows.add(table);
                }
            }
        }
        return QueryResult.rows(rows, tableColumns());
    }

    private List<Map<String, Object>> columns(String catalog, String tableNamePattern, String columnNamePattern) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String database : databases(catalog)) {
            for (Map<String, Object> table : connection.executor().showCollections(database).rows()) {
                String tableName = String.valueOf(table.get("TABLE_NAME"));
                if (!matches(tableName, tableNamePattern)) {
                    continue;
                }
                DescribeCollectionResp description = connection.client().describeCollection(DescribeCollectionReq.builder()
                        .databaseName(database)
                        .collectionName(tableName)
                        .build());
                CreateCollectionReq.CollectionSchema schema = description.getCollectionSchema();
                if (schema == null) {
                    continue;
                }
                int ordinal = 1;
                for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
                    if (!matches(field.getName(), columnNamePattern)) {
                        ordinal++;
                        continue;
                    }
                    JdbcType jdbcType = jdbcType(field);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("TABLE_CAT", database);
                    row.put("TABLE_SCHEM", database);
                    row.put("TABLE_NAME", tableName);
                    row.put("COLUMN_NAME", field.getName());
                    row.put("DATA_TYPE", jdbcType.type);
                    row.put("TYPE_NAME", jdbcType.name);
                    row.put("COLUMN_SIZE", columnSize(field));
                    row.put("BUFFER_LENGTH", null);
                    row.put("DECIMAL_DIGITS", decimalDigits(field));
                    row.put("NUM_PREC_RADIX", 10);
                    row.put("NULLABLE", Boolean.TRUE.equals(field.getIsNullable()) ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
                    row.put("REMARKS", field.getDescription());
                    row.put("COLUMN_DEF", field.getDefaultValue() == null ? null : String.valueOf(field.getDefaultValue()));
                    row.put("SQL_DATA_TYPE", null);
                    row.put("SQL_DATETIME_SUB", null);
                    row.put("CHAR_OCTET_LENGTH", field.getMaxLength());
                    row.put("ORDINAL_POSITION", ordinal);
                    row.put("IS_NULLABLE", Boolean.TRUE.equals(field.getIsNullable()) ? "YES" : "NO");
                    row.put("SCOPE_CATALOG", null);
                    row.put("SCOPE_SCHEMA", null);
                    row.put("SCOPE_TABLE", null);
                    row.put("SOURCE_DATA_TYPE", null);
                    row.put("IS_AUTOINCREMENT", Boolean.TRUE.equals(field.getAutoID()) ? "YES" : "NO");
                    row.put("IS_GENERATEDCOLUMN", Boolean.TRUE.equals(field.getIsFunctionOutput()) ? "YES" : "NO");
                    rows.add(row);
                    ordinal++;
                }
            }
        }
        return rows;
    }

    private List<String> databases(String catalog) throws SQLException {
        if (catalog != null && !catalog.isBlank()) {
            return List.of(catalog);
        }
        if (connection.hasDatabase()) {
            return List.of(connection.database());
        }
        return allDatabases();
    }

    private List<String> allDatabases() throws SQLException {
        List<String> databases = new ArrayList<>();
        for (Map<String, Object> row : connection.executor().showDatabases().rows()) {
            databases.add(String.valueOf(row.get("DATABASE_NAME")));
        }
        return databases;
    }

    private static int versionPart(String version, int index) {
        if (version == null) {
            return 0;
        }
        String normalized = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = normalized.split("[.-]");
        if (parts.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean matches(String value, String sqlPattern) {
        if (sqlPattern == null || sqlPattern.isBlank() || "%".equals(sqlPattern)) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < sqlPattern.length(); i++) {
            char c = sqlPattern.charAt(i);
            if (c == '%') regex.append(".*");
            else if (c == '_') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return value.matches(regex.toString());
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static List<ColumnInfo> tableColumns() {
        return List.of(
                new ColumnInfo("TABLE_CAT", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("TABLE_NAME", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("TABLE_TYPE", Types.VARCHAR, "VARCHAR")
        );
    }

    private static List<ColumnInfo> columnMetadataColumns() {
        return List.of(
                new ColumnInfo("TABLE_CAT", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("TABLE_NAME", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("COLUMN_NAME", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("DATA_TYPE", Types.INTEGER, "INTEGER"),
                new ColumnInfo("TYPE_NAME", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("COLUMN_SIZE", Types.INTEGER, "INTEGER"),
                new ColumnInfo("BUFFER_LENGTH", Types.INTEGER, "INTEGER"),
                new ColumnInfo("DECIMAL_DIGITS", Types.INTEGER, "INTEGER"),
                new ColumnInfo("NUM_PREC_RADIX", Types.INTEGER, "INTEGER"),
                new ColumnInfo("NULLABLE", Types.INTEGER, "INTEGER"),
                new ColumnInfo("REMARKS", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("COLUMN_DEF", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("SQL_DATA_TYPE", Types.INTEGER, "INTEGER"),
                new ColumnInfo("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER"),
                new ColumnInfo("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER"),
                new ColumnInfo("ORDINAL_POSITION", Types.INTEGER, "INTEGER"),
                new ColumnInfo("IS_NULLABLE", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("SCOPE_CATALOG", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("SCOPE_SCHEMA", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("SCOPE_TABLE", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("SOURCE_DATA_TYPE", Types.SMALLINT, "SMALLINT"),
                new ColumnInfo("IS_AUTOINCREMENT", Types.VARCHAR, "VARCHAR"),
                new ColumnInfo("IS_GENERATEDCOLUMN", Types.VARCHAR, "VARCHAR")
        );
    }

    private static JdbcType jdbcType(CreateCollectionReq.FieldSchema field) {
        DataType type = field.getDataType();
        if (type == null) return new JdbcType(Types.OTHER, "UNKNOWN");
        switch (type) {
            case Bool: return new JdbcType(Types.BOOLEAN, "BOOL");
            case Int8: return new JdbcType(Types.TINYINT, "INT8");
            case Int16: return new JdbcType(Types.SMALLINT, "INT16");
            case Int32: return new JdbcType(Types.INTEGER, "INT32");
            case Int64: return new JdbcType(Types.BIGINT, "INT64");
            case Float: return new JdbcType(Types.FLOAT, "FLOAT");
            case Double: return new JdbcType(Types.DOUBLE, "DOUBLE");
            case String: return new JdbcType(Types.VARCHAR, "STRING");
            case VarChar: return new JdbcType(Types.VARCHAR, "VARCHAR");
            case Text: return new JdbcType(Types.LONGVARCHAR, "TEXT");
            case JSON: return new JdbcType(Types.JAVA_OBJECT, "JSON");
            case Array: return new JdbcType(Types.ARRAY, field.getElementType() == null ? "ARRAY" : "ARRAY<" + field.getElementType() + ">");
            case Timestamptz: return new JdbcType(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ");
            case Geometry: return new JdbcType(Types.OTHER, "GEOMETRY");
            case BinaryVector: return new JdbcType(Types.ARRAY, "BINARY_VECTOR");
            case FloatVector: return new JdbcType(Types.ARRAY, "FLOAT_VECTOR");
            case Float16Vector: return new JdbcType(Types.ARRAY, "FLOAT16_VECTOR");
            case BFloat16Vector: return new JdbcType(Types.ARRAY, "BFLOAT16_VECTOR");
            case SparseFloatVector: return new JdbcType(Types.ARRAY, "SPARSE_FLOAT_VECTOR");
            case Int8Vector: return new JdbcType(Types.ARRAY, "INT8_VECTOR");
            default: return new JdbcType(Types.OTHER, type.name());
        }
    }

    private static Integer columnSize(CreateCollectionReq.FieldSchema field) {
        if (field.getDimension() != null) return field.getDimension();
        if (field.getMaxLength() != null) return field.getMaxLength();
        return 0;
    }

    private static Integer decimalDigits(CreateCollectionReq.FieldSchema field) {
        DataType type = field.getDataType();
        return type == DataType.Float || type == DataType.Double ? 8 : 0;
    }

    private static final class JdbcType {
        final int type;
        final String name;

        JdbcType(int type, String name) {
            this.type = type;
            this.name = name;
        }
    }
}
