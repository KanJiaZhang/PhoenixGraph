package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes a single property value.
 *
 * <p>The layout is a one byte type tag followed by the payload, so a value can be
 * decoded without consulting the schema:
 *
 * <pre>
 * null      0x00
 * boolean   0x01 + 1 byte
 * int       0x02 + 4 bytes
 * long      0x03 + 8 bytes
 * float     0x04 + 4 bytes
 * double    0x05 + 8 bytes
 * string    0x06 + length + UTF-8
 * list      0x07 + count + tagged elements
 * map       0x08 + count + (length + key + tagged value) ...
 * text      0x09 + length + UTF-8   (fallback for values without a binary form)
 * </pre>
 *
 * <p>Numbers keep their Java type ({@code Integer} stays an int, {@code Long} a long),
 * because the query layer compares property values numerically and prints them as they
 * were written. A value of an unsupported type is stored as its text form rather than
 * failing a write.
 */
public final class RocksDbPropertyCodec {

    public static final byte TYPE_NULL = 0x00;
    public static final byte TYPE_BOOLEAN = 0x01;
    public static final byte TYPE_INT = 0x02;
    public static final byte TYPE_LONG = 0x03;
    public static final byte TYPE_FLOAT = 0x04;
    public static final byte TYPE_DOUBLE = 0x05;
    public static final byte TYPE_STRING = 0x06;
    public static final byte TYPE_LIST = 0x07;
    public static final byte TYPE_MAP = 0x08;
    public static final byte TYPE_TEXT = 0x09;

    private RocksDbPropertyCodec() {
    }

    /** Encodes a property value. */
    public static byte[] encode(Object value) {
        RocksDbBytes.Writer writer = new RocksDbBytes.Writer();
        write(writer, value);
        return writer.toByteArray();
    }

    /** Appends the tagged value to a writer (used to embed values in records). */
    public static void write(RocksDbBytes.Writer writer, Object value) {
        if (value == null) {
            writer.writeByte(TYPE_NULL);
        } else if (value instanceof Boolean bool) {
            writer.writeByte(TYPE_BOOLEAN).writeBoolean(bool);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            writer.writeByte(TYPE_INT).writeInt(((Number) value).intValue());
        } else if (value instanceof Long) {
            writer.writeByte(TYPE_LONG).writeLong((Long) value);
        } else if (value instanceof Float) {
            writer.writeByte(TYPE_FLOAT).writeFloat((Float) value);
        } else if (value instanceof Double) {
            writer.writeByte(TYPE_DOUBLE).writeDouble((Double) value);
        } else if (value instanceof String text) {
            writer.writeByte(TYPE_STRING).writeString(text);
        } else if (value instanceof Map<?, ?> map) {
            writer.writeByte(TYPE_MAP).writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                writer.writeString(String.valueOf(entry.getKey()));
                write(writer, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            writer.writeByte(TYPE_LIST).writeInt(list.size());
            for (Object element : list) {
                write(writer, element);
            }
        } else if (value instanceof Iterable<?> iterable) {
            List<Object> elements = new ArrayList<>();
            iterable.forEach(elements::add);
            writer.writeByte(TYPE_LIST).writeInt(elements.size());
            for (Object element : elements) {
                write(writer, element);
            }
        } else if (value.getClass().isArray()) {
            Object[] elements = (Object[]) value;
            writer.writeByte(TYPE_LIST).writeInt(elements.length);
            for (Object element : elements) {
                write(writer, element);
            }
        } else {
            writer.writeByte(TYPE_TEXT).writeString(String.valueOf(value));
        }
    }

    /** Decodes a property value written by {@link #encode}. */
    public static Object decode(byte[] bytes) {
        return read(new RocksDbBytes.Reader(bytes));
    }

    /** Reads the tagged value at the reader's position. */
    public static Object read(RocksDbBytes.Reader reader) {
        byte type = reader.readByte();
        return switch (type) {
            case TYPE_NULL -> null;
            case TYPE_BOOLEAN -> reader.readBoolean();
            case TYPE_INT -> reader.readInt();
            case TYPE_LONG -> reader.readLong();
            case TYPE_FLOAT -> reader.readFloat();
            case TYPE_DOUBLE -> reader.readDouble();
            case TYPE_STRING, TYPE_TEXT -> reader.readString();
            case TYPE_LIST -> {
                int count = reader.readInt();
                List<Object> list = new ArrayList<>(Math.max(count, 0));
                for (int i = 0; i < count; i++) {
                    list.add(read(reader));
                }
                yield list;
            }
            case TYPE_MAP -> {
                int count = reader.readInt();
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = reader.readString();
                    map.put(key, read(reader));
                }
                yield map;
            }
            default -> throw new RocksDbException("Unknown property value type 0x"
                    + Integer.toHexString(type & 0xff));
        };
    }
}
