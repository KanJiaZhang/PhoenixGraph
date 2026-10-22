package com.pengcheng.phoenixgraph.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An immutable copy of one latency histogram, carrying the numbers a dashboard shows
 * next to the raw buckets that make the snapshot reloadable.
 *
 * @param count      number of samples
 * @param sumNanos   sum of all samples, in nanoseconds
 * @param maxNanos   largest sample, in nanoseconds
 * @param meanMillis mean latency
 * @param p50Millis  median latency
 * @param p95Millis  95th percentile
 * @param p99Millis  99th percentile
 * @param maxMillis  largest sample
 * @param buckets    {@code [upper bound in microseconds, count]} pairs
 */
public record LatencySnapshot(long count, long sumNanos, long maxNanos,
                              double meanMillis, double p50Millis, double p95Millis, double p99Millis,
                              double maxMillis, List<long[]> buckets) {

    public LatencySnapshot {
        buckets = buckets == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(buckets));
    }

    /**
     * Takes a snapshot of a live histogram.
     *
     * <p>The percentile fields are the <b>windowed</b> ones: the HTTP exposition and the alerts read them, and
     * an alert has to clear when the last five minutes are fast again. The bucket grid still carries the whole
     * cumulative history, which is what {@link #toStats()} restores - a value read back from an earlier process
     * is history, not current latency, so it does not enter the window.
     */
    public static LatencySnapshot of(LatencyStats stats) {
        return new LatencySnapshot(stats.count(), stats.sumNanos(), stats.maxNanos(), stats.meanMillis(),
                stats.percentileMillis(0.5),
                stats.percentileMillis(0.95), stats.percentileMillis(0.99),
                stats.maxMillis(), stats.buckets());
    }

    /** Rebuilds the histogram this snapshot was taken from. */
    public LatencyStats toStats() {
        return LatencyStats.of(count, sumNanos, maxNanos, buckets);
    }

    /** A compact view for logs and tests. */
    public Map<String, Object> summary() {
        return Map.of("count", count, "meanMs", round(meanMillis), "p50Ms", round(p50Millis),
                "p95Ms", round(p95Millis), "p99Ms", round(p99Millis), "maxMs", round(maxMillis));
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
