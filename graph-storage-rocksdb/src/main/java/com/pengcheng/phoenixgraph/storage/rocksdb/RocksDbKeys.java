package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Key layouts of the RocksDB storage schemes.
 *
 * <p>Every key starts with the fixed 8 byte {@link RocksDbHash hash} of the owning
 * vertex id, so the key space is spread evenly and sorts into regions:
 *
 * <pre>
 * vertex    hash(vertexId) : vertexId
 * edge      hash(vertexId) : direction : vertexId : createdTime : edgeId
 * </pre>
 *
 * <p>An edge key repeats the vertex id after the direction on purpose: the id must be
 * part of the <i>prefix</i> so that "the edges of this vertex in this direction" is a
 * single range scan, while {@code createdTime} and {@code edgeId} follow as an
 * order-preserving suffix - {@code createdTime} as a fixed width big-endian long, so a
 * time window is a {@code seek} to its start instead of a filter over every edge.
 *
 * <p>Direction codes are {@code 0} undirected, {@code 1} outgoing and {@code 2} incoming.
 */
public final class RocksDbKeys {

    /** Undirected edge: the same row is read from either endpoint. */
    public static final byte DIRECTION_UNDIRECTED = 0;

    /** Outgoing edge of the vertex stored in the key. */
    public static final byte DIRECTION_OUT = 1;

    /** Incoming edge of the vertex stored in the key. */
    public static final byte DIRECTION_IN = 2;

    private static final byte SEPARATOR = (byte) ':';

    private RocksDbKeys() {
    }

    /** The vertex key: {@code hash(vertexId) : vertexId}. */
    public static byte[] vertexKey(Object vertexId) {
        byte[] id = RocksDbBytes.utf8(String.valueOf(vertexId));
        byte[] key = new byte[RocksDbHash.HASH_BYTES + 1 + id.length];
        System.arraycopy(RocksDbHash.hashBytes(vertexId), 0, key, 0, RocksDbHash.HASH_BYTES);
        key[RocksDbHash.HASH_BYTES] = SEPARATOR;
        System.arraycopy(id, 0, key, RocksDbHash.HASH_BYTES + 1, id.length);
        return key;
    }

    /**
     * The prefix identifying "the edges of {@code vertexId} in {@code direction}":
     * {@code hash(vertexId) : direction : vertexId}.
     */
    public static byte[] edgePrefix(Object vertexId, byte direction) {
        byte[] id = RocksDbBytes.utf8(String.valueOf(vertexId));
        byte[] prefix = new byte[RocksDbHash.HASH_BYTES + 1 + 1 + id.length];
        System.arraycopy(RocksDbHash.hashBytes(vertexId), 0, prefix, 0, RocksDbHash.HASH_BYTES);
        prefix[RocksDbHash.HASH_BYTES] = SEPARATOR;
        prefix[RocksDbHash.HASH_BYTES + 1] = direction;
        System.arraycopy(id, 0, prefix, RocksDbHash.HASH_BYTES + 2, id.length);
        return prefix;
    }

    /** The edge key: {@code edgePrefix(vertexId, direction) : createdTime : edgeId}. */
    public static byte[] edgeKey(Object vertexId, byte direction, long createdTime, Object edgeId) {
        byte[] prefix = edgePrefix(vertexId, direction);
        byte[] id = RocksDbBytes.utf8(String.valueOf(edgeId));
        byte[] key = new byte[prefix.length + 8 + id.length];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        RocksDbHash.putLong(key, prefix.length, createdTime);
        System.arraycopy(id, 0, key, prefix.length + 8, id.length);
        return key;
    }

    /** The first key of the window {@code [fromTime, ...)} for a vertex/direction prefix. */
    public static byte[] edgeKeyFrom(byte[] prefix, long fromTime) {
        byte[] key = new byte[prefix.length + 8];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        RocksDbHash.putLong(key, prefix.length, fromTime);
        return key;
    }

