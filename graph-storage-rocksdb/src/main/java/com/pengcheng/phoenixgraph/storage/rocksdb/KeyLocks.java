package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Striped locks that serialise read-modify-write cycles on the same key.
 *
 * <p>A tuple store has no compare-and-set, so "read the edge list, merge the new
 * edges, write it back" has to be protected against a concurrent writer of the same
 * key within this process. Locks are striped by key hash, so unrelated keys never
 * contend, and a multi-key operation (an edge touches both endpoints) locks its keys in
 * a stable order, which makes deadlock impossible.
 */
public final class KeyLocks {

    private static final int DEFAULT_STRIPES = 128;

    private final ReentrantLock[] stripes;

    public KeyLocks() {
        this(DEFAULT_STRIPES);
    }

    public KeyLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive, got " + stripeCount);
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** Runs {@code action} holding the lock of {@code key}. */
    public <T> T withLock(Object key, Supplier<T> action) {
        ReentrantLock lock = stripeOf(key);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code action} holding the lock of {@code key}. */
    public void withLock(Object key, Runnable action) {
        withLock(key, () -> {
            action.run();
            return null;
        });
    }

    /** Runs {@code action} holding the locks of every key, acquired in stripe order. */
    public <T> T withLocks(Collection<?> keys, Supplier<T> action) {
        List<ReentrantLock> locked = new ArrayList<>();
        for (int index : stripeIndexes(keys)) {
            ReentrantLock lock = stripes[index];
            lock.lock();
            locked.add(lock);
        }
        try {
            return action.get();
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).unlock();
            }
        }
    }

    /** The distinct stripe indexes of the given keys, ascending (a stable lock order). */
    private int[] stripeIndexes(Collection<?> keys) {
        boolean[] taken = new boolean[stripes.length];
        List<Integer> indexes = new ArrayList<>();
        for (Object key : keys) {
            int index = stripeIndexOf(key);
            if (!taken[index]) {
                taken[index] = true;
                indexes.add(index);
            }
        }
        indexes.sort(Integer::compareTo);
        int[] out = new int[indexes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = indexes.get(i);
        }
        return out;
    }

    private ReentrantLock stripeOf(Object key) {
        return stripes[stripeIndexOf(key)];
    }

    private int stripeIndexOf(Object key) {
        return RocksDbHash.regionOfHash(RocksDbHash.hash(key), stripes.length);
    }
}
