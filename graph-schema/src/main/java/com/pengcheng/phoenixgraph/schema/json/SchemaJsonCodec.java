package com.pengcheng.phoenixgraph.schema.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.SchemaConstraints;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;

/**
 * JSON codec for {@link GraphSchema}.
 *
 * <p>Uses a compact tagged representation for {@link PropertyType}:
 * <ul>
 *   <li>scalar: {@code "LONG"}</li>
 *   <li>list: {@code {"list": "STRING"}}</li>
 *   <li>map: {@code {"map": "STRING"}}</li>
 *   <li>enum: {@code {"enum": ["A", "B", "C"]}}</li>
 * </ul>
 */
public class SchemaJsonCodec {

    private final ObjectMapper mapper;

    public SchemaJsonCodec() {
        SimpleModule module = new SimpleModule("phoenixgraph-schema");
        module.addSerializer(PropertyType.class, new PropertyTypeSerializer());
        module.addDeserializer(PropertyType.class, new PropertyTypeDeserializer());
        this.mapper = new ObjectMapper().registerModule(module);
    }

    /** Serializes a graph schema to its (pretty-printed) JSON definition. */
    public String toJson(GraphSchema schema) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(schema));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize graph schema: " + schema.name(), e);
        }
    }

    /** Parses a graph schema from its JSON definition. */
    public GraphSchema fromJson(String json) {
        try {
            return toDomain(mapper.readValue(json, GraphSchemaDto.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse graph schema JSON", e);
        }
    }

    // --- domain -> DTO ---

    private GraphSchemaDto toDto(GraphSchema schema) {
        return new GraphSchemaDto(
                schema.id(), schema.name(), schema.description(), schema.constraints(),
                schema.vertexSchemas().stream().map(this::toVertexDto).toList(),
                schema.edgeSchemas().stream().map(this::toEdgeDto).toList());
    }

    private VertexSchemaDto toVertexDto(VertexSchema schema) {
        return new VertexSchemaDto(
                schema.id(), schema.name(), schema.description(),
                toPropertyDto(schema.idSchema()),
                toPropertyDto(schema.createdTime()),
                toPropertyDto(schema.lastUpdatedTime()),
                schema.properties().stream().map(this::toPropertyDto).toList());
    }

    private EdgeSchemaDto toEdgeDto(EdgeSchema schema) {
        return new EdgeSchemaDto(
                schema.id(), schema.name(), schema.description(),
                toPropertyDto(schema.idSchema()),
                toPropertyDto(schema.source()),
                toPropertyDto(schema.target()),
                toPropertyDto(schema.createdTime()),
                toPropertyDto(schema.lastUpdatedTime()),
                schema.properties().stream().map(this::toPropertyDto).toList());
    }

    private PropertyDto toPropertyDto(PropertySchema property) {
        return new PropertyDto(property.id(), property.name(), property.description(), property.type());
    }

    // --- DTO -> domain ---

    private GraphSchema toDomain(GraphSchemaDto dto) {
        SchemaConstraints constraints = dto.constraints() != null ? dto.constraints() : SchemaConstraints.DEFAULTS;
        GraphSchema graph = new GraphSchema(dto.id(), dto.name(), dto.description(), constraints);
        if (dto.vertexSchemas() != null) {
            for (VertexSchemaDto v : dto.vertexSchemas()) {
                graph.vertex(toVertexSchema(v));
            }
        }
        if (dto.edgeSchemas() != null) {
            for (EdgeSchemaDto e : dto.edgeSchemas()) {
                graph.edge(toEdgeSchema(e));
            }
        }
        return graph;
    }

    private VertexSchema toVertexSchema(VertexSchemaDto dto) {
        VertexPropertySchema idSchema = new VertexPropertySchema(
                dto.idSchema().id(), dto.idSchema().name(), dto.idSchema().description(), dto.idSchema().type());
        MetaPropertySchema createdTime = toMetaProperty(dto.createdTime(), MetaPropertySchema.createdTime());
        MetaPropertySchema lastUpdatedTime = toMetaProperty(dto.lastUpdatedTime(), MetaPropertySchema.lastUpdatedTime());
        VertexSchema schema = new VertexSchema(dto.id(), dto.name(), dto.description(), idSchema, createdTime, lastUpdatedTime);
        if (dto.properties() != null) {
            for (PropertyDto p : dto.properties()) {
                schema.property(new VertexPropertySchema(p.id(), p.name(), p.description(), p.type()));
            }
        }
        return schema;
    }

    private EdgeSchema toEdgeSchema(EdgeSchemaDto dto) {
        MetaPropertySchema createdTime = toMetaProperty(dto.createdTime(), MetaPropertySchema.createdTime());
        MetaPropertySchema lastUpdatedTime = toMetaProperty(dto.lastUpdatedTime(), MetaPropertySchema.lastUpdatedTime());
        EdgeSchema schema = new EdgeSchema(
                dto.id(), dto.name(), dto.description(),
                toEdgeProperty(dto.idSchema()), toEdgeProperty(dto.source()), toEdgeProperty(dto.target()),
                createdTime, lastUpdatedTime);
        if (dto.properties() != null) {
            for (PropertyDto p : dto.properties()) {
                schema.property(toEdgeProperty(p));
            }
        }
        return schema;
    }

    private EdgePropertySchema toEdgeProperty(PropertyDto dto) {
        return new EdgePropertySchema(dto.id(), dto.name(), dto.description(), dto.type());
    }

    private MetaPropertySchema toMetaProperty(PropertyDto dto, MetaPropertySchema defaultMeta) {
        if (dto == null) {
            return defaultMeta;
        }
        return new MetaPropertySchema(dto.id(), dto.name(), dto.description());
    }

    // --- PropertyType JSON support ---

    static final class PropertyTypeSerializer extends JsonSerializer<PropertyType> {
        @Override
        public void serialize(PropertyType value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value instanceof PropertyType.ScalarType scalar) {
                gen.writeString(scalar.name());
            } else if (value instanceof PropertyType.ListType list) {
                gen.writeStartObject();
                gen.writeStringField("list", list.elementType().name());
                gen.writeEndObject();
            } else if (value instanceof PropertyType.MapType map) {
                gen.writeStartObject();
                gen.writeStringField("map", map.valueType().name());
                gen.writeEndObject();
            } else if (value instanceof PropertyType.EnumType en) {
                gen.writeStartObject();
                gen.writeArrayFieldStart("enum");
                for (String v : en.values()) {
                    gen.writeString(v);
                }
                gen.writeEndArray();
                gen.writeEndObject();
            } else {
                throw new IllegalArgumentException("Unsupported PropertyType: " + value);
            }
        }
    }

    static final class PropertyTypeDeserializer extends JsonDeserializer<PropertyType> {
        @Override
        public PropertyType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isTextual()) {
                return PropertyType.ScalarType.valueOf(node.asText());
            }
            if (node.isObject()) {
                JsonNode list = node.get("list");
                if (list != null && list.isTextual()) {
                    return PropertyType.listOf(PropertyType.ScalarType.valueOf(list.asText()));
                }
                JsonNode map = node.get("map");
                if (map != null && map.isTextual()) {
                    return PropertyType.mapOf(PropertyType.ScalarType.valueOf(map.asText()));
                }
                JsonNode en = node.get("enum");
                if (en != null && en.isArray()) {
                    LinkedHashSet<String> values = new LinkedHashSet<>();
                    for (JsonNode v : en) {
                        values.add(v.asText());
                    }
                    return new PropertyType.EnumType(values);
                }
            }
            return (PropertyType) ctxt.handleUnexpectedToken(PropertyType.class, p);
        }
    }
}
