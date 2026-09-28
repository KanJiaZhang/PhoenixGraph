package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stateful path of batch 1 (see TRAVERSER-DESIGN.md): {@code as}, {@code select}, {@code project} and
 * {@code where('a', P)}.
 *
 * <p>Two things are asserted here: that the labelled steps answer what they should, and that a query
 * written both ways - with a label and without - gives the same answer, which is what keeps the second
 * execution path from becoming a second behaviour.
 */
class GremlinTraverserStepsTest {

    private final GraphQuery graphQuery = mock(GraphQuery.class);
    private final GremlinGraphQuery gremlin = new GremlinGraphQuery(graphQuery,
            NeighborVertexResolver.of(Map.of("knows", "Account"), Map.of("knows", "Account")));

    private static Map<String, Object> propertiesOf(Object id) {
        return switch (String.valueOf(id)) {
            case "a" -> Map.of("age", 30, "name", "ann", "status", "ACTIVE");
            case "b" -> Map.of("age", 20, "name", "bob", "status", "FROZEN");
            case "c" -> Map.of("age", 40, "name", "cid", "status", "ACTIVE");
            default -> Map.of();
        };
    }

    @BeforeEach
    void stubReads() {
        when(graphQuery.readVertex(any(), anyString(), anyList()))
                .thenAnswer(inv -> new VertexQueryResult(inv.getArgument(1), inv.getArgument(0),
                        propertiesOf(inv.getArgument(0)), 1L, 2L));
        when(graphQuery.readVertices(anyString(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    List<Object> ids = inv.getArgument(1);
                    List<VertexQueryResult> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(new VertexQueryResult(inv.getArgument(0), id, propertiesOf(id), 1L, 2L));
                    }
                    return out;
                });
        // a -> b -> c along "knows"
        when(graphQuery.readEdges(any(), anyString(), eq("knows"), any(), anyList()))
                .thenAnswer(inv -> switch (String.valueOf((Object) inv.getArgument(0))) {
                    case "a" -> List.of(new EdgeQueryResult("knows", "e_ab", EdgeSide.OUT, "b", Map.of(),
                            1L, 2L));
                    case "b" -> List.of(new EdgeQueryResult("knows", "e_bc", EdgeSide.OUT, "c", Map.of(),
                            1L, 2L));
                    default -> List.of();
                });
        when(graphQuery.readEdgesBatch(anyString(), any(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    List<Object> ids = inv.getArgument(2);
                    List<List<EdgeQueryResult>> out = new ArrayList<>(ids.size());
                    for (Object id : ids) {
                        out.add(graphQuery.readEdges(id, "Account", (String) inv.getArgument(0),
                                (EdgeSide) inv.getArgument(1), (List<String>) inv.getArgument(3)));
                    }
                    return out;
                });
    }

    /** The single value of a one-row answer. */
    private Object one(String query) {
        List<Object> answer = gremlin.execute(query);
        return answer.isEmpty() ? null : answer.get(0);
    }

    /** The ids of the answer, whatever the answer's shape is. */
    private List<String> ids(String query) {
        List<Object> answer = gremlin.execute(query);
        List<String> out = new ArrayList<>(answer.size());
        for (Object value : answer) {
            out.add(value instanceof GremlinVertex vertex ? String.valueOf(vertex.id())
                    : value instanceof GremlinEdge edge ? String.valueOf(edge.id()) : String.valueOf(value));
        }
        return out;
    }

    @Test
    void aLabelRemembersTheValueTheTraverserStoodOn() {
        assertEquals(List.of("a", "b"), ids("g.V('a','b','Account').as('who').values('id').select('who')"),
                "select('who') brings back the vertex the traverser was labelled with, after values() moved on");
        assertEquals(List.of("a", "b"), ids("g.V('a','b','Account').as('who').select('who').values('id')"),
                "and it works right after the label too");
        assertEquals(List.of("ann", "bob"),
                gremlin.execute("g.V('a','b','Account').as('who').select('who').by('name')"),
                "a by() modulator picks the value out of what was selected");
    }

    @Test
    void aLabelSurvivesAHopAndSelectBringsTheVertexBack() {
        assertEquals(List.of("a"),
                ids("g.V('a','Account').as('who').out('knows').select('who')"),
                "the hop moved to b, and select('who') answers the vertex the traverser started from");
        assertEquals(List.of("c"), ids("g.V('a','Account').as('who').out('knows').out('knows')"),
                "the walk still reaches the end of the chain");
        assertEquals(List.of("b"),
                ids("g.V('a','Account').as('who').out('knows').select('who').out('knows')"),
                "after selecting the start again the walk continues from it");
    }

    @Test
    void projectBuildsOneMapPerTraverser() {
        List<Object> answer = gremlin.execute(
                "g.V('a','b','Account').project('n','p').by('name').by('status')");
        assertEquals(2, answer.size());
        assertEquals(Map.of("n", "ann", "p", "ACTIVE"), answer.get(0));
        assertEquals(Map.of("n", "bob", "p", "FROZEN"), answer.get(1));
        assertEquals(1L, one("g.V('a','Account').project('who').by('id').count()"),
                "a projection has one value per traverser, and by('id') reads that property off it");
    }

    @Test
    void whereComparesALabelledValue() {
        assertEquals(List.of("a", "c"),
                ids("g.V('a','b','c','Account').as('x').values('status').as('s').where('s', eq('ACTIVE')).select('x')"),
                "where('s', eq('ACTIVE')) keeps the traversers whose label holds ACTIVE");
        assertEquals(List.of("b"),
                ids("g.V('a','b','c','Account').as('x').values('status').as('s').where('s', neq('ACTIVE')).select('x')"));
        assertEquals(List.of("a", "c"),
                ids("g.V('a','b','c','Account').as('x').values('status').as('s').where('s', within('ACTIVE')).select('x')"));
        assertEquals(List.of("a", "b", "c"),
                ids("g.V('a','b','c','Account').as('x').values('status').as('s').where('s', eq('s'))"
                        + ".select('x')"),
                "a label named as the predicate argument is read as that label's value (comparing it with"
                        + " itself keeps every traverser, while the literal 's' would keep none)");
        assertEquals(List.of(),
                ids("g.V('a','b','c','Account').as('v').values('name').as('n').where('n', eq('v'))"
                        + ".select('v')"),
                "and comparing two different labels compares their values (a name is not a vertex id)");
        QueryException unknown = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').values('status').where('nope', eq('ACTIVE'))"));
        assertTrue(unknown.getMessage().contains("no such label"), unknown.getMessage());
    }

    @Test
    void theWhereHelperResolvesALabelArgument() {
        GremlinTraverser active = GremlinTraverser.of("ACTIVE").withLabel("s", "ACTIVE");
        GremlinTraverser frozen = GremlinTraverser.of("FROZEN").withLabel("s", "FROZEN");
        assertTrue(GremlinPredicate.parse("eq('ACTIVE')").test("ACTIVE"), "the predicate itself works");
        assertEquals(1, GremlinTraverserSteps.whereLabel(List.of(active, frozen), "s", "eq('ACTIVE')").size(), "a literal argument");
        assertEquals(2, GremlinTraverserSteps.whereLabel(List.of(active, frozen), "s", "eq('s')").size(),
                "eq('s') reads the label s and compares it with itself, which keeps everything (against the"
                        + " text 's' it would match nothing)");
        assertEquals(2, GremlinTraverserSteps.whereLabel(List.of(active, frozen), "s", "within('s')").size(),
                "within('s') is resolved per traverser, so each one contains its own label value");
    }

    @Test
    void aLabelledQueryAndTheSameQueryWithoutLabelsAnswerTheSame() {
        // the two execution paths must not become two behaviours: this is the regression net the design
        // asks for (TRAVERSER-DESIGN.md 5)
        for (String[] pair : List.of(
                new String[]{"g.V('a','b','c','Account').has('status','ACTIVE').values('id')",
                        "g.V('a','b','c','Account').as('x').has('status','ACTIVE').select('x').values('id')"},
                new String[]{"g.V('a','b','c','Account').has('age', gt(25)).values('name')",
                        "g.V('a','b','c','Account').as('x').has('age', gt(25)).select('x').values('name')"},
                new String[]{"g.V('a','b','Account').out('knows').values('id')",
                        "g.V('a','b','Account').as('x').out('knows').values('id')"},
                new String[]{"g.V('a','b','Account').count()",
                        "g.V('a','b','Account').as('x').count()"})) {
            assertEquals(gremlin.execute(pair[0]), gremlin.execute(pair[1]),
                    "same answer from both paths: " + pair[1]);
        }
    }

    @Test
    void untilStopsTheLoopWhenItsConditionHolds() {
        assertEquals(List.of("c"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".until(has('name','cid')).values('id')"),
                "repeat(out()).until(...) is a do/while: the body runs, then the condition is tested");
        assertEquals(List.of("b"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".until(has('name','bob')).values('id')"),
                "the first body run already satisfies it, so the loop stops there");
        assertEquals(List.of(), gremlin.execute("g.V('a','Account').repeat(out('knows'))"
                        + ".until(has('name','nobody'))"),
                "a condition that never holds leaves no traverser: the frontier dies at the end of the chain");
        assertEquals(List.of(), gremlin.execute("g.V('a','Account').repeat(out('knows'))"
                        + ".until(has('name','ann'))"),
                "and the start is not tested before the body, so the start itself is not the answer");
    }

    @Test
    void loopsCountsTheIterationsAndCanBeTheUntilCondition() {
        assertEquals(gremlin.execute("g.V('a','Account').repeat(out('knows')).times(2).values('id')"),
                gremlin.execute("g.V('a','Account').repeat(out('knows')).until(loops().is(2)).values('id')"),
                "until(loops().is(2)) is the same bound as times(2)");
        assertEquals(List.of("c"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".until(loops().is(2)).values('id')"),
                "and it reaches the end of the chain");
        assertEquals(List.of(2), gremlin.execute(
                        "g.V('a','Account').repeat(out('knows')).times(2).loops()"),
                "loops() answers how many times the traverser went through the body");
        assertEquals(List.of("a"), ids("g.V('a','Account').as('v').repeat(out('knows')).times(1)"
                        + ".loops().is(1).select('v')"),
                "a loop count can be tested with is(), and the label survives the loop");
    }

    @Test
    void emitHandsOnEveryIterationItAsksFor() {
        assertEquals(List.of("b", "c"), ids("g.V('a','Account').repeat(out('knows')).emit().times(2)"),
                "emit() with no condition hands on every iteration, the last one included");
        assertEquals(List.of("b", "c"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".emit().until(has('name','cid'))"),
                "and the traverser that satisfies until() is one of the emitted ones");
        assertEquals(List.of("b", "c"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".emit(has('name','bob')).until(has('name','cid'))"),
                "a traverser that satisfies both emit() and until() is emitted once, not twice");
        assertEquals(List.of("b"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".emit(has('status','FROZEN')).times(2)"),
                "emit(c) hands on only the iterations whose traverser satisfies c");
        assertEquals(List.of("b", "c"), ids("g.V('a','Account').repeat(out('knows'))"
                        + ".emit(has('status','FROZEN')).until(has('name','cid'))"),
                "and a traverser that satisfies until() is the answer whether or not emit(c) matched it");
        // emit on a plain times() loop must not change what the loop reaches, only what is handed on
        assertEquals(gremlin.execute("g.V('a','Account').repeat(out('knows')).times(2).values('id')"),
                List.of(gremlin.execute("g.V('a','Account').repeat(out('knows')).emit().times(2)"
                        + ".values('id')").get(1)),
                "the emitted answer ends at the last iteration, which is where the plain loop ends");
    }

    @Test
    void aRepeatModulatorOutsideARepeatIsRefused() {
        QueryException noRepeat = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').until(has('name','bob'))"));
        assertTrue(noRepeat.getMessage().contains("must follow repeat"), noRepeat.getMessage());
        QueryException unbounded = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').repeat(out('knows'))"));
        assertTrue(unbounded.getMessage().contains("needs a bound"), unbounded.getMessage());
        QueryException blankUntil = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').repeat(out('knows')).until()"));
        assertTrue(blankUntil.getMessage().contains("needs a condition"), blankUntil.getMessage());
        QueryException orphanLoops = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').as('v').loops()"));
        assertTrue(orphanLoops.getMessage().contains("needs a repeat"), orphanLoops.getMessage());
    }

    @Test
    void repeatWithLabelsRunsTheSameLoopAsTheListPath() {
        String twoHops = ".out('knows').out('knows')";
        assertEquals(gremlin.execute("g.V('a','Account')" + twoHops + ".values('id')"),
                gremlin.execute("g.V('a','Account').repeat(out('knows')).times(2).values('id')"),
                "the list-path repeat is exactly two hops");
        assertEquals(gremlin.execute("g.V('a','Account').repeat(out('knows')).times(2).values('id')"),
                gremlin.execute("g.V('a','Account').as('v').repeat(out('knows')).times(2).values('id')"),
                "the stateful path runs the same loop, so a labelled repeat answers the same");
        assertEquals(List.of("c"), ids("g.V('a','Account').as('v').repeat(out('knows')).times(2).values('id')"),
                "and the label bound before the loop survives it");
    }

    @Test
    void theSackAccumulatesPerTraverser() {
        assertEquals(List.of(100L, 100L), gremlin.execute("g.V('a','b','Account').withSack(100).sack()"),
                "sack() makes the traverser's value its sack");
        assertEquals(List.of(31.0), gremlin.execute("g.V('a','Account').withSack(1).sack(sum).by('age').sack()"),
                "sack(sum).by('age') adds the property to the sack (age 30 + seed 1)");
        assertEquals(List.of(30), gremlin.execute("g.V('a','Account').withSack(1).sack(assign).by('age').sack()"),
                "assign replaces it");
        assertEquals(List.of(1.0), gremlin.execute("g.V('a','Account').withSack(1).sack(min).by('age').sack()"));
        assertEquals(List.of(30.0), gremlin.execute("g.V('a','Account').withSack(1).sack(max).by('age').sack()"));
        assertEquals(List.of("a", "b"),
                ids("g.V('a','b','Account').withSack(0).sack(sum).by('age').values('id')"),
                "the sack does not disturb the traversal's values");
        QueryException bad = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('a','Account').withSack(0).sack(divide).by('age').sack()"));
        assertTrue(bad.getMessage().contains("not a modulator"), bad.getMessage());
    }

    @Test
    void aggregateAndCapCollectAndReadBackASideEffect() {
        List<Object> collected = gremlin.execute("g.V('a','b','Account').aggregate('x').cap('x')");
        assertEquals(1, collected.size(), "cap ends the traversal with the collected container");
        assertEquals(List.of("a", "b"), collectedIds(collected.get(0)));

        List<Object> stored = gremlin.execute("g.V('a','b','Account').store('x').values('id').cap('x')");
        assertEquals(List.of("a", "b"), collectedIds(stored.get(0)),
                "store collects the same values while the traversal moves on");

        QueryException empty = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').cap('nope')"));
        assertTrue(empty.getMessage().contains("nothing filled"), empty.getMessage());
    }

    @Test
    void sideEffectRunsAChildForItsEffectAndPassesTheTraverserOn() {
        assertEquals(List.of("a", "b"),
                ids("g.V('a','b','Account').sideEffect(values('id')).values('id')"),
                "the child's answer is not the traversal's");
        assertEquals(List.of("c"), ids("g.V('a','Account').as('v').out('knows').out('knows')"),
                "and a hop still works next to it");
    }

    @Test
    void theSideEffectStepsKeepTheExistingAnswer() {
        // the regression net again: a query with an effect that changes nothing must answer what it answered
        for (String[] pair : List.of(
                new String[]{"g.V('a','b','Account').values('id')",
                        "g.V('a','b','Account').sideEffect(count()).values('id')"},
                new String[]{"g.V('a','b','Account').has('status','ACTIVE').values('name')",
                        "g.V('a','b','Account').aggregate('x').has('status','ACTIVE').values('name')"})) {
            assertEquals(gremlin.execute(pair[0]), gremlin.execute(pair[1]),
                    "same answer from both paths: " + pair[1]);
        }
    }

    @Test
    void aSideEffectInsideAChildCollectsIntoTheSameContainer() {
        List<Object> answer = gremlin.execute("g.V('a','b','Account').sideEffect(aggregate('x')).cap('x')");
        assertEquals(1, answer.size());
        assertEquals(List.of("a", "b"), collectedIds(answer.get(0)),
                "the child's aggregate reached the run's container: the gap CONTEXT.md 95 recorded is closed");
        // and the container steps work on the plain path too, without any label in the query
        List<Object> plain = gremlin.execute("g.V('a','b','Account').aggregate('x').cap('x')");
        assertEquals(List.of("a", "b"), collectedIds(plain.get(0)));
    }

    @Test
    void branchSendsEachTraverserToTheFirstChildThatAnswers() {
        // the first child answers for no vertex, so every traverser falls through to the second
        assertEquals(List.of("a", "b"), ids("g.V('a','b','Account').as('v')"
                + ".branch(has('name','nobody').values('id'), values('id'))"));
        // the first child answers for 'a' only: its answer is the name, 'b' falls through to the id
        List<Object> mixed = gremlin.execute("g.V('a','b','Account').as('v')"
                + ".branch(hasId('a').values('name'), values('id'))");
        assertEquals(2, mixed.size());
        assertEquals(gremlin.execute("g.V('a','Account').values('name')"), List.of(mixed.get(0)));
        assertEquals("b", String.valueOf(mixed.get(1)));
        // and the label is still there after the branch
        assertEquals(List.of("a"), ids("g.V('a','Account').as('v').branch(hasId('a').values('id'))"
                + ".select('v')"));
    }

    @Test
    void unionOnTheStatefulPathAgreesWithThePlainOne() {
        String body = ".union(values('id'), values('name'))";
        assertEquals(gremlin.execute("g.V('a','b','Account')" + body),
                gremlin.execute("g.V('a','b','Account').as('v')" + body),
                "the same union, whether or not a label was bound");
        // both children ran, in whatever order the plain path puts them (asked, not assumed)
        String twice = ".union(values('id').limit(1), values('id').limit(1))";
        List<Object> plainTwice = gremlin.execute("g.V('a','b','Account')" + twice);
        assertEquals(4, plainTwice.size(), "both children answered: " + plainTwice);
        assertEquals(plainTwice, gremlin.execute("g.V('a','b','Account').as('v')" + twice),
                "and the stateful path puts them in the same order");
    }

    @Test
    void simpleAndCyclicPathReadTheWalkTheTraverserCarries() {
        String twoHops = ".out('knows').out('knows')";
        assertEquals(List.of("c"), ids("g.V('a','Account').as('v')" + twoHops + ".simplePath()"
                + ".values('id')"), "a->b->c visits no vertex twice, so it survives simplePath()");
        assertEquals(List.of(), gremlin.execute("g.V('a','Account').as('v')" + twoHops + ".cyclicPath()"),
                "and nothing in it is cyclic");
        // the two must not be the same filter: invert the repeat check and this pair flips
        assertEquals(1, gremlin.execute("g.V('a','Account').as('v')" + twoHops + ".simplePath()").size());
        assertEquals(0, gremlin.execute("g.V('a','Account').as('v')" + twoHops + ".cyclicPath()").size());
        // the list path refuses simplePath() without path(); the stateful path has the walk already, so it
        // answers without one - a deliberate improvement, which is why the two paths are not compared here
        QueryException needsPath = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account')" + twoHops + ".simplePath()"));
        assertTrue(needsPath.getMessage().contains("path()"), needsPath.getMessage());
    }

    @Test
    void mathReadsVariablesAndByModulators() {
        // _ with a by(): the property of the traverser's own element
        assertEquals(List.of(31L), gremlin.execute(
                "g.V('a','Account').math('_ + 1').by('age')"),
                "by('age') feeds the _ variable the element's age");
        // two variables, one by() cycled over both (TinkerPop's traversal ring)
        assertEquals(List.of(50L), gremlin.execute(
                "g.V('a','Account').as('x').out('knows').as('y').math('x + y').by('age')"),
                "x is a's age (30) and y is b's age (20), the one by() reused for both");
        assertEquals(List.of(600L), gremlin.execute(
                "g.V('a','Account').as('x').out('knows').as('y').math('x * y').by('age')"),
                "the same two variables in a product");
        // a by() child traversal instead of a property key
        assertEquals(List.of(2L), gremlin.execute(
                "g.V('a','Account').math('_ + 1').by(out('knows').count())"),
                "a's one knows edge becomes 1, plus 1");
        // a modulator that produces nothing filters the traverser (z has no age), not an error
        assertEquals(List.of(31L), gremlin.execute(
                "g.V('a','z','Account').math('_ + 1').by('age')"),
                "a vertex without the property is dropped, the others answer");
    }

    @Test
    void mathSupportsFunctionsPowerAndConstants() {
        assertEquals(List.of(900L), gremlin.execute(
                "g.V('a','Account').values('age').math('_ ^ 2')"), "power is right there in the language");
        assertEquals(List.of(3L), gremlin.execute("g.V('a','Account').math('1 + 2')"),
                "an expression with no _ answers every traverser the same");
        List<Object> rooted = gremlin.execute("g.V('a','Account').values('age').math('sqrt(_)')");
        assertEquals(1, rooted.size());
        assertEquals(Math.sqrt(30), ((Number) rooted.get(0)).doubleValue(), 1e-9);
        // the TinkerPop spelling without parentheses works too
        assertEquals(Math.sqrt(30), ((Number) gremlin.execute(
                "g.V('a','Account').values('age').math('sqrt _')").get(0)).doubleValue(), 1e-9);
    }

    @Test
    void mathRefusesAnUnboundVariableOrANonNumber() {
        QueryException unbound = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').math('nope + 1')"));
        assertTrue(unbound.getMessage().contains("not bound"), unbound.getMessage());
        QueryException notANumber = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').as('x').math('x + 1')"));
        assertTrue(notANumber.getMessage().contains("must be a number"), notANumber.getMessage());
        QueryException text = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').as('x').math('x + 1').by('name')"));
        assertTrue(text.getMessage().contains("must be a number"), text.getMessage());
    }

    @Test
    void selectAndProjectRunAChildTraversalOnTheLabel() {
        assertEquals(List.of(1L), gremlin.execute(
                "g.V('a','Account').as('x').select('x').by(out('knows').count())"),
                "select('x').by(childTraversal) runs the child on the labelled value");
        assertEquals(List.of("ann"), gremlin.execute(
                "g.V('a','Account').as('x').select('x').by(values('name'))"));
        // the same for project: by(childTraversal) used to answer null, now it runs the child
        assertEquals(List.of(Map.of("n", "ann")), gremlin.execute(
                "g.V('a','Account').project('n').by(values('name'))"));
    }

    @Test
    void whereComparesLabelsThroughAByModulator() {
        assertEquals(List.of("a"), ids("g.V('a','b','Account').as('x')"
                        + ".where('x', eq('ACTIVE')).by('status').select('x')"),
                "the stored label value is mapped through by('status') before the test (a is ACTIVE, b is FROZEN)");
        assertEquals(List.of("a"), ids("g.V('a','Account').as('x').out('knows').as('y')"
                        + ".where('x', neq('y')).by('name').select('x')"),
                "and a label named by the predicate is mapped too: ann != bob");
        assertEquals(List.of(), ids("g.V('a','Account').as('x').out('knows').as('y')"
                        + ".where('x', eq('y')).by('name').select('x')"),
                "the same comparison with eq matches nothing because the names differ");
    }

    @Test
    void flatMapFlattensTheChildTraversal() {
        assertEquals(gremlin.execute("g.V('a','b','Account').out('knows').values('id')"),
                gremlin.execute("g.V('a','b','Account').flatMap(out('knows')).values('id')"),
                "flatMap(child) is the child with its answers concatenated");
        List<Object> flattened = gremlin.execute(
                "g.V('a','Account').flatMap(def xs = new ArrayList(); xs.add(9); xs)");
        assertEquals(1, flattened.size());
        assertEquals(9L, ((Number) flattened.get(0)).longValue(),
                "an expression body is evaluated per element and its list is flattened");
    }

    @Test
    void pathFromAndToKeepThePartBetweenTwoLabels() {
        String walk = "g.V('a','Account').as('v').out('knows').as('u')";
        assertEquals(gremlin.execute(walk + ".path()"),
                gremlin.execute(walk + ".path().from('v').to('u')"),
                "the whole walk, when the two labels are its two ends");

        List<Object> fromU = gremlin.execute(walk + ".path().from('u')");
        assertEquals(1, ((GremlinPath) fromU.get(0)).size(), "from('u') keeps the walk from u on");
        assertEquals("b", ((GremlinVertex) ((GremlinPath) fromU.get(0)).start()).id());

        List<Object> toV = gremlin.execute(walk + ".path().to('v')");
        assertEquals(1, ((GremlinPath) toV.get(0)).size(), "to('v') keeps the walk up to v");
        assertEquals("a", ((GremlinVertex) ((GremlinPath) toV.get(0)).start()).id());

        // two labels in the middle of a longer walk
        String two = "g.V('a','Account').as('v').out('knows').as('u').out('knows').as('w')";
        GremlinPath middle = (GremlinPath) gremlin.execute(two + ".path().from('v').to('u')").get(0);
        assertEquals(3, middle.size(), "v -> edge -> u: " + middle);
        assertEquals("a", ((GremlinVertex) middle.start()).id());
        assertEquals("b", ((GremlinVertex) middle.end()).id());
        assertEquals(gremlin.execute(two + ".path()"),
                gremlin.execute(two + ".path().from('v').to('w')"),
                "from the first to the last label is the whole walk again");
        // the by() mapping runs after the slice, on the elements that survived it
        List<Object> names = ((GremlinPath) gremlin.execute(
                walk + ".path().by('name').from('v').to('u')").get(0)).objects();
        assertEquals(java.util.Arrays.asList("ann", null, "bob"), names);

        QueryException unknown = assertThrows(QueryException.class,
                () -> gremlin.execute(walk + ".path().from('nope')"));
        assertTrue(unknown.getMessage().contains("no such label"), unknown.getMessage());
        QueryException noPath = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').from('a')"));
        assertTrue(noPath.getMessage().contains("must follow path"), noPath.getMessage());
    }

    @Test
    void subgraphCollectsTheWalkAndCapReadsItBack() {
        List<Object> answer = gremlin.execute(
                "g.V('a','Account').out('knows').subgraph('sg').cap('sg')");
        assertEquals(1, answer.size(), "cap ends the traversal with the subgraph");
        Subgraph subgraph = (Subgraph) answer.get(0);
        assertEquals(List.of("a", "b"), vertexIds(subgraph), "the walk visited a and b");
        assertEquals(List.of("e_ab"), edgeIds(subgraph), "and walked the edge between them");
        assertEquals(1, edgeIds(subgraph).size(), "the edge is collected once");

        // a two-hop walk collects both edges and all three vertices
        Subgraph two = (Subgraph) gremlin.execute("g.V('a','Account').out('knows').out('knows')"
                + ".subgraph('sg').cap('sg')").get(0);
        assertEquals(List.of("a", "b", "c"), vertexIds(two));
        assertEquals(List.of("e_ab", "e_bc"), edgeIds(two));

        // the step is a side effect: without cap the traversers carry on
        assertEquals(List.of("b"), ids("g.V('a','Account').out('knows').subgraph('sg').values('id')"));

        QueryException empty = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').cap('nope')"));
        assertTrue(empty.getMessage().contains("nothing filled"), empty.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void treeMergesTheWalksIntoOneNestedMap() {
        List<Object> answer = gremlin.execute("g.V('a','Account').out('knows').tree()");
        assertEquals(1, answer.size(), "tree() answers one tree");
        Map<Object, Object> tree = (Map<Object, Object>) answer.get(0);
        assertEquals(1, tree.size(), "one root");
        Map<Object, Object> underA = (Map<Object, Object>) tree.get("Account#a");
        Map<Object, Object> underEdge = (Map<Object, Object>) underA.get("knows#e_ab");
        assertTrue(underEdge.containsKey("Account#b"), "a -> edge -> b: " + tree);
        assertEquals(0, ((Map<Object, Object>) underEdge.get("Account#b")).size(), "b is a leaf");

        // the named form is read back by cap()
        List<Object> capped = gremlin.execute(
                "g.V('a','Account').out('knows').tree('t').values('id').cap('t')");
        assertEquals(1, capped.size());
        assertEquals(tree, capped.get(0), "the same tree, whether it is the answer or a side effect");
    }

    @Test
    void matchJoinsPatternsThatShareALabel() {
        // the canonical join: x -knows-> y, and y is bob
        Object joined = gremlin.execute("g.V('a','Account').match("
                + "as('x').out('knows').as('y'),"
                + "as('y').has('name','bob')).select('x','y')").get(0);
        assertEquals(Map.of("x", "a", "y", "b"), selectedIds(joined));

        // the same query with the patterns written the other way round: the join decides, not the order
        Object reversed = gremlin.execute("g.V('a','Account').match("
                + "as('y').has('name','bob'),"
                + "as('x').out('knows').as('y')).select('x','y')").get(0);
        assertEquals(selectedIds(joined), selectedIds(reversed),
                "a pattern that reads a bound label can be written before the one that binds it");

        // a two-step chain: x -> y -> z
        Object chain = gremlin.execute("g.V('a','b','c','Account').match("
                + "as('x').out('knows').as('y'),"
                + "as('y').out('knows').as('z')).select('x','z')").get(0);
        assertEquals(Map.of("x", "a", "z", "c"), selectedIds(chain));

        // a condition on an already-bound label is a filter, not a re-bind
        Object filtered = gremlin.execute("g.V('a','b','c','Account').match("
                + "as('x').out('knows').as('y'),"
                + "as('y').has('status','FROZEN')).select('x','y')").get(0);
        assertEquals(Map.of("x", "a", "y", "b"), selectedIds(filtered));
    }

    @Test
    void matchAnswersNothingWhenNoBindingSetHolds() {
        assertEquals(List.of(), gremlin.execute("g.V('a','Account').match("
                + "as('x').out('knows').as('y'),"
                + "as('y').has('name','nobody'))"));
        assertEquals(List.of(), gremlin.execute("g.V('c','Account').match("
                + "as('x').out('knows').as('y')).select('x','y')"),
                "c has no outgoing edge, so the pattern cannot be satisfied");
        QueryException empty = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').match()"));
        assertTrue(empty.getMessage().contains("at least one pattern"), empty.getMessage());
    }

    /** A selected map of labelled elements as a map of their ids. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> selectedIds(Object selected) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) selected).entrySet()) {
            Object value = entry.getValue();
            out.put(String.valueOf(entry.getKey()),
                    value instanceof GremlinVertex vertex ? String.valueOf(vertex.id())
                            : value instanceof GremlinEdge edge ? String.valueOf(edge.id())
                            : String.valueOf(value));
        }
        return out;
    }

    private static List<String> vertexIds(Subgraph subgraph) {
        List<String> out = new ArrayList<>();
        for (Subgraph.Vertex vertex : subgraph.vertices()) {
            out.add(String.valueOf(vertex.id()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static List<String> edgeIds(Subgraph subgraph) {
        List<String> out = new ArrayList<>();
        for (Subgraph.Edge edge : subgraph.edges()) {
            out.add(String.valueOf(edge.id()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The ids in a collected container (a list of vertices). */
    private static List<String> collectedIds(Object container) {
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) container) {
            out.add(item instanceof GremlinVertex vertex ? String.valueOf(vertex.id()) : String.valueOf(item));
        }
        return out;
    }

    @Test
    void pathAndLabelsWorkTogetherAndTheWalkMatchesTheListPath() {
        List<Object> plain = gremlin.execute("g.V('a','Account').out('knows').path()");
        List<Object> labelled = gremlin.execute("g.V('a','Account').as('v').out('knows').path()");
        assertEquals(plain, labelled, "the same walk, whether or not a label was bound");
        assertEquals(1, plain.size());
        GremlinPath walk = (GremlinPath) plain.get(0);
        assertEquals(3, walk.size(), "the start, the edge walked, the vertex reached: " + walk);
        assertTrue(walk.get(0) instanceof GremlinVertex && walk.get(1) instanceof GremlinEdge
                && walk.get(2) instanceof GremlinVertex, "in that order: " + walk);

        // two hops: both paths, element for element
        assertEquals(gremlin.execute("g.V('a','Account').out('knows').out('knows').path()"),
                gremlin.execute("g.V('a','Account').as('v').out('knows').out('knows').path()"),
                "a longer walk agrees too");

        // what this batch is for: the label is still readable after path()
        assertEquals(List.of("a"), ids("g.V('a','Account').as('v').out('knows').path().select('v')"));
        assertEquals(List.of("b"), ids("g.V('a','Account').as('v').out('knows').as('u').path().select('u')"),
                "a label bound after the hop is there too");

        // path().by(key) maps every element of the walk; the edge in it carries no such key
        List<Object> names = ((GremlinPath) gremlin.execute(
                "g.V('a','Account').as('v').out('knows').path().by('name')").get(0)).objects();
        assertEquals(3, names.size());
        assertEquals(gremlin.execute("g.V('a','Account').values('name')"), List.of(names.get(0)));
        assertTrue(names.get(1) == null, "the edge in the walk carries no name: " + names);
    }
}
