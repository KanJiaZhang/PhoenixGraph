package com.pengcheng.phoenixgraph.http.filter;

/**
 * A request body larger than {@code phoenixgraph.limits.max-body-bytes} -&gt; HTTP 413.
 *
 * <p>Thrown while the body is being read, which is before any parsing, any graph lookup and any storage call:
 * the cheapest possible place to refuse a request that cannot be answered well anyway. The filter turns it into
 * a 413 with the same JSON error shape as every other failure, so a client sees one contract rather than an
 * HTML page from the container.
 */
public class BodyTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long actualBytes;
    private final int limitBytes;

    public BodyTooLargeException(long actualBytes, int limitBytes) {
        super("The request body is " + actualBytes + " bytes, over the limit of " + limitBytes
                + " (phoenixgraph.limits.max-body-bytes)");
        this.actualBytes = actualBytes;
        this.limitBytes = limitBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }

    public int limitBytes() {
        return limitBytes;
    }
}
