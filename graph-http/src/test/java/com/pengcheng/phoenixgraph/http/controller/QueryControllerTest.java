package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.service.QueryService;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.gremlin.Subgraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Spring MVC binding of the query endpoint (JSON request/response)
 * without touching a real storage backend.
 */
@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @Test
    void queryEndpointBindsRequestAndReturnsJsonArray() throws Exception {
        when(queryService.query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001")))
                .thenReturn(List.of(6L));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V('${id}','Account').count()",
                                  "params": { "id": "1001" } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(6));

        verify(queryService).query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"));
    }

    @Test
    void queryEndpointReturnsSubgraphWhenRequested() throws Exception {
        Subgraph subgraph = new Subgraph(
                List.of(new Subgraph.Vertex("Account", "888", Map.of("accountStatus", "ACTIVE"), 1L, 2L)),
                List.of(new Subgraph.Edge("Payment", "e1", "888", "1001", Map.of("status", "SUCCESS"), 1L, 2L)));
        when(queryService.querySubgraph("payment-graph", "g.V(888,'Account').out('Payment')", Map.of()))
                .thenReturn(subgraph);

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V(888,'Account').out('Payment')",
                                  "params": {},
                                  "returnSubgraph": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vertices.length()").value(1))
                .andExpect(jsonPath("$.vertices[0].id").value("888"))
                .andExpect(jsonPath("$.edges.length()").value(1))
                .andExpect(jsonPath("$.edges[0].source").value("888"))
                .andExpect(jsonPath("$.edges[0].target").value("1001"));

        verify(queryService).querySubgraph("payment-graph", "g.V(888,'Account').out('Payment')", Map.of());
    }

    @Test
    void failedQueryReturnsMessageExceptionAndStackTrace() throws Exception {
        when(queryService.querySubgraph("payment-graph", "g.V(1,'Account').nope()", Map.of()))
                .thenThrow(new QueryException("Unknown Gremlin step: nope"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V(1,'Account').nope()",
                                  "params": {},
                                  "returnSubgraph": true }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown Gremlin step: nope"))
                .andExpect(jsonPath("$.exception")
                        .value("com.pengcheng.phoenixgraph.query.QueryException"))
                .andExpect(jsonPath("$.hint").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace[0]").value(
                        "com.pengcheng.phoenixgraph.query.QueryException: Unknown Gremlin step: nope"))
                .andExpect(jsonPath("$.stackTrace.length()").value(org.hamcrest.Matchers.greaterThan(3)));
    }

    @Test
    void unknownGraphReturnsBadRequestWithAvailableGraphs() throws Exception {
        when(queryService.query("nope", "g.V('1','Account')", Map.of()))
                .thenThrow(new IllegalArgumentException("Unknown graph 'nope'; available graphs: payment-graph"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "nope",
                                  "query": "g.V('1','Account')",
                                  "params": {} }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Unknown graph 'nope'; available graphs: payment-graph"));
    }
}
