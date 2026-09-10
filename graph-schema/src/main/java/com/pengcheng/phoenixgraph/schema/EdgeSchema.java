package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Schema definition for a class of edges.
 *
 * <p>Declares the edge schema {@code id}, {@code name} and {@code description},
 * plus dedicated property schemas: the edge {@code idSchema} (default index
 * {@value #ID_INDEX}), the {@code source} and {@code target} endpoints, and the
 * {@code createdTime}/{@code lastUpdatedTime} meta-properties. All remaining edge
 * properties are aggregated in the property list. Properties (including
 * id/source/target) can be looked up by id or name, and both ids and names are
 * unique within an edge schema.
 */
public class EdgeSchema {

    /** Default index of the edge id property schema. */
    public static final int ID_INDEX = 0;

    private final int id;
    private final String name;
    private final String description;
    private final EdgePropertySchema idSchema;
    private final EdgePropertySchema source;
    private final EdgePropertySchema target;
    private final MetaPropertySchema createdTime;
    private final MetaPropertySchema lastUpdatedTime;
    private final List<EdgePropertySchema> properties = new ArrayList<>();
    private final Map<Integer, EdgePropertySchema> propertiesById = new HashMap<>();
    private final Map<String, EdgePropertySchema> propertiesByName = new HashMap<>();
    private final Set<Integer> occupiedIds = new HashSet<>();

    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema idSchema, EdgePropertySchema source, EdgePropertySchema target,
                      MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime) {
        this.id = id;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Edge schema name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.idSchema = Objects.requireNonNull(idSchema, "idSchema");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.createdTime = Objects.requireNonNull(createdTime, "createdTime");
        this.lastUpdatedTime = Objects.requireNonNull(lastUpdatedTime, "lastUpdatedTime");
        addProperty(idSchema);
        addProperty(source);
        addProperty(target);
        occupiedIds.add(createdTime.id());
        occupiedIds.add(lastUpdatedTime.id());
    }

    /** Convenience constructor creating a default id schema with index {@value #ID_INDEX}. */
    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema source, EdgePropertySchema target) {
        this(id, name, description,
                new EdgePropertySchema(ID_INDEX, "id", "Edge id", PropertyType.ScalarType.LONG),
                source, target,
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime());
    }

    public EdgeSchema(int id, String name, EdgePropertySchema source, EdgePropertySchema target) {
        this(id, name, (String) null, source, target);
    }

    /** Registers an edge property on this schema (fluent). */
    public EdgeSchema property(EdgePropertySchema property) {
        Objects.requireNonNull(property, "property");
        addProperty(property);
        properties.add(property);
        return this;
    }

    private void addProperty(EdgePropertySchema property) {
        if (!occupiedIds.add(property.id())) {
            throw new IllegalArgumentException(
                    "Duplicate property id " + property.id() + " in edge schema '" + name + "'");
        }
        if (propertiesByName.containsKey(property.name())) {
            throw new IllegalArgumentException(
                    "Duplicate property name '" + property.name() + "' in edge schema '" + name + "'");
        }
        if (property.name().equals(createdTime.name()) || property.name().equals(lastUpdatedTime.name())) {
            throw new IllegalArgumentException(
                    "Property name '" + property.name() + "' is reserved for meta-properties in edge schema '" + name + "'");
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

    /** The dedicated edge id property schema. */
    public EdgePropertySchema idSchema() {
        return idSchema;
    }

    /** The source endpoint property schema. */
    public EdgePropertySchema source() {
        return source;
    }

    /** The target endpoint property schema. */
    public EdgePropertySchema target() {
        return target;
    }

    /** The {@code createdTime} meta-property schema. */
    public MetaPropertySchema createdTime() {
        return createdTime;
    }

    /** The {@code lastUpdatedTime} meta-property schema. */
    public MetaPropertySchema lastUpdatedTime() {
        return lastUpdatedTime;
    }

    /** Immutable view of the (remaining) edge properties. */
    public List<EdgePropertySchema> properties() {
        return List.copyOf(properties);
    }

    /** Looks up a property (including id/source/target) by id, or {@code null} if not found. */
    public EdgePropertySchema property(int id) {
        return propertiesById.get(id);
    }

    /** Looks up a property (including id/source/target) by name, or {@code null} if not found. */
    public EdgePropertySchema property(String name) {
        return propertiesByName.get(name);
    }
}
