package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.Key;

/**
 * Whether a record read is of edges or of vertices, for the metrics.
 *
 * <p>The <b>bins</b> decide, because they name the data that was asked for: an edge lives in a
 * {@code ve_*} bin, and a vertex property - including the created/last-updated meta bins - in a
 * {@code vp_*}/{@code vpt_*} bin. A read that asks for a vertex property is therefore a vertex read
 * whatever the set is called, and a read that asks for edge bins is an edge read even when a meta bin
 * such as {@code vt} travels with them.
 *
 * <p>The <b>set name</b> only decides a whole-record read (no bins at all), where it is the only thing
 * left to look at: the aggregated schemes keep their edges in sets of their own
 * ({@code edges}, {@code edge_source_index}, {@code <set>_edge_out_<id>}, {@code <set>_edgeheads}).
 * It cannot decide on its own, because a vertex set may be named anything - including a name that
 * happens to start with {@code edge}.
 */
final class AerospikeReadKind {

    private AerospikeReadKind() {
    }

    /** The set names the aggregated schemes use for edges (see {@link AerospikeEdgeStore}). */
    private static final String EDGE_SET = "edges";
    private static final String EDGE_SET_PREFIX = "edge_";
    private static final String EDGE_HEADS_SUFFIX = "_edgeheads";
    private static final String EDGE_INSIDE = "_edge_";

    /**
     * Whether the read of {@code key} asking for {@code bins} is of edges.
     *
     * @param key  the record that was read, or {@code null} when the read is not keyed
     * @param bins the bins asked for, or {@code null}/empty for a whole-record read
     */
    static boolean isEdge(Key key, String[] bins) {
        if (bins != null && bins.length > 0) {
            boolean edges = false;
            for (String bin : bins) {
                if (isVertexBin(bin)) {
                    return false; // a vertex property was asked for: this is a vertex read
                }
                edges |= isEdgeBin(bin);
            }
            if (edges) {
                return true;
            }
        }
        return isEdgeSet(key == null ? null : key.setName);
    }

    /** Whether a bin holds an edge. */
    static boolean isEdgeBin(String bin) {
        return bin != null && bin.startsWith(AerospikeEdgeStore.EDGE_BIN_PREFIX);
    }

    /** Whether a bin holds a vertex property (or a vertex meta time). */
    static boolean isVertexBin(String bin) {
        return bin != null && (bin.startsWith(AerospikeVertexStore.PROPERTY_BIN_PREFIX)
                || bin.startsWith(AerospikeVertexStore.PROPERTY_TIME_BIN_PREFIX));
    }

    /** Whether a set of the aggregated schemes holds edges. */
    static boolean isEdgeSet(String set) {
        if (set == null) {
            return false;
        }
        return set.equals(EDGE_SET) || set.startsWith(EDGE_SET_PREFIX) || set.endsWith(EDGE_HEADS_SUFFIX)
                || set.contains(EDGE_INSIDE);
    }
}
