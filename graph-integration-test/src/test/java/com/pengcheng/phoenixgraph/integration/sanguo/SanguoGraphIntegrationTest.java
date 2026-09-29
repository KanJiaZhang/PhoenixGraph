package com.pengcheng.phoenixgraph.integration.sanguo;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.integration.IntegrationConfig;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.ErrorLog;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of the new PhoenixGraph instance that holds the 《三国演义》
 * knowledge graph on Aerospike: it seeds {@code SanguoDataset} into the
 * {@code sanguo-kg} layout (one {@code Entity} vertex type, one directed
 * {@code Entity_Entity} relation type, relation-sharded ids, relation name on
 * the edge) and then queries it the way the UI does — plus the example queries
 * the dataset answers.
 *
 * <p>Size is controlled by the {@code AEROSPIKE_VERTEX_SET} (default
 * {@value #DEFAULT_SET}), {@code KG_RESEED=true} forces a re-seed and
 * {@code KG_PARALLELISM} sets the writer thread count. Seeding is skipped when
 * the set already holds the data.
 */
class SanguoGraphIntegrationTest {

    private static final String GRAPH_NAME = "sanguo-kg";
    private static final String DEFAULT_SET = "kg_sanguo_v1";

    /** The person used for the hub queries (the largest fan-out in the graph). */
    private static final String HUB = "曹操";

    /** The relation used for the filter / inverse queries. */
    private static final String RELATION = "lord";

    @Test
    void seedsAndQueriesTheSanguoKnowledgeGraph() {
        AerospikeClient client = new IntegrationConfig("localhost", 3000, "test", "").connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping integration test");
        try (client) {
            String namespace = env("AEROSPIKE_NAMESPACE", "test");
            String set = env("AEROSPIKE_VERTEX_SET", DEFAULT_SET);
            int parallelism = intEnv("KG_PARALLELISM", 8);

            SanguoDataset dataset = SanguoDataset.load();
            GraphSchema schema = loadSchema();

            // silent logs (a seed writes ~10k bins) and more retries: parallel writers race
            // on the per-vertex edge bins, which is resolved by re-writing
            BusinessLog silentBusiness = entry -> {
            };
            ErrorLog silentErrors = entry -> {
            };
            int attempts = 10;
            AerospikeVertexStore vertexStore =
                    new AerospikeVertexStore(client, namespace, set, silentBusiness, silentErrors, attempts);
            AerospikeEdgeStore edgeStore =
                    new AerospikeEdgeStore(client, namespace, set, schema, silentBusiness, silentErrors, attempts);

            seedIfNeeded(vertexStore, edgeStore, schema, dataset, parallelism);

            assertSchemaShape(schema);
            Stats stats = Stats.of(dataset);
            assertIdScheme(dataset, stats);
            assertQueries(dataset, schema, stats, vertexStore, edgeStore);
            printExampleQueries(dataset, stats);
        }
    }

    private void seedIfNeeded(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema,
                              SanguoDataset dataset, int parallelism) {
        boolean forced = Boolean.parseBoolean(env("KG_RESEED", "false"));
        if (!forced && SanguoGraphSeeder.alreadySeeded(vertexStore, dataset)) {
            return;
        }
        long started = System.currentTimeMillis();
        SanguoGraphSeeder.Result result = SanguoGraphSeeder.seed(vertexStore, edgeStore, schema, dataset, parallelism);
        System.out.printf("[sanguo] seeded set: %d vertices, %d edges in %d ms%n",
                result.vertices(), result.edges(), System.currentTimeMillis() - started);
    }

    private void assertSchemaShape(GraphSchema schema) {
        assertEquals(GRAPH_NAME, schema.name());
        assertEquals(1, schema.vertexSchemas().size());
        VertexSchema entity = schema.vertexSchema(SanguoGraphIds.VERTEX_LABEL);
        assertNotNull(entity, "missing the Entity vertex schema");
        assertEquals(List.of("name", "shard", "entityType", "gender", "courtesyName", "historicity",
                        "history", "novel", "alias", "birthYear", "deathYear", "eventYear", "eventYearEnd",
                        "latitude", "longitude"),
                entity.properties().stream().map(p -> p.name()).toList());
        assertEquals(List.of("name"), entity.displayPropertyNames());

        assertEquals(1, schema.edgeSchemas().size());
        EdgeSchema edge = schema.edgeSchema(SanguoGraphIds.EDGE_LABEL);
        assertNotNull(edge, "missing the Entity_Entity edge schema");
        assertEquals(List.of("relation", "xrelation", "relationLabel", "relationFamily",
                        "year", "chapter", "seq", "provenance"),
                edge.properties().stream().map(p -> p.name()).toList(),
                "every relation carries its Chinese label, its family and its time dimension");
        assertEquals(2000, edge.maxOutgoingEdges());
        assertEquals(2000, edge.maxIncomingEdges());
        assertEquals(SanguoGraphIds.VERTEX_LABEL, edge.source().vertexName());
        assertEquals(SanguoGraphIds.VERTEX_LABEL, edge.target().vertexName());
    }

    private void assertIdScheme(SanguoDataset dataset, Stats stats) {
        Set<Integer> shards = new LinkedHashSet<>();
        for (SanguoDataset.Relation relation : dataset.relations()) {
            int shard = SanguoGraphIds.shard(relation.relation());
            assertTrue(shard >= 0 && shard < SanguoGraphIds.SHARD_COUNT, "shard out of range for " + relation.relation());
            // both endpoints of a triple land in the shard of its relation
            assertTrue(stats.vertexIds.contains(SanguoGraphIds.composeId(relation.head(), shard)));
            assertTrue(stats.vertexIds.contains(SanguoGraphIds.composeId(relation.tail(), shard)));
            // the id is reversible: name_shard -> name / shard
            assertEquals(relation.head(), SanguoGraphIds.nameOf(SanguoGraphIds.composeId(relation.head(), shard)));
            assertEquals(shard, SanguoGraphIds.shardOf(SanguoGraphIds.composeId(relation.head(), shard)));
            shards.add(shard);
        }
        assertEquals(9, shards.size(),
                "nine of the ten shards carry relations; shard with no relation is simply empty");
    }

    private void assertQueries(SanguoDataset dataset, GraphSchema schema, Stats stats,
                               AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore) {
        AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
        GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
        CypherGraphQuery cypher = new CypherGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
        String label = SanguoGraphIds.VERTEX_LABEL;

        List<String> hubIds = stats.vertexIdsOf(HUB);
        assertTrue(hubIds.size() > 1, HUB + " must be replicated over several shards: " + hubIds);
        String firstHubId = hubIds.get(0);
        int firstHubShard = SanguoGraphIds.shardOf(firstHubId);

        // 1. the vertex is stored under its composed id, carrying name / shard / type / gender
        StoredVertex stored = vertexStore.readVertex(firstHubId);
        assertNotNull(stored, "vertex " + firstHubId + " must be stored");
        List<Object> values = gremlin.execute("g.V('" + firstHubId + "', '" + label + "').values('name','shard','entityType')");
        assertEquals(HUB, values.get(0));
        assertEquals((long) firstHubShard, ((Number) values.get(1)).longValue());
        assertEquals("Male", values.get(2));

        // 2. the whole person is the union over its shards: every incident edge is reachable
        int totalDegree = stats.degreeOf(HUB);
        String seeds = hubIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        long multiSeed = (long) (Number) gremlin.execute(
                "g.V(" + seeds + ", '" + label + "').batch(20).bothE().count()").get(0);
        assertEquals(totalDegree, multiSeed, "bothE() over every shard of " + HUB);
        assertTrue(totalDegree > 100, HUB + " is the hub of the graph: " + totalDegree);

        // 3. relation filter: relation.equals(R) || xrelation.equals(R) finds R from either end;
        //    the inverse side matters — an edge X --minister--> 曹操 carries xrelation=lord,
        //    so it is exactly the 主公 relation seen from the lord's side
        int matchesRelation = stats.incidentWithRelation(HUB, RELATION);
        String inverseRelation = dataset.ontology().inverse(RELATION);
        int matchesInverse = inverseRelation.equals(RELATION) ? 0 : stats.incidentWithRelation(HUB, inverseRelation);
        int matches = matchesRelation + matchesInverse;
        assertTrue(matches > 0, HUB + " must have " + RELATION + " relations");
        long filtered = (long) (Number) gremlin.execute("g.V(" + seeds + ", '" + label + "').bothE()"
                + ".filter(relation.equals('" + RELATION + "') || xrelation.equals('" + RELATION + "'))"
                + ".count()").get(0);
        assertEquals(matches, filtered, "filtered edge count for relation " + RELATION);

        // 4. the direction is visible: relation.equals(R) alone counts R, not its inverse
        long forwardOnly = (long) (Number) gremlin.execute("g.V(" + seeds + ", '" + label + "').bothE()"
                + ".filter(relation.equals('" + RELATION + "')).count()").get(0);
        assertEquals(matchesRelation, forwardOnly, "forward-only count for " + RELATION);
        assertEquals(2L * matchesRelation, (long) matches,
                "in this dataset every 主公 has the mirrored 臣 edge, so both directions are equal");

        // 5. the 中文 relation name and family travel with the edge
        String lordVertexId = SanguoGraphIds.composeId(HUB, RELATION);
        List<Object> edgeProperties = gremlin.execute("g.V('" + lordVertexId + "', '" + label + "').bothE()"
                + ".filter(relation.equals('" + RELATION + "')).limit(1)"
                + ".values('relation','relationLabel','relationFamily')");
        assertFalseEmpty(edgeProperties);
        assertEquals(RELATION, edgeProperties.get(0));
        assertEquals("主公", edgeProperties.get(1));
        assertEquals("君臣", edgeProperties.get(2));

        // 6. otherV() hops to the neighbour of each surviving edge
        long neighbours = (long) (Number) gremlin.execute("g.V(" + seeds + ", '" + label + "').bothE()"
                + ".filter(relation.equals('" + RELATION + "') || xrelation.equals('" + RELATION + "'))"
                + ".otherV().count()").get(0);
        assertEquals(matches, neighbours, "otherV() yields one vertex per surviving edge");

        // 7. the materialized inverse answers the relation from the other end
        //    诸葛亮 master 姜维  =>  姜维 --apprentice--> 诸葛亮
        String zhugeShard = String.valueOf(SanguoGraphIds.shard("master"));
        String jiangWeiId = SanguoGraphIds.composeId("姜维", "master");
        long inverseHits = (long) (Number) gremlin.execute("g.V('" + jiangWeiId + "', '" + label + "').bothE()"
                + ".filter(xrelation.equals('master') || relation.equals('master')).count()").get(0);
        assertTrue(inverseHits >= 1, "姜维's shard " + zhugeShard + " must hold the apprentice edge to 诸葛亮");
        assertTrue(stats.incidentWithRelation("姜维", "apprentice") >= 1);

        // 8. the subgraph for the visualisation contains the hub's shards and every neighbour
        Subgraph subgraph = gremlin.executeSubgraph("g.V(" + seeds + ", '" + label + "').bothE()");
        assertEquals(totalDegree, subgraph.edges().size());
        Set<String> expectedVertices = new LinkedHashSet<>(hubIds);
        expectedVertices.addAll(stats.neighboursOf(HUB));
        Set<String> actualVertices = subgraph.vertices().stream()
                .map(vertex -> String.valueOf(vertex.id())).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> missing = new LinkedHashSet<>(expectedVertices);
        missing.removeAll(actualVertices);
        Set<String> extra = new LinkedHashSet<>(actualVertices);
        extra.removeAll(expectedVertices);
        assertEquals(expectedVertices.size(), actualVertices.size(),
                "subgraph vertices; missing=" + missing + " extra=" + extra);

        // 9. Cypher on the same graph: 曹操's 主公 relations, with the Chinese label
        String caoCaoShardId = SanguoGraphIds.composeId(HUB, "lord");
        CypherResult result = cypher.execute("MATCH (e:Entity {id:'" + caoCaoShardId + "'})-[r:Entity_Entity]->(x:Entity) "
                + "WHERE r.relation = 'lord' RETURN x.name AS name, r.relationLabel AS relLabel LIMIT 10");
        assertTrue(result.rows().size() > 0, "Cypher must find 曹操's 主公 relations");
        assertTrue(result.rows().stream().allMatch(row -> "主公".equals(row.get("relLabel"))),
                "every row carries the 中文 relation name");

        // 10. every shard of the person was seeded (no shard silently missing a relation)
        for (int shard = 0; shard < SanguoGraphIds.SHARD_COUNT; shard++) {
            String vertexId = SanguoGraphIds.composeId(HUB, shard);
            if (stats.vertexIds.contains(vertexId)) {
                long shardEdges = (long) (Number) gremlin.execute(
                        "g.V('" + vertexId + "', '" + label + "').bothE().count()").get(0);
                assertEquals(stats.degree(vertexId), shardEdges, "degree of " + vertexId);
            }
        }
    }

    /** The queries a user of the dataset would run, printed for the documentation. */
    private void printExampleQueries(SanguoDataset dataset, Stats stats) {
        List<String> ids = stats.vertexIdsOf(HUB);
        String seeds = ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        System.out.println();
        System.out.println("[sanguo] graph          : " + GRAPH_NAME + " (set " + env("AEROSPIKE_VERTEX_SET", DEFAULT_SET) + ")");
        System.out.printf("[sanguo] shape          : %d entities, %d relations (%d materialized), %d RDF triples%n",
                dataset.entities().size(), dataset.relations().size(), dataset.closedRelations().size(),
                SanguoRdf.instanceTriples(dataset).size());
        System.out.println("[sanguo] shard of 主公   : " + SanguoGraphIds.shard("lord") + "  (vertex id " + HUB + "_"
                + SanguoGraphIds.shard("lord") + ")");
        System.out.println("[sanguo] 1 全部关系      : g.V(" + seeds + ", 'Entity').bothE()");
        System.out.println("[sanguo] 2 按关系过滤    : g.V(" + seeds + ", 'Entity').bothE()"
                + ".filter(relation.equals('lord') || xrelation.equals('lord'))");
        System.out.println("[sanguo] 3 跳一跳        : g.V(" + seeds + ", 'Entity').bothE()"
                + ".filter(relation.equals('lord')).otherV().dedup()");
        System.out.println("[sanguo] 4 两跳          : g.V('诸葛亮_3','Entity').bothE()"
                + ".filter(relation.equals('minister') || xrelation.equals('minister')).otherV()");
        System.out.println("[sanguo] 5 按家族聚合    : g.V(" + seeds + ", 'Entity').bothE().groupCount('relationFamily')");
        System.out.println("[sanguo] 6 Cypher        : MATCH (e:Entity {id:'" + HUB + "_"
                + SanguoGraphIds.shard("lord") + "'})-[r:Entity_Entity]->(x:Entity) "
                + "WHERE r.relation = 'lord' RETURN x.name, r.relationLabel LIMIT 10");
        System.out.println();
    }

    private static void assertFalseEmpty(List<Object> values) {
        assertTrue(values.size() >= 3, "expected the edge properties, got " + values);
    }

    // --- in-memory expectations computed from the same dataset ---

    /** Vertex / relation statistics of the seeded graph, keyed by composed vertex id. */
    private record Stats(List<SanguoDataset.Relation> relations, Set<String> vertexIds,
                         Map<String, Integer> degree,
                         Map<String, Map<String, Integer>> incident,
                         Map<String, Set<String>> neighbours,
                         Map<String, String> nameById) {

        static Stats of(SanguoDataset dataset) {
            List<SanguoDataset.Relation> relations = dataset.closedRelations();
            Set<String> vertexIds = new LinkedHashSet<>();
            Map<String, Integer> degree = new HashMap<>();
            Map<String, Map<String, Integer>> incident = new HashMap<>();
            Map<String, Set<String>> neighbours = new HashMap<>();
            Map<String, String> nameById = new HashMap<>();
            for (SanguoDataset.Relation relation : relations) {
                String head = SanguoGraphIds.composeId(relation.head(), relation.relation());
                String tail = SanguoGraphIds.composeId(relation.tail(), relation.relation());
                vertexIds.add(head);
                vertexIds.add(tail);
                nameById.put(head, relation.head());
                nameById.put(tail, relation.tail());
                for (String vertex : List.of(head, tail)) {
                    degree.merge(vertex, 1, Integer::sum);
                    incident.computeIfAbsent(vertex, k -> new HashMap<>())
                            .merge(relation.relation(), 1, Integer::sum);
                }
                neighbours.computeIfAbsent(head, k -> new LinkedHashSet<>()).add(tail);
                neighbours.computeIfAbsent(tail, k -> new LinkedHashSet<>()).add(head);
            }
            return new Stats(relations, vertexIds, degree, incident, neighbours, nameById);
        }

        /** Every vertex id of one entity name (one per relation shard it takes part in). */
        List<String> vertexIdsOf(String name) {
            return vertexIds.stream()
                    .filter(id -> name.equals(nameById.get(id)))
                    .sorted(Comparator.comparingInt(SanguoGraphIds::shardOf))
                    .toList();
        }

        int degree(String vertexId) {
            return degree.getOrDefault(vertexId, 0);
        }

        int degreeOf(String name) {
            return vertexIdsOf(name).stream().mapToInt(this::degree).sum();
        }

        int incidentWithRelation(String name, String relation) {
            return vertexIdsOf(name).stream()
                    .mapToInt(id -> incident.getOrDefault(id, Map.of()).getOrDefault(relation, 0))
                    .sum();
        }

        Set<String> neighboursOf(String name) {
            return vertexIdsOf(name).stream()
                    .flatMap(id -> neighbours.getOrDefault(id, Set.of()).stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static GraphSchema loadSchema() {
        try (InputStream in = SanguoGraphIntegrationTest.class.getResourceAsStream("/sanguo-kg.json")) {
            assertNotNull(in, "Missing sanguo-kg.json on the test classpath");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load sanguo-kg.json", e);
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
