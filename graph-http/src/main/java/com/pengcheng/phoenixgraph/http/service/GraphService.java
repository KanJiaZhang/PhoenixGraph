package com.pengcheng.phoenixgraph.http.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public GraphService(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                        ObjectMapper objectMapper) {
        this(vertexStore, edgeStore, schema, objectMapper, null);
    }

    @Autowired
    public GraphService(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                        ObjectMapper objectMapper, @Nullable VectorIndexManager vectorIndexes) {
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.vectorIndexes = vectorIndexes;
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
                vectorIndexes);
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
        long lastModifiedTime = orNow(request.lastModifiedTime());
        Map<Integer, VertexPropertyValue> properties =
                Map.of(index, new VertexPropertyValue(request.value(), lastModifiedTime));
        return vertexStore.upsertVertex(vertexId, properties, lastModifiedTime, lastModifiedTime);
    }

    public WriteOutcome deleteVertexProperty(Object vertexId, int index) {
        return vertexStore.deleteVertexProperty(vertexId, index);
    }

    public WriteOutcome deleteVertex(Object vertexId) {
        return vertexStore.deleteVertex(vertexId);
    }

    public StoredVertex getVertex(Object vertexId) {
        return vertexStore.readVertex(vertexId);
    }

    // --- edge ---

    public WriteOutcome upsertEdge(EdgeRequest request) {
        return edgeStore.upsertEdge(toEdgeUpsert(request));
    }

    public WriteOutcome deleteEdge(Object edgeId, String edgeSchemaName, Object source, Object destination) {
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
            List<EdgeUpsert> upserts = resolved.edges().stream().map(this::toEdgeUpsert).toList();
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
        Objects.requireNonNull(request.id(), "id");
        VertexSchema vertexSchema = resolveVertexSchema(request.vertexSchemaName());
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        if (request.properties() != null) {
            for (VertexPropertyRequest property : request.properties()) {
                checkPropertyValue(vertexSchema.property(property.index()), request.id(), property.value());
                properties.put(property.index(),
                        new VertexPropertyValue(property.value(), orNow(property.lastModifiedTime())));
            }
        }
        return new VertexUpsert(request.id(), vertexSchema, properties,
                orNow(request.createdTime()), orNow(request.lastUpdatedTime()));
    }

    EdgeUpsert toEdgeUpsert(EdgeRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.edgeId(), "edgeId");
        Objects.requireNonNull(request.source(), "source");
        Objects.requireNonNull(request.destination(), "destination");
        EdgeSchema edgeSchema = resolveEdgeSchema(request.edgeSchemaName());
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        if (request.properties() != null) {
            for (EdgePropertyRequest property : request.properties()) {
                checkPropertyValue(edgeSchema.property(property.index()), request.edgeId(), property.value());
                properties.put(property.index(), new EdgePropertyValue(property.value(),
                        orNow(property.createdTime()), orNow(property.lastModifiedTime())));
            }
        }
        return new EdgeUpsert(request.edgeId(), edgeSchema,
                request.source(), request.destination(), properties,
                orNow(request.createdTime()), orNow(request.lastUpdatedTime()));
    }

    private void checkPropertyValue(PropertySchema propertySchema, Object elementId, Object value) {
        if (propertySchema == null) {
            return;
        }
        List<String> errors = dataQuality.validate(propertySchema, value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Element '" + elementId + "' property '"
                    + propertySchema.name() + "' invalid: " + errors);
        }
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
