package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout of the aggregated Aerospike edge tables: table names, the
 * {@code vertexId|min|max|level} keys, how a bulk import groups edges into chunks and folds
 * those into the levels above, and how a window picks the children to descend into.
 */
class AerospikeEdgeChunksTest {

    private static EdgeSchema payment() {
        return new EdgeSchema(1, "Payment", "a payment",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.DIRECTED, 0, 0, 0);
    }

    private static EdgeSchema knows() {
        return new EdgeSchema(2, "Knows", "undirected",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new EdgeEndpointSchema(1, "source", null, PropertyType.ScalarType.LONG, "Account"),
                new EdgeEndpointSchema(2, "target", null, PropertyType.ScalarType.LONG, "Account"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.UNDIRECTED);
    }

    private static AerospikeEdgeChunks.EdgeEntry edge(String id, long createdTime) {
        return new AerospikeEdgeChunks.EdgeEntry(id, "other-" + id, createdTime, createdTime,
                List.of(createdTime, createdTime, "other-" + id, java.util.Map.of()));
    }

    @Test
    void tableNamesFollowTheScheme() {
        assertEquals("http_demo_v1_edge_out_1", AerospikeEdgeChunks.outSet("http_demo_v1", payment()));
        assertEquals("http_demo_v1_edge_in_1", AerospikeEdgeChunks.inSet("http_demo_v1", payment()));
        assertEquals("http_demo_v1_edge_2", AerospikeEdgeChunks.bothSet("http_demo_v1", knows()));
        assertEquals("http_demo_v1_edgeheads", AerospikeEdgeChunks.hubSet("http_demo_v1"));

        // a directed edge class has two tables, an undirected one a single table
        assertEquals(List.of("s_edge_out_1", "s_edge_in_1"), AerospikeEdgeChunks.setsOf("s", payment()));
        assertEquals(List.of("s_edge_2"), AerospikeEdgeChunks.setsOf("s", knows()));
        assertEquals("s_edge_out_1", AerospikeEdgeChunks.edgeSet("s", payment(),
                AerospikeEdgeStore.EDGE_DIR_OUT));
        assertEquals("s_edge_2", AerospikeEdgeChunks.edgeSet("s", knows(),
                AerospikeEdgeStore.EDGE_DIR_BOTH));
    }

    @Test
    void keysCarryTheTimeSpanAndTheLevel() {
        String key = AerospikeEdgeChunks.chunkKey("acct-7", 1_000L, 2_000L, 0);
        assertEquals("acct-7|1000|2000|0", key);
        AerospikeEdgeChunks.ParsedKey parsed = AerospikeEdgeChunks.parseKey(key);
        assertEquals("acct-7", parsed.vertexId());
        assertEquals(1_000L, parsed.min());
        assertEquals(2_000L, parsed.max());
        assertEquals(0, parsed.level());
        assertNull(AerospikeEdgeChunks.parseKey("not-a-chunk-key"));

        assertEquals("1|2|acct-7", AerospikeEdgeChunks.hubKey(payment(), 2, "acct-7"));
    }

    @Test
    void aBulkImportBuildsTheLevelsBottomUp() {
        // 9 edges, 2 per chunk: 5 chunks at level 0, 3 at level 1, 2 at level 2, 1 root
        List<AerospikeEdgeChunks.EdgeEntry> edges = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            edges.add(edge("e" + i, 1_000L * i));
        }
        List<AerospikeEdgeChunks.ChunkRecord> records = AerospikeEdgeChunks.build(edges, 2);

        List<AerospikeEdgeChunks.ChunkRecord> level0 = records.stream().filter(r -> r.level() == 0).toList();
        List<AerospikeEdgeChunks.ChunkRecord> level1 = records.stream().filter(r -> r.level() == 1).toList();
        List<AerospikeEdgeChunks.ChunkRecord> level2 = records.stream().filter(r -> r.level() == 2).toList();
        assertEquals(5, level0.size());
        assertEquals(3, level1.size());
        assertEquals(2, level2.size());
        // the levels keep folding until a single root remains: 5 + 3 + 2 + 1 records
        List<AerospikeEdgeChunks.ChunkRecord> level3 = records.stream().filter(r -> r.level() == 3).toList();
        assertEquals(1, level3.size(), "one root");
        assertEquals(1_000L, level3.get(0).min(), "the root spans everything");
        assertEquals(9_000L, level3.get(0).max());
        assertEquals(11, records.size());

        // a level-0 chunk holds the edges of its span, in creation-time order
        assertEquals(2, level0.get(0).edges().size());
        assertEquals("e1", level0.get(0).edges().get(0).edgeId());
        assertEquals(1_000L, level0.get(0).min());
        assertEquals(2_000L, level0.get(0).max());
        assertEquals(1, level0.get(4).edges().size(), "the last chunk holds the odd edge");

        // a parent's span covers its children, and its value holds their (min, max) pairs
        assertEquals(2, level1.get(0).children().size());
        assertEquals(1_000L, level1.get(0).min());
        assertEquals(4_000L, level1.get(0).max());
        assertEquals(2_000L, level1.get(0).children().get(0)[1]);
        assertEquals(9_000L, level2.get(1).max());

        // the keys of the children can be rebuilt from the parent's spans (that is what a query does)
        long[] firstChild = level1.get(0).children().get(0);
        String childKey = AerospikeEdgeChunks.chunkKey("acct", firstChild[0], firstChild[1], 0);
        assertEquals("acct|1000|2000|0", childKey);
    }

