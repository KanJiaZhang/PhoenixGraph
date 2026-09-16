package com.pengcheng.phoenixgraph.hnsw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fully in-heap {@link HnswStore}: one neighbour list per (layer, vertex).
 *
 * <p>Used by the unit tests and by small in-memory indexes. It also serves as the
 * reference semantics of the layered graph: a link is symmetric, so
 * {@link #link} touches both endpoints and {@link #unlink} removes both sides.
 */
public final class InMemoryHnswStore implements HnswStore {

    private final Map<String, List<HnswNeighbor>> layers = new LinkedHashMap<>();
    private long links;
    private long unlinks;

    @Override
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        return List.copyOf(layers.getOrDefault(key(level, vertexId), List.of()));
    }

    @Override
    public List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        List<List<HnswNeighbor>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(neighbours(vertexId, level));
        }
        return out;
    }

    @Override
    public void linkAll(Object from, List<Object> to, int level) {
        for (Object other : to) {
            if (other.equals(from) || connected(from, other, level)) {
                continue;
            }
            String edgeId = edgeId(from, other, level);
            list(level, from).add(new HnswNeighbor(edgeId, other));
            list(level, other).add(new HnswNeighbor(edgeId, from));
            links++;
        }
    }

    @Override
    public void unlink(String edgeId, Object from, Object to, int level) {
        boolean removed = list(level, from).removeIf(n -> n.vertexId().equals(to));
        boolean mirrored = list(level, to).removeIf(n -> n.vertexId().equals(from));
        if (removed || mirrored) {
            unlinks++;
        }
    }

    private boolean connected(Object from, Object to, int level) {
        return list(level, from).stream().anyMatch(n -> n.vertexId().equals(to));
    }

    private List<HnswNeighbor> list(int level, Object vertexId) {
        return layers.computeIfAbsent(key(level, vertexId), k -> new ArrayList<>());
    }

    private static String key(int level, Object vertexId) {
        return level + ":" + vertexId;
    }

    /** Number of edges currently stored (each counted once). */
    public long edgeCount() {
        return layers.values().stream().mapToLong(List::size).sum() / 2;
    }

    /** Links created so far. */
    public long links() {
        return links;
    }

    /** Links removed so far (pruning). */
    public long unlinks() {
        return unlinks;
    }

    /** Highest degree over all vertices and layers. */
    public int maxDegree() {
        return layers.values().stream().mapToInt(List::size).max().orElse(0);
    }

    /** Vectors that have at least one neighbour on the given layer. */
    public int verticesOn(int level) {
        String prefix = level + ":";
        return (int) layers.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .filter(k -> !layers.get(k).isEmpty())
                .count();
    }
}
