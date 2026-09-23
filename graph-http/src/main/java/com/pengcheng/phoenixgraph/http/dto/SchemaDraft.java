package com.pengcheng.phoenixgraph.http.dto;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageSchema;
import com.pengcheng.phoenixgraph.schema.GraphStorageType;
import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A graph schema as the building screen describes it, and the conversion to and from the
 * {@link GraphSchema} the engine uses.
 *
 * <p>The editor's model is deliberately simpler than the storage model: no property
 * indexes (they are assigned on the way in, in declaration order), types spelled the way
 * a person writes them ({@code "string"}, {@code "list&lt;long&gt;"}, {@code "enum:A|B"})
 * and only the knobs the screen offers. {@link #toSchema} builds a schema from a draft and
 * {@link #toDraft} describes an existing schema as a draft, so a graph can be opened in the
 * editor, changed and saved again.
 *
 * @param name        the graph name, which is also the schema file name
 * @param description a sentence for the graph list
 * @param idType      property type of the vertex id ({@code "string"} or {@code "long"})
 * @param storage     where the graph is stored ({@code "Aerospike"} when absent)
 * @param vertices    the vertex types
 * @param edges       the edge types
 */
public record SchemaDraft(String name, String description, String idType, StorageDraft storage,
                          List<VertexDraft> vertices, List<EdgeDraft> edges) {

    public SchemaDraft {
        vertices = vertices == null ? List.of() : List.copyOf(vertices);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** Where a graph lives: the storage type plus its Knobs. */
    public record StorageDraft(String type, String path, Integer regionCount, Integer maxVersions,
                               Boolean cacheEdges) {
    }

    /** A vertex type: its name plus the properties that are not the id. */
    public record VertexDraft(String name, String description, String display, List<PropertyDraft> properties) {

        public VertexDraft {
            properties = properties == null ? List.of() : List.copyOf(properties);
        }
    }

    /**
     * An edge type: its name, direction, endpoints, properties and how many edges a vertex may keep.
     *
     * <p>The degree limits only mean something for the schemes that spread a hub over several rows
     * ({@code Aerospike-Edge}, {@code RocksDB-Edge}): an inline scheme keeps every edge of a vertex in one
     * record, where a limit would only refuse writes. A storage that cannot use them ignores them, which is
     * why the builder disables the fields for those schemes instead of hiding them.
     *
     * @param source        source vertex type ({@code null} for an undirected edge without endpoints)
     * @param target        target vertex type ({@code null} when the edge may dangle)
     * @param targetIsNull  whether the target may be absent ({@code isNull} in the schema)
     * @param maxOutDegree  most outgoing edges one vertex may keep ({@code null}/0 = unlimited)
     * @param maxInDegree   most incoming edges one vertex may keep ({@code null}/0 = unlimited)
     * @param maxDegree     the same limit for both directions of an undirected/bidirectional class
     */
    public record EdgeDraft(String name, String description, String direction, String source, String target,
                            Boolean targetIsNull, Integer maxOutDegree, Integer maxInDegree,
                            Integer maxDegree, List<PropertyDraft> properties) {

        public EdgeDraft {
            properties = properties == null ? List.of() : List.copyOf(properties);
        }
    }

    /**
     * A property.
     *
     * @param type      {@code string}, {@code int}, {@code long}, {@code double}, {@code boolean},
     *                  {@code object}, {@code list<...>}, {@code map<...>} or {@code enum:A|B}
     * @param display   whether the canvas labels elements with it
     * @param isVector  whether it is an embedding indexed with HNSW
     * @param isGeo     whether it is a geographic point indexed with S2 cells
     * @param vertex    for an edge endpoint property: the vertex type it points at
     * @param isNull    for an edge target: whether it may be absent
     */
    public record PropertyDraft(String name, String type, String description, Boolean display, Boolean isVector,
                                Boolean isGeo, String vertex, Boolean isNull, Number min, Number max,
                                Integer minLength, Integer maxLength) {
    }

    // ------------------------------------------------------------------ draft -> schema

    /** Builds the engine schema from the editor's model. */
    public GraphSchema toSchema(int schemaId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The graph needs a name");
        }
        PropertyType idPropertyType = parseType(idType == null || idType.isBlank() ? "string" : idType);
        GraphSchema schema = new GraphSchema(schemaId, name, description);
        int propertyId = 1;
        for (VertexDraft vertex : vertices) {
            if (vertex.name() == null || vertex.name().isBlank()) {
                throw new IllegalArgumentException("A vertex type needs a name");
            }
            VertexSchema vertexSchema = new VertexSchema(propertyId++, vertex.name(), vertex.description(),
                    new VertexPropertySchema(0, "id", "Vertex id", idPropertyType));
            int index = 1;
            for (PropertyDraft property : vertex.properties()) {
                VertexPropertySchema propertySchema = new VertexPropertySchema(index++,
                        requireName(property, RESERVED_VERTEX), property.description(), parseType(property.type()),
                        constraintsOf(property));
                decorate(propertySchema, property);
                vertexSchema.property(propertySchema);
                propertyId++;
            }
            if (vertex.display() != null && !vertex.display().isBlank()) {
                // the display property is flagged on the property itself
                for (VertexPropertySchema property : vertexSchema.properties()) {
                    if (property.name().equals(vertex.display())) {
                        property.display(true);
                    }
                }
            }
            schema.vertex(vertexSchema);
        }
        for (EdgeDraft edge : edges) {
            if (edge.name() == null || edge.name().isBlank()) {
                throw new IllegalArgumentException("An edge type needs a name");
            }
            EdgeDirection direction = parseDirection(edge.direction());
            EdgeEndpointSchema source = endpoint(propertyId++, "source", edge.source(), idPropertyType);
            EdgeEndpointSchema target = endpoint(propertyId++, "target", edge.target(), idPropertyType);
            if (Boolean.TRUE.equals(edge.targetIsNull()) && target != null) {
                target.nullable(true);
            }
            EdgeSchema edgeSchema = new EdgeSchema(propertyId++, edge.name(), edge.description(), source, target,
                    direction);
            if (edge.maxOutDegree() != null && edge.maxOutDegree() > 0) {
                edgeSchema.maxOutgoingEdges(edge.maxOutDegree());
            }
            if (edge.maxInDegree() != null && edge.maxInDegree() > 0) {
                edgeSchema.maxIncomingEdges(edge.maxInDegree());
            }
            if (edge.maxDegree() != null && edge.maxDegree() > 0) {
                edgeSchema.maxDegree(edge.maxDegree());
            }
            int index = 1;
            for (PropertyDraft property : edge.properties()) {
                EdgePropertySchema propertySchema = new EdgePropertySchema(index++,
                        requireName(property, RESERVED_EDGE), property.description(), parseType(property.type()),
                        constraintsOf(property));
                decorate(propertySchema, property);
                edgeSchema.property(propertySchema);
                propertyId++;
            }
            schema.edge(edgeSchema);
        }
        if (storage != null && storage.type() != null && !storage.type().isBlank()) {
            schema.storage(toStorage(storage));
        }
        return schema;
    }

    /**
     * Names the engine owns: every vertex has an {@code id} and the meta timestamps, every edge also has a
     * {@code source} and a {@code target}. Their indexes are fixed (0 for the id, -2/-1 for the timestamps),
     * so declaring a property with one of these names would either collide with the built-in one or hide it.
     */
    private static final java.util.Set<String> RESERVED_VERTEX =
            java.util.Set.of("id", "createdTime", "lastUpdatedTime");

    private static final java.util.Set<String> RESERVED_EDGE =
            java.util.Set.of("id", "source", "target", "createdTime", "lastUpdatedTime");

    private static String requireName(PropertyDraft property, java.util.Set<String> reserved) {
        String name = requireName(property);
        if (reserved.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is part of every element of this kind"
                    + " (its index is fixed), so it cannot be declared as a property");
        }
        return name;
    }

    private static String requireName(PropertyDraft property) {
        if (property.name() == null || property.name().isBlank()) {
            throw new IllegalArgumentException("Every property needs a name");
        }
        return property.name();
    }

    /** The endpoint property of an edge: named after the side it sits on. */
    private static EdgeEndpointSchema endpoint(int id, String role, String vertexName, PropertyType type) {
        if (vertexName == null || vertexName.isBlank()) {
            return null;
        }
        return new EdgeEndpointSchema(id, role, "Endpoint (" + role + ") in " + vertexName, type, vertexName);
    }

    /** The value constraints a draft declares, or none. */
    private static com.pengcheng.phoenixgraph.schema.PropertyConstraints constraintsOf(PropertyDraft draft) {
        if (draft.min() == null && draft.max() == null && draft.minLength() == null && draft.maxLength() == null) {
            return com.pengcheng.phoenixgraph.schema.PropertyConstraints.NONE;
        }
        return new com.pengcheng.phoenixgraph.schema.PropertyConstraints(
                draft.min() == null ? null : draft.min().doubleValue(),
                draft.max() == null ? null : draft.max().doubleValue(),
                draft.minLength(), draft.maxLength());
    }

    /** Copies the flags a draft carries: display, vector index and geo index. */
    private static void decorate(PropertySchema propertySchema, PropertyDraft draft) {
        if (Boolean.TRUE.equals(draft.display())) {
            propertySchema.display(true);
        }
        if (Boolean.TRUE.equals(draft.isVector())) {
            propertySchema.vector(true);
        }
        if (Boolean.TRUE.equals(draft.isGeo())) {
            propertySchema.geo(true);
        }
    }

    private static GraphStorageSchema toStorage(StorageDraft storage) {
        GraphStorageType type = GraphStorageType.fromConfigName(storage.type());
        return switch (type) {
            case AEROSPIKE -> GraphStorageSchema.aerospike();
            case AEROSPIKE_EDGE -> GraphStorageSchema.aerospikeEdge();
            case ROCKSDB_INLINE, ROCKSDB_EDGE -> GraphStorageSchema.rocksDb(type, storage.path(),
                    storage.regionCount() == null ? GraphStorageSchema.DEFAULT_REGION_COUNT
                            : storage.regionCount(),
                    storage.maxVersions() == null ? GraphStorageSchema.DEFAULT_MAX_VERSIONS
                            : storage.maxVersions(),
                    storage.cacheEdges() == null || storage.cacheEdges());
        };
    }

    private static EdgeDirection parseDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return EdgeDirection.DIRECTED;
        }
        try {
            return EdgeDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown edge direction '" + direction
                    + "'; use DIRECTED, UNDIRECTED or BIDIRECTIONAL");
        }
    }

    /** {@code string}, {@code list<long>}, {@code map<string>}, {@code enum:A|B} → the type. */
    public static PropertyType parseType(String text) {
        if (text == null || text.isBlank()) {
            return PropertyType.ScalarType.STRING;
        }
        String value = text.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("enum")) {
            String values = value.contains(":") ? value.substring(value.indexOf(':') + 1) : "";
            Set<String> allowed = new LinkedHashSet<>();
            for (String part : values.split("\\|")) {
                if (!part.isBlank()) {
                    allowed.add(part.trim());
                }
            }
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("An enum needs its values, e.g. enum:ACTIVE|FROZEN");
            }
            return new PropertyType.EnumType(allowed);
        }
        if (lower.startsWith("list<") && lower.endsWith(">")) {
            return new PropertyType.ListType(scalar(lower.substring(5, lower.length() - 1)));
        }
        if (lower.startsWith("map<") && lower.endsWith(">")) {
            return new PropertyType.MapType(scalar(lower.substring(4, lower.length() - 1)));
        }
        return scalar(lower);
    }

    private static PropertyType.ScalarType scalar(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "boolean", "bool" -> PropertyType.ScalarType.BOOLEAN;
            case "int", "integer" -> PropertyType.ScalarType.INT;
            case "long" -> PropertyType.ScalarType.LONG;
            case "float" -> PropertyType.ScalarType.FLOAT;
            case "double" -> PropertyType.ScalarType.DOUBLE;
            case "string", "text" -> PropertyType.ScalarType.STRING;
            case "object", "any" -> PropertyType.ScalarType.OBJECT;
            default -> throw new IllegalArgumentException("Unknown property type '" + text
                    + "'; use boolean, int, long, float, double, string, object, list<...>, map<...> or enum:A|B");
        };
    }

    // ------------------------------------------------------------------ schema -> draft

    /** Describes an existing schema the way the editor edits it. */
    public static SchemaDraft toDraft(GraphSchema schema) {
        List<VertexDraft> vertices = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            List<PropertyDraft> properties = new ArrayList<>();
            for (PropertySchema property : vertex.properties()) {
                if (property.id() == 0) {
                    continue; // the id property is implied by the vertex type
                }
                properties.add(describe(property));
            }
            vertices.add(new VertexDraft(vertex.name(), vertex.description(),
                    vertex.displayPropertyNames().isEmpty() ? null : vertex.displayPropertyNames().get(0),
                    properties));
        }
        List<EdgeDraft> edges = new ArrayList<>();
        for (EdgeSchema edge : schema.edgeSchemas()) {
            List<PropertyDraft> properties = new ArrayList<>();
            for (PropertySchema property : edge.properties()) {
                properties.add(describe(property));
            }
            String source = edge.source() == null ? null : edge.source().vertexName();
            String target = edge.target() == null ? null : edge.target().vertexName();
            edges.add(new EdgeDraft(edge.name(), edge.description(), edge.direction().name(), source, target,
                    edge.target() != null && edge.target().isNull(),
                    edge.maxOutgoingEdges(), edge.maxIncomingEdges(), edge.maxDegree(), properties));
        }
        PropertyType idType = schema.vertexSchemas().isEmpty() ? PropertyType.ScalarType.STRING
                : schema.vertexSchemas().get(0).idSchema().type();
        return new SchemaDraft(schema.name(), schema.description(), describeType(idType),
                describeStorage(schema.storage()), vertices, edges);
    }

    private static PropertyDraft describe(PropertySchema property) {
        return new PropertyDraft(property.name(), describeType(property.type()), property.description(),
                property.display(), property.isVector(), property.isGeo(),
                property instanceof EdgeEndpointSchema endpoint ? endpoint.vertexName() : null,
                property instanceof EdgeEndpointSchema endpoint ? endpoint.isNull() : null,
                property.constraints() == null ? null : property.constraints().min(),
                property.constraints() == null ? null : property.constraints().max(),
                property.constraints() == null ? null : property.constraints().minLength(),
                property.constraints() == null ? null : property.constraints().maxLength());
    }

    /** The type in the short form the editor shows. */
    public static String describeType(PropertyType type) {
        if (type instanceof PropertyType.ScalarType scalar) {
            return scalar.name().toLowerCase(Locale.ROOT);
        }
        if (type instanceof PropertyType.ListType list) {
            return "list<" + list.elementType().name().toLowerCase(Locale.ROOT) + ">";
        }
        if (type instanceof PropertyType.MapType map) {
            return "map<" + map.valueType().name().toLowerCase(Locale.ROOT) + ">";
        }
        if (type instanceof PropertyType.EnumType enums) {
            return "enum:" + String.join("|", enums.values());
        }
        return "string";
    }

    private static StorageDraft describeStorage(GraphStorageSchema storage) {
        if (storage == null) {
            return new StorageDraft(GraphStorageType.AEROSPIKE.configName(), null, null, null, null);
        }
        return new StorageDraft(storage.type().configName(), storage.path(), storage.regionCount(),
                storage.maxVersions(), storage.cacheEdges());
    }
}
