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
                "PAYMENT_VERTEX_SET", "http_demo_v3"));
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
        assertEquals(7, summary.largestNodePerPattern().get(0),
                "the first ring is seven accounts: six zhangs and one xia of the same name shape");
        assertEquals(4, summary.largestNodePerPattern().get(1), "the second ring is four accounts");
        assertEquals(2, summary.largestNodePerPattern().get(2),
                "the sessions that share a /16 and a reason are one group of two");
        assertEquals(2, summary.largestNodePerPattern().get(3),
                "pattern 3 links the sessions whose dispute came from the login address");
        assertEquals(2, summary.largestNodePerPattern().get(4),
                "pattern 4 links the mobile sessions tied by address, cookie or reason");
        assertEquals(3, summary.largestNodePerPattern().get(5),
                "pattern 5 links the sessions that agree on three field values");
        assertTrue(summary.nodes() >= 2, "the rings have their own nodes: " + summary);
        assertEquals((FuzzyLinkDemoLoader.memberAccounts() - 1) * 2, summary.payments(),
                "every member of a ring pays twice, the receiver does not (the event sessions pay nothing)");
    }

    @Test
    void aContextFindsTheAccountsThatShareItsFragments() {
        List<Object> accounts = gremlin.execute("g.V().fuzzyLink(0, '"
                + contextJson(FuzzyLinkDemoLoader.firstRingContext()) + "').out('FuzzyLinkedTo')");

        Set<String> ids = idsOf(accounts);
        assertEquals(Set.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6", "fz_a7"), ids,
                "the first ring (zhangs and the xia), and nobody else");
        assertFalse(ids.contains("fz_b1"), "the other ring shares no fragment of this pattern");
        assertFalse(ids.contains("fz_c1"), "an honest account shares nothing");
    }

    @Test
    void theNameWordItselfDoesNotDecideTheLink() {
        // the first pattern keeps the formula of its regular expression instead of what it matched,
        // so zhang1988 (the ring) and xia1999 (a value of the same shape) are on one node
        String xia = "{\"email\":\"19990101@mail.ru\",\"phone\":\"+8613800138001\","
                + "\"ip\":\"10.20.77.9\",\"username\":\"xia1999\"}";

        List<Object> accounts = gremlin.execute("g.V().fuzzyLink(0, '" + xia + "').out('FuzzyLinkedTo')");

        assertEquals(Set.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6", "fz_a7"), idsOf(accounts),
                "the ring, reached by a name word of the same shape");
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
        assertEquals("mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, link.properties().get("key"));
        assertEquals(0, ((Number) link.properties().get("patternId")).intValue());
    }

    @Test
    void theLinkNodeAgreesWithTheAccountsThatPointAtIt() {
        String linkId = FuzzyDemo.SHARED_HABITS.linkVertexId(FuzzyLinkDemoLoader.firstRingContext());

        List<Object> fromTheLink = gremlin.execute("g.V('" + linkId + "','FuzzyLink').out('FuzzyLinkedTo')");
        List<Object> fromEachAccount = new ArrayList<>();
        for (String account : List.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6", "fz_a7")) {
            fromEachAccount.addAll(gremlin.execute("g.V('" + account + "','Account').fuzzyLink(0)"));
        }

        assertEquals(7, fromTheLink.size(), "the node knows its members: six zhangs and one xia");
        assertEquals(Set.of("fz_a1", "fz_a2", "fz_a3", "fz_a4", "fz_a5", "fz_a6", "fz_a7"), idsOf(fromTheLink));
        assertEquals(7, fromEachAccount.size(), "and every member finds the node");
        assertEquals(Set.of(linkId), idsOf(fromEachAccount), "all seven land on the same node");
    }

    @Test
    void theQueryWalksFromThePatternToTheMoneyAndTheReceiver() {
        GremlinGraphQuery.Result result = gremlin.executeWithSubgraph("g.V().fuzzyLink(0, '"
                + contextJson(FuzzyLinkDemoLoader.firstRingContext())
                + "').out('FuzzyLinkedTo').outE('Payment').otherV()");

        assertEquals(14, result.values().size(), "seven members, two payments each");
        assertTrue(result.values().stream().allMatch(v -> RECEIVER.equals(((GremlinVertex) v).id())),
                "every payment ends at the collection account");
        assertEquals(7, result.subgraph().edges().stream()
                .filter(edge -> edge.label().equals("FuzzyLinkedTo")).count(), "the drawn graph shows the ring");
        assertEquals(14, result.subgraph().edges().stream()
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

        assertEquals("mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, fromTheLoader);
        assertEquals(fromTheLoader, again, "the same context always lands on the same node");
    }

    /**
     * The event list in, the group out: the sequence pattern through the engine, over the seeded storage.
     * The list is the one the loader wrote the links for, so the query finds the node it produced.
     */
    @Test
    void theEventSessionsAreLinkedByTheirSequence() {
        String[] session = FuzzyLinkDemoLoader.eventSessions().get(0);
        String json = FuzzyLinkDemoLoader.eventsJson(session);
        List<Object> links = gremlin.execute("g.V().fuzzyLink(2, '" + json + "').values('id')");
        assertEquals(List.of("fl_2_10.122_notmycard"), links.stream().map(String::valueOf).toList(),
                "the event list evaluates to the pattern's link node");

        List<Object> members = gremlin.execute("g.V('fl_2_10.122_notmycard','FuzzyLink')"
                + ".out('FuzzyLinkedTo').values('id')");
        assertEquals(Set.of("ev_a1", "ev_a2"), new LinkedHashSet<>(members.stream()
                .map(String::valueOf).toList()), "the accounts whose session looks the same");
    }

    /**
     * Pattern 3 reads a cross-step condition ({@code Dispute.ip == Login.ip}) and a payment that may happen
     * before the balance check. {@code ev_a6} and {@code ev_a9} share the /16 and the reason and both dispute
     * from the login address, so they are one group; {@code ev_a7} disputes from elsewhere and pattern 3
     * leaves it out.
     */
    @Test
    void theDisputeAddressConditionDecidesPatternThree() {
        String json = sessionJson(5); // ev_a6
        List<Object> links = gremlin.execute("g.V().fuzzyLink(3, '" + json + "').values('id')");
        assertEquals(List.of("fl_3_10.141_notmycard"), links.stream().map(String::valueOf).toList());

        List<Object> members = gremlin.execute("g.V('fl_3_10.141_notmycard','FuzzyLink')"
                + ".out('FuzzyLinkedTo').values('id')");
        assertEquals(Set.of("ev_a6", "ev_a9"), ids(members), "ev_a7's dispute came from another address");

        // ev_a7 does not produce pattern 3's link at all: the condition is not satisfied
        assertEquals(List.of(), gremlin.execute("g.V().fuzzyLink(3, '" + sessionJson(6) + "').values('id')"));
    }

    /**
     * Pattern 4 adds {@code Login.type=='Mobile'} and the three-way alternative. The same {@code ev_a6} is on
     * its own group, and this time {@code ev_a7} joins it (the reason is NotMyCard) while {@code ev_a9} - a web
     * login - is left out; {@code ev_a8} is a Fraud dispute tied to the payment by cookie, so it forms its own
     * group.
     */
    @Test
    void theMobileAndAlternativeConditionsDecidePatternFour() {
        List<Object> members = gremlin.execute("g.V('fl_4_10.141_notmycard','FuzzyLink')"
                + ".out('FuzzyLinkedTo').values('id')");
        assertEquals(Set.of("ev_a6", "ev_a7"), ids(members),
                "ev_a7 is tied by NotMyCard, ev_a9 is not a mobile login");

        List<Object> fraud = gremlin.execute("g.V('fl_4_10.141_fraud','FuzzyLink')"
                + ".out('FuzzyLinkedTo').values('id')");
        assertEquals(Set.of("ev_a8"), ids(fraud), "the Fraud dispute is tied by the cookie it shares");
    }

    private static String sessionJson(int index) {
        return FuzzyLinkDemoLoader.eventsJson(FuzzyLinkDemoLoader.eventSessions().get(index));
    }

    /**
     * Pattern 5's step value is the anyFields UDF: it looks at the login, the payment and the dispute of a
     * match and keeps the fields they all agree on. {@code ev_a1} agrees on cookie, ip and type (and its
     * session is ordered within the windows), so the link's key names those fields; {@code ev_b2} agrees on too
     * few of them and {@code ev_b3}'s payment is past the 50s window, so neither produces a link.
     */
    @Test
    void theAnyFieldsUdfLinksSessionsByTheirSharedFields() {
        List<Object> links = gremlin.execute("g.V().fuzzyLink(5, '" + sessionJson(0) + "').values('id')");
        assertEquals(List.of("fl_5_10.122_cookie_ip_type"), links.stream().map(String::valueOf).toList());

        List<Object> members = gremlin.execute("g.V('fl_5_10.122_cookie_ip_type','FuzzyLink')"
                + ".out('FuzzyLinkedTo').values('id')");
        assertEquals(Set.of("ev_a1", "ev_a2", "ev_a5"), ids(members),
                "the sessions whose login, payment and dispute agree on the same three fields");

        assertEquals(List.of(), gremlin.execute("g.V().fuzzyLink(5, '" + sessionJson(10) + "').values('id')"),
                "ev_b2 agrees on only one field");
        assertEquals(List.of(), gremlin.execute("g.V().fuzzyLink(5, '" + sessionJson(11) + "').values('id')"),
                "ev_b3's payment is 60 seconds after the balance check");
    }

    private static Set<String> ids(List<Object> elements) {
        return new LinkedHashSet<>(elements.stream().map(element -> String.valueOf(
                element instanceof GremlinVertex vertex ? vertex.id()
                        : element instanceof GremlinEdge edge ? edge.otherVertexId() : element)).toList());
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
