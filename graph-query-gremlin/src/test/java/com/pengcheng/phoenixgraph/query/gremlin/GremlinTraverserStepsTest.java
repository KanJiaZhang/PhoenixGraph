package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
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
        QueryException unknown = assertThrows(QueryException.class,
                () -> gremlin.execute("g.V('a','Account').values('status').where('nope', eq('ACTIVE'))"));
        assertTrue(unknown.getMessage().contains("no such label"), unknown.getMessage());
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
    void aStepThatTheStatefulPathDoesNotCoverYetSaysSo() {
        QueryException error = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('a','Account').as('x').repeat(out('knows')).times(2)"));
        assertTrue(error.getMessage().contains("stateful path"), error.getMessage());
        assertTrue(error.getMessage().contains("repeat"), error.getMessage());
    }

    @Test
    void pathAndLabelsTogetherAreTheNextBatch() {
        QueryException error = assertThrows(QueryException.class, () -> gremlin.execute(
                "g.V('a','Account').as('x').out('knows').path()"));
        assertTrue(error.getMessage().contains("next batch"), error.getMessage());
    }
}
