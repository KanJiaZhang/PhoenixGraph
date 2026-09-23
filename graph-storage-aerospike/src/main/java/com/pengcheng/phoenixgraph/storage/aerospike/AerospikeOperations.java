package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.BatchRead;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.Operation;
import com.aerospike.client.cdt.MapOperation;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.IndexType;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.Statement;
import com.aerospike.client.task.IndexTask;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;

import java.util.ArrayList;
import java.util.List;

/**
 * The slice of the Aerospike client the stores use, so every round trip can be counted.
 *
 * <p>The stores call the database through this instead of the client directly, which
 * means the metrics see <em>every</em> key-value operation — single reads, batch reads,
 * read-modify-write {@code operate} calls, scans and queries — including the ones a
 * retry repeats. A batch contributes its key count to the item counters, so "graph
 * queries translated into key-value queries" is a measured number rather than a guess.
 *
 * <p>Logical counters (vertex reads, edge evictions, stale updates) are recorded by the
 * stores themselves, which know what a record means; this type counts the traffic.
 */
public interface AerospikeOperations {

    Record get(Policy policy, Key key);

    Record get(Policy policy, Key key, String... binNames);

    Record[] get(BatchPolicy policy, Key[] keys);

    Record[] get(BatchPolicy policy, Key[] keys, String... binNames);

    void put(WritePolicy policy, Key key, Bin... bins);

    boolean delete(WritePolicy policy, Key key);

    Record operate(WritePolicy policy, Key key, Operation... operations);

    /**
     * The number of entries in a map bin, computed by the server.
     *
     * <p>It is a read-only CDT operation, so the payload is one number rather than the whole map -
     * what turns {@code count()} on a hub's edge bin from "read the bin and measure it" into "read
     * one number". It is counted as a read (of that bin), not as a write, which is what it is.
     *
     * @return the entries in {@code bin}, or {@code 0} when the record or the bin is absent
     */
    long mapSize(Key key, String bin);

    /**
     * The sizes of one map bin across many keys, computed by the server in one batch.
     *
     * <p>The batch form of {@link #mapSize(Key, String)}: one round trip for the whole set, and
     * still only the numbers on the wire.
     *
     * @return one size per key, in the key order ({@code 0} for an absent record or bin)
     */
    long[] mapSizes(Key[] keys, String bin);

    boolean operate(BatchPolicy policy, List<BatchRecord> records);

    void scanAll(ScanPolicy policy, String namespace, String set, ScanCallback callback, String... binNames);

    RecordSet query(QueryPolicy policy, Statement statement);

    Node[] getNodes();

    IndexTask createIndex(Policy policy, String namespace, String set, String indexName, String binName,
                          IndexType indexType);

    /** Wraps a client so every call is counted into {@code metrics}. */
    static AerospikeOperations metered(AerospikeClient client, MetricsSink metrics) {
        return new Metered(client, metrics);
    }

    /** A client with a counter on every call. */
    final class Metered implements AerospikeOperations {
        private final AerospikeClient client;
        private final MetricsSink metrics;

        private Metered(AerospikeClient client, MetricsSink metrics) {
            this.client = client;
            this.metrics = metrics == null ? MetricsSink.NOOP : metrics;
        }

        /** Edge sets of the aggregated schemes, and the per-edge-class marker bins. */
        private static boolean isEdgeSet(Key key) {
            return AerospikeReadKind.isEdgeSet(key == null ? null : key.setName);
        }

        private static boolean isEdgeBin(String bin) {
            return AerospikeReadKind.isEdgeBin(bin);
        }

        /** Counts one read, both as key-value traffic and as a vertex or edge read. */
        private void read(Key key, String[] bins, long items) {
            String call = AerospikeReadKind.isEdge(key, bins) ? Metrics.EDGE_READ : Metrics.VERTEX_READ;
            metrics.increment(call);
            metrics.increment(Metrics.items(call), items);
            metrics.increment(Metrics.KV_READ_CALL);
            metrics.increment(Metrics.KV_READ_ITEMS, items);
        }

        private void readFailed() {
            metrics.increment(Metrics.KV_READ_FAILED);
        }

        /** Counts a batch read: one call, one item per key that came back. */
        private void readBatch(Key[] keys, String[] bins, Record[] records) {
            int found = 0;
            if (keys != null) {
                for (int i = 0; i < keys.length; i++) {
                    boolean hit = records != null && i < records.length && records[i] != null;
                    found += hit ? 1 : 0;
                    if (hit) {
                        String call = AerospikeReadKind.isEdge(keys[i], bins)
                                ? Metrics.EDGE_READ : Metrics.VERTEX_READ;
                        metrics.increment(call);
                        metrics.increment(Metrics.items(call));
                    }
                }
            }
            metrics.increment(Metrics.KV_READ_CALL);
            metrics.increment(Metrics.KV_READ_ITEMS, found);
        }

