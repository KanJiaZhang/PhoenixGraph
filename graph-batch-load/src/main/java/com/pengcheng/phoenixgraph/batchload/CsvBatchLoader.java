package com.pengcheng.phoenixgraph.batchload;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Batch-imports vertices and edges from a CSV file into the Aerospike storage.
 *
 * <p>CSV conventions:
 * <ul>
 *   <li>the first column holds the element id in composite form
 *       {@code GraphName::Vertex::VertexName:ID} (vertex import) or
 *       {@code GraphName::Edge::EdgeName:ID} (edge import), which identifies the
 *       graph, element kind, schema name and element id;</li>
 *   <li>the remaining columns (except reserved headers and the last column) are
 *       properties, named by their property index (integer);</li>
 *   <li>for edges the reserved headers {@code source} and {@code destination}
 *       identify the endpoints;</li>
 *   <li>the last column header is {@code lastUpdatedTime} (epoch millis) and is
 *       applied to the element and to each property.</li>
 * </ul>
 *
 * <p>The file is read <b>streaming</b>: rows are accumulated into an in-memory
 * merge window of at most {@code batchSize} distinct ids and flushed to the
 * storage in batches. Duplicate vertex/edge ids within the same window are
 * merged before writing (a property with the same index keeps the newer
 * {@code lastUpdatedTime}; the merged element takes the earliest
 * {@code createdTime} and the latest {@code lastUpdatedTime}).
 */
public class CsvBatchLoader {

    public static final String VERTEX_ID_HEADER = "VertexID";
    public static final String EDGE_ID_HEADER = "EdgeID";
    public static final String SOURCE_HEADER = "source";
    public static final String DESTINATION_HEADER = "destination";
    public static final String LAST_UPDATED_TIME_HEADER = "lastUpdatedTime";

    /** Default number of distinct ids accumulated before flushing a batch. */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final int batchSize;

    public CsvBatchLoader(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        this(vertexStore, edgeStore, schema, DEFAULT_BATCH_SIZE);
    }

    public CsvBatchLoader(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                          GraphSchema schema, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.batchSize = batchSize;
    }

    /**
     * A composite element id parsed from the first CSV column:
     * {@code GraphName::Vertex::VertexName:ID} or
     * {@code GraphName::Edge::EdgeName:ID}.
     *
     * @param graphName the graph name ({@code GraphName})
     * @param kind      {@code Vertex} or {@code Edge}
     * @param name      the vertex/edge schema name
     * @param id        the actual element id
     */
    public record ParsedElementId(String graphName, String kind, String name, String id) {
    }

