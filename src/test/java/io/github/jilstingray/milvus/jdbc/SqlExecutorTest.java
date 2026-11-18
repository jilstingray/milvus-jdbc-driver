package io.github.jilstingray.milvus.jdbc;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.aggregation.AggDirection;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqlExecutorTest {
    private MilvusClientV2 client;
    private SqlExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        client = mock(MilvusClientV2.class);
        MilvusConnection connection = mock(MilvusConnection.class);
        when(connection.client()).thenReturn(client);
        when(connection.requireDatabase()).thenReturn("default");
        when(connection.consistencyLevel()).thenReturn(ConsistencyLevel.STRONG);
        when(connection.defaultQueryLimit()).thenReturn(500L);
        executor = new SqlExecutor(connection);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT COUNT(*) FROM books | count(*)",
            "select count ( * ) as total from books | total",
            "SELECT COUNT(*) `total` FROM books | total",
            "COUNT FROM books | count(*)"
    })
    void executesCountAndReturnsBigintMetadata(String sql, String label) throws SQLException {
        when(client.query(any(QueryReq.class))).thenReturn(countResponse(42L));
        QueryResult result = executor.execute(sql);
        QueryReq request = queryRequest();
        assertEquals("default", request.getDatabaseName());
        assertEquals("books", request.getCollectionName());
        assertEquals(List.of("count(*)"), request.getOutputFields());
        assertEquals("", request.getFilter());
        assertEquals(ConsistencyLevel.STRONG, request.getConsistencyLevel());
        assertEquals(List.of(Map.of(label, 42L)), result.rows());
        assertEquals(label, result.columns().get(0).name);
        assertEquals(Types.BIGINT, result.columns().get(0).jdbcType);
        try (java.sql.ResultSet rs = JdbcProxy.resultSet(result)) {
            assertTrue(rs.next());
            assertEquals(42L, rs.getLong(1));
            assertEquals(42L, rs.getLong(label));
            assertEquals(Types.BIGINT, rs.getMetaData().getColumnType(1));
            assertEquals(label, rs.getMetaData().getColumnLabel(1));
            assertFalse(rs.next());
        }
        verify(client, never()).describeCollection(any(DescribeCollectionReq.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) AS total FROM analytics.books PARTITION recent WHERE id = 1",
            "COUNT FROM analytics.books PARTITION recent WHERE id = 1"
    })
    void countPreservesDatabasePartitionAndFilter(String sql) throws SQLException {
        when(client.query(any(QueryReq.class))).thenReturn(countResponse(0L));
        assertEquals(0L, executor.execute(sql).rows().get(0).values().iterator().next());
        QueryReq request = queryRequest();
        assertEquals("analytics", request.getDatabaseName());
        assertEquals("books", request.getCollectionName());
        assertEquals(List.of("recent"), request.getPartitionNames());
        assertEquals("id == 1", request.getFilter());
    }

    @Test
    void scalarSelectPreservesPaginationPartitionAndSort() throws SQLException {
        stubSelect();
        executor.execute("SELECT b.id FROM analytics.books AS b PARTITION recent WHERE b.id IN (1, 2) ORDER BY b.id DESC LIMIT 5 OFFSET 2");
        QueryReq request = queryRequest();
        assertEquals("analytics", request.getDatabaseName());
        assertEquals(List.of("id"), request.getOutputFields());
        assertEquals(List.of("recent"), request.getPartitionNames());
        assertEquals("id IN [ 1 , 2 ]", request.getFilter());
        assertEquals(5, request.getLimit());
        assertEquals(2, request.getOffset());
        assertEquals("id", request.getOrderByFields().get(0).getFieldName());
        assertEquals(AggDirection.DESC, request.getOrderByFields().get(0).getDirection());
    }

    @Test
    void scalarSelectUsesConfiguredDefaultLimit() throws SQLException {
        stubSelect();
        executor.execute("SELECT * FROM books");
        assertEquals(500, queryRequest().getLimit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM books LIMIT 0",
            "SELECT id FROM books ORDER BY vector <-> [0.1, 0.2] LIMIT 0"})
    void zeroLimitReturnsNoRowsInsteadOfDefaultLimit(String sql) throws SQLException {
        stubSelect();
        assertTrue(executor.execute(sql).rows().isEmpty());
        verify(client, never()).query(any(QueryReq.class));
        verify(client, never()).search(any(SearchReq.class));
    }

    @Test
    void scalarSortDefaultsToAscending() throws SQLException {
        stubSelect();
        executor.execute("SELECT id FROM books ORDER BY id");
        assertEquals(AggDirection.ASC, queryRequest().getOrderByFields().get(0).getDirection());
    }

    @ParameterizedTest
    @CsvSource({"<->, L2", "<=>, COSINE", "<#>, IP"})
    void vectorSelectPreservesMetricPaginationAndPartition(String operator, IndexParam.MetricType metric) throws SQLException {
        stubSelect();
        when(client.search(any(SearchReq.class))).thenReturn(SearchResp.builder().searchResults(List.of()).build());
        executor.execute("SELECT id FROM books PARTITION recent WHERE id <> 1 ORDER BY vector "
                + operator + " [0.1, -2e-3] LIMIT 5 OFFSET 2");
        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq request = captor.getValue();
        assertEquals(metric, request.getMetricType());
        assertEquals("vector", request.getAnnsField());
        assertEquals("id != 1", request.getFilter());
        assertEquals(List.of("recent"), request.getPartitionNames());
        assertEquals(5, request.getLimit());
        assertEquals(2, request.getOffset());
        verify(client, never()).query(any(QueryReq.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT( FROM books", "SELECT COUNT(id) FROM books",
            "SELECT COUNT(*), id FROM books", "SELECT * FROM books WHERE id =",
            "SELECT * FROM books @", "SELECT * FROM books; DELETE FROM books WHERE id = 1"
    })
    void rejectsInvalidSqlBeforeAnySdkCall(String sql) {
        assertThrows(SQLException.class, () -> executor.execute(sql));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsUnsupportedAggregateClausesBeforeAnySdkCall() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> executor.execute("SELECT COUNT(*) FROM books ORDER BY id"));
        verifyNoInteractions(client);
    }

    @Test
    void executesConstantSelectWithoutSdk() throws SQLException {
        assertEquals(Map.of("COLUMN1", 1L, "COLUMN2", "keep alive"),
                executor.execute("SELECT 1, 'keep alive'").rows().get(0));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"LIMIT 5 | 1", "LIMIT 0 | 0", "LIMIT 5 OFFSET 1 | 0", "OFFSET 0 | 1"})
    void paginatesAggregateRowInsteadOfCountedEntities(String tail, int rows) throws SQLException {
        when(client.query(any(QueryReq.class))).thenReturn(countResponse(42L));
        QueryResult result = executor.execute("SELECT COUNT(*) FROM books " + tail);
        assertEquals(rows, result.rows().size());
        assertEquals(Types.BIGINT, result.columns().get(0).jdbcType);
        assertEquals(List.of("count(*)"), queryRequest().getOutputFields());
    }

    @Test
    void countStripsTableAliasWithoutChangingStringLiterals() throws SQLException {
        when(client.query(any(QueryReq.class))).thenReturn(countResponse(0L));
        executor.execute("SELECT COUNT(*) FROM books AS b WHERE b.title = 'b.id'");
        assertEquals("title == 'b.id'", queryRequest().getFilter());
    }

    private void stubSelect() {
        when(client.describeCollection(any(DescribeCollectionReq.class))).thenReturn(DescribeCollectionResp.builder().build());
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder().queryResults(List.of()).build());
    }

    private QueryReq queryRequest() {
        ArgumentCaptor<QueryReq> captor = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(captor.capture());
        return captor.getValue();
    }

    private QueryResp countResponse(long count) {
        return QueryResp.builder().queryResults(List.of(QueryResp.QueryResult.builder()
                .entity(Map.of("count(*)", count)).build())).build();
    }
}
