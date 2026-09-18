package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
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
import java.util.Random;

/**
 * Loads the "smart" payment graph: accounts and merchants that carry a spending/shop profile as a
 * 16-dimensional embedding and a home address as a latitude/longitude, plus the payments between
 * accounts and the visits from accounts to shops.
 *
 * <p>The data is generated from a fixed seed and is deliberately structured, so the three flows
 * the graph is meant to demonstrate have meaningful answers:
 * <ol>
 *   <li>accounts fall into five taste clusters (the embeddings), and a payment usually goes to an
 *       account in the same cluster — so "the accounts most similar to this one pay them" is not
 *       a coincidence;</li>
 *   <li>the five clusters also have five districts (People's Square, Xujiahui, Lujiazui, Jing'an,
 *       Zhongshan Park), and merchants sit in the same districts as the accounts that visit
 *       them — so "who is nearby" and "who is similar" agree;</li>
 *   <li>every account lives in its district, so a geo search around a point finds the accounts and
 *       shops of that neighbourhood.</li>
 * </ol>
 *
 * <p>Loads into Aerospike (where the HNSW vector index lives) and into a RocksDB-Inline directory
 * (where the S2 geo index is a real index), so the same data can be queried both ways:
 * <pre>
 * ./mvnw -q -pl graph-integration-test test -Dtest=SmartPaymentGraphLiveLoadTest \
 *        -Dsmart.set=payment_smart_v1 -Drocksdb.live.dir=.rocksdb
 * </pre>
 */
public final class SmartPaymentGraphLoader {

    /** Number of accounts, payments, merchants and visits the generator produces. */
    public static final int ACCOUNTS = 200;
    public static final int MERCHANTS = 40;
    public static final int PAYMENTS = 400;
    public static final int VISITS = 300;
    /** Dimensions of the embedding; both types declare 16. */
    public static final int DIMENSIONS = 16;

    /** The five neighbourhoods, as {lat, lon} centres and a name. */
    private static final double[][] DISTRICTS = {
            {31.2304, 121.4737},   // People's Square
            {31.1949, 121.4374},   // Xujiahui
            {31.2397, 121.4998},   // Lujiazui
            {31.2290, 121.4450},   // Jing'an
            {31.2210, 121.4180},   // Zhongshan Park
    };
    private static final String[] DISTRICT_NAMES = {"People's Square", "Xujiahui", "Lujiazui", "Jing'an",
            "Zhongshan Park"};
    private static final String[] CATEGORIES = {"coffee", "restaurant", "bookshop", "gym", "pharmacy"};
    private static final String[] STATUSES = {"SUCCESS", "SUCCESS", "SUCCESS", "PENDING", "FAILED"};

    private SmartPaymentGraphLoader() {
    }

    /** The schema the smart graph uses, read from the resources the service serves. */
    public static GraphSchema loadSchema(String resource) {
        try (InputStream in = SmartPaymentGraphLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing schema resource " + resource);
            }
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    // ------------------------------------------------------------------ the data

    /** One generated account: id, cluster (district) index, profile and location. */
    private record AccountData(String id, int cluster, double[] embedding, double lat, double lon, String status) {
    }

    /** One generated merchant: id, category, profile and location. */
    private record MerchantData(String id, String category, double[] embedding, double lat, double lon,
                                String name) {
    }

    private static List<AccountData> accounts() {
        Random random = new Random(20260918L);
        List<AccountData> accounts = new ArrayList<>();
        for (int i = 1; i <= ACCOUNTS; i++) {
            int cluster = (i - 1) % DISTRICTS.length;
            double[] embedding = profile(random, cluster, 0.12);
            double[] point = nearDistrict(random, cluster, 0.012);
            String status = i % 17 == 0 ? "FROZEN" : "ACTIVE";
            accounts.add(new AccountData("a" + i, cluster, embedding, point[0], point[1], status));
        }
        return accounts;
    }

    private static List<MerchantData> merchants() {
        Random random = new Random(4711L);
        List<MerchantData> merchants = new ArrayList<>();
        for (int i = 1; i <= MERCHANTS; i++) {
            int cluster = (i - 1) % DISTRICTS.length;
            String category = CATEGORIES[cluster];
            double[] embedding = profile(random, cluster, 0.08);
            double[] point = nearDistrict(random, cluster, 0.006);
            merchants.add(new MerchantData("m" + i, category, embedding, point[0], point[1],
                    category + " #" + i));
        }
        return merchants;
    }

    /** A profile of the cluster's taste, with noise: the five clusters are what nearest() finds. */
    private static double[] profile(Random random, int cluster, double noise) {
        double[] embedding = new double[DIMENSIONS];
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
            double centre = clusterCentre(cluster, dimension);
            embedding[dimension] = centre + (random.nextDouble() - 0.5) * 2 * noise;
        }
        return embedding;
    }

