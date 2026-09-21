package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.EnumSet;

/**
 * The tiering decisions of the Aerospike-Edge scheme: which vertex is a <em>big</em> vertex, and
 * which storage tiers a read must consult.
 *
 * <p>A vertex is big for an edge class and direction when its offline-computed degree exceeds the
 * schema's per-direction cap ({@code maxInDegree} / {@code maxOutDegree}); below the cap the
 * inline adjacency list holds everything and the read degrades to the plain inline Aerospike path,
 * exactly as it was before the tiers existed. That is the compatibility guarantee: a schema whose
 * storage is switched back to plain Aerospike never reaches this code.
 *
 * <p>The three tiers cover disjoint creation-time spans and one read is routed by which spans its
 * window overlaps:
 *
 * <pre>
 * cold      (-infinity, now - nearlineWindow]        the offline Edge_Out / Edge_In tables
 * nearline  (now - nearlineWindow, now]              the near-line KVs (TTL 7 days)
 * online    [onlineEarliest, +infinity)              the inline adjacency bin (bounded by the degree cap)
 * </pre>
 *
 * <p>That one rule yields all six of the user's read cases: a window entirely inside the online
 * span reads online alone; entirely in the past reads cold alone (near-line and online ignored);
 * spanning two or three spans merges those tiers - the edge id resolves the copies (an update
 * lifts {@code lastUpdatedTime}, so the newest copy wins, as it does everywhere in this store).
 */
public final class EdgeTiering {

    /** The default near-line window: the most recent 24 hours (the spec's boundary). */
    public static final long DEFAULT_NEARLINE_WINDOW_MILLIS = 24L * 60 * 60 * 1000;

    /** A storage tier of the Aerospike-Edge scheme. */
    public enum Tier {
        /** The inline adjacency bin (the newest few edges). */
        ONLINE,
        /** The near-line KVs of the last 24 hours. */
        NEARLINE,
        /** The offline aggregated edge tables. */
        COLD
    }

    private EdgeTiering() {
    }

    /**
     * The big-vertex test of the spec: the offline degree exceeds the schema's per-direction cap.
     * A cap of {@code <= 0} means unlimited, which can never be exceeded.
     */
    public static boolean isBig(long degree, int maxDegree) {
        return maxDegree > 0 && degree > maxDegree;
    }

    /**
     * The tiers whose time coverage overlaps {@code range}, computed at {@code nowMillis}.
     *
     * @param onlineEarliestMillis the creation time of the oldest edge still inline, or
     *                             {@link Long#MAX_VALUE} when the inline bin is empty
     */
    public static EnumSet<Tier> classify(TimeRange range, long nowMillis, long nearlineWindowMillis,
                                         long onlineEarliestMillis) {
        EnumSet<Tier> tiers = EnumSet.noneOf(Tier.class);
        long nearlineFrom = nowMillis - nearlineWindowMillis;
        if (range.overlaps(Long.MIN_VALUE, nearlineFrom - 1)) {
            tiers.add(Tier.COLD);
        }
        if (range.overlaps(nearlineFrom, nowMillis)) {
            tiers.add(Tier.NEARLINE);
        }
        if (onlineEarliestMillis != Long.MAX_VALUE
                && range.overlaps(onlineEarliestMillis, Long.MAX_VALUE)) {
            tiers.add(Tier.ONLINE);
        }
        return tiers;
    }
}
