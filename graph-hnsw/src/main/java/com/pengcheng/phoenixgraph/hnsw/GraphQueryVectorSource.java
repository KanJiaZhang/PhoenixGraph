package com.pengcheng.phoenixgraph.hnsw;

import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HnswVectorSource} reading the vectors from a graph property through the
 * storage-agnostic {@link GraphQuery} layer.
 *
 * <p>A batch lookup becomes one (native) batch read of the vector property, which
 * is what the search uses when it expands a node.
 */
public final class GraphQueryVectorSource implements HnswVectorSource {

    private final GraphQuery graphQuery;
    private final String vertexName;
    private final String vectorProperty;
    private final int dimensions;

    public GraphQueryVectorSource(GraphQuery graphQuery, String vertexName, String vectorProperty) {
        this(graphQuery, vertexName, vectorProperty, 0);
    }

    public GraphQueryVectorSource(GraphQuery graphQuery, String vertexName, String vectorProperty,
                                  int dimensions) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.vertexName = Objects.requireNonNull(vertexName, "vertexName");
        this.vectorProperty = Objects.requireNonNull(vectorProperty, "vectorProperty");
        this.dimensions = dimensions;
    }

    @Override
    public float[] vector(Object vertexId) {
        VertexQueryResult vertex = graphQuery.readVertex(vertexId, vertexName, List.of(vectorProperty));
        if (vertex == null) {
            return null;
        }
        return VectorCodec.toFloats(vertex.properties().get(vectorProperty));
    }

    @Override
    public List<float[]> vectors(List<Object> vertexIds) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<VertexQueryResult> vertices =
                graphQuery.readVertices(vertexName, vertexIds, List.of(vectorProperty));
        List<float[]> out = new ArrayList<>(vertexIds.size());
        for (VertexQueryResult vertex : vertices) {
            out.add(vertex == null ? null : VectorCodec.toFloats(vertex.properties().get(vectorProperty)));
        }
        return out;
    }

    /**
     * Reads the vectors and the extra properties in <em>one</em> batch: the query
     * layer turns the projection into a single storage read of those bins, so a
     * filtered search does not pay an extra round trip per expansion.
     */
    @Override
    public HnswElements elements(List<Object> vertexIds, List<String> extraProperties) {
        if (vertexIds.isEmpty()) {
            return new HnswElements(List.of(), List.of());
        }
        List<String> projection = new ArrayList<>();
        projection.add(vectorProperty);
        if (extraProperties != null) {
            for (String property : extraProperties) {
                if (property != null && !projection.contains(property)) {
                    projection.add(property);
                }
            }
        }
        List<VertexQueryResult> vertices = graphQuery.readVertices(vertexName, vertexIds, projection);
        List<float[]> vectors = new ArrayList<>(vertexIds.size());
        List<Map<String, Object>> properties = new ArrayList<>(vertexIds.size());
        for (VertexQueryResult vertex : vertices) {
            if (vertex == null) {
                vectors.add(null);
                properties.add(Map.of());
                continue;
            }
            vectors.add(VectorCodec.toFloats(vertex.properties().get(vectorProperty)));
            Map<String, Object> values = new LinkedHashMap<>();
            if (extraProperties != null) {
                for (String property : extraProperties) {
                    Object value = vertex.properties().get(property);
                    if (value != null) {
                        values.put(property, value);
                    }
                }
            }
            properties.add(values);
        }
        return new HnswElements(vectors, properties);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
