package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswSearchResult;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Built-in {@link GremlinStep} implementations.
 *
 * <p>Vertex/edge hops fan out over many vertices or edges. Those fan-out reads
 * are performed with the storage layer's native batch reads, chunked by
 * {@link GremlinContext#batchSize()} (tunable with the {@code batch(n)} /
 * {@code barrier(n)} steps): items are accumulated and queried
 * {@code batchSize} at a time.
 */
final class GremlinSteps {

    private GremlinSteps() {
    }

    // --- edge hops (outE / inE / bothE): vertices -> edges ---

    static GremlinStep outE(String edgeName) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.OUT);
    }

    static GremlinStep inE(String edgeName) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.IN);
    }

    static GremlinStep bothE(String edgeName) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.BOTH);
    }

    // --- vertex hops (out / in / both): vertices -> vertices ---

    static GremlinStep out(String edgeName) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.OUT);
    }

    static GremlinStep in(String edgeName) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.IN);
    }

    static GremlinStep both(String edgeName) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.BOTH);
    }

    // --- edge -> vertex ---

    /**
     * Moves from an edge to its other endpoint (the vertex the edge was reached
     * from is not carried by the traverser, so only the far end is known). Steps
     * like {@code bothE(label).filter(...).otherV()} hop to the filtered edges'
     * neighbours without going through every other edge class.
     */
    static GremlinStep otherV() {
        return (ctx, in) -> {
            if (in.isEmpty()) {
                return List.of();
            }
            List<EdgeQueryResult> edges = new ArrayList<>(in.size());
            for (Object o : in) {
                if (!(o instanceof GremlinEdge edge)) {
                    throw new QueryException("otherV expects edges but got: " + o);
                }
                edges.add(new EdgeQueryResult(edge.label(), String.valueOf(edge.id()), edge.side(),
                        edge.otherVertexId(), edge.properties(), edge.createdTime(), edge.lastUpdatedTime()));
            }
            return new ArrayList<>(readNeighbourVertices(ctx, edges));
        };
    }

    // --- tuning ---

    /**
     * Vector search step: replaces every vertex with its {@code k} nearest
     * neighbours in the HNSW index of its vertex type (the vertex itself is never
     * returned). The index is looked up per traverser, so one query can mix vertex
     * types as long as each type has an index.
     *
     * <p>While collecting a subgraph ({\@code returnSubgraph}) the bottom-layer links
     * between the query vertex and the returned neighbours are recorded too, so the
     * result can be drawn as a graph instead of k isolated nodes.
     *
     * @param indexes  vertex type name to HNSW index
     * @param k        number of neighbours
     * @param ef       candidate list size ({@code <= 0} = the index' configured {@code efSearch})
     */
    static GremlinStep nearest(Function<String, HnswIndex> indexes, int k, int ef) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            Set<Object> seen = new LinkedHashSet<>();
            for (Object o : in) {
                if (!(o instanceof GremlinVertex vertex)) {
                    throw new QueryException("nearest expects vertices but got: " + o);
                }
                HnswIndex index = indexes.apply(vertex.label());
                if (index == null) {
                    throw new QueryException("No HNSW index is configured for vertex type '"
                            + vertex.label() + "'");
                }
                int candidates = ef > 0 ? ef : index.config().efSearch();
                Set<Object> hits = new LinkedHashSet<>();
                for (HnswSearchResult hit : index.searchById(vertex.id(), k, candidates)) {
                    if (seen.add(hit.vertexId())) {
                        hits.add(hit.vertexId());
                    }
                }
                recordNeighbourEdges(ctx, index, vertex, hits);
                out.addAll(readVertices(ctx, vertex.label(), new ArrayList<>(hits)));
            }
            return out;
        };
    }

    /** Records the layer-0 links that connect a query vertex to its returned neighbours. */
    private static void recordNeighbourEdges(GremlinContext ctx, HnswIndex index, GremlinVertex vertex,
                                            Set<Object> hits) {
        if (!ctx.collectSubgraph() || hits.isEmpty()) {
            return;
        }
        List<EdgeQueryResult> edges = ctx.graphQuery().readEdges(vertex.id(), vertex.label(),
                HnswLevels.edgeName(index.vectorProperty(), 0), EdgeSide.BOTH, List.of());
        for (EdgeQueryResult edge : edges) {
            Object other = edge.otherVertexId();
            if (hits.contains(other)) {
                ctx.recordEdge(edge.edgeName(), edge.edgeId(), vertex.id(), other, edge.properties(),
                        edge.createdTime(), edge.lastUpdatedTime());
            }
        }
    }

    /** Reads the given vertices (all properties) and records them for the subgraph. */
    private static List<Object> readVertices(GremlinContext ctx, String vertexName, List<Object> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<VertexQueryResult> vertices = ctx.graphQuery().readVertices(vertexName, ids, List.of());
        List<Object> out = new ArrayList<>(vertices.size());
        for (VertexQueryResult vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            ctx.recordVertex(vertex.vertexName(), vertex.vertexId());
            out.add(new GremlinVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                    vertex.createdTime(), vertex.lastUpdatedTime()));
        }
        return out;
    }

    /** Sets the fan-out batch size for the batched lookups that follow. */
    static GremlinStep batch(int batchSize) {
        return (ctx, in) -> {
            ctx.batchSize(batchSize);
            return in;
        };
    }

    /**
     * Barrier: accumulates the traversers and, from here on, queries the storage
     * in bulks of {@code barrierSize}. In this list-based engine it configures
     * the same bulk-chunk size as {@link #batch(int)}.
     */
    static GremlinStep barrier(int barrierSize) {
        return (ctx, in) -> {
            ctx.batchSize(barrierSize);
            return in;
        };
    }

    // --- filter ---

    /**
     * Keeps the elements matching a property expression, e.g.
     * {@code filter(relation.equals('directedBy') || xrelation.equals('directed'))}.
     * See {@link GremlinFilterExpression} for the grammar.
     */
    static GremlinStep filter(String expression) {
        GremlinFilterExpression predicate = GremlinFilterExpression.parse(expression);
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                if (predicate.test(element)) {
                    out.add(o);
                } else {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /** Filters elements: keeps those having {@code key} (and, when given, matching {@code rawValue}). */
    static GremlinStep has(String key, String rawValue) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                Object value = element.properties().get(key);
                boolean keep = value != null && (rawValue == null || matches(value, rawValue));
                if (keep) {
                    out.add(o);
                } else {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    // --- projections ---

    /** Projects the given property keys; the result is the flattened list of values. */
    static GremlinStep values(List<String> keys) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                for (String key : keys) {
                    out.add(element.properties().get(key));
                }
            }
            return out;
        };
    }

    /** Projects each element to {@code {id, label, property1, property2, ...}}. */
    static GremlinStep elementMap() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", element.id());
                map.put("label", element.label());
                map.putAll(element.properties());
                out.add(map);
            }
            return out;
        };
    }

    // --- statistics / terminal ---

    static GremlinStep count() {
        return (ctx, in) -> List.of((long) in.size());
    }

    static GremlinStep limit(long n) {
        return (ctx, in) -> in.stream().limit(n).toList();
    }

    static GremlinStep dedup() {
        return (ctx, in) -> in.stream().distinct().toList();
    }

    // --- internals ---

    private static List<Object> hopEdges(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir) {
        List<EdgeQueryResult> allEdges = readAllEdges(ctx, toVertices(in), edgeName, dir, edgeReadProperties(ctx));
        List<Object> out = new ArrayList<>(allEdges.size());
        for (EdgeQueryResult edge : allEdges) {
            out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(), edge.otherVertexId(),
                    edge.properties(), edge.createdTime(), edge.lastUpdatedTime()));
        }
        return out;
    }

    private static List<Object> hopVertices(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir) {
        // The intermediate edges of a vertex hop are internal: only their otherVertexId
        // is consumed, so no edge property projection is applied here.
        List<EdgeQueryResult> allEdges = readAllEdges(ctx, toVertices(in), edgeName, dir, List.of());
        return new ArrayList<>(readNeighbourVertices(ctx, allEdges));
    }

    /** Reads the edges of the given edge class for all input vertices, with per-query dedup + cache. */
    private static List<EdgeQueryResult> readAllEdges(GremlinContext ctx, List<GremlinVertex> vertices,
                                                      String edgeName, EdgeSide dir, List<String> edgeProperties) {
        if (vertices.isEmpty()) {
            return List.of();
        }
        if (edgeName == null) {
            return readAllEdgeClasses(ctx, vertices, dir, edgeProperties);
        }
        if (vertices.size() == 1) {
            GremlinVertex v = vertices.get(0);
            return ctx.readEdgesCached(v.id(), v.label(), edgeName, dir, edgeProperties);
        }
        List<Object> ids = new ArrayList<>(vertices.size());
        for (GremlinVertex v : vertices) {
            ids.add(v.id());
        }
        return ctx.readAllEdgesBatchCached(edgeName, dir, ids, edgeProperties);
    }

    /** Reads the edges of every edge class (the {@code both()}/{@code bothE()} no-argument form). */
    private static List<EdgeQueryResult> readAllEdgeClasses(GremlinContext ctx, List<GremlinVertex> vertices,
                                                            EdgeSide dir, List<String> edgeProperties) {
        List<Object> ids = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (GremlinVertex v : vertices) {
            if (seen.add(v.id())) {
                ids.add(v.id());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<List<EdgeQueryResult>> perVertex = ctx.readAllEdgeClassesBatched(ids, dir, edgeProperties);
        List<EdgeQueryResult> all = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            ctx.recordEdges(ids.get(i), perVertex.get(i));
            all.addAll(perVertex.get(i));
        }
        return all;
    }

    /**
     * The edge property projection for an edge hop. Edge records must always be
     * read (their {@code otherVertexId} is needed), so {@code null} ("no vertex
     * properties") falls back to reading all edge properties; a non-empty list
     * projects only the named properties (used by {@code has}/{@code values}).
     */
    private static List<String> edgeReadProperties(GremlinContext ctx) {
        List<String> properties = ctx.readProperties();
        return properties == null ? List.of() : properties;
    }

    private static List<GremlinVertex> readNeighbourVertices(GremlinContext ctx,
                                                             List<EdgeQueryResult> edges) {
        if (edges.isEmpty()) {
            return List.of();
        }
        // group edges by neighbour vertex schema name, remembering the original order
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        Object[] ids = new Object[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            EdgeQueryResult edge = edges.get(i);
            String name = ctx.vertexResolver().neighborVertexName(edge.edgeName(), edge.side());
            ids[i] = edge.otherVertexId();
            groups.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
        }

        GremlinVertex[] result = new GremlinVertex[edges.size()];
        List<String> readProperties = ctx.readProperties();
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            String name = group.getKey();
            List<Integer> indices = group.getValue();
            if (name == null || readProperties == null) {
                // Only the id/label are needed for the next hop — skip the vertex read.
                for (int idx : indices) {
                    ctx.recordVertex(name, ids[idx]);
                    result[idx] = new GremlinVertex(name, ids[idx], Map.of(), 0L, 0L);
                }
                continue;
            }
            // the context splits this into batches and reads them in parallel
            List<Object> groupIds = new ArrayList<>(indices.size());
            for (int idx : indices) {
                groupIds.add(ids[idx]);
            }
            List<VertexQueryResult> vertices = ctx.readVerticesCached(name, groupIds, readProperties);
            for (int k = 0; k < indices.size(); k++) {
                int idx = indices.get(k);
                VertexQueryResult vertex = vertices.get(k);
                ctx.recordVertex(name, ids[idx]);
                result[idx] = vertex == null
                        ? new GremlinVertex(name, ids[idx], Map.of(), 0L, 0L)
                        : new GremlinVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                                vertex.createdTime(), vertex.lastUpdatedTime());
            }
        }
        return List.of(result);
    }

    private static List<GremlinVertex> toVertices(List<Object> in) {
        List<GremlinVertex> vertices = new ArrayList<>(in.size());
        for (Object o : in) {
            vertices.add(requireVertex(o));
        }
        return vertices;
    }

    /** Compares a stored property value against a raw string token (numeric-aware). */
    static boolean matches(Object stored, String rawToken) {
        if (stored instanceof Number number) {
            try {
                if (rawToken.indexOf('.') >= 0 || rawToken.indexOf('e') >= 0 || rawToken.indexOf('E') >= 0) {
                    return Double.compare(Double.parseDouble(rawToken), number.doubleValue()) == 0;
                }
                return Long.compare(Long.parseLong(rawToken), number.longValue()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (stored instanceof Boolean bool) {
            return Boolean.parseBoolean(rawToken) == bool;
        }
        return String.valueOf(stored).equals(rawToken);
    }

    private static GremlinVertex requireVertex(Object o) {
        if (o instanceof GremlinVertex vertex) {
            return vertex;
        }
        throw new QueryException("Step expects vertices but got: " + o);
    }

    private static GremlinElement requireElement(Object o) {
        if (o instanceof GremlinElement element) {
            return element;
        }
        throw new QueryException("Step expects elements but got: " + o);
    }
}
