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
            "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":1}')";

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
                (long) inline.execute("g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}',"
                        + " 2).count()").get(0),
                "a 2 km radius holds every shop");
        // a small radius holds the few shops right around the centre
        long near = (long) inline.execute("g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}',"
                + " 0.2).count()").get(0);
        assertTrue(near >= 20 && near <= 30, "0.2 km holds the first ~26 shops, got " + near);

        // both schemes answer the same
        for (String radius : List.of("0.2", "0.5", "1", "1.5")) {
            String query = "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}', " + radius
                    + ").count()";
            assertEquals(inline.execute(query), edge.execute(query), "radius " + radius + " km");
        }
    }

    @Test
    void resultsAreTheNearestShopsWithTheirDistance() {
        List<Object> distances = inline.execute(NEAREST_1KM + ".limit(5).values('distanceKm')");
        assertEquals(5, distances.size());
        double previous = -1;
        for (Object distance : distances) {
            double value = ((Number) distance).doubleValue();
            assertTrue(value <= 1d, "a hit inside the radius: " + value);
            assertTrue(value >= previous, "nearest first: " + distances);
            previous = value;
        }
        // the closest shop is the first of the spiral, 50 m from the centre
        assertEquals(GeoMerchantDataLoader.FIRST_DISTANCE_KM, ((Number) distances.get(0)).doubleValue(), 0.01d);

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
        assertTrue(first.containsKey("distanceKm"), first.toString());
        assertTrue(first.containsKey("name") && first.containsKey("category"), first.toString());
    }

    @Test
    void theSameQueryAnswersTheSameOnBothSchemes() {
        List<String> queries = List.of(
                NEAREST_1KM + ".limit(10).values('name')",
                NEAREST_1KM + ".count()",
                NEAREST_1KM + ".has('category','bakery').values('name')",
                "g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737,\"radiusKm\":0.4}')"
                        + ".limit(3).elementMap()",
                "g.V('1','Merchant').geoNear('{\"lat\":31.22,\"lon\":121.48}', 0.6).count()");
        for (String query : queries) {
            assertEquals(inline.execute(query), edge.execute(query), "query: " + query);
        }
    }

    @Test
    void anotherCentreFindsAnotherNeighbourhood() {
        long north = (long) inline.execute("g.V('1','Merchant').geoNear('{\"lat\":31.2404,\"lon\":121.4737}',"
                + " 0.3).count()").get(0);
        assertTrue(north > 0, "shops north of the centre");
        // the two circles overlap but are not the same set
        long centre = (long) inline.execute("g.V('1','Merchant').geoNear('{\"lat\":31.2304,\"lon\":121.4737}',"
                + " 0.3).count()").get(0);
        assertTrue(north != centre, "a different centre finds a different set (" + north + " vs " + centre + ")");
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
