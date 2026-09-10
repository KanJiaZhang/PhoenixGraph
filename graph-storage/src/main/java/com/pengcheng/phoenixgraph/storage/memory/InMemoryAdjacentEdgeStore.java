package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.storage.AdjacentEdgeStore;
import com.pengcheng.phoenixgraph.storage.ColumnStore;
import com.pengcheng.phoenixgraph.storage.StoredEdge;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link AdjacentEdgeStore} reference implementation.
 *
 * <p>Stores each edge inline in the vertex records of a {@link ColumnStore}:
 * {@link #OUT_EDGES_COLUMN} on the source and {@link #IN_EDGES_COLUMN} (reverse
 * edge) on the destination. When the configured maximum outgoing/incoming edge
 * count is exceeded, the oldest edges (by lastModified) are evicted from both
 * columns.
 */
public class InMemoryAdjacentEdgeStore implements AdjacentEdgeStore {

    private final ColumnStore vertices;
    private final int maxOutgoingEdges;
    private final int maxIncomingEdges;

    public InMemoryAdjacentEdgeStore(ColumnStore vertices) {
        this(vertices, 0, 0);
    }

    public InMemoryAdjacentEdgeStore(ColumnStore vertices, int maxOutgoingEdges, int maxIncomingEdges) {
        this.vertices = Objects.requireNonNull(vertices, "vertices");
        this.maxOutgoingEdges = maxOutgoingEdges;
        this.maxIncomingEdges = maxIncomingEdges;
    }

    @Override
    public int maxOutgoingEdges() {
        return maxOutgoingEdges;
    }

    @Override
    public int maxIncomingEdges() {
        return maxIncomingEdges;
    }

    @Override
    public void addEdge(StoredEdge edge) {
        Map<Object, StoredEdge> out = readMap(edge.source(), OUT_EDGES_COLUMN);
        out.put(edge.edgeId(), edge);
        vertices.putColumn(edge.source(), OUT_EDGES_COLUMN, out);

        Map<Object, StoredEdge> in = readMap(edge.destination(), IN_EDGES_COLUMN);
        in.put(edge.edgeId(), edge);
        vertices.putColumn(edge.destination(), IN_EDGES_COLUMN, in);

        trim(edge.source(), OUT_EDGES_COLUMN, maxOutgoingEdges, true);
        trim(edge.destination(), IN_EDGES_COLUMN, maxIncomingEdges, false);
    }

    @Override
    public List<StoredEdge> outgoingEdges(Object vertexId) {
        return sorted(readMap(vertexId, OUT_EDGES_COLUMN));
    }

    @Override
    public List<StoredEdge> incomingEdges(Object vertexId) {
        return sorted(readMap(vertexId, IN_EDGES_COLUMN));
    }

    @Override
    public void removeEdge(Object source, Object destination, Object edgeId) {
        removeFromMap(source, OUT_EDGES_COLUMN, edgeId);
        removeFromMap(destination, IN_EDGES_COLUMN, edgeId);
    }

    private void trim(Object vertexId, String column, int max, boolean outbound) {
        if (max <= 0) {
            return;
        }
        Map<Object, StoredEdge> map = readMap(vertexId, column);
        int excess = map.size() - max;
        if (excess <= 0) {
            return;
        }
        List<StoredEdge> toEvict = map.values().stream()
                .sorted(Comparator.comparingLong(StoredEdge::lastModified)) // oldest first
                .limit(excess)
                .toList();
        for (StoredEdge evicted : toEvict) {
            map.remove(evicted.edgeId());
            if (outbound) {
                removeFromMap(evicted.destination(), IN_EDGES_COLUMN, evicted.edgeId());
            } else {
                removeFromMap(evicted.source(), OUT_EDGES_COLUMN, evicted.edgeId());
            }
        }
        vertices.putColumn(vertexId, column, map);
    }

    private void removeFromMap(Object vertexId, String column, Object edgeId) {
        Map<Object, StoredEdge> map = readMap(vertexId, column);
        if (map.remove(edgeId) != null) {
            vertices.putColumn(vertexId, column, map);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Object, StoredEdge> readMap(Object vertexId, String column) {
        Object existing = vertices.getColumn(vertexId, column);
        if (existing == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<Object, StoredEdge>) existing);
    }

    private List<StoredEdge> sorted(Map<Object, StoredEdge> edges) {
        return edges.values().stream()
                .sorted(Comparator.comparingLong(StoredEdge::lastModified).reversed())
                .toList();
    }
}
