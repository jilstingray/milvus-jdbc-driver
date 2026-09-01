package io.github.jilstingray.milvus.jdbc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcLexer;
import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParser;
import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParserBaseVisitor;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;

import io.milvus.v2.service.utility.request.AlterAliasReq;
import io.milvus.v2.service.utility.request.CreateAliasReq;
import io.milvus.v2.service.utility.request.DropAliasReq;
import io.milvus.v2.service.utility.request.ListAliasesReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.request.RenameCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.database.request.AlterDatabasePropertiesReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.request.DropDatabaseReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.DropIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.ListPartitionsReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import io.milvus.v2.service.rbac.request.CreateRoleReq;
import io.milvus.v2.service.rbac.request.CreateUserReq;
import io.milvus.v2.service.rbac.request.DropRoleReq;
import io.milvus.v2.service.rbac.request.DropUserReq;
import io.milvus.v2.service.rbac.request.GrantPrivilegeReq;
import io.milvus.v2.service.rbac.request.GrantRoleReq;
import io.milvus.v2.service.rbac.request.RevokePrivilegeReq;
import io.milvus.v2.service.rbac.request.RevokeRoleReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SqlExecutor {
    private final Gson gson = new Gson();
    private final MilvusConnection connection;

    SqlExecutor(MilvusConnection connection) {
        this.connection = connection;
    }

    QueryResult execute(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            throw new SQLException("SQL is empty");
        }
        try {
            ParsedSql parsed = parse(sql);
            QueryResult result = new ExecutorVisitor(parsed.tokens).visit(parsed.root.statement());
            if (result == null) {
                throw new SQLException("Unsupported Milvus SQL: " + sql);
            }
            return result;
        } catch (SqlRuntimeException e) {
            throw (SQLException) e.getCause();
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    QueryResult showCollections() throws SQLException {
        ListCollectionsResp resp = connection.client().listCollections();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : resp.getCollectionNames()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("TABLE_CAT", connection.database());
            row.put("TABLE_SCHEM", connection.database());
            row.put("TABLE_NAME", name);
            row.put("TABLE_TYPE", "COLLECTION");
            rows.add(row);
        }
        return QueryResult.rows(rows);
    }


    private QueryResult showDatabases() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.client().listDatabases().getDatabaseNames()) {
            rows.add(row("DATABASE_NAME", name));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("DATABASE_NAME", java.sql.Types.VARCHAR, "VARCHAR")));
    }

    private QueryResult createDatabase(String name) throws SQLException {
        connection.client().createDatabase(CreateDatabaseReq.builder().databaseName(name).build());
        return QueryResult.update(0);
    }

    private QueryResult alterDatabase(String name, Map<String, String> properties) throws SQLException {
        connection.client().alterDatabaseProperties(AlterDatabasePropertiesReq.builder().databaseName(name).properties(properties).build());
        return QueryResult.update(0);
    }

    private QueryResult dropDatabase(String name) throws SQLException {
        connection.client().dropDatabase(DropDatabaseReq.builder().databaseName(name).build());
        return QueryResult.update(0);
    }

    private QueryResult renameTable(String collection, String newName) throws SQLException {
        connection.client().renameCollection(
            RenameCollectionReq.builder().databaseName(connection.database()).collectionName(collection).newCollectionName(newName)
                .build());
        return QueryResult.update(0);
    }

    private QueryResult createPartition(String collection, String partition) throws SQLException {
        connection.client().createPartition(
            CreatePartitionReq.builder().databaseName(connection.database()).collectionName(collection).partitionName(partition).build());
        return QueryResult.update(0);
    }

    private QueryResult dropPartition(String collection, String partition) throws SQLException {
        connection.client().dropPartition(
            DropPartitionReq.builder().databaseName(connection.database()).collectionName(collection).partitionName(partition).build());
        return QueryResult.update(0);
    }

    private QueryResult showPartitions(String collection) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.client()
            .listPartitions(ListPartitionsReq.builder().databaseName(connection.database()).collectionName(collection).build())) {
            rows.add(row("PARTITION_NAME", name));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("PARTITION_NAME", java.sql.Types.VARCHAR, "VARCHAR")));
    }

    private QueryResult createAlias(String alias, String collection) throws SQLException {
        connection.client()
            .createAlias(CreateAliasReq.builder().databaseName(connection.database()).alias(alias).collectionName(collection).build());
        return QueryResult.update(0);
    }

    private QueryResult alterAlias(String alias, String collection) throws SQLException {
        connection.client()
            .alterAlias(AlterAliasReq.builder().databaseName(connection.database()).alias(alias).collectionName(collection).build());
        return QueryResult.update(0);
    }

    private QueryResult dropAlias(String alias) throws SQLException {
        connection.client().dropAlias(DropAliasReq.builder().databaseName(connection.database()).alias(alias).build());
        return QueryResult.update(0);
    }

    private QueryResult showAliases(String collection) throws SQLException {
        Object resp = connection.client()
            .listAliases(ListAliasesReq.builder().databaseName(connection.database()).collectionName(collection).build());
        return QueryResult.rows(List.of(row("ALIASES", String.valueOf(resp))));
    }

    private QueryResult createUser(String user, String password) throws SQLException {
        connection.client().createUser(CreateUserReq.builder().userName(user).password(password).build());
        return QueryResult.update(0);
    }

    private QueryResult dropUser(String user) throws SQLException {
        connection.client().dropUser(DropUserReq.builder().userName(user).build());
        return QueryResult.update(0);
    }

    private QueryResult createRole(String role) throws SQLException {
        connection.client().createRole(CreateRoleReq.builder().roleName(role).build());
        return QueryResult.update(0);
    }

    private QueryResult dropRole(String role) throws SQLException {
        connection.client().dropRole(DropRoleReq.builder().roleName(role).build());
        return QueryResult.update(0);
    }

    private QueryResult showUsers() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.client().listUsers()) {
            rows.add(row("USER_NAME", name));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("USER_NAME", java.sql.Types.VARCHAR, "VARCHAR")));
    }

    private QueryResult showRoles() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.client().listRoles()) {
            rows.add(row("ROLE_NAME", name));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("ROLE_NAME", java.sql.Types.VARCHAR, "VARCHAR")));
    }

    private QueryResult grantRole(String role, String user) throws SQLException {
        connection.client().grantRole(GrantRoleReq.builder().roleName(role).userName(user).build());
        return QueryResult.update(0);
    }

    private QueryResult revokeRole(String role, String user) throws SQLException {
        connection.client().revokeRole(RevokeRoleReq.builder().roleName(role).userName(user).build());
        return QueryResult.update(0);
    }

    private QueryResult grantPrivilege(String role, String objectType, String objectName, String privilege) throws SQLException {
        connection.client().grantPrivilege(
            GrantPrivilegeReq.builder().roleName(role).dbName(connection.database()).objectType(objectType).objectName(objectName)
                .privilege(privilege).build());
        return QueryResult.update(0);
    }

    private QueryResult revokePrivilege(String role, String objectType, String objectName, String privilege) throws SQLException {
        connection.client().revokePrivilege(
            RevokePrivilegeReq.builder().roleName(role).dbName(connection.database()).objectType(objectType).objectName(objectName)
                .privilege(privilege).build());
        return QueryResult.update(0);
    }

    private QueryResult load(String collection, String partition) throws SQLException {
        if (partition == null) {
            connection.client()
                .loadCollection(LoadCollectionReq.builder().databaseName(connection.database()).collectionName(collection).build());
        } else {
            connection.client().loadPartitions(
                LoadPartitionsReq.builder().databaseName(connection.database()).collectionName(collection)
                    .partitionNames(List.of(partition))
                    .build());
        }
        return QueryResult.update(0);
    }

    private QueryResult release(String collection, String partition) throws SQLException {
        if (partition == null) {
            connection.client()
                .releaseCollection(ReleaseCollectionReq.builder().databaseName(connection.database()).collectionName(collection).build());
        } else {
            connection.client().releasePartitions(
                ReleasePartitionsReq.builder().databaseName(connection.database()).collectionName(collection)
                    .partitionNames(List.of(partition))
                    .build());
        }
        return QueryResult.update(0);
    }

    private QueryResult flush(String collection) throws SQLException {
        connection.client().flush(FlushReq.builder().databaseName(connection.database()).collectionNames(List.of(collection)).build());
        return QueryResult.update(0);
    }

    private QueryResult count(String collection, String filter) throws SQLException {
        QueryReq.QueryReqBuilder builder =
            QueryReq.builder().databaseName(connection.database()).collectionName(collection).outputFields(List.of("count(*)"))
                .filter(filterOrEmpty(filter));
        QueryResp resp = connection.client().query(builder.build());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            rows.add(new LinkedHashMap<>(result.getEntity()));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("count(*)", java.sql.Types.BIGINT, "BIGINT")));
    }

    private QueryResult upsert(String collection, List<String> fields, List<List<Object>> values) throws SQLException {
        List<JsonObject> rows = insertRows(collection, fields, values, true);
        UpsertResp resp = connection.client()
            .upsert(UpsertReq.builder().databaseName(connection.database()).collectionName(collection).data(rows).build());
        return QueryResult.update(resp.getUpsertCnt());
    }

    private QueryResult update(String collection, String partition, Map<String, Object> updates, UpdateTarget target)
        throws SQLException {
        if (!target.isVectorSearch() && (target.filter == null || target.filter.isBlank())) {
            throw new SQLException("UPDATE requires a WHERE filter");
        }
        if (updates.isEmpty()) {
            throw new SQLException("UPDATE requires at least one SET assignment");
        }

        DescribeCollectionResp description;
        try {
            description = describe(collection);
        } catch (RuntimeException e) {
            throw new SQLException("Failed to describe collection '" + collection + "' for UPDATE", e);
        }
        Map<String, CreateCollectionReq.FieldSchema> schemaFields = schemaFields(description);
        String primaryField = primaryField(description);
        if (primaryField == null || primaryField.isBlank()) {
            throw new SQLException("Cannot UPDATE collection '" + collection + "' because its primary key field is unknown");
        }
        CreateCollectionReq.FieldSchema primarySchema = schemaFields.get(primaryField);
        boolean dynamicFields = Boolean.TRUE.equals(description.getEnableDynamicField());
        for (String field : updates.keySet()) {
            CreateCollectionReq.FieldSchema schema = schemaFields.get(field);
            if (primaryField.equals(field) || (schema != null && Boolean.TRUE.equals(schema.getIsPrimaryKey()))) {
                throw new SQLException("Cannot UPDATE primary key field '" + field + "'");
            }
            if (schema == null && !dynamicFields) {
                throw new SQLException("Cannot UPDATE unknown field '" + field + "' on collection '" + collection + "'");
            }
        }

        if (target.isVectorSearch()) {
            return updateByVectorSearch(collection, partition, updates, schemaFields, primaryField, target);
        }

        long queryLimit = queryLimit(collection, partition, target);
        if (queryLimit == 0) {
            return QueryResult.update(0);
        }

        if (updatesVectorField(updates, schemaFields)) {
            return updateByFullRowQuery(collection, partition, updates, schemaFields, primaryField, target.filter, queryLimit);
        }

        List<Object> ids = queryPrimaryKeys(collection, partition, primaryField, target.filter, queryLimit);
        if (ids.isEmpty()) {
            return QueryResult.update(0);
        }

        List<JsonObject> rows = new ArrayList<>();
        for (Object id : ids) {
            JsonObject row = new JsonObject();
            row.add(primaryField, toJson(id, primarySchema));
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                row.add(entry.getKey(), toJson(entry.getValue(), schemaFields.get(entry.getKey())));
            }
            rows.add(row);
        }

        List<UpsertReq.FieldPartialUpdateOp> fieldOps = new ArrayList<>();
        for (String field : updates.keySet()) {
            fieldOps.add(UpsertReq.FieldPartialUpdateOp.builder()
                .fieldName(field)
                .opType(UpsertReq.FieldPartialUpdateOp.OpType.REPLACE)
                .build());
        }

        UpsertReq.UpsertReqBuilder builder = UpsertReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .data(rows)
            .partialUpdate(true)
            .fieldOps(fieldOps);
        if (partition != null) {
            builder.partitionName(partition);
        }
        UpsertResp resp;
        try {
            resp = connection.client().upsert(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Milvus partial upsert failed while executing UPDATE on collection '" + collection + "'", e);
        }
        return QueryResult.update(resp.getUpsertCnt());
    }

    private long queryLimit(String collection, String partition, UpdateTarget target) throws SQLException {
        if (target.limit > 0) {
            return target.limit;
        }
        long matched = countMatched(collection, partition, target.filter);
        if (matched == 0) {
            return 0;
        }
        long maxUpdateRows = connection.defaultQueryLimit();
        if (matched > maxUpdateRows) {
            throw new SQLException("UPDATE matched " + matched + " rows, which exceeds defaultQueryLimit " + maxUpdateRows
                + ". Add LIMIT or narrow the WHERE filter.");
        }
        return matched;
    }

    private QueryResult updateByFullRowQuery(String collection, String partition, Map<String, Object> updates,
                                             Map<String, CreateCollectionReq.FieldSchema> schemaFields, String primaryField,
                                             String filter, long limit) throws SQLException {
        List<Map<String, Object>> matchedRows = queryRowsForUpdate(collection, partition, filter, limit);
        return updateRowsByFullRowUpsert(collection, partition, updates, schemaFields, primaryField, matchedRows);
    }

    private QueryResult updateByVectorSearch(String collection, String partition, Map<String, Object> updates,
                                             Map<String, CreateCollectionReq.FieldSchema> schemaFields, String primaryField,
                                             UpdateTarget target) throws SQLException {
        long limit = target.limit > 0 ? target.limit : 10;
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .annsField(target.vectorField)
            .data(List.of(new FloatVec(toFloatList(target.vectorValue))))
            .limit(limit)
            .outputFields(List.of("*"))
            .filter(filterOrEmpty(target.filter));
        if (partition != null) {
            builder.partitionNames(List.of(partition));
        }
        ConsistencyLevel level = connection.consistencyLevel();
        if (level != null) {
            builder.consistencyLevel(level);
        }
        builder.metricType(metricType(target.vectorOperator));

        SearchResp resp;
        try {
            resp = connection.client().search(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Failed to search rows for UPDATE on collection '" + collection + "'", e);
        }
        List<Map<String, Object>> matchedRows = new ArrayList<>();
        for (List<SearchResp.SearchResult> batch : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : batch) {
                matchedRows.add(new LinkedHashMap<>(result.getEntity()));
            }
        }
        return updateRowsByFullRowUpsert(collection, partition, updates, schemaFields, primaryField, matchedRows);
    }

    private QueryResult updateRowsByFullRowUpsert(String collection, String partition, Map<String, Object> updates,
                                                  Map<String, CreateCollectionReq.FieldSchema> schemaFields, String primaryField,
                                                  List<Map<String, Object>> matchedRows) throws SQLException {
        if (matchedRows.isEmpty()) {
            return QueryResult.update(0);
        }

        List<JsonObject> rows = new ArrayList<>();
        for (Map<String, Object> matchedRow : matchedRows) {
            if (!matchedRow.containsKey(primaryField)) {
                throw new SQLException("Milvus query did not return primary key field '" + primaryField + "' for UPDATE");
            }
            Map<String, Object> merged = new LinkedHashMap<>(matchedRow);
            merged.putAll(updates);

            JsonObject row = new JsonObject();
            for (Map.Entry<String, Object> entry : merged.entrySet()) {
                row.add(entry.getKey(), toJson(entry.getValue(), schemaFields.get(entry.getKey())));
            }
            rows.add(row);
        }

        UpsertReq.UpsertReqBuilder builder = UpsertReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .data(rows);
        if (partition != null) {
            builder.partitionName(partition);
        }
        UpsertResp resp;
        try {
            resp = connection.client().upsert(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Milvus full-row upsert failed while executing UPDATE on collection '" + collection + "'", e);
        }
        return QueryResult.update(resp.getUpsertCnt());
    }

    private QueryResult unsupported(String command) throws SQLException {
        throw new java.sql.SQLFeatureNotSupportedException(command + " is parsed but not implemented by this JDBC driver yet");
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private ParsedSql parse(String sql) throws SQLException {
        MilvusJdbcLexer lexer = new MilvusJdbcLexer(CharStreams.fromString(sql));
        ThrowingErrorListener listener = new ThrowingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MilvusJdbcParser parser = new MilvusJdbcParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        MilvusJdbcParser.RootContext root = parser.root();
        return new ParsedSql(tokens, root);
    }

    private QueryResult createCollection(String collection, int dimension) throws SQLException {
        connection.client().createCollection(CreateCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .dimension(dimension)
            .build());
        return QueryResult.update(0);
    }


    private QueryResult createTable(String collection, List<CreateCollectionReq.FieldSchema> fields, Map<String, String> properties)
        throws SQLException {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
            .fieldSchemaList(fields)
            .enableDynamicField(true)
            .build();
        connection.client().createCollection(CreateCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .collectionSchema(schema)
            .properties(properties)
            .build());
        return QueryResult.update(0);
    }

    private QueryResult createIndex(String collection, String field, String indexName, String algorithm, Map<String, String> properties)
        throws SQLException {
        IndexParam.IndexParamBuilder param = IndexParam.builder().fieldName(field).indexName(indexName);
        if (algorithm != null && !algorithm.isBlank()) {
            try {
                param.indexType(IndexParam.IndexType.valueOf(algorithm.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                param.indexType(IndexParam.IndexType.AUTOINDEX);
            }
        }
        if (properties.containsKey("metric_type")) {
            param.metricType(IndexParam.MetricType.valueOf(properties.get("metric_type").toUpperCase(java.util.Locale.ROOT)));
        }
        if (!properties.isEmpty()) {
            param.extraParams(new LinkedHashMap<>(properties));
        }
        connection.client().createIndex(CreateIndexReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .indexParams(List.of(param.build()))
            .build());
        return QueryResult.update(0);
    }

    private QueryResult dropIndex(String collection, String indexName) throws SQLException {
        connection.client()
            .dropIndex(DropIndexReq.builder().databaseName(connection.database()).collectionName(collection).indexName(indexName).build());
        return QueryResult.update(0);
    }

    private QueryResult showIndexes(String collection) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.client()
            .listIndexes(ListIndexesReq.builder().databaseName(connection.database()).collectionName(collection).build())) {
            rows.add(row("INDEX_NAME", name));
        }
        return QueryResult.rows(rows, List.of(new ColumnInfo("INDEX_NAME", java.sql.Types.VARCHAR, "VARCHAR")));
    }

    private QueryResult showIndex(String collection, String indexName) throws SQLException {
        Object resp = connection.client().describeIndex(
            DescribeIndexReq.builder().databaseName(connection.database()).collectionName(collection).indexName(indexName).build());
        return QueryResult.rows(List.of(row("INDEX", String.valueOf(resp))));
    }

    private QueryResult showCreateTable(String collection) throws SQLException {
        DescribeCollectionResp resp = connection.client()
            .describeCollection(DescribeCollectionReq.builder().databaseName(connection.database()).collectionName(collection).build());
        return QueryResult.rows(List.of(row("TABLE_NAME", collection, "CREATE_TABLE", String.valueOf(resp.getCollectionSchema()))));
    }

    private QueryResult dropCollection(String collection) throws SQLException {
        connection.client().dropCollection(DropCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .build());
        return QueryResult.update(0);
    }

    private QueryResult describeCollection(String collection) throws SQLException {
        DescribeCollectionResp resp = connection.client().describeCollection(DescribeCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .build());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("collection_name", resp.getCollectionName());
        row.put("database_name", resp.getDatabaseName());
        row.put("primary_field", resp.getPrimaryFieldName());
        row.put("vector_fields", resp.getVectorFieldNames());
        row.put("fields", resp.getFieldNames());
        row.put("auto_id", resp.getAutoID());
        row.put("dynamic_field", resp.getEnableDynamicField());
        row.put("description", resp.getDescription());
        rows.add(row);
        return QueryResult.rows(rows);
    }

    private QueryResult insert(String collection, List<String> fields, List<List<Object>> values) throws SQLException {
        List<JsonObject> rows = insertRows(collection, fields, values, false);
        InsertResp resp = connection.client().insert(InsertReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .data(rows)
            .build());
        return QueryResult.update(resp.getInsertCnt());
    }

    private List<JsonObject> insertRows(String collection, List<String> fields, List<List<Object>> values, boolean upsert)
        throws SQLException {
        DescribeCollectionResp description = describe(collection);
        Map<String, CreateCollectionReq.FieldSchema> schemaFields = schemaFields(description);
        boolean allowInsertAutoId = booleanProperty(description.getProperties(), "allow_insert_auto_id");
        List<JsonObject> rows = new ArrayList<>();
        for (List<Object> rowValues : values) {
            if (rowValues.size() != fields.size()) {
                throw new SQLException("INSERT field count does not match value count");
            }
            JsonObject row = new JsonObject();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                CreateCollectionReq.FieldSchema schema = schemaFields.get(field);
                if (!upsert && isAutoIdPrimaryKey(schema) && !allowInsertAutoId) {
                    throw new SQLException("Cannot insert explicit value for auto_id primary key field '" + field
                        + "'. Omit this column from INSERT, or set collection property allow_insert_auto_id=true.");
                }
                row.add(field, toJson(rowValues.get(i), schema));
            }
            rows.add(row);
        }
        return rows;
    }

    private DescribeCollectionResp describe(String collection) throws SQLException {
        return connection.client().describeCollection(DescribeCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .build());
    }

    private String primaryField(DescribeCollectionResp description) {
        if (description.getPrimaryFieldName() != null && !description.getPrimaryFieldName().isBlank()) {
            return description.getPrimaryFieldName();
        }
        if (description.getCollectionSchema() == null) {
            return null;
        }
        for (CreateCollectionReq.FieldSchema field : description.getCollectionSchema().getFieldSchemaList()) {
            if (Boolean.TRUE.equals(field.getIsPrimaryKey())) {
                return field.getName();
            }
        }
        return null;
    }

    private Map<String, CreateCollectionReq.FieldSchema> schemaFields(DescribeCollectionResp description) {
        Map<String, CreateCollectionReq.FieldSchema> fields = new LinkedHashMap<>();
        if (description.getCollectionSchema() == null) {
            return fields;
        }
        for (CreateCollectionReq.FieldSchema field : description.getCollectionSchema().getFieldSchemaList()) {
            fields.put(field.getName(), field);
        }
        return fields;
    }

    private QueryResult delete(String collection, String filter) throws SQLException {
        if (filter == null || filter.isBlank()) {
            throw new SQLException("DELETE requires a WHERE filter");
        }
        DeleteResp resp = connection.client().delete(DeleteReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .filter(filter)
            .build());
        return QueryResult.update(resp.getDeleteCnt());
    }

    private long countMatched(String collection, String partition, String filter) throws SQLException {
        QueryReq.QueryReqBuilder builder = QueryReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .outputFields(List.of("count(*)"))
            .filter(filter);
        if (partition != null) {
            builder.partitionNames(List.of(partition));
        }
        ConsistencyLevel level = connection.consistencyLevel();
        if (level != null) {
            builder.consistencyLevel(level);
        }
        QueryResp resp;
        try {
            resp = connection.client().query(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Failed to count rows for UPDATE on collection '" + collection + "'", e);
        }
        if (resp.getQueryResults().isEmpty()) {
            return 0;
        }
        Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
        Object count = entity.get("count(*)");
        if (count == null && !entity.isEmpty()) {
            count = entity.values().iterator().next();
        }
        if (count instanceof Number) {
            return ((Number) count).longValue();
        }
        if (count != null) {
            try {
                return Long.parseLong(String.valueOf(count));
            } catch (NumberFormatException e) {
                throw new SQLException("Milvus returned a non-numeric UPDATE match count: " + count, e);
            }
        }
        return 0;
    }

    private List<Object> queryPrimaryKeys(String collection, String partition, String primaryField, String filter, long limit)
        throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        QueryReq.QueryReqBuilder builder = QueryReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .outputFields(List.of(primaryField))
            .filter(filter)
            .limit(limit);
        if (partition != null) {
            builder.partitionNames(List.of(partition));
        }
        ConsistencyLevel level = connection.consistencyLevel();
        if (level != null) {
            builder.consistencyLevel(level);
        }
        QueryResp resp;
        try {
            resp = connection.client().query(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Failed to query primary keys for UPDATE on collection '" + collection + "'", e);
        }
        List<Object> ids = new ArrayList<>();
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            Map<String, Object> entity = result.getEntity();
            if (!entity.containsKey(primaryField)) {
                throw new SQLException("Milvus query did not return primary key field '" + primaryField + "' for UPDATE");
            }
            ids.add(entity.get(primaryField));
        }
        return ids;
    }

    private List<Map<String, Object>> queryRowsForUpdate(String collection, String partition, String filter, long limit)
        throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        QueryReq.QueryReqBuilder builder = QueryReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .outputFields(List.of("*"))
            .filter(filter)
            .limit(limit);
        if (partition != null) {
            builder.partitionNames(List.of(partition));
        }
        ConsistencyLevel level = connection.consistencyLevel();
        if (level != null) {
            builder.consistencyLevel(level);
        }
        QueryResp resp;
        try {
            resp = connection.client().query(builder.build());
        } catch (RuntimeException e) {
            throw new SQLException("Failed to query rows for UPDATE on collection '" + collection + "'", e);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            rows.add(new LinkedHashMap<>(result.getEntity()));
        }
        return rows;
    }

    private boolean updatesVectorField(Map<String, Object> updates, Map<String, CreateCollectionReq.FieldSchema> schemaFields) {
        for (String field : updates.keySet()) {
            CreateCollectionReq.FieldSchema schema = schemaFields.get(field);
            if (schema != null && isVectorType(schema.getDataType())) {
                return true;
            }
        }
        return false;
    }

    private static IndexParam.MetricType metricType(String vectorOperator) {
        if ("<=>".equals(vectorOperator)) {
            return IndexParam.MetricType.COSINE;
        }
        if ("<#>".equals(vectorOperator)) {
            return IndexParam.MetricType.IP;
        }
        return IndexParam.MetricType.L2;
    }

    private static boolean isVectorType(DataType type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case BinaryVector:
            case FloatVector:
            case Float16Vector:
            case BFloat16Vector:
            case SparseFloatVector:
            case Int8Vector:
                return true;
            default:
                return false;
        }
    }

    private QueryResult select(String collection, List<String> outputFields, SelectTail parsed) throws SQLException {
        List<ColumnInfo> schemaColumns = collectionColumns(collection, outputFields);
        if (parsed.vectorField != null) {
            SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .databaseName(connection.database())
                .collectionName(collection)
                .annsField(parsed.vectorField)
                .data(List.of(new FloatVec(toFloatList(parsed.vectorValue))))
                .limit(parsed.limit <= 0 ? 10 : parsed.limit)
                .outputFields(outputFields)
                .filter(filterOrEmpty(parsed.filter));
            ConsistencyLevel level = connection.consistencyLevel();
            if (level != null) {
                builder.consistencyLevel(level);
            }
            if ("<=>".equals(parsed.vectorOperator)) {
                builder.metricType(IndexParam.MetricType.COSINE);
            } else if ("<#>".equals(parsed.vectorOperator)) {
                builder.metricType(IndexParam.MetricType.IP);
            } else {
                builder.metricType(IndexParam.MetricType.L2);
            }
            SearchResp resp = connection.client().search(builder.build());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (List<SearchResp.SearchResult> batch : resp.getSearchResults()) {
                for (SearchResp.SearchResult result : batch) {
                    Map<String, Object> row = new LinkedHashMap<>(result.getEntity());
                    row.put("_score", result.getScore());
                    row.put("_id", result.getId());
                    rows.add(row);
                }
            }
            return QueryResult.rows(rows, searchColumns(schemaColumns));
        }

        QueryReq.QueryReqBuilder builder = QueryReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .outputFields(outputFields)
            .filter(filterOrEmpty(parsed.filter));
        builder.limit(parsed.limit > 0 ? parsed.limit : connection.defaultQueryLimit());
        ConsistencyLevel level = connection.consistencyLevel();
        if (level != null) {
            builder.consistencyLevel(level);
        }
        QueryResp resp = connection.client().query(builder.build());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            rows.add(new LinkedHashMap<>(result.getEntity()));
        }
        return QueryResult.rows(rows, schemaColumns);
    }

    private List<ColumnInfo> collectionColumns(String collection, List<String> outputFields) throws SQLException {
        DescribeCollectionResp description = connection.client().describeCollection(DescribeCollectionReq.builder()
            .databaseName(connection.database())
            .collectionName(collection)
            .build());
        if (description.getCollectionSchema() == null) {
            return null;
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (CreateCollectionReq.FieldSchema field : description.getCollectionSchema().getFieldSchemaList()) {
            if (outputFields.size() == 1 && "*".equals(outputFields.get(0)) || outputFields.contains(field.getName())) {
                columns.add(columnInfo(field));
            }
        }
        return columns;
    }

    private List<ColumnInfo> searchColumns(List<ColumnInfo> schemaColumns) {
        if (schemaColumns == null) {
            return null;
        }
        List<ColumnInfo> columns = new ArrayList<>(schemaColumns);
        columns.add(new ColumnInfo("_score", java.sql.Types.FLOAT, "FLOAT"));
        columns.add(new ColumnInfo("_id", java.sql.Types.JAVA_OBJECT, "OBJECT"));
        return columns;
    }

    private static ColumnInfo columnInfo(CreateCollectionReq.FieldSchema field) {
        JdbcType type = jdbcType(field.getDataType());
        int precision = field.getDimension() != null ? field.getDimension() : field.getMaxLength() == null ? 0 : field.getMaxLength();
        int nullable = Boolean.TRUE.equals(field.getIsNullable())
            ? java.sql.ResultSetMetaData.columnNullable
            : java.sql.ResultSetMetaData.columnNoNulls;
        return new ColumnInfo(field.getName(), type.type, type.name, precision, 0, nullable);
    }

    private static JdbcType jdbcType(DataType type) {
        if (type == null) {
            return new JdbcType(java.sql.Types.OTHER, "UNKNOWN");
        }
        switch (type) {
            case Bool:
                return new JdbcType(java.sql.Types.BOOLEAN, "BOOL");
            case Int8:
                return new JdbcType(java.sql.Types.TINYINT, "INT8");
            case Int16:
                return new JdbcType(java.sql.Types.SMALLINT, "INT16");
            case Int32:
                return new JdbcType(java.sql.Types.INTEGER, "INT32");
            case Int64:
                return new JdbcType(java.sql.Types.BIGINT, "INT64");
            case Float:
                return new JdbcType(java.sql.Types.FLOAT, "FLOAT");
            case Double:
                return new JdbcType(java.sql.Types.DOUBLE, "DOUBLE");
            case String:
                return new JdbcType(java.sql.Types.VARCHAR, "STRING");
            case VarChar:
                return new JdbcType(java.sql.Types.VARCHAR, "VARCHAR");
            case Text:
                return new JdbcType(java.sql.Types.LONGVARCHAR, "TEXT");
            case JSON:
                return new JdbcType(java.sql.Types.JAVA_OBJECT, "JSON");
            case Array:
                return new JdbcType(java.sql.Types.ARRAY, "ARRAY");
            case Timestamptz:
                return new JdbcType(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ");
            case BinaryVector:
                return new JdbcType(java.sql.Types.ARRAY, "BINARY_VECTOR");
            case FloatVector:
                return new JdbcType(java.sql.Types.ARRAY, "FLOAT_VECTOR");
            case Float16Vector:
                return new JdbcType(java.sql.Types.ARRAY, "FLOAT16_VECTOR");
            case BFloat16Vector:
                return new JdbcType(java.sql.Types.ARRAY, "BFLOAT16_VECTOR");
            case SparseFloatVector:
                return new JdbcType(java.sql.Types.ARRAY, "SPARSE_FLOAT_VECTOR");
            case Int8Vector:
                return new JdbcType(java.sql.Types.ARRAY, "INT8_VECTOR");
            default:
                return new JdbcType(java.sql.Types.OTHER, type.name());
        }
    }

    private JsonElement toJson(Object value) {
        return gson.toJsonTree(value);
    }

    static boolean isAutoIdPrimaryKey(CreateCollectionReq.FieldSchema field) {
        return field != null && Boolean.TRUE.equals(field.getIsPrimaryKey()) && Boolean.TRUE.equals(field.getAutoID());
    }

    private static boolean booleanProperty(Map<String, String> properties, String key) {
        if (properties == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return Boolean.parseBoolean(entry.getValue());
            }
        }
        return false;
    }

    private JsonElement toJson(Object value, CreateCollectionReq.FieldSchema field) throws SQLException {
        if (field == null || field.getDataType() == null || value == null) {
            return toJson(value);
        }
        switch (field.getDataType()) {
            case Int8:
            case Int16:
            case Int32:
                if (!(value instanceof Number)) {
                    throw new SQLException("Value for field '" + field.getName() + "' must be numeric");
                }
                return toJson(((Number) value).intValue());
            case Int64:
                if (!(value instanceof Number)) {
                    throw new SQLException("Value for field '" + field.getName() + "' must be numeric");
                }
                return toJson(((Number) value).longValue());
            case Float:
                if (!(value instanceof Number)) {
                    throw new SQLException("Value for field '" + field.getName() + "' must be numeric");
                }
                return toJson(((Number) value).floatValue());
            case Double:
                if (!(value instanceof Number)) {
                    throw new SQLException("Value for field '" + field.getName() + "' must be numeric");
                }
                return toJson(((Number) value).doubleValue());
            case FloatVector:
                return toJson(toFloatList(value));
            default:
                return toJson(value);
        }
    }


    private static String filterOrEmpty(String filter) {
        return filter == null ? "" : filter;
    }

    @SuppressWarnings("unchecked")
    private static List<Float> toFloatList(Object value) throws SQLException {
        if (!(value instanceof List)) {
            throw new SQLException("Vector literal must be an array");
        }
        List<Float> floats = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (!(item instanceof Number)) {
                throw new SQLException("Vector literal contains non-number value: " + item);
            }
            floats.add(((Number) item).floatValue());
        }
        return floats;
    }

    private static String identifier(MilvusJdbcParser.IdentifierContext ctx) {
        String text = ctx.getText();
        if ((text.startsWith("`") && text.endsWith("`")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    private static String tokenText(TokenStream tokens, org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null) {
            return null;
        }
        return tokens.getText(ctx.getSourceInterval());
    }

    static String filterText(TokenStream tokens, org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = ctx.getSourceInterval().a; i <= ctx.getSourceInterval().b; i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) {
                break;
            }
            String tokenText = token.getType() == MilvusJdbcParser.EQUALS ? "==" : token.getText();
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(tokenText);
        }
        return text.toString();
    }

    private CollectionRef collectionRef(String name) throws SQLException {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return new CollectionRef(connection.database(), name);
        }
        String database = name.substring(0, dot);
        String collection = name.substring(dot + 1);
        if (database.isBlank() || collection.isBlank()) {
            throw new SQLException("Invalid Milvus collection reference: " + name);
        }
        if (!database.equals(connection.database())) {
            throw new SQLException("Milvus JDBC connection is scoped to database '" + connection.database()
                + "' and cannot query database '" + database + "'");
        }
        return new CollectionRef(database, collection);
    }

    private final class ExecutorVisitor extends MilvusJdbcParserBaseVisitor<QueryResult> {
        private final TokenStream tokens;

        ExecutorVisitor(TokenStream tokens) {
            this.tokens = tokens;
        }

        @Override
        public QueryResult visitShowCollections(MilvusJdbcParser.ShowCollectionsContext ctx) {
            return unchecked(() -> showCollections());
        }

        @Override
        public QueryResult visitShowDatabases(MilvusJdbcParser.ShowDatabasesContext ctx) {
            return unchecked(() -> showDatabases());
        }

        @Override
        public QueryResult visitShowTables(MilvusJdbcParser.ShowTablesContext ctx) {
            return unchecked(() -> showCollections());
        }

        @Override
        public QueryResult visitCreateDatabase(MilvusJdbcParser.CreateDatabaseContext ctx) {
            return unchecked(() -> createDatabase(identifier(ctx.dbName)));
        }

        @Override
        public QueryResult visitAlterDatabase(MilvusJdbcParser.AlterDatabaseContext ctx) {
            return unchecked(() -> alterDatabase(identifier(ctx.dbName), properties(ctx.propertiesList())));
        }

        @Override
        public QueryResult visitDropDatabase(MilvusJdbcParser.DropDatabaseContext ctx) {
            return unchecked(() -> dropDatabase(identifier(ctx.dbName)));
        }

        @Override
        public QueryResult visitDropTable(MilvusJdbcParser.DropTableContext ctx) {
            return unchecked(() -> dropCollection(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitRenameTable(MilvusJdbcParser.RenameTableContext ctx) {
            return unchecked(() -> renameTable(identifier(ctx.collectionName), identifier(ctx.newName)));
        }

        @Override
        public QueryResult visitCreatePartition(MilvusJdbcParser.CreatePartitionContext ctx) {
            return unchecked(() -> createPartition(identifier(ctx.collectionName), identifier(ctx.partitionName)));
        }

        @Override
        public QueryResult visitDropPartition(MilvusJdbcParser.DropPartitionContext ctx) {
            return unchecked(() -> dropPartition(identifier(ctx.collectionName), identifier(ctx.partitionName)));
        }

        @Override
        public QueryResult visitShowPartitions(MilvusJdbcParser.ShowPartitionsContext ctx) {
            return unchecked(() -> showPartitions(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitShowPartition(MilvusJdbcParser.ShowPartitionContext ctx) {
            return unchecked(() -> showPartitions(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitCreateAlias(MilvusJdbcParser.CreateAliasContext ctx) {
            return unchecked(() -> createAlias(identifier(ctx.aliasName), identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitAlterAlias(MilvusJdbcParser.AlterAliasContext ctx) {
            return unchecked(() -> alterAlias(identifier(ctx.aliasName), identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitDropAlias(MilvusJdbcParser.DropAliasContext ctx) {
            return unchecked(() -> dropAlias(identifier(ctx.aliasName)));
        }

        @Override
        public QueryResult visitShowUsers(MilvusJdbcParser.ShowUsersContext ctx) {
            return unchecked(() -> showUsers());
        }

        @Override
        public QueryResult visitShowRoles(MilvusJdbcParser.ShowRolesContext ctx) {
            return unchecked(() -> showRoles());
        }

        @Override
        public QueryResult visitCreateUser(MilvusJdbcParser.CreateUserContext ctx) {
            return unchecked(() -> createUser(identifier(ctx.userName), unquote(ctx.password.getText())));
        }

        @Override
        public QueryResult visitDropUser(MilvusJdbcParser.DropUserContext ctx) {
            return unchecked(() -> dropUser(identifier(ctx.userName)));
        }

        @Override
        public QueryResult visitCreateRole(MilvusJdbcParser.CreateRoleContext ctx) {
            return unchecked(() -> createRole(identifier(ctx.roleName)));
        }

        @Override
        public QueryResult visitDropRole(MilvusJdbcParser.DropRoleContext ctx) {
            return unchecked(() -> dropRole(identifier(ctx.roleName)));
        }

        @Override
        public QueryResult visitLoad(MilvusJdbcParser.LoadContext ctx) {
            return unchecked(() -> load(identifier(ctx.collectionName), ctx.partitionName == null ? null : identifier(ctx.partitionName)));
        }

        @Override
        public QueryResult visitRelease(MilvusJdbcParser.ReleaseContext ctx) {
            return unchecked(
                () -> release(identifier(ctx.collectionName), ctx.partitionName == null ? null : identifier(ctx.partitionName)));
        }

        @Override
        public QueryResult visitFlush(MilvusJdbcParser.FlushContext ctx) {
            return unchecked(() -> flush(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitCount(MilvusJdbcParser.CountContext ctx) {
            return unchecked(() -> {
                CollectionRef source = collectionRef(identifier(ctx.collectionName));
                return count(source.collection, filterText(tokens, ctx.expression()));
            });
        }

        @Override
        public QueryResult visitUpsert(MilvusJdbcParser.UpsertContext ctx) {
            List<String> fields = identifiers(ctx.columnList);
            List<List<Object>> values = new ArrayList<>();
            for (MilvusJdbcParser.ValueRowContext row : ctx.valueRows().valueRow()) {
                values.add(literals(row.valueList));
            }
            return unchecked(() -> upsert(identifier(ctx.collectionName), fields, values));
        }

        @Override
        public QueryResult visitGrant(MilvusJdbcParser.GrantContext ctx) {
            if (ctx.userName != null) {
                return unchecked(() -> grantRole(identifier(ctx.roleName), identifier(ctx.userName)));
            }
            String objectName = ctx.objectName == null ? "*" : identifier(ctx.objectName);
            return unchecked(
                () -> grantPrivilege(identifier(ctx.roleName), identifier(ctx.objectType), objectName, identifier(ctx.privilege)));
        }

        @Override
        public QueryResult visitRevoke(MilvusJdbcParser.RevokeContext ctx) {
            if (ctx.userName != null) {
                return unchecked(() -> revokeRole(identifier(ctx.roleName), identifier(ctx.userName)));
            }
            String objectName = ctx.objectName == null ? "*" : identifier(ctx.objectName);
            return unchecked(
                () -> revokePrivilege(identifier(ctx.roleName), identifier(ctx.objectType), objectName, identifier(ctx.privilege)));
        }

        @Override
        public QueryResult visitShowProgress(MilvusJdbcParser.ShowProgressContext ctx) {
            return unchecked(() -> unsupported("SHOW PROGRESS"));
        }

        @Override
        public QueryResult visitShowGrants(MilvusJdbcParser.ShowGrantsContext ctx) {
            return unchecked(() -> unsupported("SHOW GRANTS"));
        }

        @Override
        public QueryResult visitUpdate(MilvusJdbcParser.UpdateContext ctx) {
            Map<String, Object> updates = new LinkedHashMap<>();
            for (MilvusJdbcParser.SetClauseContext setClause : ctx.setClauseList().setClause()) {
                String field = identifier(setClause.columnName);
                if (updates.containsKey(field)) {
                    return unchecked(() -> {
                        throw new SQLException("Duplicate UPDATE assignment for field '" + field + "'");
                    });
                }
                updates.put(field, termValue(setClause.value));
            }
            return unchecked(() -> {
                CollectionRef source = collectionRef(identifier(ctx.collectionName));
                UpdateTarget target = new UpdateTarget(
                    filterText(tokens, ctx.expression()),
                    ctx.limit == null ? -1 : Long.parseLong(ctx.limit.getText())
                );
                if (ctx.sortClause() != null) {
                    if (ctx.sortClause().distanceOperator() == null) {
                        throw new SQLException("UPDATE ORDER BY requires a vector distance operator");
                    }
                    target.vectorField = identifier(ctx.sortClause().fieldName);
                    target.vectorOperator = ctx.sortClause().distanceOperator().getText();
                    target.vectorValue = literal(ctx.sortClause().vectorValue().listLiteral());
                }
                return update(source.collection, ctx.partitionName == null ? null : identifier(ctx.partitionName), updates, target);
            });
        }


        @Override
        public QueryResult visitCreateCollection(MilvusJdbcParser.CreateCollectionContext ctx) {
            return unchecked(() -> createCollection(identifier(ctx.collectionName), Integer.parseInt(ctx.dimension.getText())));
        }

        @Override
        public QueryResult visitCreateTable(MilvusJdbcParser.CreateTableContext ctx) {
            List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
            for (MilvusJdbcParser.FieldDefinitionContext field : ctx.fieldDefinition()) {
                fields.add(field(field));
            }
            Map<String, String> properties = ctx.propertiesList() == null ? Map.of() : properties(ctx.propertiesList());
            return unchecked(() -> createTable(identifier(ctx.collectionName), fields, properties));
        }

        @Override
        public QueryResult visitCreateIndex(MilvusJdbcParser.CreateIndexContext ctx) {
            String indexName = ctx.indexName == null ? null : identifier(ctx.indexName);
            String algo = ctx.indexAlgo() == null ? null : unquoteIfQuoted(ctx.indexAlgo().getText());
            Map<String, String> properties = ctx.propertiesList() == null ? Map.of() : properties(ctx.propertiesList());
            return unchecked(() -> createIndex(identifier(ctx.collectionName), identifier(ctx.fieldName), indexName, algo, properties));
        }

        @Override
        public QueryResult visitDropIndex(MilvusJdbcParser.DropIndexContext ctx) {
            return unchecked(() -> dropIndex(identifier(ctx.collectionName), identifier(ctx.indexName)));
        }

        @Override
        public QueryResult visitShowIndexes(MilvusJdbcParser.ShowIndexesContext ctx) {
            return unchecked(() -> showIndexes(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitShowIndex(MilvusJdbcParser.ShowIndexContext ctx) {
            return unchecked(() -> showIndex(identifier(ctx.collectionName), identifier(ctx.indexName)));
        }

        @Override
        public QueryResult visitShowCreateTable(MilvusJdbcParser.ShowCreateTableContext ctx) {
            return unchecked(() -> showCreateTable(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitDropCollection(MilvusJdbcParser.DropCollectionContext ctx) {
            return unchecked(() -> dropCollection(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitDescribeCollection(MilvusJdbcParser.DescribeCollectionContext ctx) {
            return unchecked(() -> describeCollection(identifier(ctx.collectionName)));
        }

        @Override
        public QueryResult visitInsert(MilvusJdbcParser.InsertContext ctx) {
            List<String> fields = identifiers(ctx.columnList);
            List<List<Object>> values = new ArrayList<>();
            for (MilvusJdbcParser.ValueRowContext row : ctx.valueRows().valueRow()) {
                values.add(literals(row.valueList));
            }
            return unchecked(() -> insert(identifier(ctx.collectionName), fields, values));
        }

        @Override
        public QueryResult visitDelete(MilvusJdbcParser.DeleteContext ctx) {
            return unchecked(() -> {
                CollectionRef source = collectionRef(identifier(ctx.collectionName));
                return delete(source.collection, filterText(tokens, ctx.expression()));
            });
        }

        @Override
        public QueryResult visitSelect(MilvusJdbcParser.SelectContext ctx) {
            if (ctx.constantSelectElements() != null) {
                return constantSelect(ctx.constantSelectElements());
            }
            SelectTail tail = new SelectTail(
                filterText(tokens, ctx.expression()),
                ctx.limit == null ? -1 : Long.parseLong(ctx.limit.getText()),
                null,
                null,
                null
            );
            if (ctx.sortClause() != null && ctx.sortClause().distanceOperator() != null) {
                tail.vectorField = identifier(ctx.sortClause().fieldName);
                tail.vectorOperator = ctx.sortClause().distanceOperator().getText();
                tail.vectorValue = literal(ctx.sortClause().vectorValue().listLiteral());
            }
            return unchecked(() -> {
                CollectionRef source = collectionRef(identifier(ctx.source.collectionName));
                String alias = ctx.source.alias == null ? null : identifier(ctx.source.alias);
                return select(source.collection, selectFields(ctx.selectElements(), alias), tail);
            });
        }

        private QueryResult constantSelect(MilvusJdbcParser.ConstantSelectElementsContext ctx) {
            Map<String, Object> row = new LinkedHashMap<>();
            int index = 1;
            for (MilvusJdbcParser.LiteralContext literal : ctx.literal()) {
                row.put("COLUMN" + index, literal(literal));
                index++;
            }
            return QueryResult.rows(List.of(row));
        }

        private List<String> selectFields(MilvusJdbcParser.SelectElementsContext ctx, String alias) {
            if (ctx.STAR() != null) {
                return List.of("*");
            }
            if (ctx.qualifiedStar() != null) {
                return List.of("*");
            }
            return identifiers(ctx.identifierList(), alias);
        }

        private List<String> identifiers(MilvusJdbcParser.IdentifierListContext ctx) {
            return identifiers(ctx, null);
        }

        private List<String> identifiers(MilvusJdbcParser.IdentifierListContext ctx, String alias) {
            List<String> fields = new ArrayList<>();
            for (MilvusJdbcParser.IdentifierContext identifier : ctx.identifier()) {
                fields.add(stripAlias(identifier(identifier), alias));
            }
            return fields;
        }

        private String stripAlias(String field, String alias) {
            if (alias != null && field.startsWith(alias + ".")) {
                return field.substring(alias.length() + 1);
            }
            return field;
        }


        private CreateCollectionReq.FieldSchema field(MilvusJdbcParser.FieldDefinitionContext ctx) {
            CreateCollectionReq.FieldSchema.FieldSchemaBuilder builder = CreateCollectionReq.FieldSchema.builder()
                .name(identifier(ctx.fieldName))
                .dataType(dataType(ctx.fieldType()));
            Integer dimension = dimension(ctx.fieldType());
            if (builder == null) {
                throw new IllegalArgumentException("Field builder is null for field: " + ctx.fieldName.getText());
            }
            if (dimension != null) {
                builder.dimension(dimension);
            }
            Integer maxLength = maxLength(ctx.fieldType());
            if (maxLength != null) {
                builder.maxLength(maxLength);
            }
            for (MilvusJdbcParser.FieldConstraintContext c : ctx.fieldConstraint()) {
                String text = c.getText().toUpperCase(java.util.Locale.ROOT);
                switch (text) {
                    case "PRIMARYKEY":
                        builder.isPrimaryKey(true);
                        break;
                    case "NOTNULL":
                        builder.isNullable(false);
                        break;
                    case "AUTO_ID":
                        builder.autoID(true);
                        break;
                }
            }
            return builder.build();
        }

        private DataType dataType(MilvusJdbcParser.FieldTypeContext ctx) {
            String text = ctx.getChild(0).getText().toUpperCase(java.util.Locale.ROOT);
            switch (text) {
                case "BOOL":
                    return DataType.Bool;
                case "INT8":
                    return DataType.Int8;
                case "INT16":
                    return DataType.Int16;
                case "INT32":
                    return DataType.Int32;
                case "INT64":
                    return DataType.Int64;
                case "FLOAT":
                    return DataType.Float;
                case "DOUBLE":
                    return DataType.Double;
                case "JSON":
                    return DataType.JSON;
                case "VARCHAR":
                    return DataType.VarChar;
                case "FLOAT_VECTOR":
                    return DataType.FloatVector;
                case "BINARY_VECTOR":
                    return DataType.BinaryVector;
                case "FLOAT16_VECTOR":
                    return DataType.Float16Vector;
                case "BFLOAT16_VECTOR":
                    return DataType.BFloat16Vector;
                case "SPARSE_FLOAT_VECTOR":
                    return DataType.SparseFloatVector;
                case "ARRAY":
                    return DataType.Array;
                default:
                    return DataType.VarChar;
            }
        }

        private Integer dimension(MilvusJdbcParser.FieldTypeContext ctx) {
            String text = ctx.getChild(0).getText().toUpperCase(java.util.Locale.ROOT);
            if (text.contains("VECTOR") && ctx.INTEGER() != null) {
                return Integer.parseInt(ctx.INTEGER().getText());
            }
            return null;
        }

        private Integer maxLength(MilvusJdbcParser.FieldTypeContext ctx) {
            if ("VARCHAR".equalsIgnoreCase(ctx.getChild(0).getText()) && ctx.INTEGER() != null) {
                return Integer.parseInt(ctx.INTEGER().getText());
            }
            return null;
        }

        private String unquoteIfQuoted(String text) {
            if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
                return unquote(text);
            }
            return text;
        }

        private Map<String, String> properties(MilvusJdbcParser.PropertiesListContext ctx) {
            Map<String, String> properties = new LinkedHashMap<>();
            for (MilvusJdbcParser.PropertyContext property : ctx.property()) {
                String key = property.getChild(0).getText();
                if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) {
                    key = unquote(key);
                }
                String value = property.getChild(2).getText();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = unquote(value);
                }
                properties.put(key, value);
            }
            return properties;
        }

        private List<Object> literals(MilvusJdbcParser.LiteralListContext ctx) {
            List<Object> values = new ArrayList<>();
            for (MilvusJdbcParser.LiteralContext literal : ctx.literal()) {
                values.add(literal(literal));
            }
            return values;
        }

        private Object literal(MilvusJdbcParser.ListLiteralContext ctx) {
            if (ctx.literalList() == null) {
                return List.of();
            }
            return literals(ctx.literalList());
        }

        private Object literal(MilvusJdbcParser.LiteralContext ctx) {
            if (ctx.STRING_LITERAL() != null) {
                return unquote(ctx.STRING_LITERAL().getText());
            }
            if (ctx.signedNumber() != null) {
                return number(ctx.signedNumber());
            }
            if (ctx.TRUE() != null) {
                return true;
            }
            if (ctx.FALSE() != null) {
                return false;
            }
            if (ctx.NULL() != null) {
                return null;
            }
            if (ctx.listLiteral() != null) {
                return literal(ctx.listLiteral());
            }
            return identifier(ctx.identifier());
        }

        private Object termValue(MilvusJdbcParser.TermContext ctx) {
            if (ctx.literal() != null) {
                return literal(ctx.literal());
            }
            if (ctx.identifier() != null) {
                return identifier(ctx.identifier());
            }
            throw new IllegalArgumentException("Unbound UPDATE parameter: " + ctx.getText());
        }

        private Object number(MilvusJdbcParser.SignedNumberContext ctx) {
            String text = ctx.getText();
            if (ctx.INTEGER() != null) {
                return Long.parseLong(text);
            }
            return Double.parseDouble(text);
        }

        private String unquote(String text) {
            if (text.length() < 2) {
                return text;
            }
            char quote = text.charAt(0);
            String value = text.substring(1, text.length() - 1);
            return quote == '\'' ? value.replace("''", "'") : value.replace("\"\"", "\"");
        }

        private QueryResult unchecked(SqlCall call) {
            try {
                return call.get();
            } catch (SQLException e) {
                throw new SqlRuntimeException(e);
            }
        }
    }


    private static final class JdbcType {
        final int type;
        final String name;

        JdbcType(int type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    private static final class ParsedSql {
        final TokenStream tokens;
        final MilvusJdbcParser.RootContext root;

        ParsedSql(TokenStream tokens, MilvusJdbcParser.RootContext root) {
            this.tokens = tokens;
            this.root = root;
        }
    }

    private static final class UpdateTarget {
        private final String filter;
        private final long limit;
        private String vectorField;
        private String vectorOperator;
        private Object vectorValue;

        private UpdateTarget(String filter, long limit) {
            this.filter = filter;
            this.limit = limit;
        }

        private boolean isVectorSearch() {
            return vectorField != null;
        }
    }

    private static final class SelectTail {
        final String filter;
        final long limit;
        String vectorField;
        String vectorOperator;
        Object vectorValue;

        SelectTail(String filter, long limit, String vectorField, String vectorOperator, Object vectorValue) {
            this.filter = filter;
            this.limit = limit;
            this.vectorField = vectorField;
            this.vectorOperator = vectorOperator;
            this.vectorValue = vectorValue;
        }
    }

    private static final class CollectionRef {
        final String database;
        final String collection;

        CollectionRef(String database, String collection) {
            this.database = database;
            this.collection = collection;
        }
    }

    private interface SqlCall {
        QueryResult get() throws SQLException;
    }

    private static final class SqlRuntimeException extends RuntimeException {
        SqlRuntimeException(SQLException cause) {
            super(cause);
        }
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new IllegalArgumentException("SQL syntax error at " + line + ":" + charPositionInLine + " - " + msg, e);
        }
    }
}
