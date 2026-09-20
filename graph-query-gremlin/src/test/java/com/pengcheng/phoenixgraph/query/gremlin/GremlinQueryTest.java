package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GremlinQueryTest {

    @Test
    void parsesSingleOutHop() {
        GremlinQuery query = GremlinQuery.parse("g.V(1001, Account).out(Payment)");

        assertEquals(List.of("1001"), query.vertexIds());
        assertEquals("Account", query.vertexName());
        assertEquals(List.of(new GremlinQuery.Step("out", List.of("Payment"))), query.steps());
    }

    @Test
    void parsesTwoHopsWithQuotedArgs() {
        GremlinQuery query = GremlinQuery.parse("g.V('v1', 'person').out('knows').in('created')");

        assertEquals(List.of("v1"), query.vertexIds());
        assertEquals("person", query.vertexName());
        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("knows")),
                new GremlinQuery.Step("in", List.of("created"))), query.steps());
    }

    @Test
    void parsesMultipleStartVertices() {
        GremlinQuery query = GremlinQuery.parse("g.V(123, 888, 'Account')");
        assertEquals(List.of("123", "888"), query.vertexIds());
        assertEquals("Account", query.vertexName());

        GremlinQuery comma = GremlinQuery.parse("g.V('123,888', 'Account')");
        assertEquals(List.of("123", "888"), comma.vertexIds());
        assertEquals("Account", comma.vertexName());

        GremlinQuery fullwidth = GremlinQuery.parse("g.V('123，888', 'Account')");
        assertEquals(List.of("123", "888"), fullwidth.vertexIds());
        assertEquals("Account", fullwidth.vertexName());
    }

    @Test
    void parsesEdgeHopsAndProjectionSteps() {
        GremlinQuery query = GremlinQuery.parse(
                "g.V('v1', 'person').outE('knows').has(weight, 0.5).values(weight, since)");

        assertEquals(List.of(
                new GremlinQuery.Step("outE", List.of("knows")),
                new GremlinQuery.Step("has", List.of("weight", "0.5")),
                new GremlinQuery.Step("values", List.of("weight", "since"))), query.steps());
    }

    @Test
    void parsesElementMapAndCount() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p).out(knows).elementMap()");
        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("knows")),
                new GremlinQuery.Step("elementMap", List.of())), query.steps());

        assertEquals(List.of(new GremlinQuery.Step("count", List.of())),
                GremlinQuery.parse("g.V(1, p).count()").steps());
    }

    @Test
    void parsesCustomStepNameWithoutInterpretingIt() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p).out(a).myCustomSnippet(x, 'y z')");

        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("a")),
                new GremlinQuery.Step("myCustomSnippet", List.of("x", "y z"))), query.steps());
    }

    @Test
    void parsesWithoutAnySteps() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p)");

        assertEquals(List.of("1"), query.vertexIds());
        assertEquals("p", query.vertexName());
        assertEquals(List.of(), query.steps());
    }

    @Test
    void parsesBothStepAndIgnoresWhitespace() {
        GremlinQuery query = GremlinQuery.parse("  g . V ( 1 , p ) . both ( friend )  ");

        assertEquals(List.of("1"), query.vertexIds());
        assertEquals("p", query.vertexName());
        assertEquals(List.of(new GremlinQuery.Step("both", List.of("friend"))), query.steps());
    }

    @Test
    void parsesBatchStep() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p).batch(10).out(knows)");

        assertEquals(List.of(
                new GremlinQuery.Step("batch", List.of("10")),
                new GremlinQuery.Step("out", List.of("knows"))), query.steps());
    }

    @Test
    void parsesBarrierStep() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p).out(knows).barrier(20).in(created)");

        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("knows")),
                new GremlinQuery.Step("barrier", List.of("20")),
                new GremlinQuery.Step("in", List.of("created"))), query.steps());
    }

    @Test
    void parsesTrailingLogicBlock() {
        GremlinQuery query = GremlinQuery.parse("g.V(1, p).out(knows) { $logic }");

        assertEquals(List.of(new GremlinQuery.Step("out", List.of("knows"))), query.steps());
        assertEquals("$logic", query.logic());
    }

    @Test
    void parsesMultiLineQueryWithLogic() {
        GremlinQuery query = GremlinQuery.parse("""
                g.V('v1', 'p')
                  .out('knows')
                  .in('created') { $logic }
                """);

        assertEquals(List.of("v1"), query.vertexIds());
        assertEquals(List.of(
                new GremlinQuery.Step("out", List.of("knows")),
                new GremlinQuery.Step("in", List.of("created"))), query.steps());
        assertEquals("$logic", query.logic());
    }

    @Test
    void rejectsBlankQuery() {
        assertThrows(QueryException.class, () -> GremlinQuery.parse("   "));
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(QueryException.class, () -> GremlinQuery.parse("g.X(1)"));
        assertThrows(QueryException.class, () -> GremlinQuery.parse("gV('a','person')"));
    }

    @Test
    void rejectsMissingVertexName() {
        assertThrows(QueryException.class, () -> GremlinQuery.parse("g.V(1, )"));
    }

    @Test
    void readsOneTypeWhenTheStartNamesATypeOnly() {
        // g.V('Account'): one argument is a type, and the traversal starts from every vertex of it
        GremlinQuery query = GremlinQuery.parse("g.V('Account').bothE('Payment').count()");
        assertEquals(GremlinQuery.Source.Kind.VERTEX_TYPE, query.source().kind());
        assertEquals("Account", query.vertexName());
        assertTrue(query.vertexIds().isEmpty());
        assertTrue(query.source().scans());
        assertEquals(List.of("bothE", "count"), query.steps().stream().map(GremlinQuery.Step::name).toList());
    }

    @Test
    void readsOneEdgeClassWhenTheStartNamesAnEdgeClass() {
        GremlinQuery query = GremlinQuery.parse("g.E('Payment').has('status','SUCCESS')");
        assertEquals(GremlinQuery.Source.Kind.EDGE_TYPE, query.source().kind());
        assertEquals("Payment", query.edgeName());
        assertNull(query.vertexName());
        assertEquals(1, query.steps().size());

        // the class name may be bare, and whitespace around the header does not matter
        assertEquals("payment", GremlinQuery.parse("g . E ( payment )").edgeName());
    }

    @Test
    void anEdgeStartNeedsExactlyOneClass() {
        assertThrows(QueryException.class, () -> GremlinQuery.parse("g.E()"));
        assertThrows(QueryException.class, () -> GremlinQuery.parse("g.E('a','b')"));
    }
}
