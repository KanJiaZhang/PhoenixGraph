package com.pengcheng.phoenixgraph.storage.rocksdb;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.Checkpoint;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatch;
import com.pengcheng.phoenixgraph.metrics.Metrics;
import com.pengcheng.phoenixgraph.metrics.MetricsSink;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One open RocksDB directory with named column families.
 *
 * <p>The column family is the "table" of this storage scheme: a vertex type and an edge
 * type each get their own, so a scan or a compaction never mixes unrelated records.
 * Families are declared from the graph schema when the store is opened; a family that
 * is asked for later is created on demand (the HNSW layer classes appear while an index
 * grows).
 *
 * <p>Every call is a plain RocksDB call - the store is a tuple store, so anything that
 * needs read-modify-write consistency (an edge list) takes a {@link KeyLocks} lock in
 * the calling code. All checked {@link RocksDBException}s become
 * {@link RocksDbException}.
 */
public final class RocksDbHandle implements AutoCloseable {

    private final RocksDbStoreConfig config;
    private final DBOptions dbOptions;
    private final WriteOptions writeOptions;
    private final ColumnFamilyOptions columnFamilyOptions;
    private final RocksDB db;
    /** Name of the column family RocksDB always creates. */
    public static final String DEFAULT_FAMILY = new String(RocksDB.DEFAULT_COLUMN_FAMILY,
            StandardCharsets.UTF_8);

    private final Map<String, ColumnFamilyHandle> families;
    /** Where key-value traffic is counted; a no-op until the graph wires its metrics in. */
    private volatile MetricsSink metrics = MetricsSink.NOOP;
    /** Keys added to the batch being built, per column family (applied on {@link #write}). */
    private final Map<String, Long> batchItems = new java.util.concurrent.ConcurrentHashMap<>();

    private RocksDbHandle(RocksDbStoreConfig config, DBOptions dbOptions, WriteOptions writeOptions,
                          ColumnFamilyOptions columnFamilyOptions, RocksDB db,
                          Map<String, ColumnFamilyHandle> families) {
        this.config = config;
        this.dbOptions = dbOptions;
        this.writeOptions = writeOptions;
        this.columnFamilyOptions = columnFamilyOptions;
        this.db = db;
        this.families = families;
    }

    /**
     * Opens (creating it when configured) the RocksDB directory and every named column
     * family. Missing families are created, so opening a fresh directory with the
     * schema's families is one call.
     *
     * <p>The families the directory <b>already</b> has are opened too, whatever the caller named: RocksDB
     * refuses to open a database unless every family it holds is opened, and some families are created at
     * runtime rather than declared (the layer classes of a vector index, one per layer). Asking the
     * directory is therefore what makes a graph reopenable after it was indexed; the caller's names are the
     * ones that get created when they are missing.
     */
    public static RocksDbHandle open(RocksDbStoreConfig config, Collection<String> columnFamilies) {
        Objects.requireNonNull(config, "config");
        RocksDB.loadLibrary();

        Set<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_FAMILY);
        if (columnFamilies != null) {
            names.addAll(columnFamilies);
        }
        names.addAll(existingColumnFamilies(config.path()));

        DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(config.createIfMissing())
                .setCreateMissingColumnFamilies(true)
                .setMaxOpenFiles(-1);
        ColumnFamilyOptions familyOptions = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);
        WriteOptions writeOptions = new WriteOptions();

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
        for (String name : names) {
            descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), familyOptions));
        }
        List<ColumnFamilyHandle> handles = new ArrayList<>(descriptors.size());
        RocksDB db;
        try {
            db = RocksDB.open(dbOptions, config.path(), descriptors, handles);
        } catch (RocksDBException e) {
            dbOptions.close();
            familyOptions.close();
            writeOptions.close();
            throw new RocksDbException("Failed to open RocksDB at " + config.path() + ": " + e.getMessage(), e);
        }
        Map<String, ColumnFamilyHandle> families = new LinkedHashMap<>();
        for (int i = 0; i < descriptors.size(); i++) {
            families.put(new String(descriptors.get(i).getName(), StandardCharsets.UTF_8), handles.get(i));
        }
        return new RocksDbHandle(config, dbOptions, writeOptions, familyOptions, db, families);
    }

    /** Convenience: opens a directory with the given families. */
    public static RocksDbHandle open(String path, Collection<String> columnFamilies) {
        return open(RocksDbStoreConfig.of(path), columnFamilies);
    }

    /**
     * The column families a directory already holds, or none when it does not exist yet (a fresh graph).
     * A directory that cannot be listed is not an error here: the open that follows will report it.
     */
    private static Set<String> existingColumnFamilies(String path) {
        if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(path))) {
            return Set.of();
        }
        try (Options options = new Options()) {
            List<byte[]> families = RocksDB.listColumnFamilies(options, path);
            Set<String> names = new LinkedHashSet<>();
            for (byte[] family : families) {
                names.add(new String(family, StandardCharsets.UTF_8));
            }
            return names;
        } catch (RocksDBException e) {
            return Set.of();
        }
    }

    /**
     * Writes a consistent, hard-linked copy of this directory into {@code target} (RocksDB's own
     * checkpoint: a point-in-time snapshot that another process can open read-only).
     *
     * <p>This is what makes "back up a graph" cheap: the copy is made of hard links, so it costs a few
     * kilobytes and a moment rather than a full rewrite, and the service keeps serving while it happens. The
     * snapshot is a *directory*, so restoring is "start a service whose schema points at the copy" - no import
     * step to get wrong.
     *
     * @return the number of files in the checkpoint
     */
    public int checkpoint(Path target) {
        try {
            // RocksDB creates the directory itself and refuses an existing one - which is the behaviour a
            // backup wants (a checkpoint must not be written into a directory that already holds something
            // else), so the message says how to proceed instead of hiding it behind "Directory exists"
            if (Files.exists(target)) {
                throw new RocksDbException("Checkpoint target " + target + " already exists; choose another "
                        + "directory or remove it first");
            }
            try (Checkpoint checkpoint = Checkpoint.create(db)) {
                checkpoint.createCheckpoint(target.toAbsolutePath().toString());
            }
            try (var files = Files.list(target)) {
                return (int) files.count();
            }
        } catch (RocksDBException | IOException failure) {
            throw new RocksDbException("Checkpoint of " + config.path() + " into " + target + " failed: "
                    + failure.getMessage(), failure);
        }
    }

    /** Counts every key-value operation of this handle into {@code metrics}. */
    public RocksDbHandle metrics(MetricsSink metrics) {
        this.metrics = metrics == null ? MetricsSink.NOOP : metrics;
        return this;
    }

    /** The metrics sink of this handle. */
    public MetricsSink metrics() {
        return metrics;
    }

    public RocksDbStoreConfig config() {
        return config;
    }

    public String path() {
        return config.path();
    }

    /** The number of key-space regions this store spreads keys over. */
    public int regionCount() {
        return config.regionCount();
    }

    /** The region of a vertex id (see {@link RocksDbHash#regionOf}). */
    public int regionOf(Object vertexId) {
        return RocksDbHash.regionOf(vertexId, config.regionCount());
    }

    /** The column families that currently exist. */
    public synchronized Set<String> columnFamilies() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(families.keySet()));
    }

    /** Whether the named column family exists. */
    public synchronized boolean hasColumnFamily(String name) {
        return families.containsKey(name);
    }

    /** Returns the family, creating it when it is missing. */
    public synchronized ColumnFamilyHandle columnFamily(String name) {
        ColumnFamilyHandle existing = families.get(name);
        if (existing != null) {
            return existing;
        }
        try {
            ColumnFamilyHandle created = db.createColumnFamily(
                    new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), columnFamilyOptions));
            families.put(name, created);
            return created;
        } catch (RocksDBException e) {
            throw new RocksDbException("Failed to create column family '" + name + "': " + e.getMessage(), e);
        }
    }

    // --- metrics ---

    /** What a column family holds, so traffic can be attributed to vertices, edges or geo cells. */
    private enum Family {
        VERTEX, EDGE, GEO, OTHER;

        static Family of(String columnFamily) {
            if (columnFamily == null) {
                return OTHER;
            }
            if (columnFamily.startsWith("vertex:")) {
                return VERTEX;
            }
            if (columnFamily.startsWith("edge:") || columnFamily.startsWith("edge-inline:")) {
                return EDGE;
            }
            if (columnFamily.startsWith("geo:")) {
                return GEO;
            }
            return OTHER;
        }
    }

    private static String callMetric(Family family, boolean read) {
        String prefix = switch (family) {
            case VERTEX -> "storage.vertex.";
            case EDGE -> "storage.edge.";
            case GEO -> "storage.geo.";
            case OTHER -> "storage.";
        };
        return prefix + (read ? "read" : "write");
    }

    private void recordRead(String columnFamily, long items, boolean success) {
        if (RocksDbMetricsStore.FAMILY.equals(columnFamily)) {
            return; // the metrics of the metrics are not interesting
        }
        String call = callMetric(Family.of(columnFamily), true);
        metrics.increment(call);
        metrics.increment(Metrics.items(call), items);
        metrics.increment(Metrics.KV_READ_CALL);
        metrics.increment(Metrics.KV_READ_ITEMS, items);
        if (!success) {
            metrics.increment(Metrics.failed(call));
            metrics.increment(Metrics.KV_READ_FAILED);
        }
    }

    private void recordWrite(String columnFamily, long items, boolean success) {
        if (RocksDbMetricsStore.FAMILY.equals(columnFamily)) {
            return;
        }
        String call = callMetric(Family.of(columnFamily), false);
        metrics.increment(call);
        metrics.increment(Metrics.items(call), items);
        metrics.increment(Metrics.KV_WRITE_CALL);
        metrics.increment(Metrics.KV_WRITE_ITEMS, items);
        if (!success) {
            metrics.increment(Metrics.failed(call));
            metrics.increment(Metrics.KV_WRITE_FAILED);
        }
    }

    // --- point operations ---

    /** Reads one key, or {@code null} when it is absent. */
    public byte[] get(String columnFamily, byte[] key) {
        byte[] value;
        try {
            value = db.get(columnFamily(columnFamily), key);
        } catch (RocksDBException e) {
            recordRead(columnFamily, 0, false);
            throw new RocksDbException("get(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
        recordRead(columnFamily, value == null ? 0 : 1, true);
        return value;
    }

    /** Reads many keys of one family in a single call; a missing key yields {@code null}. */
    public List<byte[]> multiGet(String columnFamily, List<byte[]> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        try {
            List<ColumnFamilyHandle> handles = new ArrayList<>(keys.size());
            ColumnFamilyHandle handle = columnFamily(columnFamily);
            for (int i = 0; i < keys.size(); i++) {
                handles.add(handle);
            }
            List<byte[]> values = db.multiGetAsList(handles, keys);
            recordRead(columnFamily, keys.size(), true);
            return values;
        } catch (RocksDBException e) {
            recordRead(columnFamily, 0, false);
            throw new RocksDbException("multiGet(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Writes one key. */
    public void put(String columnFamily, byte[] key, byte[] value) {
        try {
            db.put(columnFamily(columnFamily), writeOptions, key, value);
            recordWrite(columnFamily, 1, true);
        } catch (RocksDBException e) {
            recordWrite(columnFamily, 0, false);
            throw new RocksDbException("put(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Removes one key. */
    public void delete(String columnFamily, byte[] key) {
        try {
            db.delete(columnFamily(columnFamily), writeOptions, key);
            recordWrite(columnFamily, 1, true);
        } catch (RocksDBException e) {
            recordWrite(columnFamily, 0, false);
            throw new RocksDbException("delete(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    // --- batches ---

    /** A new batch; callers close it (use try-with-resources). */
    public WriteBatch batch() {
        return new WriteBatch();
    }

    /** Adds a put to a batch (counted when the batch is applied, one write call per batch). */
    public void put(WriteBatch batch, String columnFamily, byte[] key, byte[] value) {
        try {
            batch.put(columnFamily(columnFamily), key, value);
            batchItems.merge(columnFamily, 1L, Long::sum);
        } catch (RocksDBException e) {
            throw new RocksDbException("batch put(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Adds a delete to a batch. */
    public void delete(WriteBatch batch, String columnFamily, byte[] key) {
        try {
            batch.delete(columnFamily(columnFamily), key);
            batchItems.merge(columnFamily, 1L, Long::sum);
        } catch (RocksDBException e) {
            throw new RocksDbException("batch delete(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Applies a batch atomically across column families; one write call, one item per key. */
    public void write(WriteBatch batch) {
        Map<String, Long> items = Map.copyOf(batchItems);
        batchItems.clear();
        try {
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            items.forEach((family, count) -> recordWrite(family, 0, false));
            throw new RocksDbException("batch write failed: " + e.getMessage(), e);
        }
        items.forEach((family, count) -> recordWrite(family, count, true));
    }

    // --- iteration ---

    /**
     * Opens a range iterator over {@code [from, to)}; a {@code null} bound is open.
     * The caller closes the returned iterator.
     */
    public RocksDbRange iterator(String columnFamily, byte[] from, byte[] to) {
        ReadOptions options = new ReadOptions();
        Slice lower = from == null ? null : new Slice(from);
        Slice upper = to == null ? null : new Slice(to);
        if (lower != null) {
            options.setIterateLowerBound(lower);
        }
        if (upper != null) {
            options.setIterateUpperBound(upper);
        }
        RocksIterator iterator = db.newIterator(columnFamily(columnFamily), options);
        iterator.seekToFirst();
        boolean hasFirst = iterator.isValid();
        recordRead(columnFamily, hasFirst ? 1 : 0, true); // the iterator opens on its first record
        return new RocksDbRange(iterator, options, lower, upper, metrics, callMetric(Family.of(columnFamily), true));
    }

    /** Flushes memtables (used after a bulk load and by tests). */
    public synchronized void flush() {
        try (FlushOptions options = new FlushOptions().setWaitForFlush(true)) {
            for (ColumnFamilyHandle handle : families.values()) {
                db.flush(options, handle);
            }
        } catch (RocksDBException e) {
            throw new RocksDbException("flush failed: " + e.getMessage(), e);
        }
    }

    /** The RocksDB version string. */
    public static String version() {
        RocksDB.loadLibrary();
        return String.valueOf(RocksDB.rocksdbVersion());
    }

    @Override
    public void close() {
        synchronized (this) {
            for (ColumnFamilyHandle handle : families.values()) {
                handle.close();
            }
            families.clear();
        }
        db.close();
        writeOptions.close();
        columnFamilyOptions.close();
        dbOptions.close();
    }

    /**
     * A range iterator plus the native handles it holds. Closing it releases the
     * iterator, the read options and the bound slices in the right order.
     */
    public static final class RocksDbRange implements AutoCloseable {

        private final RocksIterator iterator;
        private final ReadOptions options;
        private final Slice lower;
        private final Slice upper;
        private final MetricsSink metrics;
        private final String itemsMetric;

        private RocksDbRange(RocksIterator iterator, ReadOptions options, Slice lower, Slice upper,
                             MetricsSink metrics, String itemsMetric) {
            this.iterator = iterator;
            this.options = options;
            this.lower = lower;
            this.upper = upper;
            this.metrics = metrics == null ? MetricsSink.NOOP : metrics;
            this.itemsMetric = itemsMetric;
        }

        public boolean isValid() {
            return iterator.isValid();
        }

        public void next() {
            iterator.next();
            if (iterator.isValid()) {
                metrics.increment(itemsMetric); // every record the caller consumes is an item
            }
        }

        public void seek(byte[] key) {
            iterator.seek(key);
            if (iterator.isValid()) {
                metrics.increment(itemsMetric);
            }
        }

        public byte[] key() {
            return iterator.key();
        }

        public byte[] value() {
            return iterator.value();
        }

        public void close() {
            iterator.close();
            options.close();
            if (lower != null) {
                lower.close();
            }
            if (upper != null) {
                upper.close();
            }
        }
    }
}
