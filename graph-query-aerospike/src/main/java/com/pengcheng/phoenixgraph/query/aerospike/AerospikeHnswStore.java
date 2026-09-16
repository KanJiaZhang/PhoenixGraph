package com.pengcheng.phoenixgraph.query.aerospike;

import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswLink;
import com.pengcheng.phoenixgraph.hnsw.HnswNeighbor;
import com.pengcheng.phoenixgraph.hnsw.HnswStore;
import com.pengcheng.phoenixgraph.hnsw.HnswUnlink;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeDelete;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredEdgeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aerospike {@link HnswStore}: layer {@code l} is read from, and written to, the
 * bidirectional edge class {@code vector_<l>} on the indexed vertex set.
 *
 * <p>Reads of many vertices use one native batch read
 * ({@link AerospikeEdgeStore#getEdgesBatch}), which is the hot path of a search.
 * Writes are aggregated by {@link AerospikeEdgeStore#upsertEdges} /
 * {@link AerospikeEdgeStore#deleteEdges}: the links and the prunes of one insert
 * are sent as a single request, and the store rewrites each touched
 * {@code (vertex, layer)} bin once, so an insert costs a handful of round trips
 * instead of one per edge.
 */
public final class AerospikeHnswStore implements HnswStore {

    /** Attempts per batch before a write is reported as failed (concurrent writers race on bins). */
    private static final int WRITE_ATTEMPTS = 3;

    private final AerospikeEdgeStore edgeStore;
    private final GraphSchema schema;
    private final String vertexName;
    private final Map<Integer, EdgeSchema> layerSchemas = new ConcurrentHashMap<>();

    public AerospikeHnswStore(AerospikeEdgeStore edgeStore, GraphSchema schema, String vertexName) {
        this.edgeStore = Objects.requireNonNull(edgeStore, "edgeStore");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.vertexName = Objects.requireNonNull(vertexName, "vertexName");
    }

    @Override
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        EdgeSchema layer = layerSchema(level);
        return decode(edgeStore.getEdges(vertexId, layer.id(), AerospikeEdgeStore.EDGE_DIR_BOTH));
    }

    @Override
    public List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        EdgeSchema layer = layerSchema(level);
        List<List<StoredEdgeValue>> raw =
                edgeStore.getEdgesBatch(vertexIds, layer.id(), AerospikeEdgeStore.EDGE_DIR_BOTH);
        List<List<HnswNeighbor>> out = new ArrayList<>(raw.size());
        for (List<StoredEdgeValue> perVertex : raw) {
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

    /** Writes every link of one insert with a single aggregated upsert. */
    @Override
    public void linkBatch(List<HnswLink> links) {
        if (links.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<EdgeUpsert> upserts = new ArrayList<>(links.size());
        for (HnswLink link : links) {
            upserts.add(new EdgeUpsert(link.edgeId(), layerSchema(link.level()), link.from(), link.to(),
                    Map.of(), now, now));
        }
        List<EdgeUpsert> pending = upserts;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            List<WriteOutcome> outcomes = edgeStore.upsertEdges(pending);
            List<EdgeUpsert> failed = new ArrayList<>();
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
        throw new QueryException("Failed to write " + pending.size() + " HNSW links (" + vertexName + ")");
    }

    @Override
    public void unlink(String edgeId, Object from, Object to, int level) {
        unlinkBatch(List.of(new HnswUnlink(edgeId, from, to, level)));
    }

    /** Removes every pruned link of one insert with a single aggregated delete. */
    @Override
    public void unlinkBatch(List<HnswUnlink> unlinks) {
        if (unlinks.isEmpty()) {
            return;
        }
        List<EdgeDelete> deletes = new ArrayList<>(unlinks.size());
        for (HnswUnlink unlink : unlinks) {
            deletes.add(new EdgeDelete(unlink.edgeId(), layerSchema(unlink.level()), unlink.from(), unlink.to()));
        }
        List<EdgeDelete> pending = deletes;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            List<WriteOutcome> outcomes = edgeStore.deleteEdges(pending);
            List<EdgeDelete> failed = new ArrayList<>();
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
        throw new QueryException("Failed to delete " + pending.size() + " HNSW links (" + vertexName + ")");
    }

    /** The edge class backing a layer, resolved (and cached) from the graph schema. */
    public EdgeSchema layerSchema(int level) {
        return layerSchemas.computeIfAbsent(level, l -> {
            String name = HnswLevels.edgeName(l);
            EdgeSchema edgeSchema = schema.edgeSchema(name);
            if (edgeSchema == null) {
                throw new QueryException("HNSW layer edge class '" + name + "' is missing from the schema of "
                        + vertexName + " (the index needs the " + HnswLevels.layerCount(l + 1)
                        + " layer classes vector_0.." + name + ")");
            }
            return edgeSchema;
        });
    }

    private static List<HnswNeighbor> decode(List<StoredEdgeValue> edges) {
        List<HnswNeighbor> neighbours = new ArrayList<>(edges.size());
        for (StoredEdgeValue edge : edges) {
            neighbours.add(new HnswNeighbor(edge.edgeId(), edge.otherVertexId()));
        }
        return neighbours;
    }
}
