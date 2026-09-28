package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.hnsw.HnswFilter;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswLevels;
import com.pengcheng.phoenixgraph.hnsw.HnswSearchResult;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLinks;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPattern;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import com.pengcheng.phoenixgraph.fuzzy.FuzzySchema;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgePropertyFilter;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.storage.TimeRange;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;

import java.util.ArrayList;
import java.util.Collection;
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
        return outE(edgeName, List.of(), 0);
    }

    /**
     * An edge hop whose read was planned with conditions and a cap: the store evaluates them while
     * it decodes (an edge that fails is never built) and stops at the cap instead of building a
     * hub's whole bin. See {@code GremlinGraphQuery#pushEdgeConditions}.
     */
    static GremlinStep outE(String edgeName, List<EdgePropertyFilter> filters, int maxResults) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.OUT, filters, maxResults);
    }

    static GremlinStep inE(String edgeName) {
        return inE(edgeName, List.of(), 0);
    }

    /** @see #outE(String, List, int) */
    static GremlinStep inE(String edgeName, List<EdgePropertyFilter> filters, int maxResults) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.IN, filters, maxResults);
    }

    static GremlinStep bothE(String edgeName) {
        return bothE(edgeName, List.of(), 0);
    }

    /** @see #outE(String, List, int) */
    static GremlinStep bothE(String edgeName, List<EdgePropertyFilter> filters, int maxResults) {
        return (ctx, in) -> hopEdges(ctx, in, edgeName, EdgeSide.BOTH, filters, maxResults);
    }

    /** The same edge hop over several named classes, concatenated in argument order. */
    static GremlinStep outE(List<String> edgeNames) {
        return (ctx, in) -> hopEdges(ctx, in, edgeNames, EdgeSide.OUT, List.of(), 0);
    }

    /** @see #outE(List) */
    static GremlinStep inE(List<String> edgeNames) {
        return (ctx, in) -> hopEdges(ctx, in, edgeNames, EdgeSide.IN, List.of(), 0);
    }

    /** @see #outE(List) */
    static GremlinStep bothE(List<String> edgeNames) {
        return (ctx, in) -> hopEdges(ctx, in, edgeNames, EdgeSide.BOTH, List.of(), 0);
    }

    /**
     * {@code outE('x').count()} in one step: the store counts the edges instead of handing them
     * over - and with conditions it counts only the edges that satisfy them, without building any
     * of them. When a subgraph is being collected the edges are read as before, so the drawing does
     * not lose them.
     */
    static GremlinStep countEdges(String edgeName, EdgeSide side) {
        return countEdges(edgeName, side, List.of());
    }

    /** @see #countEdges(String, EdgeSide) */
    static GremlinStep countEdges(String edgeName, EdgeSide side, List<EdgePropertyFilter> filters) {
        List<EdgePropertyFilter> conditions = filters == null ? List.of() : filters;
        return (ctx, in) -> {
            // no edge class means 'every class': only a store that can count all of them at once
            // could answer that, so those queries take the reading path
            if (edgeName == null || ctx.collectSubgraph()) {
                List<Object> edges = hopEdges(ctx, in, edgeName, side, conditions, 0);
                return List.of((long) edges.size());
            }
            List<Object> vertexIds = new ArrayList<>(in.size());
            for (Object element : in) {
                if (element instanceof GremlinVertex vertex) {
                    vertexIds.add(vertex.id());
                }
            }
            return List.of(ctx.countEdges(vertexIds, edgeName, side, conditions));
        };
    }

    // --- fuzzy linking ---

    /**
     * Finds the fuzzy link node a context produces ({@code fuzzyLink(0, '{...}')}).
     *
     * <p>A pattern reads fragments of a context - an email domain, the first octets of an IP, a
     * card BIN, a user name through a regular expression - and the fragments make the key of a link
     * node. Accounts whose details are hidden but whose fragments agree produce the same key, so
     * this step is how a traversal starts from "these details" and lands on the node that groups
     * them; {@code out('FuzzyLinkedTo')} then walks to the accounts.
     *
     * <p>The context is the JSON object the step was given, filled in with the properties of the
     * traversers it received - so {@code g.V('a1','Account').fuzzyLink(0)} asks "which ring does this
     * account belong to". An empty traverser list is fine: the step works as a source too.
     *
     * <p>The answer is empty when the pattern does not apply to the context (a part of it has no
     * value) or when no account has produced that key yet. Neither is an error: a link node is
     * created by the accounts that belong to it.
     */
    static GremlinStep fuzzyLink(FuzzyPatternRegistry patterns, int patternId, String contextJson) {
        return (ctx, in) -> {
            try {
                return fuzzyLinkStep(ctx, in, patterns, patternId, contextJson);
            } catch (FuzzyPatternException e) {
                // a pattern error is a query error: the UI answers with the message, not with a 500
                throw new QueryException(e.getMessage(), e);
            }
        };
    }

    /** The body of {@link #fuzzyLink}: a context becomes the link node that groups it. */
    private static List<Object> fuzzyLinkStep(GremlinContext ctx, List<Object> in,
                                              FuzzyPatternRegistry patterns, int patternId,
                                              String contextJson) {
        {
            FuzzyPattern pattern = patterns.require(patternId);
            List<Object> linkIds = new ArrayList<>();
            Map<String, FuzzyContext> contextsByLink = new LinkedHashMap<>();
            List<FuzzyContext> contexts = new ArrayList<>();
            if (in.isEmpty()) {
                contexts.add(FuzzyContexts.fromJson(contextJson));
            } else {
                for (Object element : in) {
                    Map<String, Object> properties = element instanceof GremlinElement gremlin
                            ? gremlin.properties() : Map.of();
                    contexts.add(FuzzyContexts.fromJsonAndProperties(contextJson, properties));
                }
            }
            for (FuzzyContext context : contexts) {
                String linkId = FuzzyLinks.linkVertexId(pattern, context);
                if (linkId == null) {
                    continue; // the pattern does not apply to this context
                }
                if (contextsByLink.putIfAbsent(linkId, context) == null) {
                    linkIds.add(linkId);
                }
            }
            if (linkIds.isEmpty()) {
                return List.of();
            }
            // the link nodes that exist are the answer; a key no account has produced yet has no node
            List<String> plan = ctx.readProperties();
            List<VertexQueryResult> found = ctx.readVerticesCached(FuzzySchema.LINK_VERTEX, linkIds,
                    plan == null ? List.of() : plan);
            List<Object> out = new ArrayList<>(linkIds.size());
            for (VertexQueryResult link : found) {
                if (link == null) {
                    continue;
                }
                ctx.recordVertex(link.vertexName(), link.vertexId());
                out.add(new GremlinVertex(link.vertexName(), link.vertexId(), link.properties(),
                        link.createdTime(), link.lastUpdatedTime()));
            }
            return out;
        }
    }

    // --- vertex hops (out / in / both): vertices -> vertices ---

    static GremlinStep out(String edgeName) {
        return out(edgeName, 0);
    }

    /** A vertex hop that stops once it has {@code maxResults} neighbours ({@code 0} = no cap). */
    static GremlinStep out(String edgeName, int maxResults) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.OUT, maxResults);
    }

    static GremlinStep in(String edgeName) {
        return in(edgeName, 0);
    }

    /** @see #out(String, int) */
    static GremlinStep in(String edgeName, int maxResults) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.IN, maxResults);
    }

    static GremlinStep both(String edgeName) {
        return both(edgeName, 0);
    }

    /** @see #out(String, int) */
    static GremlinStep both(String edgeName, int maxResults) {
        return (ctx, in) -> hopVertices(ctx, in, edgeName, EdgeSide.BOTH, maxResults);
    }

    /** The same vertex hop over several named classes, concatenated in argument order. */
    static GremlinStep out(List<String> edgeNames) {
        return (ctx, in) -> hopVertices(ctx, in, edgeNames, EdgeSide.OUT);
    }

    /** @see #out(List) */
    static GremlinStep in(List<String> edgeNames) {
        return (ctx, in) -> hopVertices(ctx, in, edgeNames, EdgeSide.IN);
    }

    /** @see #out(List) */
    static GremlinStep both(List<String> edgeNames) {
        return (ctx, in) -> hopVertices(ctx, in, edgeNames, EdgeSide.BOTH);
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
        return nearest(indexes, k, ef, null);
    }

    /**
     * Vector search with a pushed-down predicate: the conditions of the {@code has}/
     * {@code filter} steps directly following {@code nearest} are evaluated
     * <em>inside</em> the graph walk (see {@link HnswFilter}) instead of after it, so
     * the walk keeps expanding through non-matching vertices and returns the closest
     * {@code k} that match, rather than "the k closest, of which some match".
     *
     * @param indexes vertex type name to HNSW index
     * @param k       number of neighbours
     * @param ef      candidate list size ({@code <= 0} = the index' configured {@code efSearch})
     * @param filter  predicate pushed into the search, or {@code null}
     */
    static GremlinStep nearest(Function<String, HnswIndex> indexes, int k, int ef, HnswFilter filter) {
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
                for (HnswSearchResult hit : index.searchById(vertex.id(), k, candidates, filter)) {
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

    /**
     * Declares the creation-time window the following edge reads use, and passes the traversers
     * through unchanged.
     *
     * <p>A backend with aggregated edge tables (a vertex with a hundred million edges) can only
     * answer a bounded read, so a query on such a vertex has to say which window it wants:
     *
     * <pre>
     * g.V('hub','Account').createdBetween('2025-01-01', '2025-02-01').outE('Payment').limit(500)
     * </pre>
     *
     * <p>Bounds are epoch millis or a UTC date; {@code *} (or an empty argument) leaves a side
     * open. Backends without such tables simply ignore the window.
     */
    static GremlinStep createdBetween(TimeRange range, int maxResults) {
        return (ctx, in) -> {
            ctx.edgeTimeRange(range, maxResults);
            return in;
        };
    }

    /**
     * Geo search step: replaces every vertex with the vertices of its type that lie inside a
     * circle, nearest first, attaching the distance as
     * {@link GeoIndexSchemas#DISTANCE_PROPERTY} (metres).
     *
     * <p>The circle is described by the step's JSON argument
     * ({@code circle('{"lat":31.2304,"lon":121.4737,"radius":1000}')}) or by a point JSON plus
     * the radius in metres as the step's own argument ({@code circle('{"lat":..,"lon":..}', 2000)})
     * - the radius is a step parameter so a client can vary it per call. The search runs over
     * the geo index of the vertex type the traversers are on, the same way {@code nearest} uses
     * the HNSW index of the type.
     *
     * <p>{@code search.maxResults()} carries a {@code limit} of the query: the search stops
     * keeping hits beyond it, so only the nearest ones are read back and collected.
     */
    static GremlinStep circle(java.util.function.Function<String, String> geoPropertyOf,
                              GraphQuery graphQuery, GeoSearch search) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            Set<Object> seen = new LinkedHashSet<>();
            for (Object o : in) {
                if (!(o instanceof GremlinVertex vertex)) {
                    throw new QueryException("circle expects vertices but got: " + o);
                }
                String property = geoPropertyOf.apply(vertex.label());
                if (property == null) {
                    throw new QueryException("No geo index is configured for vertex type '"
                            + vertex.label() + "' (declare a property with isGeo)");
                }
                if (seen.size() >= search.maxResults() && search.maxResults() > 0) {
                    break; // the limit of the query is already satisfied
                }
                if (!graphQuery.supportsGeoSearch()) {
                    throw new QueryException("The storage behind this graph does not support geo search");
                }
                List<GeoHit> hits = new ArrayList<>();
                for (GeoHit hit : graphQuery.geoNear(vertex.label(), property, search)) {
                    if (search.maxResults() > 0 && seen.size() >= search.maxResults()) {
                        break;
                    }
                    if (!seen.add(hit.vertexId())) {
                        continue;
                    }
                    hits.add(hit);
                }
                out.addAll(readHits(ctx, vertex.label(), hits));
            }
            return out;
        };
    }

    /**
     * Reads the vertices behind one geo search, in the order the index returned them, each carrying its
     * distance.
     *
     * <p><b>One read for all of them</b>, not one per hit: the hits came out of one search, so they are one
     * batch (and the store answers a batch in one round trip).
     *
     * <p>When nothing after the step needs a property ({@link GremlinContext#readProperties()} is
     * {@code null}) the records are not read at all: the id, the label and the distance are everything the
     * next step works with, which is the same rule a hop follows for the vertices it reaches. The distance
     * comes from the index, so skipping the read does not lose it.
     */
    private static List<GremlinVertex> readHits(GremlinContext ctx, String vertexName, List<GeoHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<String> plan = ctx.readProperties();
        if (plan == null) {
            List<GremlinVertex> out = new ArrayList<>(hits.size());
            for (GeoHit hit : hits) {
                ctx.recordVertex(vertexName, hit.vertexId());
                out.add(new GremlinVertex(vertexName, hit.vertexId(),
                        Map.of(GeoIndexSchemas.DISTANCE_PROPERTY, hit.distanceMeters()), 0L, 0L));
            }
            return out;
        }
        List<Object> ids = new ArrayList<>(hits.size());
        for (GeoHit hit : hits) {
            ids.add(hit.vertexId());
        }
        List<VertexQueryResult> found = ctx.graphQuery().readVertices(vertexName, ids, plan);
        List<GremlinVertex> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            VertexQueryResult vertex = i < found.size() ? found.get(i) : null;
            if (vertex == null) {
                continue; // the point is indexed but its vertex is gone: skip it
            }
            ctx.recordVertex(vertex.vertexName(), vertex.vertexId());
            Map<String, Object> properties = new LinkedHashMap<>(vertex.properties());
            properties.put(GeoIndexSchemas.DISTANCE_PROPERTY, hits.get(i).distanceMeters());
            out.add(new GremlinVertex(vertex.vertexName(), vertex.vertexId(), properties,
                    vertex.createdTime(), vertex.lastUpdatedTime()));
        }
        return out;
    }

    /**
     * Builds the predicate a {@code nearest} step pushes into the search from the
     * {@code has}/{@code filter} steps that follow it. The semantics are the ones of
     * those steps (a missing property does not match, {@code has} compares numbers
     * numerically), and the property keys they read are declared so the search can
     * batch-load them together with the vectors.
     */
    static HnswFilter hnswFilter(List<GremlinQuery.Step> conditions) {
        record Condition(String key, String value) {
        }
        List<Condition> hasConditions = new ArrayList<>();
        List<GremlinFilterExpression> expressions = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (GremlinQuery.Step step : conditions) {
            if (step.name().equals("has")) {
                String key = step.args().get(0);
                hasConditions.add(new Condition(key, step.args().size() > 1 ? step.args().get(1) : null));
                keys.add(key);
            } else if (step.name().equals("filter")) {
                GremlinFilterExpression expression =
                        GremlinFilterExpression.parse(String.join(" || ", step.args()));
                expressions.add(expression);
                keys.addAll(expression.propertyKeys());
            }
        }
        List<String> propertyNames = List.copyOf(keys);
        return new HnswFilter() {
            @Override
            public List<String> propertyNames() {
                return propertyNames;
            }

            @Override
            public boolean test(Object vertexId, Map<String, Object> properties) {
                for (Condition condition : hasConditions) {
                    Object stored = properties.get(condition.key());
                    if (stored == null) {
                        return false;
                    }
                    if (condition.value() != null && !matches(stored, condition.value())) {
                        return false;
                    }
                }
                if (!expressions.isEmpty()) {
                    GremlinElement element = new GremlinVertex("", vertexId, properties, 0L, 0L);
                    for (GremlinFilterExpression expression : expressions) {
                        if (!expression.test(element)) {
                            return false;
                        }
                    }
                }
                return true;
            }

            @Override
            public String describe() {
                return conditions.stream()
                        .map(step -> step.name() + "(" + String.join(",", step.args()) + ")")
                        .reduce((a, b) -> a + " && " + b).orElse("none");
            }
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

    /**
     * {@code hasLabel(name...)}: keeps the elements whose label (schema name) is one of the names.
     *
     * <p>A vertex type is normally named by the start ({@code g.V('Account')}); this is the step form, so
     * it also works in the middle of a traversal, where the type can change from hop to hop.
     */
    static GremlinStep hasLabel(List<String> labels) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                boolean keep = element.label() != null && labels.stream()
                        .anyMatch(label -> label != null && label.equalsIgnoreCase(element.label()));
                if (keep) {
                    out.add(o);
                } else {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /** {@code outV()}: the vertex an edge comes from (its source endpoint). */
    static GremlinStep outV() {
        return endpointStep(true, false);
    }

    /** {@code inV()}: the vertex an edge points at (its target endpoint). */
    static GremlinStep inV() {
        return endpointStep(false, true);
    }

    /** {@code bothV()}: both endpoints of every edge, the source first. */
    static GremlinStep bothV() {
        return endpointStep(true, true);
    }

    /**
     * The endpoint step: each edge becomes the vertex at one end (or both ends).
     *
     * <p>The endpoint ids come from {@link GremlinEdge#sourceEndpoint()}/{@link GremlinEdge#targetEndpoint()},
     * which every edge of a hop carries (the hop remembers the vertex it read the edge for), and the
     * vertices themselves are read in one batched pass - the same pass {@code otherV()} uses, so an
     * endpoint read costs one storage round trip however many edges there are.
     */
    private static GremlinStep endpointStep(boolean source, boolean target) {
        return (ctx, in) -> {
            List<EdgeQueryResult> sources = new ArrayList<>();
            List<EdgeQueryResult> targets = new ArrayList<>();
            for (Object o : in) {
                if (!(o instanceof GremlinEdge edge)) {
                    throw new QueryException("outV()/inV()/bothV() expect edges but got: " + o);
                }
                if (source && edge.sourceEndpoint() != null) {
                    // presented as an incoming edge, so the resolver looks up the class' source type
                    sources.add(endpoint(edge, EdgeSide.IN, edge.sourceEndpoint()));
                }
                if (target && edge.targetEndpoint() != null) {
                    targets.add(endpoint(edge, EdgeSide.OUT, edge.targetEndpoint()));
                }
            }
            List<GremlinVertex> fromSource = readNeighbourVertices(ctx, sources);
            List<GremlinVertex> fromTarget = readNeighbourVertices(ctx, targets);
            if (!source) {
                return new ArrayList<Object>(fromTarget);
            }
            if (!target) {
                return new ArrayList<Object>(fromSource);
            }
            // bothV(): the source then the target of each edge, in the order the edges arrived
            List<Object> out = new ArrayList<>(fromSource.size() + fromTarget.size());
            int pairs = Math.min(fromSource.size(), fromTarget.size());
            for (int i = 0; i < pairs; i++) {
                out.add(fromSource.get(i));
                out.add(fromTarget.get(i));
            }
            return out;
        };
    }

    /** One endpoint of an edge, shaped as the edge a read of that vertex would have produced. */
    private static EdgeQueryResult endpoint(GremlinEdge edge, EdgeSide side, Object vertexId) {
        return new EdgeQueryResult(edge.label(), String.valueOf(edge.id()), side, vertexId,
                edge.properties(), edge.createdTime(), edge.lastUpdatedTime());
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
                    // a stored property, or the element own accessor when the schema declares the name
                    // as a meta property / an edge endpoint (values("id"), values("createdTime"),
                    // values("source"))
                    out.add(GremlinElementExpression.value(element, key));
                }
            }
            return out;
        };
    }

    /** Projects each element to the value of one expression, the Groovy {@code map { ... }} way. */
    static GremlinStep map(String expression) {
        GremlinExpression compiled = GremlinExpression.parse(expression);
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(compiled.evaluate(requireElement(o)));
            }
            return out;
        };
    }

    // --- terminal steps (they end the traversal, the way TinkerPop's do) ---

    /** {@code toList()}: the traversers as a list - which is what the engine already returns. */
    static GremlinStep toList() {
        return (ctx, in) -> new ArrayList<>(in);
    }

    /** {@code toSet()}: every traverser once, in first-seen order (element identity, then value). */
    static GremlinStep toSet() {
        return dedup(null);
    }

    /**
     * {@code toBulkSet()}: every distinct traverser with the number of times the traversal produced
     * it, as {@code {value, label?, count}} rows in first-seen order.
     */
    static GremlinStep toBulkSet() {
        return (ctx, in) -> {
            Map<Object, Object> first = new LinkedHashMap<>();
            Map<Object, Long> counts = new LinkedHashMap<>();
            for (Object element : in) {
                Object key = element instanceof GremlinElement ge
                        ? new ElementKey(ge.label(), ge.id())
                        : element;
                first.putIfAbsent(key, element);
                counts.merge(key, 1L, Long::sum);
            }
            List<Object> out = new ArrayList<>(first.size());
            for (Map.Entry<Object, Object> entry : first.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (entry.getValue() instanceof GremlinElement element) {
                    row.put("value", element.id());
                    row.put("label", element.label());
                } else {
                    row.put("value", entry.getValue());
                }
                row.put("count", counts.get(entry.getKey()));
                out.add(row);
            }
            return out;
        };
    }

    /** {@code next([n])}: the first traverser (or the first {@code n}), like TinkerPop's terminal. */
    static GremlinStep next(int count) {
        return (ctx, in) -> new ArrayList<>(in.subList(0, Math.min(count, in.size())));
    }

    /** {@code iterate()}/{@code none()}/{@code discard()}: the traversal runs and answers nothing. */
    static GremlinStep nothing() {
        return (ctx, in) -> List.of();
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

    // --- map / filter / reduce: the shapes a TinkerPop client writes ---

    /** {@code identity()}: pass the traverser through. */
    static GremlinStep identity() {
        return (ctx, in) -> in;
    }

    /** {@code constant(v)}: every traverser becomes the value. */
    static GremlinStep constant(String value) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (int i = 0; i < in.size(); i++) {
                out.add(value);
            }
            return out;
        };
    }

    /** {@code id()}: the element's id. */
    static GremlinStep id() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(requireElement(o).id());
            }
            return out;
        };
    }

    /** {@code label()}: the element's label (schema name). */
    static GremlinStep label() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(requireElement(o).label());
            }
            return out;
        };
    }

    /** {@code hasId(ids...)}: keep the elements whose id is one of them. */
    static GremlinStep hasId(List<String> ids) {
        return (ctx, in) -> filterElements(ctx, in,
                element -> ids.stream().anyMatch(id -> id.equals(String.valueOf(element.id()))));
    }

    /** {@code hasNot(key)}: keep the elements that do not carry the property. */
    static GremlinStep hasNot(String key) {
        return (ctx, in) -> filterElements(ctx, in, element -> element.properties().get(key) == null);
    }

    /** {@code hasKey(keys...)}: keep the elements that carry every key. */
    static GremlinStep hasKey(List<String> keys) {
        return (ctx, in) -> filterElements(ctx, in,
                element -> keys.stream().allMatch(key -> element.properties().containsKey(key)));
    }

    /** {@code hasValue(values...)}: keep the elements one of whose property values matches. */
    static GremlinStep hasValue(List<String> values) {
        return (ctx, in) -> filterElements(ctx, in, element -> element.properties().values().stream()
                .anyMatch(value -> values.stream().anyMatch(token -> matches(value, token))));
    }

    /** {@code is(value)} / {@code is(gt(5))}: keep the traversers whose own value matches. */
    static GremlinStep is(String token) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                if (isMatch(o, token)) {
                    out.add(o);
                } else if (o instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    private static boolean isMatch(Object value, String token) {
        // every predicate the engine knows is parsed here, so a token that looks like a predicate call
        // and names an unknown one is an error (see GremlinPredicate); anything else is a value
        GremlinPredicate predicate = GremlinPredicate.parse(token);
        if (predicate != null) {
            return predicate.test(value);
        }
        return matches(value, token);
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if ((first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /** {@code range(low, high)} / {@code skip(n)} / {@code tail(n)}: a slice of the traversers. */
    static GremlinStep range(long low, long high) {
        return (ctx, in) -> {
            int from = (int) Math.max(0, Math.min(low, in.size()));
            int to = high < 0 ? in.size() : (int) Math.max(from, Math.min(high, in.size()));
            for (int i = 0; i < in.size(); i++) {
                if ((i < from || i >= to) && in.get(i) instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return new ArrayList<>(in.subList(from, to));
        };
    }

    /** {@code tail(n)}: the last n traversers. */
    static GremlinStep tail(int n) {
        return (ctx, in) -> {
            int from = Math.max(0, in.size() - n);
            for (int i = 0; i < from; i++) {
                if (in.get(i) instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return new ArrayList<>(in.subList(from, in.size()));
        };
    }

    /** {@code sample(n)}: n traversers, chosen with a fixed seed so a query is reproducible. */
    static GremlinStep sample(int n) {
        return (ctx, in) -> {
            List<Object> copy = new ArrayList<>(in);
            java.util.Collections.shuffle(copy, new java.util.Random(42L));
            if (n >= copy.size()) {
                return copy;
            }
            List<Object> out = new ArrayList<>(copy.subList(0, n));
            for (int i = n; i < copy.size(); i++) {
                if (copy.get(i) instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /** {@code fold()}: every traverser becomes one list. */
    static GremlinStep fold() {
        return (ctx, in) -> List.of(new ArrayList<>(in));
    }

    /** {@code unfold()}: a collection becomes its items. */
    static GremlinStep unfold() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                if (o instanceof java.util.Collection<?> collection) {
                    out.addAll(collection);
                } else if (o instanceof Map<?, ?> map) {
                    out.addAll(map.entrySet());
                } else if (o instanceof Object[] array) {
                    out.addAll(java.util.Arrays.asList(array));
                } else {
                    out.add(o);
                }
            }
            return out;
        };
    }

    /** {@code sum()}: the numeric total of the traversers. */
    static GremlinStep sum() {
        return (ctx, in) -> {
            boolean integral = true;
            double total = 0;
            for (Object o : in) {
                Number number = asNumber(o);
                integral &= isIntegral(number);
                total += number.doubleValue();
            }
            return List.of(integral ? (Object) (long) total : total);
        };
    }

    /** {@code mean()}: the numeric average of the traversers. */
    static GremlinStep mean() {
        return (ctx, in) -> {
            if (in.isEmpty()) {
                return List.of();
            }
            double total = 0;
            for (Object o : in) {
                total += asNumber(o).doubleValue();
            }
            return List.of(total / in.size());
        };
    }

    /** {@code max()} / {@code min()}: the largest / smallest traverser. */
    static GremlinStep max() {
        return reduceByComparison(true);
    }

    static GremlinStep min() {
        return reduceByComparison(false);
    }

    private static GremlinStep reduceByComparison(boolean largest) {
        return (ctx, in) -> {
            Object best = null;
            for (Object o : in) {
                if (best == null || (compareOrderValues(o, best) > 0) == largest) {
                    best = o;
                }
            }
            return best == null ? List.of() : List.of(best);
        };
    }

    /** {@code order([key[, desc]])}: sort the traversers (by a property when a key is given). */
    static GremlinStep order(String key, boolean descending) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in);
            out.sort((a, b) -> {
                int comparison = compareOrderValues(orderKey(a, key), orderKey(b, key));
                return descending ? -comparison : comparison;
            });
            return out;
        };
    }

    private static Object orderKey(Object value, String key) {
        if (key == null) {
            return value instanceof GremlinElement element ? element.id() : value;
        }
        return value instanceof GremlinElement element ? element.properties().get(key) : value;
    }

    private static int compareOrderValues(Object a, Object b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        try {
            return Double.compare(Double.parseDouble(String.valueOf(a)), Double.parseDouble(String.valueOf(b)));
        } catch (NumberFormatException e) {
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    /** {@code groupCount([key])}: how often each traverser (or each property value) appears. */
    static GremlinStep groupCount(String key) {
        return (ctx, in) -> {
            Map<Object, Long> counts = new LinkedHashMap<>();
            for (Object o : in) {
                Object groupKey = key == null ? groupKey(o) : orderKey(o, key);
                if (groupKey == null) {
                    // a missing property is a group of its own; JSON cannot carry a null map key, so the
                    // group is named rather than dropped
                    groupKey = "(none)";
                }
                counts.merge(groupKey, 1L, Long::sum);
            }
            return List.of(counts);
        };
    }

    private static Object groupKey(Object value) {
        return value instanceof GremlinElement element
                ? element.label() + "#" + element.id() : value;
    }

    /** {@code valueMap([keys...])}: the projected properties as a map. */
    static GremlinStep valueMap(List<String> keys) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                Map<String, Object> map = new LinkedHashMap<>();
                if (keys.isEmpty()) {
                    map.putAll(element.properties());
                } else {
                    for (String key : keys) {
                        map.put(key, element.properties().get(key));
                    }
                }
                out.add(map);
            }
            return out;
        };
    }

    /** {@code toLower()} / {@code toUpper()} / {@code trim()} / {@code length()}: the string steps. */
    static GremlinStep toLower() {
        return mapStrings(text -> text.toLowerCase(java.util.Locale.ROOT));
    }

    static GremlinStep toUpper() {
        return mapStrings(text -> text.toUpperCase(java.util.Locale.ROOT));
    }

    static GremlinStep trimString() {
        return mapStrings(String::trim);
    }

    static GremlinStep length() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add((long) String.valueOf(o).length());
            }
            return out;
        };
    }

    private static GremlinStep mapStrings(Function<String, String> mapper) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(o == null ? null : mapper.apply(String.valueOf(o)));
            }
            return out;
        };
    }

    /** {@code asString()}: every traverser as text. */
    static GremlinStep asString() {
        return mapStrings(text -> text);
    }

    /** {@code asNumber()}: parse text into a number (a Long when it is whole, else a Double). */
    static GremlinStep asNumber() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(parseNumber(o));
            }
            return out;
        };
    }

    private static Object parseNumber(Object value) {
        if (value instanceof Number) {
            return value;
        }
        String text = String.valueOf(value).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new QueryException("asNumber cannot read '" + value + "' as a number");
            }
        }
    }

    /** {@code asBool()}: parse text into a boolean. */
    static GremlinStep asBool() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(o instanceof Boolean ? o : Boolean.parseBoolean(String.valueOf(o).trim()));
            }
            return out;
        };
    }

    /** {@code asDate()}: an ISO-8601 instant becomes its epoch millis (a number, as the schema keeps times). */
    static GremlinStep asDate() {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                if (o instanceof Number) {
                    out.add(o);
                    continue;
                }
                try {
                    out.add(java.time.Instant.parse(String.valueOf(o).trim()).toEpochMilli());
                } catch (RuntimeException e) {
                    throw new QueryException("asDate cannot read '" + o + "' as an ISO-8601 instant");
                }
            }
            return out;
        };
    }

    /** {@code concat(suffix)}: append text to every traverser. */
    static GremlinStep concat(String suffix) {
        return mapStrings(text -> text + suffix);
    }

    /** {@code lTrim()} / {@code rTrim()}: trim one side. */
    static GremlinStep lTrim() {
        return mapStrings(text -> text.replaceAll("^\\s+", ""));
    }

    static GremlinStep rTrim() {
        return mapStrings(text -> text.replaceAll("\\s+$", ""));
    }

    /** {@code replace(from, to)}: replace every occurrence. */
    static GremlinStep replace(String from, String to) {
        return mapStrings(text -> text.replace(from, to));
    }

    /** {@code split(separator)}: the parts as a list. */
    static GremlinStep split(String separator) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(new ArrayList<>(java.util.Arrays.asList(
                        String.valueOf(o).split(java.util.regex.Pattern.quote(separator)))));
            }
            return out;
        };
    }

    /** {@code substring(start[, end])}: a slice of the text. */
    static GremlinStep substring(int start, int end) {
        return mapStrings(text -> {
            int from = Math.max(0, Math.min(start, text.length()));
            int to = end < 0 ? text.length() : Math.max(from, Math.min(end, text.length()));
            return text.substring(from, to);
        });
    }

    /** {@code coin(p)}: keep each traverser with probability p (a fixed seed keeps a query reproducible). */
    static GremlinStep coin(double probability) {
        return (ctx, in) -> {
            java.util.Random random = new java.util.Random(42L);
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                if (random.nextDouble() < probability) {
                    out.add(o);
                } else if (o instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /** {@code inject(values...)}: the values enter the traverser stream (before the incoming ones). */
    static GremlinStep inject(List<String> values) {
        List<Object> injected = new ArrayList<>(values.size());
        for (String value : values) {
            injected.add(coerce(unquote(value)));
        }
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(injected);
            out.addAll(in);
            return out;
        };
    }

    /** {@code timeLimit(ms)}: the engine has no long step loop to interrupt, so this passes through. */
    static GremlinStep timeLimit() {
        return (ctx, in) -> in;
    }

    /** A step that only makes sense while a path is being walked ({@code simplePath}/{@code cyclicPath}). */
    static GremlinStep needsPath(String stepName) {
        return (ctx, in) -> {
            throw new QueryException(stepName + "() needs path() in the same traversal");
        };
    }

    /**
     * The collection steps ({@code combine}, {@code intersect}, {@code difference}, {@code disjunct}): the
     * traverser is a collection and the argument names the other one, written {@code [1, 2]} or a single value.
     */
    static GremlinStep combine(String argument) {
        List<Object> other = valueList(argument);
        return mapCollection(value -> {
            List<Object> out = new ArrayList<>(value);
            out.addAll(other);
            return out;
        });
    }

    static GremlinStep intersect(String argument) {
        List<Object> other = valueList(argument);
        return mapCollection(value -> {
            List<Object> out = new ArrayList<>();
            for (Object item : value) {
                if (other.contains(item)) {
                    out.add(item);
                }
            }
            return out;
        });
    }

    static GremlinStep difference(String argument) {
        List<Object> other = valueList(argument);
        return mapCollection(value -> {
            List<Object> out = new ArrayList<>();
            for (Object item : value) {
                if (!other.contains(item)) {
                    out.add(item);
                }
            }
            return out;
        });
    }

    static GremlinStep disjunct(String argument) {
        List<Object> other = valueList(argument);
        return mapCollection(value -> {
            List<Object> out = new ArrayList<>(value);
            for (Object item : other) {
                if (!out.contains(item)) {
                    out.add(item);
                }
            }
            return out;
        });
    }

    private static GremlinStep mapCollection(Function<List<Object>, List<Object>> mapper) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>(in.size());
            for (Object o : in) {
                out.add(mapper.apply(asList(o)));
            }
            return out;
        };
    }

    private static List<Object> asList(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return value == null ? new ArrayList<>() : new ArrayList<>(List.of(value));
    }

    /** {@code all(p)} / {@code any(p)} / {@code none(p)}: a predicate over a collection traverser. */
    static GremlinStep allOf(String predicate) {
        return overCollectionItems(predicate, "all");
    }

    static GremlinStep anyOf(String predicate) {
        return overCollectionItems(predicate, "any");
    }

    static GremlinStep noneOf(String predicate) {
        return overCollectionItems(predicate, "none");
    }

    private static GremlinStep overCollectionItems(String predicate, String mode) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                List<Object> items = asList(o);
                boolean holds = switch (mode) {
                    case "all" -> !items.isEmpty() && items.stream().allMatch(item -> isMatch(item, predicate));
                    case "any" -> items.stream().anyMatch(item -> isMatch(item, predicate));
                    default -> items.stream().noneMatch(item -> isMatch(item, predicate));
                };
                if (holds) {
                    out.add(o);
                } else if (o instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /** A step argument written as a list ({@code [1, 2]}) or a single value. */
    private static List<Object> valueList(String argument) {
        String text = argument == null ? "" : argument.trim();
        if (text.length() >= 2 && text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        List<Object> out = new ArrayList<>();
        for (String part : splitTopLevel(text)) {
            if (!part.isBlank()) {
                out.add(coerce(unquote(part.trim())));
            }
        }
        return out;
    }

    private static Object coerce(String text) {
        if (text.equals("true")) {
            return Boolean.TRUE;
        }
        if (text.equals("false")) {
            return Boolean.FALSE;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            // not an int
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // not a long either
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            // not a number at all
        }
        return text;
    }

    /** Splits on top-level commas, ignoring quotes and brackets. */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static List<Object> filterElements(GremlinContext ctx, List<Object> in,
                                               java.util.function.Predicate<GremlinElement> predicate) {
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
    }

    private static Number asNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new QueryException("Expected a number, got: " + value);
        }
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte;
    }

    // --- statistics / terminal ---

    /**
     * {@code path()}: the elements the traversal visited. The walk that builds a path is run by
     * {@code GremlinGraphQuery#runPathCompiled}, which materialises a {@link GremlinPath} at this step; this
     * resolved step is a pass-through so the compiler can treat {@code path} like any other name.
     */
    static GremlinStep path() {
        return (ctx, in) -> in;
    }

    static GremlinStep count() {
        return (ctx, in) -> List.of((long) in.size());
    }

    static GremlinStep limit(long n) {
        return (ctx, in) -> {
            if (n < 0 || in.size() <= n) {
                return in.stream().limit(n).toList();
            }
            // the elements the limit drops are not part of the answer, so they are not part of the
            // subgraph a UI draws either - the same rule has()/filter() follow, and the same one a
            // capped read already applies when the store sees the cap
            for (int i = (int) n; i < in.size(); i++) {
                if (in.get(i) instanceof GremlinElement element) {
                    ctx.dropElement(element);
                }
            }
            return new ArrayList<>(in.subList(0, (int) n));
        };
    }

    /** {@code dedup()} / {@code dedup(key)}: the first traverser per element (or per property value). */
    static GremlinStep dedup(String key) {
        return (ctx, in) -> {
            // Deduplicate by element identity (label + id): the same vertex reached twice is the same
            // traverser however its properties were projected. Scalars (e.g. after values()) dedupe by
            // value. First-seen order is preserved. A key dedupes by that property instead.
            Set<Object> seen = new HashSet<>();
            List<Object> result = new ArrayList<>();
            for (Object element : in) {
                Object dedupKey = key != null ? orderKey(element, key)
                        : element instanceof GremlinElement ge ? new ElementKey(ge.label(), ge.id()) : element;
                if (seen.add(dedupKey)) {
                    result.add(element);
                }
            }
            return result;
        };
    }

    /** Element identity for {@code dedup()}: the label and the id, independent of the property projection. */
    private record ElementKey(String label, Object id) {
    }

    // --- internals ---

    /**
     * Whether the conditions following an edge hop can be evaluated by the store while it decodes.
     *
     * <p>That condition sees the decoded property bins only, so one that reads the element's label
     * or id has to stay a step - otherwise the hop would answer with what the synthetic element
     * (no label, no id) makes of it instead of what the traversal means.
     */
    static boolean canPushDown(List<GremlinQuery.Step> conditions) {
        for (GremlinQuery.Step step : conditions) {
            if (!step.name().equals("filter")) {
                continue;
            }
            for (String arg : step.args()) {
                if (GremlinFilterExpression.parse(arg).readsElementIdentity()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Builds the conditions an edge hop pushes into its read from the {@code has}/{@code filter}
     * steps that follow it. The semantics are those of the steps: a missing property does not
     * match, and {@code has} compares numbers numerically.
     */
    static List<EdgePropertyFilter> edgeFilters(List<GremlinQuery.Step> conditions) {
        List<EdgePropertyFilter> filters = new ArrayList<>();
        for (GremlinQuery.Step step : conditions) {
            if (step.name().equals("has")) {
                String key = step.args().get(0);
                String value = step.args().size() > 1 ? step.args().get(1) : null;
                filters.add(value == null ? EdgePropertyFilter.present(key)
                        : EdgePropertyFilter.of(key, stored -> stored != null && matches(stored, value)));
            } else if (step.name().equals("filter")) {
                GremlinFilterExpression expression =
                        GremlinFilterExpression.parse(String.join(" || ", step.args()));
                List<String> keys = List.copyOf(expression.propertyKeys());
                filters.add(new EdgePropertyFilter(keys, properties -> {
                    // the condition sees the properties it reads, exactly like the step does
                    Map<String, Object> present = new LinkedHashMap<>();
                    for (String key : keys) {
                        Object value = properties.get(key);
                        if (value != null) {
                            present.put(key, value);
                        }
                    }
                    return expression.test(new GremlinEdge("", "", EdgeSide.BOTH, null, present, 0L, 0L));
                }));
            }
        }
        return filters;
    }

    private static List<Object> hopEdges(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir) {
        return hopEdges(ctx, in, edgeName, dir, List.of(), 0);
    }

    /** An edge hop over several named classes: one hop per class, concatenated in argument order. */
    private static List<Object> hopEdges(GremlinContext ctx, List<Object> in, List<String> edgeNames, EdgeSide dir,
                                         List<EdgePropertyFilter> filters, int maxResults) {
        if (edgeNames == null || edgeNames.size() == 1) {
            return hopEdges(ctx, in, edgeNames == null ? null : edgeNames.get(0), dir, filters, maxResults);
        }
        List<Object> out = new ArrayList<>();
        for (String edgeName : edgeNames) {
            out.addAll(hopEdges(ctx, in, edgeName, dir, filters, maxResults));
        }
        return out;
    }

    /**
     * The edge traversers of an edge hop. Each one remembers the vertex it was read for
     * ({@link GremlinEdge#sourceVertexId()}), which is what makes "the vertices this traversal went
     * through" a fact rather than a guess: a later step can then ask for an algorithm over the
     * reached graph without the query having to spell the endpoints out with {@code otherV()}.
     */
    private static List<Object> hopEdges(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir,
                                         List<EdgePropertyFilter> filters, int maxResults) {
        List<GremlinVertex> vertices = standOn(ctx, in, dir);
        List<List<EdgeQueryResult>> perVertex = readEdgesByVertex(ctx, vertices, edgeName, dir,
                edgeReadProperties(ctx), filters, maxResults);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) {
            Object owner = vertices.get(i).id();
            for (EdgeQueryResult edge : perVertex.get(i)) {
                out.add(new GremlinEdge(edge.edgeName(), edge.edgeId(), edge.side(), edge.otherVertexId(),
                        edge.properties(), edge.createdTime(), edge.lastUpdatedTime(), owner));
            }
        }
        return out;
    }

    /**
     * Reads the edges of a hop for each input vertex, keeping them apart (`null` edge name = every
     * class). The batched and cached reads of the context stay batched; only the grouping survives.
     */
    private static List<List<EdgeQueryResult>> readEdgesByVertex(GremlinContext ctx,
                                                                 List<GremlinVertex> vertices,
                                                                 String edgeName, EdgeSide dir,
                                                                 List<String> edgeProperties,
                                                                 List<EdgePropertyFilter> filters,
                                                                 int maxResults) {
        List<EdgePropertyFilter> conditions = filters == null ? List.of() : filters;
        if (vertices.isEmpty()) {
            return List.of();
        }
        if (edgeName == null) {
            // every edge class: there is no single bin to push a condition into, so the conditions
            // are applied to whatever was read
            List<Object> ids = new ArrayList<>(vertices.size());
            for (GremlinVertex v : vertices) {
                ids.add(v.id());
            }
            List<List<EdgeQueryResult>> perVertex =
                    ctx.readAllEdgeClassesBatched(ids, dir, edgeProperties);
            List<List<EdgeQueryResult>> out = new ArrayList<>(perVertex.size());
            for (int i = 0; i < perVertex.size(); i++) {
                List<EdgeQueryResult> edges = perVertex.get(i);
                // the subgraph a UI draws needs every edge that was read, including these: an
                // unnamed hop is the one path where the read does not record them itself
                ctx.recordEdges(ids.get(i), edges);
                out.add(GraphQuery.applyFilters(edges, conditions, maxResults));
            }
            return out;
        }
        if (conditions.isEmpty() && maxResults <= 0) {
            if (vertices.size() == 1) {
                GremlinVertex v = vertices.get(0);
                return List.of(ctx.readEdgesCached(v.id(), v.label(), edgeName, dir, edgeProperties));
            }
            List<Object> ids = new ArrayList<>(vertices.size());
            for (GremlinVertex v : vertices) {
                ids.add(v.id());
            }
            return ctx.readAllEdgesBatchCached(edgeName, dir, ids, edgeProperties);
        }
        // a filtered or capped read: the store does the work, one vertex at a time with a budget
        return ctx.readEdgesFilteredByVertex(vertices, edgeName, dir, edgeProperties, conditions,
                maxResults);
    }

    private static List<Object> hopVertices(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir) {
        return hopVertices(ctx, in, edgeName, dir, 0);
    }

    private static List<Object> hopVertices(GremlinContext ctx, List<Object> in, String edgeName, EdgeSide dir,
                                            int maxResults) {
        List<GremlinVertex> vertices = standOn(ctx, in, dir);
        if (vertices.isEmpty()) {
            return List.of();
        }
        if (edgeName != null && !ctx.collectsSubgraph() && ctx.graphQuery().supportsNeighbourRead()) {
            // Nothing will draw these edges, and a vertex hop only wants the far end: read the neighbours
            // instead of the edges, so a store whose edges live in a record answers from their header and
            // builds none of them (the properties would be decoded and thrown away). When a subgraph is
            // collected the edges are read as before, because the UI shows their properties.
            return new ArrayList<>(readNeighbourVertices(ctx,
                    neighbourEdges(ctx, vertices, edgeName, dir, maxResults)));
        }
        // The intermediate edges of a vertex hop are internal: only their otherVertexId
        // is consumed, so no edge property projection is applied here.
        List<EdgeQueryResult> allEdges = new ArrayList<>();
        for (List<EdgeQueryResult> edges : readEdgesByVertex(ctx, vertices, edgeName, dir, List.of(),
                List.of(), maxResults)) {
            allEdges.addAll(edges);
        }
        return new ArrayList<>(readNeighbourVertices(ctx, allEdges));
    }

    /** A vertex hop over several named classes: one hop per class, concatenated in argument order. */
    private static List<Object> hopVertices(GremlinContext ctx, List<Object> in, List<String> edgeNames,
                                            EdgeSide dir) {
        if (edgeNames == null || edgeNames.size() == 1) {
            return hopVertices(ctx, in, edgeNames == null ? null : edgeNames.get(0), dir);
        }
        List<Object> out = new ArrayList<>();
        for (String edgeName : edgeNames) {
            out.addAll(hopVertices(ctx, in, edgeName, dir));
        }
        return out;
    }

    /**
     * The neighbours of every input vertex as edges without properties, so the vertex hop's own logic (the
     * neighbour type comes from the edge class and the side, the vertex from the far endpoint) is unchanged.
     * A positive {@code maxResults} stops reading once that many neighbours are collected.
     */
    private static List<EdgeQueryResult> neighbourEdges(GremlinContext ctx, List<GremlinVertex> vertices,
                                                        String edgeName, EdgeSide dir, int maxResults) {
        List<Object> ids = new ArrayList<>(vertices.size());
        for (GremlinVertex vertex : vertices) {
            ids.add(vertex.id());
        }
        List<EdgeQueryResult> out = new ArrayList<>();
        if (maxResults <= 0) {
            // uncapped: one batched read of every source
            for (List<com.pengcheng.phoenixgraph.query.storage.NeighbourRef> neighbours
                    : ctx.readNeighboursBatch(edgeName, dir, ids)) {
                for (com.pengcheng.phoenixgraph.query.storage.NeighbourRef neighbour : neighbours) {
                    out.add(neighbour.toEdgeResult());
                }
            }
            return out;
        }
        // capped: read source batch by source batch and stop as soon as the cap is reached
        for (List<Object> batch : ctx.batches(ids)) {
            for (List<com.pengcheng.phoenixgraph.query.storage.NeighbourRef> neighbours
                    : ctx.readNeighboursBatch(edgeName, dir, batch)) {
                for (com.pengcheng.phoenixgraph.query.storage.NeighbourRef neighbour : neighbours) {
                    if (out.size() >= maxResults) {
                        return out;
                    }
                    out.add(neighbour.toEdgeResult());
                }
            }
        }
        return out;
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

    /**
     * Reads the neighbours an edge hop reached. An edge with a nullable target has no far
     * endpoint ({@code otherVertexId == null}): it is a real edge - {@code outE} returns it -
     * but there is no vertex to continue to, so no traverser is produced for it.
     */
    private static List<GremlinVertex> readNeighbourVertices(GremlinContext ctx,
                                                             List<EdgeQueryResult> edges) {
        if (edges.isEmpty()) {
            return List.of();
        }
        List<EdgeQueryResult> reachable = new ArrayList<>(edges.size());
        for (EdgeQueryResult edge : edges) {
            if (edge.otherVertexId() != null) {
                reachable.add(edge);
            }
        }
        if (reachable.size() != edges.size()) {
            edges = reachable;
        }
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
                    ctx.recordHopEdge(edges.get(idx).edgeName(), edges.get(idx).edgeId(), ids[idx]);
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
                // the edge that reached this vertex: the drawn graph may only keep it while the
                // vertex is still part of the traversal (see GremlinContext#recordHopEdge)
                ctx.recordHopEdge(edges.get(idx).edgeName(), edges.get(idx).edgeId(), ids[idx]);
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

    /**
     * The vertices a hop starts from.
     *
     * <p>A hop is a vertex step, but a traversal can be standing on an <b>edge</b> -
     * {@code g.E('Payment').has('status','SUCCESS').outE('Payment')} asks what the receivers of the successful
     * payments pay. The edge is crossed to the endpoint it <b>points at</b> for an out hop and to the one it
     * <b>came from</b> for an in hop, which is the direction the money flows; {@code both} crosses to the
     * endpoint it points at as well, because an edge already names both of its ends and {@code otherV()} is
     * the step for switching sides.
     *
     * <p>The type of the crossed endpoint comes from the schema (the neighbour of that edge class on the side
     * read), so a hop over a whole class still knows what it stands on. When the traversal does not know that
     * endpoint - an edge it only met one end of - the error says so and names {@code otherV()}.
     */
    private static List<GremlinVertex> standOn(GremlinContext ctx, List<Object> in, EdgeSide dir) {
        List<GremlinVertex> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof GremlinEdge edge)) {
                out.add(requireVertex(o));
                continue;
            }
            boolean outgoing = dir != EdgeSide.IN;
            Object id = outgoing ? edge.targetEndpoint() : edge.sourceEndpoint();
            if (id == null) {
                throw new QueryException("Cannot walk " + (outgoing ? "out of" : "in to") + " edge "
                        + edge.id() + " ('" + edge.label() + "'): the traversal does not know that endpoint."
                        + " Read the edge from a vertex, or use otherV().");
            }
            String label = ctx.vertexResolver().neighborVertexName(edge.label(),
                    outgoing ? EdgeSide.OUT : EdgeSide.IN);
            if (label == null) {
                throw new QueryException("Cannot walk " + (outgoing ? "out of" : "in to") + " edge "
                        + edge.id() + ": the schema does not describe edge class '" + edge.label()
                        + "' far enough to know which vertex it reaches");
            }
            ctx.recordVertex(label, id);
            out.add(new GremlinVertex(label, id, Map.of(), 0L, 0L));
        }
        return out;
    }

    /**
     * {@code has('Account', 'accountStatus', 'FROZEN')}: the TinkerPop form that names the element's label as
     * well as its property, i.e. the same as {@code hasLabel('Account').has('accountStatus','FROZEN')}.
     *
     * <p>At the start of a traversal ({@code g.V().has('Account', ...)}) the label is what the traversal walks,
     * and the compiler turns it into the type scan there.
     */
    static GremlinStep hasLabel(String label, String key, String rawValue) {
        return (ctx, in) -> {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                GremlinElement element = requireElement(o);
                Object value = label.equals(element.label()) ? element.properties().get(key) : null;
                if (value != null && (rawValue == null || matches(value, rawValue))) {
                    out.add(o);
                } else {
                    ctx.dropElement(element);
                }
            }
            return out;
        };
    }

    /**
     * Compares a stored property value against a raw string token (numeric-aware).
     *
     * <p>Strings compare case-insensitively: the filter grammar is written by hand in a query box
     * ({@code filter(it.get().value('accountStatus').equals('Active'))}) and a status stored as
     * {@code ACTIVE} is the same status as the one typed {@code Active}.
     */
    static boolean matches(Object stored, String rawToken) {
        // TextP.containing(…)/startingWith(…)/within(…): the token is a predicate, not a value.
        // Checked before the type branches so a predicate also works on a numeric property.
        GremlinPredicate predicate = GremlinPredicate.parse(rawToken);
        if (predicate != null) {
            return predicate.test(stored);
        }
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
        return String.valueOf(stored).equalsIgnoreCase(rawToken);
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
