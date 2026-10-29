package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.dto.QueryResponse;
import com.pengcheng.phoenixgraph.http.dto.ScriptResponse;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an answer may contain at an instant: an element whose validity window excludes it is dropped, an element
 * without a window is kept, and an edge goes when either its own window or one of its endpoints does - a edge
 * that points at a vertex the answer does not contain cannot be drawn, so keeping it would be a lie about what
 * the graph was.
 */
class SubgraphValidityTest {

    private static final long SEPTEMBER = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli();
    private static final long OCTOBER = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();

    private static Subgraph.Vertex vertex(String id, Map<String, Object> properties) {
        return new Subgraph.Vertex("Entity", id, properties, 1L, 2L);
    }

    private static Subgraph.Edge edge(String id, String source, String target, Map<String, Object> properties) {
        return new Subgraph.Edge("Relates", id, source, target, properties, 1L, 2L);
    }

    private static Subgraph mixed() {
        return new Subgraph(
                List.of(vertex("deploy-42", Map.of("validFrom", SEPTEMBER, "validTo", OCTOBER)),
                        vertex("deploy-48", Map.of("validFrom", OCTOBER)),
                        vertex("redis-cache", Map.of("name", "redis-cache"))),
                List.of(edge("a", "deploy-42", "redis-cache", Map.of("relation", "affects")),
                        edge("b", "deploy-48", "redis-cache", Map.of("relation", "affects"))));
    }

    @Test
    void anElementOutsideItsWindowIsNotInTheAnswer() {
        Subgraph before = SubgraphValidity.filter(mixed(), OCTOBER - 1);
        assertEquals(List.of("deploy-42", "redis-cache"), before.vertices().stream().map(Subgraph.Vertex::id).toList(),
                "deploy-48 is valid only from October on");
        assertEquals(List.of("a"), before.edges().stream().map(Subgraph.Edge::id).toList());

        Subgraph after = SubgraphValidity.filter(mixed(), OCTOBER);
        assertEquals(List.of("deploy-48", "redis-cache"), after.vertices().stream().map(Subgraph.Vertex::id).toList(),
                "the September relation has closed, deploy-42 itself has not");
        assertEquals(List.of("b"), after.edges().stream().map(Subgraph.Edge::id).toList());
    }

    @Test
    void anUnversionedElementIsValidAtEveryInstant() {
        long ancient = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli();
        Subgraph answer = SubgraphValidity.filter(mixed(), ancient);
        assertTrue(answer.vertices().stream().anyMatch(vertex -> vertex.id().equals("redis-cache")));
        assertEquals(1, answer.vertices().size());
    }

    @Test
    void anEdgeWhoseEndpointWasDroppedGoesWithIt() {
        Subgraph subgraph = new Subgraph(
                List.of(vertex("deploy-48", Map.of("validFrom", OCTOBER)), vertex("redis-cache", Map.of())),
                List.of(edge("b", "deploy-48", "redis-cache", Map.of("relation", "affects"))));
        Subgraph filtered = SubgraphValidity.filter(subgraph, SEPTEMBER);
        assertEquals(List.of("redis-cache"), filtered.vertices().stream().map(Subgraph.Vertex::id).toList());
        assertTrue(filtered.edges().isEmpty(), "an edge to a dropped vertex cannot be drawn");
    }

    @Test
    void noInstantMeansTheAnswerIsUntouched() {
        Subgraph subgraph = mixed();
        assertSame(subgraph, SubgraphValidity.filter(subgraph, null));
        // and a subgraph that is entirely inside the window is returned as it was, not rebuilt
        Subgraph latest = new Subgraph(List.of(vertex("redis-cache", Map.of())), List.of());
        assertSame(latest, SubgraphValidity.filter(latest, OCTOBER));
    }

    @Test
    void theWrappersKeepTheirOtherFields() {
        Subgraph subgraph = mixed();
        QueryResponse response = new QueryResponse(List.of(3L), subgraph);
        QueryResponse filtered = (QueryResponse) SubgraphValidity.filter(response, OCTOBER - 1);
        assertEquals(List.of(3L), filtered.result(), "the answer value is not a subgraph and is left alone");
        assertEquals(2, filtered.subgraph().vertices().size());

        ScriptResponse script = new ScriptResponse(List.of(1L), List.of("out"), subgraph, null);
        ScriptResponse filteredScript = (ScriptResponse) SubgraphValidity.filter(script, OCTOBER - 1);
        assertEquals(List.of("out"), filteredScript.output());
        assertEquals(2, filteredScript.subgraph().vertices().size());
    }
}
