package com.pengcheng.phoenixgraph.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounds of a creation-time window: epoch millis, UTC dates, and the relative forms a client
 * writes when it means "the last two hours" ({@code -2h}, {@code -7d}).
 *
 * <p>A relative bound must be read against a clock, which is why {@link TimeRange#parse(String,
 * String, long)} takes one: a compiled query is cached and reused, so the same text has to mean
 * "two hours before the moment it runs" every time.
 */
class TimeRangeRelativeBoundsTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    void theRelativeUnitsAreMinutesHoursDaysAndWeeks() {
        assertEquals(NOW - 30 * MINUTE, TimeRange.relativeToNow("-30m", NOW));
        assertEquals(NOW - 2 * HOUR, TimeRange.relativeToNow("-2h", NOW));
        assertEquals(NOW - 23 * HOUR, TimeRange.relativeToNow("-23h", NOW));
        assertEquals(NOW - 7 * DAY, TimeRange.relativeToNow("-7d", NOW));
        assertEquals(NOW - 2 * 7 * DAY, TimeRange.relativeToNow("-2w", NOW));
        assertEquals(NOW - 90_000L, TimeRange.relativeToNow("-90s", NOW));
        assertEquals(NOW - 500L, TimeRange.relativeToNow("-500ms", NOW));
    }

    @Test
    void anythingElseIsNotARelativeBound() {
        assertNull(TimeRange.relativeToNow("2025-01-01", NOW), "a date");
        assertNull(TimeRange.relativeToNow("1790000000000", NOW), "epoch millis");
        assertNull(TimeRange.relativeToNow("*", NOW), "an open side");
        assertNull(TimeRange.relativeToNow("-13mo", NOW), "months are not a unit");
        assertNull(TimeRange.relativeToNow("-h", NOW), "no amount");
        assertNull(TimeRange.relativeToNow("-", NOW));
        assertNull(TimeRange.relativeToNow(null, NOW));
    }

    @Test
    void aWindowCanBeRelativeOnEitherSide() {
        TimeRange lastTwoHours = TimeRange.parse("-2h", "*", NOW);
        assertEquals(NOW - 2 * HOUR, lastTwoHours.fromTime());
        assertEquals(Long.MAX_VALUE, lastTwoHours.toTime());

        TimeRange older = TimeRange.parse("-7d", "-6d", NOW);
        assertEquals(NOW - 7 * DAY, older.fromTime());
        assertEquals(NOW - 6 * DAY, older.toTime());
        assertTrue(older.contains(NOW - 6 * DAY - HOUR));
        assertTrue(!older.contains(NOW));

        // and the absolute forms still read as before
        TimeRange day = TimeRange.parse("2026-01-01", "2026-01-01", NOW);
        assertEquals("2026-01-01T00:00:00Z", java.time.Instant.ofEpochMilli(day.fromTime()).toString());
        assertEquals(86_399_999L, day.toTime() - day.fromTime());
    }
}
