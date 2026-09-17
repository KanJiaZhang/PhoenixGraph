package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code storage} block of a graph schema: the Aerospike default, the RocksDB schemes,
 * JSON round-trips and the validation of their settings.
 */
class GraphStorageSchemaTest {

    private static VertexSchema account() {
        return new VertexSchema(1, "Account", "an account")
                .property(new VertexPropertySchema(1, "accountStatus", null, PropertyType.ScalarType.STRING));
    }

    private static EdgeSchema payment(int maxOut, int maxIn) {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED, maxOut, maxIn, 0);
    }

    @Test
    void aerospikeIsTheDefault() {
        GraphSchema schema = new GraphSchema(1, "payment-graph").vertex(account()).edge(payment(0, 0));
        assertEquals(GraphStorageType.AEROSPIKE, schema.storage().type());
        assertTrue(schema.storage().isAerospike());
        assertFalse(schema.storage().isRocksDb());

        // and it stays the default through a JSON round trip
        GraphSchema parsed = new SchemaJsonCodec().fromJson(new SchemaJsonCodec().toJson(schema));
        assertEquals(GraphStorageType.AEROSPIKE, parsed.storage().type());
    }

    @Test
    void storageTypeNamesAreMatchedLoosely() {
        assertEquals(GraphStorageType.ROCKSDB_INLINE, GraphStorageType.fromConfigName("RocksDB-Inline"));
        assertEquals(GraphStorageType.ROCKSDB_INLINE, GraphStorageType.fromConfigName("rocksdb_inline"));
        assertEquals(GraphStorageType.ROCKSDB_INLINE, GraphStorageType.fromConfigName(" ROCKSDB-INLINE "));
        assertEquals(GraphStorageType.ROCKSDB_EDGE, GraphStorageType.fromConfigName("RocksDB-Edge"));
        assertEquals(GraphStorageType.AEROSPIKE, GraphStorageType.fromConfigName("Aerospike"));
        assertEquals(GraphStorageType.AEROSPIKE, GraphStorageType.fromConfigName(null));
        assertThrows(IllegalArgumentException.class, () -> GraphStorageType.fromConfigName("HBase"));
    }

    @Test
    void rocksDbBlockSurvivesAJsonRoundTrip() {
        GraphSchema schema = new GraphSchema(1, "payment-inline").vertex(account()).edge(payment(0, 0))
                .storage(GraphStorageSchema.rocksDbInline("/data/payment-inline")
                        .withRegionCount(1024).withMaxVersions(3).withCacheEdges(false));

        String json = new SchemaJsonCodec().toJson(schema);
        assertTrue(json.contains("\"type\" : \"RocksDB-Inline\""), json);
        assertTrue(json.contains("\"path\" : \"/data/payment-inline\""), json);
        assertTrue(json.contains("\"regionCount\" : 1024"), json);

        GraphSchema parsed = new SchemaJsonCodec().fromJson(json);
        assertEquals(GraphStorageType.ROCKSDB_INLINE, parsed.storage().type());
        assertEquals("/data/payment-inline", parsed.storage().path());
        assertEquals(1024, parsed.storage().regionCount());
        assertEquals(3, parsed.storage().maxVersions());
        assertFalse(parsed.storage().cacheEdges());
    }

    @Test
    void theAerospikeEdgeVariantSurvivesAJsonRoundTrip() {
        GraphSchema schema = new GraphSchema(1, "as-edge").vertex(account())
                .storage(GraphStorageSchema.aerospikeEdge());
        String json = new SchemaJsonCodec().toJson(schema);
        assertTrue(json.contains("\"type\" : \"Aerospike-Edge\""), json);

        GraphSchema parsed = new SchemaJsonCodec().fromJson(json);
        assertEquals(GraphStorageType.AEROSPIKE_EDGE, parsed.storage().type());
        assertTrue(parsed.storage().isAerospike());
        assertTrue(parsed.storage().isAerospikeEdge());
        assertFalse(parsed.storage().isRocksDb());
        // it needs no path: the aggregated tables live in the Aerospike set of the deployment
        assertEquals(null, parsed.storage().path());
    }

    @Test
    void rocksDbEdgeBlockDefaultsItsSettings() {
        String json = """
                {
                  "id": 1, "name": "payment-edge",
                  "vertexSchemas": [ { "id": 1, "name": "Account",
                      "idSchema": { "id": 0, "name": "id", "type": "LONG" },
                      "createdTime": { "id": -2, "name": "createdTime", "type": "LONG" },
                      "lastUpdatedTime": { "id": -1, "name": "lastUpdatedTime", "type": "LONG" },
                      "properties": [] } ],
                  "edgeSchemas": [],
                  "storage": { "type": "RocksDB-Edge", "path": "/data/payment-edge" }
                }
                """;
        GraphSchema parsed = new SchemaJsonCodec().fromJson(json);
        assertEquals(GraphStorageType.ROCKSDB_EDGE, parsed.storage().type());
        assertEquals(512, parsed.storage().regionCount());
        assertEquals(1, parsed.storage().maxVersions());
        assertTrue(parsed.storage().cacheEdges());
    }

    @Test
    void rocksDbWithoutAPathIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> GraphStorageSchema.rocksDbInline(" "));
        String json = """
                { "id": 1, "name": "broken", "vertexSchemas": [], "edgeSchemas": [],
                  "storage": { "type": "RocksDB-Inline" } }
                """;
        assertThrows(IllegalArgumentException.class, () -> new SchemaJsonCodec().fromJson(json));
    }

    @Test
    void validatorChecksTheStorageBlock() {
        SchemaValidator validator = new SchemaValidator();
        assertTrue(validator.validate(new GraphSchema(1, "ok").vertex(account()).edge(payment(0, 0)), SchemaConstraints.DEFAULTS)
                .isEmpty());

        // RocksDB-Edge ignores degree limits: a warning, not an error
        GraphSchema withLimits = new GraphSchema(1, "limited").vertex(account()).edge(payment(10, 5))
                .storage(GraphStorageSchema.rocksDbEdge("/data/edge"));
        SchemaValidationResult result = validator.validateResult(withLimits, SchemaConstraints.DEFAULTS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no degree limit")),
                result.warnings().toString());

        // the same limits are legal for the inline scheme (they are enforced there)
        GraphSchema inline = new GraphSchema(1, "limited-inline").vertex(account()).edge(payment(10, 5))
                .storage(GraphStorageSchema.rocksDbInline("/data/inline"));
        assertTrue(validator.validateResult(inline, SchemaConstraints.DEFAULTS).warnings().stream()
                .noneMatch(w -> w.contains("no degree limit")));
    }

    @Test
    void rocksDbSettingsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> GraphStorageSchema.rocksDbInline("/data/x").withRegionCount(0));
        assertThrows(IllegalArgumentException.class, () -> GraphStorageSchema.rocksDbInline("/data/x").withMaxVersions(0));
        assertThrows(IllegalArgumentException.class,
                () -> GraphStorageSchema.rocksDb(GraphStorageType.AEROSPIKE, "/data/x", 512, 1, true));
    }

    @Test
    void pathIsOnlyRequiredForRocksDb() {
        GraphSchema aerospike = new GraphSchema(1, "a").vertex(account());
        assertEquals(GraphStorageSchema.AEROSPIKE, aerospike.storage());
        assertThrows(IllegalStateException.class, () -> aerospike.storage().requireRocksDbPath());
        assertEquals("/data/x", GraphStorageSchema.rocksDbEdge("/data/x").requireRocksDbPath());
    }

    @Test
    void storageTypeHelpersDescribeTheSchemes() {
        assertEquals("RocksDB-Inline", GraphStorageType.ROCKSDB_INLINE.configName());
        assertTrue(GraphStorageType.ROCKSDB_EDGE.isRocksDb());
        assertFalse(GraphStorageType.ROCKSDB_EDGE.isAerospike());
        assertEquals(List.of(GraphStorageType.AEROSPIKE, GraphStorageType.AEROSPIKE_EDGE,
                GraphStorageType.ROCKSDB_INLINE, GraphStorageType.ROCKSDB_EDGE),
                List.of(GraphStorageType.values()));
    }
}
