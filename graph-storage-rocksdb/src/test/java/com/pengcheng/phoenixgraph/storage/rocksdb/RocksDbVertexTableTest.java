package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RocksDB key layout, region mapping, property codec and the vertex table with its
 * per-property version rule.
 */
class RocksDbVertexTableTest {

    @TempDir
    Path directory;

    private RocksDbHandle handle;
    private RocksDbVertexTable table;
    private VertexSchema account;

    @BeforeEach
    void setUp() {
        account = new VertexSchema(1, "Account", "an account")
                .property(new VertexPropertySchema(1, "accountStatus", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "balance", null, PropertyType.ScalarType.LONG))
                .property(new VertexPropertySchema(3, "tags", null, new PropertyType.ListType(PropertyType.ScalarType.STRING)))
                .property(new VertexPropertySchema(4, "limits", null, new PropertyType.MapType(PropertyType.ScalarType.STRING)));
        GraphSchema schema = new GraphSchema(1, "rocksdb-test").vertex(account);
        RocksDbStoreConfig config = RocksDbStoreConfig.plain(directory.resolve("db").toString());
        handle = RocksDbHandle.open(config, List.of(RocksDbVertexTable.familyOf(account)));
        table = new RocksDbVertexTable(handle, schema, config, new KeyLocks(), null);
    }

    @AfterEach
    void tearDown() {
        if (handle != null) {
            handle.close();
        }
    }

    // --- key layout ---

    @Test
    void vertexKeyStartsWithTheFixedWidthHashAndCarriesTheId() {
        byte[] key = RocksDbKeys.vertexKey("account-42");
        assertEquals(RocksDbHash.HASH_BYTES + 1 + "account-42".length(), key.length);
        assertEquals(RocksDbHash.hash("account-42"), RocksDbHash.readLong(key, 0));
        assertEquals(':', key[RocksDbHash.HASH_BYTES]);
        assertEquals("account-42", RocksDbBytes.text(java.util.Arrays.copyOfRange(
                key, RocksDbHash.HASH_BYTES + 1, key.length)));
    }

    @Test
    void keysOfTheSameRegionSortTogether() {
        // the hash prefix is what sorts a key into its region, so two ids of one region
        // share their first bytes while two different regions do not
        String first = null;
        String sameRegion = null;
        for (int i = 0; i < 10_000 && sameRegion == null; i++) {
            String candidate = "id-" + i;
            if (first == null) {
                first = candidate;
            } else if (handle.regionOf(candidate) == handle.regionOf(first)) {
                sameRegion = candidate;
            }
        }
        assertTrue(sameRegion != null, "expected two ids in the same region");
        assertEquals(handle.regionOf(first), handle.regionOf(sameRegion));
        assertTrue(handle.regionOf(first) < handle.regionCount());
        assertTrue(handle.regionOf(first) >= 0);
    }

    @Test
    void hashIsStableAndWellSpread() {
        assertEquals(RocksDbHash.hash("135"), RocksDbHash.hash("135"));
        assertNotEquals(RocksDbHash.hash("135"), RocksDbHash.hash("136"));
        boolean[] seen = new boolean[512];
        int distinct = 0;
        for (int i = 0; i < 5_000; i++) {
            int region = RocksDbHash.regionOf("account-" + i, 512);
            if (!seen[region]) {
                seen[region] = true;
                distinct++;
            }
        }
        assertEquals(512, distinct, "5000 keys should cover every region");
    }

