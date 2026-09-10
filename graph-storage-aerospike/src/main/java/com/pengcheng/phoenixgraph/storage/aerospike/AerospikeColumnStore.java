package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.pengcheng.phoenixgraph.storage.ColumnStore;

import java.util.Map;
import java.util.Objects;

/**
 * Aerospike-backed {@link ColumnStore}. A record is a vertex/row in the given
 * namespace and set; each column is an Aerospike bin. Column values must be
 * Aerospike-supported types (String, number, byte[], List, Map).
 */
public class AerospikeColumnStore implements ColumnStore {

    private final AerospikeClient client;
    private final String namespace;
    private final String set;

    public AerospikeColumnStore(AerospikeClient client, String namespace, String set) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.set = Objects.requireNonNull(set, "set");
    }

    public AerospikeColumnStore(String host, int port, String namespace, String set) {
        this(new AerospikeClient(host, port), namespace, set);
    }

    @Override
    public void put(Object key, Map<String, Object> columns) {
        Bin[] bins = columns.entrySet().stream()
                .map(e -> new Bin(e.getKey(), Value.get(e.getValue())))
                .toArray(Bin[]::new);
        client.put(null, key(namespace, set, key), bins);
    }

    @Override
    public Map<String, Object> get(Object key) {
        Record record = client.get(null, key(namespace, set, key));
        return record == null ? null : record.bins;
    }

    @Override
    public void putColumn(Object key, String column, Object value) {
        client.put(null, key(namespace, set, key), new Bin(column, Value.get(value)));
    }

    @Override
    public Object getColumn(Object key, String column) {
        Record record = client.get(null, key(namespace, set, key));
        return record == null ? null : record.bins.get(column);
    }

    @Override
    public void removeColumn(Object key, String column) {
        client.put(null, key(namespace, set, key), Bin.asNull(column));
    }

    @Override
    public void remove(Object key) {
        client.delete(null, key(namespace, set, key));
    }

    private static Key key(String namespace, String set, Object key) {
        return AerospikeKeys.of(namespace, set, key);
    }
}
