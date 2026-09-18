package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the "smart" payment graph into the live stores: the Aerospike instance (where the HNSW
 * vector index lives) and a RocksDB-Inline directory (where the S2 geo index is a real index).
 *
 * <p>Run it explicitly, because it writes data:
 * <pre>
 * ./mvnw -q -pl graph-integration-test test -Dtest=SmartPaymentGraphLiveLoadTest \
 *        -Dsmart.set=payment_smart_v1 -Dsmart.rocksdb.dir=.rocksdb \
 *        -Drocksdb.live.dir=.rocksdb -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Afterwards the vector index of {@code payment-graph-smart} has to be built once, either by the
 * service ({@code POST /api/v1/vector-index/rebuild}) or by the HNSW loader.
 */
class SmartPaymentGraphLiveLoadTest {

    /** Loads into Aerospike and into the RocksDB root given by {@code -Drocksdb.live.dir}. */
    @Test
    void loadTheSmartGraph() {
        String rocksdbRoot = System.getProperty("rocksdb.live.dir");
        assumeTrue(rocksdbRoot != null && !rocksdbRoot.isBlank(),
                "set -Drocksdb.live.dir=<directory> to load the smart graph");

        IntegrationConfig config = IntegrationConfig.fromEnv();
        var client = config.connectOrNull();
        assumeTrue(client != null, "Aerospike not available at " + config.host() + ":" + config.port());
        client.close();

        String set = System.getProperty("smart.set", "payment_smart_v1");
        GraphSchema aerospike = SmartPaymentGraphLoader.loadSchema("/payment-graph-smart.json");
        GraphSchema rocks = SmartPaymentGraphLoader.loadSchema("/payment-graph-smart-rocksdb.json");

        long started = System.currentTimeMillis();
        SmartPaymentGraphLoader.loadAerospike(config.host(), config.port(), config.namespace(), set, aerospike);
        long aerospikeMillis = System.currentTimeMillis() - started;

        String path = Path.of(rocksdbRoot).resolve(rocks.storage().path()).toString();
        started = System.currentTimeMillis();
        int written = SmartPaymentGraphLoader.loadRocksDb(rocks, path, true);
        long rocksMillis = System.currentTimeMillis() - started;

        System.out.printf("smart payment graph loaded: %s%n", SmartPaymentGraphLoader.summary());
        System.out.printf("  Aerospike set %s (%d ms)%n", set, aerospikeMillis);
        System.out.printf("  RocksDB-Inline %s: %d records (%d ms)%n", path, written, rocksMillis);
    }
}
