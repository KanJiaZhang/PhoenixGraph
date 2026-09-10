package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.Map;
import java.util.stream.Stream;

/**
 * A graph: the top-level container for vertices and edges.
 */
public interface Graph extends AutoCloseable {

    Vertex addVertex(VertexSchema schema, Object id, Map<String, ?> values);

    Edge addEdge(EdgeSchema schema, Object edgeId, Object source, Object destination, Map<String, ?> values);

    Vertex vertex(Object id);

    Edge edge(Object id);

    Stream<Vertex> vertices();

    Stream<Edge> edges();

    long vertexCount();

    long edgeCount();

    @Override
    void close();
}
