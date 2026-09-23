package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.ScanPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Benchmark of the query a caller writes when it wants the payment network around an account:
 *
 * <pre>{@code
 * g.V($id,'Account').both('Payment').both('Payment').values('accountUsage', 'firstName', 'accountStatus')
 * }</pre>
 *
 * <p>A hundred account ids - the ones the demo data is built around ({@code 135}, {@code 1001}, {@code 2017},
 * the ring, the rest of the {@code http_demo_v3} accounts) - each run several times, so the measured workload
 * is several hundred executions of one query, which is what a dashboard or a fraud screen does rather than
 * a single timed query. The report is the distribution (mean/p50/p90/p95/p99/max), the throughput, and the
 * storage traffic each execution caused: round trips, records, vertices, edges and the property bins that
 * were actually read.
 *
 * <p>The three questions the report answers are asked as tests, so a regression fails the build instead of
 * showing up in a print-out nobody reads:
 *
 * <ol>
 *   <li><b>Is the cache doing anything?</b> The same ids are measured with the per-query cache on and off
 *       and the reads the cache saved are counted. The cache is scoped to one execution - a second
 *       execution re-reads, which is exactly why a write between two queries cannot be missed.</li>
 *   <li><b>Do batch and barrier sizes adapt?</b> Every storage read is recorded with the number of ids it
 *       carried, so the report shows the batch size each hop used next to what the adaptive rule
 *       ({@code fanOut / 2}, clamped to the maximum) computes, and a sweep over {@code batch(1..200)} shows
 *       where the latency stops improving.</li>
 *   <li><b>Are properties that nobody asked for loaded?</b> The recorded reads carry the property names the
 *       plan asked for, so the report lists them per hop and compares the projected query with the same
 *       query that ends without {@code values()} - the one that has to materialise whole vertices.</li>
 * </ol>
 *
 * <p>Live test: needs the Aerospike container and the demo data. Skips itself otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AerospikeHundredSeedsValuesBenchmarkTest {

    /** The projection the measured query ends with. */
    private static final List<String> WANTED = List.of("accountUsage", "firstName", "accountStatus");

    /** How many account ids the workload runs over. */
    private static final int SEEDS = 100;

    /** How many times each id runs: 100 x 8 = 800 executions, inside the 500..1000 window. */
    private static final int ROUNDS = 8;

    /** Executions before measuring (JIT, connection warm-up, the compiled-query cache). */
    private static final int WARMUP = 5;

    /** The ids the demo data is built around, tried before the rest of the set. */
    private static final List<String> NAMED = List.of("135", "1001", "2017", "9000001", "150", "11");

    /** The counters the report prints, in the order a reader wants them. */
    private static final List<String> TRAFFIC = List.of(
            Metrics.KV_READ_CALL, Metrics.KV_READ_ITEMS, Metrics.VERTEX_READ, Metrics.VERTEX_READ_ITEMS,
            Metrics.EDGE_READ, Metrics.EDGE_READ_ITEMS, Metrics.EDGE_DECODED, Metrics.EDGE_FILTERED);

    private static final ObjectMapper JSON = new ObjectMapper();

    private String namespace;
    private AerospikeClient client;
    private String set;
    private GraphSchema schema;
    private GraphMetrics metrics;
    private GraphQuery store;
    private GremlinGraphQuery engine;

    private List<String> seeds = List.of();
    private final List<Execution> executions = new ArrayList<>();
    /** The storage reads of the current execution, in order (only while {@link #recording}). */
    private final List<Read> reads = new ArrayList<>();
    private boolean recording;

    private CacheReport cacheReport;
    private BatchReport batchReport;
    private List<String> sweep = List.of();
    private ProjectionReport projectionReport;

    /** What a store read answers, taken from its declared return type rather than from its name. */
    private enum Kind {
        /** Vertex records: the reads a property projection is about. */
        VERTEX,
        /** Edge records, or the neighbour references of one edge class. */
        EDGE,
        /** A count. */
        COUNT,
        /** Something else a store exposes (geo hits). */
        OTHER
    }

    /** One storage read: what it answers, how many ids it carried, which properties, how much came back. */
    private record Read(String op, Kind kind, int ids, List<String> properties, int returned) {
        /** Whether this read materialises vertices (the ones the projection is about). */
        boolean isVertexRead() {
            return kind == Kind.VERTEX;
        }
    }

    /**
     * How one store method is read: where its ids and its property names sit, what it answers, and whether
     * it is traffic at all. Every part comes from the <b>declared types</b>, because neither the values nor
     * the name can be trusted: the account ids of this graph are strings exactly like property names, and
     * {@code readVertices} does not start with {@code readVertex} (they differ at the ninth letter).
     */
    private record Shape(int idsIndex, int propertiesIndex, Kind kind, boolean nested, boolean traffic) {
    }

    /** One shape per store method, worked out once. */
    private final Map<Method, Shape> shapes = new LinkedHashMap<>();

    private Shape shapeOf(Method method) {
        Shape known = shapes.get(method);
        if (known != null) {
            return known;
        }
        Class<?> element = elementType(method.getGenericReturnType());
        Kind kind = switch (element == null ? "" : element.getSimpleName()) {
            case "VertexQueryResult" -> Kind.VERTEX;
            case "EdgeQueryResult", "NeighbourRef", "ScannedEdge" -> Kind.EDGE;
            case "Long" -> Kind.COUNT;
            default -> Kind.OTHER;
        };
        Class<?> returns = method.getReturnType();
        // traffic is anything that answers records or a count: a list, a single record, a number. A
        // capability probe answers a boolean, so it is not traffic and is not recorded.
        boolean traffic = List.class.isAssignableFrom(returns) || returns == long.class || returns == Long.class
                || kind != Kind.OTHER;
        int ids = -1;
        int properties = -1;
        for (int i = 0; i < method.getGenericParameterTypes().length; i++) {
            Type parameter = method.getGenericParameterTypes()[i];
            if (parameter == String[].class) {
                properties = i; // the varargs form of a property list
                continue;
            }
            if (!(parameter instanceof ParameterizedType list) || list.getRawType() != List.class) {
                continue;
            }
            Type argument = list.getActualTypeArguments()[0];
            if (argument == Object.class) {
                ids = i; // List<Object> is the vertices to read
            } else if (argument == String.class) {
                properties = i; // List<String> is the property names
            }
        }
        Shape shape = new Shape(ids, properties, kind, isNested(method.getGenericReturnType()), traffic);
        shapes.put(method, shape);
        return shape;
    }

    /** The innermost element of a (possibly nested) {@code List} type. */
    private static Class<?> elementType(Type type) {
        Type current = type;
        while (current instanceof ParameterizedType list && list.getRawType() == List.class) {
            current = list.getActualTypeArguments()[0];
        }
        return current instanceof Class<?> element ? element : null;
    }

    /** Whether the read answers one list per id ({@code List<List<X>>}) rather than a flat list. */
    private static boolean isNested(Type type) {
        return type instanceof ParameterizedType outer && outer.getRawType() == List.class
                && outer.getActualTypeArguments()[0] instanceof ParameterizedType inner
                && inner.getRawType() == List.class;
    }

    /** One measured execution: the seed, the wall time, the bytes the caller got, the storage traffic. */
    private record Execution(String seed, long nanos, int bytes, int items, Map<String, Long> traffic) {
    }

    private record CacheReport(int ids, long cachedCalls, long plainCalls, long cachedNanos, long plainNanos,
                              int cachedItems, int plainItems, String hub, long hubCachedCalls,
                              long hubPlainCalls) {
    }

    private record BatchReport(String hub, Map<String, List<Read>> readsBySeed) {
    }

    private record ProjectionReport(String seed, List<Read> projected, int itemsProjected, int bytesProjected,
                                    List<Read> whole, int itemsWhole, int bytesWhole, int declaredAccount,
                                    Map<String, List<String>> declared) {
    }

    @BeforeAll
    void openTheDemoGraph() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        namespace = config.namespace();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        set = System.getProperty("payment.vertexSet",
                System.getenv().getOrDefault("PAYMENT_VERTEX_SET", "http_demo_v3"));
        schema = loadSchema();
        metrics = new GraphMetrics("payment-graph-two-hop-values-benchmark");
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set).metrics(metrics);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema).metrics(metrics);
        store = recording(new AerospikeGraphQuery(vertexStore, edgeStore, schema));
        engine = new GremlinGraphQuery(store, NeighborVertexResolver.from(schema));
    }

    @AfterAll
    void printTheReport() {
        if (!executions.isEmpty()) {
            System.out.println(report());
        }
        if (client != null) {
            client.close();
        }
    }

    // ------------------------------------------------------------------ the workload

    @Test
    @Order(1)
    void aHundredAccountsRunTheTwoHopValuesQueryEightTimesEach() {
        List<String> ids = accountIds();
        assertEquals(SEEDS, ids.size(), "a hundred valid account ids: " + ids.size());

        for (int i = 0; i < WARMUP; i++) {
            engine.execute(query(ids.get(i % ids.size())));
        }
        executions.clear();
        for (int round = 0; round < ROUNDS; round++) {
            for (String id : ids) {
                executions.add(measure(id, engine));
            }
        }

        assertEquals(SEEDS * ROUNDS, executions.size(), "the whole workload ran");
        assertEquals(SEEDS * ROUNDS >= 500 && SEEDS * ROUNDS <= 1000, true,
                "the workload is inside the 500..1000 window: " + executions.size());
        System.out.println("executions: " + executions.size() + " (" + SEEDS + " ids x " + ROUNDS + " rounds)");
    }

    /** The same ids with the per-query cache off, to count what the cache saves. */
    @Test
    @Order(2)
    void theCacheSavesReadsInsideOneExecution() {
        List<String> ids = accountIds();
        GremlinGraphQuery uncached = new GremlinGraphQuery(store, NeighborVertexResolver.from(schema))
                .cacheEnabled(false);

        long cachedCalls = 0;
        long plainCalls = 0;
        long cachedNanos = 0;
        long plainNanos = 0;
        int cachedItems = 0;
        int plainItems = 0;
        for (String id : ids) {
            Execution cached = measure(id, engine);
            Execution plain = measure(id, uncached);
            assertTrue(equalItems(cached, plain), id + ": the cache does not change the answer");
            cachedCalls += cached.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
            plainCalls += plain.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
            cachedItems += cached.items();
            plainItems += plain.items();
            cachedNanos += cached.nanos();
            plainNanos += plain.nanos();
        }
        String hubId = hubId();
        long hubCachedCalls = measure(hubId, engine).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
        long hubPlainCalls = measure(hubId, uncached).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
        cacheReport = new CacheReport(ids.size(), cachedCalls, plainCalls, cachedNanos, plainNanos,
                cachedItems, plainItems, hubId, hubCachedCalls, hubPlainCalls);
        System.out.printf(Locale.ROOT,
                "cache: %d round trips with it, %d without (%.0f%% saved), %.1f ms vs %.1f ms per id%n",
                cachedCalls, plainCalls, 100.0 * (plainCalls - cachedCalls) / Math.max(1, plainCalls),
                cachedNanos / 1e6 / ids.size(), plainNanos / 1e6 / ids.size());
    }

    /** The seed whose fan-out is largest: the one place the cache and the batch size really work. */
    private String hubId() {
        String hub = accountIds().contains("135") ? "135" : accountIds().get(0);
        long largest = -1;
        for (String id : accountIds()) {
            Map<String, Long> traffic = measure(id, engine).traffic();
            long records = traffic.getOrDefault(Metrics.KV_READ_ITEMS, 0L);
            if (records > largest) {
                largest = records;
                hub = id;
            }
        }
        return hub;
    }

    private boolean equalItems(Execution first, Execution second) {
        return first.items() == second.items();
    }

    /**
     * A second execution re-reads instead of reusing an answer: that is the cache's scope, and the reason a
     * write between two queries cannot be answered from a stale cache.
     */
    @Test
    @Order(3)
    void theCacheDoesNotSurviveTheExecution() {
        String id = accountIds().get(0);
        long first = measure(id, engine).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
        long second = measure(id, engine).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
        assertEquals(first, second, "a second execution issues the same reads: " + first + " vs " + second);
        assertTrue(first > 0, "the query reads something at all: " + first);
    }

    /** The batch size every hop used, one execution per seed. */
    @Test
    @Order(4)
    void theBatchSizeFollowsTheFanOut() {
        Map<String, List<Read>> bySeed = new LinkedHashMap<>();
        for (String id : accountIds()) {
            measure(id, engine);
            bySeed.put(id, List.copyOf(reads));
        }
        String hub = accountIds().contains("135") ? "135" : accountIds().get(0);
        batchReport = new BatchReport(hub, bySeed);
        assertFalse(bySeed.isEmpty(), "every seed was recorded");
    }

    /** Where the latency stops improving: an explicit batch/barrier of every size on the same ids. */
    @Test
    @Order(5)
    void theSweepShowsWhereBatchingStopsPaying() {
        List<String> ids = accountIds().subList(0, Math.min(20, accountIds().size()));
        List<String> sizes = List.of("auto", "barrier(1)", "batch(1)", "batch(10)", "batch(25)", "batch(50)",
                "batch(100)", "batch(200)");
        List<String> lines = new ArrayList<>();
        for (String size : sizes) {
            GremlinGraphQuery variant = new GremlinGraphQuery(store, NeighborVertexResolver.from(schema));
            boolean auto = size.equals("auto");
            for (int warm = 0; warm < 2; warm++) {
                for (String id : ids) {
                    variant.execute(auto ? query(id) : queryWith(id, size));
                }
            }
            long calls = 0;
            long nanos = 0;
            int items = 0;
            for (String id : ids) {
                reads.clear();
                recording = true;
                long started = System.nanoTime();
                List<Object> result = variant.execute(auto ? query(id) : queryWith(id, size));
                nanos += System.nanoTime() - started;
                recording = false;
                calls += reads.size();
                items += result.size();
            }
            lines.add(String.format(Locale.ROOT, "  %-11s %7.2f ms/query %8.2f reads/query %6.1f items/query",
                    size, nanos / 1e6 / ids.size(), (double) calls / ids.size(), (double) items / ids.size()));
        }
        sweep = lines;
        assertFalse(sweep.isEmpty(), "the sweep ran");
    }

    /**
     * The projection, read off the storage calls: which properties each hop asked for, and what the query
     * that has to materialise whole vertices costs next to it.
     */
    @Test
    @Order(6)
    void onlyTheRequestedPropertiesAreRead() {
        String id = accountIds().contains("135") ? "135" : accountIds().get(0);

        List<Read> projectedReads = new ArrayList<>();
        List<Object> projected = run(engine, query(id), projectedReads);
        List<Read> wholeReads = new ArrayList<>();
        GremlinGraphQuery whole = new GremlinGraphQuery(store, NeighborVertexResolver.from(schema));
        List<Object> everything = run(whole,
                "g.V('" + id + "','Account').both('Payment').both('Payment')", wholeReads);

        projectionReport = new ProjectionReport(id, projectedReads, projected.size(), bytesOf(projected),
                wholeReads, everything.size(), bytesOf(everything), declaredAccountProperties(),
                declaredProperties());
        assertTrue(projectedReads.stream().filter(Read::isVertexRead)
                        .allMatch(read -> WANTED.containsAll(read.properties())),
                "a vertex read asks for the requested properties or for none at all: " + projectedReads);
        List<Read> notVertex = projectedReads.stream().filter(read -> !read.isVertexRead()).toList();
        assertTrue(notVertex.stream().allMatch(read -> read.properties().isEmpty()),
                "no read that walks edges asks for properties: " + notVertex);
        assertFalse(projected.isEmpty(), "the query answers something for " + id);
    }

    /** Runs a query with the reads recorded into {@code into}. */
    private List<Object> run(GremlinGraphQuery query, String gremlin, List<Read> into) {
        reads.clear();
        recording = true;
        List<Object> result = query.execute(gremlin);
        recording = false;
        into.addAll(reads);
        return result;
    }

    private int declaredAccountProperties() {
        return schema.vertexSchemas().stream()
                .filter(vertex -> vertex.name().equals("Account"))
                .findFirst()
                .map(vertex -> vertex.properties().size())
                .orElse(0);
    }

    /** The declared properties of the graph, so the report can name what stayed unread. */
    private Map<String, List<String>> declaredProperties() {
        Map<String, List<String>> byType = new TreeMap<>();
        schema.vertexSchemas().forEach(vertex -> byType.computeIfAbsent("vertex " + vertex.name(),
                        key -> new ArrayList<>())
                .addAll(vertex.properties().stream().map(property -> property.name()).toList()));
        schema.edgeSchemas().forEach(edge -> byType.computeIfAbsent("edge " + edge.name(),
                        key -> new ArrayList<>())
                .addAll(edge.properties().stream().map(property -> property.name()).toList()));
        return byType;
    }

    // ------------------------------------------------------------------ measuring

    private Execution measure(String id, GremlinGraphQuery query) {
        Map<String, Long> before = counters();
        reads.clear();
        recording = true;
        long started = System.nanoTime();
        List<Object> result = query.execute(this.query(id));
        long nanos = System.nanoTime() - started;
        recording = false;
        Map<String, Long> after = counters();

        Map<String, Long> traffic = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
            if (delta != 0) {
                traffic.put(entry.getKey(), delta);
            }
        }
        return new Execution(id, nanos, bytesOf(result), result.size(), traffic);
    }

    private int bytesOf(List<Object> result) {
        try {
            return JSON.writeValueAsBytes(result).length;
        } catch (IOException e) {
            return -1; // a result holding vertices rather than values is not the measured shape
        }
    }

    private String query(String id) {
        return "g.V('" + id + "','Account').both('Payment').both('Payment').values(" + values() + ")";
    }

    private String queryWith(String id, String between) {
        return "g.V('" + id + "','Account').both('Payment')." + between + ".both('Payment').values("
                + values() + ")";
    }

    private static String values() {
        return WANTED.stream().map(name -> "'" + name + "'").reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    private Map<String, Long> counters() {
        MetricsSnapshot snapshot = metrics.snapshot();
        return new TreeMap<>(snapshot.counters());
    }

    /** Wraps the store so every read is recorded and still answered by the real store. */
    private GraphQuery recording(GraphQuery delegate) {
        return (GraphQuery) Proxy.newProxyInstance(GraphQuery.class.getClassLoader(),
                new Class<?>[] {GraphQuery.class}, (proxy, method, args) -> {
                    Shape shape = shapeOf(method);
                    Object value;
                    try {
                        value = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    // a capability probe returns a boolean, so the declared return type already says
                    // whether the call is traffic: no name list to keep up to date
                    if (recording && shape.traffic()) {
                        reads.add(read(method, shape, args, value));
                    }
                    return value;
                });
    }

    private static Read read(Method method, Shape shape, Object[] args, Object value) {
        int ids = shape.idsIndex() < 0 || shape.idsIndex() >= args.length
                ? 1
                : Math.max(1, ((List<?>) args[shape.idsIndex()]).size());
        Object names = shape.propertiesIndex() < 0 || shape.propertiesIndex() >= args.length
                ? null
                : args[shape.propertiesIndex()];
        List<String> properties = switch (names) {
            case List<?> list -> list.stream().map(String::valueOf).toList();
            case String[] array -> List.of(array);
            case null, default -> List.of();
        };
        return new Read(method.getName(), shape.kind(), ids, properties, returned(shape, value));
    }

    /**
     * How many records came back. A batched read answers one list per id, so what was found is the elements
     * of those inner lists - {@code list.size()} there is the number of ids asked for, not the answer.
     */
    private static int returned(Shape shape, Object value) {
        if (value == null) {
            return 0;
        }
        if (!(value instanceof List<?> list)) {
            return 1;
        }
        int found = 0;
        for (Object element : list) {
            if (element == null) {
                continue; // a key that is absent, or whose requested bins are all missing
            }
            if (shape.nested() && element instanceof List<?> inner) {
                found += (int) inner.stream().filter(Objects::nonNull).count();
            } else {
                found++;
            }
        }
        return found;
    }

    /**
     * The recorder reads every store method the same way the engine does: from the declared types. This test
     * is the guard that keeps that true - a new store method with two lists of the same element type, or a
     * read whose answer cannot be classified, fails here instead of quietly producing wrong numbers.
     */
    @Test
    @Order(7)
    void everyStoreReadIsClassifiedFromItsDeclaredTypes() {
        int traffic = 0;
        int vertexReads = 0;
        for (Method method : GraphQuery.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Shape shape = shapeOf(method);
            if (!shape.traffic()) {
                assertTrue(!List.class.isAssignableFrom(method.getReturnType()),
                        method.getName() + " answers a list, so it is traffic");
                continue;
            }
            traffic++;
            if (shape.kind() == Kind.VERTEX) {
                vertexReads++;
            }
            // the two list parameters must be told apart by their element type, not by their position
            assertEquals(listParameters(method, Object.class) <= 1, true,
                    method.getName() + " declares at most one List<Object> (the ids to read)");
            assertEquals(listParameters(method, String.class) <= 1, true,
                    method.getName() + " declares at most one List<String> (the property names)");
        }
        assertTrue(traffic >= 20, "the store exposes reads to record: " + traffic);
        assertTrue(vertexReads >= 5, "reads that answer vertices: " + vertexReads);
        // the classification of the reads this benchmark actually depends on
        assertEquals(Kind.VERTEX, shapeOf(method("readVertices", String.class, List.class, List.class)).kind(),
                "readVertices answers vertices");
        assertEquals(Kind.EDGE, shapeOf(method("readNeighboursBatch", String.class, EdgeSide.class, List.class))
                .kind(), "readNeighboursBatch answers neighbour references");
        assertEquals(1, shapeOf(method("readVertices", String.class, List.class, List.class)).idsIndex(),
                "readVertices takes its ids second");
        assertEquals(2, shapeOf(method("readVertices", String.class, List.class, List.class)).propertiesIndex(),
                "readVertices takes its property names third");
        assertEquals(3, shapeOf(method("readEdgesBatchWithin", String.class, EdgeSide.class, TimeRange.class,
                        List.class, List.class, int.class)).idsIndex(),
                "readEdgesBatchWithin takes its ids fourth, after the time range");
        assertEquals(4, shapeOf(method("readEdgesBatchWithin", String.class, EdgeSide.class, TimeRange.class,
                        List.class, List.class, int.class)).propertiesIndex(),
                "readEdgesBatchWithin takes its property names fifth");
        assertTrue(shapeOf(method("readEdgesBatchWithin", String.class, EdgeSide.class, TimeRange.class,
                        List.class, List.class, int.class)).nested(),
                "readEdgesBatchWithin answers one list per id");
    }

    /** How many parameters of a method are a {@code List} of the given element type. */
    private static int listParameters(Method method, Class<?> element) {
        int found = 0;
        for (Type parameter : method.getGenericParameterTypes()) {
            if (parameter instanceof ParameterizedType list && list.getRawType() == List.class
                    && list.getActualTypeArguments()[0] == element) {
                found++;
            }
        }
        return found;
    }

    private static Method method(String name, Class<?>... parameters) {
        try {
            return GraphQuery.class.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(name + " is gone from GraphQuery", e);
        }
    }

    /**
     * The recorded hits and the storage counters are two independent measurements of the same reads, so they
     * have to agree: the store counts a vertex record once per key it found, and the recorder counts the
     * elements that came back. When they disagree, one of the two is lying about what a read returned.
     */
    @Test
    @Order(8)
    void theRecordedHitsAgreeWithTheStorageCounters() {
        String id = accountIds().get(0);
        GremlinGraphQuery whole = new GremlinGraphQuery(store, NeighborVertexResolver.from(schema));

        List<Read> readsOfQuery = new ArrayList<>();
        run(whole, "g.V('" + id + "','Account').both('Payment').both('Payment')", readsOfQuery);
        Map<String, Long> before = counters();
        reads.clear();
        recording = true;
        whole.execute("g.V('" + id + "','Account').both('Payment').both('Payment')");
        recording = false;
        Map<String, Long> after = counters();
        long counted = after.getOrDefault(Metrics.VERTEX_READ_ITEMS, 0L)
                - before.getOrDefault(Metrics.VERTEX_READ_ITEMS, 0L);
        long recorded = reads.stream().filter(Read::isVertexRead).mapToLong(Read::returned).sum();
        assertEquals(counted, recorded,
                "the store counted " + counted + " vertex records, the recorder saw " + recorded);

        // with a projection the two can differ: a record that has none of the requested bins comes back as a
        // miss although the store counted the key as read, so only the one direction holds
        reads.clear();
        recording = true;
        before = counters();
        engine.execute(query(id));
        recording = false;
        after = counters();
        long countedProjected = after.getOrDefault(Metrics.VERTEX_READ_ITEMS, 0L)
                - before.getOrDefault(Metrics.VERTEX_READ_ITEMS, 0L);
        long recordedProjected = reads.stream().filter(Read::isVertexRead).mapToLong(Read::returned).sum();
        assertTrue(recordedProjected <= countedProjected,
                "a projection cannot answer more records than the store read: " + recordedProjected
                        + " vs " + countedProjected);
        System.out.printf(Locale.ROOT,
                "counters: %d vertex records read (whole), %d (projected), recorder saw %d and %d%n",
                counted, countedProjected, recorded, recordedProjected);
    }

    // ------------------------------------------------------------------ the ids

    /** The ids of the account vertices the demo set holds, the named ones first. */
    private List<String> accountIds() {
        if (!seeds.isEmpty()) {
            return seeds;
        }
        // A scan hands records back in whatever order the server pleases, so the ids are sorted: the
        // measured workload has to be the same one run to run, or two reports cannot be compared.
        LinkedHashSet<String> candidates = new LinkedHashSet<>(NAMED);
        TreeSet<String> scanned = new TreeSet<>();
        client.scanAll(new ScanPolicy(), namespace, set, (key, record) -> {
            if (key != null && key.userKey != null && record != null
                    && "Account".equals(record.getString(AerospikeVertexStore.VERTEX_TYPE_BIN))) {
                scanned.add(String.valueOf(key.userKey));
            }
        }, AerospikeVertexStore.VERTEX_TYPE_BIN);
        candidates.addAll(scanned);

        // A valid id is one this query answers something for: the named ids may name an IP, an account
        // without payments, or a vertex of another type.
        List<String> valid = new ArrayList<>();
        int rejected = 0;
        for (String id : candidates) {
            if (valid.size() == SEEDS) {
                break;
            }
            if (engine.execute(query(id)).isEmpty()) {
                rejected++;
                continue;
            }
            valid.add(id);
        }
        assumeTrue(valid.size() >= SEEDS, "the demo data has at least " + SEEDS + " accounts: " + valid.size());
        System.out.printf(Locale.ROOT, "%d account records scanned, %d rejected as empty, %d seeds kept%n",
                candidates.size(), rejected, valid.size());
        seeds = valid;
        return seeds;
    }

    // ------------------------------------------------------------------ the report

    private String report() {
        StringBuilder out = new StringBuilder();
        out.append('\n').append("=".repeat(104)).append('\n');
        out.append("two-hop projection benchmark - ").append(query("<id>")).append('\n');
        out.append("=".repeat(104)).append('\n');

        List<Long> times = executions.stream().map(Execution::nanos).sorted().toList();
        long total = times.stream().mapToLong(Long::longValue).sum();
        out.append(String.format(Locale.ROOT, "%nlatency: %d executions, %d ids x %d rounds%n",
                times.size(), SEEDS, ROUNDS));
        out.append(String.format(Locale.ROOT,
                "  mean %7.2f ms   min %7.2f   p50 %7.2f   p90 %7.2f   p95 %7.2f   p99 %7.2f   max %7.2f%n",
                total / 1e6 / times.size(), at(times, 0.0), at(times, 0.50), at(times, 0.90),
                at(times, 0.95), at(times, 0.99), at(times, 1.0)));
        out.append(String.format(Locale.ROOT,
                "  throughput %.0f queries/s   wall %.2f s   items %.1f/query   response %.1f KB/query%n",
                times.size() / (total / 1e9), total / 1e9,
                executions.stream().mapToInt(Execution::items).average().orElse(0),
                executions.stream().mapToInt(Execution::bytes).filter(bytes -> bytes > 0).average().orElse(0)
                        / 1024.0));

        out.append(String.format(Locale.ROOT, "%nstorage traffic per execution (mean, max, total)%n"));
        for (String metric : TRAFFIC) {
            long sum = executions.stream().mapToLong(e -> e.traffic().getOrDefault(metric, 0L)).sum();
            long max = executions.stream().mapToLong(e -> e.traffic().getOrDefault(metric, 0L)).max().orElse(0);
            out.append(String.format(Locale.ROOT, "  %-24s %10.2f   max %6d   total %8d%n",
                    metric.replace("storage.", ""), (double) sum / executions.size(), max, sum));
        }

        Map<String, Long> nanosBySeed = new TreeMap<>();
        for (Execution execution : executions) {
            nanosBySeed.merge(execution.seed(), execution.nanos(), Long::sum);
        }
        out.append(String.format(Locale.ROOT, "%nper-seed mean latency (ms)%n"));
        nanosBySeed.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed())
                .limit(4)
                .forEach(e -> out.append(String.format(Locale.ROOT, "  slowest %-10s %8.2f%n",
                        e.getKey(), e.getValue() / 1e6 / ROUNDS)));
        nanosBySeed.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(3)
                .forEach(e -> out.append(String.format(Locale.ROOT, "  fastest %-10s %8.2f%n",
                        e.getKey(), e.getValue() / 1e6 / ROUNDS)));

        if (cacheReport != null) {
            out.append(String.format(Locale.ROOT, "%n(1) cache: the per-query vertex/edge/neighbour cache%n"));
            out.append(String.format(Locale.ROOT, "  cache on    %5d round trips  %7.2f ms/id  %6.1f items/id%n",
                    cacheReport.cachedCalls(), cacheReport.cachedNanos() / 1e6 / cacheReport.ids(),
                    (double) cacheReport.cachedItems() / cacheReport.ids()));
            out.append(String.format(Locale.ROOT, "  cache off   %5d round trips  %7.2f ms/id  %6.1f items/id%n",
                    cacheReport.plainCalls(), cacheReport.plainNanos() / 1e6 / cacheReport.ids(),
                    (double) cacheReport.plainItems() / cacheReport.ids()));
            out.append(String.format(Locale.ROOT,
                    "  saved %.1f%% of the round trips (%.2f per query); the cache lives for one execution,%n"
                            + "  so a second execution re-reads and a write between two queries is always seen%n",
                    100.0 * (cacheReport.plainCalls() - cacheReport.cachedCalls())
                            / Math.max(1, cacheReport.plainCalls()),
                    (double) (cacheReport.plainCalls() - cacheReport.cachedCalls()) / cacheReport.ids()));
            out.append(String.format(Locale.ROOT,
                    "  the busiest seed %s asks for %d round trips with the cache and %d without%n",
                    cacheReport.hub(), cacheReport.hubCachedCalls(), cacheReport.hubPlainCalls()));
        }

        if (batchReport != null) {
            out.append(String.format(Locale.ROOT, "%n(2) batch size: what each read carried%n"));
            Map<Integer, Integer> histogram = new TreeMap<>();
            int batched = 0;
            int single = 0;
            int largest = 0;
            for (List<Read> readsOfSeed : batchReport.readsBySeed().values()) {
                for (Read read : readsOfSeed) {
                    if (read.ids() <= 1) {
                        single++;
                        continue;
                    }
                    batched++;
                    largest = Math.max(largest, read.ids());
                    int bucket = read.ids() <= 10 ? 10
                            : read.ids() <= 25 ? 25
                            : read.ids() <= 50 ? 50
                            : read.ids() <= 100 ? 100 : 200;
                    histogram.merge(bucket, 1, Integer::sum);
                }
            }
            out.append(String.format(Locale.ROOT,
                    "  %d seeds: %d reads carrying 2..%d ids, %d reads carrying a single id%n",
                    batchReport.readsBySeed().size(), batched, largest, single));
            int batchedKinds = batched;
            histogram.forEach((bucket, count) -> out.append(String.format(Locale.ROOT,
                    "    ids <= %-4d %5d reads (%.0f%%)%n", bucket, count,
                    100.0 * count / Math.max(1, batchedKinds))));
            out.append(String.format(Locale.ROOT,
                    "  rule: batch = clamp(previous hop's fan-out / 2, 50, 100); an explicit batch(n)/barrier(n)%n"
                            + "  is authoritative and the tuning leaves it alone%n"));
            out.append(String.format(Locale.ROOT,
                    "  widest fan-outs: the first read is the seed's own edges, the second read is the next%n"
                            + "  hop, so its id count shows the batch the tuning chose (never above the 100 cap).%n"
                            + "  The tuning scales from the traverser count, which counts every parallel edge,%n"
                            + "  so it can be larger than the distinct peers listed here%n"));
            batchReport.readsBySeed().entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, List<Read>> entry) -> fanOut(entry.getValue()))
                            .reversed())
                    .limit(5)
                    .forEach(entry -> out.append(String.format(Locale.ROOT,
                            "    seed %-14s hop-1 peers %-5d -> next read carried %-5d ids  %s%n",
                            entry.getKey(), fanOut(entry.getValue()), nextBatch(entry.getValue()),
                            entry.getValue().stream().map(read -> read.op() + "(" + read.ids() + ")")
                                    .reduce((a, b) -> a + " " + b).orElse(""))));
        }

        if (!sweep.isEmpty()) {
            out.append(String.format(Locale.ROOT, "%n(2) explicit batch/barrier sweep (%d ids, 2 warm-up rounds)%n",
                    Math.min(20, SEEDS)));
            sweep.forEach(line -> out.append(line).append('\n'));
        }

        if (projectionReport != null) {
            int declared = projectionReport.declaredAccount();
            out.append(String.format(Locale.ROOT,
                    "%n(3) properties: what the reads asked for (seed %s, Account declares %d properties)%n",
                    projectionReport.seed(), declared));
            out.append(String.format(Locale.ROOT, "%n  the measured query, values(%s)%n", String.join(", ", WANTED)));
            projectionReport.projected().forEach(read -> out.append("    ").append(trace(read)).append('\n'));
            out.append(String.format(Locale.ROOT,
                    "    %d property values read, %d values returned (%.1f KB)%n",
                    propertyValues(projectionReport.projected(), declared), projectionReport.itemsProjected(),
                    projectionReport.bytesProjected() / 1024.0));
            out.append(String.format(Locale.ROOT,
                    "%n  the same query without values(), so the answer is whole vertices%n"));
            projectionReport.whole().forEach(read -> out.append("    ").append(trace(read)).append('\n'));
            out.append(String.format(Locale.ROOT,
                    "    %d property values read, %d vertices returned (%.1f KB)%n",
                    propertyValues(projectionReport.whole(), declared), projectionReport.itemsWhole(),
                    projectionReport.bytesWhole() / 1024.0));
            out.append(String.format(Locale.ROOT,
                    "%n  declared properties of the graph, the ones no read above named%n"));
            projectionReport.declared().forEach((type, properties) -> out.append(String.format(Locale.ROOT,
                    "    %-20s %s%n", type, properties)));
        }
        out.append('\n').append("=".repeat(104));
        return out.toString();
    }

    /** How many property values a set of reads asks the store for (a read with no names reads them all). */
    private static long propertyValues(List<Read> reads, int declared) {
        long values = 0;
        for (Read read : reads) {
            if (read.isVertexRead()) {
                values += (long) read.ids() * (read.properties().isEmpty() ? declared : read.properties().size());
            }
        }
        return values;
    }

    /** One line of the read trace: the operation, the ids it carried, and the properties it named. */
    private static String trace(Read read) {
        String properties = read.properties().isEmpty()
                ? (read.isVertexRead() ? "all declared properties" : "none (identity only)")
                : String.join(", ", read.properties());
        return String.format(Locale.ROOT, "%-22s ids %-5d -> %-5d  %s", read.op(), read.ids(),
                read.returned(), properties);
    }

    /** How many neighbours the seed's own edges reached: the fan-out the tuning scales from. */
    private static long fanOut(List<Read> reads) {
        return reads.isEmpty() ? 0 : reads.get(0).returned();
    }

    /** The id count of the first read after the seed's own edges: the batch size that was chosen. */
    private static int nextBatch(List<Read> reads) {
        return reads.size() < 2 ? 0 : reads.get(1).ids();
    }

    /** A one-line description of one execution's reads: the operation and how many ids it carried. */
    private String describe(List<Read> reads) {
        StringBuilder line = new StringBuilder();
        line.append(reads.size()).append(" reads [");
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(reads.get(i).op()).append('(').append(reads.get(i).ids()).append(')');
        }
        return line.append(']').toString();
    }

    private static double at(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sorted.size() - 1, Math.max(0, Math.round(percentile * (sorted.size() - 1))));
        return sorted.get(index) / 1e6;
    }

    private static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources", "payment-graph.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }
}
