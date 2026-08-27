package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

final class PreparedStatementHandler extends StatementHandler {
    private final String sql;
    private final Map<Integer, Object> parameters = new HashMap<>();

    PreparedStatementHandler(MilvusConnection connection, String sql) {
        super(connection);
        this.sql = sql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("setNull".equals(name) && args != null && args.length >= 1 && args[0] instanceof Integer) {
            parameters.put((Integer) args[0], null);
            return null;
        }
        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
            parameters.put((Integer) args[0], args[1]);
            return null;
        }
        switch (name) {
            case "execute":
                return execute(boundSql()).hasResultSet();
            case "executeQuery":
                QueryResult query = execute(boundSql());
                if (!query.hasResultSet()) {
                    throw new SQLException("SQL did not produce a ResultSet");
                }
                return currentResultSet;
            case "executeUpdate":
            case "executeLargeUpdate":
                QueryResult update = execute(boundSql());
                if (update.hasResultSet()) {
                    throw new SQLException("SQL produced a ResultSet");
                }
                return method.getReturnType() == Long.TYPE ? (long) update.updateCount() : update.updateCount();
            case "clearParameters":
                parameters.clear();
                return null;
            default:
                return super.invoke(proxy, method, args);
        }
    }

    private String boundSql() throws SQLException {
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?') {
                if (!parameters.containsKey(index)) {
                    throw new SQLException("Missing parameter " + index);
                }
                out.append(SqlLiteral.format(parameters.get(index++)));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
