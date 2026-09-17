package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Info;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.IndexType;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.Statement;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Geo queries on Aerospike.
 *
 * <p>Aerospike answers a radius query with a {@code geo2dsphere} secondary index over the bin
 * that holds the point ({@link #ensureIndex} declares it for every {@code isGeo} property).
 * That index is an enterprise feature: a community server refuses to create it, and the bin
 * value has to be a GeoJSON string for the index to match. Both are handled here by falling back
 * to a scan of the set with the distance computed per record, so the same Gremlin
 * {@code geoNear} query works on either server.
 *
 * <p>The durable index of a graph is therefore maintained by the RocksDB storage schemes (see
 * {@code graph-storage-rocksdb}); on Aerospike a geo query is as expensive as the set is large,
 * which is exactly the trade-off the two implementations make.
 */
public final class AerospikeGeoIndex {

    private AerospikeGeoIndex() {
    }

    /** The name of the secondary index of a bin. */
    public static String indexName(String set, String binName) {
        return "geo_" + set + "_" + binName;
    }

    /**
     * Declares the bin as a {@code geo2dsphere} secondary index, best effort: an index that
     * already exists is fine, and a server that does not support it (community) only gets a
     * log entry - the query then scans.
     *
     * @return whether the index exists after this call
     */
    public static boolean ensureIndex(AerospikeClient client, String namespace, String set, String binName,
                                      BusinessLog businessLog) {
        String name = indexName(set, binName);
        try {
            client.createIndex(null, namespace, set, name, binName, IndexType.GEO2DSPHERE);
            log(businessLog, "ensureGeoIndex", binName, true, "created");
            return true;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.INDEX_ALREADY_EXISTS) {
                log(businessLog, "ensureGeoIndex", binName, true, "already exists");
                return true;
            }
            // community servers (and sets whose values are not GeoJSON) land here
            log(businessLog, "ensureGeoIndex", binName, false,
                    "not available (" + e.getMessage() + "); geo queries will scan the set");
            return false;
        }
    }

    /** Declares the geo index of every {@code isGeo} property of a graph. */
    public static List<String> ensureIndexes(AerospikeClient client, String namespace, String set,
                                             GraphSchema schema, BusinessLog businessLog) {
        List<String> indexed = new ArrayList<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema)) {
            String bin = AerospikeVertexStore.propertyBin(property.propertyId());
            if (ensureIndex(client, namespace, set, bin, businessLog)) {
                indexed.add(property.indexName());
            }
        }
        return List.copyOf(indexed);
    }

    /** Whether the server reports the geo index of a bin (used to decide how to query). */
    public static boolean hasIndex(AerospikeClient client, String namespace, String set, String binName) {
        String name = indexName(set, binName);
        try {
            String response = Info.request(null, client.getNodes()[0], "sindex/" + namespace + "/" + set + "/" + name);
            return response != null && !response.isBlank() && !response.startsWith("ERROR");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Runs a radius query with the secondary index. Only usable when the index exists and the
     * stored value is a GeoJSON point; the caller falls back to a scan.
     */
    public static List<GeoHit> searchByIndex(AerospikeClient client, String namespace, String set,
                                             String vertexName, String propertyName, int propertyId,
                                             GeoSearch search) {
        String bin = AerospikeVertexStore.propertyBin(propertyId);
        GeoIndexSchemas.GeoProperty property = new GeoIndexSchemas.GeoProperty(vertexName, propertyName,
                propertyId, null);
        Statement statement = new Statement();
        statement.setNamespace(namespace);
        statement.setSetName(set);
        statement.setFilter(Filter.geoWithinRadius(bin, search.center().lat(), search.center().lon(),
                search.radiusMeters()));
        List<GeoHit> hits = new ArrayList<>();
        try (RecordSet records = client.query(new QueryPolicy(), statement)) {
            while (records.next()) {
                com.aerospike.client.Key key = records.getKey();
                Object vertexId = key == null || key.userKey == null ? null : key.userKey.getObject();
                if (vertexId == null) {
                    continue;
                }
                GeoPoint point = GeoPoint.from(records.getRecord().getValue(bin));
                if (point == null) {
                    continue;
                }
                double distance = search.center().distanceMeters(point);
                if (distance <= search.radiusMeters()) {
                    hits.add(new GeoHit(vertexName, vertexId, point, distance));
                }
            }
        }
        hits.sort(GeoHit::compareTo);
        log(null, "circle", property.indexName(), true, hits.size() + " hit(s) via the geo index");
        return List.copyOf(hits);
    }

    /**
     * Runs a radius query by scanning the set and computing every distance - the fallback that
     * makes the query work on a server without the geo index.
     */
    public static List<GeoHit> searchByScan(AerospikeVertexStore vertexStore, String vertexName,
                                            String propertyName, int propertyId, GeoSearch search,
                                            BusinessLog businessLog) {
        List<StoredVertex> vertices = vertexStore.scanVerticesWithProperty(propertyId, List.of(propertyId));
        // only the wanted number of hits is kept while the set is scanned
        GeoHit.TopK top = new GeoHit.TopK(search.maxResults());
        List<GeoHit> all = search.maxResults() > 0 ? null : new ArrayList<>();
        for (StoredVertex vertex : vertices) {
            VertexPropertyValue value = vertex.properties().get(propertyId);
            GeoPoint point = value == null ? null : GeoPoint.from(value.value());
            if (point == null) {
                continue;
            }
            double distance = search.center().distanceMeters(point);
            if (distance <= search.radiusMeters()) {
                GeoHit hit = new GeoHit(vertexName, vertex.vertexId(), point, distance);
                if (all != null) {
                    all.add(hit);
                } else {
                    top.offer(hit);
                }
            }
        }
        List<GeoHit> hits = all != null ? GeoHit.TopK.copyOf(all) : top.collect(List.of());
        log(businessLog, "circle", vertexName + "." + propertyName, true,
                hits.size() + " hit(s) of " + vertices.size() + " scanned");
        return hits;
    }

    /** The stored point of a vertex property (used by tests and diagnostics). */
    public static GeoPoint pointOf(StoredVertex vertex, int propertyId) {
        VertexPropertyValue value = vertex.properties().get(propertyId);
        return value == null ? null : GeoPoint.from(value.value());
    }

    private static void log(BusinessLog businessLog, String operation, String target, boolean success,
                            String detail) {
        if (businessLog == null) {
            return;
        }
        BusinessLogEntry entry = success
                ? BusinessLogEntry.success(operation, Objects.toString(target, "?"), Map.of("detail", detail))
                : BusinessLogEntry.failure(operation, Objects.toString(target, "?"), Map.of("detail", detail));
        businessLog.record(entry);
    }
}
