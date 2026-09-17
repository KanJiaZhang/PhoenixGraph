package com.pengcheng.phoenixgraph.storage.rocksdb;

/**
 * A vertex or edge property value together with its version.
 *
 * <p>The version is the property's {@code lastUpdatedTime}: it is written into the
 * RocksDB record as the version of that property and is what decides whether an
 * incoming write replaces the stored value. A property is only overwritten by a write
 * whose version is <b>newer</b>, so out-of-order writes cannot undo a newer one.
 *
 * @param value   the property value
 * @param version the property's last-update timestamp (epoch millis)
 */
public record RocksDbPropertyValue(Object value, long version) implements Comparable<RocksDbPropertyValue> {

    @Override
    public int compareTo(RocksDbPropertyValue other) {
        return Long.compare(version, other.version);
    }
}
