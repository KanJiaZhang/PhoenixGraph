package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // --- tuning ---

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

    /** Filters elements: keeps those having {@code key} (and, when given, matching {@code rawValue}). */
    static GremlinStep has(String key, String rawValue) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                Object value = element.properties().get(key);
                if (value == null) {
                    continue;
                }
                if (rawValue != null && !matches(value, rawValue)) {
                    continue;
                }
                out.add(o);
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
        List<EdgeQueryResult> allEdges = readAllEdges(ctx, toVertices(in), edgeName, dir);
        List<Object> out = new ArrayList<>(allEdges.size());
        for (EdgeQueryResult edge : allEdges) {
            out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(), edge.otherVertexId(),
                    edge.properties(), edge.createdTime(), edge.lastUpdatedTime()));
        }
        return out;
    }

    private static List<Object> hopVertices(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir) {
        List<EdgeQueryResult> allEdges = readAllEdges(ctx, toVertices(in), edgeName, dir);
        return new ArrayList<>(readNeighbourVertices(ctx, allEdges, edgeName));
    }

    /** Reads the edges of the given edge class for all input vertices, batched by {@code ctx.batchSize()}. */
    private static List<EdgeQueryResult> readAllEdges(GremlinContext ctx, List<GremlinVertex> vertices,
                                                      String edgeName, EdgeSide dir) {
        if (vertices.isEmpty()) {
            return List.of();
        }
        if (vertices.size() == 1) {
            GremlinVertex v = vertices.get(0);
            return ctx.graphQuery().readEdges(v.id(), v.label(), edgeName, dir, List.of());
        }
        List<EdgeQueryResult> allEdges = new ArrayList<>();
        int batchSize = ctx.batchSize();
        for (int start = 0; start < vertices.size(); start += batchSize) {
            int end = Math.min(start + batchSize, vertices.size());
            List<Object> chunkIds = new ArrayList<>(end - start);
            for (GremlinVertex v : vertices.subList(start, end)) {
                chunkIds.add(v.id());
            }
            for (List<EdgeQueryResult> edges : ctx.graphQuery().readEdgesBatch(edgeName, dir, chunkIds, List.of())) {
                allEdges.addAll(edges);
            }
        }
        return allEdges;
    }

    private static List<GremlinVertex> readNeighbourVertices(GremlinContext ctx,
                                                             List<EdgeQueryResult> edges, String edgeName) {
        if (edges.isEmpty()) {
            return List.of();
        }
        // group edges by neighbour vertex schema name, remembering the original order
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        Object[] ids = new Object[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            EdgeQueryResult edge = edges.get(i);
            String name = ctx.vertexResolver().neighborVertexName(edgeName, edge.side());
            ids[i] = edge.otherVertexId();
            groups.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
        }

        GremlinVertex[] result = new GremlinVertex[edges.size()];
        List<String> readProperties = ctx.readProperties();
        int batchSize = ctx.batchSize();
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            String name = group.getKey();
            List<Integer> indices = group.getValue();
            if (name == null || readProperties == null) {
                // Only the id/label are needed for the next hop — skip the vertex read.
                for (int idx : indices) {
                    result[idx] = new GremlinVertex(name, ids[idx], Map.of(), 0L, 0L);
                }
                continue;
            }
            for (int start = 0; start < indices.size(); start += batchSize) {
                int end = Math.min(start + batchSize, indices.size());
                List<Integer> chunk = indices.subList(start, end);
                List<Object> chunkIds = new ArrayList<>(chunk.size());
                for (int idx : chunk) {
                    chunkIds.add(ids[idx]);
                }
                List<VertexQueryResult> vertices = ctx.readVerticesCached(name, chunkIds, readProperties);
                for (int k = 0; k < chunk.size(); k++) {
                    int idx = chunk.get(k);
                    VertexQueryResult vertex = vertices.get(k);
                    result[idx] = vertex == null
                            ? new GremlinVertex(name, ids[idx], Map.of(), 0L, 0L)
                            : new GremlinVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                                    vertex.createdTime(), vertex.lastUpdatedTime());
                }
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
