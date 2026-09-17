package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.inline.RocksDbInlineStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geo queries on the shop demo, run on both RocksDB schemes through the Gremlin engine.
 *
 * <p>The same 240 shops are loaded into both instances, so every query is run twice and the two
 * answers must match: the schemes differ in how edges are stored, not in how a radius query
 * finds points.
 */
class GeoQueryIntegrationTest {

    /** The query the demo shows: the nearest shops within one kilometre of People's Square. */
    private static final String NEAREST_1KM =
            "g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737,\"radius\":1000}')";

    @TempDir
    static Path directory;

    private static RocksDbInlineStore inlineStore;
    private static RocksDbEdgeStore edgeStore;
    private static GremlinGraphQuery inline;
    private static GremlinGraphQuery edge;

    @BeforeAll
    static void loadBothInstances() {
        GraphSchema inlineSchema = PaymentGraphRocksDbLoader.loadSchema(GeoMerchantDataLoader.INLINE_GRAPH);
        GraphSchema edgeSchema = PaymentGraphRocksDbLoader.loadSchema(GeoMerchantDataLoader.EDGE_GRAPH);
        assertEquals(GraphStorageType.ROCKSDB_INLINE, inlineSchema.storage().type());
        assertEquals(GraphStorageType.ROCKSDB_EDGE, edgeSchema.storage().type());
        assertEquals(1, GeoIndexSchemas.geoProperties(inlineSchema).size(),
                "the demo declares one geo property: Merchant.location");

        GeoMerchantDataLoader.LoadResult inlineLoad = GeoMerchantDataLoader.load(inlineSchema,
                directory.resolve("inline").toString(), true);
        GeoMerchantDataLoader.LoadResult edgeLoad = GeoMerchantDataLoader.load(edgeSchema,
                directory.resolve("edge").toString(), true);
        assertEquals(240, inlineLoad.merchants());
        assertEquals(240, inlineLoad.indexed(), "every shop is in the geo index");
        assertEquals(inlineLoad.indexed(), edgeLoad.indexed());

        inlineStore = RocksDbInlineStore.open(inlineSchema,
                RocksDbStoreConfig.of(directory.resolve("inline").toString()));
        edgeStore = RocksDbEdgeStore.open(edgeSchema,
                RocksDbStoreConfig.of(directory.resolve("edge").toString()));
        inline = engine(new RocksDbInlineGraphQuery(inlineStore, inlineSchema), inlineSchema);
        edge = engine(new RocksDbEdgeGraphQuery(edgeStore, edgeSchema), edgeSchema);
    }

    private static GremlinGraphQuery engine(GraphQuery query, GraphSchema schema) {
        Map<String, String> geo = Map.of("Merchant", "location");
        return new GremlinGraphQuery(query, NeighborVertexResolver.from(schema)).geoProperties(geo);
    }

    @AfterAll
    static void closeStores() {
        if (inlineStore != null) {
            inlineStore.close();
        }
        if (edgeStore != null) {
            edgeStore.close();
        }
    }

    @Test
    void theRadiusDecidesHowManyShopsAreFound() {
        long oneKm = (long) inline.execute(NEAREST_1KM + ".count()").get(0);
        assertEquals(GeoMerchantDataLoader.SHOPS_WITHIN_1_KM, oneKm,
                "the spiral puts " + GeoMerchantDataLoader.SHOPS_WITHIN_1_KM + " shops within 1 km");
        assertEquals(GeoMerchantDataLoader.MERCHANT_COUNT,
                (long) inline.execute("g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737}',"
                        + " 2000).count()").get(0),
                "a 2 km radius holds every shop");
        // a small radius holds the few shops right around the centre
        long near = (long) inline.execute("g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737}',"
                + " 200).count()").get(0);
        assertTrue(near >= 20 && near <= 30, "200 m holds the first ~26 shops, got " + near);

        // both schemes answer the same
        for (String radius : List.of("200", "500", "1000", "1500")) {
            String query = "g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737}', " + radius
                    + ").count()";
            assertEquals(inline.execute(query), edge.execute(query), "radius " + radius + " m");
        }
    }

    @Test
    void resultsAreTheNearestShopsWithTheirDistance() {
        List<Object> distances = inline.execute(NEAREST_1KM + ".limit(5).values('distanceMeters')");
        assertEquals(5, distances.size());
        double previous = -1;
        for (Object distance : distances) {
            double value = ((Number) distance).doubleValue();
            assertTrue(value <= 1_000d, "a hit inside the radius: " + value);
            assertTrue(value >= previous, "nearest first: " + distances);
            previous = value;
        }
        // the closest shop is the first of the spiral, 50 m from the centre
        assertEquals(GeoMerchantDataLoader.FIRST_DISTANCE_KM * 1_000d,
                ((Number) distances.get(0)).doubleValue(), 10d);

        // the same five shops, in the same order, on the other scheme
        assertEquals(inline.execute(NEAREST_1KM + ".limit(5).values('name')"),
                edge.execute(NEAREST_1KM + ".limit(5).values('name')"));
    }

