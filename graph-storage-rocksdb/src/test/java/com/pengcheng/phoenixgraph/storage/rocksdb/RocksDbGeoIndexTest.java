package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.GeoIndexConfig;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S2 geo index: cell arithmetic, the index table the vertex writes maintain, and the
 * radius search that runs over it.
 */
class RocksDbGeoIndexTest {

    /** People's Square, Shanghai - the centre the demo data is built around. */
    private static final GeoPoint CENTRE = new GeoPoint(31.2304d, 121.4737d);

    private static final int LOCATION = 1;
    private static final int NAME = 2;

    @TempDir
    Path directory;

    private RocksDbHandle handle;
    private RocksDbVertexTable table;
    private RocksDbGeoIndex index;
    private GraphSchema schema;

    @BeforeEach
    void setUp() {
        VertexPropertySchema location = new VertexPropertySchema(LOCATION, "location", "where the shop is",
                new PropertyType.MapType(PropertyType.ScalarType.DOUBLE));
        location.geo(GeoIndexConfig.DEFAULT);
        VertexSchema merchant = new VertexSchema(1, "Merchant", "a shop")
                .property(new VertexPropertySchema(NAME, "name", "shop name", PropertyType.ScalarType.STRING))
                .property(location);
        schema = new GraphSchema(1, "geo-test").vertex(merchant);

        RocksDbStoreConfig config = RocksDbStoreConfig.plain(directory.resolve("db").toString());
        handle = RocksDbHandle.open(config, families(schema));
        table = new RocksDbVertexTable(handle, schema, config, new KeyLocks(), null);
        index = table.geoIndex();
    }