    @Test
    void edgeKeyLayoutIsPrefixScannableAndTimeOrdered() {
        byte[] prefix = RocksDbKeys.edgePrefix("1000", RocksDbKeys.DIRECTION_OUT);
        byte[] early = RocksDbKeys.edgeKey("1000", RocksDbKeys.DIRECTION_OUT, 1_000L, "e1");
        byte[] late = RocksDbKeys.edgeKey("1000", RocksDbKeys.DIRECTION_OUT, 2_000L, "e2");
        assertTrue(RocksDbKeys.hasPrefix(early, prefix));
        assertTrue(RocksDbKeys.hasPrefix(late, prefix));
        assertEquals(1_000L, RocksDbKeys.createdTimeOf(early, prefix));
        assertEquals("e1", RocksDbKeys.edgeIdOf(early, prefix));
        // fixed-width big-endian time keeps lexicographic order equal to time order
        assertTrue(java.util.Arrays.compareUnsigned(early, late) < 0);
        // a time window starts at a seek position inside the prefix range
        byte[] from = RocksDbKeys.edgeKeyFrom(prefix, 1_500L);
        assertTrue(java.util.Arrays.compareUnsigned(from, early) > 0);
        assertTrue(java.util.Arrays.compareUnsigned(from, late) < 0);
        // the prefix range ends right after the prefix
        byte[] end = RocksDbKeys.prefixEnd(prefix);
        assertTrue(java.util.Arrays.compareUnsigned(late, end) < 0);
    }

    // --- property codec ---

    @Test
    void propertyValuesRoundTrip() {
        List<Object> values = List.of("ACTIVE", 42, 9876543210L, 1.5d, 2.5f, true, (Object) List.of("a", "b"),
                Map.of("k", "v"));
        for (Object value : values) {
            assertEquals(value, RocksDbPropertyCodec.decode(RocksDbPropertyCodec.encode(value)));
        }
        assertNull(RocksDbPropertyCodec.decode(RocksDbPropertyCodec.encode(null)));
    }

    // --- vertex table ---

    @Test
    void upsertReadsBackWithProjection() {
        long accountId = 135L;
        assertEquals(WriteOutcome.SUCCESS, table.upsert(new RocksDbVertexUpsert(accountId, account,
                Map.of(1, new RocksDbPropertyValue("ACTIVE", 100L),
                        2, new RocksDbPropertyValue(5_000L, 100L),
                        3, new RocksDbPropertyValue(List.of("personal", "business"), 100L)),
                1_000L, 100L)));

        RocksDbStoredVertex all = table.read("Account", accountId, List.of());
        assertEquals("ACTIVE", all.value(1));
        assertEquals(5_000L, all.value(2));
        assertEquals(List.of("personal", "business"), all.value(3));
        assertEquals(1_000L, all.createdTime());
        assertEquals(100L, all.lastUpdatedTime());

        // on-demand properties: only what was asked for is decoded
        RocksDbStoredVertex projected = table.read("Account", accountId, List.of(2));
        assertEquals(Map.of(2, new RocksDbPropertyValue(5_000L, 100L)), projected.properties());

        assertNull(table.read("Account", 987_654_321L, List.of()));
    }

