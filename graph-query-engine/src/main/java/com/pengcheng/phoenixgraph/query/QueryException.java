package com.pengcheng.phoenixgraph.query;

/**
 * Thrown when a query cannot be parsed, planned or executed.
 */
public class QueryException extends RuntimeException {

    public QueryException(String message) {
        super(message);
    }

    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
