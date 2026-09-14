package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;

import java.util.List;

/**
 * JSON DTO for an edge schema definition.
 *
 * <p>The {@code source}/{@code target} endpoints carry their vertex schema name
 * inside their {@link PropertyDto#vertex()} field.
 */
public record EdgeSchemaDto(int id, String name, String description,
                            PropertyDto idSchema,
                            PropertyDto source,
                            PropertyDto target,
                            PropertyDto createdTime,
                            PropertyDto lastUpdatedTime,
                            EdgeDirection direction,
                            List<PropertyDto> properties,
                            Integer maxOutgoingEdges,
                            Integer maxIncomingEdges,
                            Integer maxDegree) {
}
