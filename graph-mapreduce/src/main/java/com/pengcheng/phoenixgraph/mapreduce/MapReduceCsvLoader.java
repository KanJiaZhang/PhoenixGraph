package com.pengcheng.phoenixgraph.mapreduce;

import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.batchload.CsvBatchLoader;
import com.pengcheng.phoenixgraph.batchload.CsvRowParser;
import com.pengcheng.phoenixgraph.batchload.ItemResult;
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
 * A MapReduce program that batch-merges and batch-writes vertices/edges from CSV
 * files.
 *
 * <p>Map phase parses each CSV line into {@code (id, row)}; the in-memory
 * shuffle groups rows by id; the reduce phase merges duplicate ids (a property
 * with the same index keeps the newer {@code lastUpdatedTime}); the output phase
 * batches merged records and writes them through the Aerospike stores.
 *
 * <p>The CSV header conventions are the same as {@link CsvBatchLoader}: the
 * first column holds {@code GraphName::Vertex::VertexName:ID} /
 * {@code GraphName::Edge::EdgeName:ID}, property columns are named by their
 * index, and the last column is {@code lastUpdatedTime}. Edge files also carry
 * {@code source} and {@code destination}.
 */
public class MapReduceCsvLoader {

    /** Default number of distinct ids held in the shuffle before reducing. */
    public static final int DEFAULT_SPILL_SIZE = 100_000;
    /** Default number of merged records buffered before a storage batch write. */
    public static final int DEFAULT_BATCH_SIZE = 1_000;

    private final AerospikeVertexStore vertexStore;
    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final int spillSize;
    private final int batchSize;

    public MapReduceCsvLoader(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        this(vertexStore, edgeStore, schema, DEFAULT_SPILL_SIZE, DEFAULT_BATCH_SIZE);
    }

