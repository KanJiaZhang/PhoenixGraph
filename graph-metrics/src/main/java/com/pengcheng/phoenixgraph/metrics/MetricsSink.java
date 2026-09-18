package com.pengcheng.phoenixgraph.metrics;

/**
 * Somewhere metrics can be recorded.
 *
 * <p>{@link GraphMetrics} is the real implementation; {@link GraphMetrics#noop()} drops
 * everything and {@link MutableMetricsSink} is the late-bound one a store holds while it
 * is being built (the graph's metrics are wired in after the stores exist).
 */
public interface MetricsSink {

    /** A sink that records nothing. */
    MetricsSink NOOP = new MetricsSink() {
        @Override
        public void increment(String metric, long delta) {
        }

        @Override
        public void recordLatency(String metric, long nanos) {
        }
    };

    void increment(String metric, long delta);

    default void increment(String metric) {
        increment(metric, 1L);
    }

    default void recordLatency(String metric, long nanos) {
    }
}
