package com.pengcheng.phoenixgraph.http.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * What one instance will spend on requests, so a slow or hostile caller cannot take it down.
 *
 * <p>Every service says it is "stateless and scales horizontally", but a single instance still has a fixed
 * number of threads, connections and bytes of heap. Without these numbers the first expensive query
 * degrades everybody, and the operator has no knob to trade throughput for fairness. They are deliberately
 * conservative defaults: a demo instance is far below them, and a deployment that needs more says so in its
 * own configuration file.
 *
 * @param enabled               whether the gate runs at all (a benchmark wants it off)
 * @param maxConcurrent         requests one instance serves at the same time; beyond this a request waits
 * @param queueWait             how long a request may wait for a slot before it is refused
 * @param perGraphConcurrent    requests in flight for one graph (a single hot graph cannot eat the instance)
 * @param perGraphPerSecond     requests per second accepted per graph (0 switches the rate limit off)
 * @param requestTimeout        wall clock a request may run before the query engine is told to stop
 * @param retryAfterSeconds     what the 429/503 answer tells the client to wait
 * @param maxBodyBytes          largest request body accepted; a bigger one is a 413 before any work happens
 * @param maxQueryLength        largest query text accepted, in characters; a bigger one is a 400
 */
@ConfigurationProperties(prefix = "phoenixgraph.limits")
public record LimitsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("64") int maxConcurrent,
        @DefaultValue("2s") Duration queueWait,
        @DefaultValue("8") int perGraphConcurrent,
        @DefaultValue("200") int perGraphPerSecond,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("1") int retryAfterSeconds,
        @DefaultValue("1048576") int maxBodyBytes,
        @DefaultValue("65536") int maxQueryLength) {

    public LimitsProperties {
        if (maxConcurrent < 1) throw new IllegalArgumentException("phoenixgraph.limits.max-concurrent must be >= 1");
        if (perGraphConcurrent < 1) throw new IllegalArgumentException("phoenixgraph.limits.per-graph-concurrent must be >= 1");
        if (perGraphPerSecond < 0) throw new IllegalArgumentException("phoenixgraph.limits.per-graph-per-second must be >= 0");
        if (maxBodyBytes < 1024) throw new IllegalArgumentException("phoenixgraph.limits.max-body-bytes must be >= 1024");
        if (maxQueryLength < 256) throw new IllegalArgumentException("phoenixgraph.limits.max-query-length must be >= 256");
    }
}
