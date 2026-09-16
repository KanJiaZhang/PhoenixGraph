package com.pengcheng.phoenixgraph.hnsw;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Vector conversions between the heap form and the stored/transported shapes. */
class VectorCodecTest {

    @Test
    void listFormRoundTrips() {
        float[] vector = {0.5f, -1.25f, 3f};
        List<Double> stored = VectorCodec.toList(vector);
        assertEquals(List.of(0.5d, -1.25d, 3.0d), stored);
        assertArrayEquals(vector, VectorCodec.toFloats(stored));
    }

    @Test
    void acceptsTheShapesAStorageBackendMayReturn() {
        assertArrayEquals(new float[] {1f, 2f}, VectorCodec.toFloats(new double[] {1d, 2d}));
        assertArrayEquals(new float[] {1f, 2f}, VectorCodec.toFloats(new float[] {1f, 2f}));
        assertArrayEquals(new float[] {1f, 2f},
                VectorCodec.toFloats(VectorCodec.toBytes(new float[] {1f, 2f})));
        assertArrayEquals(new float[] {1f, 2f}, VectorCodec.toFloats(List.of(1, 2L, 2.0f).subList(0, 2)));
        assertNull(VectorCodec.toFloats(null));
        assertNull(VectorCodec.toFloats("not a vector"));
        assertNull(VectorCodec.toFloats(List.of("x", "y")));
    }

    @Test
    void bytesAreLittleEndianFloat32() {
        byte[] bytes = VectorCodec.toBytes(new float[] {1f});
        assertEquals(4, bytes.length);
        assertEquals(0x00, bytes[0] & 0xff);
        assertEquals(0x00, bytes[1] & 0xff);
        assertEquals(0x80, bytes[2] & 0xff);
        assertEquals(0x3f, bytes[3] & 0xff); // 1.0f = 0x3F800000, little endian
    }

    @Test
    void parsesAndFormatsText() {
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, VectorCodec.parse("0.1, 0.2 0.3"));
        assertArrayEquals(new float[] {0.1f, 0.2f}, VectorCodec.parse("[0.1,0.2]"));
        assertEquals(0, VectorCodec.parse("").length);
        assertEquals("[0.10, 0.20]", VectorCodec.format(new float[] {0.1f, 0.2f}, 2));
    }

    @Test
    void heapSourceKeepsInsertionOrderAndDimensions() {
        HeapHnswVectorSource source = new HeapHnswVectorSource();
        source.put("a", new float[] {1f, 2f});
        source.put("b", new float[] {3f, 4f, 5f});
        assertEquals(List.of("a", "b"), source.ids());
        assertEquals(2, source.size());
        assertEquals(3, source.dimensions());
        assertArrayEquals(new float[] {1f, 2f}, source.vector("a"));
        assertNull(source.vector("c"));
        assertEquals(2, source.vectors(new ArrayList<>(List.of("a", "b"))).size());
    }
}
