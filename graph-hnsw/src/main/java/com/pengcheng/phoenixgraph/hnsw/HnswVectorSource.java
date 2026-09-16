package com.pengcheng.phoenixgraph.hnsw;

import java.util.List;

/**
 * Port for the vectors an HNSW index is built over.
 *
 * <p>Implementations read the vector of one vertex (or of a batch of vertices)
 * from wherever the vectors live: a vertex property in the graph store, an
 * in-memory heap while building, a file, ... Batch reads are what the search
 * uses to expand a node, so implementations should serve them with one storage
 * round trip where possible.
 */
public interface HnswVectorSource {

    /** Vector of one vertex, or {@code null} when the vertex (or its vector) is absent. */
    float[] vector(Object vertexId);

    /** Vectors of several vertices, in the requested order ({@code null} entries for missing ones). */
    List<float[]> vectors(List<Object> vertexIds);

    /** Number of dimensions, or {@code 0} when unknown. */
    default int dimensions() {
        return 0;
    }
}
