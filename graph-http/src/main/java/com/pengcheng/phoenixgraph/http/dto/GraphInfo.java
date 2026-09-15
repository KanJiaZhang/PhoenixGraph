package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.schema.GraphSchema;

import java.util.List;

/**
 * Summary of a graph schema for the web UI: the graph name plus its vertex and
 * edge type names (used to populate the graph / vertex-type dropdowns).
 */
public record GraphInfo(String name, List<String> vertexTypes, List<String> edgeTypes) {

    public static GraphInfo of(GraphSchema schema) {
        return new GraphInfo(
                schema.name(),
                schema.vertexSchemas().stream().map(v -> v.name()).toList(),
                schema.edgeSchemas().stream().map(e -> e.name()).toList());
    }
}