    /** The exclusive end of every key starting with {@code prefix} (last byte + 1, carrying). */
    public static byte[] prefixEnd(byte[] prefix) {
        byte[] end = prefix.clone();
        for (int i = end.length - 1; i >= 0; i--) {
            if ((end[i] & 0xff) != 0xff) {
                end[i]++;
                return end;
            }
            end[i] = 0;
        }
        // all bytes were 0xff: no upper bound exists, the caller scans to the end
        return null;
    }

    /** The creation time stored in an edge key (the 8 bytes right after the prefix). */
    public static long createdTimeOf(byte[] key, byte[] prefix) {
        if (key.length < prefix.length + 8) {
            throw new RocksDbException("Malformed edge key of " + key.length + " bytes");
        }
        return RocksDbHash.readLong(key, prefix.length);
    }

    /** The edge id stored at the end of an edge key. */
    public static String edgeIdOf(byte[] key, byte[] prefix) {
        int from = prefix.length + 8;
        if (key.length < from) {
            throw new RocksDbException("Malformed edge key of " + key.length + " bytes");
        }
        return new String(key, from, key.length - from, StandardCharsets.UTF_8);
    }

    /** Whether {@code key} starts with {@code prefix}. */
    public static boolean hasPrefix(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Concatenates byte arrays (used to build compound prefixes). */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * The id part of a vertex key ({@code hash(vertexId) : vertexId}).
     *
     * <p>A vertex key is fully parseable: the hash is fixed width and the id is everything after it,
     * which is what lets a scan of a vertex table walk the table instead of being handed ids.
     */
    public static Object vertexIdOf(byte[] key) {
        int from = RocksDbHash.HASH_BYTES + 1;
        if (key.length <= from) {
            throw new RocksDbException("Malformed vertex key of " + key.length + " bytes");
        }
        return RocksDbBytes.text(java.util.Arrays.copyOfRange(key, from, key.length));
    }

    /** The direction byte of an edge key ({@code hash : direction : vertexId : ...}). */
    public static byte directionOfKey(byte[] key) {
        int at = RocksDbHash.HASH_BYTES + 1;
        if (key.length <= at) {
            throw new RocksDbException("Malformed edge key of " + key.length + " bytes");
        }
        return key[at];
    }

    /**
     * The two parts of an edge key that are not fixed width: the vertex the row belongs to, and the
     * edge id.
     */
    public record EdgeKeyParts(String ownerVertexId, String edgeId) {
    }

    /**
     * The owner and the edge id of an edge row, given the creation time the row's value holds.
     *
     * <p>The key is {@code hash(vertexId) : direction : vertexId : createdTime : edgeId}, and both
     * the vertex id and the edge id are variable length - so the middle of an edge key cannot be
     * split by position, and an edge row cannot name its owner the way {@link #vertexIdOf} does. The
     * creation time sits in the row's value and its eight bytes appear exactly once in the key, so
     * the fields are what lies on either side of it; the hash is then checked against the owner,
     * which is what makes the split certain rather than a guess.
     *
     * @param key         the edge-table key
     * @param createdTime the edge's creation time (from the row's value)
     */
    public static EdgeKeyParts edgeKeyParts(byte[] key, long createdTime) {
        for (int at = RocksDbHash.HASH_BYTES + 2; at + 8 <= key.length; at++) {
            if (RocksDbHash.readLong(key, at) != createdTime) {
                continue;
            }
            byte[] owner = java.util.Arrays.copyOfRange(key, RocksDbHash.HASH_BYTES + 2, at);
            if (owner.length == 0 || !RocksDbHash.hashEquals(owner, key)) {
                continue;
            }
            return new EdgeKeyParts(RocksDbBytes.text(owner),
                    RocksDbBytes.text(java.util.Arrays.copyOfRange(key, at + 8, key.length)));
        }
        throw new RocksDbException("Edge key of " + key.length + " bytes does not carry creation time "
                + createdTime);
    }
}
