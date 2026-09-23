package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswLink;
import com.pengcheng.phoenixgraph.hnsw.HnswNeighbor;
import com.pengcheng.phoenixgraph.hnsw.HnswStore;
import com.pengcheng.phoenixgraph.hnsw.HnswUnlink;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB {@link HnswStore}: layer {@code l} is read from, and written to, the bidirectional edge class
 * {@code <vectorProperty>_<l>} of the indexed vertex type ({@link HnswLevels#edgeName(String, int)}).
 *
 * <p>This is the write side of a RocksDB-Edge vector index, the counterpart of the Aerospike
 * {@code AerospikeHnswStore}. The layer classes are ordinary edge classes of the graph, so they live in
 * their own column families and are created on demand (a graph that declares a vector property does not
 * have to name nine layer classes in its schema). A read of many vertices is one RocksDB multi-get
 * ({@link RocksDbEdgeStore#readEdgesBatch}), and the links of one insert are one aggregated write
 * ({@link RocksDbEdgeStore#upsertEdges}), which is what keeps an insert to a few touching of each
 * {@code (vertex, layer)} row rather than one per link.
 *
 * <p>Searching does not need this class: {@code GraphQueryHnswStore} reads the same layer classes through
 * the {@code GraphQuery} interface, so a reader works for any storage that can read edges.
 */
public final class RocksDbHnswStore implements HnswStore {

    /** Attempts per write before it is reported as failed (a concurrent writer may hold the same row). */
    private static final int WRITE_ATTEMPTS = 3;

    private final RocksDbEdgeStore edgeStore;
    private final GraphSchema schema;
    private final String vertexName;
    private final String vectorProperty;
    private final Map<Integer, EdgeSchema> layerSchemas = new ConcurrentHashMap<>();

    public RocksDbHnswStore(RocksDbEdgeStore edgeStore, GraphSchema schema, String vertexName,
                            String vectorProperty) {
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.vertexName = Objects.requireNonNull(vertexName, "vertexName");
        this.vectorProperty = Objects.requireNonNull(vectorProperty, "vectorProperty");
    }

    @Override
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        EdgeSchema layer = declaredLayerSchema(level);
        if (layer == null) {
            return List.of(); // the layer class does not exist: nothing was ever written on it
        }
        return decode(edgeStore.readEdges(layer.name(), vertexId, directionOf(layer), TimeRange.ALL,
                List.of()));
    }

    @Override
    public List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        EdgeSchema layer = declaredLayerSchema(level);
        if (layer == null) {
            return vertexIds.stream().map(id -> List.<HnswNeighbor>of()).toList();
        }
        List<List<RocksDbStoredEdge>> raw = edgeStore.readEdgesBatch(layer.name(), directionOf(layer),
                TimeRange.ALL, vertexIds, List.of());
        List<List<HnswNeighbor>> out = new ArrayList<>(raw.size());
        for (List<RocksDbStoredEdge> perVertex : raw) {
            out.add(decode(perVertex));
        }
        return out;
    }

    @Override
    public void linkAll(Object from, List<Object> to, int level) {
        if (to.isEmpty()) {
            return;
        }
        List<HnswLink> links = new ArrayList<>(to.size());
        for (Object other : to) {
            links.add(new HnswLink(edgeId(from, other, level), from, other, level));
        }
        linkBatch(links);
    }

    /** Writes every link of one insert, one batch per layer (a batch must share one edge class). */
    @Override
    public void linkBatch(List<HnswLink> links) {
        if (links.isEmpty()) {
            return;
        }
        Map<Integer, List<RocksDbEdgeUpsert>> byLevel = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (HnswLink link : links) {
            byLevel.computeIfAbsent(link.level(), level -> new ArrayList<>())
                    .add(new RocksDbEdgeUpsert(link.edgeId(), layerSchema(link.level()), link.from(),
                            link.to(), Map.of(), now, now));
        }
        for (List<RocksDbEdgeUpsert> batch : byLevel.values()) {
            retry(batch, edges -> edgeStore.upsertEdges(edges), "write");
        }
    }

    @Override
    public void unlink(String edgeId, Object from, Object to, int level) {
        unlinkBatch(List.of(new HnswUnlink(edgeId, from, to, level)));
    }

    /**
     * Removes every pruned link of one insert.
     *
     * <p>A RocksDB edge row is keyed by its endpoints <b>and</b> its creation time, so a delete needs the
     * creation time of the row it removes: it is read back here (one read per {@code (vertex, layer)}
     * pair, served by the store's edge cache) instead of being guessed. Pruning is rare next to linking,
     * so the extra read is cheaper than carrying a timestamp through the search.
     */
    @Override
    public void unlinkBatch(List<HnswUnlink> unlinks) {
        if (unlinks.isEmpty()) {
            return;
        }
        List<RocksDbEdgeUpsert> deletes = new ArrayList<>(unlinks.size());
        for (HnswUnlink unlink : unlinks) {
            EdgeSchema layer = layerSchema(unlink.level());
            long createdTime = creationTimeOf(layer, unlink);
            if (createdTime < 0) {
                continue; // already gone
            }
            deletes.add(new RocksDbEdgeUpsert(unlink.edgeId(), layer, unlink.from(), unlink.to(), Map.of(),
                    createdTime, createdTime));
        }
        for (RocksDbEdgeUpsert delete : deletes) {
            if (!edgeStore.deleteEdge(delete.edgeSchema(), delete.edgeId(), delete.createdTime(),
                    delete.source(), delete.destination())) {
                // the row was already removed by another writer: the outcome is the one we wanted
                continue;
            }
        }
    }

    /** The creation time of the row behind a link, or {@code -1} when it is not there any more. */
    private long creationTimeOf(EdgeSchema layer, HnswUnlink unlink) {
        for (RocksDbStoredEdge edge : edgeStore.readEdges(layer.name(), unlink.from(), directionOf(layer),
                TimeRange.ALL, List.of())) {
            if (edge.edgeId().equals(unlink.edgeId())) {
                return edge.createdTime();
            }
        }
        return -1;
    }

    /** The edge class backing a layer, or {@code null} when the schema does not declare it. */
    public EdgeSchema declaredLayerSchema(int level) {
        return schema.edgeSchema(HnswLevels.edgeName(vectorProperty, level));
    }

    /** The edge class backing a layer, resolved (and cached) from the graph schema. */
    public EdgeSchema layerSchema(int level) {
        return layerSchemas.computeIfAbsent(level, l -> {
            String name = HnswLevels.edgeName(vectorProperty, l);
            EdgeSchema edgeSchema = schema.edgeSchema(name);
            if (edgeSchema == null) {
                throw new QueryException("HNSW layer edge class '" + name + "' is missing from the schema of "
                        + vertexName + " (vector property '" + vectorProperty
                        + "'; layers are created on demand by the index provisioner)");
            }
            return edgeSchema;
        });
    }

    /** The layer classes are bidirectional, so both sides live in the one undirected row. */
    private static byte directionOf(EdgeSchema layer) {
        return RocksDbEdgeStore.directionCode(EdgeSide.BOTH, layer.direction());
    }

    private static List<HnswNeighbor> decode(List<RocksDbStoredEdge> edges) {
        List<HnswNeighbor> neighbours = new ArrayList<>(edges.size());
        for (RocksDbStoredEdge edge : edges) {
            neighbours.add(new HnswNeighbor(edge.edgeId(), edge.otherVertexId()));
        }
        return neighbours;
    }

    /** Retries the writes that failed, and reports the ones that never went through. */
    private <T> void retry(List<T> writes, java.util.function.Function<List<T>, List<WriteOutcome>> write,
                          String what) {
        List<T> pending = writes;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            List<WriteOutcome> outcomes = write.apply(pending);
            List<T> failed = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                if (outcomes.get(i) == WriteOutcome.FAILED) {
                    failed.add(pending.get(i));
                }
            }
            if (failed.isEmpty()) {
                return;
            }
            pending = failed;
        }
        throw new QueryException("Failed to " + what + " " + pending.size() + " HNSW links (" + vertexName
                + ", vector property " + vectorProperty + ")");
    }
}