        private void write(long items) {
            metrics.increment(Metrics.KV_WRITE_CALL);
            metrics.increment(Metrics.KV_WRITE_ITEMS, items);
        }

        @Override
        public Record get(Policy policy, Key key) {
            try {
                Record record = client.get(policy, key);
                read(key, null, record == null ? 0 : 1);
                return record;
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
        }

        @Override
        public Record get(Policy policy, Key key, String... binNames) {
            try {
                Record record = client.get(policy, key, binNames);
                read(key, binNames, record == null ? 0 : 1);
                return record;
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
        }

        @Override
        public Record[] get(BatchPolicy policy, Key[] keys) {
            try {
                Record[] records = client.get(policy, keys);
                readBatch(keys, null, records);
                return records;
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
        }

        @Override
        public Record[] get(BatchPolicy policy, Key[] keys, String... binNames) {
            try {
                Record[] records = client.get(policy, keys, binNames);
                readBatch(keys, binNames, records);
                return records;
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
        }

        @Override
        public void put(WritePolicy policy, Key key, Bin... bins) {
            try {
                client.put(policy, key, bins);
                write(1);
            } catch (RuntimeException e) {
                metrics.increment(Metrics.KV_WRITE_FAILED);
                throw e;
            }
        }

        @Override
        public boolean delete(WritePolicy policy, Key key) {
            try {
                boolean deleted = client.delete(policy, key);
                write(1);
                return deleted;
            } catch (RuntimeException e) {
                metrics.increment(Metrics.KV_WRITE_FAILED);
                throw e;
            }
        }

        @Override
        public Record operate(WritePolicy policy, Key key, Operation... operations) {
            try {
                Record record = client.operate(policy, key, operations);
                write(1);
                return record;
            } catch (RuntimeException e) {
                metrics.increment(Metrics.KV_WRITE_FAILED);
                throw e;
            }
        }

        @Override
        public boolean operate(BatchPolicy policy, List<BatchRecord> records) {
            try {
                boolean result = client.operate(policy, records);
                write(records == null ? 0 : records.size());
                return result;
            } catch (RuntimeException e) {
                metrics.increment(Metrics.KV_WRITE_FAILED);
                throw e;
            }
        }

        @Override
        public long mapSize(Key key, String bin) {
            Record record;
            try {
                record = client.operate(null, key, MapOperation.size(bin));
                read(key, new String[] {bin}, record == null ? 0 : 1);
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
            Object size = record == null || record.bins == null ? null : record.bins.get(bin);
            return size instanceof Number number ? number.longValue() : 0;
        }

        @Override
        public long[] mapSizes(Key[] keys, String bin) {
            long[] sizes = new long[keys.length];
            List<BatchRead> reads = new ArrayList<>(keys.length);
            for (Key key : keys) {
                // a read-only CDT op belongs in a BatchRead: a BatchWrite rejects a batch with no write
                reads.add(new BatchRead(key, new Operation[] {MapOperation.size(bin)}));
            }
            try {
                client.get(new BatchPolicy(), reads);
                Record[] records = new Record[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    records[i] = reads.get(i).record;
                }
                readBatch(keys, new String[] {bin}, records);
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
            for (int i = 0; i < keys.length; i++) {
                Record record = reads.get(i).record;
                // an absent bin comes back as a record with no bins at all: not an error, a zero
                Object size = record == null || record.bins == null ? null : record.bins.get(bin);
                sizes[i] = size instanceof Number number ? number.longValue() : 0;
            }
            return sizes;
        }

        @Override
        public void scanAll(ScanPolicy policy, String namespace, String set, ScanCallback callback,
                            String... binNames) {
            long[] seen = {0};
            ScanCallback counting = (key, record) -> {
                seen[0]++;
                callback.scanCallback(key, record);
            };
            try {
                client.scanAll(policy, namespace, set, counting, binNames);
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            } finally {
                metrics.increment(Metrics.EDGE_READ, seen[0]);
                metrics.increment(Metrics.items(Metrics.EDGE_READ), seen[0]);
                metrics.increment(Metrics.KV_READ_CALL);
                metrics.increment(Metrics.KV_READ_ITEMS, seen[0]);
            }
        }

        @Override
        public RecordSet query(QueryPolicy policy, Statement statement) {
            try {
                RecordSet records = client.query(policy, statement);
                metrics.increment(Metrics.KV_READ_CALL); // the caller drains the set
                return records;
            } catch (RuntimeException e) {
                readFailed();
                throw e;
            }
        }

        @Override
        public Node[] getNodes() {
            return client.getNodes();
        }

        @Override
        public IndexTask createIndex(Policy policy, String namespace, String set, String indexName, String binName,
                                     IndexType indexType) {
            return client.createIndex(policy, namespace, set, indexName, binName, indexType);
        }
    }
}
