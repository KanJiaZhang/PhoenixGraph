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
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
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

    /**
     * The accounts of the first ring: same provider, country, /16 and name word.
     *
     * <p>{@code fz_a7} is the point of the first pattern's regular expression: its user name starts
     * with a different word ({@code xia} instead of {@code zhang}) but has the same shape (letters
     * before the digits), so it lands on the <em>same</em> link node as the zhangs. A node that only
     * ever held zhangs would not show what "a name written as letters" links.
     */
    private static final String[][] RING_SHARED_HABITS = {
            {"fz_a1", "zhang1988", "19880101@mail.ru", "+8613800138001", "6217000010001111", "10.20.31.7"},
            {"fz_a2", "zhang1979", "19790101@mail.ru", "+8613900139002", "6217000020002222", "10.20.88.4"},
            {"fz_a3", "zhang1990", "19900101@mail.ru", "+8613700137003", "6217000030003333", "10.20.45.9"},
            {"fz_a4", "zhang1972", "19720101@mail.ru", "+8613600136004", "6217000040004444", "10.20.12.1"},
            {"fz_a5", "zhang1995", "19950101@mail.ru", "+8613500135005", "6217000050005555", "10.20.77.2"},
            {"fz_a6", "zhang1968", "19680101@mail.ru", "+8613400134006", "6217000060006666", "10.20.99.3"},
            // a second name word of the same shape, so the node shows zhang and xia together; the
            // email name is not four digits, so the second pattern does not apply to it
            {"fz_a7", "xia1999", "xia1999@mail.ru", "+8613800138007", "6217000070007777", "10.20.66.8"}};

    /**
     * The event-sequence accounts: each one is a session, and the sequence patterns read it in time order.
     *
     * <p>Columns: id, login IP, dispute reason, the offsets in millis of the balance check, the payment and
     * the dispute from the login (the payment may sit before the balance check), an extra event the patterns
     * do not name, the device of the login, the address the dispute came from, the cookie on the payment and
     * the cookie on the dispute.
     *
     * <p>{@code ev_a1} and {@code ev_a2} are the same behaviour (a login, a balance check, a payment and a
     * dispute within a minute, on one /16, disputed as NotMyCard) and {@code ev_a3} is the same session on
     * another network; {@code ev_a4} has a step 102 seconds after the one before it (past every 100s window)
     * and {@code ev_a5} disputes for another reason. {@code ev_a6..ev_a9} are the sessions the two-sided-window
     * patterns separate: {@code ev_a6} satisfies both, {@code ev_a7} ties the dispute to the payment by
     * cookie (pattern 4 only), {@code ev_a8} ties a Fraud dispute by cookie (pattern 4 only) and
     * {@code ev_a9} logs in from the web (pattern 3 only). Their balance check and payment are around each
     * other, so the ordered pattern 2 does not apply to them at all. {@code ev_b1} is the session the
     * anyFields pattern links (login, payment and dispute agree on cookie, ip and type), {@code ev_b2} agrees
     * on too few fields and {@code ev_b3} puts 60 seconds between the balance check and the payment.
     *
     * <p>The event list is what the patterns read; it is not stored on the account, so a query passes it in.
     */
    private static final String[][] EVENT_SESSIONS = {
            {"ev_a1", "10.122.13.14", "NotMyCard", "1000/2000/3000", "yes", "Mobile", "10.122.13.14", "ck_a1", "ck_a1"},
            {"ev_a2", "10.122.13.99", "NotMyCard", "2000/3000/4000", "no", "Mobile", "10.122.13.99", "ck_a2", "ck_a2"},
            {"ev_a3", "10.130.7.14", "NotMyCard", "1000/2000/3000", "no", "Mobile", "10.130.7.14", "ck_a3", "ck_a3"},
            {"ev_a4", "10.122.13.50", "NotMyCard", "1000/102000/103000", "no", "Mobile", "10.122.13.50", "ck_a4", "ck_a4"},
            {"ev_a5", "10.122.13.51", "Fraud", "1000/2000/3000", "no", "Mobile", "10.122.13.51", "ck_a5", "ck_a5"},
            {"ev_a6", "10.141.20.30", "NotMyCard", "1000/500/3500", "no", "Mobile", "10.141.20.30", "ck_a6", "ck_a6"},
            {"ev_a7", "10.141.20.31", "NotMyCard", "1000/500/3500", "no", "Mobile", "10.99.99.99", "ck_a7", "ck_x7"},
            {"ev_a8", "10.141.20.32", "Fraud", "1000/500/3500", "no", "Mobile", "10.99.99.98", "ck_a8", "ck_a8"},
            {"ev_a9", "10.141.20.33", "NotMyCard", "1000/500/3500", "no", "Web", "10.141.20.33", "ck_a9", "ck_a9"},
            // pattern 5: the login, the payment and the dispute agree on the same three fields (cookie, ip, type),
            // so anyFields finds them; ev_b2 disagrees on two of the three and ev_b3 is 60s between the balance
            // check and the payment (past the 50s window)
            {"ev_b1", "10.155.30.10", "NotMyCard", "1000/2000/3000", "no", "Mobile", "10.155.30.10", "ck_b1", "ck_b1"},
            {"ev_b2", "10.156.30.11", "NotMyCard", "1000/2000/3000", "no", "Mobile", "10.99.99.96", "ck_b2", "ck_x2"},
            {"ev_b3", "10.157.30.12", "NotMyCard", "1000/61000/62000", "no", "Mobile", "10.157.30.12", "ck_b3", "ck_b3"}};

    /** The event list of one demo session, as the patterns read it: the four events, in time order. */
    public static Map<String, Object> eventsOf(String[] session) {
        String[] gaps = session[3].split("/");
        long start = 1790147176281L;
        long balance = start + Long.parseLong(gaps[0]);
        long payment = start + Long.parseLong(gaps[1]);
        long dispute = start + Long.parseLong(gaps[2]);
        String loginType = session.length > 5 ? session[5] : "Mobile";
        String disputeIp = session.length > 6 ? session[6] : session[1];

        Map<String, Object> events = new LinkedHashMap<>();
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("ID", session[0]);
        login.put("IP", session[1]);
        login.put("Type", loginType);
        if (session.length > 7) {
            // the login carries the session cookie too, so the login, the payment and the dispute agree on it
            login.put("Cookie", session[7]);
        }
        login.put("EventTime", start);
        events.put("Login", login);

        Map<String, Object> balanceCheck = new LinkedHashMap<>();
        balanceCheck.put("ID", session[0]);
        balanceCheck.put("IP", session[1]);
        balanceCheck.put("EventTime", balance);
        events.put("BalanceCheck", balanceCheck);

        Map<String, Object> paymentEvent = new LinkedHashMap<>();
        paymentEvent.put("ID", session[0]);
        paymentEvent.put("IP", session[1]);
        paymentEvent.put("Type", loginType);
        paymentEvent.put("USD", 1000);
        paymentEvent.put("CBIN", "035");
        if (session.length > 7) {
            paymentEvent.put("Cookie", session[7]);
        }
        paymentEvent.put("EventTime", payment);
        events.put("Payment", paymentEvent);

        Map<String, Object> disputeEvent = new LinkedHashMap<>();
        disputeEvent.put("ID", session[0]);
        disputeEvent.put("DisputeReason", session[2]);
        disputeEvent.put("IP", disputeIp);
        disputeEvent.put("Type", loginType);
        if (session.length > 8) {
            disputeEvent.put("Cookie", session[8]);
        }
        disputeEvent.put("EventTime", dispute);
        events.put("Dispute", disputeEvent);
        if ("yes".equals(session[4])) {
            // an event the pattern does not name: ignored, so a list may carry more than the pattern asks
            Map<String, Object> logout = new LinkedHashMap<>();
            logout.put("ID", session[0]);
            logout.put("IP", session[1]);
            logout.put("EventTime", dispute + 60000);
            events.put("Logout", logout);
        }
        return events;
    }

    /** The event list of one demo session as the JSON a query passes to the pattern. */
    public static String eventsJson(String[] session) {
        return FuzzyContexts.toJson(FuzzyContext.of(eventsOf(session)));
    }

    /** The event sessions the demo writes, for a test that wants to check them. */
    public static List<String[]> eventSessions() {
        return List.of(EVENT_SESSIONS);
    }
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
        removePreviousLinks();
        List<VertexUpsert> vertices = new ArrayList<>();
        Set<String> nodes = new LinkedHashSet<>();
        int linkEdges = 0;
        Map<Integer, Set<String>> nodesPerPattern = new LinkedHashMap<>();
        Map<Integer, Integer> perPatternEdges = new LinkedHashMap<>();

        // the patterns come from the pattern file, not from a vertex type in the graph, so a graph
        // only has to declare the link vertex and the edge class
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

        // the event sessions: the accounts, and the links their event lists produce (the list itself is
        // not stored - a query passes it in, which is how a risk engine links a session it just saw)
        List<VertexUpsert> sessionVertices = new ArrayList<>();
        int sessionEdges = 0;
        for (String[] session : EVENT_SESSIONS) {
            VertexSchema account = schema.vertexSchema("Account");
            Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
            properties.put(property(account, "firstName"), new VertexPropertyValue(session[0], now));
            properties.put(property(account, "accountStatus"), new VertexPropertyValue("ACTIVE", now));
            sessionVertices.add(new VertexUpsert(session[0], account, properties, now, now));
            for (FuzzyLink link : FuzzyLinks.plan(patterns, FuzzyContext.of(eventsOf(session)))) {
                sessionVertices.add(linkVertex(link, now));
                edgeStore.upsertEdge(linkEdge(link, session[0], now));
                nodes.add(link.linkVertexId());
                sessionEdges++;
                nodesPerPattern.computeIfAbsent(link.patternId(), k -> new LinkedHashSet<>())
                        .add(link.linkVertexId());
                perPatternEdges.merge(link.patternId(), 1, Integer::sum);
            }
        }
        vertexStore.upsertVertices(sessionVertices);
        linkEdges += sessionEdges;

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
            for (String[] session : EVENT_SESSIONS) {
                for (FuzzyLink link : FuzzyLinks.plan(patterns, FuzzyContext.of(eventsOf(session)))) {
                    if (link.patternId() == entry.getKey()) {
                        membersByNode.merge(link.linkVertexId(), 1, Integer::sum);
                    }
                }
            }
            largest.put(entry.getKey(), membersByNode.values().stream().max(Integer::compare).orElse(0));
        }
        return new Summary(allAccounts().size() + 1 + EVENT_SESSIONS.length, nodes.size(), linkEdges,
                payments, largest);
    }

    /**
     * Removes the links a previous seed left behind, before the current patterns write theirs.
     *
     * <p>The patterns are data, so a pattern whose shape changed leaves its old link node in the graph; a
     * re-seed without this would keep both the old group and the new one, and a query would report a group no
     * pattern produces any more. Only this demo's accounts and its link vertices are touched: the
     * {@code FuzzyLinkedTo} edge bins of both ends are cleared and the link vertices are removed, then the
     * caller writes the current plan back.
     *
     * <p>The link vertices are found by scanning for the {@code key} property (a link always carries one) and
     * keeping the records that say they are links - the scan is by property index, which every vertex type has
     * its own numbering of.
     */
    private void removePreviousLinks() {
        VertexSchema linkSchema = schema.vertexSchema(FuzzySchema.LINK_VERTEX);
        EdgeSchema linkEdge = schema.edgeSchema(FuzzySchema.LINK_EDGE);
        if (linkSchema == null || linkEdge == null) {
            return;
        }
        int keyProperty = property(linkSchema, FuzzySchema.KEY_PROPERTY);
        List<Object> linkVertices = new ArrayList<>();
        for (StoredVertex vertex : vertexStore.scanVerticesWithProperty(keyProperty, List.of(keyProperty))) {
            String id = String.valueOf(vertex.vertexId());
            if (FuzzySchema.LINK_VERTEX.equals(vertex.vertexType()) || id.startsWith("fl_")) {
                linkVertices.add(vertex.vertexId());
            }
        }
        List<Object> endpoints = new ArrayList<>(accountIds());
        endpoints.addAll(linkVertices);
        edgeStore.clearEdgeClasses(List.of(linkEdge), endpoints);
        if (!linkVertices.isEmpty()) {
            vertexStore.deleteVertices(linkVertices);
        }
    }

    /** The context of a demo account, exactly as the loader stores it. */
    public static FuzzyContext contextOf(String[] row) {
        return FuzzyContexts.fromProperties(Map.of(
                "username", row[1], "email", row[2], "phoneNumber", row[3],
                "cardNumber", row[4], "ipAddress", row[5]));
    }

    /**
     * How many accounts the rings hold (the members and the receiver, without the event sessions): the
     * sessions are read from an event list rather than from properties, so the payments the demo writes are
     * the rings', and a test that counts them says so with this number.
     */
    public static int memberAccounts() {
        return allAccounts().size() + 1;
    }

    /** The ids of the demo accounts, for a test that wants to check them. */
    public static List<String> accountIds() {
        List<String> ids = new ArrayList<>();
        for (String[] row : allAccounts()) {
            ids.add(row[0]);
        }
        for (String[] session : EVENT_SESSIONS) {
            ids.add(session[0]);
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
