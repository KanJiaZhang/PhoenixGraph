package com.pengcheng.phoenixgraph.integration.sanguo;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Writes a {@link SanguoDataset} into the {@code sanguo-kg} Aerospike layout —
 * the same <em>relation-sharded</em> modelling as {@code kg-entity-graph}.
 *
 * <p>Every entity becomes one vertex per <em>relation shard</em> it takes part in
 * ({@link SanguoGraphIds}): the id is
 * {@code name + "_" + floorMod(relation.hashCode(), 10)}, so all edges of one
 * relation land in one pair of shards. Every parsed relation becomes one
 * <em>directed</em> edge of the single {@code Entity_Entity} schema carrying
 * {@code relation} (forward name), {@code xrelation} (its inverse), and the
 * ontology's {@code relationLabel} / {@code relationFamily}. Each edge is stored
 * on both endpoints (out bin on the head, in bin on the tail), so a single edge
 * answers queries from either end.
 *
 * <p>Vertex properties come from the person document when there is one
 * ({@code entityType}, {@code gender}, {@code courtesyName}, {@code historicity},
 * {@code history}, {@code novel}); a relation-only person (刘备 has no document
 * upstream) or an attribute entity (势力/籍贯/官职/章回) carries just
 * {@code name} / {@code shard} / {@code entityType}.
 */
final class SanguoGraphSeeder {

    /** Vertices per batch write. */
    static final int VERTEX_BATCH = 500;

    /** Edges per batch write. */
    static final int EDGE_BATCH = 1000;

    /** Attempts per chunk before a write is counted as failed (parallel writers race on bins). */
    static final int WRITE_ATTEMPTS = 4;

    private static final int PROPERTY_NAME = 1;
    private static final int PROPERTY_SHARD = 2;
    private static final int PROPERTY_ENTITY_TYPE = 3;
    private static final int PROPERTY_GENDER = 4;
    private static final int PROPERTY_COURTESY_NAME = 5;
    private static final int PROPERTY_HISTORICITY = 6;
    private static final int PROPERTY_HISTORY = 7;
    private static final int PROPERTY_NOVEL = 8;

    private static final int EDGE_PROPERTY_RELATION = 3;
    private static final int EDGE_PROPERTY_XRELATION = 4;
    private static final int EDGE_PROPERTY_RELATION_LABEL = 5;
    private static final int EDGE_PROPERTY_RELATION_FAMILY = 6;

    private SanguoGraphSeeder() {
    }

    /** What a seed run wrote. */
    record Result(int vertices, int edges) {
    }

    /** The single vertex type / edge type of the schema. */
    static void checkSchema(GraphSchema schema) {
        if (schema.vertexSchema(SanguoGraphIds.VERTEX_LABEL) == null) {
            throw new IllegalStateException("schema is missing the " + SanguoGraphIds.VERTEX_LABEL + " vertex");
        }
        if (schema.edgeSchema(SanguoGraphIds.EDGE_LABEL) == null) {
            throw new IllegalStateException("schema is missing the " + SanguoGraphIds.EDGE_LABEL + " edge");
        }
    }

    /** Whether the dataset has already been written (checked through one well-known vertex). */
    static boolean alreadySeeded(AerospikeVertexStore vertexStore, SanguoDataset dataset) {
        SanguoDataset.Relation first = dataset.relations().get(0);
        return vertexStore.readVertex(SanguoGraphIds.composeId(first.head(), first.relation())) != null;
    }

