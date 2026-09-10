package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.storage.StoredEdge;

import java.util.HashMap;
import java.util.Map;

/**
 * Serializes {@link StoredEdge} to Aerospike-compatible maps and back.
 */
public final class StoredEdgeCodec {

    private StoredEdgeCodec() {
    }

    public static Map<String, Object> serialize(StoredEdge edge) {
        Map<String, Object> map = new HashMap<>();
        map.put("edgeId", edge.edgeId());
        map.put("source", edge.source());
        map.put("destination", edge.destination());
        map.put("columns", edge.columns());
        map.put("lastModified", edge.lastModified());
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StoredEdge deserialize(Map<String, Object> map) {
        return new StoredEdge(
                map.get("edgeId"),
                map.get("source"),
                map.get("destination"),
                (Map<String, Object>) map.get("columns"),
                ((Number) map.get("lastModified")).longValue());
    }
}
