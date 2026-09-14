package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.storage.ColumnStore;
import com.pengcheng.phoenixgraph.storage.VertexStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link VertexStore} reference implementation backed by a
 * {@link ColumnStore} (not thread-safe).
 */
public class InMemoryVertexStore implements VertexStore {

    private final ColumnStore vertices;

    public InMemoryVertexStore(ColumnStore vertices) {
        this.vertices = Objects.requireNonNull(vertices, "vertices");
    }

    @Override
    public void putVertex(Object vertexId, Map<Integer, Object> properties) {
        Map<String, Object> columns = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
            columns.put(column(entry.getKey()), entry.getValue());
        }
        vertices.put(vertexId, columns);
    }

    @Override
    public Map<Integer, Object> getVertex(Object vertexId) {
        Map<String, Object> columns = vertices.get(vertexId);
        if (columns == null) {
            return null;
        }
        Map<Integer, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            properties.put(Integer.parseInt(entry.getKey()), entry.getValue());
        }
        return properties;
    }

    @Override
    public void updateVertexProperty(Object vertexId, int propertyIndex, Object value) {
        vertices.putColumn(vertexId, column(propertyIndex), value);
    }

    @Override
    public Object getVertexProperty(Object vertexId, int propertyIndex) {
        return vertices.getColumn(vertexId, column(propertyIndex));
    }

    @Override
    public void removeVertexProperty(Object vertexId, int propertyIndex) {
        vertices.removeColumn(vertexId, column(propertyIndex));
    }

    @Override
    public void removeVertex(Object vertexId) {
        vertices.remove(vertexId);
    }

    private static String column(int propertyIndex) {
        return Integer.toString(propertyIndex);
    }
}
