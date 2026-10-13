package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.SchemaConstraints;

import java.util.List;

/**
 * JSON DTO for a graph schema definition.
 *
 * @param schemaVersion the format version of this file (see {@link SchemaJsonCodec#SCHEMA_VERSION}); absent
 *                      means version 1, which is what every file written before the field existed is
 */
public record GraphSchemaDto(int id, String name, String description,
                             SchemaConstraints constraints,
                             StorageSchemaDto storage,
                             List<VertexSchemaDto> vertexSchemas,
                             List<EdgeSchemaDto> edgeSchemas,
                             Integer schemaVersion) {
}
