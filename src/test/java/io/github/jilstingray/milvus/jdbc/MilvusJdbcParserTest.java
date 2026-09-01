package io.github.jilstingray.milvus.jdbc;

import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcLexer;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilvusJdbcParserTest {
    @Test
    void parsesVectorSearchSelect() {
        MilvusJdbcParser.RootContext root = parse("SELECT id, title FROM books WHERE id < 10 ORDER BY vector <-> [0.1, 0.2] LIMIT 5");

        MilvusJdbcParser.SelectContext select = root.statement().select();
        assertNotNull(select);
        assertEquals("books", select.source.collectionName.getText());
        assertEquals("vector", select.sortClause().fieldName.getText());
        assertEquals("<->", select.sortClause().distanceOperator().getText());
        assertEquals("5", select.limit.getText());
    }

    @Test
    void parsesInsertRows() {
        MilvusJdbcParser.RootContext root = parse("INSERT INTO books (id, vector, title) VALUES (1, [0.1, 0.2], 'a')");

        MilvusJdbcParser.InsertContext insert = root.statement().insert();
        assertNotNull(insert);
        assertEquals("books", insert.collectionName.getText());
        assertEquals(3, insert.columnList.identifier().size());
        assertEquals(3, insert.valueRows().valueRow(0).valueList.literal().size());
    }

    @Test
    void parsesUpdateSetWhereLimit() {
        MilvusJdbcParser.RootContext root = parse("UPDATE books SET title = 'b', score = 3.5 WHERE id == 1 LIMIT 1");

        MilvusJdbcParser.UpdateContext update = root.statement().update();
        assertNotNull(update);
        assertEquals("books", update.collectionName.getText());
        assertEquals(2, update.setClauseList().setClause().size());
        assertNotNull(update.expression());
        assertEquals("1", update.limit.getText());
    }

    @Test
    void parsesQualifiedUpdateAndNormalizesEqualsFilter() {
        CommonTokenStream tokens = tokens("UPDATE vectordb.test SET test = 'b' WHERE id = 468605922546765474");
        MilvusJdbcParser parser = new MilvusJdbcParser(tokens);
        MilvusJdbcParser.RootContext root = parser.root();
        assertEquals(0, parser.getNumberOfSyntaxErrors());

        MilvusJdbcParser.UpdateContext update = root.statement().update();
        assertNotNull(update);
        assertEquals("vectordb.test", update.collectionName.getText());
        assertEquals("id == 468605922546765474", SqlExecutor.filterText(tokens, update.expression()));
    }

    @Test
    void normalizesSingleEqualsInAllWhereFilters() {
        CommonTokenStream selectTokens = tokens("SELECT id FROM books WHERE id = 1 AND score >= 3.5");
        MilvusJdbcParser.SelectContext select = new MilvusJdbcParser(selectTokens).root().statement().select();
        assertEquals("id == 1 AND score >= 3.5", SqlExecutor.filterText(selectTokens, select.expression()));

        CommonTokenStream deleteTokens = tokens("DELETE FROM books WHERE id = 1 OR title != 'old'");
        MilvusJdbcParser.DeleteContext delete = new MilvusJdbcParser(deleteTokens).root().statement().delete();
        assertEquals("id == 1 OR title != 'old'", SqlExecutor.filterText(deleteTokens, delete.expression()));

        CommonTokenStream countTokens = tokens("COUNT FROM books WHERE id == 1");
        MilvusJdbcParser.CountContext count = new MilvusJdbcParser(countTokens).root().statement().count();
        assertEquals("id == 1", SqlExecutor.filterText(countTokens, count.expression()));
    }


    @Test
    void parsesSignedVectorLiterals() {
        MilvusJdbcParser.RootContext root = parse("INSERT INTO test (id, vector) VALUES (1, [- 0.005356751848012209, -0.06410066038370132, -3.842733713099733e-05, 0.1])");

        MilvusJdbcParser.InsertContext insert = root.statement().insert();
        assertNotNull(insert);
        assertEquals(2, insert.valueRows().valueRow(0).valueList.literal().size());
        MilvusJdbcParser.LiteralContext vector = insert.valueRows().valueRow(0).valueList.literal(1);
        assertEquals(4, vector.listLiteral().literalList().literal().size());
    }

    @Test
    void parsesUpdateVectorSearchOrderBy() {
        MilvusJdbcParser.RootContext root = parse("UPDATE books SET status = 'hit' ORDER BY vector <-> [0.1, 0.2] LIMIT 1");

        MilvusJdbcParser.UpdateContext update = root.statement().update();
        assertNotNull(update);
        assertEquals("books", update.collectionName.getText());
        assertEquals("vector", update.sortClause().fieldName.getText());
        assertEquals("<->", update.sortClause().distanceOperator().getText());
        assertEquals("1", update.limit.getText());
    }

    @Test
    void parsesUpdateVectorLiteralWithScientificNotation() {
        CommonTokenStream tokens = tokens("UPDATE test SET vector = [-0.005356752, -3.8427337E-5, 9.298665E-4] WHERE id = 468605922546765474");
        MilvusJdbcParser.UpdateContext update = new MilvusJdbcParser(tokens).root().statement().update();

        assertNotNull(update);
        assertEquals("vector", update.setClauseList().setClause(0).columnName.getText());
        assertEquals(3, update.setClauseList().setClause(0).value.literal().listLiteral().literalList().literal().size());
        assertEquals("id == 468605922546765474", SqlExecutor.filterText(tokens, update.expression()));
    }



    @Test
    void identifiesAutoIdPrimaryKeyFields() {
        CreateCollectionReq.FieldSchema field = CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build();

        assertEquals(true, SqlExecutor.isAutoIdPrimaryKey(field));
    }

    @Test
    void parsesJdbcUrlQueryProperties() {
        java.util.Map<String, String> params = MilvusConnection.queryParams(
                "username=root&password=pa%40ss&token=a%3Ab&database=db1&consistencyLevel=STRONG&defaultQueryLimit=500");

        assertEquals("root", params.get("username"));
        assertEquals("pa@ss", params.get("password"));
        assertEquals("a:b", params.get("token"));
        assertEquals("db1", params.get("database"));
        assertEquals("STRONG", params.get("consistencyLevel"));
        assertEquals("500", params.get("defaultQueryLimit"));
    }

    @Test
    void parsesDefaultQueryLimit() throws SQLException {
        Properties properties = new Properties();
        assertEquals(16384, MilvusConnection.parseDefaultQueryLimit(properties));

        properties.setProperty("defaultQueryLimit", "500");
        assertEquals(500, MilvusConnection.parseDefaultQueryLimit(properties));
    }

    @Test
    void parsesConsistencyLevel() {
        Properties properties = new Properties();
        assertEquals(ConsistencyLevel.STRONG, MilvusConnection.parseConsistencyLevel(properties));

        properties.setProperty("consistencyLevel", "bounded");
        assertEquals(ConsistencyLevel.BOUNDED, MilvusConnection.parseConsistencyLevel(properties));
    }

    @Test
    void parsesOptionalSelectedDatabase() throws SQLException {
        assertEquals("", MilvusConnection.selectedDatabase("jdbc:milvus://localhost:19530", new Properties()));
        assertEquals("default", MilvusConnection.selectedDatabase("jdbc:milvus://localhost:19530/default", new Properties()));

        Properties properties = new Properties();
        properties.setProperty("database", "analytics");
        assertEquals("analytics", MilvusConnection.selectedDatabase("jdbc:milvus://localhost:19530/default", properties));
    }

    @Test
    void parsesUseDatabase() {
        MilvusJdbcParser.RootContext root = parse("USE default");

        assertNotNull(root.statement().useDatabase());
        assertEquals("default", root.statement().useDatabase().dbName.getText());
    }

    @Test
    void rejectsInvalidDefaultQueryLimit() {
        Properties properties = new Properties();
        properties.setProperty("defaultQueryLimit", "0");
        assertThrows(SQLException.class, () -> MilvusConnection.parseDefaultQueryLimit(properties));

        properties.setProperty("defaultQueryLimit", "16385");
        assertThrows(SQLException.class, () -> MilvusConnection.parseDefaultQueryLimit(properties));

        properties.setProperty("defaultQueryLimit", "many");
        assertThrows(SQLException.class, () -> MilvusConnection.parseDefaultQueryLimit(properties));
    }

    @Test
    void parsesCollectionCommands() {
        assertNotNull(parse("SHOW COLLECTIONS").statement().showCollections());
        assertNotNull(parse("DESCRIBE COLLECTION books").statement().describeCollection());
        assertNotNull(parse("DESCRIBE TABLE books").statement().describeCollection());
        assertNotNull(parse("DESC TABLE books").statement().describeCollection());
        assertNotNull(parse("CREATE COLLECTION books DIMENSION 768").statement().createCollection());
        assertNotNull(parse("DROP COLLECTION books").statement().dropCollection());
    }

    @Test
    void parsesConstantSelectForKeepAlive() {
        MilvusJdbcParser.RootContext root = parse("SELECT 'keep alive'");

        MilvusJdbcParser.SelectContext select = root.statement().select();
        assertNotNull(select);
        assertNotNull(select.constantSelectElements());
        assertEquals("'keep alive'", select.constantSelectElements().literal(0).getText());
    }

    @Test
    void parsesQualifiedStarAndTableAlias() {
        MilvusJdbcParser.RootContext root = parse("SELECT t.* FROM default.books t");

        MilvusJdbcParser.SelectContext select = root.statement().select();
        assertNotNull(select);
        assertNotNull(select.selectElements().qualifiedStar());
        assertEquals("t.", select.selectElements().qualifiedStar().qualifier.getText());
        assertEquals("default.books", select.source.collectionName.getText());
        assertEquals("t", select.source.alias.getText());
    }

    private MilvusJdbcParser.RootContext parse(String sql) {
        CommonTokenStream tokens = tokens(sql);
        MilvusJdbcParser parser = new MilvusJdbcParser(tokens);
        MilvusJdbcParser.RootContext root = parser.root();
        assertEquals(0, parser.getNumberOfSyntaxErrors());
        return root;
    }

    private CommonTokenStream tokens(String sql) {
        MilvusJdbcLexer lexer = new MilvusJdbcLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }
}
