package com.pengcheng.phoenixgraph.storage;

import java.util.Map;

/**
 * Vertex storage strategy: a vertex id maps to a set of property columns.
 *
 * <p>This is the vertex counterpart of the edge stores
 * ({@link AdjacentEdgeStore}, {@link EdgeTableStore}). A vertex and its
 * properties are stored as columns in a single key/value record keyed by vertex
 * id; {@link ColumnStore} provides a generic implementation basis.
 *
 * <p>Properties are keyed by their <em>property index</em> (the {@code id} of
 * the corresponding {@code VertexPropertySchema}), which keeps the storage
 * layout compact and independent of property names.
 */
public interface VertexStore {

    /** Stores (or replaces) the full property set of the vertex, keyed by property index. */
    void putVertex(Object vertexId, Map<Integer, Object> properties);

    /** Reads all properties of the vertex, or {@code null} if absent. */
    Map<Integer, Object> getVertex(Object vertexId);

    /** Updates a single property of the vertex. */
    void updateVertexProperty(Object vertexId, int propertyIndex, Object value);

    /** Reads a single property of the vertex, or {@code null} if absent. */
    Object getVertexProperty(Object vertexId, int propertyIndex);

    /** Removes a single property from the vertex. */
    void removeVertexProperty(Object vertexId, int propertyIndex);

    /** Removes the whole vertex record. */
    void removeVertex(Object vertexId);
}
