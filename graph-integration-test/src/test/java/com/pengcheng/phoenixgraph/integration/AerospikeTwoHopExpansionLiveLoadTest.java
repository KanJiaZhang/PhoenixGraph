package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the 5000-edge two-hop expansion of account 150 into a vertex set and runs the query the
 * user asked about, so the batch of data exists outside the benchmark.
 *
 * <pre>
 * ./mvnw -pl graph-integration-test -am test -Dtest=AerospikeTwoHopExpansionLiveLoadTest \
 *        -Dtwohop.vertexSet=payment_twohop_exp -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>After this, the graph can be wired to {@code payment_twohop_exp} and the query
 * {@code g.V('150','Account').both('Payment').both('Payment')} reaches 5000 second-hop edges.
 */
class AerospikeTwoHopExpansionLiveLoadTest {

    @Test
    void loadTheFiveThousandEdgeExpansionAndQueryIt() {
        String set = System.getProperty("twohop.vertexSet", "payment_twohop_exp");
        IntegrationConfig config = IntegrationConfig.fromEnv();
        AerospikeClient client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        try (client) {
            GraphSchema schema = AerospikeTwoHopExpansion.loadSchema();
            long start = System.currentTimeMillis();
            AerospikeTwoHopExpansion.LoadResult result =
                    AerospikeTwoHopExpansion.load(client, config.namespace(), set, schema, 50, 99);
            System.out.printf("loaded %s: %d vertices, %d payment edges, both-reach %d (%d ms)%n",
                    result.vertexSet(), result.vertices(), result.paymentEdges(), result.bothReach(),
                    System.currentTimeMillis() - start);
            assertEquals(5000, result.bothReach());

            GremlinGraphQuery gremlin = new GremlinGraphQuery(
                    new AerospikeGraphQuery(new AerospikeVertexStore(client, config.namespace(), set),
                            new AerospikeEdgeStore(client, config.namespace(), set, schema), schema),
                    NeighborVertexResolver.from(schema));
            List<Object> both = gremlin.execute("g.V('150','Account').both('Payment').both('Payment')");
            System.out.printf("g.V('150','Account').both('Payment').both('Payment') -> %d edges%n",
                    both.size());
            assertEquals(5000, both.size(), "the two-hop reach of 150");

            long distinct = both.stream().map(Object::toString).distinct().count();
            System.out.printf("distinct reached accounts: %d%n", distinct);
            assertEquals(100, distinct, "99 second-hop accounts plus the hub itself");
        }
    }
}
