package com.pengcheng.phoenixgraph.integration.sanguo;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.integration.IntegrationConfig;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs every query of {@link SanguoExampleQueries} against the seeded
 * {@code sanguo-kg} graph, so the documented examples cannot rot: the query
 * strings in the UI and in the documentation are the ones this test executes.
 *
 * <p>Requires the data to be seeded first — `SanguoGraphIntegrationTest` does
 * that, and this test skips (rather than fails) when Aerospike or the set is
 * missing.
 */
class SanguoExampleQueriesTest {

    @Test
    void everyDocumentedQueryAnswersSomething() {
        AerospikeClient client = new IntegrationConfig("localhost", 3000, "test", "").connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping the example-query test");
        try (client) {
            GraphSchema schema = loadSchema();
            AerospikeVertexStore vertexStore =
                    new AerospikeVertexStore(client, "test", "kg_sanguo_v1", entry -> {
                    }, entry -> {
                    }, 10);
            AerospikeEdgeStore edgeStore =
                    new AerospikeEdgeStore(client, "test", "kg_sanguo_v1", schema, entry -> {
                    }, entry -> {
                    }, 10);
            assumeTrue(vertexStore.readVertex("曹操_3") != null,
                    "the sanguo-kg set is not seeded; run SanguoGraphIntegrationTest first");

            AerospikeGraphQuery graphQuery = new AerospikeGraphQuery(vertexStore, edgeStore, schema);
            GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery, NeighborVertexResolver.from(schema));
            CypherGraphQuery cypher = new CypherGraphQuery(graphQuery, NeighborVertexResolver.from(schema));

            for (SanguoExampleQueries.Example example : SanguoExampleQueries.GREMLIN) {
                List<Object> rows = gremlin.execute(example.query());
                System.out.printf("[gremlin] %-28s -> %s%n", example.title(), summarise(rows));
                if (example.expectRows()) {
                    assertFalse(rows.isEmpty(), "gremlin example returned nothing: " + example.title());
                }
            }
            for (SanguoExampleQueries.Example example : SanguoExampleQueries.CYPHER) {
                CypherResult result = cypher.execute(example.query());
                System.out.printf("[cypher ] %-28s -> %d row(s) %s%n", example.title(), result.rows().size(),
                        result.rows().isEmpty() ? "" : result.rows().get(0));
                if (example.expectRows()) {
                    assertTrue(!result.rows().isEmpty(), "cypher example returned nothing: " + example.title());
                }
            }

            // the same catalogue, as the Markdown table the documentation uses
            SanguoRdf.write(Path.of("target", "sanguo-rdf", "EXAMPLE-QUERIES.md"),
                    "# sanguo-kg example queries\n\n" + SanguoExampleQueries.toMarkdown());
        }
    }

    private static String summarise(List<Object> rows) {
        if (rows.isEmpty()) {
            return "0 row(s)";
        }
        Object first = rows.get(0);
        return rows.size() + " row(s), first=" + (first instanceof List<?> ? first : String.valueOf(first));
    }

    private static GraphSchema loadSchema() {
        try (InputStream in = SanguoExampleQueriesTest.class.getResourceAsStream("/sanguo-kg.json")) {
            assertNotNull(in, "Missing sanguo-kg.json on the test classpath");
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load sanguo-kg.json", e);
        }
    }
}
