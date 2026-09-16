package com.pengcheng.phoenixgraph.hnsw;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only {@link HnswStore} over the graph query layer: layer {@code l} is read
 * as the bidirectional edge class {@code vector_<l>} of the indexed vertex type.
 *
 * <p>This is the store a search uses - it never writes, so {@link #linkAll} and
 * {@link #unlink} are unsupported. The layer edges carry no properties of their
 * own, so the reads project nothing but the edge id, the far endpoint and the
 * timestamps.
 */
public final class GraphQueryHnswStore implements HnswStore {

    private final GraphQuery graphQuery;
    private final String vertexName;

    public GraphQueryHnswStore(GraphQuery graphQuery, String vertexName) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.vertexName = Objects.requireNonNull(vertexName, "vertexName");
    }

    @Override
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        List<EdgeQueryResult> edges = graphQuery.readEdges(vertexId, vertexName,
                HnswLevels.edgeName(level), EdgeSide.BOTH, List.of());
        List<HnswNeighbor> out = new ArrayList<>(edges.size());
        for (EdgeQueryResult edge : edges) {
            out.add(new HnswNeighbor(edge.edgeId(), edge.otherVertexId()));
        }
        return out;
    }

    @Override
    public List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        if (vertexIds.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> edges = graphQuery.readEdgesBatch(HnswLevels.edgeName(level),
                EdgeSide.BOTH, vertexIds, List.of());
        List<List<HnswNeighbor>> out = new ArrayList<>(vertexIds.size());
        for (List<EdgeQueryResult> perVertex : edges) {
            List<HnswNeighbor> neighbours = new ArrayList<>(perVertex.size());
            for (EdgeQueryResult edge : perVertex) {
                neighbours.add(new HnswNeighbor(edge.edgeId(), edge.otherVertexId()));
            }
            out.add(neighbours);
        }
        return out;
    }

    @Override
    public void linkAll(Object from, List<Object> to, int level) {
        throw new UnsupportedOperationException("The query-layer HNSW store is read-only");
    }

    @Override
    public void unlink(String edgeId, Object from, Object to, int level) {
        throw new UnsupportedOperationException("The query-layer HNSW store is read-only");
    }
}
