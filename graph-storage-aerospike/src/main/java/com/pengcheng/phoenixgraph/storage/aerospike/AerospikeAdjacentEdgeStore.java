package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Record;
import com.pengcheng.phoenixgraph.storage.AdjacentEdgeStore;
import com.pengcheng.phoenixgraph.storage.StoredEdge;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aerospike-backed {@link AdjacentEdgeStore}.
 *
 * <p>Stores each edge inline in the vertex record as a map bin: the outgoing
 * column on the source vertex and a reverse (incoming) column on the
 * destination vertex, so traversal works from either side. Edges are returned
 * sorted by {@link StoredEdge#lastUpdatedTime()} descending. When the configured
 * maximum outgoing/incoming edge count is exceeded, the oldest edges are evicted
 * from both columns.
 */
public class AerospikeAdjacentEdgeStore implements AdjacentEdgeStore {

    private final AerospikeClient client;
    private final String namespace;
    private final String vertexSet;
    private final int maxOutgoingEdges;
    private final int maxIncomingEdges;

    public AerospikeAdjacentEdgeStore(AerospikeClient client, String namespace, String vertexSet) {
        this(client, namespace, vertexSet, 0, 0);
    }

    public AerospikeAdjacentEdgeStore(AerospikeClient client, String namespace, String vertexSet,
                                      int maxOutgoingEdges, int maxIncomingEdges) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.vertexSet = Objects.requireNonNull(vertexSet, "vertexSet");
        this.maxOutgoingEdges = maxOutgoingEdges;
        this.maxIncomingEdges = maxIncomingEdges;
    }

    public AerospikeAdjacentEdgeStore(String host, int port, String namespace, String vertexSet) {
        this(new AerospikeClient(host, port), namespace, vertexSet, 0, 0);
    }

    public AerospikeAdjacentEdgeStore(String host, int port, String namespace, String vertexSet,
                                      int maxOutgoingEdges, int maxIncomingEdges) {
        this(new AerospikeClient(host, port), namespace, vertexSet, maxOutgoingEdges, maxIncomingEdges);
    }

    @Override
    public void addEdge(StoredEdge edge) {
        Map<String, Object> out = readEdgeMap(edge.source(), OUT_EDGES_COLUMN);
        out.put(String.valueOf(edge.edgeId()), StoredEdgeCodec.serialize(edge));
        writeEdgeMap(edge.source(), OUT_EDGES_COLUMN, out);

        Map<String, Object> in = readEdgeMap(edge.destination(), IN_EDGES_COLUMN);
        in.put(String.valueOf(edge.edgeId()), StoredEdgeCodec.serialize(edge));
        writeEdgeMap(edge.destination(), IN_EDGES_COLUMN, in);

        trim(edge.source(), OUT_EDGES_COLUMN, maxOutgoingEdges, true);
        trim(edge.destination(), IN_EDGES_COLUMN, maxIncomingEdges, false);
    }

    @Override
    public List<StoredEdge> outgoingEdges(Object vertexId, int edgeSchemaId) {
        return sorted(readEdgeMap(vertexId, OUT_EDGES_COLUMN), edgeSchemaId);
    }

    @Override
    public List<StoredEdge> incomingEdges(Object vertexId, int edgeSchemaId) {
        return sorted(readEdgeMap(vertexId, IN_EDGES_COLUMN), edgeSchemaId);
    }

    @Override
    public void removeEdge(Object source, Object destination, int edgeSchemaId, Object edgeId) {
        removeFromMap(source, OUT_EDGES_COLUMN, edgeId);
        removeFromMap(destination, IN_EDGES_COLUMN, edgeId);
    }

    private void trim(Object vertexId, String column, int max, boolean outbound) {
        if (max <= 0) {
            return;
        }
        Map<String, Object> raw = readEdgeMap(vertexId, column);
        int excess = raw.size() - max;
        if (excess <= 0) {
            return;
        }
        List<StoredEdge> edges = raw.values().stream()
                .map(v -> StoredEdgeCodec.deserialize((Map<String, Object>) v))
                .sorted(Comparator.comparingLong(StoredEdge::lastUpdatedTime)) // oldest first
                .toList();
        for (StoredEdge evicted : edges.subList(0, excess)) {
            raw.remove(String.valueOf(evicted.edgeId()));
            if (outbound) {
                removeFromMap(evicted.destination(), IN_EDGES_COLUMN, evicted.edgeId());
            } else {
                removeFromMap(evicted.source(), OUT_EDGES_COLUMN, evicted.edgeId());
            }
        }
        writeEdgeMap(vertexId, column, raw);
    }

    private void removeFromMap(Object vertexId, String column, Object edgeId) {
        Map<String, Object> map = readEdgeMap(vertexId, column);
        if (map.remove(String.valueOf(edgeId)) != null) {
            writeEdgeMap(vertexId, column, map);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEdgeMap(Object vertexId, String column) {
        Record record = client.get(null, AerospikeKeys.of(namespace, vertexSet, vertexId));
        if (record == null || !(record.bins.get(column) instanceof Map<?, ?> existing)) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<String, Object>) existing);
    }

    private void writeEdgeMap(Object vertexId, String column, Map<String, Object> map) {
        client.put(null, AerospikeKeys.of(namespace, vertexSet, vertexId), new Bin(column, map));
    }

    private List<StoredEdge> sorted(Map<String, Object> edgeMap, int edgeSchemaId) {
        return edgeMap.values().stream()
                .map(v -> StoredEdgeCodec.deserialize((Map<String, Object>) v))
                .filter(e -> e.edgeSchemaId() == edgeSchemaId)
                .sorted(Comparator.comparingLong(StoredEdge::lastUpdatedTime).reversed())
                .toList();
    }
}
