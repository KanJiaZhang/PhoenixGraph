package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.schema.GeoIndexConfig;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.rocksdb.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The geo index of a RocksDB graph: one table (column family) per geo property, keyed by the
 * S2 cell id of the point.
 *
 * <pre>
 * key    cellId (8 bytes, big-endian) : vertexId
 * value  lat (8 bytes) : lon (8 bytes)
 * </pre>
 *
 * <p>Because cell ids are nested, the id ranges of the cells covering a circle select exactly
 * the points of that circle (plus a few of the neighbouring cells), so a radius query is a
 * handful of range scans over this table - never a scan of the vertex table. The point is kept
 * in the index row as well, so distances are computed from the index alone: the vertex table is
 * only read for the hits a query actually returns.
 *
 * <p>The index is maintained by the vertex writes themselves ({@link RocksDbVertexTable} adds
 * and removes rows in the same batch as the record), which is what makes it automatic: writing
 * a vertex with a geo property indexes it, changing or removing the property reindexes it.
 */
public final class RocksDbGeoIndex {

    /** Prefix of a geo index table name. */
    public static final String FAMILY_PREFIX = "geo:";

    /** Separator between the cell id and the vertex id in a key. */
    private static final byte SEPARATOR = (byte) ':';

    /** How many candidate rows a query scans before it stops looking (a safety net). */
    private static final int MAX_CANDIDATES = 200_000;

    private final RocksDbHandle handle;
    private final GraphSchema schema;

    public RocksDbGeoIndex(RocksDbHandle handle, GraphSchema schema) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** The column family (table) of a geo index. */
    public static String familyOf(String vertexName, String propertyName) {
        return FAMILY_PREFIX + vertexName + ":" + propertyName;
    }

