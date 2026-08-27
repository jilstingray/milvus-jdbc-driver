package io.github.jilstingray.milvus.jdbc;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.utility.request.GetServerVersionReq;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class MilvusConnection implements AutoCloseable {
    static final long DEFAULT_QUERY_LIMIT = 16384;
    private final String jdbcUrl;
    private final Properties properties;
    private final MilvusClientV2 client;
    private final SqlExecutor executor;
    private final long defaultQueryLimit;
    private String database;
    private String serverVersion;
    private boolean closed;
    private boolean readOnly;
    private boolean autoCommit = true;

    MilvusConnection(String jdbcUrl, Properties info) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        ParsedUrl parsed = parse(jdbcUrl);
        this.properties = new Properties();
        this.properties.putAll(parsed.queryProperties);
        if (info != null) {
            this.properties.putAll(info);
        }
        String databaseProperty = this.properties.getProperty("database");
        this.database = databaseProperty == null || databaseProperty.isBlank() ? parsed.database : databaseProperty;
        if (this.database == null || this.database.isBlank()) {
            throw new SQLException("Milvus JDBC URL must specify a database, for example jdbc:milvus://host:19530/default");
        }
        this.defaultQueryLimit = parseDefaultQueryLimit(this.properties);

        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder().uri(parsed.milvusUri);
        setIfPresent(builder::username, firstNonBlank(this.properties.getProperty("user"), this.properties.getProperty("username")));
        setIfPresent(builder::password, this.properties.getProperty("password"));
        setIfPresent(builder::token, this.properties.getProperty("token"));
        setIfPresent(builder::dbName, this.database);
        this.client = new MilvusClientV2(builder.build());
        this.executor = new SqlExecutor(this);
    }

    MilvusClientV2 client() throws SQLException {
        ensureOpen();
        return client;
    }

    SqlExecutor executor() {
        return executor;
    }

    String database() {
        return database == null ? "" : database;
    }

    void setDatabase(String database) throws SQLException {
        if (database == null || database.isBlank()) {
            throw new SQLException("Milvus JDBC connection requires a database");
        }
        if (!this.database.equals(database)) {
            throw new SQLException("Milvus JDBC connections are scoped to database '" + this.database
                    + "'; open a new connection for database '" + database + "'");
        }
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String serverVersion() throws SQLException {
        ensureOpen();
        if (serverVersion == null) {
            serverVersion = client.getServerVersionV2(GetServerVersionReq.builder().detail(true).build()).getVersion();
            if (serverVersion == null || serverVersion.isBlank()) {
                serverVersion = client.getServerVersion();
            }
        }
        return serverVersion == null ? "" : serverVersion;
    }

    ConsistencyLevel consistencyLevel() {
        String value = properties.getProperty("consistencyLevel");
        if (value == null || value.isBlank()) {
            return null;
        }
        return ConsistencyLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    long defaultQueryLimit() {
        return defaultQueryLimit;
    }

    void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }

    boolean isClosed() {
        return closed;
    }

    boolean isReadOnly() {
        return readOnly;
    }

    void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    boolean autoCommit() {
        return autoCommit;
    }

    void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            client.close();
        }
    }

    private static ParsedUrl parse(String jdbcUrl) throws SQLException {
        if (jdbcUrl == null || !jdbcUrl.startsWith(MilvusDriver.URL_PREFIX)) {
            throw new SQLException("Milvus JDBC URL must be jdbc:milvus://host:port/database");
        }
        String raw = jdbcUrl.substring(MilvusDriver.URL_PREFIX.length());
        if (raw.isBlank() || raw.startsWith("http://") || raw.startsWith("https://")) {
            throw new SQLException("Milvus JDBC URL must be jdbc:milvus://host:port/database");
        }
        try {
            URI uri = new URI("milvus://" + raw);
            if (uri.getHost() == null) {
                throw new SQLException("Milvus JDBC URL must be jdbc:milvus://host:port/database");
            }
            String path = uri.getPath();
            String database = "";
            if (path != null && path.length() > 1) {
                database = path.substring(1);
            }
            String milvusUri = "http://" + uri.getRawAuthority();
            return new ParsedUrl(milvusUri, database, queryParams(uri.getRawQuery()));
        } catch (URISyntaxException e) {
            throw new SQLException("Invalid Milvus JDBC URL: " + jdbcUrl, e);
        }
    }

    private static String suffix(URI uri) {
        StringBuilder suffix = new StringBuilder();
        if (uri.getRawQuery() != null) {
            suffix.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            suffix.append('#').append(uri.getRawFragment());
        }
        return suffix.toString();
    }

    static Map<String, String> queryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) {
            return params;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                params.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
        }
        return params;
    }

    static long parseDefaultQueryLimit(Properties properties) throws SQLException {
        String value = properties.getProperty("defaultQueryLimit");
        if (value == null || value.isBlank()) {
            return DEFAULT_QUERY_LIMIT;
        }
        try {
            long limit = Long.parseLong(value.trim());
            if (limit <= 0 || limit > DEFAULT_QUERY_LIMIT) {
                throw new SQLException("defaultQueryLimit must be between 1 and " + DEFAULT_QUERY_LIMIT);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new SQLException("defaultQueryLimit must be a positive integer", e);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static void setIfPresent(java.util.function.Function<String, ?> setter, String value) {
        if (value != null && !value.isBlank()) {
            setter.apply(value);
        }
    }

    private static final class ParsedUrl {
        final String milvusUri;
        final String database;
        final Map<String, String> queryProperties;

        ParsedUrl(String milvusUri, String database, Map<String, String> queryProperties) {
            this.milvusUri = milvusUri;
            this.database = database;
            this.queryProperties = queryProperties;
        }
    }
}
