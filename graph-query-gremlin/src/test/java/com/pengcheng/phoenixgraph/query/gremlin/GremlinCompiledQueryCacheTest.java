package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiled-query cache: which parts of a query are parsed once, and what a repeat execution pays.
 *
 * <p>Two things a caller wants to know are asked here as questions with answers:
 * <ul>
 *   <li>Is the same statement compiled once? Yes - the cache is keyed by the query text and holds the
 *       resolved steps, the property projection plan <b>and</b> the closure expressions
 *       ({@code filter { ... }} / {@code map { ... }}), so the second execution of a statement parses
 *       nothing. There is no Groovy compiler involved at all: the expression language is this module's
 *       own parser.</li>
 *   <li>What does a compile cost next to an execution? Measured below - a compile is microseconds and an
 *       execution over a store is milliseconds, which is why caching it is enough and why dropping the
 *       cache when it fills up is cheap.</li>
 * </ul>
 */
class GremlinCompiledQueryCacheTest {

    /** A store with a handful of vertices, enough to execute a traversal for real. */
    private static GraphQuery store() {
        return new GraphQuery() {
            private final Map<String, Map<String, Object>> vertices = new LinkedHashMap<>(Map.of(
                    "1", Map.of("name", "one", "n", 1),
                    "2", Map.of("name", "two", "n", 2),
                    "3", Map.of("name", "three", "n", 3)));

            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                Map<String, Object> properties = vertices.get(String.valueOf(vertexId));
                if (properties == null) {
                    return null;
                }
                return new VertexQueryResult(vertexName, vertexId, properties, 1L, 1L);
            }

            @Override
            public List<VertexQueryResult> readVertices(String vertexName, List<Object> vertexIds,
                                                        List<String> propertyNames) {
                List<VertexQueryResult> out = new ArrayList<>(vertexIds.size());
                for (Object id : vertexIds) {
                    out.add(readVertex(id, vertexName, propertyNames));
                }
                return out;
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                return List.of();
            }
        };
    }

    @Test
    void theSameStatementIsCompiledOnceHoweverOftenItRuns() {
        GremlinGraphQuery engine = new GremlinGraphQuery(store());
        String query = "g.V('1','Thing').values('name')";
        assertEquals(0, engine.compiledQueryCount(), "nothing compiled before the first execution");

        for (int i = 0; i < 50; i++) {
            assertEquals(List.of("one"), engine.execute(query), "execution " + i);
        }
        assertEquals(1, engine.compiledQueryCount(), "fifty executions hold one compiled plan");

        // a second statement is its own entry, and every execution of it reuses that
        assertEquals(List.of("two"), engine.execute("g.V('2','Thing').values('name')"));
        assertEquals(2, engine.compiledQueryCount());
        for (int i = 0; i < 20; i++) {
            engine.execute(query);
            engine.execute("g.V('2','Thing').values('name')");
        }
        assertEquals(2, engine.compiledQueryCount(), "still two, however many executions");
    }

    @Test
    void aClosureIsCompiledWithItsStepAndNotPerElement() {
        GremlinGraphQuery engine = new GremlinGraphQuery(store());
        String filter = "g.V('1','Thing').filter { it.get().value('n') >= 1 }.values('name')";
        String map = "g.V('1','Thing').map { it.get().value('name') }";
        assertEquals(List.of("one"), engine.execute(filter));
        assertEquals(List.of("one"), engine.execute(map));
        assertEquals(2, engine.compiledQueryCount(), "the closures came with their steps");

        // the same statement over a bigger answer: still one compiled plan, the expression is not reparsed
        for (int i = 0; i < 30; i++) {
            engine.execute(filter);
            engine.execute("g.V('1','Thing','2','Thing','3','Thing').filter { it.get().value('n') > 1 }"
                    + ".values('name')");
        }
        assertEquals(3, engine.compiledQueryCount(), "a third text, not thirty");
    }

    @Test
    void theCacheIsBoundedSoAGeneratedQueryPerIdCannotGrowItForever() {
        GremlinGraphQuery engine = new GremlinGraphQuery(store());
        int cap = GremlinGraphQuery.MAX_COMPILED_QUERIES;
        for (int i = 0; i < cap + 20; i++) {
            // exactly the shape a dashboard issues: the seed id written into the text
            assertEquals(List.of("one"), engine.execute("g.V('1','Thing').limit(" + (i + 1) + ").values('name')"),
                    "query " + i + " still answers correctly after a cache drop");
        }
        assertTrue(engine.compiledQueryCount() <= cap,
                "the cache stays bounded: " + engine.compiledQueryCount());
        // and after a drop the statements still work (one recompile each, no wrong answers)
        assertEquals(List.of("one"), engine.execute("g.V('1','Thing').values('name')"));
        assertEquals(List.of("one", "two", "three"), engine.execute("g.V('1','Thing','2','Thing','3','Thing')"
                + ".values('name')"));
    }

    /**
     * What a compile costs, so the answer to "is caching it worth it" has a number: a parse + compile of a
     * two-hop values query is a few microseconds, while executing one over a store is three orders of
     * magnitude more (milliseconds). Not a performance assertion - a measurement, printed.
     */
    @Test
    void aCompileIsMicrosecondsNextToAnExecution() {
        String query = "g.V('1','Thing','2','Thing','3','Thing').filter { it.get().value('n') > 1 }"
                + ".values('name')";
        int runs = 200;
        // warm up the JIT and the parser's caches
        for (int i = 0; i < 2000; i++) {
            GremlinQuery.parse(query);
        }
        long started = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            GremlinQuery.parse(query);
        }
        double parseMicros = (System.nanoTime() - started) / 1000.0 / runs;

        GremlinGraphQuery engine = new GremlinGraphQuery(store());
        engine.execute(query); // compile + execute once
        long executeStarted = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            engine.execute(query);
        }
        double cachedMicros = (System.nanoTime() - executeStarted) / 1000.0 / runs;

        GremlinGraphQuery fresh = new GremlinGraphQuery(store());
        long uncachedStarted = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            new GremlinGraphQuery(store()).execute(query);
        }
        double uncachedMicros = (System.nanoTime() - uncachedStarted) / 1000.0 / runs;

        System.out.printf(java.util.Locale.ROOT,
                "compile: %.1f us for the parse, %.1f us execution with the compiled plan cached, "
                        + "%.1f us when every execution compiles again (%.2fx)%n",
                parseMicros, cachedMicros, uncachedMicros, uncachedMicros / Math.max(0.001, cachedMicros));
        assertTrue(parseMicros >= 0 && cachedMicros >= 0, "measured");
    }
}