    /** The vertex tables plus the geo index tables of a schema. */
    private static List<String> families(GraphSchema schema) {
        List<String> families = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            families.add(RocksDbVertexTable.familyOf(vertex));
        }
        families.addAll(RocksDbGeoIndex.columnFamilies(schema));
        return families;
    }

    @AfterEach
    void tearDown() {
        if (handle != null) {
            handle.close();
        }
    }

    // --- cell arithmetic ---

    @Test
    void aPointIsIndexedInTheCellOfItsLevel() {
        long level16 = S2Cells.cellId(CENTRE, 16);
        long level14 = S2Cells.cellId(CENTRE, 14);
        assertEquals(16, S2Cells.levelOf(level16));
        assertEquals(14, S2Cells.levelOf(level14));
        // a level 14 cell is an ancestor of the level 16 cell that holds the point
        assertEquals(level14, new com.google.common.geometry.S2CellId(level16).parent(14).id());
        assertTrue(new com.google.common.geometry.S2CellId(level14).rangeMin().id()
                <= level16);
        assertTrue(level16 <= new com.google.common.geometry.S2CellId(level14).rangeMax().id(),
                "the deeper cell id lies inside the range of its ancestor");
    }

    @Test
    void theCoveringLevelFollowsTheRadiusAndNeverBeatsTheIndex() {
        // ~200 m cells at level 16: the index of this schema
        assertEquals(16, S2Cells.DEFAULT_INDEX_LEVEL);
        // a 1 km circle is covered at a coarser level (fewer, bigger cells)
        assertTrue(S2Cells.levelForRadius(1_000d) < 16, "1 km should cover coarser than the index");
        assertTrue(S2Cells.levelForRadius(100d) >= 15);
        // the covering never goes finer than the index, otherwise the scan would miss rows
        List<S2Cells.CellRange> coarse = S2Cells.coveringRanges(GeoSearch.of(CENTRE, 1_000d), 16, 32);
        assertTrue(coarse.size() <= 32, "the cell budget is respected: " + coarse.size());
        assertTrue(coarse.size() >= 1);
        for (S2Cells.CellRange range : coarse) {
            assertTrue(range.level() <= 16, "covering deeper than the index: " + range.level());
        }
        // even when the query asks for a finer level, the covering stops at the index's
        List<S2Cells.CellRange> fine = S2Cells.coveringRanges(GeoSearch.of(CENTRE, 50d).withLevel(20), 16, 32);
        for (S2Cells.CellRange range : fine) {
            assertTrue(range.level() <= 16, "covering deeper than the index: " + range.level());
        }
    }

    @Test
    void coveringRangesCoverTheCentreAndGrowWithTheRadius() {
        // at one level, a wider circle needs at least as many cells as a narrow one
        List<S2Cells.CellRange> small = S2Cells.coveringRanges(GeoSearch.of(CENTRE, 500d).withLevel(14), 16, 64);
        List<S2Cells.CellRange> large = S2Cells.coveringRanges(GeoSearch.of(CENTRE, 5_000d).withLevel(14), 16, 64);
        assertTrue(large.size() >= small.size(), "a wider circle needs at least as many cells");
        assertTrue(small.size() >= 1);
        long centreCell = S2Cells.cellId(CENTRE, 16);
        assertTrue(small.stream().anyMatch(range -> range.contains(centreCell)),
                "the centre must be inside the covering");
    }

    // --- the index the vertex writes maintain ---

    @Test
    void writingAVertexWithAGeoPropertyIndexesIt() {
        put(1L, "coffee", 0.4d, 0.3d);
        put(2L, "bakery", -0.5d, 0.2d);
        assertEquals(2L, index.count("Merchant", "location"));
        assertEquals(2, index.points("Merchant", "location").size());
        // the key carries the cell id of the level the schema declares
        long stored = index.cellIds("Merchant", "location").get(0);
        assertEquals(GeoIndexConfig.DEFAULT.level(), S2Cells.levelOf(stored));
    }

    @Test
    void radiusSearchReturnsThePointsInsideTheCircleWithTheirDistance() {
        put(1L, "at-centre", 0d, 0d);
        put(2L, "north-500m", kmToLat(0.5d), 0d);
        put(3L, "east-800m", 0d, kmToLon(0.8d));
        put(4L, "south-west-1500m", -kmToLat(1.06d), -kmToLon(1.06d));
        put(5L, "far-5km", kmToLat(5d), 0d);

        List<GeoHit> hits = index.search("Merchant", "location", GeoSearch.of(CENTRE, 1_000d));
        assertEquals(List.of("at-centre", "north-500m", "east-800m"),
                hits.stream().map(hit -> nameOf(hit.vertexId())).toList(),
                "only the shops within 1 km, nearest first");
        // distances are real great-circle distances
        assertEquals(0d, hits.get(0).distanceMeters(), 1e-3);
        assertEquals(500d, hits.get(1).distanceMeters(), 10d);
        assertEquals(800d, hits.get(2).distanceMeters(), 20d);

        // a wider radius brings the two farther shops in, still ordered
        List<GeoHit> wider = index.search("Merchant", "location", GeoSearch.of(CENTRE, 2_000d));
        assertEquals(4, wider.size());
        assertEquals("south-west-1500m", nameOf(wider.get(3).vertexId()));
        assertTrue(wider.get(3).distanceMeters() > 1_400d && wider.get(3).distanceMeters() < 1_600d,
                "distance " + wider.get(3).distanceMeters());

        // and the centre itself is found with a small radius
        assertEquals(1, index.search("Merchant", "location", GeoSearch.of(CENTRE, 50d)).size());
        assertTrue(index.search("Merchant", "location", GeoSearch.of(new GeoPoint(0d, 0d), 1_000d)).isEmpty());
    }

    @Test
    void theSearchHonoursAResultLimit() {
        for (int i = 0; i < 20; i++) {
            put((long) i, "shop-" + i, kmToLat(i * 0.02d), 0d);
        }
        List<GeoHit> limited = index.search("Merchant", "location",
                GeoSearch.of(CENTRE, 1_000d).withMaxResults(5));
        assertEquals(5, limited.size());
        assertEquals("shop-0", nameOf(limited.get(0).vertexId()));
        assertEquals("shop-4", nameOf(limited.get(4).vertexId()));
    }

    @Test
    void movingAShopMovesItsIndexRow() {
        put(1L, "shop", 0d, 0d);
        assertEquals(1, index.search("Merchant", "location", GeoSearch.of(CENTRE, 1_000d)).size());
        // write the same vertex with a point 3 km away
        put(1L, "shop", kmToLat(3d), 0d, 200L); // a newer point moves the shop
        assertEquals(1L, index.count("Merchant", "location"), "the old row is removed, not kept");
        assertTrue(index.search("Merchant", "location", GeoSearch.of(CENTRE, 1_000d)).isEmpty());
        assertEquals(1, index.search("Merchant", "location",
                GeoSearch.of(new GeoPoint(CENTRE.lat() + kmToLat(3d), CENTRE.lon()), 1_000d)).size());
    }

    @Test
    void deletingAVertexRemovesItsIndexRow() {
        put(1L, "shop", 0d, 0d);
        put(2L, "other", kmToLat(0.2d), 0d);
        assertEquals(2L, index.count("Merchant", "location"));
        assertTrue(table.delete("Merchant", 1L));
        assertEquals(1L, index.count("Merchant", "location"));
        assertEquals(1, index.search("Merchant", "location", GeoSearch.of(CENTRE, 1_000d)).size());
    }

    @Test
    void anOlderWriteDoesNotMoveTheIndex() {
        put(1L, "shop", 0d, 0d, 500L);
        // same property, older timestamp: the record keeps the newer point, so must the index
        put(1L, "shop", kmToLat(3d), 0d, 400L);
        assertEquals(1, index.search("Merchant", "location", GeoSearch.of(CENTRE, 1_000d)).size());
    }

    @Test
    void aPointCanBeStoredInSeveralAcceptedShapes() {
        // map with lat/lon
        table.upsert(new RocksDbVertexUpsert(1L, schema.vertexSchema("Merchant"),
                Map.of(LOCATION, new RocksDbPropertyValue(Map.of("lat", CENTRE.lat(), "lon", CENTRE.lon()), 10L)),
                1L, 10L));
        // GeoJSON list [lon, lat]
        table.upsert(new RocksDbVertexUpsert(2L, schema.vertexSchema("Merchant"),
                Map.of(LOCATION, new RocksDbPropertyValue(List.of(CENTRE.lon(), CENTRE.lat()), 10L)), 1L, 10L));
        // text form
        table.upsert(new RocksDbVertexUpsert(3L, schema.vertexSchema("Merchant"),
                Map.of(LOCATION, new RocksDbPropertyValue(CENTRE.lat() + "," + CENTRE.lon(), 10L)), 1L, 10L));
        assertEquals(3L, index.count("Merchant", "location"));
        assertEquals(3, index.search("Merchant", "location", GeoSearch.of(CENTRE, 100d)).size());
    }

    @Test
    void aValueThatIsNotAPointIsNotIndexed() {
        table.upsert(new RocksDbVertexUpsert(1L, schema.vertexSchema("Merchant"),
                Map.of(LOCATION, new RocksDbPropertyValue("not a point", 10L)), 1L, 10L));
        assertEquals(0L, index.count("Merchant", "location"));
        assertEquals(WriteOutcome.SUCCESS, table.upsert(new RocksDbVertexUpsert(2L,
                schema.vertexSchema("Merchant"), Map.of(NAME, new RocksDbPropertyValue("no geo", 10L)), 1L, 10L)));
        assertEquals(0L, index.count("Merchant", "location"));
    }

    @Test
    void theIndexIsOneTablePerGeoProperty() {
        assertEquals("geo:Merchant:location", RocksDbGeoIndex.familyOf("Merchant", "location"));
        assertEquals(List.of("geo:Merchant:location"), List.copyOf(RocksDbGeoIndex.columnFamilies(schema)));
        assertEquals(1, GeoIndexSchemas.geoProperties(schema).size());
        GeoIndexSchemas.GeoProperty property = GeoIndexSchemas.geoProperties(schema).get(0);
        assertEquals("Merchant.location", property.indexName());
        assertEquals(LOCATION, property.propertyId());
        assertEquals(GeoIndexConfig.DEFAULT, property.config());
        assertTrue(handle.columnFamilies().contains("geo:Merchant:location"));
    }

    @Test
    void pointValuesRoundTripThroughTheIndexRow() {
        byte[] value = RocksDbGeoIndex.value(CENTRE);
        assertEquals(CENTRE, RocksDbGeoIndex.pointOfValue(value));
        byte[] key = RocksDbGeoIndex.key(CENTRE, 15, 42L);
        assertEquals(S2Cells.cellId(CENTRE, 15), RocksDbGeoIndex.cellIdOfKey(key));
        assertEquals("42", RocksDbGeoIndex.vertexIdOfKey(key));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(91d, 0d));
    }

    // --- helpers ---

    private void put(long id, String name, double latOffsetDeg, double lonOffsetDeg) {
        put(id, name, latOffsetDeg, lonOffsetDeg, 100L + id);
    }

    private void put(long id, String name, double latOffsetDeg, double lonOffsetDeg, long version) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("lat", CENTRE.lat() + latOffsetDeg);
        location.put("lon", CENTRE.lon() + lonOffsetDeg);
        table.upsert(new RocksDbVertexUpsert(id, schema.vertexSchema("Merchant"),
                Map.of(LOCATION, new RocksDbPropertyValue(location, version),
                        NAME, new RocksDbPropertyValue(name, version)), 1L, version));
    }

    private String nameOf(Object vertexId) {
        var vertex = table.read("Merchant", vertexId, List.of(NAME));
        return String.valueOf(vertex.value(NAME));
    }

    /** Converts a north/south distance in kilometres into a latitude offset. */
    private static double kmToLat(double km) {
        return km / 111.19d;
    }

    /** Converts an east/west distance in kilometres into a longitude offset at the centre. */
    private static double kmToLon(double km) {
        return km / (111.32d * Math.cos(Math.toRadians(CENTRE.lat())));
    }
}
