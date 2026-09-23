package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metrics classification: which reads count as edge reads and which as vertex reads.
 *
 * <p>The bins decide whenever they name data, and the set name only decides a whole-record read. These
 * cases are the ones a name-only rule gets wrong: a vertex set is free to be called anything (including
 * something that starts with "edge"), and an edge-bin read can travel with the {@code vt} type bin.
 */
class AerospikeReadKindTest {

    private static final String NAMESPACE = "test";
    private static final String VERTEX_SET = "http_demo_v3";

    private static Key key(String set) {
        return new Key(NAMESPACE, set, "1001");
    }

    @Test
    void aReadOfVertexBinsIsAVertexReadWhateverTheSetIsCalled() {
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"vp_6"}));
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"vpt_6", "vp_6", "vt"}));
        // the meta created/last-updated bins are vertex bins as well ("vp_" + their index)
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET),
                new String[] {AerospikeVertexStore.CREATED_TIME_BIN,
                        AerospikeVertexStore.LAST_UPDATED_TIME_BIN}));
        // a vertex set named like an edge set is still a vertex set when vertex bins are read
        assertFalse(AerospikeReadKind.isEdge(key("edge_store_v1"), new String[] {"vp_1"}));
        assertFalse(AerospikeReadKind.isEdge(key("edge_volume"), new String[] {"vpt_1"}));
    }

    @Test
    void aReadOfEdgeBinsIsAnEdgeReadEvenWithAMetaBinAlong() {
        assertTrue(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"ve_3_2"}));
        assertTrue(AerospikeReadKind.isEdge(key("payment_as_edge_v1"), new String[] {"ve_3_0", "ve_3_1"}));
        // the type bin travels with a neighbour read; the edge bin still decides
        assertTrue(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"ve_3_2", "vt"}));
    }

    @Test
    void aVertexPropertyWinsOverAnEdgeBinInTheSameRead() {
        // a read that asks for both is a vertex read: it materialises the vertex, not just its edges
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"ve_3_2", "vp_6"}));
    }

    @Test
    void aWholeRecordReadFallsBackToTheSetName() {
        assertTrue(AerospikeReadKind.isEdge(key("edges"), null));
        assertTrue(AerospikeReadKind.isEdge(key("edge_source_index"), new String[0]));
        assertTrue(AerospikeReadKind.isEdge(key("payment_as_edge_v1_edgeheads"), null));
        assertTrue(AerospikeReadKind.isEdge(key("payment_as_edge_v1_edge_out_3"), null));
        assertTrue(AerospikeReadKind.isEdge(key("payment_as_edge_v1_edge_nearline"), null));
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), null));
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"vt"}));
        assertFalse(AerospikeReadKind.isEdge(null, null));
    }

    @Test
    void theSetNameAloneCannotTellAnEdgeLookingVertexSetApartSoTheBinsDo() {
        // the aggregated schemes' index sets are named "edge_*", so a whole-record read of a vertex set
        // that happens to be named the same way is the one case the name cannot settle - which is why
        // the bins decide whenever the read names data at all
        assertTrue(AerospikeReadKind.isEdgeSet("edge_store_v1"));
        assertFalse(AerospikeReadKind.isEdge(key("edge_store_v1"), new String[] {"vp_1"}));
        assertTrue(AerospikeReadKind.isEdge(key("edge_store_v1"), new String[] {"ve_1_0"}));
        assertFalse(AerospikeReadKind.isEdgeSet("edgecases"));
        assertTrue(AerospikeReadKind.isEdgeSet("edges"));
        assertTrue(AerospikeReadKind.isEdgeSet("edge_destination_index"));
        assertFalse(AerospikeReadKind.isEdgeSet(null));
    }

    @Test
    void binPrefixesAreRecognised() {
        assertTrue(AerospikeReadKind.isEdgeBin("ve_3_2"));
        assertFalse(AerospikeReadKind.isEdgeBin("vp_3"));
        assertFalse(AerospikeReadKind.isEdgeBin("vt"));
        assertFalse(AerospikeReadKind.isEdgeBin(null));
        assertTrue(AerospikeReadKind.isVertexBin("vp_3"));
        assertTrue(AerospikeReadKind.isVertexBin("vpt_3"));
        assertFalse(AerospikeReadKind.isVertexBin("ve_3_2"));
        assertFalse(AerospikeReadKind.isVertexBin("vt"));
        assertFalse(AerospikeReadKind.isVertexBin(null));
    }

    @Test
    void theSetsAndBinsTheDemoDataUsesAreClassifiedTheWayTheTrafficSays() {
        // the neighbour read of the inline scheme: one edge bin per direction
        assertTrue(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"ve_3_0"}));
        assertTrue(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"ve_3_1"}));
        // a projected vertex read of the same set: the three properties plus the meta bins
        assertFalse(AerospikeReadKind.isEdge(key(VERTEX_SET), new String[] {"vp_4", "vpt_4", "vp_6", "vpt_6",
                "vp_1", "vpt_1", "vt",
                AerospikeVertexStore.CREATED_TIME_BIN, AerospikeVertexStore.LAST_UPDATED_TIME_BIN}));
        assertEquals(AerospikeEdgeStore.EDGE_BIN_PREFIX, "ve_");
        assertEquals(AerospikeVertexStore.PROPERTY_BIN_PREFIX, "vp_");
        assertEquals(AerospikeVertexStore.PROPERTY_TIME_BIN_PREFIX, "vpt_");
    }
}