    @Test
    void newerVersionWinsAndOlderIsIgnored() {
        table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("ACTIVE", 100L)), 10L, 100L));

        // newer write replaces the property and lifts lastUpdatedTime
        assertEquals(WriteOutcome.SUCCESS, table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("FROZEN", 200L)), 10L, 200L)));
        RocksDbStoredVertex after = table.read("Account", 1L, List.of());
        assertEquals("FROZEN", after.value(1));
        assertEquals(200L, after.properties().get(1).version());
        assertEquals(200L, after.lastUpdatedTime());
        assertEquals(10L, after.createdTime(), "createdTime is not rewritten");

        // an older write cannot undo a newer one
        assertEquals(WriteOutcome.CONFLICT, table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("CLOSED", 150L)), 10L, 150L)));
        assertEquals("FROZEN", table.read("Account", 1L, List.of()).value(1));
    }

    @Test
    void partialUpdateKeepsUntouchedProperties() {
        table.upsert(new RocksDbVertexUpsert(7L, account,
                Map.of(1, new RocksDbPropertyValue("ACTIVE", 100L),
                        2, new RocksDbPropertyValue(10L, 100L)), 1L, 100L));
        table.upsert(new RocksDbVertexUpsert(7L, account,
                Map.of(2, new RocksDbPropertyValue(20L, 300L)), 1L, 300L));

        RocksDbStoredVertex vertex = table.read("Account", 7L, List.of());
        assertEquals("ACTIVE", vertex.value(1));
        assertEquals(20L, vertex.value(2));
        assertEquals(300L, vertex.lastUpdatedTime());
    }

    @Test
    void batchedUpsertsAndBatchedReads() {
        List<RocksDbVertexUpsert> upserts = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            upserts.add(new RocksDbVertexUpsert((long) i, account,
                    Map.of(1, new RocksDbPropertyValue("status-" + i, 10L + i)), 1L, 10L + i));
        }
        List<WriteOutcome> outcomes = table.upsertAll(upserts);
        assertEquals(250, outcomes.size());
        assertTrue(outcomes.stream().allMatch(outcome -> outcome == WriteOutcome.SUCCESS));
        assertEquals(250L, table.count("Account"));

        List<Object> ids = List.of(0L, 5L, 249L, 9_999L);
        List<RocksDbStoredVertex> read = table.readAll("Account", ids, List.of(1));
        assertEquals(4, read.size());
        assertEquals("status-0", read.get(0).value(1));
        assertEquals("status-249", read.get(2).value(1));
        assertNull(read.get(3), "an absent vertex reads as null");
    }

    @Test
    void versionHistoryIsKeptWhenConfigured() {
        RocksDbStoreConfig config = RocksDbStoreConfig.plain(directory.resolve("db-versions").toString())
                .withMaxVersions(3);
        try (RocksDbHandle versioned = RocksDbHandle.open(config, List.of(RocksDbVertexTable.familyOf(account)))) {
            RocksDbVertexTable table = new RocksDbVertexTable(versioned, new GraphSchema(1, "v").vertex(account),
                    config, new KeyLocks(), null);
            table.upsert(new RocksDbVertexUpsert(1L, account,
                    Map.of(1, new RocksDbPropertyValue("A", 100L)), 1L, 100L));
            table.upsert(new RocksDbVertexUpsert(1L, account,
                    Map.of(1, new RocksDbPropertyValue("B", 200L)), 1L, 200L));
            table.upsert(new RocksDbVertexUpsert(1L, account,
                    Map.of(1, new RocksDbPropertyValue("C", 300L)), 1L, 300L));

            List<RocksDbPropertyValue> versions = table.versions("Account", 1L, 1);
            assertEquals(3, versions.size());
            assertEquals("C", versions.get(0).value());
            assertEquals("B", versions.get(1).value());
            assertEquals("A", versions.get(2).value());
            assertEquals("C", table.read("Account", 1L, List.of()).value(1));
        }
    }

    @Test
    void singleVersionIsTheDefault() {
        table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("A", 100L)), 1L, 100L));
        table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("B", 200L)), 1L, 200L));
        assertEquals(1, table.versions("Account", 1L, 1).size());
    }

    @Test
    void deleteRemovesTheRecord() {
        table.upsert(new RocksDbVertexUpsert(1L, account,
                Map.of(1, new RocksDbPropertyValue("A", 100L)), 1L, 100L));
        assertTrue(table.delete("Account", 1L));
        assertNull(table.read("Account", 1L, List.of()));
        assertTrue(!table.delete("Account", 1L));
    }

    @Test
    void scanningListsIdsAndVertices() {
        for (int i = 0; i < 20; i++) {
            table.upsert(new RocksDbVertexUpsert("acct-" + i, account,
                    Map.of(1, new RocksDbPropertyValue("s" + i, 5L)), 1L, 5L));
        }
        assertEquals(20, table.ids("Account").size());
        List<String> scanned = new java.util.ArrayList<>();
        table.forEach("Account", List.of(1), vertex -> scanned.add(String.valueOf(vertex.value(1))));
        assertEquals(20, scanned.size());
        assertTrue(scanned.contains("s7"));
    }
}
