package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import com.pengcheng.phoenixgraph.metrics.MutableMetricsSink;
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
    /** The same client, with every round trip counted into {@link #metrics}. */
    private final AerospikeOperations db;
    /** Where this store records its traffic; the graph's metrics are wired in later. */
    private final MutableMetricsSink metrics = new MutableMetricsSink();
    private final String namespace;
    private final String set;

    public AerospikeColumnStore(AerospikeClient client, String namespace, String set) {
        this.client = Objects.requireNonNull(client, "client");
        this.db = AerospikeOperations.metered(this.client, metrics);
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
        db.put(null, key(namespace, set, key), bins);
    }

    @Override
    public Map<String, Object> get(Object key) {
        Record record = db.get(null, key(namespace, set, key));
        return record == null ? null : record.bins;
    }

    @Override
    public void putColumn(Object key, String column, Object value) {
        db.put(null, key(namespace, set, key), new Bin(column, Value.get(value)));
    }

    @Override
    public Object getColumn(Object key, String column) {
        Record record = db.get(null, key(namespace, set, key));
        return record == null ? null : record.bins.get(column);
    }

    @Override
    public void removeColumn(Object key, String column) {
        db.put(null, key(namespace, set, key), Bin.asNull(column));
    }

    @Override
    public void remove(Object key) {
        db.delete(null, key(namespace, set, key));
    }

    private static Key key(String namespace, String set, Object key) {
        return AerospikeKeys.of(namespace, set, key);
    }
    /**
     * Points this store's metrics at a graph's counters. Called while wiring the
     * application (and by tests); until then the store records nothing.
     */
    public AerospikeColumnStore metrics(MetricsSink sink) {
        metrics.target(sink);
        return this;
    }

}
