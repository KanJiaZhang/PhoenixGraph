package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.storage.TimeRange;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tiering decisions of the Aerospike-Edge scheme: the big-vertex test and the routing of a
 * read to the online / near-line / cold tiers by which creation-time spans its window overlaps.
 */
class EdgeTieringTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_000L * DAY;

    // --- the big-vertex test (item 5) ---

    @Test
    void aVertexIsBigWhenItsOfflineDegreeExceedsTheCap() {
        assertTrue(EdgeTiering.isBig(101, 100));
        assertTrue(EdgeTiering.isBig(100, 99));
        assertFalse(EdgeTiering.isBig(100, 100), "exactly at the cap is not big");
        assertFalse(EdgeTiering.isBig(50, 100));
        assertFalse(EdgeTiering.isBig(1_000_000_000L, 0), "a cap <= 0 means unlimited, never big");
    }

    // --- the read routing (items 1-6 of the read spec) ---

    @Test
    void aRecentWindowReadsOnlineAndNearlineTogether() {
        // online holds [NOW - 2 days, +infinity) and near-line holds the last 24h, so a recent
        // window overlaps both (online may hold a newer copy of an edge than near-line)
        assertEquals(EnumSet.of(EdgeTiering.Tier.ONLINE, EdgeTiering.Tier.NEARLINE),
                classify(TimeRange.of(NOW - DAY, NOW), NOW - 2 * DAY));
    }

    @Test
    void aWindowEntirelyInThePastReadsColdAlone() {
        // older than 24h: ignore near-line and online (the spec's rule 4)
        assertEquals(EnumSet.of(EdgeTiering.Tier.COLD),
                classify(TimeRange.of(NOW - 100 * DAY, NOW - 3 * DAY), Long.MAX_VALUE));
    }

    @Test
    void aWindowSpanningColdAndNearlineReadsBoth() {
        // the window straddles the 24h boundary
        assertEquals(EnumSet.of(EdgeTiering.Tier.COLD, EdgeTiering.Tier.NEARLINE),
                classify(TimeRange.of(NOW - 2 * DAY, NOW - DAY), NOW));
    }

    @Test
    void aWindowSpanningColdNearlineAndOnlineReadsAllThree() {
        // the spec's rule 6: from now back to three days ago
        assertEquals(EnumSet.of(EdgeTiering.Tier.ONLINE, EdgeTiering.Tier.NEARLINE,
                        EdgeTiering.Tier.COLD),
                classify(TimeRange.of(NOW - 3 * DAY, NOW), NOW - DAY));
    }

    @Test
    void aWindowBeyondTheOnlineEarliestButWithinNearlineReadsOnlineAndNearline() {
        // the online bin holds the last hour; the window is the last 23 hours, so it starts before
        // the online bin's oldest edge (rule 3) but is still within near-line's 24h
        long oneHour = 60L * 60 * 1000;
        assertEquals(EnumSet.of(EdgeTiering.Tier.ONLINE, EdgeTiering.Tier.NEARLINE),
                classify(TimeRange.of(NOW - 23 * oneHour, NOW), NOW - oneHour));
    }

    @Test
    void anEmptyInlineBinNeverReadsOnline() {
        assertEquals(EnumSet.of(EdgeTiering.Tier.NEARLINE),
                classify(TimeRange.of(NOW - DAY, NOW), Long.MAX_VALUE));
    }

    private static EnumSet<EdgeTiering.Tier> classify(TimeRange range, long onlineEarliest) {
        return EdgeTiering.classify(range, NOW, EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS,
                onlineEarliest);
    }
}