    public MapReduceCsvLoader(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                              int spillSize, int batchSize) {
        if (spillSize < 1) {
            throw new IllegalArgumentException("spillSize must be >= 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.vertexStore = Objects.requireNonNull(vertexStore, "vertexStore");
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.spillSize = spillSize;
        this.batchSize = batchSize;
    }

    /** Loads and merges multiple CSV files (all sharing the same header). */
    public BatchLoadResult load(List<Path> files) throws IOException {
        Objects.requireNonNull(files, "files");
        if (files.isEmpty()) {
            return BatchLoadResult.of("NONE", List.of());
        }

        List<String> header = readHeader(files.get(0));
        boolean vertex = isHeader(header.get(0), CsvBatchLoader.VERTEX_ID_HEADER);
        boolean edge = isHeader(header.get(0), CsvBatchLoader.EDGE_ID_HEADER);
        if (!vertex && !edge) {
            throw new IllegalArgumentException("First column must be '" + CsvBatchLoader.VERTEX_ID_HEADER
                    + "' or '" + CsvBatchLoader.EDGE_ID_HEADER + "', got: " + header.get(0));
        }
        if (!isHeader(header.get(header.size() - 1), CsvBatchLoader.LAST_UPDATED_TIME_HEADER)) {
            throw new IllegalArgumentException("Last column must be '" + CsvBatchLoader.LAST_UPDATED_TIME_HEADER
                    + "', got: " + header.get(header.size() - 1));
        }

        Integer sourceCol = null;
        Integer destinationCol = null;
        if (edge) {
            sourceCol = indexOf(header, CsvBatchLoader.SOURCE_HEADER);
            destinationCol = indexOf(header, CsvBatchLoader.DESTINATION_HEADER);
            if (sourceCol == null || destinationCol == null) {
                throw new IllegalArgumentException("Edge CSV must contain '" + CsvBatchLoader.SOURCE_HEADER
                        + "' and '" + CsvBatchLoader.DESTINATION_HEADER + "' columns");
            }
        }

        Map<Integer, Integer> propertyColumns = propertyColumns(header, edge);
        int lastCol = header.size() - 1;
        List<MapReduceJob.LineSource> sources = files.stream()
                .map(f -> (MapReduceJob.LineSource) new CsvFileLineSource(f))
                .toList();

        return vertex
                ? loadVertices(sources, header, propertyColumns, lastCol)
                : loadEdges(sources, header, propertyColumns, sourceCol, destinationCol, lastCol);
    }

    private BatchLoadResult loadVertices(List<MapReduceJob.LineSource> sources, List<String> header,
                                         Map<Integer, Integer> propertyColumns, int lastCol) throws IOException {
        List<ItemResult> results = new ArrayList<>();
        List<VertexUpsert> buffer = new ArrayList<>();

        MapReduceJob<Object, VertexRow, VertexUpsert> job = new MapReduceJob<>(
                (line, emit) -> {
                    List<String> row = CsvRowParser.parseLine(line);
                    checkRow(row, header);
                    long lastUpdatedTime = parseLong(row.get(lastCol), CsvBatchLoader.LAST_UPDATED_TIME_HEADER);
                    CsvBatchLoader.ParsedElementId elementId = CsvBatchLoader.parseElementId(row.get(0));
                    if (!"Vertex".equalsIgnoreCase(elementId.kind())) {
                        throw new IllegalArgumentException(
                                "Vertex CSV row must use GraphName::Vertex::Name:ID, got: " + row.get(0));
                    }
                    VertexSchema vertexSchema = schema.vertexSchema(elementId.name());
                    if (vertexSchema == null) {
                        throw new IllegalArgumentException("Unknown vertex schema '" + elementId.name() + "'");
                    }
                    emit.emit(elementId.id(), new VertexRow(
                            vertexSchema,
                            vertexProperties(row, propertyColumns, lastUpdatedTime),
                            lastUpdatedTime, lastUpdatedTime));
                },
                MapReduceCsvLoader::mergeVertexRows,
                upsert -> {
                    buffer.add(upsert);
                    if (buffer.size() >= batchSize) {
                        flushVertexBuffer(buffer, results);
                    }
                },
                spillSize);

        job.run(sources);
        flushVertexBuffer(buffer, results);
        return BatchLoadResult.of("VERTEX", results);
    }

    private BatchLoadResult loadEdges(List<MapReduceJob.LineSource> sources, List<String> header,
                                      Map<Integer, Integer> propertyColumns, Integer sourceCol,
                                      Integer destinationCol, int lastCol) throws IOException {
        List<ItemResult> results = new ArrayList<>();
        List<EdgeUpsert> buffer = new ArrayList<>();

        MapReduceJob<Object, EdgeRow, EdgeUpsert> job = new MapReduceJob<>(
                (line, emit) -> {
                    List<String> row = CsvRowParser.parseLine(line);
                    checkRow(row, header);
                    long lastUpdatedTime = parseLong(row.get(lastCol), CsvBatchLoader.LAST_UPDATED_TIME_HEADER);
                    CsvBatchLoader.ParsedElementId elementId = CsvBatchLoader.parseElementId(row.get(0));
                    if (!"Edge".equalsIgnoreCase(elementId.kind())) {
                        throw new IllegalArgumentException(
                                "Edge CSV row must use GraphName::Edge::Name:ID, got: " + row.get(0));
                    }
                    EdgeSchema edgeSchema = schema.edgeSchema(elementId.name());
                    if (edgeSchema == null) {
                        throw new IllegalArgumentException("Unknown edge schema '" + elementId.name() + "'");
                    }
                    emit.emit(elementId.id(), new EdgeRow(edgeSchema, row.get(sourceCol), row.get(destinationCol),
                            edgeProperties(row, propertyColumns, lastUpdatedTime),
                            lastUpdatedTime, lastUpdatedTime));
                },
                MapReduceCsvLoader::mergeEdgeRows,
                upsert -> {
                    buffer.add(upsert);
                    if (buffer.size() >= batchSize) {
                        flushEdgeBuffer(buffer, results);
                    }
                },
                spillSize);

        job.run(sources);
        flushEdgeBuffer(buffer, results);
        return BatchLoadResult.of("EDGE", results);
    }

    // --- reducers ---

    private static VertexUpsert mergeVertexRows(Object id, List<VertexRow> rows) {
        VertexRow first = rows.get(0);
        Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
        long createdTime = Long.MAX_VALUE;
        long lastUpdatedTime = Long.MIN_VALUE;
        for (VertexRow row : rows) {
            for (Map.Entry<Integer, VertexPropertyValue> entry : row.properties().entrySet()) {
                VertexPropertyValue old = properties.get(entry.getKey());
                if (old == null || entry.getValue().lastModifiedTime() >= old.lastModifiedTime()) {
                    properties.put(entry.getKey(), entry.getValue());
                }
            }
            createdTime = Math.min(createdTime, row.createdTime());
            lastUpdatedTime = Math.max(lastUpdatedTime, row.lastUpdatedTime());
        }
        return new VertexUpsert(id, first.vertexSchema(), properties, createdTime, lastUpdatedTime);
    }

    private static EdgeUpsert mergeEdgeRows(Object id, List<EdgeRow> rows) {
        EdgeRow first = rows.get(0);
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        long createdTime = Long.MAX_VALUE;
        long lastUpdatedTime = Long.MIN_VALUE;
        for (EdgeRow row : rows) {
            for (Map.Entry<Integer, EdgePropertyValue> entry : row.properties().entrySet()) {
                EdgePropertyValue old = properties.get(entry.getKey());
                if (old == null || entry.getValue().lastUpdatedTime() >= old.lastUpdatedTime()) {
                    properties.put(entry.getKey(), entry.getValue());
                }
            }
            createdTime = Math.min(createdTime, row.createdTime());
            lastUpdatedTime = Math.max(lastUpdatedTime, row.lastUpdatedTime());
        }
        return new EdgeUpsert(id, first.edgeSchema(), first.source(), first.destination(),
                properties, createdTime, lastUpdatedTime);
    }

    // --- output writers ---

    private void flushVertexBuffer(List<VertexUpsert> buffer, List<ItemResult> results) {
        if (buffer.isEmpty()) {
            return;
        }
        List<WriteOutcome> outcomes = vertexStore.upsertVertices(buffer);
        for (int i = 0; i < buffer.size(); i++) {
            results.add(new ItemResult(buffer.get(i).vertexId(), outcomes.get(i).name()));
        }
        buffer.clear();
    }

    private void flushEdgeBuffer(List<EdgeUpsert> buffer, List<ItemResult> results) {
        if (buffer.isEmpty()) {
            return;
        }
        List<WriteOutcome> outcomes = edgeStore.upsertEdges(buffer);
        for (int i = 0; i < buffer.size(); i++) {
            results.add(new ItemResult(buffer.get(i).edgeId(), outcomes.get(i).name()));
        }
        buffer.clear();
    }

    // --- CSV helpers ---

    private static List<String> readHeader(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException("Empty CSV file: " + file);
            }
            return CsvRowParser.parseLine(line);
        }
    }

    private static void checkRow(List<String> row, List<String> header) {
        if (row.size() != header.size()) {
            throw new IllegalArgumentException("Row has " + row.size()
                    + " columns, expected " + header.size() + ": " + row);
        }
    }

    private Map<Integer, Integer> propertyColumns(List<String> header, boolean edge) {
        Set<String> reserved = new HashSet<>();
        reserved.add(CsvBatchLoader.LAST_UPDATED_TIME_HEADER.toLowerCase());
        if (edge) {
            reserved.add(CsvBatchLoader.SOURCE_HEADER.toLowerCase());
            reserved.add(CsvBatchLoader.DESTINATION_HEADER.toLowerCase());
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
