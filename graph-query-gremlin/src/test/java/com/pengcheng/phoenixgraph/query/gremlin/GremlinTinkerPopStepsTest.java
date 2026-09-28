package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pengcheng.phoenixgraph.query.QueryException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The TinkerPop map/filter/reduce steps: {@code id/label/hasId/hasNot/hasKey/hasValue/is}, the slices
 * ({@code range/skip/tail}), {@code fold/unfold}, the reductions ({@code sum/mean/max/min}), {@code order},
 * {@code groupCount}, {@code valueMap} and the string steps.
 */
class GremlinTinkerPopStepsTest {

    private final GraphQuery graphQuery = mock(GraphQuery.class);
    private final GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery,
            NeighborVertexResolver.none());

    private static Map<String, Object> propertiesOf(Object id) {
        return switch (String.valueOf(id)) {
            case "a" -> Map.of("age", 30, "name", "ann", "status", "ACTIVE");
            case "b" -> Map.of("age", 20, "name", "bob");
            case "c" -> Map.of("age", 40, "name", "cid", "status", "FROZEN");
            case "d" -> Map.of("age", 25, "name", "dee", "status", "ACTIVE");
            default -> Map.of();
        };
    }

    @BeforeEach
    void stubVertices() {
        when(graphQuery.readVertex(any(), anyString(), anyList()))
                .thenAnswer(inv -> new VertexQueryResult(inv.getArgument(1), inv.getArgument(0),
                        propertiesOf(inv.getArgument(0)), 1L, 2L));
        when(graphQuery.readVertices(anyString(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    List<Object> ids = inv.getArgument(1);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(new VertexQueryResult(name, id, propertiesOf(id), 1L, 2L));
                    }
                    return out;
                });
    }

    private Object one(String query) {
        List<Object> result = gremlin.execute(query);
        return result.isEmpty() ? null : result.get(0);
    }

    @Test
    void hasLabelKeepsTheTypeItNames() {
        assertEquals(2, gremlin.execute("g.V('a','b','Account').hasLabel('Account')").size());
        assertEquals(0, gremlin.execute("g.V('a','b','Account').hasLabel('Payment')").size());
        assertEquals(2, gremlin.execute("g.V('a','b','Account').hasLabel('account')").size(),
                "the label match ignores case");
    }

    @Test
    void predicatesAreEvaluatedAndAnUnknownOneIsAnError() {
        assertEquals(List.of("c"), gremlin.execute("g.V('a','b','c','Account').has('status', eq('FROZEN'))")
                .stream().map(o -> ((GremlinVertex) o).id()).toList());
        assertEquals(1, gremlin.execute("g.V('a','b','c','Account').has('status', neq('FROZEN'))").size(),
                "b has no status at all, and has(key, ...) needs the key to be there");
        assertEquals(1, gremlin.execute("g.V('a','b','c','Account').has('status', not(within('ACTIVE')))").size());
        assertEquals(2, gremlin.execute("g.V('a','b','c','d','Account').has('age', between(20, 30))").size(),
                "between is inclusive of the lower bound and exclusive of the upper one");
        assertEquals(1, gremlin.execute("g.V('a','b','c','d','Account').has('age', inside(20, 30))").size());
        assertEquals(1, gremlin.execute("g.V('a','b','c','d','Account').has('age', outside(20, 30))").size(),
                "outside(20,30) is x < 20 or x > 30: only c (40)");
        assertEquals(List.of("a"), gremlin.execute("g.V('a','b','Account').values('id').is(eq('a'))"));
        assertEquals(List.of("b"), gremlin.execute("g.V('a','b','Account').values('id').is(neq('a'))"));
        assertEquals(List.of("b"), gremlin.execute("g.V('a','b','Account').values('id').is(within('b'))"));
        assertEquals(List.of("a"), gremlin.execute("g.V('a','b','Account').values('id').is(not(within('b')))"));
        QueryException error = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('a','Account').has('status', roughly('ACTIVE'))"));
        assertTrue(error.getMessage().contains("Unknown predicate"), error.getMessage());
    }

    @Test
    void aChildTraversalMayCarryTinkerPopsAnonymousMarker() {
        assertEquals(List.of("a", "b"), gremlin.execute(
                "g.V('a','b','Account').union(__.values('id')).toList()"),
                "union(__.values('id')) means union(values('id'))");
        assertEquals(2L, one("g.V('a','b','Account').and(__.has('name')).count()"));
        assertEquals(List.of("a"), gremlin.execute(
                "g.V('a','b','Account').where(__.has('status','ACTIVE')).values('id')"));
    }

    @Test
    void propertiesGiveKeyAndValueAndMathFormatsAndDates() {
        // properties()/key()/value()
        assertEquals(List.of("age", "name", "status"),
                gremlin.execute("g.V('a','Account').properties().key()").stream()
                        .map(String::valueOf).sorted().toList());
        assertEquals(List.of("30", "ACTIVE", "ann"),
                gremlin.execute("g.V('a','Account').properties().value()").stream()
                        .map(String::valueOf).sorted().toList());
        assertEquals(3L, one("g.V('a','Account').properties().count()"));
        QueryException notAProperty = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').key()"));
        assertTrue(notAProperty.getMessage().contains("properties()"), notAProperty.getMessage());

        // math('_ ...')
        assertEquals(15L, one("g.V('a','Account').values('age').math('_ / 2')"));
        assertEquals(62L, one("g.V('a','Account').values('age').math('(_ + 1) * 2')"));
        assertEquals(1L, one("g.V('a','Account').values('age').math('_ % 29')"));
        QueryException notANumber = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').math('_ + 1')"));
        assertTrue(notANumber.getMessage().contains("needs a number"), notANumber.getMessage());

        // format('%{key}')
        assertEquals(List.of("ann: 30"), gremlin.execute("g.V('a','Account').format('%{name}: %{age}')"));
        assertEquals(List.of("Account a"), gremlin.execute("g.V('a','Account').format('%{label} %{id}')"));
        QueryException missing = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').format('%{nope}')"));
        assertTrue(missing.getMessage().contains("does not carry"), missing.getMessage());

        // dates: epoch millis stay numbers, text stays text
        assertEquals(List.of(86_400_000L), gremlin.execute("g.inject(0).dateAdd('day', 1)"));
        assertEquals(List.of(86_400_000L), gremlin.execute("g.inject(0).dateAdd('DT.day', 1)"),
                "the DT. spelling of TinkerPop works too");
        assertEquals(List.of(172_800_000L), gremlin.execute("g.inject(0).dateAdd('days', 2)"));
        assertEquals(List.of(2L), gremlin.execute("g.inject(0).dateDiff('day', 172800000)"));
        assertEquals(List.of("1970-01-01T00:00:00Z"), gremlin.execute("g.inject(0).dateTime()"));
        assertEquals(List.of("2026-01-02T00:00:00Z"),
                gremlin.execute("g.inject('2026-01-01').dateAdd('day', 1)"), "text in, text out");
    }

    @Test
    void profileAndExplainSayWhatTheRunDid() {
        Map<?, ?> profile = (Map<?, ?>) one("g.V('a','b','Account').has('status','ACTIVE').profile()");
        assertEquals(1L, ((Number) profile.get("traversers")).longValue());
        List<?> steps = (List<?>) profile.get("steps");
        assertEquals(1, steps.size(), "only the steps that ran, not profile() itself");
        Map<?, ?> has = (Map<?, ?>) steps.get(0);
        assertEquals("has", has.get("step"));
        assertEquals(2L, ((Number) has.get("in")).longValue());
        assertEquals(1L, ((Number) has.get("out")).longValue());

        Map<?, ?> plan = (Map<?, ?>) one("g.V('a','Account').out('knows').explain()");
        assertEquals("vertices", plan.get("source"));
        assertEquals("Account", plan.get("name"));
        assertEquals(false, plan.get("pathAware"));
        assertEquals(List.of("out"), ((List<?>) plan.get("steps")).stream()
                .map(step -> ((Map<?, ?>) step).get("step")).toList());
    }

    @Test
    void identityConstantIdAndLabel() {
        assertEquals(3, gremlin.execute("g.V('a','b','c','Account').identity()").size());
        assertEquals(List.of("x", "x"), gremlin.execute("g.V('a','b','Account').constant('x')"));
        assertEquals(List.of("a", "b"), gremlin.execute("g.V('a','b','Account').id()"));
        assertEquals(List.of("Account", "Account"), gremlin.execute("g.V('a','b','Account').label()"));
    }

    @Test
    void hasIdHasNotHasKeyAndHasValue() {
        assertEquals(List.of("a"), gremlin.execute("g.V('a','b','Account').hasId('a')")
                .stream().map(o -> ((GremlinVertex) o).id()).toList());
        assertEquals(1, gremlin.execute("g.V('a','b','Account').hasNot('status')").size(),
                "only b has no status");
        assertEquals(2, gremlin.execute("g.V('a','b','c','Account').hasKey('status') ").size(),
                "a and c carry a status");
        assertEquals(3, gremlin.execute("g.V('a','b','c','Account').hasKey('name')").size());
        assertEquals(1, gremlin.execute("g.V('a','b','c','Account').hasValue('FROZEN')").size());
    }

    @Test
    void isComparesTheValue() {
        assertEquals(List.of(30), gremlin.execute("g.V('a','b','Account').values('age').is(gt(25))"));
        assertEquals(List.of(20), gremlin.execute("g.V('a','b','Account').values('age').is(lt(25))"));
        assertEquals(List.of(30, 20), gremlin.execute("g.V('a','b','Account').values('age').is(neq(40))"));
        assertEquals(List.of(20), gremlin.execute("g.V('a','b','Account').values('age').is(20)"));
    }

    @Test
    void rangeSkipAndTailSlice() {
        assertEquals(2, gremlin.execute("g.V('a','b','c','Account').range(0, 2)").size());
        assertEquals(1, gremlin.execute("g.V('a','b','c','Account').skip(2)").size());
        assertEquals(1, gremlin.execute("g.V('a','b','c','Account').tail(1)").size());
        assertEquals("c", ((GremlinVertex) one("g.V('a','b','c','Account').tail(1)")).id());
    }

    @Test
    void foldAndUnfold() {
        Object folded = one("g.V('a','b','Account').values('age').fold()");
        assertEquals(List.of(30, 20), folded);
        assertEquals(List.of(30, 20), gremlin.execute(
                "g.V('a','b','Account').values('age').fold().unfold()"));
    }

    @Test
    void sumMeanMaxAndMin() {
        assertEquals(List.of(90L), gremlin.execute("g.V('a','b','c','Account').values('age').sum()"));
        assertEquals(List.of(30.0), gremlin.execute("g.V('a','b','c','Account').values('age').mean()"));
        assertEquals(List.of(40), gremlin.execute("g.V('a','b','c','Account').values('age').max()"));
        assertEquals(List.of(20), gremlin.execute("g.V('a','b','c','Account').values('age').min()"));
    }

    @Test
    void orderSortsByAProperty() {
        List<Object> ascending = gremlin.execute("g.V('a','b','c','Account').order('age')");
        assertEquals(List.of("b", "a", "c"),
                ascending.stream().map(o -> ((GremlinVertex) o).id()).toList());

        List<Object> descending = gremlin.execute("g.V('a','b','c','Account').order('age', 'desc')");
        assertEquals(List.of("c", "a", "b"),
                descending.stream().map(o -> ((GremlinVertex) o).id()).toList());
    }

    @Test
    void groupCountCountsByAProperty() {
        Map<?, ?> counts = (Map<?, ?>) one("g.V('a','b','c','Account').groupCount('status')");
        assertEquals(1L, counts.get("ACTIVE"));
        assertEquals(1L, counts.get("FROZEN"));
        assertEquals(1L, counts.get("(none)"), "a missing property is its own named group");
    }

    @Test
    void valueMapProjectsTheProperties() {
        Map<?, ?> map = (Map<?, ?>) one("g.V('a','Account').valueMap('age', 'name')");
        assertEquals(30, map.get("age"));
        assertEquals("ann", map.get("name"));
    }

    @Test
    void byModulatesOrderGroupCountAndDedup() {
        List<Object> ordered = gremlin.execute("g.V('a','b','c','d','Account').order().by('age')");
        assertEquals(List.of("b", "d", "a", "c"),
                ordered.stream().map(o -> ((GremlinVertex) o).id()).toList());

        List<Object> descending = gremlin.execute("g.V('a','b','c','d','Account').order().by('age', desc)");
        assertEquals(List.of("c", "a", "d", "b"),
                descending.stream().map(o -> ((GremlinVertex) o).id()).toList());

        Map<?, ?> counts = (Map<?, ?>) one("g.V('a','b','c','d','Account').groupCount().by('status')");
        assertEquals(2L, counts.get("ACTIVE"));
        assertEquals(1L, counts.get("FROZEN"));
        assertEquals(1L, counts.get("(none)"));

        assertEquals(3, gremlin.execute("g.V('a','b','c','d','Account').dedup().by('status')").size(),
                "a and d share a status, so one of them is the representative");
    }

    @Test
    void theStringStepsMapTheirValues() {
        assertEquals(List.of("ANN"), gremlin.execute("g.V('a','Account').values('name').toUpper()"));
        assertEquals(List.of("ann"), gremlin.execute("g.V('a','Account').values('name').toLower()"));
        assertEquals(List.of(3L), gremlin.execute("g.V('a','Account').values('name').length()"));
    }

    @Test
    void unionCoalesceLocalNotAndOrBranchOnAChildTraversal() {
        // a -> x over Payment, b has no payment
        when(graphQuery.readEdges(any(), anyString(), eq("Payment"), any(), anyList()))
                .thenAnswer(inv -> {
                    Object owner = inv.getArgument(0);
                    return "a".equals(String.valueOf(owner))
                            ? List.of(new EdgeQueryResult("Payment", "e1", EdgeSide.OUT, "x", Map.of(), 1L, 2L))
                            : List.of();
                });

        assertEquals(List.of("x"), ids(gremlin.execute("g.V('a','b','Account').union(out('Payment'))")));
        assertEquals(List.of("x"), ids(gremlin.execute("g.V('a','b','Account').local(out('Payment'))")));
        assertEquals(List.of("b"), ids(gremlin.execute("g.V('a','b','Account').not(out('Payment'))")),
                "not keeps the element whose child reaches nothing");
        assertEquals(List.of("a"), ids(gremlin.execute("g.V('a','b','Account').and(out('Payment'))")));
        assertEquals(List.of("a"), ids(gremlin.execute("g.V('a','b','Account').or(out('Payment'))")));
        assertEquals(List.of("x", "none"), gremlin.execute(
                "g.V('a','b','Account').coalesce(out('Payment'), constant('none'))").stream()
                .map(o -> o instanceof GremlinVertex v ? String.valueOf(v.id()) : String.valueOf(o)).toList());
    }

    @Test
    void stringTypeAndCollectionSteps() {
        assertEquals(List.of("ANN!"), gremlin.execute(
                "g.V('a','Account').values('name').toUpper().concat('!')"));
        assertEquals(List.of(3L), gremlin.execute("g.V('a','Account').values('name').length().asNumber()"));
        assertEquals(List.of(List.of("a", "n", "n")), gremlin.execute(
                "g.V('a','Account').values('name').split('')"));
        assertEquals(List.of("A"), gremlin.execute(
                "g.V('a','Account').values('name').substring(0, 1).toUpper()"));
        assertEquals(List.of("xnn"), gremlin.execute(
                "g.V('a','Account').values('name').replace('a', 'x')"));
        assertEquals(List.of(false), gremlin.execute("g.V('a','Account').values('name').asBool()"));
        assertEquals(List.of(1735689600000L), gremlin.execute(
                "g.inject('2025-01-01T00:00:00Z').asDate()"));
    }

    @Test
    void collectionStepsOperateOnAListTraverser() {
        assertEquals(List.of(List.of(30, 20, 10)), gremlin.execute(
                "g.V('a','b','Account').values('age').fold().combine([10])"));
        assertEquals(List.of(List.of(20)), gremlin.execute(
                "g.V('a','b','Account').values('age').fold().intersect([20, 40])"));
        assertEquals(List.of(List.of(30)), gremlin.execute(
                "g.V('a','b','Account').values('age').fold().difference([20])"));
        assertEquals(List.of(List.of(30, 20)), gremlin.execute(
                "g.V('a','b','Account').values('age').fold().disjunct([20])"));
        assertEquals(1, gremlin.execute("g.V('a','b','Account').values('age').fold().all(gt(10))").size());
        assertEquals(0, gremlin.execute("g.V('a','b','Account').values('age').fold().all(gt(25))").size());
        assertEquals(1, gremlin.execute("g.V('a','b','Account').values('age').fold().any(eq(20))").size());
        assertEquals(1, gremlin.execute("g.V('a','b','Account').values('age').fold().none(lt(0))").size());
    }

    @Test
    void optionalChooseWhereRepeatInjectAndCoin() {
        when(graphQuery.readEdges(any(), anyString(), eq("Payment"), any(), anyList()))
                .thenAnswer(inv -> {
                    Object owner = inv.getArgument(0);
                    return "a".equals(String.valueOf(owner))
                            ? List.of(new EdgeQueryResult("Payment", "e1", EdgeSide.OUT, "x", Map.of(), 1L, 2L))
                            : List.of();
                });

        assertEquals(List.of("x", "b"), ids(gremlin.execute(
                "g.V('a','b','Account').optional(out('Payment'))")));
        assertEquals(List.of("a"), ids(gremlin.execute("g.V('a','b','Account').where(out('Payment'))")));
        assertEquals(List.of("x", "none"), gremlin.execute(
                "g.V('a','b','Account').choose(out('Payment'), out('Payment'), constant('none'))").stream()
                .map(o -> o instanceof GremlinVertex v ? String.valueOf(v.id()) : String.valueOf(o)).toList());
        assertEquals(List.of("x"), ids(gremlin.execute(
                "g.V('a','b','Account').repeat(out('Payment')).times(1)")));
        assertEquals(2, gremlin.execute("g.V('a','b','Account').coin(1.0)").size());
        assertEquals(List.of(1, 2, 3), gremlin.execute("g.V('a','b','c','Account').values('age').inject(1, 2, 3).limit(3)"));
    }

    private static List<String> ids(List<Object> elements) {
        List<String> out = new ArrayList<>(elements.size());
        for (Object element : elements) {
            out.add(element instanceof GremlinVertex vertex ? String.valueOf(vertex.id())
                    : String.valueOf(element));
        }
        return out;
    }
}
