package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the supported openCypher subset over the storage-agnostic
 * {@link GraphQuery} layer.
 *
 * <p><b>Anchors.</b> The storage has no label index, so a match needs a starting
 * vertex: a node pattern with an {@code id} — inline ({@code (a:Account {id:'135'})})
 * or as an equality in {@code WHERE} ({@code MATCH (a:Account) WHERE a.id = '135'})
 * — whose variable also carries a label. A pattern may also start from a variable that
 * an earlier pattern bound, which is how {@code MATCH p1, p2} joins. Nothing is ever
 * scanned, everything is reached from an anchor.
 *
 * <p><b>Matching.</b> Every step expands the bound rows: edges are read by class and
 * direction (an untyped {@code -[r]-} reads all edge classes), the reached vertex is
 * read with the {@link NeighborVertexResolver} that maps (edge class, direction) to the
 * neighbour's vertex type, and the step's constraints (label, inline properties) are
 * applied. Reads are cached per execution and predicates run as soon as the variables
 * they mention are bound, so {@code WHERE} narrows the walk instead of filtering at the
 * end. Variable-length steps ({@code -[:TYPE*1..3]->}) expand breadth-first.
 *
 * <p><b>Projection.</b> {@code RETURN} items are evaluated per row; a projection
 * containing an aggregation groups the rows by the remaining items (Cypher's implicit
 * grouping). {@code DISTINCT}, {@code ORDER BY}, {@code SKIP} and {@code LIMIT} apply
 * afterwards, in that order.
 *
 * <p><b>Subgraph.</b> With {@link #executeSubgraph} every vertex and edge read while
 * matching is recorded (deduplicated by label + id) and returned, which is what a graph
 * renderer draws.
 */
public final class CypherGraphQuery {

    private final GraphQuery graphQuery;
    private final NeighborVertexResolver neighborResolver;
    private final CypherParameters parameters;

    public CypherGraphQuery(GraphQuery graphQuery) {
        this(graphQuery, NeighborVertexResolver.none(), CypherParameters.none());
    }

    public CypherGraphQuery(GraphQuery graphQuery, NeighborVertexResolver neighborResolver) {
        this(graphQuery, neighborResolver, CypherParameters.none());
    }

    public CypherGraphQuery(GraphQuery graphQuery, NeighborVertexResolver neighborResolver,
                            CypherParameters parameters) {
        this.graphQuery = Objects.requireNonNull(graphQuery, "graphQuery");
        this.neighborResolver = neighborResolver == null ? NeighborVertexResolver.none() : neighborResolver;
        this.parameters = parameters == null ? CypherParameters.none() : parameters;
    }

    /** The same engine over the same storage with other parameters. */
    public CypherGraphQuery withParameters(Map<String, Object> params) {
        return new CypherGraphQuery(graphQuery, neighborResolver, CypherParameters.of(params));
    }

    /** Executes a query and returns its rows. */
    public CypherResult execute(String cypher) {
        return execute(cypher, Map.of());
    }

    /** Executes a query with {@code $name} parameters (on top of the engine's own). */
    public CypherResult execute(String cypher, Map<String, Object> params) {
        return new Execution(merge(params), false).run(CypherParser.parse(cypher));
    }

    /** Executes a query and additionally returns the induced subgraph it visited. */
    public CypherResult executeSubgraph(String cypher, Map<String, Object> params) {
        return new Execution(merge(params), true).run(CypherParser.parse(cypher));
    }

    /** The engine's parameters, overridden by the call's. */
    private CypherParameters merge(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return parameters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(parameters.values());
        merged.putAll(params);
        return CypherParameters.of(merged);
    }

    /** Just the rows, for callers that do not need the subgraph. */
    public List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return execute(cypher, params).rows();
    }

    // ------------------------------------------------------------------ execution

    /** A projected row plus the matched rows it came from, so ORDER BY can evaluate any key. */
    private record Projected(Map<String, Object> row, List<CypherRow> source) {
    }

    /** The state of one execution: bindings, read caches and the collected subgraph. */
    private final class Execution {

        private final CypherParameters params;
        private final boolean collectSubgraph;
        private final Map<Object, VertexQueryResult> vertexCache = new LinkedHashMap<>();
        private final Map<String, List<EdgeQueryResult>> edgeCache = new LinkedHashMap<>();
        private final Map<String, Subgraph.Vertex> subgraphVertices = new LinkedHashMap<>();
        private final Map<String, Subgraph.Edge> subgraphEdges = new LinkedHashMap<>();
        /**
         * The variables whose properties the query reads. A pattern variable that is only used
         * for its identity - matched, counted, grouped by something else - is bound without
         * reading its vertex, which is what keeps an aggregation like
         * {@code MATCH (a {id:'135'})-[p:Payment]->(b) RETURN p.status, count(*)} from turning
         * into one read per matched row.
         */
        private Set<String> variablesWithProperties = Set.of();

        /** Every variable the query's patterns bind. */
        private static Set<String> boundVariables(CypherQuery query) {
            Set<String> variables = new LinkedHashSet<>();
            for (CypherPattern.Path path : query.matches()) {
                for (CypherPattern.Node node : path.nodes()) {
                    if (node.variable() != null) {
                        variables.add(node.variable());
                    }
                }
                for (CypherPattern.Relationship relationship : path.relationships()) {
                    if (relationship.variable() != null) {
                        variables.add(relationship.variable());
                    }
                }
            }
            return variables;
        }

        private Execution(CypherParameters params, boolean collectSubgraph) {
            this.params = params;
            this.collectSubgraph = collectSubgraph;
        }

        CypherResult run(CypherQuery query) {
            CypherExpression where = query.where() == null ? null : bind(query.where());
            List<CypherQuery.ReturnItem> projection = query.projection().stream()
                    .map(item -> new CypherQuery.ReturnItem(bind(item.expression()), item.text(), item.alias()))
                    .toList();
            List<CypherQuery.OrderKey> orderBy = query.orderBy().stream()
                    .map(key -> new CypherQuery.OrderKey(bind(key.expression()), key.text(), key.descending()))
                    .toList();

            variablesWithProperties = variablesNeedingProperties(query);
            List<CypherRow> rows = new ArrayList<>();
            rows.add(new CypherRow());
            for (CypherPattern.Path path : bindPatterns(query.matches())) {
                rows = matchPath(path, rows, where);
            }
            if (where != null) {
                rows = filter(rows, where);
            }

            boolean allVariables = projection.size() == 1 && projection.get(0).text().equals("*");
            List<String> columns = allVariables ? patternVariables(query) : projection.stream()
                    .map(CypherQuery.ReturnItem::column).distinct().toList();
            List<Projected> projected = allVariables
                    ? projectAll(rows, columns)
                    : project(rows, projection, columns);
            if (query.distinct()) {
                projected = distinct(projected, columns);
            }
            projected = order(projected, orderBy, columns);
            projected = slice(projected, query.skip(), query.limit());
            return new CypherResult(columns, projected.stream().map(Projected::row).toList(),
                    collectSubgraph ? buildSubgraph() : Subgraph.EMPTY);
        }

        /** Replaces parameters inside node property maps ({@code {id: $id}}). */
        private List<CypherPattern.Path> bindPatterns(List<CypherPattern.Path> matches) {
            List<CypherPattern.Path> bound = new ArrayList<>(matches.size());
            for (CypherPattern.Path path : matches) {
                List<CypherPattern.Step> steps = new ArrayList<>(path.steps().size());
                for (CypherPattern.Step step : path.steps()) {
                    steps.add(new CypherPattern.Step(step.relationship(),
                            bindNode(step.node())));
                }
                bound.add(new CypherPattern.Path(bindNode(path.start()), steps));
            }
            return bound;
        }

        private CypherPattern.Node bindNode(CypherPattern.Node node) {
            if (node.properties().isEmpty()) {
                return node;
            }
            Map<String, CypherExpression> properties = new LinkedHashMap<>();
            node.properties().forEach((key, value) -> properties.put(key, bind(value)));
            return new CypherPattern.Node(node.variable(), node.label(), properties);
        }

        /** The variables a query is expected to bind, in pattern order (for {@code RETURN *}). */
        private List<String> patternVariables(CypherQuery query) {
            Set<String> variables = new LinkedHashSet<>();
            for (CypherPattern.Path path : query.matches()) {
                // the order variables appear in the pattern: a, r, b (not nodes first)
                add(variables, path.start().variable());
                for (CypherPattern.Step step : path.steps()) {
                    add(variables, step.relationship().variable());
                    add(variables, step.node().variable());
                }
            }
            return List.copyOf(variables);
        }

        private static void add(Set<String> variables, String variable) {
            if (variable != null && !variable.startsWith("  hop")) {
                variables.add(variable);
            }
        }

        // --- parameters ---

        /** Replaces every {@code $name} with its value (failing fast when it is missing). */
        private CypherExpression bind(CypherExpression expression) {
            if (expression instanceof CypherExpression.Parameter parameter) {
                return new CypherExpression.Literal(params.require(parameter.name()));
            }
            if (expression instanceof CypherExpression.Compare compare) {
                return new CypherExpression.Compare(bind(compare.left()), compare.operator(), bind(compare.right()));
            }
            if (expression instanceof CypherExpression.And and) {
                return new CypherExpression.And(and.operands().stream().map(this::bind).toList());
            }
            if (expression instanceof CypherExpression.Or or) {
                return new CypherExpression.Or(or.operands().stream().map(this::bind).toList());
            }
            if (expression instanceof CypherExpression.Not not) {
                return new CypherExpression.Not(bind(not.operand()));
            }
            if (expression instanceof CypherExpression.In in) {
                return new CypherExpression.In(bind(in.value()), in.list().stream().map(this::bind).toList());
            }
            if (expression instanceof CypherExpression.IsNull isNull) {
                return new CypherExpression.IsNull(bind(isNull.value()), isNull.negated());
            }
            if (expression instanceof CypherExpression.StringOp stringOp) {
                return new CypherExpression.StringOp(bind(stringOp.value()), stringOp.operator(),
                        bind(stringOp.pattern()));
            }
            if (expression instanceof CypherExpression.Function function) {
                return new CypherExpression.Function(function.name(),
                        function.arguments().stream().map(this::bind).toList(),
                        function.distinct(), function.star());
            }
            if (expression instanceof CypherExpression.ListLiteral list) {
                return new CypherExpression.ListLiteral(list.items().stream().map(this::bind).toList());
            }
            return expression;
        }

        // --- matching ---

        private List<CypherRow> matchPath(CypherPattern.Path path, List<CypherRow> startRows,
                                        CypherExpression where) {
            List<CypherPattern.Node> nodes = path.nodes();
            int anchor = anchorIndex(path, where, startRows);
            List<CypherRow> rows = anchorRows(nodes.get(anchor), anchor, startRows, where);
            if (rows.isEmpty()) {
                return rows;
            }
            // forward from the anchor
            String variable = nodes.get(anchor).variable();
            for (int index = anchor; index < path.steps().size(); index++) {
                CypherPattern.Step step = path.steps().get(index);
                if (variable == null) {
                    throw new QueryException("the node the pattern expands from needs a variable");
                }
                // an anonymous node in the middle of a path still has to be reachable for the
                // next step, so it is bound to an internal variable
                CypherPattern.Node target = step.node();
                if (target.variable() == null && index < path.steps().size() - 1) {
                    target = new CypherPattern.Node("  hop" + index, target.label(), target.properties());
                }
                rows = expand(rows, variable, step.relationship(), target);
                if (where != null) {
                    rows = applyWhenReady(rows, where);
                }
                variable = target.variable();
            }
            // and backwards for the steps before the anchor
            for (int index = anchor; index > 0; index--) {
                CypherPattern.Step step = path.steps().get(index - 1);
                CypherPattern.Node near = path.node(index);
                CypherPattern.Node far = path.node(index - 1);
                if (near.variable() == null) {
                    throw new QueryException("the node the pattern expands backwards from needs a variable");
                }
                CypherPattern.Relationship reversed = new CypherPattern.Relationship(step.relationship().variable(),
                        step.relationship().types(), opposite(step.relationship().direction()),
                        step.relationship().minHops(), step.relationship().maxHops());
                rows = expand(rows, near.variable(), reversed, far);
                if (where != null) {
                    rows = applyWhenReady(rows, where);
                }
            }
            return rows;
        }

        /** Which node of the path the match starts from: a bound variable, or an id. */
        private int anchorIndex(CypherPattern.Path path, CypherExpression where, List<CypherRow> startRows) {
            List<CypherPattern.Node> nodes = path.nodes();
            for (int index = 0; index < nodes.size(); index++) {
                String variable = nodes.get(index).variable();
                if (variable != null && !startRows.isEmpty() && startRows.get(0).bound(variable)) {
                    return index;
                }
            }
            for (int index = 0; index < nodes.size(); index++) {
                CypherPattern.Node node = nodes.get(index);
                if (anchorValue(node, where) == null) {
                    continue;
                }
                if (node.label() == null) {
                    throw new QueryException("the node that starts the search needs a label, e.g. ("
                            + (node.variable() == null ? "n" : node.variable()) + ":Account {id:'135'})");
                }
                return index;
            }
            throw new QueryException("a MATCH needs a starting vertex: give one node an id, e.g. "
                    + "MATCH (a:Account {id:'135'}) or MATCH (a:Account) WHERE a.id = '135'");
        }

        /** The rows the match starts with, once the anchor node is bound and constrained. */
        private List<CypherRow> anchorRows(CypherPattern.Node node, int index, List<CypherRow> startRows,
                                          CypherExpression where) {
            String variable = node.variable();
            if (variable != null && !startRows.isEmpty() && startRows.get(0).bound(variable)) {
                // already bound (a join across patterns): keep the binding from the earlier match
                List<CypherRow> rows = new ArrayList<>();
                for (CypherRow row : startRows) {
                    CypherRow constrained = applyNodeConstraints(row, node);
                    if (constrained != null) {
                        rows.add(constrained);
                    }
                }
                return rows;
            }
            Object id = bind(anchorValue(node, where)).evaluate(new CypherRow());
            if (id == null) {
                throw new QueryException("the vertex id that starts the search is null");
            }
            CypherElement element = readVertex(node.label(), id);
            if (element == null) {
                // {id: 135} against a record keyed by the string "135" (or the other way
                // round): the id is the storage key, so try the other representation once
                Object alternative = alternativeId(id);
                if (alternative != null) {
                    element = readVertex(node.label(), alternative);
                }
            }
            if (element == null) {
                return List.of();
            }
            List<CypherRow> rows = new ArrayList<>();
            for (CypherRow base : startRows) {
                CypherRow row = base.copy();
                if (variable != null) {
                    row.bind(variable, element);
                }
                CypherRow constrained = applyNodeConstraints(row, node);
                if (constrained != null) {
                    rows.add(constrained);
                }
            }
            return rows;
        }

        /** The same id as a number or as a string, or {@code null} when there is no other form. */
        private static Object alternativeId(Object id) {
            if (id instanceof Number number) {
                return String.valueOf(number.longValue());
            }
            if (id instanceof String text) {
                try {
                    return Long.valueOf(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        /** The id an anchor node is pinned to: an inline property or {@code WHERE <alias>.id = ...}. */
        private CypherExpression anchorValue(CypherPattern.Node node, CypherExpression where) {
            CypherExpression inline = node.property("id");
            if (inline != null) {
                return inline;
            }
            return node.variable() == null ? null : idEquals(where, node.variable());
        }

        private CypherExpression idEquals(CypherExpression expression, String variable) {
            if (expression == null) {
                return null;
            }
            if (expression instanceof CypherExpression.Compare compare && compare.operator().equals("=")) {
                if (isIdOf(compare.left(), variable)) {
                    return compare.right();
                }
                if (isIdOf(compare.right(), variable)) {
                    return compare.left();
                }
                return null;
            }
            if (expression instanceof CypherExpression.And and) {
                for (CypherExpression operand : and.operands()) {
                    CypherExpression found = idEquals(operand, variable);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private static boolean isIdOf(CypherExpression expression, String variable) {
            return expression instanceof CypherExpression.Property property
                    && property.variable().equals(variable) && property.key().equalsIgnoreCase("id");
        }

        private static CypherPattern.Direction opposite(CypherPattern.Direction direction) {
            return switch (direction) {
                case OUT -> CypherPattern.Direction.IN;
                case IN -> CypherPattern.Direction.OUT;
                case BOTH -> CypherPattern.Direction.BOTH;
            };
        }

        private List<CypherRow> expand(List<CypherRow> rows, String fromVariable,
                                      CypherPattern.Relationship relationship, CypherPattern.Node target) {
            List<CypherRow> out = new ArrayList<>();
            for (CypherRow row : rows) {
                CypherElement from = row.get(fromVariable);
                if (from == null) {
                    throw new QueryException("the pattern uses '" + fromVariable + "' before it is matched");
                }
                if (relationship.variableLength()) {
                    out.addAll(expandVariableLength(row, from, fromVariable, relationship, target));
                } else {
                    out.addAll(expandOneHop(row, from, fromVariable, relationship, target));
                }
            }
            return out;
        }

        private List<CypherRow> expandOneHop(CypherRow row, CypherElement from, String fromVariable,
                                             CypherPattern.Relationship relationship, CypherPattern.Node target) {
            List<CypherRow> out = new ArrayList<>();
            for (EdgeQueryResult edge : edges(from, relationship)) {
                String neighborLabel = neighborResolver.neighborVertexName(edge.edgeName(), edge.side());
                if (neighborLabel == null) {
                    neighborLabel = target.label() != null ? target.label() : from.typeName();
                }
                // only read the neighbour when the query uses its properties: an aggregation over the
            // relationship alone needs its id, not a record
            CypherElement neighbour = needsProperties(target.variable())
                    ? readVertex(neighborLabel, edge.otherVertexId())
                    : vertexReference(neighborLabel, edge.otherVertexId());
                if (neighbour == null) {
                    continue;
                }
                CypherRow next = row.copy();
                String relationshipVariable = relationship.variable();
                if (relationshipVariable != null && !relationshipVariable.equals(fromVariable)
                        && !relationshipVariable.equals(target.variable())) {
                    next.bind(relationshipVariable, relationshipElement(from, edge));
                }
                if (target.variable() != null && !target.variable().equals(fromVariable)) {
                    next.bind(target.variable(), neighbour);
                }
                CypherRow constrained = applyNodeConstraints(next, target);
                if (constrained != null) {
                    out.add(constrained);
                }
            }
            return out;
        }

        /**
         * Breadth-first expansion of a variable-length relationship. Intermediate hops bind
         * a scratch variable; every depth from {@code minHops} on also binds the target
         * variable, so {@code -[*1..2]->(c)} yields {@code c} for one and two hops alike.
         */
        private List<CypherRow> expandVariableLength(CypherRow row, CypherElement from, String fromVariable,
                                                     CypherPattern.Relationship relationship,
                                                     CypherPattern.Node target) {
            if (relationship.variable() != null) {
                throw new QueryException("a variable-length relationship cannot bind a name yet ("
                        + relationship.variable() + ")");
            }
            List<CypherRow> results = new ArrayList<>();
            List<CypherRow> frontier = new ArrayList<>();
            frontier.add(row.copy());
            String currentVariable = fromVariable;
            for (int depth = 1; depth <= relationship.maxHops() && !frontier.isEmpty(); depth++) {
                String scratch = "  hop" + depth;
                CypherPattern.Node stepNode = new CypherPattern.Node(scratch, target.label(), target.properties());
                CypherPattern.Relationship single = new CypherPattern.Relationship(null, relationship.types(),
                        relationship.direction(), 1, 1);
                List<CypherRow> next = new ArrayList<>();
                for (CypherRow currentRow : frontier) {
                    next.addAll(expandOneHop(currentRow, currentRow.get(currentVariable), currentVariable,
                            single, stepNode));
                }
                if (depth >= relationship.minHops()) {
                    for (CypherRow reached : next) {
                        CypherRow named = target.variable() == null ? reached
                                : reached.bind(target.variable(), reached.get(scratch));
                        results.add(named);
                    }
                }
                frontier = next;
                currentVariable = scratch;
            }
            return results;
        }

        private CypherRow applyNodeConstraints(CypherRow row, CypherPattern.Node node) {
            String variable = node.variable();
            if (variable == null) {
                return row;
            }
            CypherElement element = row.get(variable);
            if (element == null) {
                return row;
            }
            if (node.label() != null && !node.label().equals(element.typeName())) {
                return null;
            }
            for (Map.Entry<String, CypherExpression> entry : node.properties().entrySet()) {
                Object expected = entry.getValue().evaluate(row);
                Object actual = element.property(entry.getKey());
                if (expected == null || actual == null
                        || CypherExpression.Values.compare(actual, expected) != 0) {
                    return null;
                }
            }
            return row;
        }


        /**
         * The variables whose property values the query reads: those a projection mentions as a
         * property (other than {@code id}, which is the record key), those a WHERE/ORDER BY
         * mentions, those an inline node constraint constrains on a property, and - because the
         * caller may draw it - any variable returned on its own.
         *
         * <p>Everything else is matched by id and label only.
         */
        private static Set<String> variablesNeedingProperties(CypherQuery query) {
            boolean allVariables = query.projection().size() == 1
                    && "*".equals(query.projection().get(0).text().trim());
            if (allVariables) {
                return boundVariables(query);
            }
            Set<String> needed = new LinkedHashSet<>();
            Set<String> everyVariable = boundVariables(query);
            for (CypherQuery.ReturnItem item : query.projection()) {
                if (isBareVariable(item.expression())) {
                    needed.add(((CypherExpression.VariableReference) item.expression()).name());
                }
                collectPropertyVariables(item.expression(), needed);
                collectPropertyVariablesFromText(item.text(), everyVariable, needed);
            }
            collectPropertyVariables(query.where(), needed);
            collectPropertyVariablesFromText(query.where() == null ? null : query.where().toString(), everyVariable,
                    needed);
            for (CypherQuery.OrderKey key : query.orderBy()) {
                collectPropertyVariables(key.expression(), needed);
                collectPropertyVariablesFromText(key.text(), everyVariable, needed);
            }
            for (CypherPattern.Path path : query.matches()) {
                for (CypherPattern.Node node : path.nodes()) {
                    if (node.variable() == null) {
                        continue;
                    }
                    for (String property : node.properties().keySet()) {
                        if (!"id".equals(property)) {
                            needed.add(node.variable());
                        }
                    }
                }
            }
            return needed;
        }

        /**
         * The same, read off the source text: {@code b.accountType} needs the neighbour's
         * properties, while {@code b.id} does not (the id is the record key).
         *
         * <p>The walk above covers the expression tree; this covers whatever shape the parser
         * gives an expression, so a new expression type cannot silently skip a read.
         */
        private static void collectPropertyVariablesFromText(String text, Set<String> variables, Set<String> out) {
            if (text == null || text.isBlank()) {
                return;
            }
            for (String variable : variables) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\\b" + java.util.regex.Pattern.quote(variable)
                                + "\\.([A-Za-z_][A-Za-z0-9_]*)")
                        .matcher(text);
                while (matcher.find()) {
                    if (!"id".equals(matcher.group(1))) {
                        out.add(variable);
                    }
                }
            }
        }

        /** Whether an expression is just a variable name ({@code RETURN b}). */
        private static boolean isBareVariable(CypherExpression expression) {
            return expression instanceof CypherExpression.VariableReference;
        }

        /** Collects the variables an expression reads a property of ({@code id} is the key, not a read). */
        private static void collectPropertyVariables(CypherExpression expression, Set<String> out) {
            if (expression == null) {
                return;
            }
            if (expression instanceof CypherExpression.Property property) {
                if (!"id".equals(property.key())) {
                    out.add(property.variable());
                }
                return;
            }
            for (CypherExpression child : childrenOf(expression)) {
                collectPropertyVariables(child, out);
            }
        }

        /** The sub-expressions of an expression, so the walk above can reach every property use. */
        private static List<CypherExpression> childrenOf(CypherExpression expression) {
            if (expression instanceof CypherExpression.Compare compare) {
                return List.of(compare.left(), compare.right());
            }
            if (expression instanceof CypherExpression.And and) {
                return and.operands();
            }
            if (expression instanceof CypherExpression.Or or) {
                return or.operands();
            }
            if (expression instanceof CypherExpression.Not not) {
                return List.of(not.operand());
            }
            if (expression instanceof CypherExpression.In in) {
                List<CypherExpression> children = new ArrayList<>();
                children.add(in.value());
                children.addAll(in.list());
                return children;
            }
            if (expression instanceof CypherExpression.IsNull isNull) {
                return List.of(isNull.value());
            }
            if (expression instanceof CypherExpression.StringOp stringOp) {
                return List.of(stringOp.value());
            }
            if (expression instanceof CypherExpression.Function function) {
                return function.arguments();
            }
            return List.of();
        }

        /** Binds a vertex by id and label without reading it (its properties are not used). */
        private CypherElement vertexReference(String label, Object id) {
            recordVertex(label, id, Map.of(), 0L, 0L);
            return CypherElement.vertex(label, id, Map.of());
        }

        /** Whether a variable's properties have to be read. */
        private boolean needsProperties(String variable) {
            return variable == null || variablesWithProperties.contains(variable);
        }

        // --- reads ---

        private CypherElement readVertex(String label, Object id) {
            VertexQueryResult vertex = vertexCache.get(id);
            if (vertex == null) {
                vertex = graphQuery.readVertex(id, label, List.of());
                vertexCache.put(id, vertex);
            }
            if (vertex == null) {
                return null;
            }
            recordVertex(vertex.vertexName(), vertex.vertexId(), vertex.properties(),
                    vertex.createdTime(), vertex.lastUpdatedTime());
            return CypherElement.vertex(vertex.vertexName(), vertex.vertexId(), vertex.properties());
        }

        private List<EdgeQueryResult> edges(CypherElement from, CypherPattern.Relationship relationship) {
            List<String> types = relationship.types().isEmpty()
                    ? java.util.Collections.singletonList(null) : relationship.types();
            List<EdgeQueryResult> out = new ArrayList<>();
            for (String type : types) {
                String key = from.typeName() + "|" + from.id() + "|" + type + "|" + relationship.direction();
                List<EdgeQueryResult> cached = edgeCache.get(key);
                if (cached == null) {
                    cached = readEdges(from, type, relationship.direction());
                    edgeCache.put(key, cached);
                }
                out.addAll(cached);
            }
            return out;
        }

        private List<EdgeQueryResult> readEdges(CypherElement from, String type, CypherPattern.Direction direction) {
            List<EdgeQueryResult> edges;
            if (type == null) {
                // an untyped relationship reads every edge class of the vertex type
                List<List<EdgeQueryResult>> perVertex = graphQuery.readAllEdgesBatch(List.of(from.id()),
                        side(direction), List.of());
                edges = perVertex.isEmpty() ? List.of() : perVertex.get(0);
            } else {
                edges = graphQuery.readEdges(from.id(), from.typeName(), type, side(direction), List.of());
            }
            for (EdgeQueryResult edge : edges) {
                recordEdge(from, edge);
            }
            return edges;
        }

        private static EdgeSide side(CypherPattern.Direction direction) {
            return switch (direction) {
                case OUT -> EdgeSide.OUT;
                case IN -> EdgeSide.IN;
                case BOTH -> EdgeSide.BOTH;
            };
        }

        private CypherElement relationshipElement(CypherElement from, EdgeQueryResult edge) {
            Object source = edge.side() == EdgeSide.OUT ? from.id() : edge.otherVertexId();
            Object target = edge.side() == EdgeSide.OUT ? edge.otherVertexId() : from.id();
            return CypherElement.relationship(edge.edgeName(), edge.edgeId(), source, target, edge.properties());
        }

        // --- subgraph ---

        private void recordVertex(String label, Object id, Map<String, Object> properties,
                                  long createdTime, long lastUpdatedTime) {
            if (!collectSubgraph) {
                return;
            }
            Object text = id == null ? null : String.valueOf(id);
            subgraphVertices.putIfAbsent(label + "::" + text,
                    new Subgraph.Vertex(label, text, properties, createdTime, lastUpdatedTime));
        }

        private void recordEdge(CypherElement from, EdgeQueryResult edge) {
            if (!collectSubgraph) {
                return;
            }
            Object source = edge.side() == EdgeSide.OUT ? from.id() : edge.otherVertexId();
            Object target = edge.side() == EdgeSide.OUT ? edge.otherVertexId() : from.id();
            subgraphEdges.putIfAbsent(edge.edgeName() + "::" + edge.edgeId(),
                    new Subgraph.Edge(edge.edgeName(), edge.edgeId(), source, target, edge.properties(),
                            edge.createdTime(), edge.lastUpdatedTime()));
        }

        private Subgraph buildSubgraph() {
            return new Subgraph(List.copyOf(subgraphVertices.values()), List.copyOf(subgraphEdges.values()));
        }

        // --- projection ---

        private List<CypherRow> filter(List<CypherRow> rows, CypherExpression predicate) {
            List<CypherRow> out = new ArrayList<>(rows.size());
            for (CypherRow row : rows) {
                if (CypherExpression.Values.truthy(predicate.evaluate(row))) {
                    out.add(row);
                }
            }
            return out;
        }

        /** Applies a predicate as soon as every variable it mentions is bound. */
        private List<CypherRow> applyWhenReady(List<CypherRow> rows, CypherExpression predicate) {
            if (rows.isEmpty()) {
                return rows;
            }
            for (String variable : predicate.variables()) {
                if (!rows.get(0).bound(variable)) {
                    return rows;
                }
            }
            return filter(rows, predicate);
        }

        /** {@code RETURN *}: one row per match with one column per pattern variable. */
        private List<Projected> projectAll(List<CypherRow> rows, List<String> variables) {
            List<Projected> out = new ArrayList<>(rows.size());
            for (CypherRow row : rows) {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String variable : variables) {
                    CypherElement element = row.get(variable);
                    projected.put(variable, element == null ? null : element.describe());
                }
                out.add(new Projected(projected, List.of(row)));
            }
            return out;
        }

        private List<Projected> project(List<CypherRow> rows, List<CypherQuery.ReturnItem> projection,
                                        List<String> columns) {
            boolean aggregated = projection.stream().anyMatch(CypherQuery.ReturnItem::aggregated);
            List<Projected> out = new ArrayList<>();
            if (!aggregated) {
                for (CypherRow row : rows) {
                    Map<String, Object> projected = new LinkedHashMap<>();
                    for (int i = 0; i < projection.size(); i++) {
                        projected.put(columns.get(i), projection.get(i).expression().evaluate(row));
                    }
                    out.add(new Projected(projected, List.of(row)));
                }
                return out;
            }
            for (List<CypherRow> group : groups(rows, projection)) {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (int i = 0; i < projection.size(); i++) {
                    projected.put(columns.get(i),
                            CypherExpression.Aggregate.evaluateAny(projection.get(i).expression(), group));
                }
                out.add(new Projected(projected, group));
            }
            return out;
        }

        /** Groups rows by the non-aggregating items (Cypher's implicit grouping). */
        private List<List<CypherRow>> groups(List<CypherRow> rows, List<CypherQuery.ReturnItem> projection) {
            List<CypherQuery.ReturnItem> keys = projection.stream()
                    .filter(item -> !item.aggregated()).toList();
            Map<List<Object>, List<CypherRow>> grouped = new LinkedHashMap<>();
            for (CypherRow row : rows) {
                List<Object> key = new ArrayList<>(keys.size());
                for (CypherQuery.ReturnItem item : keys) {
                    Object value = item.expression().evaluate(row);
                    key.add(value instanceof Map<?, ?> map ? map.get("id") : value);
                }
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
            if (grouped.isEmpty()) {
                // an aggregation over no rows still yields one row (count(*) = 0)
                grouped.put(List.of(), List.of());
            }
            return new ArrayList<>(grouped.values());
        }

        private List<Projected> distinct(List<Projected> rows, List<String> columns) {
            List<Projected> out = new ArrayList<>();
            Set<List<Object>> seen = new LinkedHashSet<>();
            for (Projected projected : rows) {
                List<Object> key = new ArrayList<>(columns.size());
                for (String column : columns) {
                    Object value = projected.row().get(column);
                    key.add(value == null ? null : String.valueOf(value));
                }
                if (seen.add(key)) {
                    out.add(projected);
                }
            }
            return out;
        }

        private List<Projected> order(List<Projected> projected, List<CypherQuery.OrderKey> orderBy,
                                      List<String> columns) {
            if (orderBy.isEmpty()) {
                return projected;
            }
            List<Integer> order = new ArrayList<>(projected.size());
            for (int i = 0; i < projected.size(); i++) {
                order.add(i);
            }
            Comparator<Integer> comparator = null;
            for (CypherQuery.OrderKey key : orderBy) {
                Comparator<Integer> next = (a, b) -> compareNullable(
                        orderKey(projected.get(a), key, columns), orderKey(projected.get(b), key, columns));
                if (key.descending()) {
                    next = next.reversed();
                }
                comparator = comparator == null ? next : comparator.thenComparing(next);
            }
            order.sort(comparator);
            List<Projected> out = new ArrayList<>(projected.size());
            for (int index : order) {
                out.add(projected.get(index));
            }
            return out;
        }

        /**
         * What an {@code ORDER BY} key sorts by: the projected column when the key names one
         * (an alias, or the expression as written), otherwise the expression evaluated against
         * the rows the projection came from.
         */
        private Object orderKey(Projected projected, CypherQuery.OrderKey key, List<String> columns) {
            if (columns.contains(key.text())) {
                return projected.row().get(key.text());
            }
            if (key.expression().isAggregating()) {
                return CypherExpression.Aggregate.evaluateAny(key.expression(), projected.source());
            }
            return projected.source().isEmpty() ? null : key.expression().evaluate(projected.source().get(0));
        }

        private static int compareNullable(Object a, Object b) {
            Object left = orderingValue(a);
            Object right = orderingValue(b);
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return -1;
            }
            if (right == null) {
                return 1;
            }
            return CypherExpression.Values.compare(left, right);
        }

        /** An element sorts by its id, so {@code ORDER BY a} is meaningful. */
        private static Object orderingValue(Object value) {
            if (value instanceof Map<?, ?> map && map.containsKey("id")) {
                return map.get("id");
            }
            return value;
        }

        private static List<Projected> slice(List<Projected> rows, long skip, long limit) {
            int from = (int) Math.min(Math.max(skip, 0), rows.size());
            int to = limit < 0 ? rows.size() : (int) Math.min(from + limit, rows.size());
            return new ArrayList<>(rows.subList(from, Math.max(from, to)));
        }
    }
}
