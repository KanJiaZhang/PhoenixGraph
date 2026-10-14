package com.pengcheng.phoenixgraph.http.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengcheng.phoenixgraph.http.ElementNotFoundException;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgePropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.PropertyUpdateRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.schema.DataQualityValidator;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps HTTP DTOs to the Aerospike storage stores and executes writes.
 */
@Service
public class GraphService {

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final ObjectMapper objectMapper;
    private final VectorIndexManager vectorIndexes;
    private final DataQualityValidator dataQuality = new DataQualityValidator();
    private final WriteValidation validation;

    /** Every schema in this project numbers the id property 0 (see {@code payment-graph.json}, {@code idSchema}). */
    private static final int ID_PROPERTY_INDEX = 0;

    public GraphService(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                        ObjectMapper objectMapper) {
        this(vertexStore, edgeStore, schema, objectMapper, null, WriteValidation.defaults());
    }

    public GraphService(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                        ObjectMapper objectMapper, @Nullable VectorIndexManager vectorIndexes) {
        this(vertexStore, edgeStore, schema, objectMapper, vectorIndexes, WriteValidation.defaults());
    }

    /**
     * The same service with an explicit write policy (see {@link WriteValidation}).
     *
     * <p>The policy is a bean when the application context defines one, so a deployment can allow dangling
     * edges for a bulk import; without it the strict defaults apply, which is what tests and tooling get.
     */
    @Autowired
    public GraphService(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                        ObjectMapper objectMapper, @Nullable VectorIndexManager vectorIndexes,
                        @Nullable WriteValidation validation) {
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.vectorIndexes = vectorIndexes;
        this.validation = validation == null ? WriteValidation.defaults() : validation;
    }

    /**
     * The same service bound to another graph of this instance.
     *
     * <p>The write endpoints are bound to the primary graph at startup; a graph created in
     * the builder needs the same writes, so this resolves the graph's own stores and schema
     * and returns a service over them. Graphs whose storage has no write path (the RocksDB
     * schemes today) are refused with a message that says so.
     */
    public GraphService forGraph(GraphRegistry graphs, String graphName) {
        GraphBinding binding = graphs.require(graphName);
        if (!binding.hasAerospikeStores()) {
            throw new IllegalArgumentException("Graph '" + graphName + "' uses "
                    + binding.storageType().configName() + "; writes go through the primary graph or a loader");
        }
        return new GraphService(binding.vertexStore(), binding.edgeStore(), binding.schema(), objectMapper,
                vectorIndexes, validation);
    }

    // --- vertex ---

    /**
     * Writes a vertex and, when its type declares a vector property, indexes it:
     * the HNSW index of every {@code isVector} property is updated right after the
     * write, so {@code nearest(k)} sees the vertex immediately.
     */
    public WriteOutcome upsertVertex(VertexRequest request) {
        VertexUpsert upsert = toVertexUpsert(request);
        WriteOutcome outcome = vertexStore.upsertVertex(upsert.vertexId(), upsert.properties(),
                upsert.createdTime(), upsert.lastUpdatedTime(), upsert.vertexSchema().name());
        if (outcome == WriteOutcome.SUCCESS && vectorIndexes != null) {
            vectorIndexes.indexVertex(schema.name(), upsert.vertexSchema().name(), upsert.vertexId());
        }
        return outcome;
    }

    public WriteOutcome updateVertexProperty(Object vertexId, int index, PropertyUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        StoredVertex existing = requireVertexExists(vertexId, "update a property of");
        VertexSchema vertexSchema = existing.vertexType() == null ? null : schema.vertexSchema(existing.vertexType());
        // a stub vertex written before this rule existed has no type, so its property indexes cannot be
        // checked against a schema - the value/type check below still applies
        checkPropertyIndex(vertexSchema, vertexId, index, "vertex");
        checkPropertyValue(vertexSchema == null ? null : vertexSchema.property(index), vertexId, request.value());
        long lastModifiedTime = orNow(request.lastModifiedTime());
        Map<Integer, VertexPropertyValue> properties =
                Map.of(index, new VertexPropertyValue(request.value(), lastModifiedTime));
        return vertexStore.upsertVertex(vertexId, properties, lastModifiedTime, lastModifiedTime);
    }

