package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the Gremlin (and Cypher) answers of the Aerospike payment graph against what
 * Aerospike actually stores.
 *
 * <p>Three levels, in the order the questions were asked:
 * <ol>
 *   <li>one hop: the out and in edges of a vertex, compared edge by edge with the
 *       {@code ve_<schema>_<dir>} bin of its record (count, ids, other endpoint, properties);</li>
 *   <li>two hops over five different edge classes, checking each hop's count against the
 *       stored bins of the intermediate vertex;</li>
 *   <li>filtered, deduplicated, limited and projected plans (Gremlin and Cypher), each
 *       compared with the stored values they should select.</li>
 * </ol>
 *
 * <p>The test reads the demo graph ({@code http_demo_v3} by default, override with
 * {@code -Dpayment.vertexSet=}) and the schema the service serves, and skips itself when
 * Aerospike is unreachable or the demo data is not loaded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentGraphConsistencyTest {

    private static final String HUB = "135";              // the account with 50 payments
    private static final String RING = "1000";            // a ring account with all five edge classes
    private static final int PAYMENT_SCHEMA_ID = 1;
    private static final int ACCOUNT_TO_IP = 4;
    private static final int ACCOUNT_TO_PHONE = 3;
    private static final int IP_TO_ACCOUNT = 5;
    private static final int PHONE_TO_ACCOUNT = 2;
    private AerospikeClient client;
    private GraphSchema schema;
    private AerospikeVertexStore vertexStore;
    private AerospikeEdgeStore edgeStore;
    private GremlinGraphQuery gremlin;
    private CypherGraphQuery cypher;
    private String vertexSet;
    private int statusPropertyId;

    @BeforeAll
    void openTheDemoGraph() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        vertexSet = System.getProperty("payment.vertexSet", System.getenv().getOrDefault("PAYMENT_VERTEX_SET",
                "http_demo_v3"));
        schema = loadSchema();
        vertexStore = new AerospikeVertexStore(client, config.namespace(), vertexSet);
        edgeStore = new AerospikeEdgeStore(client, config.namespace(), vertexSet, schema);
        NeighborVertexResolver resolver = NeighborVertexResolver.from(schema);
        gremlin = new GremlinGraphQuery(new AerospikeGraphQuery(vertexStore, edgeStore, schema), resolver);
        cypher = new CypherGraphQuery(new AerospikeGraphQuery(vertexStore, edgeStore, schema), resolver);
        statusPropertyId = schema.edgeSchema("Payment").property("status").id();
        assumeTrue(paymentCount(HUB) == 76,
                "the payment demo data is not loaded in set '" + vertexSet + "' (account " + HUB
                        + " should have 76 payments); run the loader or set -Dpayment.vertexSet=");
    }

    @AfterAll
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    /** The schema the service serves, read from the graph-http resources so it stays in step. */
    private static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources", "payment-graph.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }

    // ------------------------------------------------------------------ 1. one hop

    @Test
    void outEdgesOfAVertexMatchTheStoredBin() {
        for (String account : List.of(HUB, RING)) {
            List<GremlinEdge> fromGremlin = edges(gremlin.execute(
                    "g.V('" + account + "','Account').outE('Payment')"));
            List<StoredEdgeValue> stored = edgeStore.getEdges(account, PAYMENT_SCHEMA_ID,
                    AerospikeEdgeStore.EDGE_DIR_OUT);

            assertEquals(stored.size(), fromGremlin.size(),
                    "out-edge count of account " + account + " differs from the stored bin");
            assertEquals(ids(stored), ids(fromGremlin), "the same edges came back");
            assertEquals(otherEndpoints(stored), otherEndpoints(fromGremlin), "the same endpoints came back");
            assertEquals(statesOf(stored), statesOf(fromGremlin), "the same property values came back");
        }
    }

    @Test
    void inEdgesOfAVertexMatchTheStoredBin() {
        for (String account : List.of(HUB, RING)) {
            List<GremlinEdge> fromGremlin = edges(gremlin.execute(
                    "g.V('" + account + "','Account').inE('Payment')"));
            List<StoredEdgeValue> stored = edgeStore.getEdges(account, PAYMENT_SCHEMA_ID,
                    AerospikeEdgeStore.EDGE_DIR_IN);

            assertEquals(stored.size(), fromGremlin.size(),
                    "in-edge count of account " + account + " differs from the stored bin");
            assertEquals(ids(stored), ids(fromGremlin));
            assertEquals(otherEndpoints(stored), otherEndpoints(fromGremlin));
        }
    }

    @Test
    void bothDirectionsSeeTheUnionOfTheTwoBins() {
        int out = edgeStore.getEdges(RING, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT).size();
        int in = edgeStore.getEdges(RING, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_IN).size();

        List<GremlinEdge> both = edges(gremlin.execute("g.V('" + RING + "','Account').bothE('Payment')"));
        assertEquals(out + in, both.size(), "both() is the union of the out and in bins");
    }

    @Test
    void theRawRecordAndTheStoreAgreeOnTheEdgeIds() {
        // the bin holds the edges keyed by edge id; the store decodes the same keys
        int schemaId = PAYMENT_SCHEMA_ID;
        Record record = client.get(null, new Key(IntegrationConfig.fromEnv().namespace(), vertexSet, HUB));
        assertNotNull(record, "account " + HUB + " is missing from set " + vertexSet);
        Object bin = record.bins.get(AerospikeEdgeStore.edgeBin(schemaId, AerospikeEdgeStore.EDGE_DIR_OUT));
        assertNotNull(bin, "the out bin is missing from the record");
        Map<?, ?> raw = (Map<?, ?>) bin;

        Set<String> storedIds = new LinkedHashSet<>();
        edgeStore.getEdges(HUB, schemaId, AerospikeEdgeStore.EDGE_DIR_OUT)
                .forEach(edge -> storedIds.add(String.valueOf(edge.edgeId())));
        assertEquals(raw.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()),
                storedIds, "the record's bin keys are the edge ids the store returns");
    }

    @Test
    void vertexReadsReturnTheStoredProperties() {
        GremlinVertex vertex = (GremlinVertex) gremlin.execute("g.V('" + RING + "','Account')").get(0);
        StoredVertex stored = vertexStore.readVertex(RING);
        assertNotNull(stored, "the vertex must exist in Aerospike");

        assertEquals(stored.properties().size(), vertex.properties().size(),
                "the projection returns every declared property");
        for (Map.Entry<Integer, VertexPropertyValue> property : stored.properties().entrySet()) {
            String name = schema.vertexSchema("Account").property(property.getKey()).name();
            assertEquals(String.valueOf(property.getValue().value()).trim(),
                    String.valueOf(vertex.properties().get(name)).trim(),
                    "property '" + name + "' differs from the stored value");
        }
    }

    // ------------------------------------------------------------------ 2. two hops

    @Test
    void twoHopsOverFiveEdgeClassesAgreeWithTheStoredCounts() {
        List<String[]> plans = List.of(
                new String[]{"Payment", "Payment"},                 // account -> account -> account
                new String[]{"AccountToIP", "IPToAccount"},          // account -> ip -> account
                new String[]{"AccountToPhone", "PhoneToAccount"},    // account -> phone -> account
                new String[]{"AccountToIP", "IPToAccount"});
        int checked = 0;
        for (String[] plan : plans) {
            List<Object> first = gremlin.execute("g.V('" + RING + "','Account').outE('" + plan[0] + "')");
            List<GremlinEdge> firstEdges = edges(first);
            int expectedFirst = edgeStore.getEdges(RING, schemaIdOf(plan[0]), AerospikeEdgeStore.EDGE_DIR_OUT).size();
            assertEquals(expectedFirst, firstEdges.size(), "hop 1 count of " + plan[0] + " differs from the bin");

            for (GremlinEdge edge : firstEdges) {
                String next = String.valueOf(edge.otherVertexId());
                List<GremlinEdge> secondEdges = edges(gremlin.execute(
                        "g.V('" + next + "','" + vertexTypeOf(plan[0]) + "').outE('" + plan[1] + "')"));
                int expectedSecond = edgeStore.getEdges(next, schemaIdOf(plan[1]),
                        AerospikeEdgeStore.EDGE_DIR_OUT).size();
                assertEquals(expectedSecond, secondEdges.size(),
                        "hop 2 count of " + plan[1] + " from " + next + " differs from its bin");
                checked++;
            }
        }
        assertTrue(checked > 0, "at least one two-hop path was checked");
    }

    @Test
    void aTwoHopTraversalReturnsExactlyTheReachableVertices() {
        List<Object> direct = gremlin.execute("g.V('" + RING + "','Account').out('AccountToPhone').out('PhoneToAccount')");
        Set<String> reached = new LinkedHashSet<>();
        for (Object element : direct) {
            reached.add(String.valueOf(((GremlinVertex) element).id()));
        }

        // the same set, computed from the stored bins
        Set<String> expected = new LinkedHashSet<>();
        for (StoredEdgeValue toPhone : edgeStore.getEdges(RING, ACCOUNT_TO_PHONE, AerospikeEdgeStore.EDGE_DIR_OUT)) {
            for (StoredEdgeValue back : edgeStore.getEdges(toPhone.otherVertexId(), PHONE_TO_ACCOUNT,
                    AerospikeEdgeStore.EDGE_DIR_OUT)) {
                expected.add(String.valueOf(back.otherVertexId()));
            }
        }
        assertEquals(expected, reached, "the two-hop set differs from what the bins describe");
    }

    // ------------------------------------------------------------------ 3. plans

    @Test
    void filteredPlansSelectWhatIsStored() {
        long storedSuccess = edgeStore.getEdges(HUB, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT).stream()
                .filter(edge -> edge.properties().get(statusPropertyId) != null
                        && "SUCCESS".equals(edge.properties().get(statusPropertyId).value()))
                .count();
        List<Object> filtered = gremlin.execute(
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS').count()");

        assertEquals(storedSuccess, ((Number) filtered.get(0)).longValue(),
                "has('status','SUCCESS') counts the stored SUCCESS edges");
    }

    @Test
    void filterCountsTheSameAsHas() {
        List<Object> viaHas = gremlin.execute(
                "g.V('" + HUB + "','Account').outE('Payment').has('status','PENDING').count()");
        List<Object> viaFilter = gremlin.execute(
                "g.V('" + HUB + "','Account').outE('Payment').filter(status.equals('PENDING')).count()");

        assertEquals(((Number) viaHas.get(0)).longValue(), ((Number) viaFilter.get(0)).longValue(),
                "has and an equivalent filter must agree");
    }

    @Test
    void dedupLimitAndValuesFollowTheStoredEdges() {
        List<Object> values = gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').values('status')");
        List<StoredEdgeValue> stored = edgeStore.getEdges(HUB, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT);
        assertEquals(stored.size(), values.size(), "one value per stored edge");

        List<Object> limited = gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').limit(3)");
        assertEquals(3, limited.size(), "limit(3) returns three edges");

        List<Object> limitedValues = gremlin.execute(
                "g.V('" + HUB + "','Account').outE('Payment').limit(3).values('status')");
        assertEquals(3, limitedValues.size(), "limit keeps the projection for the following steps");
        assertTrue(limitedValues.stream().allMatch(value -> value == null || value instanceof String),
                "a projected value is a status, not a serialized edge");
    }

    @Test
    void dedupCollapsesTheRepeatedTargets() {        List<Object> toTargets = gremlin.execute("g.V('" + HUB + "','Account').out('Payment').dedup()");
        Set<String> distinct = new LinkedHashSet<>();
        edgeStore.getEdges(HUB, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT)
                .forEach(edge -> distinct.add(String.valueOf(edge.otherVertexId())));
        assertEquals(distinct.size(), toTargets.size(), "dedup() keeps one traverser per distinct target");
    }

    /**
     * The same query, run against a store that evaluates the conditions and the cap while it
     * decodes and against one that hands the plain read back, must produce the same answer.
     *
     * <p>This is the test that keeps the pushdown honest: the engine may hand a condition or a cap
     * to the store as an optimisation, so both ways of reading have to agree - on the traversers,
     * on the subgraph a UI would draw, and on the counts.
     */
    @Test
    void pushedConditionsAndCapsAgreeWithThePlainRead() {
        GremlinGraphQuery plain = new GremlinGraphQuery(new PlainReadQuery(
                new AerospikeGraphQuery(vertexStore, edgeStore, schema)),
                NeighborVertexResolver.from(schema));

        List<String> queries = List.of(
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS')",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','PENDING')",
                "g.V('" + HUB + "','Account').outE('Payment').has('status')",
                "g.V('" + HUB + "','Account').outE('Payment').filter(status.equals('SUCCESS')).count()",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS').count()",
                "g.V('" + HUB + "','Account').outE('Payment').limit(3)",
                "g.V('" + HUB + "','Account').outE('Payment').limit(3).values('status')",
                "g.V('" + HUB + "','Account').outE('Payment').has('status','SUCCESS').limit(2)",
                "g.V('" + HUB + "','Account').bothE('Payment').has('status','SUCCESS').limit(4)",
                "g.V('" + HUB + "','Account').inE('Payment').has('status','SUCCESS')",
                "g.V('" + RING + "','Account').outE('AccountToIP').limit(1)");

        for (String query : queries) {
            assertEquals(describe(plain.execute(query)), describe(gremlin.execute(query)),
                    "plain read and pushdown disagree on: " + query);
            GremlinGraphQuery.Result pushed = gremlin.executeWithSubgraph(query);
            GremlinGraphQuery.Result plainResult = plain.executeWithSubgraph(query);
            assertEquals(describe(plainResult.values()), describe(pushed.values()),
                    "the subgraph run disagrees on: " + query);
            assertEquals(plainResult.subgraph().edges().size(), pushed.subgraph().edges().size(),
                    "the drawn edges disagree on: " + query);
        }
    }

    /** The elements of a result, as text, so two runs can be compared without sharing objects. */
    private static List<String> describe(List<Object> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof GremlinEdge edge) {
                out.add(edge.id() + " " + edge.side() + " " + edge.otherVertexId());
            } else {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    /**
     * The same store with the pushdown switched off: the engine then applies the conditions and the
     * cap itself, which is what a backend without the capability does.
     */
    private static final class PlainReadQuery implements GraphQuery {
        private final GraphQuery delegate;

        PlainReadQuery(GraphQuery delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supportsEdgeReadPushdown() {
            return false;
        }

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            return delegate.readVertex(vertexId, vertexName, propertyNames);
        }

        @Override
        public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                    List<String> propertyNames) {
            return delegate.readVertices(vertexName, vertexIds, propertyNames);
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            return delegate.readEdges(vertexId, vertexName, edgeName, direction, propertyNames);
        }

        @Override
        public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                          List<Object> vertexIds, List<String> propertyNames) {
            return delegate.readEdgesBatch(edgeName, direction, vertexIds, propertyNames);
        }

        @Override
        public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                             List<String> propertyNames) {
            return delegate.readAllEdgesBatch(vertexIds, direction, propertyNames);
        }

        @Override
        public long countEdges(Object vertexId, String vertexName, String edgeName, EdgeSide direction) {
            return delegate.countEdges(vertexId, vertexName, edgeName, direction);
        }
    }

    @Test
    void cypherProjectionAndAggregationMatchTheStoredValues() {
        CypherResult grouped = cypher.execute(
                "MATCH (a:Account {id: '" + HUB + "'})-[p:Payment]->(b:Account) RETURN p.status, count(*)",
                Map.of());
        Map<String, Long> fromCypher = new HashMap<>();
        for (Map<String, Object> row : grouped.rows()) {
            fromCypher.put(String.valueOf(row.get("p.status")), ((Number) row.get("count(*)")).longValue());
        }
        Map<String, Long> fromStorage = new HashMap<>();
        edgeStore.getEdges(HUB, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT).forEach(edge -> {
            EdgePropertyValue status = edge.properties().get(statusPropertyId);
            fromStorage.merge(String.valueOf(status == null ? null : status.value()), 1L, Long::sum);
        });

        assertEquals(fromStorage, fromCypher, "the grouped counts match the stored statuses");
        assertFalse(fromCypher.isEmpty(), "the hub has payments to group");
    }

    // ------------------------------------------------------------------ helpers

    private int paymentCount(String account) {
        return edgeStore.getEdges(account, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_OUT).size()
                + edgeStore.getEdges(account, PAYMENT_SCHEMA_ID, AerospikeEdgeStore.EDGE_DIR_IN).size();
    }

    private static List<GremlinEdge> edges(List<Object> elements) {
        List<GremlinEdge> out = new ArrayList<>();
        for (Object element : elements) {
            assertTrue(element instanceof GremlinEdge, "expected an edge, got " + element);
            out.add((GremlinEdge) element);
        }
        return out;
    }

    private static Set<String> ids(List<?> edges) {
        Set<String> out = new LinkedHashSet<>();
        for (Object edge : edges) {
            out.add(String.valueOf(edge instanceof GremlinEdge gremlin ? gremlin.id()
                    : ((StoredEdgeValue) edge).edgeId()));
        }
        return out;
    }

    private static Set<String> otherEndpoints(List<?> edges) {
        Set<String> out = new LinkedHashSet<>();
        for (Object edge : edges) {
            out.add(String.valueOf(edge instanceof GremlinEdge gremlin ? gremlin.otherVertexId()
                    : ((StoredEdgeValue) edge).otherVertexId()));
        }
        return out;
    }

    private List<String> statesOf(List<?> edges) {
        List<String> out = new ArrayList<>();
        for (Object edge : edges) {
            Object status;
            if (edge instanceof GremlinEdge gremlin) {
                status = gremlin.properties().get("status");
            } else {
                EdgePropertyValue stored = ((StoredEdgeValue) edge).properties().get(statusPropertyId);
                status = stored == null ? null : stored.value();
            }
            out.add(String.valueOf(status));
        }
        return out;
    }

    /**
     * Aerospike is the transactional store, and a question about a whole type is not one it answers:
     * a record is read by its key. The traversal says so before touching the store, so the caller is
     * told what is missing (and which store has it) instead of getting an empty answer.
     */
    @Test
    void aClassQuestionIsRefusedByTheTransactionalStoreAndSaysWhichStoreAnswersIt() {
        QueryException vertices = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('Account').count()"));
        assertTrue(vertices.getMessage().contains("starts from a whole type"), vertices.getMessage());
        assertTrue(vertices.getMessage().contains("RocksDB-Edge"), vertices.getMessage());

        QueryException edges = assertThrows(QueryException.class,
                () -> gremlin.execute("g.E('Payment').count()"));
        assertTrue(edges.getMessage().contains("starts from a whole type"), edges.getMessage());
        assertTrue(edges.getMessage().contains("RocksDB-Edge"), edges.getMessage());

        // a query that names its ids is still answered here: only a class is out of reach
        assertEquals(List.of(76L), gremlin.execute("g.V('" + HUB + "','Account').outE('Payment').count()"));
    }

    private int schemaIdOf(String edgeName) {
        return schema.edgeSchema(edgeName).id();
    }

    private String vertexTypeOf(String edgeName) {
        return schema.edgeSchema(edgeName).target().vertexName();
    }
}
