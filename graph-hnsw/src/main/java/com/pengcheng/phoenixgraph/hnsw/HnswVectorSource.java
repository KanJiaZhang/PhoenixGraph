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

    /**
     * Vectors <em>and</em> extra properties of several vertices, read together.
     *
     * <p>A filtered search needs the properties of every vertex it expands (e.g. the
     * {@code category} a predicate tests). Reading them in the same batch as the
     * vectors keeps a filtered search at the same number of storage round trips as
     * an unfiltered one, so implementations backed by the query layer should project
     * both in one call. The default reads the vectors and reports no properties,
     * which makes filtering fall back to "everything matches".
     *
     * @param vertexIds       ids to read, in order
     * @param extraProperties properties to fetch on top of the vector ({@code null} = none)
     */
    default HnswElements elements(List<Object> vertexIds, List<String> extraProperties) {
        return new HnswElements(vectors(vertexIds), List.of());
    }

    /** Number of dimensions, or {@code 0} when unknown. */
    default int dimensions() {
        return 0;
    }
}
