package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import com.pengcheng.phoenixgraph.metrics.MutableMetricsSink;
import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.log.JulBusinessLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The offline bulk import into the aggregated edge tables.
 *
 * <p>This is the write path the {@code Aerospike-Edge} scheme is built for: instead of updating
 * one edge at a time (a read-modify-write of the vertex record, which cannot hold a hub anyway),
 * a batch job hands over the edges of a vertex and this class generates the records of every
 * level and writes them in bulk:
 *
 * <ol>
 *   <li>the edges of one vertex are sorted by creation time and cut into chunks of
 *       {@link AerospikeEdgeChunks#DEFAULT_CHUNK_SIZE} (level 0, a CDT map of edges),</li>
 *   <li>each level's chunks are grouped again, and a parent chunk keeps the {@code (min, max)}
 *       span of its children (level 1, level 2, ... until one root remains),</li>
 *   <li>a directed edge goes into the {@code Edge_Out} table of its source and the
 *       {@code Edge_In} table of its destination; an undirected edge into the single table of
 *       both,</li>
 *   <li>every generated record is written with one batch call per group, and a hub marker per
 *       (edge class, direction, vertex) says how deep the tables are.</li>
 * </ol>
 *
 * <p>Nothing is written to the vertex record: the aggregated tables are the only home of these
 * edges. Edges that are updated later stay inline (the classic write path), and a query merges
 * the two - see {@code AerospikeEdgeGraphQuery}.
 */
public class AerospikeEdgeBulkImporter {

    private final AerospikeClient client;
    /** The same client, with every round trip counted into {@link #metrics}. */
    private final AerospikeOperations db;
    /** Where this store records its traffic; the graph's metrics are wired in later. */
    private final MutableMetricsSink metrics = new MutableMetricsSink();
    private final String namespace;
    private final String baseSet;
    private final GraphSchema schema;
    private final int chunkSize;
    private final BusinessLog businessLog;
    private final AerospikeEdgeChunkStore store;

    public AerospikeEdgeBulkImporter(AerospikeClient client, String namespace, String baseSet,
                                     GraphSchema schema) {
        this(client, namespace, baseSet, schema, AerospikeEdgeChunks.DEFAULT_CHUNK_SIZE,
                new JulBusinessLog());
    }

    public AerospikeEdgeBulkImporter(AerospikeClient client, String namespace, String baseSet,
                                     GraphSchema schema, int chunkSize, BusinessLog businessLog) {
        this.client = Objects.requireNonNull(client, "client");
        this.db = AerospikeOperations.metered(this.client, metrics);
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.baseSet = Objects.requireNonNull(baseSet, "baseSet");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.chunkSize = chunkSize <= 0 ? AerospikeEdgeChunks.DEFAULT_CHUNK_SIZE : chunkSize;
        this.businessLog = businessLog == null ? entry -> { } : businessLog;
        this.store = new AerospikeEdgeChunkStore(client, namespace, baseSet, this.businessLog);
    }

    /** What one import wrote. */
    public record ImportResult(String edgeClass, int edges, int vertices, int chunks,
                               Map<String, Integer> levels) {
    }

    /**
     * Imports the given edges, one edge class at a time.
     *
     * @param edgeName the edge class the edges belong to
     * @param edges    the edges to import (any order)
     */
    public ImportResult importEdges(String edgeName, List<AerospikeEdgeChunks.EdgeEntry> edges,
                                    Map<String, Object> sourceByEdgeId,
                                    Map<String, Object> destinationByEdgeId) {
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new IllegalArgumentException("Unknown edge class: " + edgeName);
        }
        return importEdges(edgeSchema, edges, sourceByEdgeId, destinationByEdgeId);
    }

    /**
     * Imports the given edges of one edge class into the aggregated tables.
     *
     * @param edgeSchema          the edge class
     * @param edges               the edges to import
     * @param sourceByEdgeId      the source vertex of every edge (by edge id)
     * @param destinationByEdgeId the destination vertex of every edge, or {@code null} for an
     *                            edge whose target is declared {@code isNull}
     */
    public ImportResult importEdges(EdgeSchema edgeSchema, List<AerospikeEdgeChunks.EdgeEntry> edges,
                                    Map<String, Object> sourceByEdgeId,
                                    Map<String, Object> destinationByEdgeId) {
        Objects.requireNonNull(edges, "edges");
        boolean directed = edgeSchema.direction() == EdgeDirection.DIRECTED;
        // group the edges by the vertex that will hold them, per side
        Map<String, List<AerospikeEdgeChunks.EdgeEntry>> bySide = new LinkedHashMap<>();
        Map<String, Integer> edgeCountBySide = new LinkedHashMap<>();
        for (AerospikeEdgeChunks.EdgeEntry edge : edges) {
            Object source = sourceByEdgeId.get(edge.edgeId());
            Object destination = destinationByEdgeId == null ? null : destinationByEdgeId.get(edge.edgeId());
            if (source == null) {
                throw new IllegalArgumentException("Edge '" + edge.edgeId() + "' has no source");
            }
            if (directed) {
                side(bySide, edge, AerospikeEdgeStore.EDGE_DIR_OUT, source, edge.otherVertexId());
                edgeCountBySide.merge(sideKey(AerospikeEdgeStore.EDGE_DIR_OUT, source), 1, Integer::sum);
                if (destination != null) {
                    side(bySide, edge, AerospikeEdgeStore.EDGE_DIR_IN, destination, source);
                    edgeCountBySide.merge(sideKey(AerospikeEdgeStore.EDGE_DIR_IN, destination), 1, Integer::sum);
                }
            } else {
                side(bySide, edge, AerospikeEdgeStore.EDGE_DIR_BOTH, source, edge.otherVertexId());
                edgeCountBySide.merge(sideKey(AerospikeEdgeStore.EDGE_DIR_BOTH, source), 1, Integer::sum);
                if (destination != null && !destination.equals(source)) {
                    side(bySide, edge, AerospikeEdgeStore.EDGE_DIR_BOTH, destination, source);
                    edgeCountBySide.merge(sideKey(AerospikeEdgeStore.EDGE_DIR_BOTH, destination), 1, Integer::sum);
                }
            }
        }

        int chunks = 0;
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (Map.Entry<String, List<AerospikeEdgeChunks.EdgeEntry>> entry : bySide.entrySet()) {
            int direction = directionOf(entry.getKey());
            Object vertexId = vertexOf(entry.getKey());
            List<AerospikeEdgeChunks.ChunkRecord> records =
                    AerospikeEdgeChunks.build(entry.getValue(), chunkSize);
            chunks += store.writeChunks(edgeSchema, direction, vertexId,
                    edgeCountBySide.getOrDefault(entry.getKey(), entry.getValue().size()), records);
            int top = records.isEmpty() ? 0 : records.get(records.size() - 1).level() + 1;
            levels.merge(vertexId + "/" + direction, top, Math::max);
        }
        return new ImportResult(edgeSchema.name(), edges.size(), bySide.size(), chunks, Map.copyOf(levels));
    }

    private static void side(Map<String, List<AerospikeEdgeChunks.EdgeEntry>> bySide,
                             AerospikeEdgeChunks.EdgeEntry edge, int direction, Object vertexId,
                             Object otherVertexId) {
        // the entry stored for this side carries the vertex the edge points at
        AerospikeEdgeChunks.EdgeEntry sided = new AerospikeEdgeChunks.EdgeEntry(edge.edgeId(),
                otherVertexId, edge.createdTime(), edge.lastUpdatedTime(), edge.value());
        bySide.computeIfAbsent(sideKey(direction, vertexId), k -> new ArrayList<>()).add(sided);
    }

    private static String sideKey(int direction, Object vertexId) {
        return direction + "\u0000" + vertexId;
    }

    private static int directionOf(String sideKey) {
        return Integer.parseInt(sideKey.substring(0, sideKey.indexOf('\u0000')));
    }

    private static Object vertexOf(String sideKey) {
        return sideKey.substring(sideKey.indexOf('\u0000') + 1);
    }

    /**
     * Serializes an edge the way the chunk tables store it: the same
     * {@code [lastUpdatedTime, createdTime, otherVertexId, properties]} shape the inline bins use,
     * so a merge of the two sources compares like with like.
     */
    public static List<Object> serialize(EdgeSchema edgeSchema, Object otherVertexId, long createdTime,
                                         long lastUpdatedTime, Map<String, Object> propertiesByName) {
        Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : propertiesByName.entrySet()) {
            EdgePropertySchema propertySchema = edgeSchema.property(entry.getKey());
            if (propertySchema == null) {
                throw new IllegalArgumentException("Unknown property '" + entry.getKey()
                        + "' in edge class '" + edgeSchema.name() + "'");
            }
            properties.put(propertySchema.id(), new EdgePropertyValue(entry.getValue(),
                    createdTime, lastUpdatedTime));
        }
        return AerospikeEdgeStore.serializeEdgeValue(
                AerospikeEdgeStore.edgeLastUpdatedTime(lastUpdatedTime, properties),
                createdTime, otherVertexId, properties);
    }

    /** The chunk store behind this importer (used by tests and diagnostics). */
    public AerospikeEdgeChunkStore chunkStore() {
        return store;
    }
    /**
     * Points this store's metrics at a graph's counters. Called while wiring the
     * application (and by tests); until then the store records nothing.
     */
    public AerospikeEdgeBulkImporter metrics(MetricsSink sink) {
        metrics.target(sink);
        return this;
    }

}
