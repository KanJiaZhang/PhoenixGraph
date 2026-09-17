package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.service.GraphService;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC binding of the vertex and edge write endpoints.
 *
 * <p>Includes the path-variable endpoints ({@code /{id}}, {@code /{edgeId}},
 * {@code /{id}/properties/{index}}): a handler declaring {@code @PathVariable Object id}
 * needs the parameter name at runtime, which the build provides via
 * {@code <parameters>true</parameters>} - a regression here would make every read/delete
 * by id fail with 500 while the plain upserts keep working.
 */
@WebMvcTest({VertexController.class, EdgeController.class})
class VertexEdgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphService service;

    @Test
    void getVertexByIdResolvesThePathVariable() throws Exception {
        when(service.getVertex("1001")).thenReturn(new StoredVertex("1001", Map.of(), 1L, 2L));

        mockMvc.perform(get("/api/v1/vertices/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vertexId").value("1001"));

        verify(service).getVertex("1001");
    }

    @Test
    void unknownVertexByIdReturnsNotFound() throws Exception {
        when(service.getVertex("404")).thenReturn(null);

        mockMvc.perform(get("/api/v1/vertices/404")).andExpect(status().isNotFound());
    }

    @Test
    void deleteVertexByIdResolvesThePathVariable() throws Exception {
        when(service.deleteVertex("1001")).thenReturn(WriteOutcome.SUCCESS);

        mockMvc.perform(delete("/api/v1/vertices/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("1001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(service).deleteVertex("1001");
    }

    @Test
    void updateAndDeleteVertexPropertyResolveBothPathVariables() throws Exception {
        when(service.updateVertexProperty(eq("1001"), eq(3), any())).thenReturn(WriteOutcome.SUCCESS);
        when(service.deleteVertexProperty("1001", 3)).thenReturn(WriteOutcome.SUCCESS);

        mockMvc.perform(put("/api/v1/vertices/1001/properties/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"value\": \"Shanghai\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(delete("/api/v1/vertices/1001/properties/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(service).updateVertexProperty(eq("1001"), eq(3), any());
        verify(service).deleteVertexProperty("1001", 3);
    }

    @Test
    void deleteEdgeResolvesPathAndQueryParameters() throws Exception {
        when(service.deleteEdge("p1", "Payment", "100", "103")).thenReturn(WriteOutcome.SUCCESS);

        mockMvc.perform(delete("/api/v1/edges/p1")
                        .param("edgeSchemaName", "Payment")
                        .param("source", "100")
                        .param("destination", "103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(service).deleteEdge("p1", "Payment", "100", "103");
    }

    @Test
    void upsertVertexStillWorks() throws Exception {
        when(service.upsertVertex(any())).thenReturn(WriteOutcome.SUCCESS);

        mockMvc.perform(post("/api/v1/vertices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "id": "1001", "vertexSchemaName": "Account",
                                  "properties": [ { "index": 1, "value": "ACTIVE" } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
