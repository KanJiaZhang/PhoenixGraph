package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyKey;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fuzzy linking against a real Aerospike: does a context find the accounts that share its fragments,
 * and does a query walk from there to the money and the receiver?
 *
 * <p>The demo data is seeded by the test itself (accounts that share only fragments, the link nodes
 * the patterns produce, and two payments per member), so the assertions are about the feature and
 * not about whatever else is in the set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FuzzyLinkIntegrationTest {

    private static final String RECEIVER = "fz_receiver";
    private static final int DEGREE_LIMIT = 1000;

    private AerospikeClient client;
    private GraphSchema schema;
    private AerospikeVertexStore vertexStore;
    private AerospikeEdgeStore edgeStore;
    private GremlinGraphQuery gremlin;
    private FuzzyPatternRegistry patterns;
    private FuzzyLinkDemoLoader.Summary summary;

    @BeforeAll
    void loadTheDemoGraph() {
        IntegrationConfig config = IntegrationConfig.fromEnv();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        schema = loadSchema();
        String vertexSet = System.getProperty("payment.vertexSet", System.getenv().getOrDefault(
                "PAYMENT_VERTEX_SET", "http_demo_v1"));
        vertexStore = new AerospikeVertexStore(client, config.namespace(), vertexSet);
        edgeStore = new AerospikeEdgeStore(client, config.namespace(), vertexSet, schema);
        patterns = FuzzyDemo.demoRegistry();
        summary = new FuzzyLinkDemoLoader(vertexStore, edgeStore, schema, patterns, vertexSet).load();
        System.out.println("[fuzzy] seeded: " + summary);
        NeighborVertexResolver resolver = NeighborVertexResolver.from(schema);
        gremlin = new GremlinGraphQuery(new AerospikeGraphQuery(vertexStore, edgeStore, schema), resolver)
                .fuzzyPatterns(patterns);
    }

    @AfterAll
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void theSchemaDeclaresWhatLinkingNeeds() {
        assertTrue(com.pengcheng.phoenixgraph.fuzzy.FuzzySchema.isSupported(schema));
        assertEquals(DEGREE_LIMIT, schema.edgeSchema("FuzzyLinkedTo").maxOutgoingEdges(),
                "a link node groups many accounts");
        assertEquals(DEGREE_LIMIT, schema.edgeSchema("FuzzyLinkedTo").maxIncomingEdges(),
                "an account may belong to many links");
        assertEquals("FuzzyLink", schema.edgeSchema("FuzzyLinkedTo").sourceVertexName());
        assertEquals("Account", schema.edgeSchema("FuzzyLinkedTo").targetVertexName());
    }

    @Test
    void theSeedPlantedTwoRings() {
        assertEquals(6, summary.largestNodePerPattern().get(0), "the first ring is six accounts");
        assertEquals(4, summary.largestNodePerPattern().get(1), "the second ring is four accounts");
        assertTrue(summary.nodes() >= 2, "the rings have their own nodes: " + summary);
        assertEquals((summary.accounts() - 1) * 2, summary.payments(),
                "every member pays twice, the receiver does not");
    }

    @Test
    void aContextFindsTheAccountsThatShareItsFragments() {
        List<Object> accounts = gremlin.execute("g.V().fuzzyLink(0, '"
                + contextJson(FuzzyLinkDemoLoader.firstRingContext()) + "').out('FuzzyLinkedTo')");

        Set<String> ids = idsOf(accounts);
        assertEquals(Set.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6"), ids,
                "the first ring, and nobody else");
        assertFalse(ids.contains("fz_b1"), "the other ring shares no fragment of this pattern");
        assertFalse(ids.contains("fz_c1"), "an honest account shares nothing");
    }

    @Test
    void theOtherPatternFindsTheOtherRing() {
        List<Object> accounts = gremlin.execute("g.V().fuzzyLink(1, '{"
                + "\"email\":\"44100101@qq.com\",\"ip\":\"10.30.40.11\",\"cardNumber\":\"6217000090009001\""
                + "}').out('FuzzyLinkedTo')");

        assertEquals(Set.of("fz_b1", "fz_b2", "fz_b3", "fz_b4"), idsOf(accounts));
    }

    @Test
    void anAccountAsksWhichGroupItBelongsTo() {
        // the context is the vertex's own properties, so no context JSON is needed
        List<Object> links = gremlin.execute("g.V('fz_a1','Account').fuzzyLink(0)");

        assertEquals(1, links.size());
        GremlinVertex link = (GremlinVertex) links.get(0);
        assertEquals("mail.ru_86_10.20_zhang", link.properties().get("key"));
        assertEquals(0, ((Number) link.properties().get("patternId")).intValue());
    }

    @Test
    void theLinkNodeAgreesWithTheAccountsThatPointAtIt() {
        String linkId = FuzzyDemo.SHARED_HABITS.linkVertexId(FuzzyLinkDemoLoader.firstRingContext());

        List<Object> fromTheLink = gremlin.execute("g.V('" + linkId + "','FuzzyLink').out('FuzzyLinkedTo')");
        List<Object> fromEachAccount = new ArrayList<>();
        for (String account : List.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6")) {
            fromEachAccount.addAll(gremlin.execute("g.V('" + account + "','Account').fuzzyLink(0)"));
        }

        assertEquals(6, fromTheLink.size(), "the node knows its members");
        assertEquals(Set.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6"), idsOf(fromTheLink));
        assertEquals(6, fromEachAccount.size(), "and every member finds the node");
        assertEquals(Set.of(linkId), idsOf(fromEachAccount), "all six land on the same node");
    }

    @Test
    void theQueryWalksFromThePatternToTheMoneyAndTheReceiver() {
        GremlinGraphQuery.Result result = gremlin.executeWithSubgraph("g.V().fuzzyLink(0, '"
                + contextJson(FuzzyLinkDemoLoader.firstRingContext())
                + "').out('FuzzyLinkedTo').outE('Payment').otherV()");

        assertEquals(12, result.values().size(), "six members, two payments each");
        assertTrue(result.values().stream().allMatch(v -> RECEIVER.equals(((GremlinVertex) v).id())),
                "every payment ends at the collection account");
        assertEquals(6, result.subgraph().edges().stream()
                .filter(edge -> edge.label().equals("FuzzyLinkedTo")).count(), "the drawn graph shows the ring");
        assertEquals(12, result.subgraph().edges().stream()
                .filter(edge -> edge.label().equals("Payment")).count());
    }

    @Test
    void thePaymentsCarryWhatTheQueryShouldSee() {
        List<Object> amounts = gremlin.execute("g.V().fuzzyLink(0, '"
                + contextJson(FuzzyLinkDemoLoader.firstRingContext())
                + "').out('FuzzyLinkedTo').outE('Payment').limit(3).values('amount')");

        assertEquals(3, amounts.size());
        assertTrue(amounts.stream().allMatch(amount -> amount instanceof Number), "amounts, not edges");
    }

    @Test
    void anUnrelatedContextFindsNoRing() {
        List<Object> nothing = gremlin.execute("g.V().fuzzyLink(0, '{"
                + "\"email\":\"nobody@example.org\",\"phone\":\"+447700900123\","
                + "\"ip\":\"203.0.113.9\",\"username\":\"nobody\"}')");

        assertEquals(List.of(), nothing, "no account produced that key");
    }

    @Test
    void aKeyIsStableAcrossReloads() {
        String fromTheLoader = FuzzyDemo.SHARED_HABITS.key(FuzzyLinkDemoLoader.firstRingContext());
        String again = FuzzyKey.of(FuzzyDemo.SHARED_HABITS.parsed()
                .key(FuzzyContexts.fromJson(contextJson(FuzzyLinkDemoLoader.firstRingContext()))));

        assertEquals("mail.ru_86_10.20_zhang", fromTheLoader);
        assertEquals(fromTheLoader, again, "the same context always lands on the same node");
    }

    private static Set<String> idsOf(List<Object> elements) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object element : elements) {
            if (element instanceof GremlinVertex vertex) {
                ids.add(String.valueOf(vertex.id()));
            } else if (element instanceof GremlinEdge edge) {
                ids.add(String.valueOf(edge.otherVertexId()));
            }
        }
        return ids;
    }

    private static String contextJson(FuzzyContext context) {
        return FuzzyContexts.toJson(context);
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
