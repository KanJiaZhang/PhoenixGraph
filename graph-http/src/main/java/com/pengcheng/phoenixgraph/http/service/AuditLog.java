package com.pengcheng.phoenixgraph.http.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Who did what: one entry per API call, and the last few thousand of them kept in memory for the audit view.
 *
 * <p>"Who queried what" is the question an audit has to answer, and a log line alone answers it only if
 * somebody kept the log. So the entry goes to two places: a dedicated logger
 * ({@code com.pengcheng.phoenixgraph.audit}, which a deployment routes to its own file or collector) and a
 * bounded ring buffer, so an operator can ask the running instance for the last N calls
 * ({@code GET /api/v1/audit/recent}) without shipping logs anywhere.
 *
 * <p>Bounded on purpose: an audit buffer that grows with traffic is a memory leak with a compliance story.
 * The buffer is a convenience view, the log is the record.
 *
 * <p>What is recorded: the key's *name*, never its token; the method and path (a query string is not part of
 * the path and is not stored); the graph; the query text, clipped, because a query is the thing an analyst
 * asks about and it is also the thing that could carry a secret - which is why the clip is short and
 * documented.
 */
@Component
public class AuditLog {

    /** The logger a deployment routes wherever its audit trail has to live. */
    public static final String AUDIT_LOGGER = "com.pengcheng.phoenixgraph.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);
    private static final int CAPACITY = 2000;
    private static final int QUERY_CLIP = 200;

    /** One call. */
    public record Entry(Instant at, String key, String role, String method, String path, String graph,
                        String query, int status, long millis) {
    }

    private final Deque<Entry> recent = new ArrayDeque<>(CAPACITY);

    /** Records a call (also logged, so the trail survives a restart). */
    public void record(String key, String role, String method, String path, String graph, String query,
                       int status, long millis) {
        String clipped = clip(query);
        Entry entry = new Entry(Instant.now(), key, role, method, path, graph, clipped, status, millis);
        synchronized (recent) {
            if (recent.size() >= CAPACITY) {
                recent.removeFirst();
            }
            recent.addLast(entry);
        }
        AUDIT.info("key={} role={} {} {} graph={} status={} in {}ms query={}", key, role, method, path,
                graph == null ? "-" : graph, status, millis, clipped);
    }

    /** The most recent entries, newest first. */
    public List<Entry> recent(int limit) {
        synchronized (recent) {
            List<Entry> all = new ArrayList<>(recent);
            int size = Math.max(0, Math.min(limit, all.size()));
            List<Entry> newestFirst = new ArrayList<>(size);
            for (int i = all.size() - 1; i >= all.size() - size; i -= 1) {
                newestFirst.add(all.get(i));
            }
            return List.copyOf(newestFirst);
        }
    }

    /** How many entries the buffer currently holds. */
    public int size() {
        synchronized (recent) {
            return recent.size();
        }
    }

    /** The audit view: newest first, with a count and what "recent" means. */
    public Map<String, Object> view(int limit) {
        List<Entry> entries = recent(limit);
        return Map.of(
                "entries", entries.stream().map((entry) -> Map.<String, Object>of(
                        "at", entry.at().toString(),
                        "key", entry.key(),
                        "role", entry.role(),
                        "method", entry.method(),
                        "path", entry.path(),
                        "graph", entry.graph() == null ? "-" : entry.graph(),
                        "query", entry.query(),
                        "status", entry.status(),
                        "millis", entry.millis())).toList(),
                "returned", entries.size(),
                "buffered", size(),
                "capacity", CAPACITY,
                "note", "the durable record is the '" + AUDIT_LOGGER + "' logger; this buffer is a view of it");
    }

    private static String clip(String query) {
        if (query == null || query.isBlank()) return "-";
        return query.length() <= QUERY_CLIP ? query : query.substring(0, QUERY_CLIP) + "…";
    }
}
