package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Groovy statements a {@code filter}/{@code map} closure may contain: {@code if}/{@code else}
 * branches, and {@code new ArrayList()} with {@code add}/{@code put} to build a value.
 */
class GremlinClosureStatementTest {

    private static GremlinVertex vertex(Map<String, Object> properties) {
        return new GremlinVertex("Account", "1001", properties, 111L, 222L);
    }

    private static GremlinEdge edge(Map<String, Object> properties) {
        return new GremlinEdge("Payment", "pay_r1", EdgeSide.OUT, "1002", properties, 111L, 222L, "1001");
    }

    private static Object eval(String expression, GremlinElement element) {
        return GremlinExpression.parse(expression).evaluate(element);
    }

    @Test
    void anIfElseAnswersWithTheBranchThatRan() {
        String expression = """
                def v = it.get();
                def status = v.value('accountStatus');
                if (status.equals('ACTIVE')) {
                    'active:' + v.value('accountType')
                } else {
                    'other:' + v.value('accountType')
                }
                """;

        assertEquals("active:savings",
                eval(expression, vertex(Map.of("accountStatus", "ACTIVE", "accountType", "savings"))));
        assertEquals("other:current",
                eval(expression, vertex(Map.of("accountStatus", "FROZEN", "accountType", "current"))));
    }

    @Test
    void anIfWithoutElseAnswersNullWhenFalse() {
        assertEquals("yes", eval("if (it.get().value('flag') == 'y') { 'yes' }", vertex(Map.of("flag", "y"))));
        assertNull(eval("if (it.get().value('flag') == 'y') { 'yes' }", vertex(Map.of("flag", "n"))));
    }

    @Test
    void elseIfChainsAndSingleStatementBranches() {
        String chained = """
                def s = it.get().value('accountStatus');
                if (s == 'ACTIVE') 'a'
                else if (s == 'FROZEN') 'f'
                else 'x'
                """;
        assertEquals("a", eval(chained, vertex(Map.of("accountStatus", "ACTIVE"))));
        assertEquals("f", eval(chained, vertex(Map.of("accountStatus", "FROZEN"))));
        assertEquals("x", eval(chained, vertex(Map.of("accountStatus", "CLOSED"))));
    }

    @Test
    void aClosureCanBuildACollectionWithNewAndAdd() {
        String expression = """
                def v = it.get();
                def list = new ArrayList();
                list.add(v.value('status'));
                list.add(v.value('type'));
                list.join('/')
                """;

        assertEquals("SUCCESS/transfer", eval(expression, edge(Map.of("status", "SUCCESS", "type", "transfer"))));
    }

    @Test
    void theCollectionIsFreshForEveryElement() {
        GremlinExpression expression = GremlinExpression.parse(
                "def list = new ArrayList(); list.add(it.get().value('status')); list.size()");

        assertEquals(1L, expression.evaluate(edge(Map.of("status", "SUCCESS"))));
        assertEquals(1L, expression.evaluate(edge(Map.of("status", "PENDING"))));
    }

    @Test
    void aMapValueCanBeBuiltAndRead() {
        assertEquals("SUCCESS", eval(
                "def m = new LinkedHashMap(); m.put('s', it.get().value('status')); m.get('s')",
                edge(Map.of("status", "SUCCESS"))));
        assertEquals(true, eval(
                "def m = new HashMap(); m.put('k', 1); m.containsKey('k')", edge(Map.of())));
        assertEquals(0L, eval("def m = new HashMap(); m.size()", edge(Map.of())));
    }

    @Test
    void aCollectionAnswersContainsAndIsEmpty() {
        assertEquals(true, eval("def l = new ArrayList(); l.add(1); l.contains(1)", edge(Map.of())));
        assertEquals(false, eval("def l = new ArrayList(); l.contains(1)", edge(Map.of())));
        assertEquals(true, eval("def l = new ArrayList(); l.isEmpty()", edge(Map.of())));
        // a string keeps its own meaning of the same methods
        assertEquals(true, eval("it.get().value('name').contains('lic')", edge(Map.of("name", "alice"))));
        assertEquals(false, eval("it.get().value('name').isEmpty()", edge(Map.of("name", "alice"))));
    }

    @Test
    void anUnsupportedTypeIsReported() {
        QueryException error = assertThrows(QueryException.class,
                () -> GremlinExpression.parse("def c = new ConcurrentSkipListMap(); c"));
        assertEquals(true, error.getMessage().contains("ArrayList"), error.getMessage());
    }

    @Test
    void theMetaPropertiesAndEndpointsAreReadableInAnExpression() {
        GremlinEdge edge = edge(Map.of("status", "SUCCESS"));
        assertEquals(111L, eval("it.get().values('createdTime')", edge));
        assertEquals(222L, eval("it.get().values('lastUpdatedTime')", edge));
        assertEquals("1001", eval("it.get().values('source')", edge));
        assertEquals("1002", eval("it.get().values('target')", edge));
        assertEquals("1001", eval("it.get().value('id')", vertex(Map.of())));
        // the same keys through a variable bound to the element, which takes another code path
        assertEquals(111L, eval("def v = it.get(); v.values('createdTime')", edge));
        assertEquals(222L, eval("def v = it.get(); v.values('lastUpdatedTime')", edge));
        assertEquals("1002", eval("def v = it.get(); v.values('target')", edge));
    }

    /** The whole shape an analyst writes: a two-way choice plus a collection, in one closure. */
    @Test
    void theShapeFromTheTicketWorks() {
        String expression = """
                def v = it.get();
                def stats = v.values('status');
                def list = new ArrayList();
                if (stats.equals('SUCCESS')) {
                    v.values('status') + ':ok'
                } else {
                    v.values('status') + ':no'
                }
                """;

        assertEquals("SUCCESS:ok", eval(expression, edge(Map.of("status", "SUCCESS"))));
        assertEquals("PENDING:no", eval(expression, edge(Map.of("status", "PENDING"))));
    }

    @Test
    void aQueryWithAnIfInsideAMapKeepsTheStepTogether() {
        GremlinQuery query = GremlinQuery.parse(
                "g.V(1001,'Account').bothE('Payment').map { def v = it.get(); "
                        + "if (v.value('status') == 'SUCCESS') { 'S' } else { 'P' } }");

        assertEquals(List.of("bothE", "map"), query.steps().stream().map(GremlinQuery.Step::name).toList());
        assertEquals(true, query.steps().get(1).args().get(0).contains("else"));
        assertNull(query.logic()); // the branch braces are not the trailing logic block
    }
}
