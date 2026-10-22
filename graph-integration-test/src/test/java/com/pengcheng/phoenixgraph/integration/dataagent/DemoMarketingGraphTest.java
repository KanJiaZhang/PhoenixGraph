package com.pengcheng.phoenixgraph.integration.dataagent;

import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The marketing demo of the semantic layer: the second graph that proves the runner, the interpreter and the
 * renderer are not hardcoded to TPC-H. Each test reads a fact the semantic layer must expose.
 */
class DemoMarketingGraphTest {

    @Test
    void answersTheMetricQuestions(@TempDir Path directory) {
        GraphSchema schema = DemoMarketingGraph.loadSchema(DemoMarketingGraph.SEMANTIC_GRAPH);
        Path path = directory.resolve(schema.storage().requireRocksDbPath());
        DemoMarketingGraph.loadSemantic(directory.toString(), true);

        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery g = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema));

            // a base metric carries its fact-graph property on the node
            List<Object> spend = g.execute("g.V('metric/spend','Node').values('graphProperty','aggFunction','unit')");
            assertEquals("spend", spend.get(0));
            assertEquals("SUM", spend.get(1));
            assertEquals("元", spend.get(2));

            // a ratio metric binds nowhere, so the runner must refuse it rather than guess
            List<Object> cacExpression = g.execute("g.V('metric/cac','Node').values('expression')");
            assertEquals("SUM(spend) / SUM(new_customers)", cacExpression.get(0));
            List<Object> cacBinding = g.execute("g.V('metric/cac','Node').values('graphProperty')");
            assertTrue(cacBinding.isEmpty() || cacBinding.stream().allMatch(v -> v == null || String.valueOf(v).isBlank()),
                    "cac has no graphProperty, so the runner refuses and hands back the SQL instead of guessing");

            // the date dimension binds a year grain, so the year filter can find it
            List<Object> date = g.execute("g.V('dim/date','Node').values('graphProperty')");
            assertEquals("year=year,month=month", date.get(0));

            // a dimension slices by one warehouse column
            List<Object> channel = g.execute("g.V('dim/channel','Node').outE()"
                    + ".filter(relation.equals('dimensionColumn')).otherV().values('name')");
            assertEquals(List.of("column/marketing.channel"), channel);
        }
    }

    /** Loads the instance the service serves, so the UI and the verification scripts can use the demo. */
    @Test
    void loadsTheInstanceTheServiceServes() {
        String root = System.getenv().getOrDefault("DEMO_ROCKSDB_ROOT", "../.rocksdb");
        boolean reseed = Boolean.parseBoolean(System.getenv().getOrDefault("DEMO_ROCKSDB_RESEED", "false"));
        if (!reseed && DemoMarketingGraph.isLoaded(root)) {
            System.out.printf("[demo] %s / %s already loaded under %s "
                            + "- set DEMO_ROCKSDB_RESEED=true (service stopped) to rebuild%n",
                    DemoMarketingGraph.SEMANTIC_GRAPH, DemoMarketingGraph.DATA_GRAPH, root);
            return;
        }
        DemoMarketingGraph.loadAll(root, reseed);
        List<DemoMarketingGraph.Fact> facts = DemoMarketingGraph.facts();
        double totalSpend = facts.stream().mapToDouble(DemoMarketingGraph.Fact::spend).sum();
        System.out.printf("[demo] %d rows, total spend %.2f, so a real number backs every answer%n",
                facts.size(), totalSpend);
        assertTrue(facts.size() >= 1000, "the marketing demo has enough rows to group by four dimensions");
    }
}
