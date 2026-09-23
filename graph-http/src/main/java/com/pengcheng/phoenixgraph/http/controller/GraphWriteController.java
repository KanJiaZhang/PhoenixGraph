package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.http.service.GraphRegistry;
import com.pengcheng.phoenixgraph.http.service.GraphService;
import org.springframework.http.ResponseEntity;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Writes into a named graph.
 *
 * <p>The original endpoints write into the primary graph; this one takes the graph name
 * from the path, which is what the graph builder needs: a schema created in the UI can be
 * filled with vertices and edges, and those writes land in that graph's own storage (for
 * Aerospike graphs, in the graph's vertex set).
 */
@RestController
@RequestMapping("/api/v1/graphs/{graph}")
public class GraphWriteController {

    private final GraphService service;
    private final GraphRegistry graphs;

    public GraphWriteController(GraphService service, GraphRegistry graphs) {
        this.service = service;
        this.graphs = graphs;
    }

    @PostMapping("/vertices")
    public ResponseEntity<WriteResult> upsertVertex(@PathVariable("graph") String graph,
                                                    @RequestBody VertexRequest request) {
        return WriteResponses.of(request.id(), service.forGraph(graphs, graph).upsertVertex(request));
    }

    @DeleteMapping("/vertices/{id}")
    public ResponseEntity<WriteResult> deleteVertex(@PathVariable("graph") String graph,
                                                    @PathVariable("id") Object id) {
        return WriteResponses.of(id, service.forGraph(graphs, graph).deleteVertex(id));
    }

    @PostMapping("/edges")
    public ResponseEntity<WriteResult> upsertEdge(@PathVariable("graph") String graph,
                                                  @RequestBody EdgeRequest request) {
        return WriteResponses.of(request.edgeId(), service.forGraph(graphs, graph).upsertEdge(request));
    }

    /**
     * Many writes in one request, into <b>this</b> graph.
     *
     * <p>{@code /api/v1/batch} writes into the primary graph, which is what a bulk loader pointed at one
     * graph wants; the builder fills a graph it just created, so it needs the batch to name the graph. It is
     * the same call either way - one storage round trip per chunk instead of one per row - which is what
     * makes importing a CSV file a handful of requests.
     */
    @PostMapping("/batch")
    public List<WriteResult> batch(@PathVariable("graph") String graph, @RequestBody BatchRequest request) {
        return service.forGraph(graphs, graph).processBatch(request);
    }

    @DeleteMapping("/edges/{edgeId}")
    public ResponseEntity<WriteResult> deleteEdge(@PathVariable("graph") String graph,
                                                  @PathVariable("edgeId") Object edgeId,
                                                  @RequestParam String edgeSchemaName,
                                                  @RequestParam Object source,
                                                  @RequestParam Object destination) {
        return WriteResponses.of(edgeId, service.forGraph(graphs, graph)
                .deleteEdge(edgeId, edgeSchemaName, source, destination));
    }
}
