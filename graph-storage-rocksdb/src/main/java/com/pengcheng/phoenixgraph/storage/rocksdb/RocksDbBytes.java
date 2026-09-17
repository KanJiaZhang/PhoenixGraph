package com.pengcheng.phoenixgraph.storage.rocksdb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small growable byte writer and its matching reader.
 *
 * <p>Values are stored as a compact binary layout instead of JSON: property values and
 * edge rows are read on every hop, so the format only has to be fast, unambiguous and
 * stable. Numbers are fixed width (long/double 8 bytes) so that a key suffix can be
 * compared byte-wise to order by time.
 */
public final class RocksDbBytes {

    private RocksDbBytes() {
    }

    /** Writes the given strings as one value: length, then bytes. */
    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Decodes UTF-8 bytes. */
    public static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** An append-only byte buffer. */
    public static final class Writer {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer writeByte(int value) {
            out.write(value & 0xff);
            return this;
        }

        public Writer writeBoolean(boolean value) {
            return writeByte(value ? 1 : 0);
        }

        public Writer writeInt(int value) {
            out.write((value >>> 24) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write(value & 0xff);
            return this;
        }

        public Writer writeLong(long value) {
            writeInt((int) (value >>> 32));
            writeInt((int) value);
            return this;
        }

        public Writer writeDouble(double value) {
            return writeLong(Double.doubleToRawLongBits(value));
        }

        public Writer writeFloat(float value) {
            return writeInt(Float.floatToRawIntBits(value));
        }

        public Writer writeRaw(byte[] bytes) {
            out.writeBytes(bytes);
            return this;
        }

        /** Writes a length-prefixed byte string. */
        public Writer writeBytes(byte[] bytes) {
            writeInt(bytes.length);
            return writeRaw(bytes);
        }

        /** Writes a length-prefixed UTF-8 string. */
        public Writer writeString(String value) {
            return writeBytes(utf8(value));
        }

        public int size() {
            return out.size();
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** A forward-only reader over a byte array. */
    public static final class Reader {

        private final byte[] bytes;
        private int position;

        public Reader(byte[] bytes) {
            this(bytes, 0);
        }

        public Reader(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.position = offset;
        }

        public int position() {
            return position;
        }

        public int remaining() {
            return bytes.length - position;
        }

        public byte readByte() {
            if (remaining() < 1) {
                throw new RocksDbException("Truncated value: expected a byte at offset " + position);
            }
            return bytes[position++];
        }

        public boolean readBoolean() {
            return readByte() != 0;
        }

        public int readInt() {
            if (remaining() < 4) {
                throw new RocksDbException("Truncated value: expected an int at offset " + position);
            }
            int value = ((bytes[position] & 0xff) << 24) | ((bytes[position + 1] & 0xff) << 16)
                    | ((bytes[position + 2] & 0xff) << 8) | (bytes[position + 3] & 0xff);
            position += 4;
            return value;
        }

        public long readLong() {
            return ((long) readInt() << 32) | (readInt() & 0xffffffffL);
        }

        public double readDouble() {
            return Double.longBitsToDouble(readLong());
        }

        public float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        public byte[] readRaw(int length) {
            if (length < 0 || remaining() < length) {
                throw new RocksDbException("Truncated value: expected " + length + " bytes at offset " + position);
            }
            byte[] out = new byte[length];
            System.arraycopy(bytes, position, out, 0, length);
            position += length;
            return out;
        }

        /** Reads a length-prefixed byte string. */
        public byte[] readBytes() {
            return readRaw(readInt());
        }

        /** Reads a length-prefixed UTF-8 string. */
        public String readString() {
            return text(readBytes());
        }
    }
}
