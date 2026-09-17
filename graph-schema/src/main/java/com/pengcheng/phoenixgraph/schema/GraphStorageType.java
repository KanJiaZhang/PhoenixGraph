package com.pengcheng.phoenixgraph.schema;

import java.util.Locale;

/**
 * The storage implementation a graph schema is served by.
 *
 * <p>Declared in the schema itself, so the same graph definition says both what the data
 * means and where it lives:
 *
 * <pre>
 * "storage": { "type": "RocksDB-Inline", "path": "/var/lib/phoenixgraph/payment-inline" }
 * </pre>
 *
 * <p>{@link #AEROSPIKE} is the default: a schema without a storage block is served by
 * Aerospike, which is how every schema written before this option existed keeps working.
 */
public enum GraphStorageType {

    /**
     * Aerospike set: vertices and their denormalised edges in one record per vertex,
     * with per-property CAS on the {@code lastUpdatedTime}. The default.
     */
    AEROSPIKE("Aerospike"),

    /**
     * Aerospike set as {@link #AEROSPIKE}, plus an aggregated edge table per edge class
     * ({@code Edge_Out} / {@code Edge_In}): the edges of a vertex are chunked by creation time
     * and indexed by two further levels, so a vertex with hundreds of millions of edges is read
     * by walking the levels of a time window. Written by a bulk import, not edge by edge.
     */
    AEROSPIKE_EDGE("Aerospike-Edge"),

    /**
     * RocksDB directory with one table per vertex type and one table per edge type, the
     * edges of a vertex folded into its record of that edge type. Degree limits apply.
     */
    ROCKSDB_INLINE("RocksDB-Inline"),

    /**
     * RocksDB directory with one table per vertex type and an edge table per edge class,
     * keyed by hash prefix, direction, vertex id, creation time and edge id. Degree limits
     * do not apply and a creation-time window is a range scan.
     */
    ROCKSDB_EDGE("RocksDB-Edge");

    private final String configName;

    GraphStorageType(String configName) {
        this.configName = configName;
    }

    /** The name written in the schema JSON. */
    public String configName() {
        return configName;
    }

    /** Whether this is an Aerospike storage (the default, or the aggregated edge variant). */
    public boolean isAerospike() {
        return this == AEROSPIKE || this == AEROSPIKE_EDGE;
    }

    /** Whether this is the Aerospike storage with the aggregated edge tables. */
    public boolean isAerospikeEdge() {
        return this == AEROSPIKE_EDGE;
    }

    /** Whether this is one of the RocksDB schemes. */
    public boolean isRocksDb() {
        return this == ROCKSDB_INLINE || this == ROCKSDB_EDGE;
    }

    /**
     * Parses the schema's storage type. The name is matched case-insensitively and
     * tolerates {@code -}, {@code _} and spaces as separators, so {@code "rocksdb-inline"},
     * {@code "RocksDB_Inline"} and {@code "RocksDB-Inline"} all mean the same scheme.
     *
     * @throws IllegalArgumentException when the name is not a known storage implementation
     */
    public static GraphStorageType fromConfigName(String name) {
        if (name == null || name.isBlank()) {
            return AEROSPIKE;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        for (GraphStorageType type : values()) {
            if (type.configName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown storage type '" + name + "'; supported: "
                + AEROSPIKE.configName + ", " + AEROSPIKE_EDGE.configName + ", "
                + ROCKSDB_INLINE.configName + ", " + ROCKSDB_EDGE.configName);
    }
}
