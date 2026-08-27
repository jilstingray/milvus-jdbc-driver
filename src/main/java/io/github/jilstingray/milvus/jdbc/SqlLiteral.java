package io.github.jilstingray.milvus.jdbc;

import java.lang.reflect.Array;
import java.util.Iterator;

final class SqlLiteral {
    private SqlLiteral() {
    }

    static String format(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Iterable<?>) {
            StringBuilder out = new StringBuilder("[");
            Iterator<?> iterator = ((Iterable<?>) value).iterator();
            while (iterator.hasNext()) {
                out.append(format(iterator.next()));
                if (iterator.hasNext()) {
                    out.append(", ");
                }
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder out = new StringBuilder("[");
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(format(Array.get(value, i)));
            }
            return out.append(']').toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