    public WriteOutcome deleteVertexProperty(Object vertexId, int index) {
        requireVertexExists(vertexId, "delete a property of");
        return vertexStore.deleteVertexProperty(vertexId, index);
    }

    /**
     * Deletes a vertex (a soft delete: the record keeps a marker, reads hide the vertex from then on).
     *
     * <p>An id that is not there is a {@link ElementNotFoundException} rather than a silent success: the
     * caller asked to remove something specific, and "SUCCESS" for a vertex that never existed hides the
     * typo that caused it. {@code GET} says the same thing with a 404, so both verbs agree.
     */
    public WriteOutcome deleteVertex(Object vertexId) {
        requireNonNullId(vertexId, "id");
        requireVertexExists(vertexId, "delete");
        return vertexStore.deleteVertex(vertexId);
    }

    public StoredVertex getVertex(Object vertexId) {
        return vertexStore.readVertex(vertexId);
    }

    // --- edge ---

    public WriteOutcome upsertEdge(EdgeRequest request) {
        return edgeStore.upsertEdge(toEdgeUpsert(request, Set.of()));
    }

    public WriteOutcome deleteEdge(Object edgeId, String edgeSchemaName, Object source, Object destination) {
        requireNonNullId(edgeId, "edgeId");
        requireNonNullId(source, "source");
        requireNonNullId(destination, "destination");
        return edgeStore.deleteEdge(edgeId, resolveEdgeSchema(edgeSchemaName), source, destination);
    }

    // --- batch ---

    /**
     * Processes a batch write request. If {@code request.url()} is present, the
     * batch definition is loaded from that location (http(s)://, file:// or a
     * local path) first.
     */
    public List<WriteResult> processBatch(BatchRequest request) {
        BatchRequest resolved = resolve(request);
        List<WriteResult> results = new ArrayList<>();

        if (resolved.vertices() != null && !resolved.vertices().isEmpty()) {
            List<VertexUpsert> upserts = resolved.vertices().stream().map(this::toVertexUpsert).toList();
            List<WriteOutcome> outcomes = vertexStore.upsertVertices(upserts);
            for (int i = 0; i < upserts.size(); i++) {
                results.add(new WriteResult(upserts.get(i).vertexId(), outcomes.get(i).name()));
                if (outcomes.get(i) == WriteOutcome.SUCCESS && vectorIndexes != null) {
                    // batch writes index their vectors too, one vertex at a time
                    vectorIndexes.indexVertex(schema.name(), upserts.get(i).vertexSchema().name(),
                            upserts.get(i).vertexId());
                }
            }
        }
        if (resolved.edges() != null && !resolved.edges().isEmpty()) {
            // an edge may point at a vertex the same batch creates: those ids count as present, so a loader
            // can send one file's vertices and edges together and still be checked against the graph
            Set<Object> inThisBatch = new LinkedHashSet<>();
            if (resolved.vertices() != null) {
                for (VertexRequest vertex : resolved.vertices()) {
                    if (vertex != null && vertex.id() != null) {
                        inThisBatch.add(key(vertex.id()));
                    }
                }
            }
            List<EdgeUpsert> upserts = resolved.edges().stream()
                    .map(edge -> toEdgeUpsert(edge, inThisBatch)).toList();
            List<WriteOutcome> outcomes = edgeStore.upsertEdges(upserts);
            for (int i = 0; i < upserts.size(); i++) {
                results.add(new WriteResult(upserts.get(i).edgeId(), outcomes.get(i).name()));
            }
        }
        return results;
    }

    // --- mapping ---

