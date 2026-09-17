package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The geo demo data: shops around People's Square in Shanghai, each with a point the geo index
 * is built from.
 *
 * <p>The shops are laid out on a spiral, shop {@code i} at {@code 0.05 + i * 0.006} km from the
 * centre (so the last one is about 1.5 km away) on a golden-angle bearing, which spreads them
 * the way a city does and makes the radius of a query decide how many are found: one kilometre
 * holds roughly 160 of the 240 shops. Every shop is written through the ordinary vertex write,
 * so the S2 index is maintained by the write path itself.
 *
 * <pre>
 * java ... GeoMerchantDataLoader /path/to/rocksdb-root        # both geo instances
 * </pre>
 */
public final class GeoMerchantDataLoader {

    /** The RocksDB-Inline instance of the geo demo. */
    public static final String INLINE_GRAPH = "geo-merchants-rocksdb-inline";

    /** The RocksDB-Edge instance of the geo demo. */
    public static final String EDGE_GRAPH = "geo-merchants-rocksdb-edge";

    /** The centre the demo queries search around: People's Square, Shanghai. */
    public static final GeoPoint CENTRE = new GeoPoint(31.2304d, 121.4737d);

    /** How many shops the demo holds. */
    public static final int MERCHANT_COUNT = 240;

    /** The distance of the first shop from the centre, in kilometres. */
    public static final double FIRST_DISTANCE_KM = 0.05d;

    /** How much farther each following shop sits, in kilometres. */
    public static final double DISTANCE_STEP_KM = 0.006d;

    /** The categories the shops are spread over. */
    public static final String[] CATEGORIES = {"coffee", "bakery", "pharmacy", "supermarket",
            "restaurant", "bookstore"};

    /** How many shops a one kilometre radius holds (the query the demo runs). */
    public static final int SHOPS_WITHIN_1_KM = 159;

    private static final long BASE_TIME = 1_710_000_000_000L;

    private GeoMerchantDataLoader() {
    }

    /** How many "opens" edges (nullable target) the demo writes. */
    public static final int OPEN_DEALS = 3;

    /** What a load wrote. */
    public record LoadResult(String graph, GraphStorageType storage, String path, int merchants,
                             long indexed, int opens) {
    }

    /** Loads a geo instance declared by a schema file (its own storage path is used). */
    public static LoadResult load(String graphName, String rocksDbRoot, boolean fresh) {
        GraphSchema schema = PaymentGraphRocksDbLoader.loadSchema(graphName);
        String path = schema.storage().requireRocksDbPath();
        String resolved = rocksDbRoot == null || rocksDbRoot.isBlank()
                ? path : Path.of(rocksDbRoot).resolve(path).toString();
        return load(schema, resolved, fresh);
    }

