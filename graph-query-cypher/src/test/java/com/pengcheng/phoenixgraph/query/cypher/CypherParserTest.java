package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Cypher parser: the accepted grammar, the parsed shape and the error messages. */
class CypherParserTest {

    @Test
    void parsesAMatchWithPropertiesAndAProjection() {
        CypherQuery query = CypherParser.parse(
                "MATCH (a:Account {id: '135'})-[p:Payment]->(b:Account) "
                        + "RETURN b.id AS receiver, p.amount ORDER BY p.amount DESC LIMIT 10");

        assertEquals(1, query.matches().size());
        CypherPattern.Path path = query.matches().get(0);
        assertEquals("a", path.start().variable());
        assertEquals("Account", path.start().label());
        assertNotNull(path.start().property("id"));
        assertEquals(1, path.steps().size());
        CypherPattern.Relationship relationship = path.steps().get(0).relationship();
        assertEquals("p", relationship.variable());
        assertEquals(java.util.List.of("Payment"), relationship.types());
        assertEquals(CypherPattern.Direction.OUT, relationship.direction());
        assertFalse(relationship.variableLength());
        assertEquals("b", path.steps().get(0).node().variable());

        assertEquals(2, query.projection().size());
        assertEquals("receiver", query.projection().get(0).alias());
        assertEquals("b.id", query.projection().get(0).text());
        assertEquals("p.amount", query.projection().get(1).column());
        assertEquals(1, query.orderBy().size());
        assertTrue(query.orderBy().get(0).descending());
        assertEquals(10, query.limit());
        assertEquals(0, query.skip());
    }

    @Test
    void parsesDirectionsAndMultipleTypes() {
        assertEquals(CypherPattern.Direction.IN, CypherParser.parse(
                "MATCH (a:Account {id:'135'})<-[p:Payment]-(b:Account) RETURN b.id")
                .matches().get(0).steps().get(0).relationship().direction());
        assertEquals(CypherPattern.Direction.BOTH, CypherParser.parse(
                "MATCH (a:Account {id:'135'})-[p:Payment]-(b:Account) RETURN b.id")
                .matches().get(0).steps().get(0).relationship().direction());
        assertEquals(CypherPattern.Direction.OUT, CypherParser.parse(
                "MATCH (a:Account {id:'135'})-->(b:Account) RETURN b.id")
                .matches().get(0).steps().get(0).relationship().direction());
        assertEquals(java.util.List.of("Payment", "PhoneToAccount"), CypherParser.parse(
                "MATCH (a:Account {id:'135'})-[r:Payment|PhoneToAccount]->(b) RETURN r")
                .matches().get(0).steps().get(0).relationship().types());
    }

    @Test
    void parsesVariableLengthAndMultiplePatterns() {
        CypherQuery query = CypherParser.parse(
                "MATCH (a:Account {id:'135'})-[:Payment*1..3]->(c:Account), (a)-[:PhoneToAccount]-(ph:Phone) "
                        + "RETURN DISTINCT c.id, ph.id");
        assertEquals(2, query.matches().size());
        CypherPattern.Relationship variadic = query.matches().get(0).steps().get(0).relationship();
        assertTrue(variadic.variableLength());
        assertEquals(1, variadic.minHops());
        assertEquals(3, variadic.maxHops());
        assertTrue(query.distinct());
    }

    @Test
    void parsesWhereExpressions() {
        CypherQuery query = CypherParser.parse("MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) "
                + "WHERE (p.amount > 100 AND b.accountStatus = 'ACTIVE') "
                + "OR p.status IN ['SUCCESS', 'PENDING'] "
                + "AND NOT b.accountType CONTAINS 'business' RETURN b.id");
        assertNotNull(query.where());
        assertTrue(query.where().variables().contains("p"));
        assertTrue(query.where().variables().contains("b"));

        CypherExpression loose = CypherParser.parse("MATCH (a:Account {id:'1'}) WHERE a.primaryAddress "
                + "STARTS WITH 'addr-' AND a.accountType IS NOT NULL RETURN a.id").where();
        assertNotNull(loose);
    }

    @Test
    void parsesAggregationsAndOrderingByAlias() {
        CypherQuery query = CypherParser.parse("MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) "
                + "RETURN b.accountType AS type, count(*) AS n, sum(p.amount) AS total "
                + "ORDER BY n DESC, total ASC SKIP 1 LIMIT 5");
        assertTrue(query.projection().get(1).aggregated());
        assertTrue(query.projection().get(0).aggregated() == false);
        assertEquals(2, query.orderBy().size());
        assertTrue(query.orderBy().get(0).descending());
        assertFalse(query.orderBy().get(1).descending());
        assertEquals(1, query.skip());
        assertEquals(5, query.limit());
    }

    @Test
    void rejectsUnsupportedClausesWithAHelpfulMessage() {
        QueryException create = assertThrows(QueryException.class, () -> CypherParser.parse(
                "CREATE (a:Account {id:'1'})"));
        assertTrue(create.getMessage().contains("MATCH"), create.getMessage());

        QueryException write = assertThrows(QueryException.class, () -> CypherParser.parse(
                "MATCH (a:Account {id:'1'}) SET a.accountType = 'x' RETURN a"));
        assertTrue(write.getMessage().contains("SET") || write.getMessage().contains("RETURN"),
                write.getMessage());
    }

    @Test
    void reportsPositionsOnSyntaxErrors() {
        QueryException unterminated = assertThrows(QueryException.class, () -> CypherParser.parse(
                "MATCH (a:Account {id:'135}) RETURN a"));
        assertTrue(unterminated.getMessage().contains("unterminated string"), unterminated.getMessage());

        QueryException missingReturn = assertThrows(QueryException.class,
                () -> CypherParser.parse("MATCH (a:Account {id:'135'})"));
        assertTrue(missingReturn.getMessage().contains("RETURN"), missingReturn.getMessage());

        QueryException unclosed = assertThrows(QueryException.class,
                () -> CypherParser.parse("MATCH (a:Account {id:'135'} RETURN a"));
        assertTrue(unclosed.getMessage().contains("column"), unclosed.getMessage());

        QueryException blank = assertThrows(QueryException.class, () -> CypherParser.parse("   "));
        assertTrue(blank.getMessage().contains("blank"), blank.getMessage());
    }

    @Test
    void rejectsNestedAggregations() {
        QueryException error = assertThrows(QueryException.class, () -> CypherParser.parse(
                "MATCH (a:Account {id:'135'})-[p:Payment]->(b:Account) RETURN count(*) + 1"));
        assertTrue(error.getMessage().contains("aggregation") || error.getMessage().contains("unexpected"),
                error.getMessage());
    }

    @Test
    void ignoresCommentsAndTrailingSemicolons() {
        CypherQuery query = CypherParser.parse("// the 135 account\n"
                + "MATCH (a:Account {id:'135'}) RETURN a.id;  // trailing comment\n");
        assertEquals(1, query.projection().size());
        assertEquals("a.id", query.projection().get(0).column());
    }
}
