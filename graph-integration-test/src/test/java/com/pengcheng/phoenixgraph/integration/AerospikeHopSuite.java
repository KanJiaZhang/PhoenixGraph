package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Aerospike payment demo, the twenty traversals the accuracy and latency checks run, and a
 * <b>reference reader</b> that answers the same questions straight from the stored records.
 *
 * <p>The point of the reference is that it shares no code with the query engine: it reads bins with
 * {@link AerospikeEdgeStore#getEdges} and {@link AerospikeVertexStore#readVertex}, maps property ids
 * to names with the schema, and composes the hops (and the conditions, caps, projections and
 * deduplication) by hand. A query is therefore checked against the data, not against a number that was
 * written down once - so the check keeps working when the demo data changes, and a change in the
 * engine that silently drops an edge, a filter or a projection shows up as a difference.
 *
 * <p>Each case says which side it ends on ({@link EndsAt#VERTEX} or {@link EndsAt#EDGE}) and how its
 * answer is compared ({@link Shape}): a multiset of ids, a multiset of property values, a count, a
 * capped list that has to be a subset of the full one, or the keys of a map.
 */
final class AerospikeHopSuite implements AutoCloseable {

    /** The account the demo pays everyone from, and one of the ring accounts that has every edge class. */
    static final String HUB = "135";
    static final String RING = "1000";

    /** Which side of the last edge traversal the answer is on. */
    enum EndsAt {
        VERTEX, EDGE
    }

    /** How an answer is compared with the reference. */
    enum Shape {
        /** Ids (vertices or edges), as a multiset: order is ignored, duplicates are not. */
        IDS,
        /** Property values, as a multiset. */
        VALUES,
        /** A single number. */
        COUNT,
        /** At most {@code limit} ids, all of them from the expected set. */
        LIMITED
    }

    /** One traversal: its text, what it should answer, and where the reference gets that from. */
    record Case(String id, String label, String query, EndsAt endsAt, Shape shape, int limit,
                Supplier<Object> expected) {
    }

    /** One stored edge, read the way the storage layer stores it (property names resolved by the schema). */
    record RawEdge(String edgeId, Object peer, Map<String, Object> properties, long createdTime,
                   long lastUpdatedTime) {
    }

    private final String set;
    private final GraphSchema schema;
    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final AerospikeGraphQuery graphQuery;
    private final GremlinGraphQuery gremlin;
    private final GraphMetrics metrics;
    private final Map<String, List<RawEdge>> edgeCache = new LinkedHashMap<>();
    private final Map<Object, Map<String, Object>> vertexCache = new LinkedHashMap<>();
    private final AerospikeClient client;

    private AerospikeHopSuite(AerospikeClient client, String set, GraphSchema schema,
                              AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                              AerospikeGraphQuery graphQuery, GremlinGraphQuery gremlin,
                              GraphMetrics metrics) {
        this.client = client;
        this.set = set;
        this.schema = schema;
        this.vertexStore = vertexStore;
        this.edgeStore = edgeStore;
        this.graphQuery = graphQuery;
        this.gremlin = gremlin;
        this.metrics = metrics;
    }

    /**
     * Opens the demo graph, skipping the test when Aerospike or the data is missing.
     *
     * @param metricsName the graph name the metrics of this suite are recorded under
     */
    static AerospikeHopSuite open(String metricsName) {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        String vertexSet = System.getProperty("payment.vertexSet",
                System.getenv().getOrDefault("PAYMENT_VERTEX_SET", "http_demo_v2"));
        GraphSchema schema = loadSchema();
        GraphMetrics metrics = new GraphMetrics(metricsName);
        AerospikeVertexStore vertexStore =
                new AerospikeVertexStore(client, config.namespace(), vertexSet).metrics(metrics);
        AerospikeEdgeStore edgeStore =
                new AerospikeEdgeStore(client, config.namespace(), vertexSet, schema).metrics(metrics);
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
        AerospikeHopSuite suite = new AerospikeHopSuite(client, vertexSet, schema, vertexStore, edgeStore,
                graphQuery, gremlin, metrics);
        assumeTrue(suite.edges(HUB, "Payment", EdgeSide.OUT).size() >= 10,
                "the payment demo data is not loaded in set '" + vertexSet + "'");
        return suite;
    }

    /** The graph the traversals run on. */
    GremlinGraphQuery gremlin() {
        return gremlin;
    }

    /** The same graph with a different read parallelism, for a comparison measurement. */
    GremlinGraphQuery engineWithParallelism(int parallelism) {
        return new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema)).parallelism(parallelism);
    }

    GraphMetrics metrics() {
        return metrics;
    }

    String setId() {
        return set;
    }

    int hops(Case testCase) {
        // the label of a case starts with its hop count ("1." / "2.")
        return testCase.id().startsWith("2") ? 2 : 1;
    }

    // ------------------------------------------------------------------ the reference reader

    /** The edges of one class at a vertex, read from the store (cached per vertex/class/side). */
    List<RawEdge> edges(Object vertexId, String edgeName, EdgeSide side) {
        String key = vertexId + "|" + edgeName + "|" + side;
        List<RawEdge> cached = edgeCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<RawEdge> edges = switch (side) {
            // a directed class stores one row per endpoint, so "both" is the two reads a traversal
            // makes as well - the store's single both-direction read is the undirected layout
            case BOTH -> {
                List<RawEdge> both = new ArrayList<>(readEdges(vertexId, edgeName,
                        AerospikeEdgeStore.EDGE_DIR_OUT));
                both.addAll(readEdges(vertexId, edgeName, AerospikeEdgeStore.EDGE_DIR_IN));
                yield both;
            }
            case OUT -> readEdges(vertexId, edgeName, AerospikeEdgeStore.EDGE_DIR_OUT);
            case IN -> readEdges(vertexId, edgeName, AerospikeEdgeStore.EDGE_DIR_IN);
        };
        edgeCache.put(key, edges);
        return edges;
    }

    /** The rows of one direction of a directed class, as the storage layer reports them. */
    private List<RawEdge> readEdges(Object vertexId, String edgeName, int direction) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        List<RawEdge> edges = new ArrayList<>();
        for (StoredEdgeValue stored : edgeStore.getEdges(vertexId, edgeSchema.id(), direction)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (Map.Entry<Integer, EdgePropertyValue> entry : stored.properties().entrySet()) {
                EdgePropertySchema property = edgeSchema.property(entry.getKey());
                if (property != null) {
                    properties.put(property.name(), entry.getValue().value());
                }
            }
            edges.add(new RawEdge(stored.edgeId(), stored.otherVertexId(), properties,
                    stored.createdTime(), stored.lastUpdatedTime()));
        }
        return edges;
    }

    /** The declared properties of a vertex that are present, by name (cached). */
    Map<String, Object> vertex(Object vertexId) {
        Map<String, Object> cached = vertexCache.get(vertexId);
        if (cached != null) {
            return cached;
        }
        StoredVertex stored = vertexStore.readVertex(vertexId);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (stored != null) {
            for (VertexSchema vertexSchema : schema.vertexSchemas()) {
                for (VertexPropertySchema property : vertexSchema.properties()) {
                    VertexPropertyValue value = stored.properties().get(property.id());
                    if (value != null) {
                        properties.put(property.name(), value.value());
                    }
                }
            }
        }
        vertexCache.put(vertexId, properties);
        return properties;
    }

    /** The peer ids of a list of edges, as the traversal reports them (text). */
    static List<String> peers(List<RawEdge> edges) {
        List<String> out = new ArrayList<>(edges.size());
        for (RawEdge edge : edges) {
            out.add(String.valueOf(edge.peer()));
        }
        return out;
    }

    /** The edge ids of a list of edges. */
    static List<String> edgeIds(List<RawEdge> edges) {
        List<String> out = new ArrayList<>(edges.size());
        for (RawEdge edge : edges) {
            out.add(String.valueOf(edge.edgeId()));
        }
        return out;
    }

    /** The edges whose property has one of the given values (the reference for {@code has}/{@code filter}). */
    static List<RawEdge> matching(List<RawEdge> edges, String property, String... values) {
        Set<String> wanted = Set.of(values);
        List<RawEdge> out = new ArrayList<>();
        for (RawEdge edge : edges) {
            Object actual = edge.properties().get(property);
            if (actual != null && wanted.contains(String.valueOf(actual))) {
                out.add(edge);
            }
        }
        return out;
    }

    /** The edges of a class out of a vertex. */
    List<RawEdge> out(Object vertexId, String edgeName) {
        return edges(vertexId, edgeName, EdgeSide.OUT);
    }

    List<RawEdge> in(Object vertexId, String edgeName) {
        return edges(vertexId, edgeName, EdgeSide.IN);
    }

    List<RawEdge> both(Object vertexId, String edgeName) {
        return edges(vertexId, edgeName, EdgeSide.BOTH);
    }

    /** Two hops over out-edges: for every peer of the first hop (in order, duplicates kept), the second. */
    List<RawEdge> hop2(List<RawEdge> first, String edgeName) {
        return hop2(first, edgeName, EdgeSide.OUT);
    }

    /** Two hops, the second one on the given side (a {@code both} traversal reads both directions). */
    List<RawEdge> hop2(List<RawEdge> first, String edgeName, EdgeSide side) {
        List<RawEdge> out = new ArrayList<>();
        for (RawEdge edge : first) {
            out.addAll(edges(edge.peer(), edgeName, side));
        }
        return out;
    }

    /** The property of every peer of a list of edges that has it (the reference for {@code values}). */
    List<String> peerProperty(List<RawEdge> edges, String property) {
        List<String> out = new ArrayList<>();
        for (RawEdge edge : edges) {
            Object value = vertex(edge.peer()).get(property);
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    /** The values of one property over a list of edges. */
    static List<String> property(List<RawEdge> edges, String property) {
        List<String> out = new ArrayList<>();
        for (RawEdge edge : edges) {
            Object value = edge.properties().get(property);
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the twenty traversals

    /**
     * Ten one-hop traversals: out and in, edges and vertices, with a condition, a count, a cap, a
     * projection and a creation-time window - each answer computed from the stored records.
     */
    List<Case> oneHopCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("1.1", "V(135).out(Payment) - the 50 accounts it pays", vertex("out", HUB, "Payment"),
                EndsAt.VERTEX, Shape.IDS, 0, () -> peers(out(HUB, "Payment"))));
        cases.add(new Case("1.2", "V(135).outE(Payment) - the edges themselves", edge("out", HUB, "Payment"),
                EndsAt.EDGE, Shape.IDS, 0, () -> edgeIds(out(HUB, "Payment"))));
        cases.add(new Case("1.3", "V(135).outE(Payment).values(status)",
                "g.V('" + HUB + "','Account').outE('Payment').values('status')",
                EndsAt.EDGE, Shape.VALUES, 0, () -> property(out(HUB, "Payment"), "status")));
        cases.add(new Case("1.4", "V(135).out(Payment).values(accountStatus) - a vertex projection",
                "g.V('" + HUB + "','Account').out('Payment').values('accountStatus')",
                EndsAt.VERTEX, Shape.VALUES, 0, () -> peerProperty(out(HUB, "Payment"), "accountStatus")));
        cases.add(new Case("1.5", "V(135).outE(Payment).has(status,SUCCESS) - pushed into the read",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS')",
                EndsAt.EDGE, Shape.IDS, 0, () -> edgeIds(matching(out(HUB, "Payment"), "status", "SUCCESS"))));
        cases.add(new Case("1.6", "V(135).outE(Payment).filter(status=SUCCESS || type=invoice).count()",
                "g.V('" + HUB + "','Account').outE('Payment')"
                        + ".filter(status.equals('SUCCESS') || type.equals('invoice')).count()",
                EndsAt.EDGE, Shape.COUNT, 0, () -> (long) (matching(out(HUB, "Payment"), "status", "SUCCESS").size()
                        + matching(out(HUB, "Payment"), "type", "invoice").size())));
        cases.add(new Case("1.7", "V(135).out(Payment).count() - the neighbours, counted",
                "g.V('" + HUB + "','Account').out('Payment').count()",
                EndsAt.VERTEX, Shape.COUNT, 0, () -> (long) out(HUB, "Payment").size()));
        cases.add(new Case("1.8", "V(135).outE(Payment).limit(5) - a capped read",
                "g.V('" + HUB + "','Account').outE('Payment').limit(5)",
                EndsAt.EDGE, Shape.LIMITED, 5, () -> new LinkedHashSet<>(edgeIds(out(HUB, "Payment")))));
        cases.add(new Case("1.9", "V(1000).inE(Payment) - the accounts that pay it",
                "g.V('" + RING + "','Account').inE('Payment')",
                EndsAt.EDGE, Shape.IDS, 0, () -> edgeIds(in(RING, "Payment"))));
        cases.add(new Case("1.10", "V(135).createdBetween(oldest half).outE(Payment) - a time window",
                "g.V('" + HUB + "','Account').createdBetween(" + window().fromTime() + "," + window().toTime()
                        + ").outE('Payment')",
                EndsAt.EDGE, Shape.IDS, 0, () -> {
                    List<RawEdge> inWindow = new ArrayList<>();
                    for (RawEdge edge : out(HUB, "Payment")) {
                        if (window().contains(edge.createdTime())) {
                            inWindow.add(edge);
                        }
                    }
                    return edgeIds(inWindow);
                }));
        return cases;
    }

    /**
     * A window over the oldest half of the hub's payments, so the query has a proper subset to find.
     *
     * <p>It is written <b>before</b> the hop on purpose: that is the form a store can use - the window
     * is known when the bin is read, so the edges outside it are never built. A window after a hop can
     * only be a report about what was already read. When every payment of the hub shares one creation
     * time there is no proper subset to select, so the whole class is the window and the case still
     * checks the same answer.
     */
    private TimeRange window() {
        List<RawEdge> edges = out(HUB, "Payment");
        List<Long> times = new ArrayList<>();
        for (RawEdge edge : edges) {
            if (!times.contains(edge.createdTime())) {
                times.add(edge.createdTime());
            }
        }
        times.sort(null);
        long from = times.get(0);
        long to = times.size() > 1 ? times.get(times.size() / 2) : times.get(times.size() - 1);
        return TimeRange.of(from, to);
    }

    /**
     * Ten two-hop traversals: five that end on vertices and five that end on edges, over the payment,
     * IP and phone relations, with conditions, projections, a cap, a count and a deduplication.
     */
    List<Case> twoHopCases() {
        List<Case> cases = new ArrayList<>();

        // --- ending on vertices (five) ---
        cases.add(new Case("2.1", "V(1000).out(Payment).out(Payment) - two hops of payments",
                "g.V('" + RING + "','Account').out('Payment').out('Payment')",
                EndsAt.VERTEX, Shape.IDS, 0, () -> peers(hop2(out(RING, "Payment"), "Payment"))));
        cases.add(new Case("2.2", "V(1000).out(AccountToIP).out(IPToAccount) - accounts sharing an IP",
                "g.V('" + RING + "','Account').out('AccountToIP').out('IPToAccount')",
                EndsAt.VERTEX, Shape.IDS, 0, () -> peers(hop2(out(RING, "AccountToIP"), "IPToAccount"))));
        cases.add(new Case("2.3", "V(1000).out(AccountToPhone).out(PhoneToAccount) - accounts sharing a phone",
                "g.V('" + RING + "','Account').out('AccountToPhone').out('PhoneToAccount')",
                EndsAt.VERTEX, Shape.IDS, 0, () -> peers(hop2(out(RING, "AccountToPhone"), "PhoneToAccount"))));
        cases.add(new Case("2.4", "V(135).out(Payment).out(AccountToIP).values(ipAddress) - two hops, projected",
                "g.V('" + HUB + "','Account').out('Payment').out('AccountToIP').values('ipAddress')",
                EndsAt.VERTEX, Shape.VALUES, 0,
                () -> peerProperty(hop2(out(HUB, "Payment"), "AccountToIP"), "ipAddress")));
        cases.add(new Case("2.5", "V(1000).both(Payment).both(Payment).dedup() - a neighbourhood, deduplicated",
                "g.V('" + RING + "','Account').both('Payment').both('Payment').dedup()",
                EndsAt.VERTEX, Shape.IDS, 0,
                () -> new ArrayList<>(new LinkedHashSet<>(
                        peers(hop2(both(RING, "Payment"), "Payment", EdgeSide.BOTH))))));

        // --- ending on edges (five) ---
        cases.add(new Case("2.6", "V(1000).out(Payment).outE(Payment) - the payees' payments",
                "g.V('" + RING + "','Account').out('Payment').outE('Payment')",
                EndsAt.EDGE, Shape.IDS, 0, () -> edgeIds(hop2(out(RING, "Payment"), "Payment"))));
        cases.add(new Case("2.7", "V(1000).out(AccountToIP).outE(IPToAccount) - the IP's other accounts",
                "g.V('" + RING + "','Account').out('AccountToIP').outE('IPToAccount')",
                EndsAt.EDGE, Shape.IDS, 0, () -> edgeIds(hop2(out(RING, "AccountToIP"), "IPToAccount"))));
        cases.add(new Case("2.8", "V(135).outE(Payment).has(status,SUCCESS).otherV().outE(AccountToPhone)",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS')"
                        + ".otherV().outE('AccountToPhone')",
                EndsAt.EDGE, Shape.IDS, 0, () -> {
                    List<RawEdge> out = new ArrayList<>();
                    for (RawEdge payment : matching(out(HUB, "Payment"), "status", "SUCCESS")) {
                        out.addAll(out(payment.peer(), "AccountToPhone"));
                    }
                    return edgeIds(out);
                }));
        cases.add(new Case("2.9", "V(1000).out(Payment).outE(AccountToPhone).count() - two hops, counted",
                "g.V('" + RING + "','Account').out('Payment').outE('AccountToPhone').count()",
                EndsAt.EDGE, Shape.COUNT, 0, () -> (long) hop2(out(RING, "Payment"), "AccountToPhone").size()));
        cases.add(new Case("2.10", "V(1000).outE(AccountToIP).otherV().outE(IPToAccount).limit(3) - capped",
                "g.V('" + RING + "','Account').outE('AccountToIP').otherV().outE('IPToAccount').limit(3)",
                EndsAt.EDGE, Shape.LIMITED, 3, () -> new LinkedHashSet<>(
                        edgeIds(hop2(out(RING, "AccountToIP"), "IPToAccount")))));
        return cases;
    }

    /** Every case, one-hop first. */
    List<Case> cases() {
        List<Case> all = new ArrayList<>(oneHopCases());
        all.addAll(twoHopCases());
        return all;
    }

    private static String vertex(String hop, String id, String edgeName) {
        return "g.V('" + id + "','Account')." + hop + "('" + edgeName + "')";
    }

    private static String edge(String hop, String id, String edgeName) {
        return "g.V('" + id + "','Account')." + hop + "E('" + edgeName + "')";
    }

    // ------------------------------------------------------------------ comparison

    /**
     * Compares an answer with the reference and returns nothing but a readable difference, so a test
     * can report the query itself rather than a bare assertion failure.
     *
     * @return {@code null} when the answer is right, otherwise what differed
     */
    static String difference(Case testCase, Object expected, List<Object> actual) {
        return switch (testCase.shape()) {
            case IDS -> compareMultiset(testCase, expected, idMultiset(actual));
            case VALUES -> compareMultiset(testCase, expected, valueMultiset(actual));
            case COUNT -> {
                long wanted = ((Number) expected).longValue();
                long got = actual.isEmpty() ? -1 : ((Number) actual.get(0)).longValue();
                yield wanted == got ? null : "expected " + wanted + ", got " + got;
            }
            case LIMITED -> {
                List<String> ids = idList(actual);
                Set<String> allowed = castSet(expected);
                if (ids.size() != Math.min(testCase.limit(), allowed.size())) {
                    yield "expected " + Math.min(testCase.limit(), allowed.size()) + " of " + allowed.size()
                            + ", got " + ids.size();
                }
                for (String id : ids) {
                    if (!allowed.contains(id)) {
                        yield "id " + id + " is not among the newest of the class";
                    }
                }
                yield null;
            }
        };
    }

    private static String compareMultiset(Case testCase, Object expected, List<String> actual) {
        List<String> wanted = new ArrayList<>();
        for (Object value : (Iterable<?>) expected) {
            wanted.add(String.valueOf(value));
        }
        List<String> left = new ArrayList<>(wanted);
        List<String> right = new ArrayList<>(actual);
        left.sort(null);
        right.sort(null);
        if (left.equals(right)) {
            return null;
        }
        Set<String> missing = new LinkedHashSet<>(left);
        missing.removeAll(right);
        Set<String> extra = new LinkedHashSet<>(right);
        extra.removeAll(left);
        return "expected " + left.size() + " items, got " + right.size()
                + (missing.isEmpty() ? "" : ", missing " + preview(missing))
                + (extra.isEmpty() ? "" : ", unexpected " + preview(extra));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> castSet(Object expected) {
        return (Set<String>) expected;
    }

    private static String preview(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        return list.subList(0, Math.min(4, list.size())) + (list.size() > 4 ? "..." : "");
    }

    /** The ids of an answer that is a list of elements, as text (the store reports numbers as numbers). */
    private static List<String> idList(List<Object> actual) {
        List<String> ids = new ArrayList<>(actual.size());
        for (Object element : actual) {
            ids.add(elementId(element));
        }
        return ids;
    }

    private static List<String> idMultiset(List<Object> actual) {
        return idList(actual);
    }

    private static List<String> valueMultiset(List<Object> actual) {
        List<String> values = new ArrayList<>(actual.size());
        for (Object value : actual) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    /** The id of a traverser, whichever kind it is (a vertex id, an edge id, or a plain value). */
    private static String elementId(Object element) {
        if (element instanceof com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex vertex) {
            return String.valueOf(vertex.id());
        }
        if (element instanceof com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge edge) {
            return String.valueOf(edge.id());
        }
        return String.valueOf(element);
    }

    @Override
    public void close() {
        edgeCache.clear();
        vertexCache.clear();
        if (client != null) {
            client.close();
        }
    }

    private static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources", "payment-graph.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }

    @Override
    public String toString() {
        return "AerospikeHopSuite[" + set + "]";
    }
}
