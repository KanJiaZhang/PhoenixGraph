package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code P} predicate vocabulary: what a comparison argument means when it is a predicate instead
 * of a bare value.
 *
 * <p>This is where "found nothing" and "never asked" used to be the same answer: only a few predicates
 * were parsed, the rest were compared as the literal text of the call, so {@code eq('CLOSED')} matched
 * nothing at all. Every kind TinkerPop defines is evaluated now, and a call that names an unknown
 * predicate is an error.
 */
class GremlinPredicateTest {

    private static boolean matches(String predicate, Object stored) {
        GremlinPredicate parsed = GremlinPredicate.parse(predicate);
        assertTrue(parsed != null, "the token is a predicate: " + predicate);
        return parsed.test(stored);
    }

    @Test
    void equalityAndInequality() {
        assertTrue(matches("eq('CLOSED')", "CLOSED"), "the quoted argument is compared unquoted");
        assertFalse(matches("eq('CLOSED')", "ACTIVE"));
        assertTrue(matches("eq('closed')", "CLOSED"), "text equality ignores case, like the rest of the engine");
        assertTrue(matches("eq(2)", 2L), "numbers compare across types");
        assertTrue(matches("eq(2)", "2"), "and against the text form a store may report");
        assertTrue(matches("eq(true)", Boolean.TRUE));
        assertFalse(matches("neq('CLOSED')", "CLOSED"));
        assertTrue(matches("neq('CLOSED')", "ACTIVE"));
        assertTrue(GremlinPredicate.parse("P.eq('x')").test("x"), "the P. prefix is optional");
    }

    @Test
    void ordering() {
        assertTrue(matches("gt(10)", 11));
        assertFalse(matches("gt(10)", 10), "gt is exclusive");
        assertTrue(matches("gte(10)", 10));
        assertTrue(matches("lt(10)", 9));
        assertTrue(matches("lte(10)", 10));
        assertTrue(matches("gt('B')", "C"), "text orders by String.compareTo");
        assertFalse(matches("gt('C')", "B"));
    }

    @Test
    void rangesFollowTinkerPopsDefinitions() {
        // between(a, b) is a <= x < b
        assertTrue(matches("between(10,20)", 10));
        assertTrue(matches("between(10,20)", 19));
        assertFalse(matches("between(10,20)", 20));
        // inside(a, b) is a < x < b
        assertFalse(matches("inside(10,20)", 10));
        assertTrue(matches("inside(10,20)", 11));
        assertFalse(matches("inside(10,20)", 20));
        // outside(a, b) is x < a || x > b
        assertTrue(matches("outside(10,20)", 9));
        assertTrue(matches("outside(10,20)", 21));
        assertFalse(matches("outside(10,20)", 15));
    }

    @Test
    void membershipAndText() {
        assertTrue(matches("within('A','B')", "a"), "within is case-insensitive text membership");
        assertFalse(matches("within('A','B')", "C"));
        assertTrue(matches("without('A','B')", "C"));
        assertTrue(matches("without('A','B')", 1), "a value outside the set matches without(...)");
        assertTrue(matches("containing('los')", "CLOSED"));
        assertTrue(matches("startingWith('CLO')", "CLOSED"));
        assertTrue(matches("endingWith('SED')", "CLOSED"));
        assertTrue(matches("notContaining('xyz')", "CLOSED"));
        assertTrue(matches("TextP.containing('los')", "CLOSED"), "the TextP. prefix is optional");
    }

    @Test
    void combinators() {
        assertTrue(matches("not(within('CLOSED'))", "ACTIVE"));
        assertFalse(matches("not(within('CLOSED'))", "CLOSED"));
        assertTrue(matches("not(eq('CLOSED'))", "ACTIVE"));
        assertTrue(matches("and(gt(10),lt(20))", 15));
        assertFalse(matches("and(gt(10),lt(20))", 25));
        assertTrue(matches("or(gt(20),lt(10))", 5));
        assertFalse(matches("or(gt(20),lt(10))", 15));
        // a nested argument that is not a predicate is compared for equality
        assertTrue(matches("and(eq('A'),'A')", "A"));
        assertThrows(QueryException.class, () -> GremlinPredicate.parse("and()"));
    }

    @Test
    void aMultiValuedPropertyMatchesElementWise() {
        List<Object> values = List.of("Personal", "Business");
        assertTrue(matches("within('Business')", values));
        assertFalse(matches("within('Other')", values));
        assertTrue(matches("notContaining('xyz')", values), "a negative predicate holds when no element matches");
        assertFalse(matches("containing('Bus')", List.of()), "and is false for an empty collection");
    }

    @Test
    void aPlainValueIsNotAPredicateAndAnUnknownOneIsAnError() {
        assertNull(GremlinPredicate.parse("'CLOSED'"), "a quoted value is compared by equality");
        assertNull(GremlinPredicate.parse("CLOSED"));
        assertNull(GremlinPredicate.parse("42"));
        assertNull(GremlinPredicate.parse(null));
        assertNull(GremlinPredicate.parse(""), "an empty token is not a call");

        QueryException error = assertThrows(QueryException.class,
                () -> GremlinPredicate.parse("roughly('CLOSED')"));
        assertTrue(error.getMessage().contains("Unknown predicate 'roughly'"), error.getMessage());
        assertTrue(error.getMessage().contains("between"), "the message lists what is supported");
    }

    @Test
    void theSupportedListNamesEveryKind() {
        for (String predicate : List.of("eq", "neq", "gt", "gte", "lt", "lte", "between", "inside",
                "outside", "within", "without", "containing", "startingWith", "endingWith",
                "notContaining", "notStartingWith", "notEndingWith", "not", "and", "or")) {
            assertTrue(GremlinPredicate.SUPPORTED.contains(predicate), predicate + " is listed");
        }
        assertEquals(20, GremlinPredicate.SUPPORTED.split(",").length);
    }
}
