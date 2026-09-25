package com.pengcheng.phoenixgraph.integration;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeEdgeGraphQuery;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunkStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeChunks;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearline;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeNearlineStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVolumeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.EdgeTiering;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The big-vertex read cases of the Aerospike-Edge scheme, against the demo of
 * {@link AerospikeBigVertexDemoLoader} and through the Gremlin engine - so every assertion here is a
 * query a client can paste into the UI.
 *
 * <p>The cases are the six of the spec: a big vertex refuses a read without a window; a window
 * inside the online span reads the inline tail; a window over the last day merges near-line and
 * online; a window inside the cold span reads the aggregated tables alone; a window over all three
 * spans merges all three; and a merged read keeps the newest copy of an edge. Plus the two
 * boundaries: a vertex under the cap needs no window, and the outgoing side tiers the same way.
 *
 * <p>The test writes into its own vertex set ({@code payment_as_edge_bv_it} unless
 * {@code AEROSPIKE_BIG_VERTEX_SET} says otherwise) and truncates what it writes first, so the counts
 * are exact: an edge id keeps the creation time it was first written with, and re-running the demo on
 * a set that already has it would move every edge out of a narrow window. The demo the UI serves is
 * written by {@link AerospikeBigVertexDemoTest} into
 * {@link AerospikeBigVertexDemoLoader#VERTEX_SET} instead.
 *
 * <p>Skipped when no Aerospike is reachable.
 */
class AerospikeEdgeBigVertexIntegrationTest {

    /** The windows the loader wrote, and how many edges each holds. */
    private static final String UPDATED = AerospikeBigVertexDemoLoader.UPDATED_NEARLINE_EDGE;
    private static final String UPDATED_ONLINE = AerospikeBigVertexDemoLoader.UPDATED_ONLINE_EDGE;

    private static AerospikeClient client;
    private static String namespace;
    private static String vertexSet;
    private static GraphSchema schema;
    private static AerospikeBigVertexDemoLoader.LoadResult demo;
    private static GremlinGraphQuery gremlin;

    @BeforeAll
    static void loadTheDemo() {
        client = connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping the big-vertex integration test");
        namespace = env("AEROSPIKE_NAMESPACE", "test");
        vertexSet = env("AEROSPIKE_BIG_VERTEX_SET", "payment_as_edge_bv_it");
        schema = AerospikeEdgeLiveLoadTest.AerospikeEdgeIntegrationTestAccess.loadSchema();
        truncateDemoSets();

        long started = System.currentTimeMillis();
        demo = AerospikeBigVertexDemoLoader.load(client, namespace, vertexSet, schema);
        System.out.printf("loaded the big-vertex demo into %s/%s in %d ms: %d vertices, %s%n",
                namespace, vertexSet, System.currentTimeMillis() - started, demo.vertices(),
                demo.edgesPerVertex());
        System.out.printf("  spans: cold [%d, %d], nearline [%d, %d], online [%d, now]%n",
                demo.coldFrom(), demo.coldTo(), demo.nearlineFrom(), demo.nearlineTo(),
                demo.onlineFrom());
        System.out.printf("  nearline partitions a day (declared volume %d): %d%n",
                demo.declaredMonthlyVolume(), demo.nearlinePartitions());

        AerospikeEdgeChunkStore chunks = new AerospikeEdgeChunkStore(client, namespace, vertexSet);
        AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, namespace, vertexSet);
        AerospikeEdgeGraphQuery query = new AerospikeEdgeGraphQuery(
                new AerospikeGraphQuery(new AerospikeVertexStore(client, namespace, vertexSet),
                        new AerospikeEdgeStore(client, namespace, vertexSet, schema), schema),
                chunks, schema, nearline, Clock.systemUTC(),
                EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS);
        gremlin = new GremlinGraphQuery(query, NeighborVertexResolver.from(schema));
    }

    /**
     * Clears every set the demo writes (see {@link AerospikeBigVertexDemoLoader#truncate}).
     */
    private static void truncateDemoSets() {
        AerospikeBigVertexDemoLoader.truncate(client, namespace, vertexSet, schema);
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    // --- 1. a big vertex needs a window ---

    @Test
    void aBigVertexRefusesAReadWithoutAWindow() {
        for (String hub : List.of(AerospikeBigVertexDemoLoader.HUB_ALL,
                AerospikeBigVertexDemoLoader.HUB_NEW, AerospikeBigVertexDemoLoader.HUB_COLD)) {
            QueryException error = assertThrows(QueryException.class,
                    () -> run("g.V('" + hub + "','Account').inE('Payment')"),
                    hub + " is big, so a window is required");
            assertTrue(error.getMessage().contains("creation-time window"), error.getMessage());
            assertTrue(error.getMessage().contains("createdBetween"), error.getMessage());
        }
        QueryException outgoing = assertThrows(QueryException.class,
                () -> run("g.V('" + AerospikeBigVertexDemoLoader.HUB_OUT
                        + "','Account').outE('Payment')"));
        assertTrue(outgoing.getMessage().contains("creation-time window"), outgoing.getMessage());
    }

    @Test
    void aVertexUnderTheCapNeedsNoWindow() {
        List<Object> ids = run("g.V('" + AerospikeBigVertexDemoLoader.BELOW_CAP
                + "','Account').inE('Payment').values('id')");
        assertEquals(AerospikeBigVertexDemoLoader.ONLINE_EDGES, ids.size(),
                "a vertex under the cap reads its inline bin directly");
        assertFalse(ids.isEmpty());
    }

    // --- 2. online alone, 3. near-line + online, 4. cold alone, 5/6. all three, newest copy wins ---

    @Test
    void aWindowInsideTheOnlineSpanReadsTheInlineTail() {
        List<Object> ids = run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in", demo.onlineFrom(), null));
        assertTrue(ids.size() == AerospikeBigVertexDemoLoader.ONLINE_EDGES + 1,
                "the online tier holds the live tail plus the updated copy: " + ids);
        assertTrue(ids.stream().allMatch(id -> String.valueOf(id).startsWith("bv_all_o_")
                        || String.valueOf(id).equals(UPDATED_ONLINE)),
                "only inline edges: " + ids);
        System.out.println("  online window -> " + ids.size() + " edges: " + ids);
    }

    @Test
    void aWindowOverTheLastDayMergesNearlineAndOnline() {
        List<Object> ids = run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in",
                demo.now() - AerospikeBigVertexDemoLoader.NEARLINE_FROM_AGO, null));
        Set<Object> distinct = new LinkedHashSet<>(ids);
        assertEquals(ids.size(), distinct.size(), "no edge is reported twice: " + duplicates(ids));
        long nearlineIds = ids.stream().filter(id -> String.valueOf(id).startsWith("bv_all_n_")).count();
        long onlineIds = ids.stream().filter(id -> String.valueOf(id).startsWith("bv_all_o_")).count();
        List<Object> other = ids.stream().filter(id -> !String.valueOf(id).startsWith("bv_all_n_")
                && !String.valueOf(id).startsWith("bv_all_o_")).toList();
        System.out.println("  composition: total=" + ids.size() + " nearline-prefixed=" + nearlineIds
                + " online-prefixed=" + onlineIds + " other=" + other);
        assertTrue(ids.size() == AerospikeBigVertexDemoLoader.NEARLINE_EDGES + 1
                        + AerospikeBigVertexDemoLoader.ONLINE_EDGES, "near-line (with the updated copy)"
                        + " + online, with " + UPDATED_ONLINE + " counted once: " + ids.size());
        assertTrue(ids.stream().anyMatch(id -> String.valueOf(id).startsWith("bv_all_n_")),
                "near-line edges");
        assertTrue(ids.stream().anyMatch(id -> String.valueOf(id).startsWith("bv_all_o_")
                        || String.valueOf(id).equals(UPDATED_ONLINE)), "inline edges");
        assertTrue(ids.contains(UPDATED), "the near-line copy of the cold edge is in the window");
        assertTrue(ids.stream().noneMatch(id -> String.valueOf(id).startsWith("bv_all_c_1")),
                "no older cold edge is in the window");
        System.out.println("  near-line + online window -> " + ids.size() + " edges");
    }

    @Test
    void aWindowInsideTheColdSpanReadsTheColdTablesAlone() {
        long from = demo.coldFrom();
        long to = from + 30L * AerospikeBigVertexDemoLoader.DAY;
        List<Object> ids = run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in", from, to));
        assertTrue(ids.size() > 100, "a month of a year of cold edges: " + ids.size());
        assertTrue(ids.stream().allMatch(id -> String.valueOf(id).startsWith("bv_all_c_")),
                "only cold edges: " + ids);
        System.out.println("  cold-only window -> " + ids.size() + " edges");
    }

    @Test
    void aWindowOverAllThreeSpansMergesThemAndKeepsTheNewestCopy() {
        List<String> ids = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in",
                demo.coldFrom(), null)));
        Set<String> distinct = new LinkedHashSet<>(ids);
        assertEquals(ids.size(), distinct.size(), "no edge is reported twice: " + duplicates(ids));

        long cold = ids.stream().filter(id -> id.startsWith("bv_all_c_")).count();
        long nearline = ids.stream().filter(id -> id.startsWith("bv_all_n_")).count();
        long online = ids.stream().filter(id -> id.startsWith("bv_all_o_")).count();
        assertTrue(cold > 5000 && nearline > 800 && online > 0,
                "all three tiers are in the answer: cold=" + cold + " nearline=" + nearline
                        + " online=" + online);

        // the newest copy wins: the status of the two doubly-written ids comes from the newer tier
        List<String> statuses = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in",
                demo.coldFrom(), null).replace("values('id')", "values('status')")));
        assertEquals("REFUNDED", statuses.get(ids.indexOf(UPDATED)),
                "the near-line copy of " + UPDATED + " is newer than the cold one");
        assertEquals("REFUNDED", statuses.get(ids.indexOf(UPDATED_ONLINE)),
                "the inline copy of " + UPDATED_ONLINE + " is newer than the near-line one");

        // the same edge read in the cold window alone still shows the cold copy
        List<String> coldStatuses = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_ALL, "in",
                demo.coldFrom(), demo.coldFrom() + 30L * AerospikeBigVertexDemoLoader.DAY)
                .replace("values('id')", "values('status')")));
        assertTrue(coldStatuses.contains("SUCCESS"), "the cold copy of the updated edge is SUCCESS");
        System.out.println("  all-three window -> " + ids.size() + " edges (cold=" + cold
                + ", nearline=" + nearline + ", online=" + online + ")");
    }

    @Test
    void aBigVertexWithNothingColdReadsNearlineAndOnline() {
        List<String> ids = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_NEW, "in",
                demo.coldFrom(), null)));
        assertTrue(ids.stream().noneMatch(id -> id.startsWith("bv_new_c_")),
                "this vertex has no cold edges");
        assertTrue(ids.stream().anyMatch(id -> id.startsWith("bv_new_n_")), "near-line edges");
        assertTrue(ids.stream().anyMatch(id -> id.startsWith("bv_new_o_")), "inline edges");
        System.out.println("  near-line-only hub -> " + ids.size() + " edges");
    }

    @Test
    void aQuietBigVertexIsAllCold() {
        List<String> ids = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_COLD, "in",
                demo.coldFrom(), null)));
        assertTrue(ids.stream().allMatch(id -> id.startsWith("bv_cold_c_")),
                "everything is older than the near-line window: " + ids.size());
        // and the near-line window alone finds nothing for it
        List<Object> recent = run(hub(AerospikeBigVertexDemoLoader.HUB_COLD, "in",
                demo.now() - AerospikeBigVertexDemoLoader.NEARLINE_FROM_AGO, null));
        assertTrue(recent.isEmpty(), "nothing in the last day: " + recent);
        System.out.println("  cold-only hub -> " + ids.size() + " edges, " + recent.size()
                + " in the last day");
    }

    // --- 7. the outgoing side tiers the same way ---

    @Test
    void theOutgoingSideHasTheSameTiers() {
        List<String> all = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_OUT, "out",
                demo.coldFrom(), null)));
        assertTrue(all.stream().anyMatch(id -> id.startsWith("bv_out_c_")), "cold outbound edges");
        assertTrue(all.stream().anyMatch(id -> id.startsWith("bv_out_n_")), "near-line outbound edges");
        assertTrue(all.stream().anyMatch(id -> id.startsWith("bv_out_o_")), "inline outbound edges");

        long from = demo.coldFrom();
        List<String> coldOnly = strings(run(hub(AerospikeBigVertexDemoLoader.HUB_OUT, "out",
                from, from + 30L * AerospikeBigVertexDemoLoader.DAY)));
        assertTrue(coldOnly.stream().allMatch(id -> id.startsWith("bv_out_c_")),
                "a cold window on the outgoing side reads the cold tables alone");
        System.out.println("  outgoing side -> " + all.size() + " edges over all three tiers");
    }

    // --- the near-line layout the spec describes ---

    @Test
    void theNearlineDayIsCutFromTheDeclaredVolume() {
        // 1e8 a month / 30 days / 4000 edges a KV is about 833, the spec's "say 1000"
        assertEquals(834, AerospikeNearline.partitionCount(AerospikeBigVertexDemoLoader
                .DECLARED_MONTHLY_VOLUME));
        assertEquals(AerospikeBigVertexDemoLoader.expectedPartitions(), demo.nearlinePartitions());

        AerospikeVolumeStore volume = new AerospikeVolumeStore(client, namespace, vertexSet);
        long monthly = volume.monthlyVolume(schema.edgeSchema("Payment").id(),
                AerospikeEdgeStore.EDGE_DIR_IN, AerospikeBigVertexDemoLoader.HUB_ALL,
                AerospikeVolumeStore.monthBin(demo.now()).substring(2));
        assertEquals(AerospikeBigVertexDemoLoader.DECLARED_MONTHLY_VOLUME, monthly,
                "the volume record the pre-partitioning is computed from");

        // the index record of the near-line day addresses exactly that many data key-values
        AerospikeNearlineStore nearline = new AerospikeNearlineStore(client, namespace, vertexSet);
        int partitions = nearline.prePartition(AerospikeBigVertexDemoLoader.HUB_ALL,
                schema.edgeSchema("Payment").id(), AerospikeEdgeStore.EDGE_DIR_IN,
                AerospikeNearline.dayOf(demo.now()), demo.nearlinePartitions());
        assertEquals(834, partitions);
        // and the tier really answers from there
        assertTrue(nearline.hasData(AerospikeBigVertexDemoLoader.HUB_ALL,
                schema.edgeSchema("Payment").id(), AerospikeEdgeStore.EDGE_DIR_IN, demo.now()));
    }

    /** A tier classification sanity check, independent of the queries above. */
    @Test
    void theWindowIsClassifiedByTheSpansItOverlaps() {
        long now = demo.now();
        long window = EdgeTiering.DEFAULT_NEARLINE_WINDOW_MILLIS;
        long onlineEarliest = demo.onlineFrom();

        assertEquals(Set.of(EdgeTiering.Tier.ONLINE, EdgeTiering.Tier.NEARLINE),
                EdgeTiering.classify(TimeRange.of(now - 2 * AerospikeBigVertexDemoLoader.HOUR, now),
                        now, window, onlineEarliest));
        assertEquals(Set.of(EdgeTiering.Tier.COLD),
                EdgeTiering.classify(TimeRange.of(demo.coldFrom(), demo.coldFrom() + 1000), now,
                        window, onlineEarliest));
        assertEquals(Set.of(EdgeTiering.Tier.COLD, EdgeTiering.Tier.NEARLINE, EdgeTiering.Tier.ONLINE),
                EdgeTiering.classify(TimeRange.of(demo.coldFrom(), Long.MAX_VALUE), now, window,
                        onlineEarliest));
    }

    // --- helpers ---

    /** A hub query over one direction and window: {@code g.V(hub,'Account').createdBetween(from,to).XXE('Payment').values('id')}. */
    private static String hub(String hub, String direction, long from, Long to) {
        String step = direction.equals("out") ? "outE" : "inE";
        return "g.V('" + hub + "','Account').createdBetween('" + from + "','"
                + (to == null ? "*" : String.valueOf(to)) + "')." + step + "('Payment').values('id')";
    }

    private static List<Object> run(String query) {
        return gremlin.execute(query);
    }

    private static List<String> strings(List<Object> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static Set<Object> duplicates(List<?> ids) {
        Set<Object> seen = new LinkedHashSet<>();
        Set<Object> twice = new LinkedHashSet<>();
        for (Object id : ids) {
            if (!seen.add(id)) {
                twice.add(id);
            }
        }
        return twice;
    }

    private static AerospikeClient connectOrNull() {
        try {
            return new AerospikeClient(env("AEROSPIKE_HOST", "localhost"),
                    Integer.parseInt(env("AEROSPIKE_PORT", "3000")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
