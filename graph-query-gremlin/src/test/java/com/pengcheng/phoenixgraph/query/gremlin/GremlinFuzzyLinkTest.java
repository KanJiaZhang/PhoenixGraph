package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.fuzzy.FuzzySchema;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code fuzzyLink} step: starting a traversal from a context, and from an account that has one.
 *
 * <p>The graph holds three accounts whose details differ but agree on fragments, two link nodes
 * (pattern 0 for the two that share habits, pattern 1 for a machine-made email), and two payments
 * from a linked account to a receiver.
 */
class GremlinFuzzyLinkTest {

    private static final String CONTEXT_A = "{\"email\":\"19880101@mail.ru\",\"phone\":\"+8613800138000\","
            + "\"ip\":\"10.20.31.7\",\"username\":\"zhang1988\",\"cardNumber\":\"6217000010001234\"}";
    private static final String CONTEXT_B = "{\"email\":\"19790101@mail.ru\",\"phone\":\"+8613900139000\","
            + "\"ip\":\"10.20.88.4\",\"username\":\"zhang1979\",\"cardNumber\":\"6217000099998888\"}";

    private static final String LINK_A = linkOf(CONTEXT_A);
    private static final String HONEST_CONTEXT =
            "{\"email\":\"li.wei@qq.com\",\"phone\":\"+8613700137000\",\"ip\":\"172.16.5.9\","
                    + "\"username\":\"liwei\"}";
    private static final String LINK_HONEST = FuzzyDemo.SHARED_HABITS.linkVertexId(
            com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts.fromJson(HONEST_CONTEXT));
    // the second pattern puts the shape of the machine-made email in the key, so the id is computed
    private static final String LINK_MACHINE = FuzzyDemo.MACHINE_MAIL.linkVertexId(
            com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts.fromJson(
                    "{\"email\":\"19880101@mail.ru\",\"ip\":\"10.20.31.7\","
                            + "\"cardNumber\":\"6217000010001234\"}"));

    private static final Map<String, Map<String, Object>> VERTICES = Map.of(
            LINK_A, linkProperties(habitsKey(CONTEXT_A), 0),
            LINK_HONEST, linkProperties(habitsKey(HONEST_CONTEXT), 0),
            LINK_MACHINE, linkProperties(FuzzyDemo.MACHINE_MAIL.key(
                    com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts.fromJson(
                            "{\"email\":\"19880101@mail.ru\",\"ip\":\"10.20.31.7\",\"cardNumber\":\"6217000010001234\"}")), 1),
            "a1", account("Zhang", "zhang1988", "19880101@mail.ru", "+8613800138000", "10.20.31.7"),
            "a2", account("Zhang", "zhang1979", "19790101@mail.ru", "+8613900139000", "10.20.88.4"),
            "a3", account("Li", "liwei", "li.wei@qq.com", "+8613700137000", "172.16.5.9"),
            "r1", Map.of("firstName", "Receiver"));

    /** FuzzyLinkedTo edges, keyed by the link vertex they leave from. */
    private static final Map<String, List<EdgeQueryResult>> LINK_EDGES = Map.of(
            LINK_A, List.of(edge(FuzzySchema.LINK_EDGE, "e-a1", "a1"), edge(FuzzySchema.LINK_EDGE, "e-a2", "a2")),
            LINK_HONEST, List.of(edge(FuzzySchema.LINK_EDGE, "e-a3", "a3")),
            LINK_MACHINE, List.of(edge(FuzzySchema.LINK_EDGE, "e-a1b", "a1")));

    /** Payment edges, keyed by the account they leave from. */
    private static final Map<String, List<EdgeQueryResult>> PAYMENTS = Map.of(
            "a1", List.of(edge("Payment", "p1", "r1"), edge("Payment", "p2", "r1")));

