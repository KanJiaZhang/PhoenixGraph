package com.pengcheng.phoenixgraph.query.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphQueryTest {

    static final class FakeQuery implements GraphQuery {
        EdgeSide lastDirection;
        List<String> lastProperties;
        final VertexQueryResult vertexResult = new VertexQueryResult("person", "v1", Map.of(), 1L, 2L);
        final EdgeQueryResult edgeResult = new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L);

        @Override
        public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
            return vertexResult;
        }

        @Override
        public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                               EdgeSide direction, List<String> propertyNames) {
            this.lastDirection = direction;
            this.lastProperties = propertyNames;
            return List.of(edgeResult);
        }
    }

    @Test
    void readVertexVarargsDelegatesToCoreMethod() {
        FakeQuery query = new FakeQuery();
        VertexQueryResult result = query.readVertex("v1", "person", "name", "age");

        assertSame(query.vertexResult, result);
    }

    @Test
    void readEdgesVarargsDelegatesWithExplicitDirection() {
        FakeQuery query = new FakeQuery();
        query.readEdges("v1", "person", "knows", EdgeSide.IN, "weight");

        assertEquals(EdgeSide.IN, query.lastDirection);
        assertEquals(List.of("weight"), query.lastProperties);
    }

    @Test
    void readEdgesListWithoutDirectionDefaultsToBoth() {
        FakeQuery query = new FakeQuery();
        query.readEdges("v1", "person", "knows", List.of("weight"));

        assertEquals(EdgeSide.BOTH, query.lastDirection);
        assertEquals(List.of("weight"), query.lastProperties);
    }

    @Test
    void readEdgesVarargsWithoutDirectionDefaultsToBoth() {
        FakeQuery query = new FakeQuery();
        query.readEdges("v1", "person", "knows", "weight");

        assertEquals(EdgeSide.BOTH, query.lastDirection);
        assertEquals(List.of("weight"), query.lastProperties);
    }

    @Test
    void resultRecordsDefensivelyCopyProperties() {
        Map<String, Object> vertexProps = new LinkedHashMap<>();
        vertexProps.put("a", 1);
        VertexQueryResult vertex = new VertexQueryResult("person", "v1", vertexProps, 1L, 2L);
        vertexProps.put("b", 2);
        assertEquals(Map.of("a", 1), vertex.properties());

        Map<String, Object> edgeProps = new LinkedHashMap<>();
        edgeProps.put("w", 0.5);
        EdgeQueryResult edge = new EdgeQueryResult("knows", "e1", EdgeSide.OUT, "n1", edgeProps, 1L, 2L);
        edgeProps.put("x", 1);
        assertEquals(Map.of("w", 0.5), edge.properties());
    }

    @Test
    void resultRecordsRejectBlankNamesAndNullSide() {
        assertThrows(IllegalArgumentException.class,
                () -> new VertexQueryResult(" ", "v1", Map.of(), 1L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeQueryResult(" ", "e1", EdgeSide.OUT, "n1", Map.of(), 1L, 2L));
        assertThrows(NullPointerException.class,
                () -> new EdgeQueryResult("knows", "e1", null, "n1", Map.of(), 1L, 2L));
    }
}
