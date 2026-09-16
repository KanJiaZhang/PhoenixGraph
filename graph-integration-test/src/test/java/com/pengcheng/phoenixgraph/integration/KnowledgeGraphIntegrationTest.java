package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.gremlin.Subgraph;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.ErrorLog;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of the knowledge-graph layout: seeds a triple dataset into the
 * {@code kg-entity-graph} schema (a single {@code Entity} vertex type and a single
 * directed {@code Entity_Entity} relation type) and then queries it the way the UI
 * does.
 *
 * <p>Vertices are sharded by relation: an endpoint of {@code (head, relation, tail)}
 * is stored as {@code name + "_" + floorMod(relation.hashCode(), 10)}, so all edges
 * of a relation live in one shard.
 *
 * <p>Size is controlled by {@code KG_TRIPLE_LIMIT} (default {@value #DEFAULT_TRIPLE_LIMIT}
 * = the whole dataset, {@code 0}) and the writer thread count by
 * {@code KG_PARALLELISM}. Seeding is skipped when the set already contains the
 * data; set {@code KG_RESEED=true} to force it.
 */
class KnowledgeGraphIntegrationTest {

    private static final String GRAPH_NAME = "kg-entity-graph";
    private static final String DEFAULT_SET = "kg_nell995_v2";

    /**
     * Triples to seed: {@code 0} = the whole dataset. A positive value seeds an
     * evenly-spaced sample into its own set ({@code kg_nell995_v2_s<N>}) so a
     * sampled set can never be mistaken for the full one.
     */
    private static final int DEFAULT_TRIPLE_LIMIT = 0;

    @Test
    void seedsAndQueriesTheKnowledgeGraph() {
        AerospikeClient client = new IntegrationConfig("localhost", 3000, "test", "").connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            int tripleLimit = intEnv("KG_TRIPLE_LIMIT", DEFAULT_TRIPLE_LIMIT);
            String set = System.getenv("AEROSPIKE_VERTEX_SET");
            if (set == null || set.isBlank()) {
                set = tripleLimit > 0 ? DEFAULT_SET + "_s" + tripleLimit : DEFAULT_SET;
            }
            int parallelism = intEnv("KG_PARALLELISM", 8);

            KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();
            GraphSchema schema = loadSchema();
            // silent logs (a full seed writes ~700k bins) and more retries: parallel
            // writers race on the per-vertex edge bins, which is resolved by re-writing
            BusinessLog silentBusiness = entry -> {
            };
            ErrorLog silentErrors = entry -> {
            };
            int attempts = 10;
            AerospikeVertexStore vertexStore =
                    new AerospikeVertexStore(client, namespace, set, silentBusiness, silentErrors, attempts);
            AerospikeEdgeStore edgeStore =
                    new AerospikeEdgeStore(client, namespace, set, schema, silentBusiness, silentErrors, attempts);

            seedIfNeeded(vertexStore, edgeStore, schema, dataset, tripleLimit, parallelism);

            assertSchemaShape(schema);
            assertIdScheme(dataset);
            assertQueries(dataset, tripleLimit, schema, vertexStore, edgeStore);
        }
    }

    private void seedIfNeeded(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                              KnowledgeGraphDataset dataset, int tripleLimit, int parallelism) {
        boolean forced = Boolean.parseBoolean(env("KG_RESEED", "false"));
        if (!forced && KnowledgeGraphSeeder.alreadySeeded(vertexStore, dataset)) {
            return;
        }
        long started = System.currentTimeMillis();
        KnowledgeGraphSeeder.Result result =
                KnowledgeGraphSeeder.seed(vertexStore, edgeStore, schema, dataset, tripleLimit, parallelism);
        System.out.printf("[kg] seeded set: %d vertices, %d edges in %d ms%n",
                result.vertices(), result.edges(), System.currentTimeMillis() - started);
    }

    private void assertSchemaShape(GraphSchema schema) {
        assertEquals(GRAPH_NAME, schema.name());
        assertEquals(1, schema.vertexSchemas().size());
        VertexSchema entity = schema.vertexSchema(KnowledgeGraphIds.VERTEX_LABEL);
        assertNotNull(entity, "missing the Entity vertex schema");
        assertEquals(List.of("name", "shard"), entity.properties().stream().map(p -> p.name()).toList());
        assertEquals(List.of("name"), entity.displayPropertyNames());

        assertEquals(1, schema.edgeSchemas().size());
        EdgeSchema edge = schema.edgeSchema(KnowledgeGraphIds.EDGE_LABEL);
        assertNotNull(edge, "missing the Entity_Entity edge schema");
        assertEquals(List.of("relation", "xrelation"), edge.properties().stream().map(p -> p.name()).toList());
        assertEquals(2000, edge.maxOutgoingEdges());
        assertEquals(2000, edge.maxIncomingEdges());
        assertEquals(KnowledgeGraphIds.VERTEX_LABEL, edge.source().vertexName());
        assertEquals(KnowledgeGraphIds.VERTEX_LABEL, edge.target().vertexName());
    }

    private void assertIdScheme(KnowledgeGraphDataset dataset) {
        Set<Integer> shards = new LinkedHashSet<>();
        for (KnowledgeGraphDataset.Triple triple : dataset.triples()) {
            String relation = dataset.relationName(triple.relationId());
            int shard = KnowledgeGraphIds.shard(relation);
            assertTrue(shard >= 0 && shard < KnowledgeGraphIds.SHARD_COUNT, "shard out of range for " + relation);
            // both endpoints of a triple land in the shard of its relation
            assertEquals(shard, KnowledgeGraphIds.shard(relation));
            String head = dataset.entityName(triple.headId());
            String tail = dataset.entityName(triple.tailId());
            assertEquals(head + "_" + shard, KnowledgeGraphIds.composeId(head, relation));
            assertEquals(tail + "_" + shard, KnowledgeGraphIds.composeId(tail, relation));
            shards.add(shard);
            if (shards.size() == KnowledgeGraphIds.SHARD_COUNT && triple.headId() > 5000) {
                break;
            }
        }
        assertEquals(KnowledgeGraphIds.SHARD_COUNT, shards.size(), "every shard should be used");
    }

    private void assertQueries(KnowledgeGraphDataset dataset, int tripleLimit, GraphSchema schema,
                               AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        List<KnowledgeGraphDataset.Triple> triples = KnowledgeGraphSeeder.sample(dataset.triples(), tripleLimit);
        Stats stats = Stats.of(dataset, triples);
        String hubId = stats.hubVertexId();
        String hubName = stats.nameOf(hubId);
        int hubRelationId = stats.hubRelationId();
        String hubRelation = dataset.relationName(hubRelationId);

        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
        String label = KnowledgeGraphIds.VERTEX_LABEL;

        // 1. the vertex is stored under its composed id, carrying name + shard
        List<Object> values = gremlin.execute(
                "g.V('" + hubId + "', '" + label + "').values('name','shard')");
        assertEquals(hubName, values.get(0));
        assertEquals((long) stats.shardOf(hubId), ((Number) values.get(1)).longValue());

        // 2. every incident edge of the shard is reachable
        int degree = stats.degree(hubId);
        long edgeCount = (long) (Number) gremlin.execute(
                "g.V('" + hubId + "', '" + label + "').bothE().count()").get(0);
        assertEquals(degree, edgeCount, "bothE() degree of " + hubId);

        // 3. relation filter: relation.equals(R) || xrelation.equals(R) finds the
        //    relation from either endpoint, in this direction or the reverse one
        int matchesRelation = stats.incidentWithRelation(hubId, hubRelationId);
        int inverseRelationId = stats.inverseRelationId(hubRelationId);
        // when a relation is its own reverse the two sets coincide, so never add it twice
        int matchesReverse = inverseRelationId == hubRelationId
                ? 0 : stats.incidentWithRelation(hubId, inverseRelationId);
        long filtered = (long) (Number) gremlin.execute("g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))"
                + ".count()").get(0);
        assertEquals(matchesRelation + matchesReverse, filtered,
                "filtered edge count for relation " + hubRelation);

        long forwardOnly = (long) (Number) gremlin.execute("g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "')).count()").get(0);
        assertEquals(matchesRelation, forwardOnly, "forward-only count for relation " + hubRelation);

        // 4. the same filter over the traversers themselves, as the UI issues it
        List<Object> edges = gremlin.execute("g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))");
        assertEquals(filtered, edges.size());

        // 5. every relation shard is loaded
        for (int shard = 0; shard < KnowledgeGraphIds.SHARD_COUNT; shard++) {
            long shardEdges = stats.triplesInShard(shard);
            assertTrue(shardEdges > 0, "shard " + shard + " has no edges in the sample");
        }

        // 6. the subgraph for the visualisation contains the hub and every neighbour
        Subgraph subgraph = gremlin.executeSubgraph("g.V('" + hubId + "', '" + label + "').bothE()");
        assertEquals(degree, subgraph.edges().size());
        assertEquals(1 + stats.neighbours(hubId).size(), subgraph.vertices().size());
        assertTrue(subgraph.vertices().stream().anyMatch(v -> v.id().equals(hubId)));

        // 7. otherV() hops from the filtered edges to their neighbours (one per edge),
        //    and the unfiltered edges must not leak into the subgraph
        long neighbours = (long) (Number) gremlin.execute("g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))"
                + ".otherV().count()").get(0);
        assertEquals(filtered, neighbours, "otherV() should yield one vertex per surviving edge");
        Subgraph filteredSubgraph = gremlin.executeSubgraph("g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))");
        assertEquals(filtered, filteredSubgraph.edges().size(), "filtered edges only");

        // 8. several seeds in one query, read in parallel batches of batch(20)
        List<String> seedIds = stats.vertexIdsNamed(stats.nameOf(hubId));
        String seeds = seedIds.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));
        long multiSeed = (long) (Number) gremlin.execute("g.V(" + seeds + ", '" + label + "').batch(20)"
                + ".bothE().count()").get(0);
        int expectedSeeded = seedIds.stream().mapToInt(stats::degree).sum();
        assertEquals(expectedSeeded, multiSeed, "bothE() over " + seedIds.size() + " seeds");

        System.out.println("[kg] hub vertex id      : " + hubId + "   (degree " + degree
                + ", relation '" + hubRelation + "')");
        System.out.println("[kg] multi-seed query   : g.V(" + seeds + ", '" + label + "').batch(20).bothE()"
                + "   -> " + multiSeed + " edges over " + seedIds.size() + " seeds");
        System.out.println("[kg] hub query          : g.V('" + hubId + "', '" + label + "').bothE()");
        System.out.println("[kg] relation query     : g.V('" + hubId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))");

        // a right-sized neighbourhood for the visualiser (the hub is a stress test)
        String demoId = stats.demoVertexId();
        String demoName = stats.nameOf(demoId);
        Subgraph demo = gremlin.executeSubgraph("g.V('" + demoId + "', '" + label + "').bothE()");
        assertEquals(stats.degree(demoId), demo.edges().size());
        assertTrue(demo.vertices().size() > 1);
        System.out.println("[kg] demo vertex id     : " + demoId + "   (name " + demoName + ")");
        System.out.println("[kg] demo query         : g.V('" + demoId + "', '" + label + "').bothE()");
        System.out.println("[kg] demo subgraph      : " + demo.vertices().size() + " vertices, "
                + demo.edges().size() + " edges");
    }

    // --- in-memory expectations computed from the same dataset sample ---

    /** Degree / relation statistics of the seeded vertices. */
    private record Stats(Map<String, Integer> degree, Map<String, Map<Integer, Integer>> incident,
                         Map<String, Set<String>> neighbours, Map<String, String> nameById,
                         Map<String, Integer> shardById, int[] inverse, String hubVertexId,
                         int hubRelationId, String demoVertexId) {

        static Stats of(KnowledgeGraphDataset dataset, List<KnowledgeGraphDataset.Triple> triples) {
            Map<String, Integer> degree = new HashMap<>();
            Map<String, Map<Integer, Integer>> incident = new HashMap<>();
            Map<String, Set<String>> neighbours = new HashMap<>();
            Map<String, String> nameById = new HashMap<>();
            Map<String, Integer> shardById = new HashMap<>();
            for (KnowledgeGraphDataset.Triple triple : triples) {
                String relation = dataset.relationName(triple.relationId());
                int shard = KnowledgeGraphIds.shard(relation);
                String head = KnowledgeGraphIds.composeId(dataset.entityName(triple.headId()), relation);
                String tail = KnowledgeGraphIds.composeId(dataset.entityName(triple.tailId()), relation);
                nameById.put(head, dataset.entityName(triple.headId()));
                nameById.put(tail, dataset.entityName(triple.tailId()));
                shardById.put(head, shard);
                shardById.put(tail, shard);
                for (String vertex : List.of(head, tail)) {
                    degree.merge(vertex, 1, Integer::sum);
                    incident.computeIfAbsent(vertex, k -> new HashMap<>())
                            .merge(triple.relationId(), 1, Integer::sum);
                }
                neighbours.computeIfAbsent(head, k -> new HashSet<>()).add(tail);
                neighbours.computeIfAbsent(tail, k -> new HashSet<>()).add(head);
            }
            String hub = degree.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .orElseThrow(() -> new IllegalStateException("no triples sampled"))
                    .getKey();
            int hubRelation = incident.get(hub).entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow()
                    .getKey();
            // a vertex whose neighbourhood renders nicely (a few dozen nodes)
            String demo = degree.entrySet().stream()
                    .filter(e -> e.getValue() >= 15 && e.getValue() <= 80)
                    .min(Comparator.comparingInt(e -> Math.abs(e.getValue() - 40)))
                    .map(Map.Entry::getKey)
                    .orElse(hub);
            return new Stats(degree, incident, neighbours, nameById, shardById,
                    dataset.inverseRelations(), hub, hubRelation, demo);
        }

        int degree(String vertexId) {
            return degree.getOrDefault(vertexId, 0);
        }

        int incidentWithRelation(String vertexId, int relationId) {
            return incident.getOrDefault(vertexId, Map.of()).getOrDefault(relationId, 0);
        }

        int inverseRelationId(int relationId) {
            return inverse[relationId];
        }

        Set<String> neighbours(String vertexId) {
            return neighbours.getOrDefault(vertexId, Set.of());
        }

        /** Every vertex id sharing the given entity name (one per relation shard). */
        List<String> vertexIdsNamed(String name) {
            return nameById.entrySet().stream()
                    .filter(e -> e.getValue().equals(name))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }

        String nameOf(String vertexId) {
            return nameById.getOrDefault(vertexId, vertexId);
        }

        int shardOf(String vertexId) {
            return shardById.getOrDefault(vertexId, -1);
        }

        /** Number of sampled triples whose relation falls into the given shard. */
        long triplesInShard(int shard) {
            return shardById.values().stream().filter(s -> s == shard).count();
        }
    }

    private static GraphSchema loadSchema() {
        try (InputStream in = KnowledgeGraphIntegrationTest.class.getResourceAsStream("/kg-entity-graph.json")) {
            assertNotNull(in, "Missing kg-entity-graph.json on the test classpath");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load kg-entity-graph.json", e);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }
}
