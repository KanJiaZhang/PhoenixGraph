package com.pengcheng.phoenixgraph.storage.rocksdb;

/**
 * Encodes a vertex/edge id so that it survives the round trip through a key or a value.
 *
 * <p>The record key of a vertex is {@code hash(vertexId) : vertexId} - the id is part of
 * the key as text. Query results, however, must hand back the id in the type it was
 * written with ({@code 135} is a number, {@code account-42} is a string), so every stored
 * id also carries a one byte type tag.
 *
 * <pre>
 * 0  string
 * 1  long
 * 2  int
 * </pre>
 */
public final class RocksDbIdCodec {

    public static final byte TYPE_STRING = 0;
    public static final byte TYPE_LONG = 1;
    public static final byte TYPE_INT = 2;

    private RocksDbIdCodec() {
    }

    /** The type tag of an id value. */
    public static byte typeOf(Object id) {
        if (id instanceof Long) {
            return TYPE_LONG;
        }
        if (id instanceof Integer || id instanceof Short || id instanceof Byte) {
            return TYPE_INT;
        }
        return TYPE_STRING;
    }

    /** Writes {@code [type][text]} into a writer. */
    public static void write(RocksDbBytes.Writer writer, Object id) {
        writer.writeByte(typeOf(id));
        writer.writeString(String.valueOf(id));
    }

    /** Reads {@code [type][text]} from a reader into the original type. */
    public static Object read(RocksDbBytes.Reader reader) {
        return decode(reader.readByte(), reader.readString());
    }

    /** Rebuilds an id from its type tag and text form. */
    public static Object decode(byte type, String text) {
        return switch (type) {
            case TYPE_LONG -> {
                try {
                    yield Long.parseLong(text);
                } catch (NumberFormatException e) {
                    yield text;
                }
            }
            case TYPE_INT -> {
                try {
                    yield Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    yield text;
                }
            }
            default -> text;
        };
    }
}
