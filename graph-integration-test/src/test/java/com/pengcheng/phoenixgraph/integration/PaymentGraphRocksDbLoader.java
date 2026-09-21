package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the payment graph into a RocksDB instance.
 *
 * <p>The data is the demo graph the Aerospike instance holds: the hub account {@code 135}
 * paying 50 accounts, those accounts paying each other in a ring, and every account owning two
 * IPs and two phones - 151 vertices and 600 edges. The same generator feeds both RocksDB
 * schemes, so the two instances can be compared query by query.
 *
 * <p>Usage:
 * <pre>
 * java ... PaymentGraphRocksDbLoader /path/to/rocksdb-root      # both instances
 * java ... PaymentGraphRocksDbLoader /path/to/root inline       # only RocksDB-Inline
 * </pre>
 *
 * @see #load(GraphSchema, String, boolean)
 */
public final class PaymentGraphRocksDbLoader {

    /** The RocksDB-Inline instance of the payment graph. */
    public static final String INLINE_GRAPH = "payment-graph-rocksdb-inline";

    /** The RocksDB-Edge instance of the payment graph. */
    public static final String EDGE_GRAPH = "payment-graph-rocksdb-edge";

    /** The hub account every demo traversal starts from. */
    public static final long HUB_ACCOUNT = 135L;

    /** The first of the 50 accounts the hub pays. */
    public static final long FIRST_ACCOUNT = 1_000L;

    /** How many accounts the hub pays. */
    public static final int ACCOUNT_COUNT = 50;

    /** The first IP id (account {@code 1000 + i} owns {@code 5000 + i}). */
    public static final long FIRST_PHONE = 6_000L;

    /** How many accounts one traversal visits from the hub. */
    public static final int PAYMENTS_FROM_HUB = ACCOUNT_COUNT;

    private static final String[] ACCOUNT_STATUS = {"ACTIVE", "FROZEN", "CLOSED"};
    private static final String[] ACCOUNT_TYPE = {"savings", "checking", "business"};
    private static final String[] PAYMENT_STATUS = {"SUCCESS", "PENDING", "FAILED"};
    private static final String[] PAYMENT_TYPE = {"transfer", "invoice", "refund"};
    private static final String[] FIRST_NAMES = {
            "Ada", "Alan", "Grace", "Linus", "Barbara", "Ken", "Margaret", "Dennis", "Radia", "Vint"};
    private static final String[] LAST_NAMES = {
            "Lovelace", "Turing", "Hopper", "Torvalds", "Liskov", "Thompson", "Hamilton", "Ritchie",
            "Perlman", "Cerf"};

    private static final long BASE_TIME = 1_700_000_000_000L;

    private PaymentGraphRocksDbLoader() {
    }

    /** What a load wrote. */
    public record LoadResult(String graph, GraphStorageType storage, String path,
                             int vertices, int edges) {
    }

    /** Loads a graph instance declared by a schema file (its own storage path is used). */
    public static LoadResult load(String graphName, String rocksDbRoot, boolean fresh) {
        GraphSchema schema = loadSchema(graphName);
        String path = schema.storage().requireRocksDbPath();
        String resolved = rocksDbRoot == null || rocksDbRoot.isBlank()
                ? path : Path.of(rocksDbRoot).resolve(path).toString();
        return load(schema, resolved, fresh);
    }

