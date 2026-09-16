package com.pengcheng.phoenixgraph.hnsw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversions between the raw {@code float[]} an index computes with and the
 * shapes a vector is stored or transported in: a list of numbers (the graph
 * property form), or a little-endian {@code float32} blob.
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    /** Converts a stored property value to {@code float[]}, or {@code null} when it is not a vector. */
    public static float[] toFloats(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof float[] floats) {
            return floats;
        }
        if (value instanceof double[] doubles) {
            float[] out = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                out[i] = (float) doubles[i];
            }
            return out;
        }
        if (value instanceof byte[] bytes) {
            return fromBytes(bytes);
        }
        if (value instanceof List<?> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Number number)) {
                    return null;
                }
                out[i] = number.floatValue();
            }
            return out;
        }
        return null;
    }

    /** Converts a vector to the list form stored as a vertex property. */
    public static List<Double> toList(float[] vector) {
        List<Double> out = new ArrayList<>(vector.length);
        for (float value : vector) {
            out.add((double) value);
        }
        return out;
    }

    /** Parses a comma/space separated vector, e.g. {@code "0.1, 0.2, 0.3"}. */
    public static float[] parse(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        String[] parts = text.replace("[", "").replace("]", "").trim().split("[,\\s]+");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i]);
        }
        return out;
    }

    /** Renders a vector as {@code [0.1, 0.2, ...]}. */
    public static String format(float[] vector, int decimals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%." + decimals + "f", vector[i]));
        }
        return sb.append(']').toString();
    }

    /** Little-endian {@code float32} blob (the fvecs layout of the SIFT datasets). */
    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** Reads a little-endian {@code float32} blob. */
    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }
}