    /** Loads the shops into the RocksDB directory the schema points at. */
    public static LoadResult load(GraphSchema schema, String path, boolean fresh) {
        if (fresh) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path, schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        List<RocksDbVertexUpsert> merchants = merchants(schema);
        List<RocksDbEdgeUpsert> opens = openDeals(schema);
        long indexed;
        if (schema.storage().type() == GraphStorageType.ROCKSDB_INLINE) {
            try (RocksDbInlineStore store = RocksDbInlineStore.open(schema, config)) {
                store.upsertVertices(merchants);
                if (!opens.isEmpty()) {
                    store.upsertEdges(opens);
                }
                store.flush();
                indexed = store.geoIndex().count("Merchant", "location");
            }
        } else if (schema.storage().type() == GraphStorageType.ROCKSDB_EDGE) {
            try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
                store.upsertVertices(merchants);
                if (!opens.isEmpty()) {
                    store.upsertEdges(opens);
                }
                store.flush();
                indexed = store.geoIndex().count("Merchant", "location");
            }
        } else {
            throw new IllegalArgumentException("Graph '" + schema.name() + "' is not a RocksDB graph");
        }
        return new LoadResult(schema.name(), schema.storage().type(), path, merchants.size(), indexed,
                opens.size());
    }

    /** The shops of the demo, in the shape the storage writes. */
    public static List<RocksDbVertexUpsert> merchants(GraphSchema schema) {
        VertexSchema merchant = schema.vertexSchema("Merchant");
        GeoIndexSchemas.GeoProperty geo = GeoIndexSchemas.geoProperties(schema).get(0);
        List<RocksDbVertexUpsert> upserts = new ArrayList<>(MERCHANT_COUNT);
        for (int i = 0; i < MERCHANT_COUNT; i++) {
            GeoPoint point = pointOf(i);
            long time = BASE_TIME + i;
            Map<String, RocksDbPropertyValue> values = new LinkedHashMap<>();
            values.put("name", new RocksDbPropertyValue(nameOf(i), time));
            values.put("category", new RocksDbPropertyValue(CATEGORIES[i % CATEGORIES.length], time));
            values.put("rating", new RocksDbPropertyValue(3.5d + (i % 15) / 10d, time));
            values.put("address", new RocksDbPropertyValue((100 + i) + " Nanjing Road", time));
            values.put("location", new RocksDbPropertyValue(
                    Map.of("lat", point.lat(), "lon", point.lon()), time));
            values.put("openNow", new RocksDbPropertyValue(i % 3 != 0, time));
            upserts.add(new RocksDbVertexUpsert((long) (i + 1), merchant, byName(merchant, values), time, time));
        }
        return upserts;
    }

    /**
     * The "opens" edges: deals whose counterpart is not known yet. The schema declares the
     * target nullable ({@code isNull}), so the edge is stored on its source only and no vertex
     * points back at it.
     */
    public static List<RocksDbEdgeUpsert> openDeals(GraphSchema schema) {
        EdgeSchema opens = schema.edgeSchema("Opens");
        if (opens == null) {
            return List.of();
        }
        List<RocksDbEdgeUpsert> edges = new ArrayList<>(OPEN_DEALS);
        for (int i = 0; i < OPEN_DEALS; i++) {
            long time = BASE_TIME + 500 + i;
            Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>();
            properties.put(opens.property("since").id(), new RocksDbEdgeProperty(time, time, time));
            properties.put(opens.property("title").id(),
                    new RocksDbEdgeProperty("Deal #" + (i + 1) + " (counterpart TBD)", time, time));
            edges.add(new RocksDbEdgeUpsert("deal_" + (i + 1), opens, 1L, null, // all announced by merchant 1
                    properties, time, time));
        }
        return edges;
    }

    /** The point of shop {@code i}: a spiral around the centre with a golden-angle bearing. */
    public static GeoPoint pointOf(int index) {
        double distanceKm = FIRST_DISTANCE_KM + index * DISTANCE_STEP_KM;
        double bearing = Math.toRadians(index * 137.508d); // the golden angle spreads the shops
        double latOffset = (distanceKm * Math.cos(bearing)) / 111.19d;
        double lonOffset = (distanceKm * Math.sin(bearing))
                / (111.32d * Math.cos(Math.toRadians(CENTRE.lat())));
        return new GeoPoint(CENTRE.lat() + latOffset, CENTRE.lon() + lonOffset);
    }

    /** The distance of shop {@code i} from the centre, in kilometres (the spiral radius). */
    public static double distanceKmOf(int index) {
        return FIRST_DISTANCE_KM + index * DISTANCE_STEP_KM;
    }

    /** A shop name such as {@code "Coffee #12"}. */
    public static String nameOf(int index) {
        String category = CATEGORIES[index % CATEGORIES.length];
        return Character.toUpperCase(category.charAt(0)) + category.substring(1) + " #" + (index + 1);
    }

    private static Map<Integer, RocksDbPropertyValue> byName(VertexSchema vertexSchema,
                                                             Map<String, RocksDbPropertyValue> values) {
        Map<Integer, RocksDbPropertyValue> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, RocksDbPropertyValue> entry : values.entrySet()) {
            indexed.put(vertexSchema.property(entry.getKey()).id(), entry.getValue());
        }
        return indexed;
    }

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

    /** Loads both geo instances under a root directory (or the schema paths by default). */
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
            System.out.printf("loaded %s (%s) at %s: %d merchants, %d geo index rows, %d open deals%n",
                    result.graph(), result.storage().configName(), result.path(),
                    result.merchants(), result.indexed(), result.opens());
        }
    }
}
