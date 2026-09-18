package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.dto.QueryResponse;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsRegistry;
import com.pengcheng.phoenixgraph.metrics.MetricsSnapshot;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph query is recorded with its language, its caller, its latency and — the number
 * that explains the cost — how many key-value reads it turned into.
 */
class QueryMetricsTest {

    /** A store that answers one vertex with one outgoing edge, counting what it was asked. */
    private static final class CountingGraphQuery implements GraphQuery {
        private final GraphMetrics metrics;
        private final List<String> calls = new ArrayList<>();

        CountingGraphQuery(GraphMetrics metrics) {
            this.metrics = metrics;
        }

        /** A store counts both the logical read and the key-value round trip behind it. */
        private void countRead(boolean edge, int items) {
            metrics.increment(edge ? Metrics.EDGE_READ : Metrics.VERTEX_READ);
            metrics.increment(Metrics.items(edge ? Metrics.EDGE_READ : Metrics.VERTEX_READ), items);
            metrics.increment(Metrics.KV_READ_CALL);
            metrics.increment(Metrics.KV_READ_ITEMS, items);
        }

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            countRead(false, 1);
            calls.add("readVertex " + vertexId);
            return new VertexQueryResult(vertexName, vertexId, Map.of(), 1L, 2L);
        }

        @Override
        public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                    List<String> propertyNames) {
            countRead(false, vertexIds.size());
            calls.add("readVertices " + vertexIds.size());
            List<VertexQueryResult> out = new ArrayList<>();
            for (Object id : vertexIds) {
                out.add(new VertexQueryResult(vertexName, id, Map.of(), 1L, 2L));
            }
            return out;
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            countRead(true, 1);
            calls.add("readEdges " + vertexId + " " + direction);
            return List.of(new EdgeQueryResult(edgeName, "e_" + vertexId, direction, "2000", Map.of(), 1L, 2L));
        }

        @Override
        public List<List<EdgeQueryResult>> readEdgesBatch(String edgeName, EdgeSide direction,
                                                          List<Object> vertexIds, List<String> propertyNames) {
            countRead(true, vertexIds.size());
            calls.add("readEdgesBatch " + vertexIds.size());
            return List.of();
        }

        @Override
        public List<EdgeQueryResult> readEdgesWithin(Object vertexId, String vertexName, String edgeName,
                                                     EdgeSide direction, TimeRange range,
                                                     List<String> propertyNames) {
            countRead(true, 1);
            return List.of();
        }
    }

    private static GraphSchema schema() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "cnCode", "Country", PropertyType.ScalarType.STRING));
        EdgeSchema payment = new EdgeSchema(1, "Payment", "A payment from a sender to a receiver",
                new EdgeEndpointSchema(1, "source", "Sender vertex id", PropertyType.ScalarType.LONG,
                        "Account"),
                new EdgeEndpointSchema(2, "target", "Receiver vertex id", PropertyType.ScalarType.LONG,
                        "Account"));
        return new GraphSchema(99, "metrics-test").vertex(account).edge(payment);
    }

    private static QueryService service(MetricsRegistry registry, GraphQuery query) {
        GraphBinding binding = GraphBinding.of(schema(), query);
        return new QueryService(new GraphRegistry(List.of(binding)), null, registry);
    }

    @Test
    void aQueryIsCountedWithItsLanguageSourceLatencyAndKeyValueTraffic() {
        MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO);
        QueryService service = service(registry, new CountingGraphQuery(registry.forGraph("metrics-test")));

        Object result = service.query("metrics-test", "g.V('1000','Account').outE('Payment')", Map.of(),
                "gremlin", true, false, Metrics.SOURCE_UI);

        assertTrue(result instanceof Subgraph, "the subgraph comes back as before");
        MetricsSnapshot snapshot = registry.snapshot("metrics-test");
        assertEquals(1, snapshot.value("query.count{language=gremlin,source=ui}"));
        assertEquals(1, snapshot.latency(Metrics.queryLatency("gremlin")).count());
        assertTrue(snapshot.value(Metrics.QUERY_VERTEX_READ) >= 1, "the seed read is attributed");
        assertTrue(snapshot.value(Metrics.QUERY_EDGE_READ) >= 1, "the edge read is attributed");
        assertTrue(snapshot.value(Metrics.QUERY_KV_READ) >= 2, "translated key-value reads are counted");
        assertTrue(snapshot.value(Metrics.QUERY_ITEMS) >= 1, "the answer's elements are counted");
    }

    @Test
    void theSourceSeparatesTheUiFromOtherCallers() {
        MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO);
        QueryService service = service(registry, new CountingGraphQuery(registry.forGraph("metrics-test")));

        service.query("metrics-test", "g.V('1','Account')", Map.of(), "gremlin", false, false,
                Metrics.SOURCE_UI);
        service.query("metrics-test", "g.V('1','Account')", Map.of(), "gremlin", false, false,
                Metrics.SOURCE_UI);
        service.query("metrics-test", "g.V('1','Account')", Map.of(), "gremlin", false, false, null);

        MetricsSnapshot snapshot = registry.snapshot("metrics-test");
        assertEquals(2, snapshot.value("query.count{language=gremlin,source=ui}"));
        assertEquals(1, snapshot.value("query.count{language=gremlin,source=http}"),
                "a request that does not say who it is counts as http");
    }

    @Test
    void aFailedQueryIsCountedAsFailedAndStillTimed() {
        MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO);
        QueryService service = service(registry, new CountingGraphQuery(registry.forGraph("metrics-test")));

        try {
            service.query("metrics-test", "g.V('1','Account').nope()", Map.of(), "gremlin", false, false,
                    Metrics.SOURCE_UI);
        } catch (RuntimeException expected) {
            // the engine rejects the unknown step
        }

        MetricsSnapshot snapshot = registry.snapshot("metrics-test");
        assertEquals(1, snapshot.value("query.failed{language=gremlin,source=ui}"));
        assertEquals(1, snapshot.value("query.count{language=gremlin,source=ui}"),
                "a failed query still counts as a query");
        assertEquals(1, snapshot.latency(Metrics.queryLatency("gremlin")).count());
    }

    @Test
    void withoutARegistryQueriesAreNotMeasuredButStillRun() {
        CountingGraphQuery query = new CountingGraphQuery(GraphMetrics.noop());
        QueryService service = service(null, query);

        service.query("metrics-test", "g.V('1','Account').outE('Payment')", Map.of(), "gremlin", true, true);

        assertTrue(!query.calls.isEmpty(), "the query ran");
    }

    @Test
    void theResponseCarriesBothTheAnswerAndTheSubgraphWhenAsked() {
        MetricsRegistry registry = new MetricsRegistry(null, Duration.ZERO);
        QueryService service = service(registry, new CountingGraphQuery(registry.forGraph("metrics-test")));

        Object result = service.query("metrics-test", "g.V('1','Account').outE('Payment')", Map.of(),
                "gremlin", true, true, Metrics.SOURCE_UI);

        assertTrue(result instanceof QueryResponse, "includeResult keeps working");
    }
}