    /** Parses a composite element id ({@code GraphName::Vertex::Name:ID} / {@code GraphName::Edge::Name:ID}). */
    public static ParsedElementId parseElementId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Element id must not be blank");
        }
        String[] parts = value.trim().split("::", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Element id must be GraphName::Vertex::Name:ID or GraphName::Edge::Name:ID, got: " + value);
        }
        String schemaAndId = parts[2];
        int colon = schemaAndId.indexOf(':');
        if (colon < 0 || colon == schemaAndId.length() - 1) {
            throw new IllegalArgumentException("Element id must end with ':ID', got: " + value);
        }
        return new ParsedElementId(parts[0].trim(), parts[1].trim(),
                schemaAndId.substring(0, colon).trim(), schemaAndId.substring(colon + 1).trim());
    }

    /** Loads a CSV file (UTF-8) in streaming batches. */
    public BatchLoadResult load(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    /** Loads CSV data from a reader in streaming batches. */
    public BatchLoadResult load(Reader reader) throws IOException {
        CsvRowParser parser = new CsvRowParser(new BufferedReader(reader));
        List<String> header = parser.nextRow();
        if (header == null || header.isEmpty()) {
            throw new IllegalArgumentException("Empty CSV input");
        }

        boolean vertex = isHeader(header.get(0), VERTEX_ID_HEADER);
        boolean edge = isHeader(header.get(0), EDGE_ID_HEADER);
        if (!vertex && !edge) {
            throw new IllegalArgumentException("First column must be '" + VERTEX_ID_HEADER
                    + "' or '" + EDGE_ID_HEADER + "', got: " + header.get(0));
        }
        if (!isHeader(header.get(header.size() - 1), LAST_UPDATED_TIME_HEADER)) {
            throw new IllegalArgumentException("Last column must be '" + LAST_UPDATED_TIME_HEADER
                    + "', got: " + header.get(header.size() - 1));
        }

        Integer sourceCol = null;
        Integer destinationCol = null;
        if (edge) {
            sourceCol = indexOf(header, SOURCE_HEADER);
            destinationCol = indexOf(header, DESTINATION_HEADER);
            if (sourceCol == null || destinationCol == null) {
                throw new IllegalArgumentException("Edge CSV must contain '" + SOURCE_HEADER
                        + "' and '" + DESTINATION_HEADER + "' columns");
            }
        }

        Map<Integer, Integer> propertyColumns = propertyColumns(header, edge);
        int lastCol = header.size() - 1;

        List<ItemResult> results = new ArrayList<>();
        Map<Object, VertexUpsert> vertices = new LinkedHashMap<>();
        Map<Object, EdgeUpsert> edges = new LinkedHashMap<>();

        List<String> row;
        while ((row = parser.nextRow()) != null) {
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("Row has " + row.size()
                        + " columns, expected " + header.size() + ": " + row);
            }
            long lastUpdatedTime = parseLong(row.get(lastCol), LAST_UPDATED_TIME_HEADER);
            if (vertex) {
                mergeVertex(vertices, buildVertex(row, propertyColumns, lastUpdatedTime));
                if (vertices.size() >= batchSize) {
                    flushVertices(vertices, results);
                    vertices.clear();
                }
            } else {
                mergeEdge(edges, buildEdge(row, propertyColumns, sourceCol, destinationCol, lastUpdatedTime));
                if (edges.size() >= batchSize) {
                    flushEdges(edges, results);
                    edges.clear();
                }
            }
        }
        flushVertices(vertices, results);
        flushEdges(edges, results);
        return BatchLoadResult.of(vertex ? "VERTEX" : "EDGE", results);
    }

    private VertexUpsert buildVertex(List<String> row, Map<Integer, Integer> columns, long lastUpdatedTime) {
        ParsedElementId elementId = parseElementId(row.get(0));
        if (!"Vertex".equalsIgnoreCase(elementId.kind())) {
            throw new IllegalArgumentException("Vertex CSV row must use GraphName::Vertex::Name:ID, got: " + row.get(0));
        }
        VertexSchema vertexSchema = schema.vertexSchema(elementId.name());
        if (vertexSchema == null) {
            throw new IllegalArgumentException("Unknown vertex schema '" + elementId.name() + "'");
        }
        return new VertexUpsert(elementId.id(), vertexSchema,
                vertexProperties(row, columns, lastUpdatedTime), lastUpdatedTime, lastUpdatedTime);
    }

    private EdgeUpsert buildEdge(List<String> row, Map<Integer, Integer> columns,
                                 Integer sourceCol, Integer destinationCol, long lastUpdatedTime) {
        ParsedElementId elementId = parseElementId(row.get(0));
        if (!"Edge".equalsIgnoreCase(elementId.kind())) {
            throw new IllegalArgumentException("Edge CSV row must use GraphName::Edge::Name:ID, got: " + row.get(0));
        }
        EdgeSchema edgeSchema = schema.edgeSchema(elementId.name());
        if (edgeSchema == null) {
            throw new IllegalArgumentException("Unknown edge schema '" + elementId.name() + "'");
        }
        return new EdgeUpsert(elementId.id(), edgeSchema, row.get(sourceCol), row.get(destinationCol),
                edgeProperties(row, columns, lastUpdatedTime), lastUpdatedTime, lastUpdatedTime);
    }

    private void mergeVertex(Map<Object, VertexUpsert> map, VertexUpsert incoming) {
        VertexUpsert existing = map.get(incoming.vertexId());
        if (existing == null) {
            map.put(incoming.vertexId(), incoming);
            return;
        }
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>(existing.properties());
        for (Map.Entry<Integer, VertexPropertyValue> entry : incoming.properties().entrySet()) {
            VertexPropertyValue old = properties.get(entry.getKey());
            if (old == null || entry.getValue().lastModifiedTime() >= old.lastModifiedTime()) {
                properties.put(entry.getKey(), entry.getValue());
            }
        }
        map.put(incoming.vertexId(), new VertexUpsert(incoming.vertexId(), incoming.vertexSchema(), properties,
                Math.min(existing.createdTime(), incoming.createdTime()),
                Math.max(existing.lastUpdatedTime(), incoming.lastUpdatedTime())));
    }

    private void mergeEdge(Map<Object, EdgeUpsert> map, EdgeUpsert incoming) {
        EdgeUpsert existing = map.get(incoming.edgeId());
        if (existing == null) {
            map.put(incoming.edgeId(), incoming);
            return;
        }
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>(existing.properties());
        for (Map.Entry<Integer, EdgePropertyValue> entry : incoming.properties().entrySet()) {
            EdgePropertyValue old = properties.get(entry.getKey());
            if (old == null || entry.getValue().lastUpdatedTime() >= old.lastUpdatedTime()) {
                properties.put(entry.getKey(), entry.getValue());
            }
        }
        map.put(incoming.edgeId(), new EdgeUpsert(existing.edgeId(), existing.edgeSchema(),
                existing.source(), existing.destination(), properties,
                Math.min(existing.createdTime(), incoming.createdTime()),
                Math.max(existing.lastUpdatedTime(), incoming.lastUpdatedTime())));
    }

    private void flushVertices(Map<Object, VertexUpsert> map, List<ItemResult> results) {
        if (map.isEmpty()) {
            return;
        }
        List<VertexUpsert> batch = new ArrayList<>(map.values());
        List<WriteOutcome> outcomes = vertexStore.upsertVertices(batch);
        for (int i = 0; i < batch.size(); i++) {
            results.add(new ItemResult(batch.get(i).vertexId(), outcomes.get(i).name()));
        }
    }

    private void flushEdges(Map<Object, EdgeUpsert> map, List<ItemResult> results) {
        if (map.isEmpty()) {
            return;
        }
        List<EdgeUpsert> batch = new ArrayList<>(map.values());
        List<WriteOutcome> outcomes = edgeStore.upsertEdges(batch);
        for (int i = 0; i < batch.size(); i++) {
            results.add(new ItemResult(batch.get(i).edgeId(), outcomes.get(i).name()));
        }
    }

    private Map<Integer, Integer> propertyColumns(List<String> header, boolean edge) {
        Set<String> reserved = new HashSet<>();
        reserved.add(LAST_UPDATED_TIME_HEADER.toLowerCase());
        if (edge) {
            reserved.add(SOURCE_HEADER.toLowerCase());
            reserved.add(DESTINATION_HEADER.toLowerCase());
        }
        Map<Integer, Integer> columns = new LinkedHashMap<>();
        for (int i = 1; i < header.size() - 1; i++) {
            String name = header.get(i).trim();
            if (reserved.contains(name.toLowerCase())) {
                continue;
            }
            int propertyIndex;
            try {
                propertyIndex = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Property column header must be a property index (integer), got: " + name);
            }
            columns.put(i, propertyIndex);
        }
        return columns;
    }

    private Map<Integer, VertexPropertyValue> vertexProperties(List<String> row,
                                                               Map<Integer, Integer> columns, long lastUpdatedTime) {
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : columns.entrySet()) {
            String value = row.get(entry.getKey());
            if (value == null || value.isEmpty()) {
                continue;
            }
            properties.put(entry.getValue(), new VertexPropertyValue(value, lastUpdatedTime));
        }
        return properties;
    }

    private Map<Integer, EdgePropertyValue> edgeProperties(List<String> row,
                                                           Map<Integer, Integer> columns, long lastUpdatedTime) {
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : columns.entrySet()) {
            String value = row.get(entry.getKey());
            if (value == null || value.isEmpty()) {
                continue;
            }
            properties.put(entry.getValue(), new EdgePropertyValue(value, lastUpdatedTime, lastUpdatedTime));
        }
        return properties;
    }

    private static boolean isHeader(String actual, String expected) {
        return actual != null && expected.equalsIgnoreCase(actual.trim());
    }

    private static Integer indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (isHeader(header.get(i), name)) {
                return i;
            }
        }
        return null;
    }

    private static long parseLong(String value, String column) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Column '" + column + "' must be a long, got: " + value, e);
        }
    }
}
