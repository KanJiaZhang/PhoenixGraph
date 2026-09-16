package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.hnsw.VectorCodec;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexPropertyValue;
import com.pengcheng.phoenixgraph.storage.aerospike.VertexUpsert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Reader/seeder for the SIFT small dataset (the {@code .fvecs} / {@code .ivecs}
 * layout of the texmex corpus, see {@code tools/download-siftsmall-dataset.sh}).
 *
 * <p>Each record is a little-endian {@code int32} dimension followed by that many
 * {@code float32} values, so a file is a flat list of equally sized vectors.
 * {@code siftsmall_base.fvecs} holds the 10,000 x 128 base vectors (the dataset to
 * index), {@code siftsmall_query.fvecs} the 100 x 128 queries and
 * {@code siftsmall_groundtruth.ivecs} the exact 100 nearest base ids per query
 * (used to measure the recall of the graph search).
 */
final class HnswDatasetLoader {

    /** Vector dimension of the SIFT small dataset. */
    static final int DIMENSIONS = 128;

    /** Property index of the display name in the HNSW demo schema. */
    static final int NAME_PROPERTY = 1;

    /** Property index of the vector in the HNSW demo schema. */
    static final int VECTOR_PROPERTY = 2;

    private HnswDatasetLoader() {
    }

    /** The loaded dataset: base vectors (rows to index), queries and ground truth. */
    record Dataset(List<float[]> vectors, List<float[]> queries, List<int[]> groundTruth) {

        int size() {
            return vectors.size();
        }

        /**
         * Id of a row ({@code 0..size-1}) as stored in the graph. Ids are strings,
         * like in the other demo graphs: the id is the Aerospike record key, and a
         * Gremlin query always addresses vertices by string ({@code g.V('1000')}).
         */
        static String idOf(int row) {
            return String.valueOf(row);
        }
    }

    /** Loads up to {@code limit} base rows ({@code <= 0} = all) plus the queries and ground truth. */
    static Dataset load(Path directory, int limit) throws IOException {
        List<float[]> vectors = readFvecs(directory.resolve("siftsmall_base.fvecs"), limit);
        List<float[]> queries = readFvecs(directory.resolve("siftsmall_query.fvecs"), 0);
        List<int[]> groundTruth = readIvecs(directory.resolve("siftsmall_groundtruth.ivecs"), 0);
        return new Dataset(vectors, queries, groundTruth);
    }

    /** Reads at most {@code limit} vectors from a {@code .fvecs} file ({@code <= 0} = all). */
    static List<float[]> readFvecs(Path file, int limit) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<float[]> out = new ArrayList<>();
        while (buffer.remaining() >= Integer.BYTES) {
            int dimensions = buffer.getInt();
            if (dimensions <= 0 || buffer.remaining() < dimensions * Float.BYTES) {
                break;
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = buffer.getFloat();
            }
            out.add(vector);
            if (limit > 0 && out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Reads the {@code int32} result lists of an {@code .ivecs} file ({@code limit <= 0} = all). */
    static List<int[]> readIvecs(Path file, int limit) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> out = new ArrayList<>();
        while (buffer.remaining() >= Integer.BYTES) {
            int count = buffer.getInt();
            if (count <= 0 || buffer.remaining() < count * Integer.BYTES) {
                break;
            }
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = buffer.getInt();
            }
            out.add(ids);
            if (limit > 0 && out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /**
     * Writes the base vectors as vertices of the HNSW demo schema: id = row number,
     * {@code name} (display) and {@code vector} (the {@code list<double>} property
     * the index reads).
     *
     * @param progress called with the number of rows written so far (every batch)
     */
    static int seedVertices(AerospikeVertexStore vertexStore, VertexSchema schema, List<float[]> vectors,
                            int batchSize, IntConsumer progress) {
        long now = System.currentTimeMillis();
        int written = 0;
        for (int start = 0; start < vectors.size(); start += batchSize) {
            int end = Math.min(start + batchSize, vectors.size());
            List<VertexUpsert> upserts = new ArrayList<>(end - start);
            for (int row = start; row < end; row++) {
                Map<Integer, VertexPropertyValue> properties = new LinkedHashMap<>();
                properties.put(NAME_PROPERTY, new VertexPropertyValue(nameOf(row), now));
                properties.put(VECTOR_PROPERTY,
                        new VertexPropertyValue(VectorCodec.toList(vectors.get(row)), now));
                upserts.add(new VertexUpsert(Dataset.idOf(row), schema, properties, now, now));
            }
            vertexStore.upsertVertices(upserts);
            written = end;
            if (progress != null) {
                progress.accept(written);
            }
        }
        return written;
    }

    /** Display name of a row, e.g. {@code sift-4242}. */
    static String nameOf(int row) {
        return "sift-" + row;
    }
}
