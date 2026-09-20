package com.pengcheng.phoenixgraph.gemini;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph a Gemini cluster computes on, in the form a partitioner needs: dense vertex numbers, an
 * edge list, and a way back to the ids the query used.
 *
 * <p>Gemini numbers vertices {@code 0..|V|-1} so that a partition can be a contiguous range and a
 * vertex state array can be indexed directly. Our graph is keyed by ids that are strings as often as
 * numbers, so the mapping is built here once and every result is translated back at the end.
 *
 * <p>The edge list is read from storage for the vertices a traversal reached - the same shape the
 * in-memory algorithm steps use - so a distributed run and an in-memory run over the same traversal
 * are comparable.
 */
public final class GeminiGraph {

    /** An edge of the computation graph: dense endpoints and a weight (1 when there is none). */
    public record Edge(int source, int target, double weight) {
    }

    private final List<Object> ids;
    private final Map<Object, Integer> byId;
    private final List<Edge> edges;

    private GeminiGraph(List<Object> ids, Map<Object, Integer> byId, List<Edge> edges) {
        this.ids = List.copyOf(ids);
        this.byId = Map.copyOf(byId);
        this.edges = List.copyOf(edges);
    }

    /** The ids in dense order ({@code ids.get(denseId)} is the query id of that vertex). */
    public List<Object> ids() {
        return ids;
    }

    /** How many vertices the graph has. */
    public int vertexCount() {
        return ids.size();
    }

    /** The dense id of a query id, or {@code -1} when the graph does not hold it. */
    public int denseId(Object id) {
        Integer dense = byId.get(id);
        return dense == null ? -1 : dense;
    }

    /** The query id of a dense id. */
    public Object id(int denseId) {
        return ids.get(denseId);
    }

    /** The edges, both endpoints inside the vertex set. */
    public List<Edge> edges() {
        return edges;
    }

    /** Whether an edge of the given class is weighted by its {@code weightProperty}. */
    public record Scope(List<Object> vertexIds, Set<String> edgeNames, String weightProperty) {

        public Scope {
            vertexIds = List.copyOf(vertexIds);
            edgeNames = edgeNames == null ? Set.of() : Set.copyOf(edgeNames);
        }
    }

    /**
     * Builds the computation graph over the vertices a traversal reached.
     *
     * <p>Edges are read with {@link GraphQuery#readAllEdgesBatch} for undirected algorithms and with
     * the outgoing side for directed ones, all classes at once unless the traversal named some (then
     * only those, which is what {@code g.E('payment').pagerank(DIST)} means), and an edge is kept only
     * when both of its endpoints are in the vertex set - the induced subgraph, like the in-memory
     * steps build.
     *
     * @param query    the read path of the graph
     * @param scope    the vertices to include and the edge classes to follow
     * @param directed whether the algorithm treats the graph as directed (out-edges only)
     */
    public static GeminiGraph of(GraphQuery query, Scope scope, boolean directed) {
        List<Object> ids = new ArrayList<>();
        Map<Object, Integer> byId = new LinkedHashMap<>();
        Map<String, Integer> byText = new LinkedHashMap<>();
        for (Object id : scope.vertexIds()) {
            if (id != null && byId.putIfAbsent(id, ids.size()) == null) {
                byText.put(String.valueOf(id), ids.size());
                ids.add(id);
            }
        }
        List<Edge> edges = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<List<EdgeQueryResult>> byVertex = scope.edgeNames().isEmpty()
                    ? query.readAllEdgesBatch(ids, EdgeSide.BOTH, List.of())
                    : readNamed(query, ids, scope.edgeNames());
            for (int i = 0; i < ids.size(); i++) {
                int source = i;
                for (EdgeQueryResult edge : byVertex.get(i)) {
                    Integer target = denseId(byId, byText, edge.otherVertexId());
                    if (target == null) {
                        continue; // an endpoint outside the reached set is not part of the subgraph
                    }
                    if (directed && edge.side() == EdgeSide.IN) {
                        continue; // a directed algorithm follows the edge the way it is stored
                    }
                    edges.add(new Edge(source, target, weight(edge, scope.weightProperty())));
                }
            }
        }
        return new GeminiGraph(ids, byId, edges);
    }

    /**
     * The dense id of an endpoint read back from storage.
     *
     * <p>A vertex id reaches this class in two forms: the one a caller wrote in the query (text, since
     * a query is text) and the one the store reports (the type it stored the id under, numbers as
     * numbers). Both name the same vertex, so an endpoint is matched by identity first and by its text
     * form second - otherwise a run seeded by ids from the query would see no edges at all, because
     * every endpoint it reads back would look like a different vertex.
     */
    private static Integer denseId(Map<Object, Integer> byId, Map<String, Integer> byText, Object id) {
        if (id == null) {
            return null;
        }
        Integer dense = byId.get(id);
        return dense != null ? dense : byText.get(String.valueOf(id));
    }

    /** An explicit graph, for a test or a caller that already has the edges (no storage involved). */
    public static GeminiGraph of(List<Object> vertexIds, List<Edge> edges) {
        Map<Object, Integer> byId = new LinkedHashMap<>();
        List<Object> ids = new ArrayList<>();
        for (Object id : vertexIds) {
            if (id != null && byId.putIfAbsent(id, ids.size()) == null) {
                ids.add(id);
            }
        }
        return new GeminiGraph(ids, byId, edges);
    }

    /** The vertices of a set of query ids, plus the edges between them, as a plain graph. */
    public static GeminiGraph ofEdges(Collection<Object> vertexIds, List<Edge> denseEdges) {
        return of(new ArrayList<>(vertexIds), denseEdges);
    }

    /** Reads the named edge classes only (an empty list of classes means "no edges at all"). */
    private static List<List<EdgeQueryResult>> readNamed(GraphQuery query, List<Object> ids,
                                                         Set<String> edgeNames) {
        List<List<EdgeQueryResult>> perVertex = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            perVertex.add(new ArrayList<>());
        }
        for (String edgeName : edgeNames) {
            List<List<EdgeQueryResult>> byEdge = query.readEdgesBatch(edgeName, EdgeSide.BOTH, ids, List.of());
            for (int i = 0; i < ids.size(); i++) {
                perVertex.get(i).addAll(byEdge.get(i));
            }
        }
        for (List<EdgeQueryResult> list : perVertex) {
            list.sort((a, b) -> a.edgeId().compareTo(b.edgeId()));
        }
        return perVertex;
    }

    /** The weight of an edge: the named property when it is a number, otherwise one. */
    private static double weight(EdgeQueryResult edge, String weightProperty) {
        if (weightProperty == null || weightProperty.isBlank()) {
            return 1.0;
        }
        Object value = edge.properties().get(weightProperty);
        if (value instanceof Number number) {
            double weight = number.doubleValue();
            return weight > 0 ? weight : 1.0;
        }
        return 1.0;
    }

    /** A vertex of the computation graph with its value, for a result a caller can read. */
    public record Value(Object vertexId, Object value) {
    }
}
