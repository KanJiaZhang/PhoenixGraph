package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgePropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeUpsert;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Writes a {@link KnowledgeGraphDataset} into the {@code kg-entity-graph}
 * storage layout.
 *
 * <p>Every dataset entity becomes one vertex in its
 * {@code Entity<slot>} schema ({@link KnowledgeGraphIds}), and every triple
 * becomes one <em>directed</em> edge of schema
 * {@code Entity<headSlot>_Entity<tailSlot>} carrying
 * {@code relation} (the forward name) and {@code xrelation} (its reverse name).
 * Each edge is stored on both endpoints (out bin on the head, in bin on the
 * tail), so a single edge answers queries from either end.
 */
final class KnowledgeGraphSeeder {

    /** Vertices per batch write. */
    static final int VERTEX_BATCH = 500;

    /** Edges per batch write. */
    static final int EDGE_BATCH = 1000;

    /** Attempts per chunk before a write is counted as failed (parallel writers race on bins). */
    static final int WRITE_ATTEMPTS = 4;

    private KnowledgeGraphSeeder() {
    }

    /** What a seed run wrote. */
    record Result(int vertices, int edges) {
    }

    /** Whether the dataset has already been written (checked through one well-known vertex). */
    static boolean alreadySeeded(AerospikeVertexStore vertexStore, KnowledgeGraphDataset dataset) {
        String name = dataset.entityName(0);
        return vertexStore.readVertex(KnowledgeGraphIds.composeId(name)) != null;
    }

    /**
     * Seeds at most {@code tripleLimit} triples ({@code <= 0} = all of them), spread
     * evenly over the dataset so every relation / type pair stays represented.
     */
    static Result seed(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                       GraphSchema schema, KnowledgeGraphDataset dataset, int tripleLimit, int parallelism) {
        List<KnowledgeGraphDataset.Triple> triples = sample(dataset.triples(), tripleLimit);
        long now = System.currentTimeMillis();

        List<VertexUpsert> vertices = buildVertices(dataset, schema, triples, now);
        List<EdgeUpsert> edges = buildEdges(dataset, schema, triples, now);

        AtomicLong failures = new AtomicLong();
        AtomicLong verticesWritten = new AtomicLong();
        runChunks(chunkCount(vertices.size(), VERTEX_BATCH), parallelism, chunk -> {
            List<VertexUpsert> slice = slice(vertices, chunk, VERTEX_BATCH);
            List<VertexUpsert> pending = slice;
            for (int attempt = 1; attempt <= WRITE_ATTEMPTS && !pending.isEmpty(); attempt++) {
                pending = failed(vertexStore.upsertVertices(pending), pending);
            }
            failures.addAndGet(pending.size());
            verticesWritten.addAndGet(slice.size());
        });
        runChunks(chunkCount(edges.size(), EDGE_BATCH), parallelism, chunk -> {
            List<EdgeUpsert> pending = slice(edges, chunk, EDGE_BATCH);
            for (int attempt = 1; attempt <= WRITE_ATTEMPTS && !pending.isEmpty(); attempt++) {
                pending = failed(edgeStore.upsertEdges(pending), pending);
            }
            failures.addAndGet(pending.size());
        });

        if (failures.get() > 0) {
            throw new IllegalStateException("Knowledge graph seed failed: " + failures.get() + " writes failed");
        }
        return new Result((int) verticesWritten.get(), edges.size());
    }

    /** The writes reported as failed, so a chunk can be re-issued (generation conflicts). */
    private static <T> List<T> failed(List<WriteOutcome> outcomes, List<T> written) {
        List<T> failed = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == WriteOutcome.FAILED) {
                failed.add(written.get(i));
            }
        }
        return failed;
    }

    private static List<VertexUpsert> buildVertices(KnowledgeGraphDataset dataset, GraphSchema schema,
                                                    List<KnowledgeGraphDataset.Triple> triples, long now) {
        Set<Integer> used = new LinkedHashSet<>();
        for (KnowledgeGraphDataset.Triple triple : triples) {
            used.add(triple.headId());
            used.add(triple.tailId());
        }
        List<VertexUpsert> vertices = new ArrayList<>(used.size());
        long tick = 0;
        for (int entityId : used) {
            String name = dataset.entityName(entityId);
            String label = KnowledgeGraphIds.vertexLabel(name);
            long timestamp = now + tick++;
            Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
            properties.put(1, new VertexPropertyValue(name, timestamp));          // name (display property)
            properties.put(2, new VertexPropertyValue(label, timestamp));         // type
            vertices.add(new VertexUpsert(KnowledgeGraphIds.composeId(name),
                    schema.vertexSchema(label), properties, timestamp, timestamp));
        }
        return vertices;
    }

    private static List<EdgeUpsert> buildEdges(KnowledgeGraphDataset dataset, GraphSchema schema,
                                               List<KnowledgeGraphDataset.Triple> triples, long now) {
        List<EdgeUpsert> edges = new ArrayList<>(triples.size());
        for (int i = 0; i < triples.size(); i++) {
            KnowledgeGraphDataset.Triple triple = triples.get(i);
            String head = dataset.entityName(triple.headId());
            String tail = dataset.entityName(triple.tailId());
            String edgeLabel = KnowledgeGraphIds.edgeLabel(head, tail);
            EdgeSchema edgeSchema = schema.edgeSchema(edgeLabel);
            if (edgeSchema == null) {
                throw new IllegalStateException("Dataset produced an edge of unknown schema: " + edgeLabel);
            }
            long timestamp = now + (i % 1000);
            Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
            properties.put(3, new EdgePropertyValue(dataset.relationName(triple.relationId()),
                    timestamp, timestamp));
            properties.put(4, new EdgePropertyValue(dataset.inverseRelationName(triple.relationId()),
                    timestamp, timestamp));
            edges.add(new EdgeUpsert("e" + i, edgeSchema,
                    KnowledgeGraphIds.composeId(head), KnowledgeGraphIds.composeId(tail),
                    properties, timestamp, timestamp));
        }
        return edges;
    }

    /** Evenly-spaced sample of the triples (keeps every relation / type pair present). */
    static List<KnowledgeGraphDataset.Triple> sample(List<KnowledgeGraphDataset.Triple> triples, int limit) {
        if (limit <= 0 || triples.size() <= limit) {
            return triples;
        }
        List<KnowledgeGraphDataset.Triple> sampled = new ArrayList<>(limit);
        double step = (double) triples.size() / limit;
        for (int i = 0; i < limit; i++) {
            sampled.add(triples.get((int) (i * step)));
        }
        return sampled;
    }

    private static int chunkCount(int size, int batch) {
        return (size + batch - 1) / batch;
    }

    private static <T> List<T> slice(List<T> items, int chunk, int batch) {
        int start = chunk * batch;
        return items.subList(start, Math.min(start + batch, items.size()));
    }

    /** Runs {@code chunks} independent write tasks, optionally on a thread pool. */
    private static void runChunks(int chunks, int parallelism, IntConsumer task) {
        if (chunks <= 0) {
            return;
        }
        if (parallelism <= 1) {
            for (int i = 0; i < chunks; i++) {
                task.accept(i);
            }
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, chunks));
        try {
            List<Future<?>> futures = new ArrayList<>(chunks);
            for (int i = 0; i < chunks; i++) {
                int chunk = i;
                futures.add(pool.submit(() -> task.accept(chunk)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Knowledge graph seed write failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Knowledge graph seed interrupted", e);
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
