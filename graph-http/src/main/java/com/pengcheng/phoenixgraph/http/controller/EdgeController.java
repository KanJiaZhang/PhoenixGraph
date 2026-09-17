package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.http.service.GraphService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for edge writes.
 */
@RestController
@RequestMapping("/api/v1/edges")
public class EdgeController {

    private final GraphService service;

    public EdgeController(GraphService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WriteResult> upsert(@RequestBody EdgeRequest request) {
        return WriteResponses.of(request.edgeId(), service.upsertEdge(request));
    }

    @DeleteMapping("/{edgeId}")
    public ResponseEntity<WriteResult> delete(@PathVariable("edgeId") Object edgeId,
                                              @RequestParam String edgeSchemaName,
                                              @RequestParam Object source,
                                              @RequestParam Object destination) {
        return WriteResponses.of(edgeId, service.deleteEdge(edgeId, edgeSchemaName, source, destination));
    }
}