    VertexUpsert toVertexUpsert(VertexRequest request) {
        Objects.requireNonNull(request, "request");
        requireNonNullId(request.id(), "id");
        VertexSchema vertexSchema = resolveVertexSchema(request.vertexSchemaName());
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        if (request.properties() != null) {
            for (VertexPropertyRequest property : request.properties()) {
                if (property == null) {
                    throw new IllegalArgumentException("properties must not contain null entries");
                }
                checkPropertyIndex(vertexSchema, request.id(), property.index(), "vertex");
                checkPropertyValue(vertexSchema.property(property.index()), request.id(), property.value());
                properties.put(property.index(),
                        new VertexPropertyValue(property.value(), orNow(property.lastModifiedTime())));
            }
        }
        return new VertexUpsert(request.id(), vertexSchema, properties,
                orNow(request.createdTime()), orNow(request.lastUpdatedTime()));
    }

    EdgeUpsert toEdgeUpsert(EdgeRequest request) {
        return toEdgeUpsert(request, Set.of());
    }

    /**
     * Maps an edge request, checking it against the schema and - when
     * {@link WriteValidation#requireExistingEndpoints()} is on - against the vertices that
     * already exist.
     *
     * @param alsoPresent ids written by the same batch, which count as present
     */
    EdgeUpsert toEdgeUpsert(EdgeRequest request, Set<Object> alsoPresent) {
        Objects.requireNonNull(request, "request");
        requireNonNullId(request.edgeId(), "edgeId");
        requireNonNullId(request.source(), "source");
        requireNonNullId(request.destination(), "destination");
        EdgeSchema edgeSchema = resolveEdgeSchema(request.edgeSchemaName());
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        if (request.properties() != null) {
            for (EdgePropertyRequest property : request.properties()) {
                if (property == null) {
                    throw new IllegalArgumentException("properties must not contain null entries");
                }
                checkPropertyIndex(edgeSchema, request.edgeId(), property.index(), "edge");
                checkPropertyValue(edgeSchema.property(property.index()), request.edgeId(), property.value());
                properties.put(property.index(), new EdgePropertyValue(property.value(),
                        orNow(property.createdTime()), orNow(property.lastModifiedTime())));
            }
        }
        if (validation.requireExistingEndpoints()) {
            requireEndpoint(request.edgeId(), "source", request.source(), alsoPresent);
            requireEndpoint(request.edgeId(), "destination", request.destination(), alsoPresent);
        }
        return new EdgeUpsert(request.edgeId(), edgeSchema,
                request.source(), request.destination(), properties,
                orNow(request.createdTime()), orNow(request.lastUpdatedTime()));
    }

    private void requireEndpoint(Object edgeId, String role, Object vertexId, Set<Object> alsoPresent) {
        if (alsoPresent.contains(key(vertexId))) {
            return;
        }
        if (vertexStore.readVertex(vertexId) == null) {
            throw new ElementNotFoundException("Edge '" + edgeId + "' " + role + " vertex '" + vertexId
                    + "' does not exist; write the vertex first, or set "
                    + "phoenixgraph.write.require-existing-endpoints=false for a loader that imports edges first");
        }
    }

    /** Ids are compared as the store keys them: {@code 135} and {@code "135"} are the same vertex. */
    private static Object key(Object id) {
        return String.valueOf(id);
    }

    /** A property index the schema does not declare would be stored as a bin nothing can read back. */
    private void checkPropertyIndex(VertexSchema vertexSchema, Object elementId, int index, String kind) {
        if (vertexSchema == null) {
            return;
        }
        if (vertexSchema.property(index) == null) {
            throw new IllegalArgumentException("Unknown property index " + index + " on " + kind
                    + " schema '" + vertexSchema.name() + "' for element '" + elementId + "'; declared: "
                    + declaredIndexes(vertexSchema));
        }
    }

