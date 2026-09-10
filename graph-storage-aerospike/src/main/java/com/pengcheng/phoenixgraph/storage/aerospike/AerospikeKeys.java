package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.Key;

/**
 * Converts an arbitrary graph id ({@code String}/{@code Long}/{@code Integer}/
 * {@code byte[]}) to an Aerospike {@link Key}.
 */
final class AerospikeKeys {

    private AerospikeKeys() {
    }

    static Key of(String namespace, String set, Object userKey) {
        if (userKey instanceof String s) {
            return new Key(namespace, set, s);
        }
        if (userKey instanceof Long l) {
            return new Key(namespace, set, l.longValue());
        }
        if (userKey instanceof Integer i) {
            return new Key(namespace, set, i.intValue());
        }
        if (userKey instanceof byte[] b) {
            return new Key(namespace, set, b);
        }
        return new Key(namespace, set, String.valueOf(userKey));
    }
}
