package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.nio.charset.StandardCharsets;

/**
 * The fixed-width hash every RocksDB key starts with.
 *
 * <p>Vertex ids are keys of their own right (an {@code Account} id may be {@code 135}
 * or {@code account-42}), so a key is stored as
 * <pre>{@code hash(vertexId) : vertexId}</pre>
 * where the hash is a <b>fixed 8 byte</b> FNV-1a value written big-endian. Because the
 * prefix has a constant width, keys sort by hash first - which is what turns the key
 * space into {@code regionCount} physically separate regions - and the trailing id
 * makes an individual key unique (hash collisions only ever cost an extra comparison,
 * never a lost record).
 *
 * <p>The hash is not used to locate a record (a lookup knows the id and rebuilds the
 * key), it is used to spread writes over the key space and to group neighbours of the
 * same region together.
 */
public final class RocksDbHash {

    /** Width of the hash prefix in bytes. */
    public static final int HASH_BYTES = 8;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private RocksDbHash() {
    }

    /** The 64-bit FNV-1a hash of the id's UTF-8 bytes. */
    public static long hash(Object id) {
        return hash(String.valueOf(id));
    }

    /** The 64-bit FNV-1a hash of a string. */
    public static long hash(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** The hash prefix as the fixed 8 bytes stored at the start of every key. */
    public static byte[] hashBytes(Object id) {
        return hashBytes(hash(id));
    }

    /** The hash prefix as the fixed 8 bytes stored at the start of every key. */
    public static byte[] hashBytes(long hash) {
        byte[] bytes = new byte[HASH_BYTES];
        putLong(bytes, 0, hash);
        return bytes;
    }

    /** The region a key belongs to: {@code floorMod(hash, regionCount)}. */
    public static int regionOf(Object id, int regionCount) {
        return regionOfHash(hash(id), regionCount);
    }

    /**
     * Whether the first {@link #HASH_BYTES} bytes of {@code key} are the hash of these id bytes.
     *
     * <p>This is how a scan reconstructs the owner of an edge row from its key: the id is the part of
     * the key that hashes to the prefix, so a split that satisfies this is the only possible one.
     */
    public static boolean hashEquals(byte[] idBytes, byte[] key) {
        if (key.length < HASH_BYTES || idBytes.length == 0) {
            return false;
        }
        // hash the id text and compare its prefix bytes with the start of the key
        byte[] expected = hashBytes(hash(new String(idBytes, StandardCharsets.UTF_8)));
        for (int i = 0; i < HASH_BYTES; i++) {
            if (expected[i] != key[i]) {
                return false;
            }
        }
        return true;
    }

    /** The region a hash belongs to: {@code floorMod(hash, regionCount)}. */
    public static int regionOfHash(long hash, int regionCount) {
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount must be positive, got " + regionCount);
        }
        return (int) Math.floorMod(hash, regionCount);
    }

    /** Reads the big-endian long stored at {@code offset}. */
    public static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xffL);
        }
        return value;
    }

    /** Writes {@code value} big-endian at {@code offset} (order preserving for unsigned compare). */
    public static void putLong(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + i] = (byte) (value >>> (56 - 8 * i));
        }
    }
}