    @Test
    void twoAccountsThatShareFragmentsProduceTheSameNode() {
        assertEquals(LINK_A, linkOf(CONTEXT_B),
                "different emails, phones and networks, but the same provider, country, /16 and name word");
    }

    @Test
    void aContextFindsTheAccountsThatShareItsFragments() {
        List<Object> accounts = gremlin().execute("g.V().fuzzyLink(0, '" + CONTEXT_A + "')"
                + ".out('" + FuzzySchema.LINK_EDGE + "')");

        assertEquals(List.of("a1", "a2"), ids(accounts), "the node groups the accounts that share it");
    }

    @Test
    void anAccountAsksWhichGroupItBelongsTo() {
        List<Object> links = gremlin().execute("g.V('a1','Account').fuzzyLink(0)");

        assertEquals(1, links.size());
        GremlinVertex link = (GremlinVertex) links.get(0);
        assertEquals(LINK_A, link.id(), "the context came from the account's own properties");
        assertEquals(habitsKey(CONTEXT_A), link.properties().get("key"));
    }

    @Test
    void theLinkExplainsItself() {
        List<Object> keys = gremlin().execute("g.V().fuzzyLink(0, '" + CONTEXT_A + "').values('key')");

        assertEquals(List.of(habitsKey(CONTEXT_A)), keys, "the projection after the step is honoured");
    }

    @Test
    void theQueryWalksFromTheLinkToTheMoney() {
        GremlinGraphQuery.Result result = gremlin().executeWithSubgraph(
                "g.V().fuzzyLink(0, '" + CONTEXT_A + "').out('" + FuzzySchema.LINK_EDGE
                        + "').outE('Payment').otherV()");

        assertEquals(List.of("r1", "r1"), ids(result.values()), "both payments end at the receiver");
        assertTrue(result.subgraph().vertices().stream().anyMatch(v -> LINK_A.equals(v.id())),
                "the drawn graph starts at the link node");
        assertEquals(4, result.subgraph().edges().size(), "two link edges and two payments");
    }

    @Test
    void aSecondPatternIsFoundTheSameWay() {
        List<Object> accounts = gremlin().execute(
                "g.V().fuzzyLink(1, '{\"email\":\"19880101@mail.ru\",\"ip\":\"10.20.31.7\","
                        + "\"cardNumber\":\"6217000010001234\"}').out('" + FuzzySchema.LINK_EDGE + "')");

        assertEquals(List.of("a1"), ids(accounts));
    }

    @Test
    void aPatternThatDoesNotApplyFindsNothing() {
        assertEquals(List.of(), gremlin().execute("g.V().fuzzyLink(0, '{\"ip\":\"10.20.31.7\"}')"),
                "no email domain, no link: the pattern needs all of its parts");
    }

    @Test
    void aKeyNobodyProducedHasNoNodeYet() {
        assertEquals(List.of(), gremlin().execute(
                        "g.V().fuzzyLink(0, '{\"email\":\"new@qq.com\",\"phone\":\"+8613800138999\","
                                + "\"ip\":\"172.20.1.1\",\"username\":\"wang9999\"}')"),
                "a link node is created by the accounts that belong to it");
    }

    @Test
    void anUnknownPatternSaysWhatExists() {
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin().execute("g.V().fuzzyLink(9, '{\"ip\":\"10.20.31.7\"}')"));

