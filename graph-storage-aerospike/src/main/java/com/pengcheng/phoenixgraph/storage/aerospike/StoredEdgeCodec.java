package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
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
        map.put("edgeSchemaId", edge.edgeSchemaId());
        map.put("source", edge.source());
        map.put("destination", edge.destination());
        map.put("direction", edge.direction().name());
        map.put("columns", edge.columns());
        map.put("lastUpdatedTime", edge.lastUpdatedTime());
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StoredEdge deserialize(Map<String, Object> map) {
        return new StoredEdge(
                map.get("edgeId"),
                ((Number) map.get("edgeSchemaId")).intValue(),
                map.get("source"),
                map.get("destination"),
                EdgeDirection.valueOf((String) map.get("direction")),
                (Map<String, Object>) map.get("columns"),
                ((Number) map.get("lastUpdatedTime")).longValue());
    }
}
