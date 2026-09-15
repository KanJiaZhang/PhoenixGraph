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
 * {@value #ID_INDEX}), the {@link EdgeEndpointSchema source} and
 * {@link EdgeEndpointSchema target} endpoints, and the
 * {@code createdTime}/{@code lastUpdatedTime} meta-properties. All remaining edge
 * properties are aggregated in the property list. Properties (including
 * id/source/target) can be looked up by id or name, and both ids and names are
 * unique within an edge schema.
 *
 * <p>Each endpoint declares both its id property and the vertex schema name it
 * connects to ({@link EdgeEndpointSchema#vertexName()}). An edge schema also
 * declares its {@link EdgeDirection} ({@code DIRECTED}, {@code UNDIRECTED} or
 * {@code BIDIRECTIONAL}), which defaults to {@link EdgeDirection#DIRECTED}.
 */
public class EdgeSchema {

    /** Default index of the edge id property schema. */
    public static final int ID_INDEX = 0;

    private final int id;
    private final String name;
    private final String description;
    private final EdgePropertySchema idSchema;
    private final EdgeEndpointSchema source;
    private final EdgeEndpointSchema target;
    private final MetaPropertySchema createdTime;
    private final MetaPropertySchema lastUpdatedTime;
    private final EdgeDirection direction;
    private int maxOutgoingEdges;
    private int maxIncomingEdges;
    private int maxDegree;
    private boolean maxOutgoingEdgesSet;
    private boolean maxIncomingEdgesSet;
    private boolean maxDegreeSet;
    private final List<EdgePropertySchema> properties = new ArrayList<>();
    private final Map<Integer, EdgePropertySchema> propertiesById = new HashMap<>();
    private final Map<String, EdgePropertySchema> propertiesByName = new HashMap<>();
    private final Set<Integer> occupiedIds = new HashSet<>();

    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema idSchema, EdgePropertySchema source, EdgePropertySchema target,
                      MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime) {
        this(id, name, description, idSchema, endpoint(source), endpoint(target),
                createdTime, lastUpdatedTime, EdgeDirection.DIRECTED);
    }

    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema idSchema, EdgePropertySchema source, EdgePropertySchema target,
                      MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime,
                      EdgeDirection direction) {
        this(id, name, description, idSchema, endpoint(source), endpoint(target),
                createdTime, lastUpdatedTime, direction, 0, 0, 0);
    }

    /**
     * Full constructor including per-direction degree limits. Limits
     * {@code <= 0} mean unlimited.
     */
    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema idSchema, EdgeEndpointSchema source, EdgeEndpointSchema target,
                      MetaPropertySchema createdTime, MetaPropertySchema lastUpdatedTime,
                      EdgeDirection direction,
                      int maxOutgoingEdges, int maxIncomingEdges, int maxDegree) {
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
        this.direction = Objects.requireNonNull(direction, "direction");
        this.maxOutgoingEdges = maxOutgoingEdges;
        this.maxIncomingEdges = maxIncomingEdges;
        this.maxDegree = maxDegree;
        addProperty(idSchema);
        addProperty(source);
        addProperty(target);
        occupiedIds.add(createdTime.id());
        occupiedIds.add(lastUpdatedTime.id());
    }

    /** Convenience constructor creating a default id schema with index {@value #ID_INDEX}. */
    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema source, EdgePropertySchema target) {
        this(id, name, description, source, target, EdgeDirection.DIRECTED);
    }

    /** Convenience constructor with an explicit {@link EdgeDirection}. */
    public EdgeSchema(int id, String name, String description,
                      EdgePropertySchema source, EdgePropertySchema target, EdgeDirection direction) {
        this(id, name, description,
                new EdgePropertySchema(ID_INDEX, "id", "Edge id", PropertyType.ScalarType.LONG),
                endpoint(source), endpoint(target),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                direction);
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

    /**
     * Sets the maximum number of outgoing edges retained per vertex for this
     * edge class (DIRECTED edges only); {@code <= 0} means unlimited (fluent).
     */
    public EdgeSchema maxOutgoingEdges(int maxOutgoingEdges) {
        this.maxOutgoingEdges = maxOutgoingEdges;
        this.maxOutgoingEdgesSet = true;
        return this;
    }

    /**
     * Sets the maximum number of incoming edges retained per vertex for this
     * edge class (DIRECTED edges only); {@code <= 0} means unlimited (fluent).
     */
    public EdgeSchema maxIncomingEdges(int maxIncomingEdges) {
        this.maxIncomingEdges = maxIncomingEdges;
        this.maxIncomingEdgesSet = true;
        return this;
    }

    /**
     * Sets the single maximum degree retained per vertex for undirected or
     * bidirectional edge classes; {@code <= 0} means unlimited (fluent).
     */
    public EdgeSchema maxDegree(int maxDegree) {
        this.maxDegree = maxDegree;
        this.maxDegreeSet = true;
        return this;
    }

    /** Wraps a plain endpoint property into an {@link EdgeEndpointSchema} (vertex name unknown). */
    private static EdgeEndpointSchema endpoint(EdgePropertySchema property) {
        Objects.requireNonNull(property, "endpoint");
        if (property instanceof EdgeEndpointSchema endpoint) {
            return endpoint;
        }
        return new EdgeEndpointSchema(property.id(), property.name(), property.description(),
                property.type(), null, property.constraints());
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

    /** The source endpoint (id property + source vertex schema name). */
    public EdgeEndpointSchema source() {
        return source;
    }

    /** The target endpoint (id property + target vertex schema name). */
    public EdgeEndpointSchema target() {
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

    /** The direction semantics of this edge class. */
    public EdgeDirection direction() {
        return direction;
    }

    /** The vertex schema name of the source endpoint, or {@code null} if not declared. */
    public String sourceVertexName() {
        return source.vertexName();
    }

    /** The vertex schema name of the target endpoint, or {@code null} if not declared. */
    public String targetVertexName() {
        return target.vertexName();
    }

    /** Maximum outgoing edges per vertex ({@code <= 0} means unlimited). */
    public int maxOutgoingEdges() {
        return maxOutgoingEdges;
    }

    /** Whether {@code maxOutgoingEdges} was explicitly declared. */
    public boolean maxOutgoingEdgesSet() {
        return maxOutgoingEdgesSet;
    }

    /** Maximum incoming edges per vertex ({@code <= 0} means unlimited). */
    public int maxIncomingEdges() {
        return maxIncomingEdges;
    }

    /** Whether {@code maxIncomingEdges} was explicitly declared. */
    public boolean maxIncomingEdgesSet() {
        return maxIncomingEdgesSet;
    }

    /** Single maximum degree for undirected/bidirectional edges ({@code <= 0} means unlimited). */
    public int maxDegree() {
        return maxDegree;
    }

    /** Whether {@code maxDegree} was explicitly declared. */
    public boolean maxDegreeSet() {
        return maxDegreeSet;
    }

    /**
     * Effective maximum outgoing degree: {@code maxOutgoingEdges} for DIRECTED
     * edges, otherwise the single {@code maxDegree}.
     */
    public int maxOutDegree() {
        return direction == EdgeDirection.DIRECTED ? maxOutgoingEdges : maxDegree;
    }

    /**
     * Effective maximum incoming degree: {@code maxIncomingEdges} for DIRECTED
     * edges, otherwise the single {@code maxDegree}.
     */
    public int maxInDegree() {
        return direction == EdgeDirection.DIRECTED ? maxIncomingEdges : maxDegree;
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
