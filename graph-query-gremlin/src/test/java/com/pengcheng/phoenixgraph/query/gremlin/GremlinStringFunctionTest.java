package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The string and number functions an expression can call on any value, and the
 * {@code value('id')}/{@code value('label')} spellings of the element accessors.
 */
class GremlinStringFunctionTest {

    private static GremlinEdge edge(Map<String, Object> properties) {
        return new GremlinEdge("rel", "e1", EdgeSide.OUT, "n1", properties, 1L, 2L);
    }

    private static Object eval(String expression, GremlinEdge element) {
        return GremlinExpression.parse(expression).evaluate(element);
    }

    @Test
    void transformsTheValueWithStringFunctions() {
        GremlinEdge alice = edge(Map.of("name", "Alice", "note", "  spaced  "));

        assertEquals("ALICE", eval("name.toUpperCase()", alice));
        assertEquals("alice", eval("name.toLowerCase()", alice));
        assertEquals(5L, eval("name.length()", alice));
        assertEquals("spaced", eval("note.trim()", alice));
        assertEquals("Alice", eval("name.toString()", alice));
        assertEquals(false, eval("name.isEmpty()", alice));
        assertEquals(true, eval("note.trim().isEmpty()", edge(Map.of("note", "   "))));
    }

    @Test
    void substringAndReplaceAndIndexOf() {
        GremlinEdge alice = edge(Map.of("name", "Alice"));

        assertEquals("lic", eval("name.substring(1, 4)", alice));
        assertEquals("lice", eval("name.substring(1)", alice));
        assertEquals("ce", eval("name.substring(-2)", alice)); // a negative index counts from the end
        assertEquals("Alice", eval("name.substring(-99, 99)", alice)); // and out of range is clamped
        assertEquals("A_ice", eval("name.replace('l', '_')", alice));
        assertEquals(0L, eval("name.indexOf('A')", alice));
        assertEquals(2L, eval("name.indexOf('i')", alice));
        assertEquals(-1L, eval("name.indexOf('z')", alice));
    }

    @Test
    void functionsComposeAndFeedTheComparisonOperators() {
        GremlinEdge alice = edge(Map.of("first", "alice", "last", "smith"));

        assertEquals("AL", eval("first.toUpperCase().substring(0, 2)", alice));
        assertEquals("ALICE SMITH", eval("(first + ' ' + last).toUpperCase()", alice));

        // the functions plug into the condition grammar the same way a property does
        assertEquals(true, GremlinExpression.parse("first.toUpperCase() = 'ALICE'").test(alice));
        assertEquals(true, GremlinExpression.parse("last.length() > 4").test(alice));
        assertEquals(true, GremlinExpression.parse("first.startsWith('al') && last.endsWith('th')").test(alice));
    }

    @Test
    void absOfANumberKeepsItsShape() {
        assertEquals(42L, eval("delta.abs()", edge(Map.of("delta", -42L))));
        assertEquals(2.5, eval("delta.abs()", edge(Map.of("delta", -2.5))));
    }

    @Test
    void aMissingValueStaysMissing() {
        GremlinEdge empty = edge(Map.of());

        assertNull(eval("missing.toUpperCase()", empty));
        assertNull(eval("missing.length()", empty));
        assertNull(eval("missing.substring(1)", empty));
        assertNull(eval("missing.abs()", empty));
    }

    @Test
    void valueOfIdAndLabelReadTheElementItself() {
        GremlinEdge edge = edge(Map.of("name", "Alice"));

        assertEquals("e1", eval("it.get().value('id')", edge));
        assertEquals("rel", eval("it.get().value('label')", edge));
        assertEquals("e1", eval("it.value('id')", edge));
        assertEquals("e1", eval("it.get().value('$id')", edge));
        // a real property of another name is untouched
        assertEquals("Alice", eval("it.get().value('name')", edge));
    }

    @Test
    void rejectsTheWrongArityOrAnUnknownMethod() {
        assertThrows(QueryException.class, () -> GremlinExpression.parse("name.length('x')"));
        assertThrows(QueryException.class, () -> GremlinExpression.parse("name.substring(1, 2, 3)"));
        assertThrows(QueryException.class, () -> GremlinExpression.parse("name.replace('a')"));
        assertThrows(QueryException.class, () -> GremlinExpression.parse("name.shrink()"));
    }
}
