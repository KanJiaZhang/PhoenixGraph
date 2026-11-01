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

    /**
     * Deletes a vertex by taking its id in the body.
     *
     * <p>The path form cannot carry an id such as {@code metric/revenue}: the slash is encoded as %2F and the
     * servlet container rejects it before the handler runs. A data-agent model names its nodes that way, so the
     * delete has to put the id somewhere a slash is allowed.
     */
    @PostMapping("/vertices/delete")
    public ResponseEntity<WriteResult> deleteVertexByBody(@PathVariable("graph") String graph,
                                                          @RequestBody DeleteVertexRequest request) {
        return WriteResponses.of(request.id(), service.forGraph(graphs, graph).deleteVertex(request.id()));
    }

    /** Deletes an edge by taking its id and endpoints in the body (same reason as the vertex form). */
    @PostMapping("/edges/delete")
    public ResponseEntity<WriteResult> deleteEdgeByBody(@PathVariable("graph") String graph,
                                                        @RequestBody EdgeRequest request) {
        return WriteResponses.of(request.edgeId(), service.forGraph(graphs, graph)
                .deleteEdge(request.edgeId(), request.edgeSchemaName(), request.source(), request.destination()));
    }

    /** An id-only delete body. */
    public record DeleteVertexRequest(Object id) {
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
