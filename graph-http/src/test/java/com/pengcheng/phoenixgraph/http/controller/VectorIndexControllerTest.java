package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswIndexStats;
import com.pengcheng.phoenixgraph.http.service.GraphResolver;
import com.pengcheng.phoenixgraph.http.service.VectorIndexManager;
import com.pengcheng.phoenixgraph.http.service.VectorIndexRebuildResult;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.MetaPropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VectorIndexConfig;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC binding of the vector index endpoints: listing what a graph indexes
 * and rebuilding it, without touching a real storage backend.
 */
@WebMvcTest(VectorIndexController.class)
class VectorIndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VectorIndexManager vectorIndexes;

    @MockitoBean
    private GraphResolver graphs;

    @Test
    void listReportsTheIndexConfigurationAndState() throws Exception {
        GraphSchema schema = vectorSchema();
        when(graphs.schemas()).thenReturn(List.of(schema));
        HnswIndex index = org.mockito.Mockito.mock(HnswIndex.class);
        when(index.stats()).thenReturn(new HnswIndexStats(8_000, 13, 13,
                List.of(new HnswEntryPoint("42", 13)), 8_000, 3, 1_000));
        when(vectorIndexes.index("hnsw-demo", "Doc", "embedding")).thenReturn(index);

        mockMvc.perform(get("/api/v1/vector-index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].graph").value("hnsw-demo"))
                .andExpect(jsonPath("$[0].vertex").value("Doc"))
                .andExpect(jsonPath("$[0].property").value("embedding"))
                .andExpect(jsonPath("$[0].metric").value("COSINE"))
                .andExpect(jsonPath("$[0].efConstruction").value(64))
                .andExpect(jsonPath("$[0].efSearch").value(24))
                .andExpect(jsonPath("$[0].declaredLayers").value(2))
                .andExpect(jsonPath("$[0].size").value(8_000))
                .andExpect(jsonPath("$[0].topLevel").value(13))
                .andExpect(jsonPath("$[0].entryPoints[0]").value("42@13"));
    }

    @Test
    void rebuildPassesTheRequestThroughAndReturnsTheSummary() throws Exception {
        when(vectorIndexes.rebuild("hnsw-demo", "Doc", "embedding"))
                .thenReturn(List.of(new VectorIndexRebuildResult("hnsw-demo", "Doc", "embedding",
                        600, 11, 12_345, 678, 42.5)));

        mockMvc.perform(post("/api/v1/vector-index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "graph": "hnsw-demo", "vertexSchemaName": "Doc",
                                  "vectorProperty": "embedding" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].graph").value("hnsw-demo"))
                .andExpect(jsonPath("$[0].vertices").value(600))
                .andExpect(jsonPath("$[0].layers").value(11))
                .andExpect(jsonPath("$[0].links").value(12_345))
                .andExpect(jsonPath("$[0].pruned").value(678));

        verify(vectorIndexes).rebuild("hnsw-demo", "Doc", "embedding");
    }

    @Test
    void rebuildDefaultsToThePrimaryGraph() throws Exception {
        when(graphs.schemas()).thenReturn(List.of(vectorSchema()));
        when(vectorIndexes.rebuild(eq("hnsw-demo"), any(), any()))
                .thenReturn(List.of(new VectorIndexRebuildResult("hnsw-demo", "Doc", "embedding",
                        10, 2, 10, 0, 0.5)));

        mockMvc.perform(post("/api/v1/vector-index/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].graph").value("hnsw-demo"));

        verify(vectorIndexes).rebuild("hnsw-demo", null, null);
    }

    /** A graph with one vertex type declaring the {@code embedding} vector property. */
    private static GraphSchema vectorSchema() {
        GraphSchema schema = new GraphSchema(7, "hnsw-demo", null);
        schema.vertex(new VertexSchema(1, "Doc", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.STRING),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime())
                .property(new VertexPropertySchema(1, "title", null, PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "embedding", null,
                        new PropertyType.ListType(PropertyType.ScalarType.DOUBLE))
                        .vector(new VectorIndexConfig("COSINE", 8, 64, 24))));
        schema.edge(new com.pengcheng.phoenixgraph.schema.EdgeSchema(1, "embedding_0", null,
                new com.pengcheng.phoenixgraph.schema.EdgePropertySchema(0, "id", null,
                        PropertyType.ScalarType.STRING),
                new com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema(1, "source", null,
                        PropertyType.ScalarType.STRING, "Doc"),
                new com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema(2, "target", null,
                        PropertyType.ScalarType.STRING, "Doc"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                com.pengcheng.phoenixgraph.schema.EdgeDirection.BIDIRECTIONAL, 32, 32, 32));
        schema.edge(new com.pengcheng.phoenixgraph.schema.EdgeSchema(2, "embedding_1", null,
                new com.pengcheng.phoenixgraph.schema.EdgePropertySchema(0, "id", null,
                        PropertyType.ScalarType.STRING),
                new com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema(1, "source", null,
                        PropertyType.ScalarType.STRING, "Doc"),
                new com.pengcheng.phoenixgraph.schema.EdgeEndpointSchema(2, "target", null,
                        PropertyType.ScalarType.STRING, "Doc"),
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                com.pengcheng.phoenixgraph.schema.EdgeDirection.BIDIRECTIONAL, 16, 16, 16));
        return schema;
    }
}