    private void checkPropertyIndex(EdgeSchema edgeSchema, Object elementId, int index, String kind) {
        if (edgeSchema.property(index) == null) {
            throw new IllegalArgumentException("Unknown property index " + index + " on " + kind
                    + " schema '" + edgeSchema.name() + "' for element '" + elementId + "'; declared: "
                    + declaredIndexes(edgeSchema));
        }
    }

    private static String declaredIndexes(VertexSchema vertexSchema) {
        List<String> declared = new ArrayList<>();
        vertexSchema.properties().forEach(property -> declared.add(property.id() + " (" + property.name() + ")"));
        return declared.toString();
    }

    private static String declaredIndexes(EdgeSchema edgeSchema) {
        List<String> declared = new ArrayList<>();
        edgeSchema.properties().forEach(property -> declared.add(property.id() + " (" + property.name() + ")"));
        return declared.toString();
    }

    private void checkPropertyValue(PropertySchema propertySchema, Object elementId, Object value) {
        if (value instanceof CharSequence text
                && text.toString().getBytes(StandardCharsets.UTF_8).length > validation.maxPropertyValueBytes()) {
            throw new IllegalArgumentException("Element '" + elementId + "' property '"
                    + (propertySchema == null ? "?" : propertySchema.name()) + "' is "
                    + text.toString().getBytes(StandardCharsets.UTF_8).length + " bytes, over the limit of "
                    + validation.maxPropertyValueBytes() + " (phoenixgraph.write.max-property-value-bytes)");
        }
        if (propertySchema == null || propertySchema.id() == ID_PROPERTY_INDEX) {
            // the id property is the record key spelled out, and the API takes an id as either JSON shape (the
            // demo data writes "135" for an account whose id schema is LONG, and the store keys it as a string),
            // so the declared id type describes the data rather than constraining the request
            return;
        }
        List<String> errors = dataQuality.validate(propertySchema, value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Element '" + elementId + "' property '"
                    + propertySchema.name() + "' invalid: " + errors);
        }
    }

    /** An id that is missing (or blank) is a client error, not a NullPointerException. */
    private static void requireNonNullId(Object id, String field) {
        if (id == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (id instanceof CharSequence text && text.toString().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** The vertex, or a 404: an operation that names a vertex it expects to be there. */
    private StoredVertex requireVertexExists(Object vertexId, String operation) {
        requireNonNullId(vertexId, "id");
        StoredVertex vertex = vertexStore.readVertex(vertexId);
        if (vertex == null) {
            throw new ElementNotFoundException("No vertex '" + vertexId + "' to " + operation
                    + "; GET /api/v1/vertices/" + vertexId + " says the same");
        }
        return vertex;
    }

    private BatchRequest resolve(BatchRequest request) {
        String url = request.url();
        if (url == null || url.isBlank()) {
            return request;
        }
        try {
            return objectMapper.readValue(load(url), BatchRequest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load batch definition from " + url, e);
        }
    }

    private String load(String location) throws IOException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            try (InputStream in = URI.create(location).toURL().openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (location.startsWith("file://")) {
            location = location.substring("file://".length());
        }
        return Files.readString(Path.of(location), StandardCharsets.UTF_8);
    }

    private EdgeSchema resolveEdgeSchema(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("edgeSchemaName must not be blank");
        }
        EdgeSchema edgeSchema = schema.edgeSchema(name);
        if (edgeSchema == null) {
            throw new IllegalArgumentException("Unknown edge schema: " + name);
        }
        return edgeSchema;
    }

    private VertexSchema resolveVertexSchema(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vertexSchemaName must not be blank");
        }
        VertexSchema vertexSchema = schema.vertexSchema(name);
        if (vertexSchema == null) {
            throw new IllegalArgumentException("Unknown vertex schema: " + name);
        }
        return vertexSchema;
    }

    private static long orNow(Long timestamp) {
        return timestamp == null ? System.currentTimeMillis() : timestamp;
    }
}
