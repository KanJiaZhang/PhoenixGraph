package com.pengcheng.phoenixgraph.schema.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.schema.GraphMetaSchema;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * JSON codec for {@link GraphMetaSchema} ({@code GraphMetaSchema.json}).
 */
public class GraphMetaSchemaJsonCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Serializes a meta-schema to its (pretty-printed) JSON definition. */
    public String toJson(GraphMetaSchema metaSchema) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(metaSchema));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize meta-schema: " + metaSchema.name(), e);
        }
    }

    /** Parses a meta-schema from its JSON definition. */
    public GraphMetaSchema fromJson(String json) {
        try {
            return toDomain(mapper.readValue(json, GraphMetaSchemaDto.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse meta-schema JSON", e);
        }
    }

    private GraphMetaSchemaDto toDto(GraphMetaSchema metaSchema) {
        return new GraphMetaSchemaDto(
                metaSchema.id(), metaSchema.name(), metaSchema.description(),
                metaSchema.maxInDegree(), metaSchema.maxOutDegree(), metaSchema.maxDegree(),
                metaSchema.propertyBounds());
    }

    private GraphMetaSchema toDomain(GraphMetaSchemaDto dto) {
        return new GraphMetaSchema(dto.id(), dto.name(), dto.description(),
                dto.maxInDegree(), dto.maxOutDegree(), dto.maxDegree(),
                dto.propertyBounds());
    }
}
