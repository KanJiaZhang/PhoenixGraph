package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Schema definition for a class of vertices.
 *
 * <p>Declares the vertex schema {@code id}, {@code name} and {@code description},
 * a dedicated {@link VertexPropertySchema} for the vertex id (default index
 * {@value #ID_INDEX}), the {@code createdTime}/{@code lastUpdatedTime}
 * meta-properties (default indexes {@link MetaPropertySchema#CREATED_TIME_ID} and
 * {@link MetaPropertySchema#LAST_UPDATED_TIME_ID}), and aggregates the remaining
 * {@link VertexPropertySchema}s in the property list. Properties (including the
 * id schema) can be looked up by id or name, and both ids and names are unique
 * within a vertex schema.
 */
public class VertexSchema {

    /** Default index of the vertex id property schema. */
    public static final int ID_INDEX = 0;

    private final int id;
    private final String name;
    private final String description;
    private final VertexPropertySchema idSchema;
    private final MetaPropertySchema createdTime;
    private final MetaPropertySchema lastUpdatedTime;
    private final List<VertexPropertySchema> properties = new ArrayList<>();
    private final Map<Integer, VertexPropertySchema> propertiesById = new HashMap<>();
    private final Map<String, VertexPropertySchema> propertiesByName = new HashMap<>();
    private final Set<Integer> occupiedIds = new HashSet<>();

    public VertexSchema(int id, String name, String description, VertexPropertySchema idSchema,
                        MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime) {
        this.id = id;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Vertex schema name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.idSchema = Objects.requireNonNull(idSchema, "idSchema");
        this.createdTime = Objects.requireNonNull(createdTime, "createdTime");
        this.lastUpdatedTime = Objects.requireNonNull(lastUpdatedTime, "lastUpdatedTime");
        addProperty(idSchema);
        occupiedIds.add(createdTime.id());
        occupiedIds.add(lastUpdatedTime.id());
    }

    public VertexSchema(int id, String name, String description, VertexPropertySchema idSchema) {
        this(id, name, description, idSchema,
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime());
    }

    /** Convenience constructor creating a default id schema with index {@value #ID_INDEX}. */
    public VertexSchema(int id, String name, String description) {
        this(id, name, description,
                new VertexPropertySchema(ID_INDEX, "id", "Vertex id", PropertyType.ScalarType.LONG));
    }

    /** Registers a vertex property on this schema (fluent). */
    public VertexSchema property(VertexPropertySchema property) {
        Objects.requireNonNull(property, "property");
        addProperty(property);
        properties.add(property);
        return this;
    }

    private void addProperty(VertexPropertySchema property) {
        if (!occupiedIds.add(property.id())) {
            throw new IllegalArgumentException(
                    "Duplicate property id " + property.id() + " in vertex schema '" + name + "'");
        }
        if (propertiesByName.containsKey(property.name())) {
            throw new IllegalArgumentException(
                    "Duplicate property name '" + property.name() + "' in vertex schema '" + name + "'");
        }
        if (property.name().equals(createdTime.name()) || property.name().equals(lastUpdatedTime.name())) {
            throw new IllegalArgumentException(
                    "Property name '" + property.name() + "' is reserved for meta-properties in vertex schema '" + name + "'");
        }
        propertiesById.put(property.id(), property);
        propertiesByName.put(property.name(), property);
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** The dedicated vertex id property schema. */
    public VertexPropertySchema idSchema() {
        return idSchema;
    }

    /** The {@code createdTime} meta-property schema. */
    public MetaPropertySchema createdTime() {
        return createdTime;
    }

    /** The {@code lastUpdatedTime} meta-property schema. */
    public MetaPropertySchema lastUpdatedTime() {
        return lastUpdatedTime;
    }

    /** Immutable view of the (non-id, non-meta) vertex properties. */
    public List<VertexPropertySchema> properties() {
        return List.copyOf(properties);
    }

    /**
     * Immutable view of the properties flagged as {@link PropertySchema#display() display}
     * properties, in declaration order. A UI renders their values (joined by a
     * space) to identify the vertex.
     */
    public List<VertexPropertySchema> displayProperties() {
        return properties.stream().filter(VertexPropertySchema::display).toList();
    }

    /** Names of the {@link #displayProperties() display properties}, in declaration order. */
    public List<String> displayPropertyNames() {
        return displayProperties().stream().map(VertexPropertySchema::name).toList();
    }

    /** Looks up a property (including the id schema) by id, or {@code null} if not found. */
    public VertexPropertySchema property(int id) {
        return propertiesById.get(id);
    }

    /** Looks up a property (including the id schema) by name, or {@code null} if not found. */
    public VertexPropertySchema property(String name) {
        return propertiesByName.get(name);
    }
}
