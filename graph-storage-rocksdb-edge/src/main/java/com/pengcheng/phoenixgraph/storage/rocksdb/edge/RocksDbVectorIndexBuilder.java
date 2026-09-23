package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.hnsw.HeapHnswVectorSource;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswInsertResult;
import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswNeighbor;
import com.pengcheng.phoenixgraph.hnsw.VectorCodec;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a vector index over a graph served by RocksDB-Edge: read every vector, drop the old layers, insert
 * the vertices.
 *
 * <p>The counterpart of the rebuild half of {@code VectorIndexManager}, which does the same for Aerospike
 * (where the ids have to be walked out of the layer-0 graph and the vectors read back in batches). Here the
 * type can be <b>scanned</b> - a RocksDB vertex type is a column family, so one iteration returns every
 * account with its vector and both the id list and the vectors come from that single pass.
 *
 * <p>Dropping the old layers matters even though the rebuild overwrites the links: a rebuilt index must not
 * keep links of the graph it was built from, or a search would walk neighbours that are no longer the
 * nearest. The layer classes belong to the graph, so their rows are removed vertex by vertex (the same
 * approach the Aerospike path uses, which has no cheaper way either).
 */
public final class RocksDbVectorIndexBuilder {

    /** What one (re)build did. */
    public record Built(String vertexName, String vectorProperty, int vertices, int layers, long links,
                        long pruned, long dropped, double seconds) {
    }

    private RocksDbVectorIndexBuilder() {
    }

    /** Whether this graph query can enumerate a vertex type with a property (the build needs it). */
    public static boolean canBuild(GraphQuery query) {
        return query != null && query.supportsScan();
    }

    /**
     * Rebuilds the index of one vector property: reads the vectors of every vertex of the type, drops the
     * layer rows, and inserts the vertices again.
     *
     * @param store    the RocksDB edge store holding (and receiving) the layer edges
     * @param query    the graph's read path (used for the scan and for vector lookups)
     * @param schema   the graph schema (layer edge classes are added on demand)
     * @param property the vector property to index
     */
    public static Built rebuild(RocksDbEdgeStore store, GraphQuery query, GraphSchema schema,
                                VectorIndexSchemas.VectorProperty property) {
        if (!canBuild(query)) {
            throw new QueryException("Building the vector index of " + property.vertexName() + "."
                    + property.propertyName() + " needs a scan of the vertex type, which "
                    + query.getClass().getSimpleName() + " does not support");
        }
        long started = System.currentTimeMillis();
        String vertexName = property.vertexName();
        String propertyName = property.propertyName();

        // 1. one pass over the vertex type: the ids and the vectors come from the same scan
        HeapHnswVectorSource vectors = new HeapHnswVectorSource();
        List<Object> reloaded = new ArrayList<>();
        for (VertexQueryResult vertex : query.scanVertices(vertexName, List.of(propertyName))) {
            float[] vector = VectorCodec.toFloats(vertex.properties().get(propertyName));
            if (vector == null) {
                continue; // a vertex without this vector is not part of the index
            }
            vectors.put(vertex.vertexId(), vector);
            reloaded.add(vertex.vertexId());
        }

        // 2. the old layers go, whatever the new build then writes
        HnswIndex existing = RocksDbHnswIndexFactory.builder(store, query, schema, property, false);
        long dropped = dropLayers(store, schema, property, reloaded,
                Math.max(existing.stats().topLevel() + 1, property.declaredLevels()));

        // 3. build from the vectors in memory (no per-insert read of the vector property)
        HnswIndex index = RocksDbHnswIndexFactory.builder(store, query, schema, property, true);
        long links = 0;
        long pruned = 0;
        for (Object id : reloaded) {
            HnswInsertResult result = index.insert(id);
            links += result.links();
            pruned += result.pruned();
        }
        index.flush();
        return new Built(vertexName, propertyName, reloaded.size(), index.topLevel() + 1, links, pruned,
                dropped, (System.currentTimeMillis() - started) / 1000.0);
    }

    /** Removes the layer rows of the given vertices and returns how many were removed. */
    private static long dropLayers(RocksDbEdgeStore store, GraphSchema schema,
                                   VectorIndexSchemas.VectorProperty property, List<Object> vertexIds,
                                   int layers) {
        long dropped = 0;
        Map<String, EdgeSchema> schemas = new LinkedHashMap<>();
        for (int level = 0; level < Math.max(layers, 1); level++) {
            VectorIndexSchemas.ensureLayer(schema, property.vertexName(), property.propertyName(), level);
            EdgeSchema layer = VectorIndexSchemas.layerSchema(schema, property.vertexName(),
                    property.propertyName(), level);
            if (layer != null) {
                schemas.put(HnswLevels.edgeName(property.propertyName(), level), layer);
            }
        }
        for (EdgeSchema layer : schemas.values()) {
            byte direction = RocksDbEdgeStore.directionCode(
                    com.pengcheng.phoenixgraph.query.storage.EdgeSide.BOTH, layer.direction());
            List<List<RocksDbStoredEdge>> rows = store.readEdgesBatch(layer.name(), direction, TimeRange.ALL,
                    vertexIds, List.of());
            for (int i = 0; i < rows.size(); i++) {
                Object from = vertexIds.get(i);
                for (RocksDbStoredEdge edge : rows.get(i)) {
                    // a bidirectional row is stored once per pair; deleting from one endpoint removes it
                    if (edge.otherVertexId() != null
                            && String.valueOf(edge.otherVertexId()).compareTo(String.valueOf(from)) > 0) {
                        continue; // the other endpoint removes the same row
                    }
                    if (store.deleteEdge(layer, edge.edgeId(), edge.createdTime(), from, edge.otherVertexId())) {
                        dropped++;
                    }
                }
            }
        }
        return dropped;
    }

    /** The layer-0 neighbour count of the index' entry point, for a report or a check. */
    public static int entryLinks(HnswIndex index) {
        List<HnswNeighbor> neighbours = index.entryPoints().isEmpty()
                ? List.of()
                : index.neighbours(index.entryPoints().get(0).vertexId(), 0);
        return neighbours.size();
    }
}
