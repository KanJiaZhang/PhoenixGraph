package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.SchemaConstraints;

import java.util.List;

/**
 * JSON DTO for a graph schema definition.
 */
public record GraphSchemaDto(int id, String name, String description,
                             SchemaConstraints constraints,
                             StorageSchemaDto storage,
                             List<VertexSchemaDto> vertexSchemas,
                             List<EdgeSchemaDto> edgeSchemas) {
}
