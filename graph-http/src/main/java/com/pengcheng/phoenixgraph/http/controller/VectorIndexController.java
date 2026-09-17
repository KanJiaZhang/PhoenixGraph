package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.hnsw.HnswEntryPoint;
import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswIndexStats;
import com.pengcheng.phoenixgraph.http.dto.VectorIndexRebuildRequest;
import com.pengcheng.phoenixgraph.http.service.GraphResolver;
import com.pengcheng.phoenixgraph.http.service.VectorIndexManager;
import com.pengcheng.phoenixgraph.http.service.VectorIndexRebuildResult;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST endpoints for the vector indexes: what the served graphs index, and a
 * rebuild that clears the layer edge data and builds fresh edges from the vectors
 * stored in the graph.
 *
 * <p>{@code GET /api/v1/vector-index} lists every property flagged
 * {@code "isVector": true} together with its configuration and the state of its
 * index (size, layers, entry points). {@code POST /api/v1/vector-index/rebuild}
 * rebuilds one index - by default the primary graph's, but {@code graph},
 * {@code vertexSchemaName} and {@code vectorProperty} narrow or widen it.
 */
@RestController
@RequestMapping("/api/v1/vector-index")
public class VectorIndexController {

    private final VectorIndexManager vectorIndexes;
    private final GraphResolver graphs;

    public VectorIndexController(VectorIndexManager vectorIndexes, GraphResolver graphs) {
        this.vectorIndexes = vectorIndexes;
        this.graphs = graphs;
    }

    /** The vector indexes of every served graph, with their configuration and state. */
    @GetMapping
    public List<VectorIndexInfo> list() {
        List<VectorIndexInfo> infos = new ArrayList<>();
        for (GraphSchema schema : graphs.schemas()) {
            for (VectorIndexSchemas.VectorProperty property : VectorIndexSchemas.vectorProperties(schema)) {
                HnswIndex index = vectorIndexes.index(schema.name(), property.vertexName(), property.propertyName());
                infos.add(VectorIndexInfo.of(schema.name(), property, index));
            }
        }
        return infos;
    }

    /**
     * Rebuilds the vector index (or indexes) described by the request: the layer
     * edge data is cleared and the edges are built again from the stored vectors.
     */
    @PostMapping("/rebuild")
    public ResponseEntity<List<VectorIndexRebuildResult>> rebuild(@RequestBody(required = false)
                                                                 VectorIndexRebuildRequest request) {
        String graph = request == null ? null : request.graph();
        if (graph == null || graph.isBlank()) {
            List<GraphSchema> schemas = graphs.schemas();
            if (schemas.isEmpty()) {
                throw new IllegalArgumentException("No graph is configured");
            }
            graph = schemas.get(0).name();
        }
        List<VectorIndexRebuildResult> results = vectorIndexes.rebuild(graph,
                request == null ? null : request.vertexSchemaName(),
                request == null ? null : request.vectorProperty());
        return ResponseEntity.ok(results);
    }

    /**
     * Description of one vector index.
     *
     * @param graph          graph serving the index
     * @param vertex         vertex type of the indexed property
     * @param property       the vector property
     * @param metric         distance metric the graph is ordered by
     * @param m              neighbours kept per layer
     * @param efConstruction candidate list size used while building
     * @param efSearch       default candidate list size of a query
     * @param declaredLayers layer edge classes the schema declares
     * @param size           vertices the index knows about
     * @param topLevel       highest layer in use
     * @param entryPoints    search entry points (vertex id + layer), highest first
     */
    public record VectorIndexInfo(String graph, String vertex, String property, String metric, int m,
                                  int efConstruction, int efSearch, int declaredLayers, long size,
                                  int topLevel, List<String> entryPoints) {

        static VectorIndexInfo of(String graph, VectorIndexSchemas.VectorProperty property, HnswIndex index) {
            HnswIndexStats stats = index == null ? null : index.stats();
            return new VectorIndexInfo(graph, property.vertexName(), property.propertyName(),
                    property.config().metric(), property.config().m(), property.config().efConstruction(),
                    property.config().efSearch(), property.declaredLevels(),
                    stats == null ? 0 : stats.size(), stats == null ? -1 : stats.topLevel(),
                    stats == null ? List.of() : stats.entryPoints().stream()
                            .map(VectorIndexInfo::describe).toList());
        }

        private static String describe(HnswEntryPoint point) {
            return point.vertexId() + "@" + point.level();
        }
    }
}
