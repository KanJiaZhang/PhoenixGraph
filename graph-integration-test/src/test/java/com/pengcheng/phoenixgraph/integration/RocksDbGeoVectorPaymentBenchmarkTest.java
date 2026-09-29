package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbHnswIndexFactory;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbVectorIndexBuilder;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The combined query the payment demo is for, measured: an account's neighbourhood, the lookalikes of the
 * accounts around it, and what those pay.
 *
 * <pre>{@code
 * g.V(1001,'Account').circle('{"latitude":31.2304,"longitude":121.4737}', 5000).limit(10).nearest(10).outE('Payment')
 * }</pre>
 *
 * <p>It is the only shape in the demo that uses all three access paths one after another: the geo index
 * (S2) for the circle, the vector index (HNSW) for the lookalikes, and the payment edge classes for the
 * last hop. So the report is built the same way: a hundred runs of the statement, the latency distribution,
 * the storage traffic per run, and - because "which part costs what" is the first question such a query
 * raises - the same statement cut short after each step, measured separately.
 *
 * <p>A running service locks the demo directory, so the benchmark works on a copy. The vector index is
 * rebuilt there (about a fifth of a second) so the measurement never depends on whether the served copy
 * happens to be indexed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RocksDbGeoVectorPaymentBenchmarkTest {

    /** The statement under measurement, and the three prefixes it is made of. */
    private static final String SEED = "g.V(1001,'Account')";
    private static final String CIRCLE =
            ".circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 5000)";
    private static final String GEO = SEED + CIRCLE + ".limit(10)";
    private static final String LOOKALIKES = GEO + ".nearest(10)";
    private static final String QUERY = LOOKALIKES + ".outE('Payment')";
    /**
     * The same statement ending on the <b>vertices</b> the payments reach instead of the edges: the answer is
     * the receivers, so the edges are only walked - which is the comparison this benchmark is for.
     */
    private static final String QUERY_OUT = LOOKALIKES + ".out('Payment')";

    /** How many times the statement runs (what the caller asked for). */
    private static final int RUNS = 100;
    /** Runs before measuring: the store's caches warm up here, not during the measurement. */
    private static final int WARMUP = 20;
    /** Runs per prefix in the stage breakdown. */
    private static final int STAGE_RUNS = 50;

    /** The counters the report prints, in reading order. */
    private static final List<String> TRAFFIC = List.of(
            Metrics.KV_READ_CALL, Metrics.KV_READ_ITEMS, Metrics.VERTEX_READ, Metrics.VERTEX_READ_ITEMS,
            Metrics.EDGE_READ, Metrics.EDGE_READ_ITEMS, Metrics.GEO_READ, Metrics.EDGE_DECODED,
            Metrics.EDGE_FILTERED);

    private Path directory;
    private RocksDbEdgeStore store;
    private GraphSchema schema;
    private GraphMetrics metrics;
    private GremlinGraphQuery engine;
    private HnswIndex index;
    private VectorIndexSchemas.VectorProperty vector;

    /** One measured run: the wall time, the answer's shape, and the storage traffic it caused. */
    private record Run(String label, long nanos, int items, int vertices, int edges, Map<String, Long> traffic) {
    }

    /** What one stage of the statement cost (its own prefix, measured alone). */
    private record Stage(String label, String gremlin, List<Run> runs) {
    }

    private final List<Run> runs = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();
    private List<String> readTrace = List.of();
    /** The same three measurements for the 'out()' variant. */
    private final List<Run> runsOut = new ArrayList<>();
    private final List<Stage> stagesOut = new ArrayList<>();
    private List<String> readTraceOut = List.of();
    private List<String> cacheNotes = List.of();

    @BeforeAll
    void openTheDemoGraphOnACopy() throws IOException {
        Path source = Path.of("..", ".rocksdb", "payment-graph-rocksdb-edge");
        assumeTrue(Files.isDirectory(source), "the RocksDB demo data is not loaded: " + source);
        directory = Files.createTempDirectory("phoenixgraph-bench-geo-vector-");
        copyDirectory(source, directory);

        schema = schemaOf();
        RocksDbHnswIndexFactory.declareLayers(schema);
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.toString()));
        metrics = new GraphMetrics("payment-graph-rocksdb-edge-geo-vector-payment");

        vector = VectorIndexSchemas.vectorProperties(schema).get(0);
        RocksDbVectorIndexBuilder.Built built = RocksDbVectorIndexBuilder.rebuild(store,
                new RocksDbEdgeGraphQuery(store, schema), schema, vector);
        System.out.printf(Locale.ROOT, "vector index rebuilt for the benchmark: %d vertices, %d layers, "
                + "%d links (%.2f s)%n", built.vertices(), built.layers(), built.links(), built.seconds());

        engine = new GremlinGraphQuery(recording(new RocksDbEdgeGraphQuery(store.metrics(metrics), schema)),
                NeighborVertexResolver.from(schema))
                .schema(schema)
                .geoProperties(geoProperties())
                .hnswIndex(vector.vertexName(), RocksDbHnswIndexFactory.open(
                        new RocksDbEdgeGraphQuery(store, schema), store.handle(), schema, vector, Duration.ZERO));
        index = engine.hnswIndexes().get(vector.vertexName());
        assertTrue(index.stats().size() > 0, "the benchmark needs a built index to search");
    }

    @AfterAll
    void printTheReport() {
        System.out.println(report());
        if (store != null) {
            store.close();
        }
        if (directory != null) {
            deleteRecursively(directory);
        }
    }

    /**
     * A hundred runs of each statement, <b>interleaved</b>: the two differ only in their last hop, so
     * measuring one after the other would compare a cold JIT against a warm one. Alternating them gives
     * both the same machine state, which is what makes the comparison in the report mean anything.
     */
    @Test
    @Order(1)
    void bothStatementsRunAHundredTimesEachInterleaved() {
        for (int i = 0; i < WARMUP; i++) {
            engine.executeWithSubgraph(QUERY);
            engine.executeWithSubgraph(QUERY_OUT);
        }
        runs.clear();
        runsOut.clear();
        for (int i = 0; i < RUNS; i++) {
            runs.add(measure("run " + (i + 1), QUERY));
            runsOut.add(measure("run " + (i + 1), QUERY_OUT));
        }
        assertEquals(RUNS, runs.size(), "a hundred runs of A");
        assertEquals(RUNS, runsOut.size(), "a hundred runs of B");
        assertTrue(runs.stream().allMatch(run -> run.items() > 0), "every run of A answers something");
        assertTrue(runsOut.stream().allMatch(run -> run.items() > 0), "every run of B answers something");
        System.out.printf(Locale.ROOT, "%d interleaved runs of each statement on %s%n", RUNS, label());
    }

    /** The prefixes: which part of the statement costs what. */
    @Test
    @Order(2)
    void eachStepOfTheStatementIsMeasuredOnItsOwn() {
        stages.clear();
        stages.add(measureStage("1. seed only", SEED));
        stages.add(measureStage("2. + circle(5km).limit(10)", GEO));
        stages.add(measureStage("3. + nearest(10)", LOOKALIKES));
        stages.add(measureStage("4. + outE('Payment') (the statement)", QUERY));
        System.out.println("stage breakdown: " + stages.stream()
                .map(stage -> String.format(Locale.ROOT, "%s %.2f ms", stage.label(), meanMillis(stage.runs())))
                .reduce((a, b) -> a + " | " + b).orElse(""));
    }

    /** What the statement reads: the property names of every storage read, in order. */
    @Test
    @Order(3)
    void theReadsOfOneRunAreListed() {
        reads.clear();
        recording = true;
        engine.executeWithSubgraph(QUERY);
        recording = false;
        List<String> lines = new ArrayList<>();
        for (Read read : reads) {
            lines.add(traceLine(read));
        }
        readTrace = lines;
        assertFalse(lines.isEmpty(), "the statement reads something");
    }

    /** Cold against warm, per stage of the cache: the engine's per-query cache and the store's own. */
    @Test
    @Order(4)
    void theCachesAreMeasuredByTheirWarmUp() {
        List<String> notes = new ArrayList<>();
        // the engine's cache lives for one execution, so it cannot be measured by repeating a run; what can
        // be measured is the store's, whose caches fill during the warm-up rounds
        GremlinGraphQuery fresh = newEngine();
        Run cold = measure(fresh, "cold", QUERY);
        long warmNanos = 0;
        long warmCalls = 0;
        for (int i = 0; i < 20; i++) {
            Run warm = measure(fresh, "warm", QUERY);
            warmNanos += warm.nanos();
            warmCalls += warm.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L);
        }
        notes.add(String.format(Locale.ROOT,
                "  first run after the store opened: %.2f ms, %d kv reads", cold.nanos() / 1e6,
                cold.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L)));
        notes.add(String.format(Locale.ROOT,
                "  next twenty runs:                 %.2f ms, %.1f kv reads (the store serves the repeated "
                        + "read from memory)", warmNanos / 1e6 / 20,
                (double) warmCalls / 20));
        notes.add(String.format(Locale.ROOT,
                "  the engine's per-query cache is per execution: two identical runs read the same records "
                        + "(%d = %d)",
                runs.get(0).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L),
                runs.get(1).traffic().getOrDefault(Metrics.KV_READ_CALL, 0L)));
        cacheNotes = notes;
    }

    @Test
    @Order(6)
    void theOutVariantStepsAreMeasuredOnTheirOwn() {
        stagesOut.clear();
        stagesOut.add(measureStage("1. seed only", SEED));
        stagesOut.add(measureStage("2. + circle(5km).limit(10)", GEO));
        stagesOut.add(measureStage("3. + nearest(10)", LOOKALIKES));
        stagesOut.add(measureStage("4. + out('Payment') (the statement)", QUERY_OUT));
    }

    @Test
    @Order(7)
    void theOutVariantReadsAreListed() {
        reads.clear();
        recording = true;
        engine.executeWithSubgraph(QUERY_OUT);
        recording = false;
        List<String> lines = new ArrayList<>();
        for (Read read : reads) {
            lines.add(traceLine(read));
        }
        readTraceOut = lines;
        assertFalse(lines.isEmpty(), "the out() variant reads something");
    }

    // ------------------------------------------------------------------ measuring

    private Run measure(String label, String gremlin) {
        return measure(engine, label, gremlin);
    }

    private Run measure(GremlinGraphQuery query, String label, String gremlin) {
        Map<String, Long> before = counters();
        reads.clear();
        recording = true;
        long started = System.nanoTime();
        GremlinGraphQuery.Result result = query.executeWithSubgraph(gremlin);
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
        return new Run(label, nanos, result.values().size(), result.subgraph().vertices().size(),
                result.subgraph().edges().size(), traffic);
    }

    private Stage measureStage(String label, String gremlin) {
        for (int i = 0; i < WARMUP; i++) {
            engine.executeWithSubgraph(gremlin);
        }
        List<Run> measured = new ArrayList<>(STAGE_RUNS);
        for (int i = 0; i < STAGE_RUNS; i++) {
            measured.add(measure(label, gremlin));
        }
        return new Stage(label, gremlin, measured);
    }

    private Map<String, Long> counters() {
        MetricsSnapshot snapshot = metrics.snapshot();
        return new TreeMap<>(snapshot.counters());
    }

    // ------------------------------------------------------------------ the recorder

    /** What a store read answers, from its declared return type. */
    private enum Kind {
        VERTEX, EDGE, GEO, COUNT, OTHER
    }

    /** One storage read: the operation, what it answers, the edge class if any, the properties, how much. */
    private record Read(String op, Kind kind, String edgeClass, int ids, List<String> properties, int returned) {
    }

    /** How one store method is read: where its ids and property names sit, what it answers. */
    private record Shape(int idsIndex, int propertiesIndex, Kind kind, boolean nested, boolean traffic) {
    }

    private final List<Read> reads = new ArrayList<>();
    /**
     * The declared edge classes, so a read can be labelled with the class it walked. Read from the live
     * schema every time: the vector index adds its layer classes to it while it is built.
     */
    private java.util.Set<String> edgeClassNames() {
        return schema.edgeSchemas().stream()
                .map(com.pengcheng.phoenixgraph.schema.EdgeSchema::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private boolean recording;
    private final Map<Method, Shape> shapes = new LinkedHashMap<>();

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
                    if (recording && shape.traffic()) {
                        reads.add(read(method, shape, args, value));
                    }
                    return value;
                });
    }

    private Shape shapeOf(Method method) {
        return shapes.computeIfAbsent(method, m -> {
            Class<?> element = elementType(m.getGenericReturnType());
            Kind kind = switch (element == null ? "" : element.getSimpleName()) {
                case "VertexQueryResult" -> Kind.VERTEX;
                case "EdgeQueryResult", "NeighbourRef", "ScannedEdge" -> Kind.EDGE;
                case "GeoHit" -> Kind.GEO;
                case "Long" -> Kind.COUNT;
                default -> Kind.OTHER;
            };
            Class<?> returns = m.getReturnType();
            boolean traffic = List.class.isAssignableFrom(returns) || returns == long.class
                    || returns == Long.class || kind != Kind.OTHER;
            int ids = -1;
            int properties = -1;
            Type[] parameters = m.getGenericParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] == String[].class) {
                    properties = i;
                    continue;
                }
                if (!(parameters[i] instanceof ParameterizedType list) || list.getRawType() != List.class) {
                    continue;
                }
                Type argument = list.getActualTypeArguments()[0];
                if (argument == Object.class) {
                    ids = i;
                } else if (argument == String.class && properties < 0) {
                    properties = i;
                }
            }
            return new Shape(ids, properties, kind, isNested(m.getGenericReturnType()), traffic);
        });
    }

    private static Class<?> elementType(Type type) {
        Type current = type;
        while (current instanceof ParameterizedType list && list.getRawType() == List.class) {
            current = list.getActualTypeArguments()[0];
        }
        return current instanceof Class<?> element ? element : null;
    }

    private static boolean isNested(Type type) {
        return type instanceof ParameterizedType outer && outer.getRawType() == List.class
                && outer.getActualTypeArguments()[0] instanceof ParameterizedType inner
                && inner.getRawType() == List.class;
    }

    private Read read(Method method, Shape shape, Object[] args, Object value) {
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
        String edgeClass = null;
        for (Object argument : args) {
            if (argument instanceof String text && edgeClassNames().contains(text)) {
                edgeClass = text;
                break;
            }
        }
        return new Read(method.getName(), shape.kind(), edgeClass, ids, properties, returned(shape, value));
    }

    /** How many records came back (a batched read answers one list per id). */
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
                continue;
            }
            if (shape.nested() && element instanceof List<?> inner) {
                found += (int) inner.stream().filter(Objects::nonNull).count();
            } else {
                found++;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ the report

    private String label() {
        return "payment-graph-rocksdb-edge (RocksDB-Edge, geo + vector + edges)";
    }

    private String report() {
        StringBuilder out = new StringBuilder();
        out.append('\n').append("=".repeat(102)).append('\n');
        out.append("geo + vector + edges benchmark on ").append(label()).append('\n');
        out.append("  A: ").append(QUERY).append("\n");
        out.append("  B: ").append(QUERY_OUT).append('\n');
        out.append("=".repeat(102)).append('\n');

        statement(out, "A  outE('Payment') - the answer is the edges", QUERY, runs, stages, readTrace);
        statement(out, "B  out('Payment') - the answer is the receivers", QUERY_OUT, runsOut, stagesOut,
                readTraceOut);
        comparison(out);

        if (!cacheNotes.isEmpty()) {
            out.append(String.format(Locale.ROOT, "%ncaches (measured on A)%n"));
            cacheNotes.forEach(note -> out.append(note).append('\n'));
        }
        out.append(String.format(Locale.ROOT,
                "%n  an empty property list reads everything the class declares: a vertex read with no name%n"
                        + "  asks for all its properties, and so does an edge read. The index' layer-0%n"
                        + "  consistency check adds one read per search, so nearest(10) over ten traversers%n"
                        + "  pays ten of them.\n"));
        out.append('\n').append("=".repeat(102));
        return out.toString();
    }

    /** One statement's sections: latency, its blocks, traffic, its steps, its read trace. */
    private void statement(StringBuilder out, String title, String gremlin, List<Run> measured,
                           List<Stage> measuredStages, List<String> trace) {
        if (measured.isEmpty()) {
            return;
        }
        out.append(String.format(Locale.ROOT, "%n--- %s%n", title));
        List<Long> times = measured.stream().map(Run::nanos).sorted().toList();
        long total = times.stream().mapToLong(Long::longValue).sum();
        out.append(String.format(Locale.ROOT, "latency over %d runs%n", times.size()));
        out.append(String.format(Locale.ROOT,
                "  mean %7.3f ms   min %7.3f   p50 %7.3f   p90 %7.3f   p95 %7.3f   p99 %7.3f   max %7.3f%n",
                total / 1e6 / times.size(), at(times, 0.0), at(times, 0.50), at(times, 0.90), at(times, 0.95),
                at(times, 0.99), at(times, 1.0)));
        out.append(String.format(Locale.ROOT,
                "  throughput %.0f queries/s   wall %.2f s   items %.1f   subgraph %.1f vertices / %.1f edges%n",
                times.size() / (total / 1e9), total / 1e9,
                measured.stream().mapToInt(Run::items).average().orElse(0),
                measured.stream().mapToInt(Run::vertices).average().orElse(0),
                measured.stream().mapToInt(Run::edges).average().orElse(0)));

        out.append(String.format(Locale.ROOT, "  blocks of 20 (the first ones are not the steady state)%n"));
        for (int block = 0; block < measured.size(); block += 20) {
            List<Run> slice = measured.subList(block, Math.min(block + 20, measured.size()));
            out.append(String.format(Locale.ROOT, "    runs %3d-%3d  %7.3f ms mean   %7.3f p95   %6.1f kv reads%n",
                    block + 1, block + slice.size(), meanMillis(slice),
                    at(slice.stream().map(Run::nanos).sorted().toList(), 0.95),
                    slice.stream().mapToLong(run -> run.traffic().getOrDefault(Metrics.KV_READ_CALL, 0L))
                            .average().orElse(0)));
        }

        out.append(String.format(Locale.ROOT, "  storage traffic per run (mean / max)%n"));
        for (String metric : TRAFFIC) {
            long sum = measured.stream().mapToLong(run -> run.traffic().getOrDefault(metric, 0L)).sum();
            long max = measured.stream().mapToLong(run -> run.traffic().getOrDefault(metric, 0L)).max().orElse(0);
            out.append(String.format(Locale.ROOT, "    %-22s %9.2f / %-6d%n", metric.replace("storage.", ""),
                    (double) sum / measured.size(), max));
        }

        if (!measuredStages.isEmpty()) {
            out.append(String.format(Locale.ROOT, "  where the time goes (%d runs each, warm)%n", STAGE_RUNS));
            double seed = meanMillis(measuredStages.get(0).runs());
            for (Stage stage : measuredStages) {
                List<Long> stageTimes = stage.runs().stream().map(Run::nanos).sorted().toList();
                out.append(String.format(Locale.ROOT,
                        "    %-40s %7.3f ms   %7.3f p95   %+7.3f vs seed   %6.1f items%n",
                        stage.label(), meanMillis(stage.runs()), at(stageTimes, 0.95),
                        meanMillis(stage.runs()) - seed,
                        stage.runs().stream().mapToInt(Run::items).average().orElse(0)));
            }
            if (measuredStages.size() == 4) {
                out.append(String.format(Locale.ROOT,
                        "    steps: seed %.3f, +circle %.3f, +nearest(10) %.3f, +last hop %.3f ms%n",
                        meanMillis(measuredStages.get(0).runs()),
                        meanMillis(measuredStages.get(1).runs()) - meanMillis(measuredStages.get(0).runs()),
                        meanMillis(measuredStages.get(2).runs()) - meanMillis(measuredStages.get(1).runs()),
                        meanMillis(measuredStages.get(3).runs()) - meanMillis(measuredStages.get(2).runs())));
            }
        }

        if (!trace.isEmpty()) {
            out.append(String.format(Locale.ROOT, "  what one run of this statement reads%n"));
            trace.forEach(line -> out.append(line).append('\n'));
        }
    }

    /** A and B next to each other: what the last hop changes. */
    private void comparison(StringBuilder out) {
        if (runs.isEmpty() || runsOut.isEmpty()) {
            return;
        }
        out.append(String.format(Locale.ROOT, "%n--- A against B: the last hop is the only difference%n"));
        out.append(String.format(Locale.ROOT,
                "  %-26s %14s %14s %12s%n", "", "A  outE (edges)", "B  out (vertices)", "B - A"));
        line(out, "mean ms", meanMillis(runs), meanMillis(runsOut));
        line(out, "p95 ms", at(runs.stream().map(Run::nanos).sorted().toList(), 0.95),
                at(runsOut.stream().map(Run::nanos).sorted().toList(), 0.95));
        line(out, "items returned", meanOf(runs, Run::items), meanOf(runsOut, Run::items));
        line(out, "subgraph vertices", meanOf(runs, Run::vertices), meanOf(runsOut, Run::vertices));
        line(out, "subgraph edges", meanOf(runs, Run::edges), meanOf(runsOut, Run::edges));
        for (String metric : TRAFFIC) {
            line(out, metric.replace("storage.", ""), meanTraffic(runs, metric), meanTraffic(runsOut, metric));
        }
        out.append(String.format(Locale.ROOT,
                "%n  measured interleaved (A, B, A, B, ...), so the two columns share one JIT state and one%n"
                        + "  cache state: the difference is the last hop and nothing else - the steps before it%n"
                        + "  are identical in both.%n"));
    }

    private void line(StringBuilder out, String name, double a, double b) {
        out.append(String.format(Locale.ROOT, "  %-26s %14.2f %14.2f %12.2f%n", name, a, b, b - a));
    }

    private static double meanOf(List<Run> runs, java.util.function.ToIntFunction<Run> field) {
        return runs.stream().mapToInt(field).average().orElse(0);
    }

    private static double meanTraffic(List<Run> runs, String metric) {
        return runs.stream().mapToLong(run -> run.traffic().getOrDefault(metric, 0L)).average().orElse(0);
    }

    /** One line of a read trace: the operation, what it answers, the class, ids, and the properties. */
    private static String traceLine(Read read) {
        return String.format(Locale.ROOT, "    %-20s %-7s %-22s ids %-4d -> %-5d  %s", read.op(),
                read.kind(), read.edgeClass() == null ? "" : read.edgeClass(), read.ids(), read.returned(),
                read.properties().isEmpty() ? "no property named" : String.join(", ", read.properties()));
    }

    private static double meanMillis(List<Run> runs) {
        return runs.stream().mapToLong(Run::nanos).average().orElse(0) / 1e6;
    }

    private static double at(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sorted.size() - 1,
                Math.max(0, Math.round(percentile * (sorted.size() - 1))));
        return sorted.get(index) / 1e6;
    }

    // ------------------------------------------------------------------ the graph

    private GremlinGraphQuery newEngine() {
        Map<String, String> geo = geoProperties();
        return new GremlinGraphQuery(new RocksDbEdgeGraphQuery(store, schema),
                NeighborVertexResolver.from(schema))
                .schema(schema)
                .geoProperties(geo)
                .hnswIndex(vector.vertexName(), RocksDbHnswIndexFactory.open(
                        new RocksDbEdgeGraphQuery(store, schema), store.handle(), schema, vector,
                        Duration.ZERO));
    }

    private Map<String, String> geoProperties() {
        Map<String, String> geo = new LinkedHashMap<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema)) {
            geo.putIfAbsent(property.vertexName(), property.propertyName());
        }
        return geo;
    }

    private static GraphSchema schemaOf() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources",
                "payment-graph-rocksdb-edge.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().equals("LOCK")) {
                    continue;
                }
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // a temporary directory: what is left behind is not worth failing a benchmark for
                }
            }
        } catch (IOException ignored) {
            // same
        }
    }
}
