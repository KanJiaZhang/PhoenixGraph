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
 *
 * <p><b>Percentiles are windowed; totals are not.</b> {@link #percentileMillis(double)} reads the last
 * {@value #WINDOW_SLOTS} minutes ({@link #cumulativePercentileMillis(double)} reads everything since the
 * process started), while {@link #count()}, {@link #meanMillis()} and {@link #sumNanos()} stay cumulative. The
 * split is deliberate and it came out of the on-call drill: an alert like "p99 > 60 ms" read against a
 * since-start percentile can never clear. One slow query - a cold plan, a store hiccup, a loaded machine during
 * a benchmark - raises a lifetime p99 permanently, so the alert fires forever and the runbook's "it clears when
 * the latency drops" is a lie. Prometheus's own `rate(...)` is windowed for the same reason; a histogram that
 * is not is the part that surprises people.
 */
public final class LatencyStats {
    /** Minutes kept for the windowed percentiles: the 5m window the alert rules use. */
    public static final int WINDOW_SLOTS = 5;
    /** How long one slot covers. */
    static final long SLOT_NANOS = 60_000_000_000L;

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

    /**
     * The per-minute histograms the windowed percentiles are summed from, oldest first.
     *
     * <p>Guarded by {@code this} rather than lock-free: recording is one increment plus one slot lookup, and
     * contention only appears at rates where a histogram lock is not the bottleneck. Each slot keeps its own
     * bucket array, so a rotation never has to walk another thread's increments.
     */
    private final AtomicLongArray[] window = new AtomicLongArray[WINDOW_SLOTS];
    private final long[] windowStart = new long[WINDOW_SLOTS];
    private int currentSlot;

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
        record(nanos, clock.getAsLong());
    }

    /** The clock the window rotates on. {@link System#nanoTime()} in production; a test seam otherwise. */
    private java.util.function.LongSupplier clock = System::nanoTime;

    /** Lets a test drive the window without sleeping a minute (package-private on purpose). */
    void clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** Records a sample as if it arrived at {@code nowNanos} (a {@link System#nanoTime()} reading). */
    void record(long nanos, long nowNanos) {
        counts.incrementAndGet(bucketOf(nanos));
        total.increment();
        sumNanos.add(nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
        synchronized (this) {
            AtomicLongArray slot = slotFor(nowNanos);
            slot.incrementAndGet(bucketOf(nanos));
        }
    }

    /** The window slot {@code nowNanos} belongs to, rotating the ring when the minute has turned. */
    private AtomicLongArray slotFor(long nowNanos) {
        long minute = Math.floorDiv(nowNanos, SLOT_NANOS);
        if (window[currentSlot] == null) {
            // first sample: start the ring here, so the slots before now are simply empty
            for (int i = 0; i < WINDOW_SLOTS; i++) {
                windowStart[i] = minute - (WINDOW_SLOTS - 1 - i);
                window[i] = new AtomicLongArray(BUCKETS);
            }
            currentSlot = WINDOW_SLOTS - 1;
            return window[currentSlot];
        }
        long currentMinute = windowStart[currentSlot];
        if (minute == currentMinute) {
            return window[currentSlot];
        }
        long steps = Math.min(minute - currentMinute, WINDOW_SLOTS);
        for (long step = 0; step < steps; step += 1) {
            currentSlot = (currentSlot + 1) % WINDOW_SLOTS;
            windowStart[currentSlot] = currentMinute + step + 1;
            window[currentSlot].set(0, 0L); // reuse the array instead of allocating a fresh one per minute
            for (int bucket = 1; bucket < BUCKETS; bucket++) {
                window[currentSlot].set(bucket, 0L);
            }
        }
        return window[currentSlot];
    }

    /**
     * Merges another histogram into this one (used when loading a snapshot).
     *
     * <p>Restored samples do <b>not</b> enter the window. A snapshot carries no per-minute timestamps, so they
     * cannot be placed in time - and the window means "recent", which a value read back from an earlier process
     * is not. Treating them as just-happened (the first version of this code) made a graph nobody was querying
     * report a 1.2 s p99 from a benchmark an hour earlier, so the latency alert fired on an idle graph and could
     * not clear. The cumulative totals - which the day rollup reads - are restored exactly, which is what the
     * snapshot exists for.
     */
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

    /**
     * Percentile (0..1) in milliseconds over the last {@value #WINDOW_SLOTS} minutes, interpolated inside the
     * bucket it lands in.
     *
     * <p>This is the one the alerts read (see the class comment for why), and it is empty - {@code 0} - when
     * nothing was recorded in the window, which is how a quiet instance tells an alert to stand down instead of
     * reporting the latency of a query from an hour ago.
     */
    public double percentileMillis(double percentile) {
        return percentileMillis(percentile, histogramOf(windowSnapshot()));
    }

    /** Percentile (0..1) in milliseconds over every sample since this process started. */
    public double cumulativePercentileMillis(double percentile) {
        return percentileMillis(percentile, counts);
    }

    /** Number of samples in the window the percentiles are computed from. */
    public long windowCount() {
        return countOf(histogramOf(windowSnapshot()));
    }

    /**
     * The window, rotated to <em>now</em> before it is read.
     *
     * <p>The rotation cannot only happen on {@code record}: a graph that goes quiet keeps its last minute in the
     * window forever, so an alert reading it would stay firing on an instance that is merely idle (a Prometheus
     * scrape reads this exposition many times a minute and is not a query). Reading rotates, which is why this
     * is not a lock-free getter.
     */
    private AtomicLongArray[] windowSnapshot() {
        synchronized (this) {
            slotFor(clock.getAsLong());
            return window.clone();
        }
    }

    /** The window's buckets as one histogram (the slots with no samples contribute nothing). */
    private static AtomicLongArray histogramOf(AtomicLongArray[] slots) {
        AtomicLongArray merged = new AtomicLongArray(BUCKETS);
        for (AtomicLongArray slot : slots) {
            if (slot == null) {
                continue;
            }
            for (int i = 0; i < BUCKETS; i++) {
                long value = slot.get(i);
                if (value != 0) {
                    merged.addAndGet(i, value);
                }
            }
        }
        return merged;
    }

    private static long countOf(AtomicLongArray histogram) {
        long sum = 0;
        for (int i = 0; i < BUCKETS; i++) {
            sum += histogram.get(i);
        }
        return sum;
    }

    private double percentileMillis(double percentile, AtomicLongArray histogram) {
        long n = countOf(histogram);
        if (n == 0) {
            return 0d;
        }
        long target = Math.max(1, (long) Math.ceil(percentile * n));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            long bucketCount = histogram.get(i);
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
                // cumulative only: see merge() - a restored sample is history, not current latency
                stats.counts.addAndGet(index, bucket[1]);
            }
        }
        stats.total.add(Math.max(count, 0));
        stats.sumNanos.add(Math.max(sumNanos, 0));
        stats.maxNanos.set(Math.max(maxNanos, 0));
        return stats;
    }
}