    @Test
    void levelsForGrowsWithTheEdgeCount() {
        assertEquals(1, AerospikeEdgeChunks.levelsFor(500, 2_000));
        assertEquals(1, AerospikeEdgeChunks.levelsFor(2_000, 2_000));
        assertEquals(2, AerospikeEdgeChunks.levelsFor(2_001, 2_000));
        // the demo hub: 300k edges of 2000 per chunk is 150 chunks, so two levels
        assertEquals(2, AerospikeEdgeChunks.levelsFor(300_000, 2_000));
        // a third level appears past 2000 * 2000 edges
        assertEquals(3, AerospikeEdgeChunks.levelsFor(4_000_001, 2_000));
    }

    @Test
    void aWindowSelectsTheChildrenToDescendInto() {
        List<long[]> children = List.of(new long[]{1_000L, 2_000L}, new long[]{3_000L, 4_000L},
                new long[]{5_000L, 6_000L});
        assertEquals(3, AerospikeEdgeChunks.selectChildren(children, 0L, 9_000L).size());
        List<long[]> middle = AerospikeEdgeChunks.selectChildren(children, 3_500L, 3_600L);
        assertEquals(1, middle.size());
        assertEquals(3_000L, middle.get(0)[0]);
        assertEquals(4_000L, middle.get(0)[1]);
        // a window that only touches one edge of a chunk still selects that chunk
        assertEquals(1, AerospikeEdgeChunks.selectChildren(children, 2_000L, 2_000L).size());
        assertEquals(2, AerospikeEdgeChunks.selectChildren(children, 2_500L, 5_500L).size());
        assertTrue(AerospikeEdgeChunks.selectChildren(children, 7_000L, 8_000L).isEmpty());
        // the selection is in time order
        List<long[]> selected = AerospikeEdgeChunks.selectChildren(children, 0L, 9_000L);
        assertEquals(1_000L, selected.get(0)[0]);
        assertEquals(5_000L, selected.get(2)[0]);
    }

    @Test
    void anEmptyImportBuildsNothing() {
        assertEquals(List.of(), AerospikeEdgeChunks.build(List.of(), 2_000));
        assertEquals(1, AerospikeEdgeChunks.levelsFor(0, 2_000));
    }

    @Test
    void buildLevelsRebuildsTheSameParentsAsBuild() {
        List<AerospikeEdgeChunks.EdgeEntry> edges = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            edges.add(edge("e" + i, i));
        }
        List<AerospikeEdgeChunks.ChunkRecord> whole = AerospikeEdgeChunks.build(edges, 64);
        List<AerospikeEdgeChunks.ChunkRecord> level0 = whole.stream()
                .filter(record -> record.level() == 0).toList();
        List<AerospikeEdgeChunks.ChunkRecord> parents = AerospikeEdgeChunks.buildLevels(level0, 64);

        // the parents of a rebuilt tree are the parents build produced, level by level
        List<AerospikeEdgeChunks.ChunkRecord> wholeParents = whole.stream()
                .filter(record -> record.level() > 0).toList();
        assertEquals(wholeParents.size(), parents.size());
        for (int i = 0; i < parents.size(); i++) {
            assertEquals(wholeParents.get(i).level(), parents.get(i).level());
            assertEquals(wholeParents.get(i).min(), parents.get(i).min());
            assertEquals(wholeParents.get(i).max(), parents.get(i).max());
        }
    }
}