    /** The cluster centres sit on a circle in the first two dimensions and vary a little elsewhere. */
    private static double clusterCentre(int cluster, int dimension) {
        double angle = 2 * Math.PI * cluster / DISTRICTS.length;
        if (dimension == 0) {
            return Math.cos(angle);
        }
        if (dimension == 1) {
            return Math.sin(angle);
        }
        return 0.25 * Math.sin(angle + dimension) * (1 + cluster * 0.1);
    }

    private static double[] nearDistrict(Random random, int cluster, double spread) {
        double lat = DISTRICTS[cluster][0] + (random.nextDouble() - 0.5) * spread;
        double lon = DISTRICTS[cluster][1] + (random.nextDouble() - 0.5) * spread;
        return new double[]{lat, lon};
    }

    /** The district a point is closest to, which is how a "similar" account is picked. */
    private static int districtOf(double lat, double lon) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < DISTRICTS.length; i++) {
            double distance = Math.hypot(lat - DISTRICTS[i][0], lon - DISTRICTS[i][1]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Aerospike

    /** Writes the whole graph into an Aerospike set. */
    public static void loadAerospike(String host, int port, String namespace, String set, GraphSchema schema) {
        long now = System.currentTimeMillis();
        try (AerospikeClient client = new AerospikeClient(host, port)) {
            AerospikeVertexStore vertices = new AerospikeVertexStore(client, namespace, set);
            AerospikeEdgeStore edges = new AerospikeEdgeStore(client, namespace, set, schema);

            VertexSchema accountSchema = schema.vertexSchema("Account");
            VertexSchema merchantSchema = schema.vertexSchema("Merchant");
            List<VertexUpsert> vertexUpserts = new ArrayList<>();
            List<AccountData> accounts = accounts();
            for (AccountData account : accounts) {
                Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
                properties.put(1, new VertexPropertyValue("Account " + account.id(), now));
                properties.put(2, new VertexPropertyValue(account.status(), now));
                properties.put(3, new VertexPropertyValue(DISTRICT_NAMES[account.cluster()], now));
                properties.put(4, new VertexPropertyValue(toList(account.embedding()), now));
                properties.put(5, new VertexPropertyValue(location(account.lat(), account.lon()), now));
                vertexUpserts.add(new VertexUpsert(account.id(), accountSchema, properties, now, now));
            }
            List<MerchantData> merchants = merchants();
            for (MerchantData merchant : merchants) {
                Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
                properties.put(1, new VertexPropertyValue(merchant.name(), now));
                properties.put(2, new VertexPropertyValue(merchant.category(), now));
                properties.put(3, new VertexPropertyValue(toList(merchant.embedding()), now));
                properties.put(4, new VertexPropertyValue(location(merchant.lat(), merchant.lon()), now));
                vertexUpserts.add(new VertexUpsert(merchant.id(), merchantSchema, properties, now, now));
            }
            vertices.upsertVertices(vertexUpserts);

            EdgeSchema payment = schema.edgeSchema("Payment");
            EdgeSchema visited = schema.edgeSchema("Visited");
            vertices(edges, payment, paymentUpserts(accounts, payment, now));
            vertices(edges, visited, visitUpserts(accounts, merchants, visited, now));
        }
    }

    private static void vertices(AerospikeEdgeStore store, EdgeSchema edgeSchema, List<EdgeUpsert> upserts) {
        for (EdgeUpsert upsert : upserts) {
            store.upsertEdge(upsert);
        }
    }

    /** Payments go to accounts in the same district most of the time, occasionally elsewhere. */
    private static List<EdgeUpsert> paymentUpserts(List<AccountData> accounts, EdgeSchema payment, long now) {
        Random random = new Random(99L);
        List<EdgeUpsert> upserts = new ArrayList<>();
        int seed = 0;
        while (upserts.size() < PAYMENTS) {
            AccountData from = accounts.get(seed % accounts.size());
            seed++;
            AccountData to;
            if (random.nextInt(100) < 70) {
                List<AccountData> same = accounts.stream().filter(a -> a.cluster() == from.cluster()).toList();
                to = same.get(random.nextInt(same.size()));
            } else {
                to = accounts.get(random.nextInt(accounts.size()));
            }
            if (to.id().equals(from.id())) {
                continue;
            }
            String status = STATUSES[random.nextInt(STATUSES.length)];
            int index = upserts.size();
            Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
            properties.put(3, new EdgePropertyValue(status, now, now));
            properties.put(4, new EdgePropertyValue(Math.round((5 + random.nextDouble() * 495) * 100) / 100.0, now, now));
            properties.put(5, new EdgePropertyValue("CNY", now, now));
            upserts.add(new EdgeUpsert("pay_" + index, payment, from.id(), to.id(), properties,
                    now + index, now + index));
        }
        return upserts;
    }

    /** Visits go to shops in the district the account lives in. */
    private static List<EdgeUpsert> visitUpserts(List<AccountData> accounts, List<MerchantData> merchants,
                                                 EdgeSchema visited, long now) {
        Random random = new Random(1234L);
        List<EdgeUpsert> upserts = new ArrayList<>();
        int seed = 0;
        while (upserts.size() < VISITS) {
            AccountData account = accounts.get(seed % accounts.size());
            seed++;
            List<MerchantData> nearby = merchants.stream()
                    .filter(m -> districtOf(m.lat(), m.lon()) == account.cluster()).toList();
            if (nearby.isEmpty()) {
                continue;
            }
            MerchantData merchant = nearby.get(random.nextInt(nearby.size()));
            int index = upserts.size();
            Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
            properties.put(3, new EdgePropertyValue(1 + random.nextInt(12), now, now));
            upserts.add(new EdgeUpsert("visit_" + index, visited, account.id(), merchant.id(), properties,
                    now + index, now + index));
        }
        return upserts;
    }

    // ------------------------------------------------------------------ RocksDB

    /** Writes the same graph into a RocksDB-Inline directory (creating it when needed). */
    public static int loadRocksDb(GraphSchema schema, String path, boolean reset) {
        RocksDbStoreConfig config = new RocksDbStoreConfig(path, schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        long now = System.currentTimeMillis();
        List<AccountData> accounts = accounts();
        List<MerchantData> merchants = merchants();

        if (reset) {
            deleteDirectory(Path.of(path));
        }
        int written = 0;
        try (RocksDbInlineStore store = RocksDbInlineStore.open(schema, config)) {
            VertexSchema accountSchema = schema.vertexSchema("Account");
            VertexSchema merchantSchema = schema.vertexSchema("Merchant");
            List<RocksDbVertexUpsert> vertices = new ArrayList<>();
            for (AccountData account : accounts) {
                vertices.add(new RocksDbVertexUpsert(account.id(), accountSchema, Map.of(
                        1, new RocksDbPropertyValue("Account " + account.id(), now),
                        2, new RocksDbPropertyValue(account.status(), now),
                        3, new RocksDbPropertyValue(DISTRICT_NAMES[account.cluster()], now),
                        4, new RocksDbPropertyValue(toList(account.embedding()), now),
                        5, new RocksDbPropertyValue(location(account.lat(), account.lon()), now)), now, now));
            }
            for (MerchantData merchant : merchants) {
                vertices.add(new RocksDbVertexUpsert(merchant.id(), merchantSchema, Map.of(
                        1, new RocksDbPropertyValue(merchant.name(), now),
                        2, new RocksDbPropertyValue(merchant.category(), now),
                        3, new RocksDbPropertyValue(toList(merchant.embedding()), now),
                        4, new RocksDbPropertyValue(location(merchant.lat(), merchant.lon()), now)), now, now));
            }
            store.upsertVertices(vertices);
            written += vertices.size();

            EdgeSchema payment = schema.edgeSchema("Payment");
            List<RocksDbEdgeUpsert> payments = new ArrayList<>();
            for (EdgeUpsert upsert : paymentUpserts(accounts, payment, now)) {
                payments.add(new RocksDbEdgeUpsert(upsert.edgeId(), payment, upsert.source(), upsert.destination(),
                        toRocksDbProperties(upsert.properties()), upsert.createdTime(), upsert.lastUpdatedTime()));
            }
            store.upsertEdges(payments);
            written += payments.size();

            EdgeSchema visited = schema.edgeSchema("Visited");
            List<RocksDbEdgeUpsert> visits = new ArrayList<>();
            for (EdgeUpsert upsert : visitUpserts(accounts, merchants, visited, now)) {
                visits.add(new RocksDbEdgeUpsert(upsert.edgeId(), visited, upsert.source(), upsert.destination(),
                        toRocksDbProperties(upsert.properties()), upsert.createdTime(), upsert.lastUpdatedTime()));
            }
            store.upsertEdges(visits);
            written += visits.size();
        }
        return written;
    }

    private static Map<Integer, RocksDbEdgeProperty> toRocksDbProperties(Map<Integer, EdgePropertyValue> properties) {
        Map<Integer, RocksDbEdgeProperty> out = new LinkedHashMap<>();
        properties.forEach((id, value) ->
                out.put(id, new RocksDbEdgeProperty(value.value(), value.createdTime(),
                        value.lastUpdatedTime())));
        return out;
    }

    private static List<Double> toList(double[] embedding) {
        List<Double> values = new ArrayList<>(embedding.length);
        for (double value : embedding) {
            values.add(Math.round(value * 10_000) / 10_000.0);
        }
        return values;
    }

    private static Map<String, Double> location(double lat, double lon) {
        Map<String, Double> point = new LinkedHashMap<>();
        point.put("lat", Math.round(lat * 1_000_000) / 1_000_000.0);
        point.put("lon", Math.round(lon * 1_000_000) / 1_000_000.0);
        return point;
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete " + entry, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reset " + path, e);
        }
    }

    /** A one-line summary of what was generated, for loaders and tests to print. */
    public static String summary() {
        return ACCOUNTS + " accounts, " + MERCHANTS + " merchants, " + PAYMENTS + " payments, "
                + VISITS + " visits (" + DIMENSIONS + " dimensions, " + DISTRICTS.length + " districts)";
    }
}
