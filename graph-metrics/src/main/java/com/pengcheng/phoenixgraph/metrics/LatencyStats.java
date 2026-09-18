package com.pengcheng.phoenixgraph.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * A latency histogram over a fixed grid of buckets.
 *
 * <p>The grid is linear up to {@value #LINEAR_LIMIT_NANOS} ns and geometric above it,
 * with {@value #SUB_BUCKETS} buckets per power of two. That puts any sample within
 * ±2.2 % of its bucket bounds, so the percentiles a dashboard shows are accurate to a few
 * percent of the reported value, while recording stays a single atomic increment and
 * memory stays bounded ({@value #BUCKETS} longs per histogram).
 *
 * <p>Percentiles interpolate linearly inside a bucket, which is enough at this resolution.
 */
public final class LatencyStats {

    /** Below this, buckets are 1 ns wide (a microsecond of resolution at the top). */
    static final long LINEAR_LIMIT_NANOS = 1_024L;
    /** Buckets per power of two above the linear part (16 → ratio 2^(1/16) ≈ 1.044). */
    static final int SUB_BUCKETS = 16;
    private static final int SUB_BITS = 4;
    /** Powers of two covered above the linear part (1 µs … ~2.4 days). */
    private static final int OCTAVES = 47;

    /** Number of buckets in the grid. */
    public static final int BUCKETS = (int) LINEAR_LIMIT_NANOS + OCTAVES * SUB_BUCKETS;
    private static final int FIRST_LOG_BUCKET = (int) LINEAR_LIMIT_NANOS;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final LongAdder total = new LongAdder();
    private final LongAdder sumNanos = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong();

    /** The bucket a latency falls into; the last bucket collects everything larger. */
    public static int bucketOf(long nanos) {
        if (nanos < LINEAR_LIMIT_NANOS) {
            return (int) Math.max(0L, nanos);
        }
        int octave = 63 - Long.numberOfLeadingZeros(nanos);
        int sub = (int) ((nanos >>> Math.max(0, octave - SUB_BITS)) & (SUB_BUCKETS - 1));
        int index = FIRST_LOG_BUCKET + (octave - 10) * SUB_BUCKETS + sub;
        return Math.min(BUCKETS - 1, Math.max(FIRST_LOG_BUCKET, index));
    }

    /** Lower bound of a bucket, in nanoseconds (inclusive). */
    public static double lowerBoundOf(int bucket) {
        if (bucket < FIRST_LOG_BUCKET) {
            return bucket;
        }
        int offset = Math.min(bucket, BUCKETS - 1) - FIRST_LOG_BUCKET;
        int octave = 10 + offset / SUB_BUCKETS;
        int sub = offset % SUB_BUCKETS;
        // inside an octave the grid is linear, so the bound follows bucketOf exactly
        return Math.pow(2, octave) + sub * Math.pow(2, octave - SUB_BITS);
    }

    /** Upper bound of a bucket, in nanoseconds (exclusive). */
    public static double upperBoundOf(int bucket) {
        if (bucket < FIRST_LOG_BUCKET) {
            return bucket + 1d;
        }
        int offset = Math.min(bucket, BUCKETS - 1) - FIRST_LOG_BUCKET;
        int octave = 10 + offset / SUB_BUCKETS;
        return lowerBoundOf(bucket) + Math.pow(2, octave - SUB_BITS);
    }

    public void record(long nanos) {
        counts.incrementAndGet(bucketOf(nanos));
        total.increment();
        sumNanos.add(nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
    }

    /** Merges another histogram into this one (used when loading a snapshot). */
    public void merge(LatencyStats other) {
        for (int i = 0; i < BUCKETS; i++) {
            long value = other.counts.get(i);
            if (value != 0) {
                counts.addAndGet(i, value);
            }
        }
        total.add(other.total.sum());
        sumNanos.add(other.sumNanos.sum());
        maxNanos.accumulateAndGet(other.maxNanos.get(), Math::max);
    }

    public long count() {
        return total.sum();
    }

    /** Mean latency in milliseconds, or 0 when nothing was recorded. */
    public double meanMillis() {
        long n = count();
        return n == 0 ? 0d : sumNanos.sum() / 1_000_000d / n;
    }

    public double maxMillis() {
        return maxNanos.get() / 1_000_000d;
    }

    /** Sum of every recorded sample, in nanoseconds. */
    public long sumNanos() {
        return sumNanos.sum();
    }

    /** Largest recorded sample, in nanoseconds. */
    public long maxNanos() {
        return maxNanos.get();
    }

    /** Percentile (0..1) in milliseconds, interpolated inside the bucket it lands in. */
    public double percentileMillis(double percentile) {
        long n = count();
        if (n == 0) {
            return 0d;
        }
        long target = Math.max(1, (long) Math.ceil(percentile * n));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            long bucketCount = counts.get(i);
            if (bucketCount == 0) {
                continue;
            }
            if (seen + bucketCount >= target) {
                double lower = lowerBoundOf(i);
                double upper = upperBoundOf(i);
                double ratio = (target - seen) / (double) bucketCount;
                double value = lower + (upper - lower) * ratio;
                return Math.max(value, lower) / 1_000_000d;
            }
            seen += bucketCount;
        }
        return maxMillis();
    }

    /** Raw buckets as {@code [bucket index, count]} pairs, for persistence. */
    public List<long[]> buckets() {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < BUCKETS; i++) {
            long value = counts.get(i);
            if (value != 0) {
                out.add(new long[]{i, value});
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Rebuilds a histogram from {@link #buckets()} plus the aggregate numbers. */
    public static LatencyStats of(long count, long sumNanos, long maxNanos, List<long[]> buckets) {
        LatencyStats stats = new LatencyStats();
        if (buckets != null) {
            for (long[] bucket : buckets) {
                if (bucket == null || bucket.length < 2 || bucket[0] < 0) {
                    continue;
                }
                int index = (int) Math.min(BUCKETS - 1, bucket[0]);
                stats.counts.addAndGet(index, bucket[1]);
            }
        }
        stats.total.add(Math.max(count, 0));
        stats.sumNanos.add(Math.max(sumNanos, 0));
        stats.maxNanos.set(Math.max(maxNanos, 0));
        return stats;
    }
}
