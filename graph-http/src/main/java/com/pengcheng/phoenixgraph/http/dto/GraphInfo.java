package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Summary of a graph schema for the web UI: the graph name plus its vertex and
 * edge type names (used to populate the graph / vertex-type dropdowns) and, per
 * vertex type, the names of its {@code display} properties - the values a UI
 * should render (joined by a space) to label a vertex.
 */
public record GraphInfo(String name, String storage, List<String> vertexTypes, List<String> edgeTypes,
                        Map<String, List<String>> displayProperties) {

    public static GraphInfo of(GraphSchema schema) {
        Map<String, List<String>> display = new LinkedHashMap<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            display.put(vertex.name(), vertex.displayPropertyNames());
        }
        return new GraphInfo(
                schema.name(),
                schema.storage() == null ? "Aerospike" : schema.storage().type().configName(),
                schema.vertexSchemas().stream().map(v -> v.name()).toList(),
                schema.edgeSchemas().stream().map(e -> e.name()).toList(),
                Map.copyOf(display));
    }
}
