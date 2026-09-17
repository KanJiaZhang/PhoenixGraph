package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;
import java.util.Map;

/**
 * The vectors and the extra properties of a batch of vertices, read together.
 *
 * @param vectors    one vector per requested id ({@code null} when absent)
 * @param properties one property map per requested id (the names asked for)
 */
public record HnswElements(List<float[]> vectors, List<Map<String, Object>> properties) {

    /** Properties of the vertex at {@code index}, never {@code null}. */
    public Map<String, Object> propertiesAt(int index) {
        if (properties == null || index >= properties.size() || properties.get(index) == null) {
            return Map.of();
        }
        return properties.get(index);
    }
}
