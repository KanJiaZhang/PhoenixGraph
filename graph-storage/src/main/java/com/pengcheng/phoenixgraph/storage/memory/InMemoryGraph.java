package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.core.Edge;
import com.pengcheng.phoenixgraph.core.Graph;
import com.pengcheng.phoenixgraph.core.SimpleEdge;
import com.pengcheng.phoenixgraph.core.SimpleVertex;
import com.pengcheng.phoenixgraph.core.Vertex;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A simple thread-safe in-memory {@link Graph} implementation.
 */
public class InMemoryGraph implements Graph {

    private final Map<Object, Vertex> vertices = new ConcurrentHashMap<>();
    private final Map<Object, Edge> edges = new ConcurrentHashMap<>();

    @Override
    public Vertex addVertex(VertexSchema schema, Object id, Map<String, ?> values) {
        Vertex vertex = new SimpleVertex(schema, id, values);
        vertices.put(id, vertex);
        return vertex;
    }

    @Override
    public Edge addEdge(EdgeSchema schema, Object edgeId, Object source, Object destination, Map<String, ?> values) {
        Edge edge = new SimpleEdge(schema, edgeId, source, destination, values);
        edges.put(edgeId, edge);
        return edge;
    }

    @Override
    public Vertex vertex(Object id) {
        return vertices.get(id);
    }

    @Override
    public Edge edge(Object id) {
        return edges.get(id);
    }

    @Override
    public Stream<Vertex> vertices() {
        return vertices.values().stream();
    }

    @Override
    public Stream<Edge> edges() {
        return edges.values().stream();
    }

    @Override
    public long vertexCount() {
        return vertices.size();
    }

    @Override
    public long edgeCount() {
        return edges.size();
    }

    @Override
    public void close() {
        vertices.clear();
        edges.clear();
    }
}
