package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A small thread-safe LRU cache.
 *
 * <p>Used in front of the decoded edge lists: a traversal asks for the neighbours of the
 * same vertex from several steps, and the inline layout would otherwise decode the whole
 * edge map of that vertex each time. Writers invalidate the entries they change, so a
 * cached list is never read after the record it came from was rewritten.
 *
 * @param <V> the cached value type
 */
public final class RocksDbLruCache<V> {

    private final int maxEntries;
    private final Map<String, V> entries;

    public RocksDbLruCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > RocksDbLruCache.this.maxEntries;
            }
        };
    }

    /** The cached value, or {@code null}. */
    public synchronized V get(String key) {
        return entries.get(key);
    }

    /** The cached value, loading and storing it on a miss. */
    public V get(String key, Supplier<V> loader) {
        Objects.requireNonNull(loader, "loader");
        synchronized (this) {
            V cached = entries.get(key);
            if (cached != null) {
                return cached;
            }
        }
        V loaded = loader.get();
        synchronized (this) {
            entries.put(key, loaded);
        }
        return loaded;
    }

    /** Stores a value. */
    public synchronized void put(String key, V value) {
        entries.put(key, value);
    }

    /** Drops one entry. */
    public synchronized void invalidate(String key) {
        entries.remove(key);
    }

    /** Drops every entry whose key matches the predicate (used to invalidate a prefix). */
    public synchronized void invalidateIf(java.util.function.Predicate<String> matches) {
        entries.keySet().removeIf(matches);
    }

    /** Drops every entry (used when a write cannot tell which keys it invalidated). */
    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public int maxEntries() {
        return maxEntries;
    }
}
