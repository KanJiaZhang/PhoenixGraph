package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.config.LimitsProperties;
import com.pengcheng.phoenixgraph.query.QueryDeadline;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The admission gate: how many requests this instance runs at once, and how fast one graph or one tenant
 * may ask.
 *
 * <p>Two limits that mean different things. Concurrency bounds *resources* (threads, connections, heap) -
 * it is what stops a hundred expensive queries from all being in the middle of a hop at the same time. A
 * rate bounds *fairness* (one caller cannot occupy the graph for everybody else) and is checked first, so a
 * flood is rejected in microseconds instead of queueing behind the semaphore.
 *
 * <p>Tenant limits are optional ({@code per-tenant-concurrent}, {@code per-tenant-per-second}; 0 means off).
 * A tenant is the authenticated key's tenant, so several keys can share a budget. With security off there is
 * no tenant and only the graph/instance limits apply.
 *
 * <p>The result is a plain enum rather than an exception: the caller (a servlet filter) has to decide the
 * status code and the {@code Retry-After} header, and a service that throws HTTP concerns is harder to test
 * than one that returns a verdict.
 */
@Component
public class RequestLimits {

    /** Why a request was refused, or that it was admitted. */
    public enum Admission {
        ADMITTED,
        /** Too many requests per second for this graph or tenant -&gt; 429. */
        RATE_LIMITED,
        /** No slot free within the wait budget, or this graph/tenant already has its share -&gt; 503. */
        BUSY,
    }

    private final LimitsProperties properties;
    private final Semaphore slots;
    private final Map<String, Semaphore> perGraphSlots = new ConcurrentHashMap<>();
    private final Map<String, Window> perGraphRate = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> perTenantSlots = new ConcurrentHashMap<>();
    private final Map<String, Window> perTenantRate = new ConcurrentHashMap<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    public RequestLimits(LimitsProperties properties) {
        this.properties = properties;
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrent()), true);
    }

    /** Admit for a graph without a tenant (security off, or a caller that only has a graph). */
    public Admission admit(String graph) {
        return admit(graph, null);
    }

    /**
     * Tries to take a slot for one request, waiting at most {@code queueWait} for it.
     *
     * @param graph  the graph the request names, or null when it names none (then only the instance-wide
     *               limits apply - a schema listing is cheap and must not be refused by a rate limit meant
     *               for queries)
     * @param tenant the authenticated key's tenant, or null when security is off
     */
    public Admission admit(String graph, String tenant) {
        if (!properties.enabled()) return Admission.ADMITTED;
        if (graph != null && properties.perGraphPerSecond() > 0
                && !withinRate(perGraphRate, graph, properties.perGraphPerSecond())) {
            refused.incrementAndGet();
            return Admission.RATE_LIMITED;
        }
        if (tenant != null && properties.perTenantPerSecond() > 0
                && !withinRate(perTenantRate, tenant, properties.perTenantPerSecond())) {
            refused.incrementAndGet();
            return Admission.RATE_LIMITED;
        }
        Semaphore graphSlots = graph == null ? null : perGraphSlots.computeIfAbsent(graph,
                key -> new Semaphore(Math.max(1, properties.perGraphConcurrent()), true));
        if (graphSlots != null && !graphSlots.tryAcquire()) {
            refused.incrementAndGet();
            return Admission.BUSY; // this graph is already using its share; waiting would not help
        }
        Semaphore tenantSlots = tenant == null || properties.perTenantConcurrent() <= 0 ? null
                : perTenantSlots.computeIfAbsent(tenant,
                        key -> new Semaphore(properties.perTenantConcurrent(), true));
        if (tenantSlots != null && !tenantSlots.tryAcquire()) {
            if (graphSlots != null) graphSlots.release();
            refused.incrementAndGet();
            return Admission.BUSY;
        }
        try {
            long millis = Math.max(0, properties.queueWait().toMillis());
            if (!slots.tryAcquire(millis, TimeUnit.MILLISECONDS)) {
                if (tenantSlots != null) tenantSlots.release();
                if (graphSlots != null) graphSlots.release();
                refused.incrementAndGet();
                return Admission.BUSY;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (tenantSlots != null) tenantSlots.release();
            if (graphSlots != null) graphSlots.release();
            refused.incrementAndGet();
            return Admission.BUSY;
        }
        admitted.incrementAndGet();
        QueryDeadline.set(properties.requestTimeout());
        return Admission.ADMITTED;
    }

    /** Gives the graph and instance slots back. */
    public void release(String graph) {
        release(graph, null);
    }

    /** Gives the graph, tenant and instance slots back. Must run in a finally block. */
    public void release(String graph, String tenant) {
        QueryDeadline.clear();
        if (!properties.enabled()) return;
        if (tenant != null && properties.perTenantConcurrent() > 0) {
            Semaphore tenantSlots = perTenantSlots.get(tenant);
            if (tenantSlots != null) tenantSlots.release();
        }
        if (graph != null) {
            Semaphore graphSlots = perGraphSlots.get(graph);
            if (graphSlots != null) graphSlots.release();
        }
        slots.release();
    }

    /** True when this key is inside its per-second budget for the current window. */
    private boolean withinRate(Map<String, Window> rates, String key, int limit) {
        long second = System.currentTimeMillis() / 1000;
        Window window = rates.computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            if (window.second != second) {
                window.second = second;
                window.count = 0;
            }
            window.count += 1;
            return window.count <= limit;
        }
    }

    /** How long the filter should tell a client to wait (the rate window is one second). */
    public Duration retryAfter() {
        return Duration.ofSeconds(Math.max(1, properties.retryAfterSeconds()));
    }

    /** The limits as they were configured, for the startup log line and the tests. */
    public LimitsProperties properties() {
        return properties;
    }

    /** Admitted and refused counts since startup, for the metrics endpoint. */
    public Map<String, Long> counters() {
        return Map.of("requests.admitted", admitted.get(), "requests.refused", refused.get());
    }

    /** Frees the per-graph state of a graph that is no longer served (a re-authored schema). */
    public void forget(String graph) {
        perGraphSlots.remove(graph);
        perGraphRate.remove(graph);
    }

    private static final class Window {
        long second = -1;
        int count;
    }
}
