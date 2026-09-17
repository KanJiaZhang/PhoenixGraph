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
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GeoIndexConfig;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyConstraints;
import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.SchemaConstraints;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
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
                toStorageDto(schema.storage()),
                schema.vertexSchemas().stream().map(this::toVertexDto).toList(),
                schema.edgeSchemas().stream().map(this::toEdgeDto).toList());
    }

    /** The storage block, or {@code null} for the Aerospike default (keeps old JSON unchanged). */
    private StorageSchemaDto toStorageDto(GraphStorageSchema storage) {
        if (storage == null || storage.isAerospike()) {
            return null;
        }
        return new StorageSchemaDto(storage.type().configName(), storage.path(), storage.regionCount(),
                storage.maxVersions(), storage.cacheEdges());
    }

    /** Parses the storage block: absent means Aerospike, a RocksDB block must carry a path. */
    private GraphStorageSchema toStorage(GraphSchemaDto dto) {
        StorageSchemaDto storage = dto.storage();
        if (storage == null || storage.type() == null || storage.type().isBlank()) {
            return GraphStorageSchema.AEROSPIKE;
        }
        GraphStorageType type = GraphStorageType.fromConfigName(storage.type());
        if (type.isAerospike()) {
            return GraphStorageSchema.AEROSPIKE;
        }
        return GraphStorageSchema.rocksDb(type, storage.path(),
                storage.regionCount() == null ? GraphStorageSchema.DEFAULT_REGION_COUNT : storage.regionCount(),
                storage.maxVersions() == null ? GraphStorageSchema.DEFAULT_MAX_VERSIONS : storage.maxVersions(),
                storage.cacheEdges() == null || storage.cacheEdges());
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
                toEndpointDto(schema.source()),
                toEndpointDto(schema.target()),
                toPropertyDto(schema.createdTime()),
                toPropertyDto(schema.lastUpdatedTime()),
                schema.direction(),
                schema.properties().stream().map(this::toPropertyDto).toList(),
                schema.maxOutgoingEdges(),
                schema.maxIncomingEdges(),
                schema.maxDegree());
    }

    private PropertyDto toPropertyDto(PropertySchema property) {
        PropertyConstraints constraints = property.constraints();
        VectorIndexConfig vector = property.vectorConfig();
        GeoIndexConfig geo = property.geoConfig();
        return new PropertyDto(property.id(), property.name(), property.description(), property.type(),
                constraints.min(), constraints.max(), constraints.minLength(), constraints.maxLength(), null,
                property.display() ? Boolean.TRUE : null,
                property.isVector() ? Boolean.TRUE : null,
                vector == null ? null : vector.metric(),
                vector == null ? null : vector.m(),
                vector == null ? null : vector.efConstruction(),
                vector == null ? null : vector.efSearch(),
                property.isGeo() ? Boolean.TRUE : null,
                geo == null ? null : geo.level(),
                geo == null ? null : geo.maxCells());
    }

    private PropertyDto toEndpointDto(EdgeEndpointSchema endpoint) {
        PropertyConstraints constraints = endpoint.constraints();
        return new PropertyDto(endpoint.id(), endpoint.name(), endpoint.description(), endpoint.type(),
                constraints.min(), constraints.max(), constraints.minLength(), constraints.maxLength(),
                endpoint.vertexName(), null);
    }

    // --- DTO -> domain ---

    private GraphSchema toDomain(GraphSchemaDto dto) {
        SchemaConstraints constraints = dto.constraints() != null ? dto.constraints() : SchemaConstraints.DEFAULTS;
        GraphSchema graph = new GraphSchema(dto.id(), dto.name(), dto.description(), constraints)
                .storage(toStorage(dto));
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
                dto.idSchema().id(), dto.idSchema().name(), dto.idSchema().description(), dto.idSchema().type(),
                toConstraints(dto.idSchema()));
        MetaPropertySchema createdTime = toMetaProperty(dto.createdTime(), MetaPropertySchema.createdTime());
        MetaPropertySchema lastUpdatedTime = toMetaProperty(dto.lastUpdatedTime(), MetaPropertySchema.lastUpdatedTime());
        VertexSchema schema = new VertexSchema(dto.id(), dto.name(), dto.description(), idSchema, createdTime, lastUpdatedTime);
        if (dto.properties() != null) {
            for (PropertyDto p : dto.properties()) {
                VertexPropertySchema property = toVectorProperty(
                        new VertexPropertySchema(p.id(), p.name(), p.description(), p.type(),
                                toConstraints(p)).display(Boolean.TRUE.equals(p.display())), p);
                schema.property(toGeoProperty(property, p));
            }
        }
        return schema;
    }

    private EdgeSchema toEdgeSchema(EdgeSchemaDto dto) {
        MetaPropertySchema createdTime = toMetaProperty(dto.createdTime(), MetaPropertySchema.createdTime());
        MetaPropertySchema lastUpdatedTime = toMetaProperty(dto.lastUpdatedTime(), MetaPropertySchema.lastUpdatedTime());
        EdgeDirection direction = dto.direction() != null ? dto.direction() : EdgeDirection.DIRECTED;
        EdgeSchema schema = new EdgeSchema(
                dto.id(), dto.name(), dto.description(),
                toEdgeProperty(dto.idSchema()), toEndpoint(dto.source()), toEndpoint(dto.target()),
                createdTime, lastUpdatedTime, direction, 0, 0, 0);
        if (dto.maxOutgoingEdges() != null) {
            schema.maxOutgoingEdges(dto.maxOutgoingEdges());
        }
        if (dto.maxIncomingEdges() != null) {
            schema.maxIncomingEdges(dto.maxIncomingEdges());
        }
        if (dto.maxDegree() != null) {
            schema.maxDegree(dto.maxDegree());
        }
        if (dto.properties() != null) {
            for (PropertyDto p : dto.properties()) {
                schema.property(toEdgeProperty(p));
            }
        }
        return schema;
    }

    private EdgeEndpointSchema toEndpoint(PropertyDto dto) {
        return new EdgeEndpointSchema(dto.id(), dto.name(), dto.description(), dto.type(),
                dto.vertex(), toConstraints(dto));
    }

    /**
     * Applies the vector index declaration of a property DTO ({@code isVector} plus
     * its optional metric/m/ef tunables).
     */
    /** Applies the geo settings of a DTO to a property. */
    private static <T extends PropertySchema> T toGeoProperty(T property, PropertyDto dto) {
        if (dto.isGeo() == null || !dto.isGeo()) {
            return property;
        }
        GeoIndexConfig defaults = GeoIndexConfig.DEFAULT;
        int level = dto.geoLevel() == null ? defaults.level() : dto.geoLevel();
        int maxCells = dto.geoMaxCells() == null ? defaults.maxCells() : dto.geoMaxCells();
        return (T) property.geo(new GeoIndexConfig(level, maxCells));
    }

    private static <T extends PropertySchema> T toVectorProperty(T property, PropertyDto dto) {
        if (Boolean.TRUE.equals(dto.isVector())) {
            property.vector(VectorIndexConfig.of(dto.metric(), dto.m(), dto.efConstruction(), dto.efSearch()));
        }
        return property;
    }

    private EdgePropertySchema toEdgeProperty(PropertyDto dto) {
        return new EdgePropertySchema(dto.id(), dto.name(), dto.description(), dto.type(),
                toConstraints(dto)).display(Boolean.TRUE.equals(dto.display()));
    }

    private MetaPropertySchema toMetaProperty(PropertyDto dto, MetaPropertySchema defaultMeta) {
        if (dto == null) {
            return defaultMeta;
        }
        return new MetaPropertySchema(dto.id(), dto.name(), dto.description(), toConstraints(dto));
    }

    private static PropertyConstraints toConstraints(PropertyDto dto) {
        if (dto == null) {
            return PropertyConstraints.NONE;
        }
        return new PropertyConstraints(dto.min(), dto.max(), dto.minLength(), dto.maxLength());
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