    @Test
    void aGeoSearchCombinesWithTheOtherSteps() {
        // filter the found shops by a property ...
        List<Object> categories = inline.execute(
                NEAREST_1KM + ".has('category','coffee').values('category')");
        assertTrue(!categories.isEmpty(), "the 1 km circle holds coffee shops");
        assertTrue(categories.stream().allMatch("coffee"::equals), categories.toString());

        // ... and count them, on both schemes
        String coffees = NEAREST_1KM + ".has('category','coffee').count()";
        assertEquals(inline.execute(coffees), edge.execute(coffees));

        // elementMap shows the distance next to the stored properties
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) inline.execute(NEAREST_1KM + ".limit(1).elementMap()")
                .get(0);
        assertEquals("Merchant", first.get("label"));
        assertTrue(first.containsKey("distanceMeters"), first.toString());
        assertTrue(first.containsKey("name") && first.containsKey("category"), first.toString());
    }

    @Test
    void theSameQueryAnswersTheSameOnBothSchemes() {
        List<String> queries = List.of(
                NEAREST_1KM + ".limit(10).values('name')",
                NEAREST_1KM + ".count()",
                NEAREST_1KM + ".has('category','bakery').values('name')",
                "g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737,\"radius\":400}')"
                        + ".limit(3).elementMap()",
                "g.V('1','Merchant').circle('{\"lat\":31.22,\"lon\":121.48}', 600).count()");
        for (String query : queries) {
            assertEquals(inline.execute(query), edge.execute(query), "query: " + query);
        }
    }

    @Test
    void anotherCentreFindsAnotherNeighbourhood() {
        long north = (long) inline.execute("g.V('1','Merchant').circle('{\"lat\":31.2404,\"lon\":121.4737}',"
                + " 300).count()").get(0);
        assertTrue(north > 0, "shops north of the centre");
        // the two circles overlap but are not the same set
        long centre = (long) inline.execute("g.V('1','Merchant').circle('{\"lat\":31.2304,\"lon\":121.4737}',"
                + " 300).count()").get(0);
        assertTrue(north != centre, "a different centre finds a different set (" + north + " vs " + centre + ")");
    }

    @Test
    void anEdgeWithANullableTargetIsAnOutEdgeWithNoNeighbour() {
        // the demo data holds three "opens" edges: a merchant announced a deal whose
        // counterpart is not known yet, and the schema declares the target nullable
        for (GremlinGraphQuery engine : List.of(inline, edge)) {
            assertEquals(GeoMerchantDataLoader.OPEN_DEALS,
                    ((Number) engine.execute("g.V('1','Merchant').outE('Opens').count()").get(0)).intValue(),
                    "the edge is stored on its source");
            assertEquals(List.of(), engine.execute("g.V('1','Merchant').out('Opens')"),
                    "following it reaches no vertex");
            assertEquals(List.of(), engine.execute("g.V('1','Merchant').both('Opens')"));
            // the edge itself carries its properties and no far endpoint
            @SuppressWarnings("unchecked")
            Map<String, Object> edgeMap = (Map<String, Object>) engine.execute(
                    "g.V('1','Merchant').outE('Opens').limit(1).elementMap()").get(0);
            assertEquals("Opens", edgeMap.get("label"));
            assertTrue(String.valueOf(edgeMap.get("title")).startsWith("Deal #"), edgeMap.toString());
        }
    }

    @Test
    void aLimitAfterCircleLimitsWhatIsReadAndDrawn() {
        // the UI draws the visited subgraph, so a limited circle must not collect the whole
        // circle: ten merchants asked for means ten merchants read, collected and drawn
        com.pengcheng.phoenixgraph.query.result.Subgraph subgraph = inline.executeSubgraph(
                NEAREST_1KM + ".limit(10)");
        assertEquals(10, subgraph.vertices().size(), "ten shops, not the 159 inside the radius");
        java.util.Set<String> names = subgraph.vertices().stream()
                .map(vertex -> String.valueOf(vertex.properties().get("name")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.containsAll(List.of(GeoMerchantDataLoader.nameOf(0),
                        GeoMerchantDataLoader.nameOf(1), GeoMerchantDataLoader.nameOf(9))),
                "the ten nearest shops are the ones collected: " + names);
        assertTrue(!names.contains(GeoMerchantDataLoader.nameOf(50)),
                "shops beyond the limit are not collected: " + names);

        // without a limit the whole circle is collected (159 shops, 0 edges)
        com.pengcheng.phoenixgraph.query.result.Subgraph all = inline.executeSubgraph(NEAREST_1KM);
        assertEquals(GeoMerchantDataLoader.SHOPS_WITHIN_1_KM, all.vertices().size());
        // and the same on the other scheme
        assertEquals(10, edge.executeSubgraph(NEAREST_1KM + ".limit(10)").vertices().size());
    }

    @Test
    void theIndexHoldsTheS2CellOfEveryShop() {
        assertEquals(240L, inlineStore.geoIndex().count("Merchant", "location"));
        assertEquals(240L, edgeStore.geoIndex().count("Merchant", "location"));
        // the cell ids are the level-16 cells the schema declares, and a row carries the point
        List<Long> cells = inlineStore.geoIndex().cellIds("Merchant", "location");
        assertEquals(240, cells.size());
        long expected = com.pengcheng.phoenixgraph.storage.rocksdb.S2Cells
                .cellId(GeoMerchantDataLoader.pointOf(0), 16);
        assertTrue(cells.contains(expected), "shop 1 is indexed in the cell of its point");
        assertEquals(240, inlineStore.geoIndex().points("Merchant", "location").size());
    }
}
