package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLink;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLinks;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.fuzzy.FuzzySchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the fuzzy linking demo: accounts that hide who they are but not how they behave.
 *
 * <p>Two rings are built out of the fragments a risk analyst would look for. The first shares an
 * email provider, a country, a {@code /16} network and user names that start with the same word; the
 * second hides behind machine-made email addresses on one {@code /24} network and a single card BIN.
 * Every member has a different account, a different phone number and a different card number, so
 * nothing links them at a glance - the patterns do.
 *
 * <p>The loader writes the accounts, the pattern vertices, the link vertices with the key each
 * pattern produced, and the edge from every link to its members. It also makes each member pay the
 * same merchant, which is what the demo query walks: pattern -> link -> accounts -> payments ->
 * receiver.
 */
public final class FuzzyLinkDemoLoader {

    /** The accounts of the first ring: same provider, country, /16 and name word. */
    private static final String[][] RING_SHARED_HABITS = {
            {"fz_a1", "zhang1988", "19880101@mail.ru", "+8613800138001", "6217000010001111", "10.20.31.7"},
            {"fz_a2", "zhang1979", "19790101@mail.ru", "+8613900139002", "6217000020002222", "10.20.88.4"},
            {"fz_a3", "zhang1990", "19900101@mail.ru", "+8613700137003", "6217000030003333", "10.20.45.9"},
            {"fz_a4", "zhang1972", "19720101@mail.ru", "+8613600136004", "6217000040004444", "10.20.12.1"},
            {"fz_a5", "zhang1995", "19950101@mail.ru", "+8613500135005", "6217000050005555", "10.20.77.2"},
            {"fz_a6", "zhang1968", "19680101@mail.ru", "+8613400134006", "6217000060006666", "10.20.99.3"}};

    /** The accounts of the second ring: one /24 network, one card BIN, digits-only email names. */
    private static final String[][] RING_MACHINE_MAIL = {
            {"fz_b1", "user4410", "44100101@qq.com", "+8612800128001", "6217000090009001", "10.30.40.11"},
            {"fz_b2", "user4411", "44100102@qq.com", "+8612800128002", "6217000090009002", "10.30.40.12"},
            {"fz_b3", "user4412", "44100103@qq.com", "+8612800128003", "6217000090009003", "10.30.40.13"},
            {"fz_b4", "user4413", "44100104@qq.com", "+8612800128004", "6217000090009004", "10.30.40.14"}};

    /** Accounts that share nothing: the control group a query must not return. */
    private static final String[][] HONEST = {
            {"fz_c1", "liwei", "li.wei@163.com", "+8613700137001", "6222020011110001", "172.16.5.9"},
            {"fz_c2", "wangfang", "wangfang@gmail.com", "+8613600136002", "6222020011110002", "172.16.6.10"},
            {"fz_c3", "chenyu", "chen.yu@outlook.com", "+8613500135003", "6222020011110003", "172.16.7.11"},
            {"fz_c4", "zhaolei", "zhao.lei@126.com", "+8613400134004", "6222020011110004", "172.16.8.12"},
            {"fz_c5", "sunqi", "sun.qi@qq.com", "+8613300133005", "6222020011110005", "172.16.9.13"},
            {"fz_c6", "zhouyan", "zhou.yan@mail.ru", "+8613200132006", "6222020011110006", "172.31.9.14"}};

    /** The merchant every member of a ring pays, so the demo query has somewhere to arrive. */
    private static final String RECEIVER = "fz_receiver";

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final FuzzyPatternRegistry patterns;
    private final String vertexSet;

    public FuzzyLinkDemoLoader(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                               GraphSchema schema, FuzzyPatternRegistry patterns, String vertexSet) {
        this.vertexStore = vertexStore;
        this.edgeStore = edgeStore;
        this.schema = schema;
        this.patterns = patterns;
        this.vertexSet = vertexSet;
    }

    /**
     * What the loader wrote. {@code accounts} is the accounts written (members and the receiver),
     * {@code nodes} counts the distinct link vertices and
     * {@code largestNodePerPattern} the biggest group each pattern produced - which is the size of
     * the ring, since a singleton link node is what an account that shares nothing gets.
     */
    public record Summary(int accounts, int nodes, int edges, int payments,
                          Map<Integer, Integer> largestNodePerPattern) {

        @Override
        public String toString() {
            return accounts + " accounts, " + nodes + " link nodes (" + largestNodePerPattern + "), "
                    + edges + " link edges, " + payments + " payments";
        }
    }

