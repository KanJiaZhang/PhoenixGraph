package com.pengcheng.phoenixgraph.storage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * A creation-time window for edge reads.
 *
 * <p>Some storage layouts cannot answer "all edges of this vertex": a vertex with a hundred
 * million edges is written to an aggregated edge table (see the Aerospike {@code Edge_Out} /
 * {@code Edge_In} tables), where the edges are grouped by creation time and only a window can be
 * read. A query therefore carries a window, and a backend that needs one says so instead of
 * scanning:
 *
 * <pre>
 * g.V('hub','Account').createdBetween('2025-01-01', '2025-02-01').outE('Payment').limit(500)
 * </pre>
 *
 * <p>Bounds are inclusive; {@code from}/{@code to} may be given as epoch millis or as a date
 * ({@code 2025-01-01}, {@code 2025-01-01T12:30}, {@code 2025-01-01T12:30:45}) in UTC.
 *
 * @param fromTime inclusive lower bound of {@code createdTime} ({@link Long#MIN_VALUE} = open)
 * @param toTime   inclusive upper bound of {@code createdTime} ({@link Long#MAX_VALUE} = open)
 */
public record TimeRange(long fromTime, long toTime) {

    /** Every edge, whatever its creation time. */
    public static final TimeRange ALL = new TimeRange(Long.MIN_VALUE, Long.MAX_VALUE);

    public TimeRange {
        if (fromTime > toTime) {
            throw new IllegalArgumentException("fromTime " + fromTime + " is after toTime " + toTime);
        }
    }

    /** A window from {@code fromTime} (inclusive) onwards. */
    public static TimeRange from(long fromTime) {
        return new TimeRange(fromTime, Long.MAX_VALUE);
    }

    /** A window up to {@code toTime} (inclusive). */
    public static TimeRange until(long toTime) {
        return new TimeRange(Long.MIN_VALUE, toTime);
    }

    /** A closed window. */
    public static TimeRange of(long fromTime, long toTime) {
        return new TimeRange(fromTime, toTime);
    }

    /** Whether a creation time falls inside the window. */
    public boolean contains(long createdTime) {
        return createdTime >= fromTime && createdTime <= toTime;
    }

    /** Whether the window has no bounds at all (the "everything" range). */
    public boolean isUnbounded() {
        return fromTime == Long.MIN_VALUE && toTime == Long.MAX_VALUE;
    }

    /** Whether this window overlaps {@code [otherFrom, otherTo]}. */
    public boolean overlaps(long otherFrom, long otherTo) {
        return otherFrom <= toTime && otherTo >= fromTime;
    }

    /**
     * Parses a step's two bounds: epoch millis or a UTC date/time
     * ({@code 2025-01-01}, {@code 2025-01-01T12:30}, {@code 2025-01-01T12:30:45}).
     *
     * @throws IllegalArgumentException when a bound cannot be read
     */
    public static TimeRange parse(String fromText, String toText) {
        return new TimeRange(parseBound(fromText, Long.MIN_VALUE, true),
                parseBound(toText, Long.MAX_VALUE, false));
    }

    private static long parseBound(String text, long fallback, boolean from) {
        if (text == null || text.isBlank() || text.equals("*")) {
            return fallback;
        }
        String value = text.trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // not epoch millis: try a date, where a bare date covers the whole day
        }
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                LocalDateTime at = from ? date.atStartOfDay() : date.atTime(23, 59, 59, 999_000_000);
                return at.toInstant(ZoneOffset.UTC).toEpochMilli();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Cannot read '" + text
                    + "' as a time; use epoch millis or a UTC date such as 2025-01-01 or"
                    + " 2025-01-01T12:30", e);
        }
    }

    /** A readable form used in logs and error messages. */
    public String describe() {
        if (isUnbounded()) {
            return "all time";
        }
        return "[" + bound(fromTime) + ", " + bound(toTime) + "]";
    }

    private static String bound(long time) {
        if (time == Long.MIN_VALUE) {
            return "*";
        }
        if (time == Long.MAX_VALUE) {
            return "*";
        }
        return java.time.Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC).toString();
    }
}
