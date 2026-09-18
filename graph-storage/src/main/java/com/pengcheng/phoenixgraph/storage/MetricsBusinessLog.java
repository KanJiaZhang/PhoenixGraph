package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;

import java.util.Map;
import java.util.Objects;

/**
 * Turns the write outcomes the stores already log into system metrics.
 *
 * <p>Every store funnels its writes through a {@link BusinessLog}, and that log already
 * distinguishes the three things a dashboard cares about: a write that went through, a
 * write that conflicted (a stale property version, {@code CONFLICT}) and a write that
 * failed after its retries. Decorating the log therefore counts logical vertex and edge
 * writes without touching a single store method.
 *
 * <p>The operation name decides whether the write is about vertices or edges
 * ({@code upsertVertices} versus {@code upsertEdges}), so the counters line up with what
 * a user sees in the schema.
 */
public final class MetricsBusinessLog implements BusinessLog {

    private final BusinessLog delegate;
    private final MetricsSink metrics;

    public MetricsBusinessLog(BusinessLog delegate, MetricsSink metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = metrics == null ? MetricsSink.NOOP : metrics;
    }

    @Override
    public void record(BusinessLogEntry entry) {
        if (entry != null) {
            count(entry);
        }
        delegate.record(entry);
    }

    private void count(BusinessLogEntry entry) {
        boolean aboutEdges = isAboutEdges(entry.operation());
        String write = aboutEdges ? Metrics.EDGE_WRITE : Metrics.VERTEX_WRITE;
        metrics.increment(write);
        metrics.increment(Metrics.items(write));
        if (entry.success()) {
            return;
        }
        if (isConflict(entry.details())) {
            metrics.increment(aboutEdges ? Metrics.EDGE_WRITE_STALE : Metrics.VERTEX_WRITE_STALE);
            return;
        }
        metrics.increment(Metrics.failed(write));
    }

    /** A conflict is a stale-version rejection; the writers mark it in the details. */
    private static boolean isConflict(Map<String, Object> details) {
        if (details == null) {
            return false;
        }
        return Boolean.TRUE.equals(details.get("conflict")) || Boolean.TRUE.equals(details.get("stale"));
    }

    static boolean isAboutEdges(String operation) {
        if (operation == null) {
            return false;
        }
        String lower = operation.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("edge");
    }

    /** The log this decorator wraps. */
    public BusinessLog delegate() {
        return delegate;
    }
}
