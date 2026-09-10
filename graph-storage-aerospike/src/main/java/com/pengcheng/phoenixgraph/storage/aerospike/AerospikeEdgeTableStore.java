package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.pengcheng.phoenixgraph.storage.EdgeTableStore;
import com.pengcheng.phoenixgraph.storage.StoredEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aerospike-backed {@link EdgeTableStore} using a separate edge set, plus
 * manual source/destination index sets (mapping a vertex id to its edge ids).
 */
public class AerospikeEdgeTableStore implements EdgeTableStore {

    private final AerospikeClient client;
    private final String namespace;
    private final String edgeSet;
    private final String sourceIndexSet;
    private final String destinationIndexSet;

    public AerospikeEdgeTableStore(AerospikeClient client, String namespace) {
        this(client, namespace, "edges", "edge_source_index", "edge_destination_index");
    }

    public AerospikeEdgeTableStore(AerospikeClient client, String namespace,
                                   String edgeSet, String sourceIndexSet, String destinationIndexSet) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.edgeSet = Objects.requireNonNull(edgeSet, "edgeSet");
        this.sourceIndexSet = Objects.requireNonNull(sourceIndexSet, "sourceIndexSet");
        this.destinationIndexSet = Objects.requireNonNull(destinationIndexSet, "destinationIndexSet");
    }

    @Override
    public void put(StoredEdge edge) {
        client.put(null, AerospikeKeys.of(namespace, edgeSet, edge.edgeId()),
                new Bin("source", Value.get(edge.source())),
                new Bin("destination", Value.get(edge.destination())),
                new Bin("lastModified", edge.lastModified()),
                new Bin("columns", edge.columns()));
        appendIndex(sourceIndexSet, edge.source(), edge.edgeId());
        appendIndex(destinationIndexSet, edge.destination(), edge.edgeId());
    }

    @Override
    public StoredEdge get(Object edgeId) {
        Record record = client.get(null, AerospikeKeys.of(namespace, edgeSet, edgeId));
        return record == null ? null : toEdge(edgeId, record.bins);
    }

    @Override
    public void remove(Object edgeId) {
        StoredEdge edge = get(edgeId);
        if (edge != null) {
            removeFromIndex(sourceIndexSet, edge.source(), edgeId);
            removeFromIndex(destinationIndexSet, edge.destination(), edgeId);
        }
        client.delete(null, AerospikeKeys.of(namespace, edgeSet, edgeId));
    }

    @Override
    public Collection<StoredEdge> edgesBySource(Object source) {
        return edgesByIndex(sourceIndexSet, source);
    }

    @Override
    public Collection<StoredEdge> edgesByDestination(Object destination) {
        return edgesByIndex(destinationIndexSet, destination);
    }

    private void appendIndex(String indexSet, Object vertexId, Object edgeId) {
        Record record = client.get(null, AerospikeKeys.of(namespace, indexSet, vertexId));
        List<Object> ids = new ArrayList<>();
        if (record != null && record.bins.get("edgeIds") instanceof List<?> existing) {
            ids.addAll(existing);
        }
        if (!ids.contains(edgeId)) {
            ids.add(edgeId);
        }
        client.put(null, AerospikeKeys.of(namespace, indexSet, vertexId), new Bin("edgeIds", ids));
    }

    private void removeFromIndex(String indexSet, Object vertexId, Object edgeId) {
        Record record = client.get(null, AerospikeKeys.of(namespace, indexSet, vertexId));
        if (record == null || !(record.bins.get("edgeIds") instanceof List<?> existing)) {
            return;
        }
        List<Object> ids = new ArrayList<>(existing);
        if (ids.remove(edgeId)) {
            client.put(null, AerospikeKeys.of(namespace, indexSet, vertexId), new Bin("edgeIds", ids));
        }
    }

    private Collection<StoredEdge> edgesByIndex(String indexSet, Object vertexId) {
        Record record = client.get(null, AerospikeKeys.of(namespace, indexSet, vertexId));
        if (record == null || !(record.bins.get("edgeIds") instanceof List<?> ids)) {
            return List.of();
        }
        List<StoredEdge> result = new ArrayList<>();
        for (Object id : ids) {
            StoredEdge edge = get(id);
            if (edge != null) {
                result.add(edge);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private StoredEdge toEdge(Object edgeId, Map<String, Object> bins) {
        return new StoredEdge(edgeId,
                bins.get("source"),
                bins.get("destination"),
                (Map<String, Object>) bins.get("columns"),
                ((Number) bins.get("lastModified")).longValue());
    }
}
