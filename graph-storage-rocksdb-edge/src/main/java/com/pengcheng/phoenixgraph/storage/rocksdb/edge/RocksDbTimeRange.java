package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

/**
 * The time window an edge-table scan covers.
 *
 * <p>An edge table is ordered by creation time inside a {@code (vertex, direction)} prefix,
 * so a window is not a filter but the range the iterator walks: the scan seeks straight to
 * {@code fromTime} and stops at {@code toTime}. The default is everything, which is what a
 * traversal without a time bound asks for, while a client that knows the interesting period
 * (an hour of payments, a day of logins) reads only that slice of the table.
 *
 * @param fromTime inclusive lower bound of {@code createdTime} ({@link Long#MIN_VALUE} = open)
 * @param toTime   inclusive upper bound of {@code createdTime} ({@link Long#MAX_VALUE} = open)
 */
public record RocksDbTimeRange(long fromTime, long toTime) {

    /** Every edge, whatever its creation time. */
    public static final RocksDbTimeRange ALL = new RocksDbTimeRange(Long.MIN_VALUE, Long.MAX_VALUE);

    public RocksDbTimeRange {
        if (fromTime > toTime) {
            throw new IllegalArgumentException("fromTime " + fromTime + " is after toTime " + toTime);
        }
    }

    /** A window from {@code fromTime} (inclusive) onwards. */
    public static RocksDbTimeRange from(long fromTime) {
        return new RocksDbTimeRange(fromTime, Long.MAX_VALUE);
    }

    /** A window up to {@code toTime} (inclusive). */
    public static RocksDbTimeRange until(long toTime) {
        return new RocksDbTimeRange(Long.MIN_VALUE, toTime);
    }

    /** A closed window. */
    public static RocksDbTimeRange of(long fromTime, long toTime) {
        return new RocksDbTimeRange(fromTime, toTime);
    }

    /** Whether a creation time falls inside the window. */
    public boolean contains(long createdTime) {
        return createdTime >= fromTime && createdTime <= toTime;
    }

    /** Whether the window has no bounds at all. */
    public boolean isUnbounded() {
        return fromTime == Long.MIN_VALUE && toTime == Long.MAX_VALUE;
    }
}
