package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.ScanPolicy;
import com.pengcheng.phoenixgraph.metrics.GraphMetrics;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two-hop projection benchmark on the <b>Aerospike</b> payment graph: a hundred account ids
 * ({@code http_demo_v3} holds more than that), eight rounds each, 800 executions.
 *
 * <p>The measurement and the report live in {@link TwoHopValuesBenchmark}; this class is the storage: the
 * vertex/edge stores over the Aerospike container, and the account ids read out of the set with a scan.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AerospikeHundredSeedsValuesBenchmarkTest extends TwoHopValuesBenchmark {

    /** The ids the demo data is built around, tried before the rest of the set. */
    private static final List<String> NAMED = List.of("135", "1001", "2017", "9000001", "150", "11");

    private AerospikeClient client;
    private String namespace;
    private String set;
    private GraphSchema schema;

    @Override
    protected int seedTarget() {
        return 100;
    }

    @Override
    protected int rounds() {
        return 8;
    }

    @Override
    protected String label() {
        connect();
        return "payment-graph (Aerospike, " + set + ")";
    }

    @Override
    protected GraphSchema schema() {
        connect();
        if (schema == null) {
            schema = schemaFrom("payment-graph.json");
        }
        return schema;
    }

    @Override
    protected GraphQuery openStore(GraphMetrics metrics) {
        connect();
        AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, namespace, set).metrics(metrics);
        AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, namespace, set, schema())
                .metrics(metrics);
        return new AerospikeGraphQuery(vertexStore, edgeStore, schema());
    }

    @Override
    protected void closeStore() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected List<String> namedFirst() {
        return NAMED;
    }

    /**
     * The account ids of the set. A scan hands records back in whatever order the server pleases, so they
     * are sorted: the measured workload has to be the same one run to run, or two reports cannot be compared.
     */
    @Override
    protected List<String> candidateIds() {
        connect();
        TreeSet<String> scanned = new TreeSet<>();
        client.scanAll(new ScanPolicy(), namespace, set, (key, record) -> {
            if (key != null && key.userKey != null && record != null
                    && "Account".equals(record.getString(AerospikeVertexStore.VERTEX_TYPE_BIN))) {
                scanned.add(String.valueOf(key.userKey));
            }
        }, AerospikeVertexStore.VERTEX_TYPE_BIN);
        return List.copyOf(scanned);
    }

    private void connect() {
        if (client != null) {
            return;
        }
        IntegrationConfig config = IntegrationConfig.fromEnv();
        namespace = config.namespace();
        client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        set = System.getProperty("payment.vertexSet",
                System.getenv().getOrDefault("PAYMENT_VERTEX_SET", "http_demo_v3"));
    }
}
