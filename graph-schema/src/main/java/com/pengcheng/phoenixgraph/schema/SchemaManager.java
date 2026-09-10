package com.pengcheng.phoenixgraph.schema;

import java.util.Collection;

/**
 * Registry and access point for the schema of a single graph.
 */
public interface SchemaManager {

    void addPropertyKey(PropertyKey key);

    void addVertexLabel(VertexLabel label);

    void addEdgeLabel(EdgeLabel label);

    PropertyKey propertyKey(String name);

    VertexLabel vertexLabel(String name);

    EdgeLabel edgeLabel(String name);

    Collection<PropertyKey> propertyKeys();

    Collection<VertexLabel> vertexLabels();

    Collection<EdgeLabel> edgeLabels();
}
