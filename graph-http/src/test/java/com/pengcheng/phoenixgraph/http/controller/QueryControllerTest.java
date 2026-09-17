package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.http.dto.QueryResponse;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.http.service.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Spring MVC binding of the query endpoint (JSON request/response)
 * without touching a real storage backend: the request carries the language, the
 * query template and its parameters, and the response is whatever the selected
 * language produces (traversers, a subgraph, or Cypher columns/rows).
 */
@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @Test
    void queryEndpointBindsRequestAndReturnsJsonArray() throws Exception {
        when(queryService.query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false, false)).thenReturn(List.of(6L));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V('${id}','Account').count()",
                                  "params": { "id": "1001" } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(6));

        verify(queryService).query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false, false);
    }

    @Test
    void queryEndpointReturnsSubgraphWhenRequested() throws Exception {
        Subgraph subgraph = new Subgraph(
                List.of(new Subgraph.Vertex("Account", "888", Map.of("accountStatus", "ACTIVE"), 1L, 2L)),
                List.of(new Subgraph.Edge("Payment", "e1", "888", "1001", Map.of("status", "SUCCESS"), 1L, 2L)));
        when(queryService.query("payment-graph", "g.V(888,'Account').out('Payment')", Map.of(),
                "gremlin", true, false)).thenReturn(subgraph);

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
    }

    @Test
    void includeResultReturnsTheAnswerNextToTheSubgraph() throws Exception {
        Subgraph subgraph = new Subgraph(
                List.of(new Subgraph.Vertex("Account", "1000", Map.of(), 1L, 2L),
                        new Subgraph.Vertex("Phone", "6000", Map.of(), 1L, 2L)),
                List.of(new Subgraph.Edge("AccountToPhone", "atp_0", "1000", "6000", Map.of(), 1L, 2L)));
        when(queryService.query("payment-graph", "g.V('1000','Account').out('AccountToPhone').count()",
                Map.of(), "gremlin", true, true))
                .thenReturn(new QueryResponse(List.of(2L), subgraph));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V('1000','Account').out('AccountToPhone').count()",
                                  "params": {},
                                  "returnSubgraph": true,
                                  "includeResult": true }
                                """))
                .andExpect(status().isOk())
                // the computed value a UI shows in a floating window ...
                .andExpect(jsonPath("$.result[0]").value(2))
                // ... next to the graph it draws
                .andExpect(jsonPath("$.subgraph.vertices.length()").value(2))
                .andExpect(jsonPath("$.subgraph.edges[0].label").value("AccountToPhone"));

        verify(queryService).query(eq("payment-graph"), anyString(), anyMap(), eq("gremlin"),
                eq(true), eq(true));
    }

    @Test
    void withoutIncludeResultTheSubgraphResponseIsUnchanged() throws Exception {
        Subgraph subgraph = new Subgraph(
                List.of(new Subgraph.Vertex("Account", "888", Map.of(), 1L, 2L)), List.of());
        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("gremlin"), eq(true), eq(false)))
                .thenReturn(subgraph);

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "query": "g.V(888,'Account').out('Payment')",
                                  "returnSubgraph": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vertices.length()").value(1))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void cypherLanguageReturnsColumnsRowsAndSubgraph() throws Exception {
        CypherResult result = new CypherResult(List.of("b.id", "p.amount"),
                List.of(Map.of("b.id", "102", "p.amount", 20L)),
                new Subgraph(List.of(new Subgraph.Vertex("Account", "100", Map.of(), 1L, 2L)), List.of()));
        when(queryService.query("payment-graph",
                "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.amount",
                Map.of("id", "100"), "cypher", true, false)).thenReturn(result);

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph",
                                  "language": "cypher",
                                  "query": "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.amount",
                                  "params": { "id": "100" },
                                  "returnSubgraph": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0]").value("b.id"))
                .andExpect(jsonPath("$.columns[1]").value("p.amount"))
                .andExpect(jsonPath("$.rows[0]['b.id']").value("102"))
                .andExpect(jsonPath("$.rows[0]['p.amount']").value(20))
                .andExpect(jsonPath("$.subgraph.vertices[0].id").value("100"));

        verify(queryService).query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), eq(true),
                eq(false)); // this request did not ask for the answer separately
    }

    @Test
    void theLanguageDefaultsToGremlin() throws Exception {
        when(queryService.query(anyString(), anyString(), anyMap(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of());

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph", "query": "g.V()" }
                                """))
                .andExpect(status().isOk());

        // the controller normalises a missing language to gremlin
        verify(queryService).query("payment-graph", "g.V()", Map.of(), "gremlin", false, false);
    }

    @Test
    void failedQueryReturnsMessageExceptionAndStackTrace() throws Exception {
        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), anyString(), anyBoolean(), anyBoolean()))
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
    void aCypherErrorIsReportedWithItsPosition() throws Exception {
        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), anyBoolean(), anyBoolean()))
                .thenThrow(new QueryException("line 1, column 23: expected ')'"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph", "language": "cypher",
                                  "query": "MATCH (a:Account {id:'100' RETURN a.id" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("line 1, column 23: expected ')'"))
                .andExpect(jsonPath("$.exception")
                        .value("com.pengcheng.phoenixgraph.query.QueryException"));
    }

    @Test
    void unknownGraphReturnsBadRequestWithAvailableGraphs() throws Exception {
        when(queryService.query(eq("nope"), anyString(), anyMap(), anyString(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalArgumentException(
                        "Unknown graph 'nope'; available graphs: payment-graph"));

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

    @Test
    void unknownLanguageIsRejected() throws Exception {
        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("sparql"), anyBoolean(), anyBoolean()))
                .thenThrow(new QueryException("Unknown query language 'sparql'; supported: gremlin, cypher"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graphName": "payment-graph", "language": "sparql",
                                  "query": "SELECT * WHERE { ?s ?p ?o }" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Unknown query language 'sparql'; supported: gremlin, cypher"));
    }

}
