package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Execution context handed to every {@link GremlinStep}: the storage query layer,
 * the neighbour-vertex schema resolver, the fan-out batch size, the property
 * materialization flag and a per-query vertex cache.
 *
 * <p>The cache is scoped to a single query execution: the first read of a vertex
 * is stored, and later reads of the same vertex id (in the same or a later hop)
 * reuse it. Duplicate ids within one batch are also collapsed so only one
 * storage query is issued per unique id.
 */
public final class GremlinContext {

    /** Default fan-out batch size (parallel lookups are on by default). */
    public static final int DEFAULT_BATCH_SIZE = 10;

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver vertexResolver;
    private final Map<Object, VertexQueryResult> vertexCache = new LinkedHashMap<>();
    private int batchSize = DEFAULT_BATCH_SIZE;
    private List<String> readProperties = List.of(); // empty = all, null = skip read
    private boolean cacheEnabled = true;

    public GremlinContext(GraphQuery graphQuery, NeighborVertexResolver vertexResolver) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.vertexResolver = vertexResolver == null ? NeighborVertexResolver.none() : vertexResolver;
    }

    public GraphQuery graphQuery() {
        return graphQuery;
    }

    public NeighborVertexResolver vertexResolver() {
        return vertexResolver;
    }

    public int batchSize() {
        return batchSize;
    }

    /** Sets the fan-out batch size; {@code 1} means serial, larger values run in parallel. */
    public void batchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be >= 1");
        }
        this.batchSize = batchSize;
    }

    /**
     * The property names to read for vertices that a hop materializes:
     * {@code null} means skip the read (only id/label), an empty list means read
     * all properties, a non-empty list reads only those property bins.
     */
    public List<String> readProperties() {
        return readProperties;
    }

    public void readProperties(List<String> readProperties) {
        this.readProperties = readProperties;
    }

    /** Whether the per-query vertex cache is enabled (on by default). */
    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    public void cacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * Reads vertices, de-duplicating ids and reusing previously read ids from the
     * cache. Only unique ids not yet cached are queried against the storage; the
     * result preserves the request order (duplicates included).
     */
    public List<VertexQueryResult> readVerticesCached(String vertexName, List<Object> vertexIds,
                                                      List<String> propertyNames) {
        if (!cacheEnabled) {
            return graphQuery.readVertices(vertexName, vertexIds, propertyNames);
        }
        if (vertexIds.isEmpty()) {
            return List.of();
        }

        // De-duplicate, preserving first-occurrence order.
        List<Object> uniqueIds = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object id : vertexIds) {
            if (seen.add(id)) {
                uniqueIds.add(id);
            }
        }

        Map<Object, VertexQueryResult> byId = new LinkedHashMap<>();
        List<Object> toFetch = new ArrayList<>();
        for (Object id : uniqueIds) {
            if (vertexCache.containsKey(id)) {
                byId.put(id, vertexCache.get(id));
            } else {
                toFetch.add(id);
            }
        }

        if (!toFetch.isEmpty()) {
            List<VertexQueryResult> fetched = graphQuery.readVertices(vertexName, toFetch, propertyNames);
            for (int i = 0; i < toFetch.size(); i++) {
                VertexQueryResult vertex = fetched.get(i);
                vertexCache.put(toFetch.get(i), vertex);
                byId.put(toFetch.get(i), vertex);
            }
        }

        List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
        for (Object id : vertexIds) {
            out.add(byId.get(id));
        }
        return out;
    }
}
