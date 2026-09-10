package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.storage.EdgeTableStore;
import com.pengcheng.phoenixgraph.storage.StoredEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link EdgeTableStore} reference implementation with source and
 * destination indexes (not thread-safe).
 */
public class InMemoryEdgeTableStore implements EdgeTableStore {

    private final Map<Object, StoredEdge> edges = new HashMap<>();
    private final Map<Object, List<Object>> sourceIndex = new HashMap<>();
    private final Map<Object, List<Object>> destinationIndex = new HashMap<>();

    @Override
    public void put(StoredEdge edge) {
        edges.put(edge.edgeId(), edge);
        sourceIndex.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.edgeId());
        destinationIndex.computeIfAbsent(edge.destination(), k -> new ArrayList<>()).add(edge.edgeId());
    }

    @Override
    public StoredEdge get(Object edgeId) {
        return edges.get(edgeId);
    }

    @Override
    public void remove(Object edgeId) {
        StoredEdge edge = edges.remove(edgeId);
        if (edge != null) {
            removeFromIndex(sourceIndex, edge.source(), edgeId);
            removeFromIndex(destinationIndex, edge.destination(), edgeId);
        }
    }

    @Override
    public Collection<StoredEdge> edgesBySource(Object source) {
        return byIndex(sourceIndex, source);
    }

    @Override
    public Collection<StoredEdge> edgesByDestination(Object destination) {
        return byIndex(destinationIndex, destination);
    }

    private void removeFromIndex(Map<Object, List<Object>> index, Object key, Object edgeId) {
        List<Object> ids = index.get(key);
        if (ids != null) {
            ids.remove(edgeId);
        }
    }

    private Collection<StoredEdge> byIndex(Map<Object, List<Object>> index, Object key) {
        List<Object> ids = index.get(key);
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(edges::get).filter(Objects::nonNull).toList();
    }
}