        assertTrue(error.getMessage().contains("Unknown fuzzy pattern 9"), error.getMessage());
        assertTrue(error.getMessage().contains("email domain"), "the message lists the patterns that exist");
    }

    @Test
    void aStepWithoutAPatternIdSaysHowToWriteIt() {
        QueryException error = assertThrows(QueryException.class, () -> gremlin().execute("g.V().fuzzyLink()"));

        assertTrue(error.getMessage().contains("fuzzyLink(patternId)"), error.getMessage());
    }

    @Test
    void aContextThatIsNotAJsonObjectIsRejected() {
        QueryException error = assertThrows(QueryException.class,
                () -> gremlin().execute("g.V().fuzzyLink(0, '\"just-a-string\"')"));

        assertTrue(error.getMessage().contains("JSON object"), error.getMessage());
    }

    // --- the graph this test walks ---

    private static GremlinGraphQuery gremlin() {
        GraphQuery graphQuery = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                Map<String, Object> stored = VERTICES.get(String.valueOf(vertexId));
                if (stored == null) {
                    return null;
                }
                Map<String, Object> projected = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : stored.entrySet()) {
                    if (propertyNames == null || propertyNames.isEmpty()
                            || propertyNames.contains(entry.getKey())) {
                        projected.put(entry.getKey(), entry.getValue());
                    }
                }
                return new VertexQueryResult(labelOf(String.valueOf(vertexId)), vertexId, projected, 1L, 2L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(readVertex(id, vertexName, propertyNames));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                if (direction == EdgeSide.IN) {
                    return List.of(); // this test only walks out of the link and out of the accounts
                }
                List<EdgeQueryResult> found = (FuzzySchema.LINK_EDGE.equals(edgeName) ? LINK_EDGES : PAYMENTS)
                        .get(String.valueOf(vertexId));
                return found == null ? List.of() : found;
            }

            @Override
            public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                              List<Object> vertexIds,
                                                              List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(readEdges(id, null, edgeName, direction, propertyNames));
                }
                return out;
            }

            @Override
            public List<List<EdgeQueryResult>> readAllEdgesBatch(List<Object> vertexIds, EdgeSide direction,
                                                                 List<String> propertyNames) {
                List<List<EdgeQueryResult>> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    List<EdgeQueryResult> edges = new ArrayList<>();
                    edges.addAll(readEdges(id, null, FuzzySchema.LINK_EDGE, direction, propertyNames));
                    edges.addAll(readEdges(id, null, "Payment", direction, propertyNames));
                    out.add(edges);
                }
                return out;
            }
        };
        NeighborVertexResolver resolver = NeighborVertexResolver.of(
                Map.of(FuzzySchema.LINK_EDGE, "Account", "Payment", "Account"),
                Map.of(FuzzySchema.LINK_EDGE, FuzzySchema.LINK_VERTEX, "Payment", "Account"));
        FuzzyPatternRegistry patterns = FuzzyDemo.demoRegistry();
        return new GremlinGraphQuery(graphQuery, resolver).fuzzyPatterns(patterns);
    }

    private static Map<String, Object> linkProperties(String key, int patternId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("key", key);
        properties.put(FuzzySchema.PATTERN_ID_PROPERTY, patternId);
        return properties;
    }

    private static Map<String, Object> account(String firstName, String username, String email,
                                              String phone, String ip) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("firstName", firstName);
        properties.put("username", username);
        properties.put("email", email);
        properties.put("phone", phone);
        properties.put("ip", ip);
        return properties;
    }

    private static EdgeQueryResult edge(String edgeName, String edgeId, String otherVertexId) {
        return new EdgeQueryResult(edgeName, edgeId, EdgeSide.OUT, otherVertexId, Map.of(), 1L, 2L);
    }

    private static String linkOf(String contextJson) {
        return FuzzyDemo.SHARED_HABITS.linkVertexId(
                com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts.fromJson(contextJson));
    }

    /** The key the first demo pattern produces for a context (the shape of its regex is part of it). */
    private static String habitsKey(String contextJson) {
        return FuzzyDemo.SHARED_HABITS.key(
                com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts.fromJson(contextJson));
    }

    private static String labelOf(String vertexId) {
        return vertexId.startsWith("fl_") ? FuzzySchema.LINK_VERTEX : "Account";
    }

    private static List<String> ids(List<Object> elements) {
        List<String> out = new ArrayList<>(elements.size());
        for (Object element : elements) {
            out.add(String.valueOf(((GremlinElement) element).id()));
        }
        return out;
    }
}
