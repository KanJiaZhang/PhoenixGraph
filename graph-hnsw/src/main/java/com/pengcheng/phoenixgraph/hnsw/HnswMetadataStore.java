package com.pengcheng.phoenixgraph.hnsw;

/**
 * Port for the HNSW metadata / entry-point registry.
 *
 * <p>Backed by a single dedicated key/value record (an Aerospike record, a file,
 * ...) that holds the index description plus the up-to-{@value
 * HnswLevels#MAX_ENTRY_POINTS} entry points. Building an index rewrites it
 * whenever the top layers change; searching reads it once.
 */
public interface HnswMetadataStore {

    /** Reads the index description, or {@code null} when the index does not exist yet. */
    HnswMetadata read();

    /** Writes (replaces) the index description. */
    void write(HnswMetadata metadata);

    /** No-op registry, for a purely in-memory (test) index. */
    static HnswMetadataStore inMemory() {
        return new HnswMetadataStore() {
            private HnswMetadata metadata;

            @Override
            public HnswMetadata read() {
                return metadata;
            }

            @Override
            public void write(HnswMetadata value) {
                this.metadata = value;
            }
        };
    }
}
