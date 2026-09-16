package com.pengcheng.phoenixgraph.hnsw;

/**
 * Creates the layer edge classes an index needs while it grows.
 *
 * <p>The number of layers follows the dataset size ({@code ceil(log2 n) + 1}), so
 * a graph that starts empty cannot declare them all up front. A dynamic index
 * asks its provisioner for the layers it is about to write to, and the
 * implementation adds the missing edge classes to the schema (see
 * {@code VectorIndexSchemas.ensureLayer}). Ids must stay stable, otherwise the
 * storage bins - which embed the edge schema id - would move.
 */
@FunctionalInterface
public interface HnswLayerProvisioner {

    /** No-op provisioner: the schema must already declare every layer the index uses. */
    static HnswLayerProvisioner none() {
        return (vertexName, vectorProperty, level) -> {
        };
    }

    /**
     * Makes sure layer {@code level} of a vector property exists.
     *
     * @param vertexName     vertex type owning the vector property
     * @param vectorProperty the vector property
     * @param level          layer that is about to be written
     */
    void ensureLayer(String vertexName, String vectorProperty, int level);
}
