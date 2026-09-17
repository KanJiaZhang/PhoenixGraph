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

    /** Whether this is the Aerospike storage (the default). */
    public boolean isAerospike() {
        return this == AEROSPIKE;
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
                + AEROSPIKE.configName + ", " + ROCKSDB_INLINE.configName + ", " + ROCKSDB_EDGE.configName);
    }
}
