package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A unified schema definition for a whole graph.
 *
 * <p>Aggregates multiple {@link VertexSchema}s and {@link EdgeSchema}s into a
 * single, consistent graph schema definition, plus the {@link SchemaConstraints}
 * used to validate it. Vertex schemas and edge schemas can be looked up by id or
 * name, and both ids and names are unique within a graph schema.
 */
public class GraphSchema {

    private final int id;
    private final String name;
    private final String description;
    private final SchemaConstraints constraints;
    private GraphStorageSchema storage = GraphStorageSchema.AEROSPIKE;
    private final List<VertexSchema> vertexSchemas = new ArrayList<>();
    private final List<EdgeSchema> edgeSchemas = new ArrayList<>();
    private final Map<Integer, VertexSchema> vertexById = new HashMap<>();
    private final Map<String, VertexSchema> vertexByName = new HashMap<>();
    private final Map<Integer, EdgeSchema> edgeById = new HashMap<>();
    private final Map<String, EdgeSchema> edgeByName = new HashMap<>();

    public GraphSchema(int id, String name, String description, SchemaConstraints constraints) {
        this.id = id;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Graph schema name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.constraints = constraints == null ? SchemaConstraints.DEFAULTS : constraints;
    }

    public GraphSchema(int id, String name, String description) {
        this(id, name, description, SchemaConstraints.DEFAULTS);
    }

    public GraphSchema(int id, String name) {
        this(id, name, null);
    }

    /** Registers a vertex schema (fluent). */
    public GraphSchema vertex(VertexSchema vertexSchema) {
        Objects.requireNonNull(vertexSchema, "vertexSchema");
        if (vertexById.containsKey(vertexSchema.id())) {
            throw new IllegalArgumentException(
                    "Duplicate vertex schema id " + vertexSchema.id() + " in graph schema '" + name + "'");
        }
        if (vertexByName.containsKey(vertexSchema.name())) {
            throw new IllegalArgumentException(
                    "Duplicate vertex schema name '" + vertexSchema.name() + "' in graph schema '" + name + "'");
        }
        vertexById.put(vertexSchema.id(), vertexSchema);
        vertexByName.put(vertexSchema.name(), vertexSchema);
        vertexSchemas.add(vertexSchema);
        return this;
    }

    /** Registers an edge schema (fluent). */
    public GraphSchema edge(EdgeSchema edgeSchema) {
        Objects.requireNonNull(edgeSchema, "edgeSchema");
        if (edgeById.containsKey(edgeSchema.id())) {
            throw new IllegalArgumentException(
                    "Duplicate edge schema id " + edgeSchema.id() + " in graph schema '" + name + "'");
        }
        if (edgeByName.containsKey(edgeSchema.name())) {
            throw new IllegalArgumentException(
                    "Duplicate edge schema name '" + edgeSchema.name() + "' in graph schema '" + name + "'");
        }
        edgeById.put(edgeSchema.id(), edgeSchema);
        edgeByName.put(edgeSchema.name(), edgeSchema);
        edgeSchemas.add(edgeSchema);
        return this;
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

    /** The validation constraints declared by this graph schema. */
    public SchemaConstraints constraints() {
        return constraints;
    }

    /** The storage implementation serving this graph (Aerospike unless declared otherwise). */
    public GraphStorageSchema storage() {
        return storage;
    }

    /** Declares the storage implementation serving this graph (fluent). */
    public GraphSchema storage(GraphStorageSchema storageSchema) {
        this.storage = Objects.requireNonNull(storageSchema, "storageSchema");
        return this;
    }

    /** Immutable view of the vertex schemas in this graph. */
    public List<VertexSchema> vertexSchemas() {
        return List.copyOf(vertexSchemas);
    }

    /** Immutable view of the edge schemas in this graph. */
    public List<EdgeSchema> edgeSchemas() {
        return List.copyOf(edgeSchemas);
    }

    /** Looks up a vertex schema by id, or {@code null} if not found. */
    public VertexSchema vertexSchema(int id) {
        return vertexById.get(id);
    }

    /** Looks up a vertex schema by name, or {@code null} if not found. */
    public VertexSchema vertexSchema(String name) {
        return vertexByName.get(name);
    }

    /** Looks up an edge schema by id, or {@code null} if not found. */
    public EdgeSchema edgeSchema(int id) {
        return edgeById.get(id);
    }

    /** Looks up an edge schema by name, or {@code null} if not found. */
    public EdgeSchema edgeSchema(String name) {
        return edgeByName.get(name);
    }
}
