package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GremlinFilterExpressionTest {

    private static GremlinEdge edge(Map<String, Object> properties) {
        return new GremlinEdge("rel", "e1", EdgeSide.OUT, "n1", properties, 1L, 2L);
    }

    private static GremlinEdge kgEdge(String relation, String xrelation) {
        return edge(Map.of("relation", relation, "xrelation", xrelation));
    }

    @Test
    void orOfEqualsMatchesEitherProperty() {
        GremlinFilterExpression filter = GremlinFilterExpression
                .parse("relation.equals('directedBy') || xrelation.equals('directed')");

        assertTrue(filter.test(kgEdge("directedBy", "directed")));
        assertTrue(filter.test(kgEdge("directedBy", "other")));
        assertTrue(filter.test(kgEdge("other", "directed")));
        assertFalse(filter.test(kgEdge("other", "other")));
    }

    @Test
    void acceptsOperatorAndBareValueForms() {
        GremlinFilterExpression assignment = GremlinFilterExpression.parse("relation = 'r1'");
        GremlinFilterExpression doubleEquals = GremlinFilterExpression.parse("relation=='r1'");
        GremlinFilterExpression wordForm = GremlinFilterExpression.parse("relation equals r1");

        assertTrue(assignment.test(kgEdge("r1", "x")));
        assertTrue(doubleEquals.test(kgEdge("r1", "x")));
        assertTrue(wordForm.test(kgEdge("r1", "x")));
        assertFalse(wordForm.test(kgEdge("r2", "x")));
    }

    @Test
    void notEqualsAndPresence() {
        GremlinFilterExpression notEquals = GremlinFilterExpression.parse("relation != 'r1'");
        assertFalse(notEquals.test(kgEdge("r1", "x")));
        assertTrue(notEquals.test(kgEdge("r2", "x")));
        assertTrue(notEquals.test(edge(Map.of()))); // missing property is "not equal"

        GremlinFilterExpression present = GremlinFilterExpression.parse("xrelation");
        assertTrue(present.test(kgEdge("r1", "x")));
        assertFalse(present.test(edge(Map.of())));
    }

    @Test
    void andBindsTighterThanOr() {
        GremlinFilterExpression filter = GremlinFilterExpression
                .parse("relation = 'a' || relation = 'b' && xrelation = 'c'");

        assertTrue(filter.test(kgEdge("a", "z")));
        assertTrue(filter.test(kgEdge("b", "c")));
        assertFalse(filter.test(kgEdge("b", "z")));
        assertFalse(filter.test(kgEdge("z", "c")));
    }

    @Test
    void keywordJoinsAreCaseInsensitiveAndBoundaryAware() {
        GremlinFilterExpression filter = GremlinFilterExpression.parse("relation EQ 'a' OR xrelation EQ 'b'");
        assertTrue(filter.test(kgEdge("a", "z")));
        assertTrue(filter.test(kgEdge("z", "b")));
        assertFalse(filter.test(kgEdge("z", "z")));

        // 'or' inside a property name must not split the expression
        assertTrue(GremlinFilterExpression.parse("proxyfor = 'a'").test(edge(Map.of("proxyfor", "a"))));
        // ... nor inside a quoted value
        assertTrue(GremlinFilterExpression.parse("relation = 'a or b'").test(kgEdge("a or b", "x")));
    }

    @Test
    void quotedValuesMayContainSeparators() {
        GremlinFilterExpression filter = GremlinFilterExpression.parse("relation = 'a || b'");
        assertTrue(filter.test(kgEdge("a || b", "x")));
        assertFalse(filter.test(kgEdge("a", "x")));
    }

    @Test
    void methodVariants() {
        assertTrue(GremlinFilterExpression.parse("name.contains('lice')").test(edge(Map.of("name", "alice"))));
        assertTrue(GremlinFilterExpression.parse("name.startsWith('al')").test(edge(Map.of("name", "alice"))));
        assertTrue(GremlinFilterExpression.parse("name.endsWith('ce')").test(edge(Map.of("name", "alice"))));
        assertFalse(GremlinFilterExpression.parse("name.startsWith('bo')").test(edge(Map.of("name", "alice"))));
    }

    @Test
    void numericValuesCompareNumerically() {
        assertTrue(GremlinFilterExpression.parse("weight = '0.5'").test(edge(Map.of("weight", 0.5))));
        assertTrue(GremlinFilterExpression.parse("count = '42'").test(edge(Map.of("count", 42L))));
        assertFalse(GremlinFilterExpression.parse("count = '43'").test(edge(Map.of("count", 42L))));
    }

    @Test
    void reportsReferencedPropertyKeys() {
        GremlinFilterExpression filter = GremlinFilterExpression
                .parse("relation.equals('a') || xrelation = 'b' && extra");

        assertEquals(Set.of("relation", "xrelation", "extra"), filter.propertyKeys());
        assertEquals(List.of("relation", "xrelation", "extra"),
                List.copyOf(filter.propertyKeys())); // declaration order is preserved
    }

    @Test
    void rejectsMalformedExpressions() {
        assertThrows(QueryException.class, () -> GremlinFilterExpression.parse(null));
        assertThrows(QueryException.class, () -> GremlinFilterExpression.parse("  "));
        assertThrows(QueryException.class, () -> GremlinFilterExpression.parse("a = 'x' || "));
        assertThrows(QueryException.class, () -> GremlinFilterExpression.parse("a = 'x' && "));
    }
}
