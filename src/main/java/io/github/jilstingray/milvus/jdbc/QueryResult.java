package io.github.jilstingray.milvus.jdbc;

import java.util.List;
import java.util.Map;

final class QueryResult {
    private final List<Map<String, Object>> rows;
    private final List<ColumnInfo> columns;
    private final int updateCount;

    private QueryResult(List<Map<String, Object>> rows, List<ColumnInfo> columns, int updateCount) {
        this.rows = rows;
        this.columns = columns;
        this.updateCount = updateCount;
    }

    static QueryResult rows(List<Map<String, Object>> rows) {
        return new QueryResult(rows, null, -1);
    }

    static QueryResult rows(List<Map<String, Object>> rows, List<ColumnInfo> columns) {
        return new QueryResult(rows, columns, -1);
    }

    static QueryResult update(long count) {
        return new QueryResult(null, null, count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
    }

    boolean hasResultSet() {
        return rows != null;
    }

    List<Map<String, Object>> rows() {
        return rows;
    }

    List<ColumnInfo> columns() {
        return columns;
    }

    int updateCount() {
        return updateCount;
    }
}
