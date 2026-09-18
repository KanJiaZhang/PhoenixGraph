package com.pengcheng.phoenixgraph.metrics;

/**
 * The names of the system metrics a graph collects.
 *
 * <p>Counters come in pairs: an <em>operation</em> counter counts calls
 * ({@code storage.vertex.read}) while an <em>items</em> counter counts the
 * vertices/edges those calls moved ({@code storage.vertex.read.items}). Latency
 * metrics are histograms, everything else is a monotonic counter.
 *
 * <p>A name may carry a tag in braces — {@code query.count{language=gremlin,source=ui}}
 * — so the same metric can be sliced without changing the storage layout.
 */
public final class Metrics {

    // ---------------------------------------------------------------- storage reads
    /** Vertex read calls (one per point lookup or batch read against the store). */
    public static final String VERTEX_READ = "storage.vertex.read";
    /** Vertices returned by those calls. */
    public static final String VERTEX_READ_ITEMS = "storage.vertex.read.items";
    /** Vertex reads that failed (after retries). */
    public static final String VERTEX_READ_FAILED = "storage.vertex.read.failed";
    /** Edge read calls (one per adjacency lookup, scan or window read). */
    public static final String EDGE_READ = "storage.edge.read";
    /** Edges returned by those calls. */
    public static final String EDGE_READ_ITEMS = "storage.edge.read.items";
    /** Edge reads that failed (after retries). */
    public static final String EDGE_READ_FAILED = "storage.edge.read.failed";

    // --------------------------------------------------------------- storage writes
    /** Vertex write calls (insert or update). */
    public static final String VERTEX_WRITE = "storage.vertex.write";
    /** Vertices written by those calls. */
    public static final String VERTEX_WRITE_ITEMS = "storage.vertex.write.items";
    /** Vertex writes that failed (after retries). */
    public static final String VERTEX_WRITE_FAILED = "storage.vertex.write.failed";
    /** Vertex property updates rejected because the incoming version was stale. */
    public static final String VERTEX_WRITE_STALE = "storage.vertex.write.stale";
    /** Edge write calls (insert or property update). */
    public static final String EDGE_WRITE = "storage.edge.write";
    /** Edges written by those calls. */
    public static final String EDGE_WRITE_ITEMS = "storage.edge.write.items";
    /** Edge writes that failed (after retries). */
    public static final String EDGE_WRITE_FAILED = "storage.edge.write.failed";
    /** Edge property updates rejected because the incoming version was stale. */
    public static final String EDGE_WRITE_STALE = "storage.edge.write.stale";
    /** Edges dropped because a vertex reached its declared degree limit. */
    public static final String EDGE_EVICTED = "storage.edge.evicted";
    /** Write attempts that had to be retried (CAS/generation conflict or transient error). */
    public static final String WRITE_RETRY = "storage.write.retry";
    /** Counters removed from a vertex because an edge was deleted. */
    public static final String VERTEX_DELETE = "storage.vertex.delete";
    /** Edges removed. */
    public static final String EDGE_DELETE = "storage.edge.delete";

    // ------------------------------------------------------------------- key values
    /** Round trips to the store for reads (one per call, batch or single). */
    public static final String KV_READ_CALL = "storage.kv.read.call";
    /** Keys read by those round trips (a batch of 20 counts 20). */
    public static final String KV_READ_ITEMS = "storage.kv.read.items";
    /** Round trips to the store for writes. */
    public static final String KV_WRITE_CALL = "storage.kv.write.call";
    /** Keys written by those round trips. */
    public static final String KV_WRITE_ITEMS = "storage.kv.write.items";
    /** Read round trips that threw (after retries). */
    public static final String KV_READ_FAILED = "storage.kv.read.failed";
    /** Write round trips that threw (after retries). */
    public static final String KV_WRITE_FAILED = "storage.kv.write.failed";
    /** Edges read because a page of a scan or count was needed. */
    public static final String KV_SCAN_RECORDS = "storage.kv.scan.records";

    /** Geo cell reads of a RocksDB geo index. */
    public static final String GEO_READ = "storage.geo.read";
    /** Geo cell writes of a RocksDB geo index. */
    public static final String GEO_WRITE = "storage.geo.write";

    // ----------------------------------------------------------------------- queries
    /** Graph queries executed, tagged {@code {language=…,source=…}}. */
    public static final String QUERY_COUNT = "query.count";
    /** Graph queries that ended in an exception, tagged like {@link #QUERY_COUNT}. */
    public static final String QUERY_FAILED = "query.failed";
    /** Elements (vertices and edges) returned by graph queries. */
    public static final String QUERY_ITEMS = "query.items";
    /** Vertex reads issued while executing graph queries (≈ translated KV queries). */
    public static final String QUERY_VERTEX_READ = "query.storage.vertex.read";
    /** Edge reads issued while executing graph queries. */
    public static final String QUERY_EDGE_READ = "query.storage.edge.read";
    /** Vertex/edge items moved while executing graph queries. */
    public static final String QUERY_STORAGE_ITEMS = "query.storage.items";
    /** Key-value read round trips caused by graph queries. */
    public static final String QUERY_KV_READ = "query.storage.kv.read";
    /** Key-value write round trips caused by graph queries. */
    public static final String QUERY_KV_WRITE = "query.storage.kv.write";

    /** Wall-clock latency of a graph query, in milliseconds (histogram). */
    public static final String QUERY_LATENCY = "query.latency";

    /** Tag used when a counter is not sliced further. */
    public static final String SOURCE_HTTP = "http";
    /** Query source: the browser UI (it sends {@code source=ui}). */
    public static final String SOURCE_UI = "ui";
    /** Query source: an algorithm run over the graph (pagerank and friends). */
    public static final String SOURCE_ALGORITHM = "algorithm";
    /** Query source: an internal caller (tests, loaders, maintenance). */
    public static final String SOURCE_INTERNAL = "internal";

    private Metrics() {
    }

    /** {@code query.count}, tagged with the language and the caller. */
    public static String queryCount(String language, String source) {
        return tagged(QUERY_COUNT, "language", language, "source", source);
    }

    /** {@code query.failed}, tagged with the language and the caller. */
    public static String queryFailed(String language, String source) {
        return tagged(QUERY_FAILED, "language", language, "source", source);
    }

    /** {@code query.latency}, tagged with the language. */
    public static String queryLatency(String language) {
        return tagged(QUERY_LATENCY, "language", language);
    }

    /** The item counter of an operation counter ({@code storage.vertex.read.items}). */
    public static String items(String metric) {
        return metric + ".items";
    }

    /** The failure counter of an operation counter ({@code storage.vertex.read.failed}). */
    public static String failed(String metric) {
        return metric + ".failed";
    }

    /** Builds {@code name{k=v,k=v}}; a null or blank value is skipped. */
    public static String tagged(String name, String... keysAndValues) {
        StringBuilder out = new StringBuilder(name).append('{');
        boolean first = true;
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            String key = keysAndValues[i];
            String value = keysAndValues[i + 1];
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            out.append(key).append('=').append(value);
            first = false;
        }
        if (first) {
            return name;
        }
        return out.append('}').toString();
    }

    /** The bare name of a tagged metric ({@code query.count} for {@code query.count{a=b}}). */
    public static String baseName(String metric) {
        int brace = metric.indexOf('{');
        return brace < 0 ? metric : metric.substring(0, brace);
    }

    /** Whether the metric is a latency histogram rather than a counter. */
    public static boolean isLatency(String metric) {
        return baseName(metric).endsWith("latency");
    }
}
