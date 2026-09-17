package com.pengcheng.phoenixgraph.schema.json;

/**
 * JSON DTO of a graph schema's {@code storage} block.
 *
 * <pre>
 * "storage": { "type": "RocksDB-Inline", "path": "/var/lib/phoenixgraph/payment-inline",
 *              "regionCount": 512, "maxVersions": 1, "cacheEdges": true }
 * </pre>
 *
 * <p>Every component but {@code type} is optional: the RocksDB schemes fill in their
 * defaults, and an absent block means the Aerospike default.
 */
public record StorageSchemaDto(String type, String path, Integer regionCount, Integer maxVersions,
                               Boolean cacheEdges) {
}