    /** The column families every geo property of a schema needs. */
    public static Set<String> columnFamilies(GraphSchema schema) {
        Set<String> families = new LinkedHashSet<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema)) {
            families.add(familyOf(property.vertexName(), property.propertyName()));
        }
        return families;
    }

    /** The index key of a point: its cell id, then the vertex id. */
    public static byte[] key(GeoPoint point, int level, Object vertexId) {
        return key(S2Cells.cellId(point, level), vertexId);
    }

    /** The index key of an already computed cell id. */
    public static byte[] key(long cellId, Object vertexId) {
        byte[] id = RocksDbBytes.utf8(String.valueOf(vertexId));
        byte[] key = new byte[RocksDbHash.HASH_BYTES + 1 + id.length];
        RocksDbHash.putLong(key, 0, cellId);
        key[RocksDbHash.HASH_BYTES] = SEPARATOR;
        System.arraycopy(id, 0, key, RocksDbHash.HASH_BYTES + 1, id.length);
        return key;
    }

    /** The cell id a key was indexed under. */
    public static long cellIdOfKey(byte[] key) {
        if (key.length < RocksDbHash.HASH_BYTES) {
            throw new RocksDbException("Malformed geo index key of " + key.length + " bytes");
        }
        return RocksDbHash.readLong(key, 0);
    }

    /** The vertex id a key belongs to. */
    public static String vertexIdOfKey(byte[] key) {
        int from = RocksDbHash.HASH_BYTES + 1;
        if (key.length <= from) {
            throw new RocksDbException("Malformed geo index key of " + key.length + " bytes");
        }
        return RocksDbBytes.text(Arrays.copyOfRange(key, from, key.length));
    }

    /** The value of an index row: the point itself. */
    public static byte[] value(GeoPoint point) {
        RocksDbBytes.Writer writer = new RocksDbBytes.Writer();
        writer.writeDouble(point.lat());
        writer.writeDouble(point.lon());
        return writer.toByteArray();
    }

    /** Decodes an index row into its point. */
    public static GeoPoint pointOfValue(byte[] value) {
        RocksDbBytes.Reader reader = new RocksDbBytes.Reader(value);
        return new GeoPoint(reader.readDouble(), reader.readDouble());
    }

    /** Whether a vertex type declares any geo property. */
    public boolean hasGeoProperties(String vertexName) {
        return !GeoIndexSchemas.geoProperties(schema, vertexName).isEmpty();
    }

    /** The property ids of the geo properties of a vertex type. */
    public static List<Integer> propertyIds(GraphSchema schema, String vertexName) {
        List<Integer> ids = new ArrayList<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema, vertexName)) {
            ids.add(property.propertyId());
        }
        return ids;
    }

    /** The geo property schema of a vertex type and property index, or {@code null}. */
    public static VertexPropertySchema geoProperty(VertexSchema vertexSchema, int propertyId) {
        VertexPropertySchema property = vertexSchema.property(propertyId);
        return property != null && property.isGeo() ? property : null;
    }

    /** The index settings of a vertex type's geo property. */
    private GeoIndexConfig configOf(String vertexName, String propertyName) {
        GeoIndexSchemas.GeoProperty property =
                GeoIndexSchemas.geoProperty(schema, vertexName, propertyName);
        return property == null ? GeoIndexConfig.DEFAULT : property.config();
    }

    /**
     * Adds the index rows of a vertex to a batch: for every geo property that has a value in
     * the record, one row keyed by its cell id.
     */
    public void index(WriteBatch batch, String vertexName, Object vertexId,
                      Map<Integer, RocksDbPropertyValue> properties) {
        VertexSchema vertexSchema = schema.vertexSchema(vertexName);
        if (vertexSchema == null) {
            return;
        }
        for (GeoIndexSchemas.GeoProperty geo : GeoIndexSchemas.geoProperties(schema, vertexName)) {
            RocksDbPropertyValue value = properties.get(geo.propertyId());
            GeoPoint point = value == null ? null : GeoPoint.from(value.value());
            if (point == null) {
                continue;
            }
            handle.put(batch, familyOf(vertexName, geo.propertyName()),
                    key(point, geo.config().level(), vertexId), value(point));
        }
    }

    /** Removes the index rows of the given points (used when a point changes or a vertex goes). */
    public void unindex(WriteBatch batch, String vertexName, Object vertexId,
                        Map<Integer, RocksDbPropertyValue> properties) {
        for (GeoIndexSchemas.GeoProperty geo : GeoIndexSchemas.geoProperties(schema, vertexName)) {
            RocksDbPropertyValue value = properties.get(geo.propertyId());
            GeoPoint point = value == null ? null : GeoPoint.from(value.value());
            if (point == null) {
                continue;
            }
            handle.delete(batch, familyOf(vertexName, geo.propertyName()),
                    key(point, geo.config().level(), vertexId));
        }
    }

    /** Removes every index row of a vertex (all of its geo properties). */
    public void unindexAll(WriteBatch batch, String vertexName, Object vertexId,
                           Map<Integer, RocksDbPropertyValue> properties) {
        unindex(batch, vertexName, vertexId, properties);
    }

    /**
     * Searches the index of one geo property: covers the circle, scans the cell ranges, drops
     * the candidates that are farther than the radius and returns the rest, nearest first.
     */
    public List<GeoHit> search(String vertexName, String propertyName, GeoSearch search) {
        GeoIndexConfig config = configOf(vertexName, propertyName);
        List<S2Cells.CellRange> ranges = S2Cells.coveringRanges(search, config.level(), config.maxCells());
        String family = familyOf(vertexName, propertyName);
        List<GeoHit> hits = new ArrayList<>();
        int candidates = 0;
        for (S2Cells.CellRange range : ranges) {
            // the covering cells are at or above the index level, so the sentinel keys are the
            // outer bounds of the cell id range: every descendant of the cell falls inside it
            byte[] from = key(range.min(), "");
            byte[] to = RocksDbKeys.prefixEnd(key(range.max(), ""));
            try (RocksDbHandle.RocksDbRange iterator = handle.iterator(family, from, to)) {
                while (iterator.isValid() && candidates < MAX_CANDIDATES) {
                    candidates++;
                    GeoPoint point = pointOfValue(iterator.value());
                    double distance = search.center().distanceKm(point);
                    if (distance <= search.radiusKm()) {
                        hits.add(new GeoHit(vertexName, vertexIdOfKey(iterator.key()), point, distance));
                    }
                    iterator.next();
                }
            }
        }
        hits.sort(GeoHit::compareTo);
        if (search.maxResults() > 0 && hits.size() > search.maxResults()) {
            return List.copyOf(hits.subList(0, search.maxResults()));
        }
        return List.copyOf(hits);
    }

    /** The number of indexed rows of a geo property (diagnostics and tests). */
    public long count(String vertexName, String propertyName) {
        long count = 0;
        try (RocksDbHandle.RocksDbRange range =
                     handle.iterator(familyOf(vertexName, propertyName), null, null)) {
            while (range.isValid()) {
                count++;
                range.next();
            }
        }
        return count;
    }

    /** The indexed points of a geo property (diagnostics and tests). */
    public List<GeoPoint> points(String vertexName, String propertyName) {
        List<GeoPoint> points = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange range =
                     handle.iterator(familyOf(vertexName, propertyName), null, null)) {
            while (range.isValid()) {
                points.add(pointOfValue(range.value()));
                range.next();
            }
        }
        return points;
    }

    /** The cell ids the index holds, ascending (diagnostics and tests). */
    public List<Long> cellIds(String vertexName, String propertyName) {
        List<Long> ids = new ArrayList<>();
        try (RocksDbHandle.RocksDbRange range =
                     handle.iterator(familyOf(vertexName, propertyName), null, null)) {
            while (range.isValid()) {
                ids.add(cellIdOfKey(range.key()));
                range.next();
            }
        }
        return ids;
    }
}
