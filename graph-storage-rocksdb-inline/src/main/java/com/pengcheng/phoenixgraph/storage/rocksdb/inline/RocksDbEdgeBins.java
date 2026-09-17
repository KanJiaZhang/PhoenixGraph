package com.pengcheng.phoenixgraph.storage.rocksdb.inline;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;

/**
 * The bin names of the inline edge layout.
 *
 * <p>The names are the ones the Aerospike store uses ({@code ve_<edgeSchemaId>_<direction>})
 * on purpose: the same schema describes the same graph on either backend, and an operator
 * reading a RocksDB dump next to an Aerospike dump sees the same column names.
 *
 * <p>The direction codes are the Aerospike ones - {@code 0} undirected, {@code 1} incoming,
 * {@code 2} outgoing. Note that the RocksDB-Edge scheme numbers directions the other way
 * round ({@code 1} outgoing, {@code 2} incoming); each scheme keeps its own convention
 * because both are part of their on-disk contract.
 */
public final class RocksDbEdgeBins {

    /** Prefix of an edge bin name. */
    public static final String PREFIX = "ve_";

    /** Undirected/bidirectional edge bin. */
    public static final int DIR_BOTH = 0;

    /** Incoming edge bin (Aerospike uses 1 for IN). */
    public static final int DIR_IN = 1;

    /** Outgoing edge bin (Aerospike uses 2 for OUT). */
    public static final int DIR_OUT = 2;

    private RocksDbEdgeBins() {
    }

    /** The bin name of an edge class and direction: {@code ve_<edgeSchemaId>_<direction>}. */
    public static String bin(int edgeSchemaId, int directionCode) {
        return PREFIX + edgeSchemaId + "_" + directionCode;
    }

    /** The direction code of a requested side (UNDIRECTED/BIDIRECTIONAL use the single bin). */
    public static int directionCode(com.pengcheng.phoenixgraph.query.storage.EdgeSide side) {
        return switch (side) {
            case OUT -> DIR_OUT;
            case IN -> DIR_IN;
            case BOTH -> DIR_BOTH;
        };
    }

    /** The bin name of an edge class and direction. */
    public static String bin(EdgeSchema edgeSchema, int directionCode) {
        return bin(edgeSchema.id(), directionCode);
    }

    /** The degree limit the schema declares for a direction ({@code <= 0} = unlimited). */
    public static int degreeLimit(EdgeSchema edgeSchema, int directionCode) {
        if (edgeSchema == null) {
            return 0;
        }
        return switch (directionCode) {
            case DIR_OUT -> edgeSchema.maxOutDegree();
            case DIR_IN -> edgeSchema.maxInDegree();
            default -> edgeSchema.maxDegree();
        };
    }
}
