package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the demo graphs into the on-disk RocksDB instances the HTTP service serves: the payment
 * graph in both schemes, and the shop graph with its geo index.
 *
 * <p>This is the "prepare the demo" step rather than a test of its own: it writes where the
 * service will read from, so it only runs when a directory is named explicitly.
 *
 * <pre>
 * ./mvnw -pl graph-integration-test -am test -Dtest=RocksDbLiveLoadTest \
 *        -Drocksdb.live.dir=/absolute/path/to/rocksdb-root -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>The directory may also come from the {@code ROCKSDB_LIVE_DIR} environment variable. The
 * service then has to be pointed at the same root
 * ({@code phoenixgraph.storage.rocksdb-root}); it opens
 * {@code <root>/payment-graph-rocksdb-inline} and {@code <root>/payment-graph-rocksdb-edge}.
 * The check at the end reopens both instances and runs one Gremlin and one Cypher query, so a
 * load that produced unreadable data fails here instead of in the UI.
 */
class RocksDbLiveLoadTest {

    @Test
    void loadBothInstancesIntoTheConfiguredDirectory() throws IOException {
        String root = directory();
        assumeTrue(root != null, "ROCKSDB_LIVE_DIR / -Drocksdb.live.dir not set, skipping the live load");
        Files.createDirectories(Path.of(root));

        for (String graph : List.of(PaymentGraphRocksDbLoader.INLINE_GRAPH,
                PaymentGraphRocksDbLoader.EDGE_GRAPH)) {
            PaymentGraphRocksDbLoader.LoadResult result = PaymentGraphRocksDbLoader.load(graph, root, true);
            System.out.printf("loaded %s (%s) at %s: %d vertices, %d edges%n", result.graph(),
                    result.storage().configName(), result.path(), result.vertices(), result.edges());
            assertEquals(151, result.vertices());
            assertEquals(400, result.edges());
            assertTrue(Files.isDirectory(Path.of(result.path())), result.path() + " should exist");
        }

        // the instances are readable again: the same two queries the UI runs must answer
        GraphSchema inlineSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.INLINE_GRAPH);
        GraphSchema edgeSchema = PaymentGraphRocksDbLoader.loadSchema(PaymentGraphRocksDbLoader.EDGE_GRAPH);
        String inlinePath = Path.of(root).resolve(inlineSchema.storage().requireRocksDbPath()).toString();
        String edgePath = Path.of(root).resolve(edgeSchema.storage().requireRocksDbPath()).toString();

        try (RocksDbInlineStore inline = RocksDbInlineStore.open(inlineSchema,
                RocksDbStoreConfig.of(inlinePath));
             RocksDbEdgeStore edge = RocksDbEdgeStore.open(edgeSchema, RocksDbStoreConfig.of(edgePath))) {
            GremlinGraphQuery inlineGremlin = new GremlinGraphQuery(
                    new RocksDbInlineGraphQuery(inline, inlineSchema),
                    NeighborVertexResolver.from(inlineSchema));
            CypherGraphQuery edgeCypher = new CypherGraphQuery(
                    new RocksDbEdgeGraphQuery(edge, edgeSchema),
                    NeighborVertexResolver.from(edgeSchema));

            assertEquals(List.of(50L), inlineGremlin.execute("g.V('135','Account').out('Payment').count()"));
            assertEquals(50, inlineGremlin.execute("g.V('135','Account').out('Payment').out('AccountToIP')").size());
            assertEquals(50L, ((Number) edgeCypher.execute(
                            "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN count(*) AS n")
                    .rows().get(0).get("n")).longValue());
            assertEquals(151L, edge.vertices().count("Account") + edge.vertices().count("IP")
                    + edge.vertices().count("Phone"));
        }
    }

    /** The geo demo: the same shops, indexed with S2 cells, in both RocksDB schemes. */
    @Test
    void loadTheGeoInstancesIntoTheConfiguredDirectory() throws IOException {
        String root = directory();
        assumeTrue(root != null, "ROCKSDB_LIVE_DIR / -Drocksdb.live.dir not set, skipping the live load");
        Files.createDirectories(Path.of(root));

        for (String graph : List.of(GeoMerchantDataLoader.INLINE_GRAPH, GeoMerchantDataLoader.EDGE_GRAPH)) {
            GeoMerchantDataLoader.LoadResult result = GeoMerchantDataLoader.load(graph, root, true);
            System.out.printf("loaded %s (%s) at %s: %d merchants, %d geo index rows%n", result.graph(),
                    result.storage().configName(), result.path(), result.merchants(), result.indexed());
            assertEquals(GeoMerchantDataLoader.MERCHANT_COUNT, result.merchants());
            assertEquals(GeoMerchantDataLoader.MERCHANT_COUNT, result.indexed(),
                    "every shop must be in the geo index");
        }

        // the demo query answers out of the freshly written instances
        GraphSchema schema = PaymentGraphRocksDbLoader.loadSchema(GeoMerchantDataLoader.INLINE_GRAPH);
        String path = Path.of(root).resolve(schema.storage().requireRocksDbPath()).toString();
        try (com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore store =
                     com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore.open(schema,
                             RocksDbStoreConfig.of(path))) {
            GremlinGraphQuery engine = new GremlinGraphQuery(
                    new com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery(store, schema),
                    NeighborVertexResolver.from(schema))
                    .geoProperties(java.util.Map.of("Merchant", "location"));
            assertEquals((long) GeoMerchantDataLoader.SHOPS_WITHIN_1_KM,
                    (long) engine.execute("g.V('1','Merchant')"
                            + ".geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":1}').count()").get(0));
        }
    }

    private static String directory() {
        String property = System.getProperty("rocksdb.live.dir");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("ROCKSDB_LIVE_DIR");
        return env == null || env.isBlank() ? null : env;
    }
}
