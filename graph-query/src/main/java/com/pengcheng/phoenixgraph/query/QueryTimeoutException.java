package com.pengcheng.phoenixgraph.query;

/**
 * A query that ran out of the budget the service gave it.
 *
 * <p>Deliberately <b>not</b> a {@link QueryException}: a malformed query is the caller's mistake (400), while
 * a query that was still working when its time ran out is the *server* declining to spend more of the
 * instance on it (503, retryable with a narrower query). Mapping the two to one status code would tell an
 * integrator to fix a query that has nothing wrong with it.
 */
public class QueryTimeoutException extends RuntimeException {

    public QueryTimeoutException(String message) {
        super(message);
    }
}
