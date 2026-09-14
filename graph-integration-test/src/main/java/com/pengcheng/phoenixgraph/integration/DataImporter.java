package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.batchload.BatchLoadResult;
import com.pengcheng.phoenixgraph.mapreduce.MapReduceCsvLoader;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Imports the generated CSV datasets using the MapReduce batch tool.
 */
public final class DataImporter {

    private final MapReduceCsvLoader loader;

    public DataImporter(AerospikeVertexStore vertexStore, AerospikeEdgeStore edgeStore, GraphSchema schema) {
        this.loader = new MapReduceCsvLoader(vertexStore, edgeStore, schema);
    }

    public BatchLoadResult importVertices(Path file) throws IOException {
        return loader.load(List.of(file));
    }

    public BatchLoadResult importEdges(Path file) throws IOException {
        return loader.load(List.of(file));
    }
}
