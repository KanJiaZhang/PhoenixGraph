package com.pengcheng.phoenixgraph.hnsw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-heap {@link HnswVectorSource} used while building an index: the whole
 * dataset fits in memory easily (10k x 128 floats is 5 MB), and keeping it there
 * turns the vector lookups of a build into map reads instead of storage round
 * trips.
 */
public final class HeapHnswVectorSource implements HnswVectorSource {

    private final Map<Object, float[]> vectors = new LinkedHashMap<>();
    private int dimensions;

    /** Empty source; add vectors with {@link #put(Object, float[])}. */
    public HeapHnswVectorSource() {
    }

    public HeapHnswVectorSource(Map<Object, float[]> vectors) {
        vectors.forEach(this::put);
    }

    /** Stores (or replaces) the vector of a vertex. */
    public HeapHnswVectorSource put(Object vertexId, float[] vector) {
        if (vector == null) {
            return this;
        }
        vectors.put(vertexId, vector);
        dimensions = Math.max(dimensions, vector.length);
        return this;
    }

    public int size() {
        return vectors.size();
    }

    /** Vertex ids in insertion order (the order a build should follow). */
    public List<Object> ids() {
        return List.copyOf(vectors.keySet());
    }

    @Override
    public float[] vector(Object vertexId) {
        return vectors.get(vertexId);
    }

    @Override
    public List<float[]> vectors(List<Object> vertexIds) {
        List<float[]> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(vectors.get(vertexId));
        }
        return out;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
