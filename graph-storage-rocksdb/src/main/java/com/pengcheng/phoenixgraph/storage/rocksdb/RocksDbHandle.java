package com.pengcheng.phoenixgraph.storage.rocksdb;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
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
     */
    public static RocksDbHandle open(RocksDbStoreConfig config, Collection<String> columnFamilies) {
        Objects.requireNonNull(config, "config");
        RocksDB.loadLibrary();

        Set<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_FAMILY);
        if (columnFamilies != null) {
            names.addAll(columnFamilies);
        }

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

    // --- point operations ---

    /** Reads one key, or {@code null} when it is absent. */
    public byte[] get(String columnFamily, byte[] key) {
        try {
            return db.get(columnFamily(columnFamily), key);
        } catch (RocksDBException e) {
            throw new RocksDbException("get(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
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
            return db.multiGetAsList(handles, keys);
        } catch (RocksDBException e) {
            throw new RocksDbException("multiGet(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Writes one key. */
    public void put(String columnFamily, byte[] key, byte[] value) {
        try {
            db.put(columnFamily(columnFamily), writeOptions, key, value);
        } catch (RocksDBException e) {
            throw new RocksDbException("put(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Removes one key. */
    public void delete(String columnFamily, byte[] key) {
        try {
            db.delete(columnFamily(columnFamily), writeOptions, key);
        } catch (RocksDBException e) {
            throw new RocksDbException("delete(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    // --- batches ---

    /** A new batch; callers close it (use try-with-resources). */
    public WriteBatch batch() {
        return new WriteBatch();
    }

    /** Adds a put to a batch. */
    public void put(WriteBatch batch, String columnFamily, byte[] key, byte[] value) {
        try {
            batch.put(columnFamily(columnFamily), key, value);
        } catch (RocksDBException e) {
            throw new RocksDbException("batch put(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Adds a delete to a batch. */
    public void delete(WriteBatch batch, String columnFamily, byte[] key) {
        try {
            batch.delete(columnFamily(columnFamily), key);
        } catch (RocksDBException e) {
            throw new RocksDbException("batch delete(" + columnFamily + ") failed: " + e.getMessage(), e);
        }
    }

    /** Applies a batch atomically across column families. */
    public void write(WriteBatch batch) {
        try {
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new RocksDbException("batch write failed: " + e.getMessage(), e);
        }
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
        return new RocksDbRange(iterator, options, lower, upper);
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

        private RocksDbRange(RocksIterator iterator, ReadOptions options, Slice lower, Slice upper) {
            this.iterator = iterator;
            this.options = options;
            this.lower = lower;
            this.upper = upper;
        }

        public boolean isValid() {
            return iterator.isValid();
        }

        public void next() {
            iterator.next();
        }

        public void seek(byte[] key) {
            iterator.seek(key);
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