    /** Writes the demo into the graph, replacing whatever was there for these accounts. */
    public Summary load() {
        long now = System.currentTimeMillis();
        List<VertexUpsert> vertices = new ArrayList<>();
        Set<String> nodes = new LinkedHashSet<>();
        int linkEdges = 0;
        Map<Integer, Set<String>> nodesPerPattern = new LinkedHashMap<>();
        Map<Integer, Integer> perPatternEdges = new LinkedHashMap<>();

        vertices.add(patternVertex(0, now));
        vertices.add(patternVertex(1, now));
        for (String[] row : allAccounts()) {
            FuzzyContext context = contextOf(row);
            vertices.add(accountVertex(row, now));
            for (FuzzyLink link : FuzzyLinks.plan(patterns, context)) {
                vertices.add(linkVertex(link, now));
                edgeStore.upsertEdge(linkEdge(link, row[0], now));
                nodes.add(link.linkVertexId());
                linkEdges++;
                nodesPerPattern.computeIfAbsent(link.patternId(), k -> new LinkedHashSet<>())
                        .add(link.linkVertexId());
                perPatternEdges.merge(link.patternId(), 1, Integer::sum);
            }
        }
        vertexStore.upsertVertices(vertices);

        int payments = writePayments(now);
        Map<Integer, Integer> largest = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : nodesPerPattern.entrySet()) {
            Map<String, Integer> membersByNode = new LinkedHashMap<>();
            for (String[] row : allAccounts()) {
                for (FuzzyLink link : FuzzyLinks.plan(patterns, contextOf(row))) {
                    if (link.patternId() == entry.getKey()) {
                        membersByNode.merge(link.linkVertexId(), 1, Integer::sum);
                    }
                }
            }
            largest.put(entry.getKey(), membersByNode.values().stream().max(Integer::compare).orElse(0));
        }
        return new Summary(allAccounts().size() + 1, nodes.size(), linkEdges, payments, largest);
    }

    /** The context of a demo account, exactly as the loader stores it. */
    public static FuzzyContext contextOf(String[] row) {
        return FuzzyContexts.fromProperties(Map.of(
                "username", row[1], "email", row[2], "phoneNumber", row[3],
                "cardNumber", row[4], "ipAddress", row[5]));
    }

    /** The ids of the demo accounts, for a test that wants to check them. */
    public static List<String> accountIds() {
        List<String> ids = new ArrayList<>();
        for (String[] row : allAccounts()) {
            ids.add(row[0]);
        }
        ids.add(RECEIVER);
        return ids;
    }

    /** The account of the first ring, whose context the demo query uses. */
    public static FuzzyContext firstRingContext() {
        return contextOf(RING_SHARED_HABITS[0]);
    }

    private static List<String[]> allAccounts() {
        List<String[]> all = new ArrayList<>();
        all.addAll(List.of(RING_SHARED_HABITS));
        all.addAll(List.of(RING_MACHINE_MAIL));
        all.addAll(List.of(HONEST));
        return all;
    }

    private VertexUpsert accountVertex(String[] row, long now) {
        VertexSchema account = schema.vertexSchema("Account");
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        properties.put(property(account, "firstName"), new VertexPropertyValue(row[1], now));
        properties.put(property(account, "username"), new VertexPropertyValue(row[1], now));
        properties.put(property(account, "email"), new VertexPropertyValue(row[2], now));
        properties.put(property(account, "phoneNumber"), new VertexPropertyValue(row[3], now));
        properties.put(property(account, "cardNumber"), new VertexPropertyValue(row[4], now));
        properties.put(property(account, "ipAddress"), new VertexPropertyValue(row[5], now));
        properties.put(property(account, "accountStatus"), new VertexPropertyValue("ACTIVE", now));
        return new VertexUpsert(row[0], account, properties, now, now);
    }

    private VertexUpsert patternVertex(int patternId, long now) {
        VertexSchema pattern = schema.vertexSchema(FuzzySchema.PATTERN_VERTEX);
        var declared = patterns.require(patternId);
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        properties.put(property(pattern, FuzzySchema.PATTERN_ID_PROPERTY),
                new VertexPropertyValue(patternId, now));
        properties.put(property(pattern, "name"), new VertexPropertyValue(declared.name(), now));
        properties.put(property(pattern, "expression"), new VertexPropertyValue(declared.expression(), now));
        properties.put(property(pattern, "description"), new VertexPropertyValue(declared.description(), now));
        properties.put(property(pattern, "enabled"), new VertexPropertyValue(true, now));
        return new VertexUpsert(declared.vertexId(), pattern, properties, now, now);
    }

    private VertexUpsert linkVertex(FuzzyLink link, long now) {
        VertexSchema linkSchema = schema.vertexSchema(FuzzySchema.LINK_VERTEX);
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        properties.put(property(linkSchema, FuzzySchema.PATTERN_ID_PROPERTY),
                new VertexPropertyValue(link.patternId(), now));
        properties.put(property(linkSchema, FuzzySchema.KEY_PROPERTY),
                new VertexPropertyValue(link.key(), now));
        properties.put(property(linkSchema, FuzzySchema.PATTERN_PROPERTY),
                new VertexPropertyValue(link.patternText(), now));
        return new VertexUpsert(link.linkVertexId(), linkSchema, properties, now, now);
    }

    private EdgeUpsert linkEdge(FuzzyLink link, String accountId, long now) {
        EdgeSchema linkEdge = schema.edgeSchema(FuzzySchema.LINK_EDGE);
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        properties.put(property(linkEdge, FuzzySchema.PATTERN_ID_PROPERTY),
                new EdgePropertyValue(link.patternId(), now, now));
        properties.put(property(linkEdge, FuzzySchema.KEY_PROPERTY),
                new EdgePropertyValue(link.key(), now, now));
        return new EdgeUpsert(FuzzySchema.linkEdgeId(link.linkVertexId(), accountId), linkEdge,
                link.linkVertexId(), accountId, properties, now, now);
    }

    /** Every member of a ring pays the receiver twice, which is what the demo query follows. */
    private int writePayments(long now) {
        VertexSchema receiver = schema.vertexSchema("Account");
        Map<Integer, VertexPropertyValue> receiverProperties = new LinkedHashMap<>();
        receiverProperties.put(property(receiver, "firstName"),
                new VertexPropertyValue("Collection Account", now));
        receiverProperties.put(property(receiver, "accountStatus"),
                new VertexPropertyValue("ACTIVE", now));
        vertexStore.upsertVertices(List.of(new VertexUpsert(RECEIVER, receiver, receiverProperties, now, now)));

        EdgeSchema payment = schema.edgeSchema("Payment");
        List<EdgeUpsert> payments = new ArrayList<>();
        int index = 0;
        for (String[] row : allAccounts()) {
            for (int i = 0; i < 2; i++) {
                Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
                properties.put(property(payment, "status"),
                        new EdgePropertyValue("SUCCESS", now, now));
                properties.put(property(payment, "amount"),
                        new EdgePropertyValue(100.0 * (index + 1), now, now));
                properties.put(property(payment, "currency"), new EdgePropertyValue("CNY", now, now));
                payments.add(new EdgeUpsert("fz_pay_" + index, payment, row[0], RECEIVER, properties, now, now));
                index++;
            }
        }
        edgeStore.upsertEdges(payments);
        return payments.size();
    }

    private static int property(VertexSchema vertexSchema, String name) {
        VertexPropertySchema property = vertexSchema.property(name);
        if (property == null) {
            throw new IllegalStateException("Vertex type '" + vertexSchema.name() + "' has no property '" + name
                    + "', so the fuzzy demo cannot be loaded");
        }
        return property.id();
    }

    private static int property(EdgeSchema edgeSchema, String name) {
        EdgePropertySchema property = edgeSchema.property(name);
        if (property == null) {
            throw new IllegalStateException("Edge class '" + edgeSchema.name() + "' has no property '" + name + "'");
        }
        return property.id();
    }

    /** The demo patterns, so a caller can register them the way the loader assumed. */
    public static FuzzyPatternRegistry demoPatterns() {
        return FuzzyDemo.demoRegistry();
    }
}