    /** Loads the demo payment graph into the RocksDB directory the schema points at. */
    public static LoadResult load(GraphSchema schema, String path, boolean fresh) {
        if (fresh) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path, schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        GraphStorageType type = schema.storage().type();
        List<RocksDbVertexUpsert> vertices = paymentVertices(schema);
        List<RocksDbEdgeUpsert> edges = paymentEdges(schema);
        if (type == GraphStorageType.ROCKSDB_INLINE) {
            try (RocksDbInlineStore store = RocksDbInlineStore.open(schema, config)) {
                store.upsertVertices(vertices);
                writeEdges(edges, store::upsertEdges);
                store.flush();
            }
        } else if (type == GraphStorageType.ROCKSDB_EDGE) {
            try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
                store.upsertVertices(vertices);
                writeEdges(edges, store::upsertEdges);
                store.flush();
            }
        } else {
            throw new IllegalArgumentException("Graph '" + schema.name() + "' is not a RocksDB graph but "
                    + (type == null ? "null" : type.configName()));
        }
        return new LoadResult(schema.name(), type, path, vertices.size(), edges.size());
    }

    /** Writes the edges one edge class at a time (a batch must share one class). */
    private static void writeEdges(List<RocksDbEdgeUpsert> edges, EdgeWriter writer) {
        Map<String, List<RocksDbEdgeUpsert>> byClass = new LinkedHashMap<>();
        for (RocksDbEdgeUpsert edge : edges) {
            byClass.computeIfAbsent(edge.edgeSchema().name(), k -> new ArrayList<>()).add(edge);
        }
        for (List<RocksDbEdgeUpsert> batch : byClass.values()) {
            writer.write(batch);
        }
    }

    /** Writes a batch of edges of one class. */
    @FunctionalInterface
    private interface EdgeWriter {

        void write(List<RocksDbEdgeUpsert> edges);
    }

    /** The vertices of the demo graph. */
    public static List<RocksDbVertexUpsert> paymentVertices(GraphSchema schema) {
        VertexSchema account = schema.vertexSchema("Account");
        VertexSchema ip = schema.vertexSchema("IP");
        VertexSchema phone = schema.vertexSchema("Phone");
        List<RocksDbVertexUpsert> upserts = new ArrayList<>();

        long time = BASE_TIME;
        upserts.add(account(account, HUB_ACCOUNT, 0, time));
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            upserts.add(account(account, FIRST_ACCOUNT + i, i + 1, time + i + 1));
            // the IP and the phone are identified by their value: an address, a number
            String address = ipAddressOf(i);
            String number = phoneNumberOf(i);
            upserts.add(vertex(ip, address, Map.of("ipAddress",
                    new RocksDbPropertyValue(address, time + i)), time + i));
            upserts.add(vertex(phone, number, Map.of(
                    "cnCode", new RocksDbPropertyValue("+86", time + i),
                    "phoneNumber", new RocksDbPropertyValue(number, time + i)), time + i));
        }
        return upserts;
    }

    /** The IP address a demo IP vertex is identified by (the id is the value itself). */
    public static String ipAddressOf(int index) {
        return "10.0." + (index / 256) + "." + (index % 256);
    }

    /** The phone number a demo Phone vertex is identified by (the id is the value itself). */
    public static String phoneNumberOf(int index) {
        return "+86-138-6" + String.format("%03d", index);
    }

    private static RocksDbVertexUpsert account(VertexSchema account, long id, int seed, long time) {
        String first = FIRST_NAMES[seed % FIRST_NAMES.length];
        String last = LAST_NAMES[(seed / FIRST_NAMES.length + seed) % LAST_NAMES.length];
        Map<String, RocksDbPropertyValue> values = Map.of(
                "accountStatus", new RocksDbPropertyValue(ACCOUNT_STATUS[seed % ACCOUNT_STATUS.length], time),
                "accountType", new RocksDbPropertyValue(ACCOUNT_TYPE[seed % ACCOUNT_TYPE.length], time),
                "primaryAddress", new RocksDbPropertyValue("addr-" + id, time),
                "accountUsage", new RocksDbPropertyValue(List.of("Personal", "Business"), time),
                "accountLimited", new RocksDbPropertyValue(Map.of("174", "open", "175", "kyc"), time),
                "firstName", new RocksDbPropertyValue(first, time),
                "lastName", new RocksDbPropertyValue(last, time));
        return vertex(account, id, values, time);
    }

    private static RocksDbVertexUpsert vertex(VertexSchema vertexSchema, Object id,
                                              Map<String, RocksDbPropertyValue> values, long time) {
        return new RocksDbVertexUpsert(id, vertexSchema, byName(vertexSchema, values), time, time);
    }

    /** Turns property names into the property indexes a record is stored under. */
    private static Map<Integer, RocksDbPropertyValue> byName(VertexSchema vertexSchema,
                                                             Map<String, RocksDbPropertyValue> values) {
        Map<Integer, RocksDbPropertyValue> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, RocksDbPropertyValue> entry : values.entrySet()) {
            indexed.put(vertexSchema.property(entry.getKey()).id(), entry.getValue());
        }
        return indexed;
    }

    /** The edges of the demo graph: hub payments, a ring, and IP/phone links one way. */
    public static List<RocksDbEdgeUpsert> paymentEdges(GraphSchema schema) {
        EdgeSchema payment = schema.edgeSchema("Payment");
        EdgeSchema ipToAccount = schema.edgeSchema("IPToAccount");
        EdgeSchema phoneToAccount = schema.edgeSchema("PhoneToAccount");

        List<RocksDbEdgeUpsert> edges = new ArrayList<>();
        long time = BASE_TIME;

        // the hub pays every account, and the 50 accounts pay each other in three rings
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            edges.add(edge(payment, "pay_hub_" + i, HUB_ACCOUNT, FIRST_ACCOUNT + i,
                    edgeValues(payment, Map.of(
                            "status", PAYMENT_STATUS[i % PAYMENT_STATUS.length],
                            "type", PAYMENT_TYPE[i % PAYMENT_TYPE.length],
                            "amount", 10.0d + i,
                            "currency", "USD"), time),
                    time + i));
        }
        for (int step : new int[]{1, 7, 13}) {
            for (int i = 0; i < ACCOUNT_COUNT; i++) {
                int target = (i + step) % ACCOUNT_COUNT;
                edges.add(edge(payment, "pay_ring" + step + "_" + i, FIRST_ACCOUNT + i,
                        FIRST_ACCOUNT + target,
                        edgeValues(payment, Map.of(
                                "status", PAYMENT_STATUS[(i + step) % PAYMENT_STATUS.length],
                                "type", PAYMENT_TYPE[(i + step) % PAYMENT_TYPE.length],
                                "amount", 5.0d + step,
                                "currency", "USD"), time),
                        time + 100 + i + step));
            }
        }

        // every account owns an IP and a phone, and each link is stored once, in the direction its
        // class declares (IP -> Account, Phone -> Account), so a UI draws one arrow per link and
        // counts it once. The id is the pair, so the same link can never be written twice.
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            long accountId = FIRST_ACCOUNT + i;
            String ipId = ipAddressOf(i);
            String phoneId = phoneNumberOf(i);
            edges.add(edge(ipToAccount, ipId + ":" + accountId, ipId, accountId,
                    edgeValues(ipToAccount, Map.of("linkedAt", time + i), time), time + i));
            edges.add(edge(phoneToAccount, phoneId + ":" + accountId, phoneId, accountId,
                    edgeValues(phoneToAccount, Map.of("linkedAt", time + i), time), time + i));
        }
        return edges;
    }

    /** Turns edge property names into property indexes. */
    private static Map<Integer, RocksDbEdgeProperty> edgeValues(EdgeSchema edgeSchema,
                                                                Map<String, Object> values, long time) {
        Map<Integer, RocksDbEdgeProperty> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            indexed.put(edgeSchema.property(entry.getKey()).id(),
                    new RocksDbEdgeProperty(entry.getValue(), time, time));
        }
        return indexed;
    }

    private static RocksDbEdgeUpsert edge(EdgeSchema edgeSchema, String edgeId, Object source, Object target,
                                          Map<Integer, RocksDbEdgeProperty> properties, long time) {
        return new RocksDbEdgeUpsert(edgeId, edgeSchema, source, target, properties, time, time);
    }

    // --- schema loading ---

    /**
     * Loads one of the RocksDB payment schemas. The file lives with the HTTP service's
     * resources; it is looked up relative to the working directory first (repository root or
     * module directory), then on the classpath.
     */
    public static GraphSchema loadSchema(String graphName) {
        for (String candidate : List.of("graph-http/src/main/resources/" + graphName + ".json",
                "../graph-http/src/main/resources/" + graphName + ".json")) {
            Path file = Path.of(candidate);
            if (Files.isReadable(file)) {
                return readSchema(file);
            }
        }
        try (InputStream in = PaymentGraphRocksDbLoader.class.getResourceAsStream("/" + graphName + ".json")) {
            if (in != null) {
                return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the schema of " + graphName, e);
        }
        throw new IllegalStateException("Cannot find the schema of " + graphName
                + " (looked for graph-http/src/main/resources/" + graphName + ".json)");
    }

    private static GraphSchema readSchema(Path file) {
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    /** Removes a directory tree (a fresh load starts from an empty instance). */
    private static void deleteDirectory(String path) {
        Path directory = Path.of(path);
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete " + entry, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clean " + path, e);
        }
    }

    /** Loads both RocksDB instances under a root directory (or the schema paths by default). */
    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : null;
        String only = args.length > 1 ? args[1] : null;
        List<String> graphs = new ArrayList<>();
        if (only == null || only.equalsIgnoreCase("inline")) {
            graphs.add(INLINE_GRAPH);
        }
        if (only == null || only.equalsIgnoreCase("edge")) {
            graphs.add(EDGE_GRAPH);
        }
        for (String graph : graphs) {
            LoadResult result = load(graph, root, true);
            System.out.printf("loaded %s (%s) at %s: %d vertices, %d edges%n",
                    result.graph(), result.storage().configName(), result.path(),
                    result.vertices(), result.edges());
        }
    }
}
