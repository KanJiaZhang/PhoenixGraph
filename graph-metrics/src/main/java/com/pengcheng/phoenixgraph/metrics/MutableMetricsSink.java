package com.pengcheng.phoenixgraph.metrics;

/**
 * A {@link MetricsSink} whose target is set later.
 *
 * <p>Stores are built before the graph's metrics exist (the graph name and its
 * persistence are known once the schema is loaded), so they hold one of these from the
 * start and the application points it at the graph's {@link GraphMetrics} during wiring.
 * Until then — and in tests — it drops everything.
 */
public final class MutableMetricsSink implements MetricsSink {

    private volatile MetricsSink target = NOOP;

    /** Points this sink at a target; {@code null} makes it mute again. */
    public void target(MetricsSink target) {
        this.target = target == null ? NOOP : target;
    }

    /** The current target. */
    public MetricsSink target() {
        return target;
    }

    /** Whether anything is listening. */
    public boolean recording() {
        return target != NOOP;
    }

    @Override
    public void increment(String metric, long delta) {
        target.increment(metric, delta);
    }

    @Override
    public void recordLatency(String metric, long nanos) {
        target.recordLatency(metric, nanos);
    }
}
