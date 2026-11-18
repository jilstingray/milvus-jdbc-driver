package io.github.jilstingray.milvus.jdbc;

import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcLexer;
import io.github.jilstingray.milvus.jdbc.parser.MilvusJdbcParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "select count(*) from books",
            "SeLeCt CoUnT ( * ) AS total FROM default.books b WHERE id = 1;",
            "SELECT COUNT(*) total FROM books PARTITION recent WHERE score >= 3.5",
            "COUNT FROM books PARTITION recent WHERE id = 1",
            "SELECT * FROM books WHERE title IS NULL",
            "SELECT id FROM books WHERE title IS NOT NULL",
            "SELECT id FROM books WHERE id NOT IN (1, 2, 3)",
            "SELECT id FROM books WHERE title NOT LIKE 'old%'",
            "SELECT id FROM books WHERE score-1 > 0",
            "SELECT t.* FROM default.books AS t LIMIT 5 OFFSET 2",
            "SELECT id, title FROM books WHERE (id = 1 OR id = 2) AND NOT title = 'old'",
            "SELECT id FROM books WHERE id IN [1, 2] AND title LIKE 'new%'",
            "SELECT id FROM books ORDER BY id DESC LIMIT 10",
            "SELECT id FROM books ORDER BY vector <=> [0.1, -2e-3] LIMIT 5",
            "SELECT 1, 'keep alive', true, null",
            "/* query */ SELECT * FROM `books` WHERE id >= 1; -- end",
            "INSERT INTO books (id, vector, title) VALUES (1, [0.1, -2e-3], 'it''s'), (2, [0, 1], 'b')",
            "UPSERT INTO books (id, title) VALUES (1, 'new')",
            "UPDATE books SET title = 'new', score = -1.5 WHERE id IN (1, 2) LIMIT 2",
            "DELETE FROM books WHERE id = 1",
            "CREATE TABLE books (id INT64 PRIMARY KEY AUTO_ID, title VARCHAR(128), vector FLOAT_VECTOR(2))",
            "CREATE INDEX vec_idx ON books (vector) USING HNSW WITH (metric_type = 'COSINE')",
            "SHOW DATABASES", "SHOW TABLES", "DESC books", "SHOW INDEXES FROM books",
            "USE default", "LOAD TABLE books", "RELEASE TABLE books", "FLUSH books"
    })
    void parsesCommonStatements(String sql) {
        parse(sql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "id = 1 | id == 1",
            "id <> 1 | id != 1",
            "id IN (1, 2, 3) | id IN [ 1 , 2 , 3 ]",
            "id NOT IN (1, 2) | id NOT IN [ 1 , 2 ]",
            "(id = 1 OR id = 2) AND score >= 3.5 | ( id == 1 OR id == 2 ) AND score >= 3.5",
            "score-1 > 0 | score - 1 > 0",
            "title = 'a=b' | title == 'a=b'",
            "title IS NOT NULL | title IS NOT NULL"
    })
    void translatesSqlFilters(String filter, String expected) {
        CommonTokenStream tokens = tokens("SELECT * FROM books WHERE " + filter);
        MilvusJdbcParser parser = parser(tokens);
        assertEquals(expected, SqlExecutor.filterText(tokens, parser.root().statement().select().expression()));
    }

    static MilvusJdbcParser.RootContext parse(String sql) {
        return parser(tokens(sql)).root();
    }

    private static CommonTokenStream tokens(String sql) {
        MilvusJdbcLexer lexer = new MilvusJdbcLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ERRORS);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }

    private static MilvusJdbcParser parser(CommonTokenStream tokens) {
        MilvusJdbcParser parser = new MilvusJdbcParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ERRORS);
        return parser;
    }

    private static final BaseErrorListener ERRORS = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int column,
                                String message, RecognitionException cause) {
            throw new AssertionError("SQL error at " + line + ":" + column + " " + message, cause);
        }
    };
}