    /** Writes the whole dataset. */
    static Result seed(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore,
                       GraphSchema schema, SanguoDataset dataset, int parallelism) {
        checkSchema(schema);
        long now = System.currentTimeMillis();

        List<VertexUpsert> vertices = buildVertices(dataset, schema, now);
        List<EdgeUpsert> edges = buildEdges(dataset, schema, now);

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
            throw new IllegalStateException("Sanguo knowledge graph seed failed: " + failures.get()
                    + " writes failed");
        }
        return new Result((int) verticesWritten.get(), edges.size());
    }

    /** One vertex per (entity, relation shard); the same entity is replicated per shard. */
    private static List<VertexUpsert> buildVertices(SanguoDataset dataset, GraphSchema schema, long now) {
        Map<String, String> nameByVertexId = new LinkedHashMap<>();
        Map<String, Integer> shardByVertexId = new LinkedHashMap<>();
        for (SanguoDataset.Relation relation : dataset.closedRelations()) {
            int shard = SanguoGraphIds.shard(relation.relation());
            for (String name : List.of(relation.head(), relation.tail())) {
                String vertexId = SanguoGraphIds.composeId(name, shard);
                nameByVertexId.putIfAbsent(vertexId, name);
                shardByVertexId.putIfAbsent(vertexId, shard);
            }
        }

        Map<String, SanguoDataset.Document> documents = dataset.documentsByName();
        List<VertexUpsert> vertices = new ArrayList<>(nameByVertexId.size());
        long tick = 0;
        for (Map.Entry<String, String> entry : nameByVertexId.entrySet()) {
            String vertexId = entry.getKey();
            String name = entry.getValue();
            int shard = shardByVertexId.get(vertexId);
            SanguoDataset.Entity entity = dataset.entities().get(name);
            SanguoDataset.Document document = documents.get(name);
            long timestamp = now + tick++;

            Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
            properties.put(PROPERTY_NAME, new VertexPropertyValue(name, timestamp));
            properties.put(PROPERTY_SHARD, new VertexPropertyValue((long) shard, timestamp));
            properties.put(PROPERTY_ENTITY_TYPE,
                    new VertexPropertyValue(entity == null ? "Person" : entity.type().rdfClass(), timestamp));
            if (document != null) {
                put(properties, PROPERTY_GENDER, document.gender(), timestamp);
                put(properties, PROPERTY_COURTESY_NAME, document.courtesyName(), timestamp);
                put(properties, PROPERTY_HISTORICITY, document.historicity(), timestamp);
                put(properties, PROPERTY_HISTORY, truncate(document.history()), timestamp);
                put(properties, PROPERTY_NOVEL, truncate(document.novel()), timestamp);
            }
            vertices.add(new VertexUpsert(vertexId, schema.vertexSchema(SanguoGraphIds.VERTEX_LABEL),
                    properties, timestamp, timestamp));
        }
        return vertices;
    }

    /** One directed edge per parsed relation (the closure is already materialized). */
    private static List<EdgeUpsert> buildEdges(SanguoDataset dataset, GraphSchema schema, long now) {
        EdgeSchema edgeSchema = schema.edgeSchema(SanguoGraphIds.EDGE_LABEL);
        if (edgeSchema == null) {
            throw new IllegalStateException("Missing edge schema: " + SanguoGraphIds.EDGE_LABEL);
        }
        SanguoOntology ontology = dataset.ontology();
        List<SanguoDataset.Relation> relations = dataset.closedRelations();
        List<EdgeUpsert> edges = new ArrayList<>(relations.size());
        for (int i = 0; i < relations.size(); i++) {
            SanguoDataset.Relation relation = relations.get(i);
            long timestamp = now + (i % 1000);
            Map<Integer, EdgePropertyValue> properties = new LinkedHashMap<>();
            properties.put(EDGE_PROPERTY_RELATION,
                    new EdgePropertyValue(relation.relation(), timestamp, timestamp));
            properties.put(EDGE_PROPERTY_XRELATION,
                    new EdgePropertyValue(ontology.inverse(relation.relation()), timestamp, timestamp));
            properties.put(EDGE_PROPERTY_RELATION_LABEL,
                    new EdgePropertyValue(ontology.label(relation.relation()), timestamp, timestamp));
            properties.put(EDGE_PROPERTY_RELATION_FAMILY,
                    new EdgePropertyValue(ontology.relationFamilyLabel(relation.relation()), timestamp, timestamp));
            edges.add(new EdgeUpsert(edgeId(relation), edgeSchema,
                    SanguoGraphIds.composeId(relation.head(), relation.relation()),
                    SanguoGraphIds.composeId(relation.tail(), relation.relation()),
                    properties, timestamp, timestamp));
        }
        return edges;
    }

    /**
     * A <b>stable</b> edge id derived from the triple itself, not from its position: the
     * Aerospike edge bins are CDT maps keyed by this id, so an id that shifts when a
     * triple is inserted would leave the old edge behind as a duplicate on the next seed.
     */
    static String edgeId(SanguoDataset.Relation relation) {
        return relation.head() + "|" + relation.relation() + "|" + relation.tail();
    }

    private static void put(Map<Integer, VertexPropertyValue> properties, int index, String value, long timestamp) {
        if (value != null && !value.isEmpty()) {
            properties.put(index, new VertexPropertyValue(value, timestamp));
        }
    }

    private static String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
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
                    throw new IllegalStateException("Sanguo knowledge graph seed write failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Sanguo knowledge graph seed interrupted", e);
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
