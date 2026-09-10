package com.pengcheng.phoenixgraph.query;

import java.util.List;

/**
 * Result of a query execution.
 *
 * @param rows          result rows in an engine-defined shape
 * @param elapsedMillis execution time in milliseconds
 */
public record QueryResult(List<Object> rows, long elapsedMillis) {

    public static QueryResult empty(long elapsedMillis) {
        return new QueryResult(List.of(), elapsedMillis);
    }
}
