package com.pengcheng.phoenixgraph.hnsw;

import java.time.Duration;
import java.util.Objects;

/**
 * Keeps the index metadata (entry points, size, layer count) in memory for a
 * short while instead of reading the record on every query.
 *
 * <p>A search re-reads the entry points before it starts, which is what makes a
 * second process pick up the vertices another instance inserted meanwhile. Doing
 * that against the key/value store on every query would be wasteful, so this
 * decorator serves the cached copy for {@link #ttl()} and only then goes back to
 * the store - one extra read per minute per index, by default.
 *
 * <p>Writes are write-through: the local view is updated immediately, so a
 * builder (or an instance that indexes on write) always sees its own state.
 */
public final class CachedHnswMetadataStore implements HnswMetadataStore {

    /** The default time-to-live of a cached metadata record. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(1);

    private final HnswMetadataStore delegate;
    private final long ttlMillis;
    private volatile HnswMetadata cached;
    private volatile long loadedAt;
    private volatile boolean loaded;

    public CachedHnswMetadataStore(HnswMetadataStore delegate) {
        this(delegate, DEFAULT_TTL);
    }

    public CachedHnswMetadataStore(HnswMetadataStore delegate, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttlMillis = Objects.requireNonNull(ttl, "ttl").toMillis();
    }

    /** How long a read result is reused before the store is asked again. */
    public Duration ttl() {
        return Duration.ofMillis(ttlMillis);
    }

    /** Whether the cached value has expired. */
    public boolean stale() {
        return !loaded || System.currentTimeMillis() - loadedAt >= ttlMillis;
    }

    @Override
    public HnswMetadata read() {
        if (stale()) {
            synchronized (this) {
                if (stale()) {
                    cached = delegate.read();
                    loadedAt = System.currentTimeMillis();
                    loaded = true;
                }
            }
        }
        return cached;
    }

    @Override
    public void write(HnswMetadata metadata) {
        delegate.write(metadata);
        cached = metadata;
        loadedAt = System.currentTimeMillis();
        loaded = true;
    }

    /** Drops the cached value, so the next read hits the store. */
    public void invalidate() {
        loaded = false;
    }
}
