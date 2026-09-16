package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.query.gremlin.Subgraph;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of the knowledge-graph layout: seeds a triple dataset into the
 * {@code kg-entity-graph} schema (10 {@code Entity0..Entity9} vertex types, all 100
 * ordered relation types, each edge carrying {@code relation} / {@code xrelation})
 * and then queries it the way the UI does.
 *
 * <p>Size is controlled by {@code KG_TRIPLE_LIMIT} (default
 * {@value #DEFAULT_TRIPLE_LIMIT} evenly-sampled triples, {@code 0} = the whole
 * dataset) and the writer thread count by {@code KG_PARALLELISM}. Seeding is
 * skipped when the set already contains the data; set {@code KG_RESEED=true} to
 * force it.
 */
class KnowledgeGraphIntegrationTest {

    private static final String GRAPH_NAME = "kg-entity-graph";
    private static final String DEFAULT_SET = "kg_nell995_v1";

    /**
     * Triples to seed: {@code 0} = the whole dataset. A positive value seeds an
     * evenly-spaced sample into its own set ({@code kg_nell995_v1_s<N>}) so a
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
            // silent logs (a full seed writes ~300k bins) and more retries: parallel
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
        assertEquals(KnowledgeGraphIds.ENTITY_COUNT, schema.vertexSchemas().size());
        for (int i = 0; i < KnowledgeGraphIds.ENTITY_COUNT; i++) {
            VertexSchema vertex = schema.vertexSchema("Entity" + i);
            assertNotNull(vertex, "missing vertex schema Entity" + i);
            // id + name + type, with `name` flagged as the display property
            assertEquals(List.of("name", "type"),
                    vertex.properties().stream().map(p -> p.name()).toList());
            assertEquals(List.of("name"), vertex.displayPropertyNames());
        }

        int expectedEdges = KnowledgeGraphIds.ENTITY_COUNT * KnowledgeGraphIds.ENTITY_COUNT;
        assertEquals(expectedEdges, schema.edgeSchemas().size());
        for (EdgeSchema edge : schema.edgeSchemas()) {
            assertEquals(2000, edge.maxOutgoingEdges(), edge.name());
            assertEquals(2000, edge.maxIncomingEdges(), edge.name());
            assertEquals(List.of("relation", "xrelation"),
                    edge.properties().stream().map(p -> p.name()).toList());
            // the edge schema name encodes the two endpoint kinds
            assertEquals(edge.source().vertexName() + "_" + edge.target().vertexName(), edge.name());
        }
    }

    private void assertIdScheme(KnowledgeGraphDataset dataset) {
        for (int entityId = 0; entityId < 200; entityId++) {
            String name = dataset.entityName(entityId);
            int slot = KnowledgeGraphIds.slot(name);
            assertTrue(slot >= 0 && slot < KnowledgeGraphIds.ENTITY_COUNT, "slot out of range for " + name);
            assertEquals(slot + "_" + name, KnowledgeGraphIds.composeId(name));
            assertEquals("Entity" + slot, KnowledgeGraphIds.vertexLabel(name));
        }
        // negative hash codes must still map into 0..9
        assertTrue(KnowledgeGraphIds.slot("polyglot") >= 0);
    }

    private void assertQueries(KnowledgeGraphDataset dataset, int tripleLimit, GraphSchema schema,
                               AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        List<KnowledgeGraphDataset.Triple> triples = KnowledgeGraphSeeder.sample(dataset.triples(), tripleLimit);
        Stats stats = Stats.of(dataset, triples);
        int hubEntityId = stats.hubEntityId();
        String hubName = dataset.entityName(hubEntityId);
        String hubId = KnowledgeGraphIds.composeId(hubName);
        String hubLabel = KnowledgeGraphIds.vertexLabel(hubName);
        int hubRelationId = stats.hubRelationId();
        String hubRelation = dataset.relationName(hubRelationId);

        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

        // 1. the vertex is stored under its composed id, in its Entity<slot> schema
        List<Object> values = gremlin.execute(
                "g.V('" + hubId + "', '" + hubLabel + "').values('name','type')");
        assertEquals(List.of(hubName, hubLabel), values);

        // 2. every incident edge is reachable through bothE() (all 100 relation types)
        int degree = stats.degree(hubEntityId);
        long edgeCount = (long) (Number) gremlin.execute(
                "g.V('" + hubId + "', '" + hubLabel + "').bothE().count()").get(0);
        assertEquals(degree, edgeCount, "bothE() degree of " + hubName);

        // 3. relation filter: relation.equals(R) || xrelation.equals(R) finds the
        //    relation from either endpoint, in this direction or the reverse one
        int matchesRelation = stats.incidentWithRelation(hubEntityId, hubRelationId);
        int inverseRelationId = stats.inverseRelationId(hubRelationId);
        // when a relation is its own reverse the two sets coincide, so never add it twice
        int matchesReverse = inverseRelationId == hubRelationId
                ? 0 : stats.incidentWithRelation(hubEntityId, inverseRelationId);
        long filtered = (long) (Number) gremlin.execute("g.V('" + hubId + "', '" + hubLabel + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))"
                + ".count()").get(0);
        assertEquals(matchesRelation + matchesReverse, filtered,
                "filtered edge count for relation " + hubRelation);

        long forwardOnly = (long) (Number) gremlin.execute("g.V('" + hubId + "', '" + hubLabel + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "')).count()").get(0);
        assertEquals(matchesRelation, forwardOnly, "forward-only count for relation " + hubRelation);
        assertTrue(filtered >= forwardOnly);

        // 4. the same filter over the traversers themselves, as the UI issues it
        List<Object> edges = gremlin.execute("g.V('" + hubId + "', '" + hubLabel + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))");
        assertEquals(filtered, edges.size());

        // 5. the subgraph for the visualisation contains the hub and every neighbour
        Subgraph subgraph = gremlin.executeSubgraph(
                "g.V('" + hubId + "', '" + hubLabel + "').bothE()");
        assertEquals(degree, subgraph.edges().size());
        assertEquals(1 + stats.neighbours(hubEntityId).size(), subgraph.vertices().size());
        assertTrue(subgraph.vertices().stream().anyMatch(v -> v.id().equals(hubId)));

        System.out.println("[kg] hub vertex id      : " + hubId + "   (label " + hubLabel + ")");
        System.out.println("[kg] hub query          : g.V('" + hubId + "', '" + hubLabel + "').bothE()");
        System.out.println("[kg] relation query     : g.V('" + hubId + "', '" + hubLabel + "').bothE()"
                + ".filter(relation.equals('" + hubRelation + "') || xrelation.equals('" + hubRelation + "'))");
        System.out.println("[kg] hub degree         : " + degree + " (" + stats.neighbours(hubEntityId).size()
                + " distinct neighbours), relation '" + hubRelation + "' edges " + matchesRelation
                + " + reverse " + matchesReverse);

        // a right-sized neighbourhood for the visualiser (the hub is a stress test)
        int demoEntityId = stats.demoEntityId();
        String demoName = dataset.entityName(demoEntityId);
        String demoId = KnowledgeGraphIds.composeId(demoName);
        String demoLabel = KnowledgeGraphIds.vertexLabel(demoName);
        long demoEdges = (long) (Number) gremlin.execute(
                "g.V('" + demoId + "', '" + demoLabel + "').bothE().count()").get(0);
        Subgraph demo = gremlin.executeSubgraph("g.V('" + demoId + "', '" + demoLabel + "').bothE()");
        assertEquals(demoEdges, demo.edges().size());
        assertTrue(demo.vertices().size() > 1);
        System.out.println("[kg] demo vertex id     : " + demoId + "   (label " + demoLabel + ")");
        System.out.println("[kg] demo name          : " + demoName);
        System.out.println("[kg] demo query         : g.V('" + demoId + "', '" + demoLabel + "').bothE()");
        System.out.println("[kg] demo subgraph      : " + demo.vertices().size() + " vertices, "
                + demo.edges().size() + " edges");
    }

    // --- in-memory expectations computed from the same dataset sample ---

    /** Degree / relation statistics of the seeded sample. */
    private record Stats(Map<Integer, Integer> degree, Map<Integer, Map<Integer, Integer>> incident,
                         Map<Integer, java.util.Set<Integer>> neighbours, int[] inverse, int hubEntityId,
                         int hubRelationId, int demoEntityId) {

        static Stats of(KnowledgeGraphDataset dataset, List<KnowledgeGraphDataset.Triple> triples) {
            Map<Integer, Integer> degree = new HashMap<>();
            Map<Integer, Map<Integer, Integer>> incident = new HashMap<>();
            Map<Integer, java.util.Set<Integer>> neighbours = new HashMap<>();
            for (KnowledgeGraphDataset.Triple triple : triples) {
                degree.merge(triple.headId(), 1, Integer::sum);
                degree.merge(triple.tailId(), 1, Integer::sum);
                incident.computeIfAbsent(triple.headId(), k -> new HashMap<>())
                        .merge(triple.relationId(), 1, Integer::sum);
                incident.computeIfAbsent(triple.tailId(), k -> new HashMap<>())
                        .merge(triple.relationId(), 1, Integer::sum);
                neighbours.computeIfAbsent(triple.headId(), k -> new java.util.HashSet<>()).add(triple.tailId());
                neighbours.computeIfAbsent(triple.tailId(), k -> new java.util.HashSet<>()).add(triple.headId());
            }
            int hub = degree.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow(() -> new IllegalStateException("no triples sampled"))
                    .getKey();
            int hubRelation = incident.get(hub).entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow()
                    .getKey();
            // a vertex whose neighbourhood renders nicely (a few dozen nodes)
            int demo = degree.entrySet().stream()
                    .filter(e -> e.getValue() >= 15 && e.getValue() <= 80)
                    .min(java.util.Comparator.comparingInt(e -> Math.abs(e.getValue() - 40)))
                    .map(Map.Entry::getKey)
                    .orElse(hub);
            return new Stats(degree, incident, neighbours, dataset.inverseRelations(), hub, hubRelation, demo);
        }

        int degree(int entityId) {
            return degree.getOrDefault(entityId, 0);
        }

        int incidentWithRelation(int entityId, int relationId) {
            return incident.getOrDefault(entityId, Map.of()).getOrDefault(relationId, 0);
        }

        int inverseRelationId(int relationId) {
            return inverse[relationId];
        }

        java.util.Set<Integer> neighbours(int entityId) {
            return neighbours.getOrDefault(entityId, java.util.Set.of());
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
