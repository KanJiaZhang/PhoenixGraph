package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.PropertyUpdateRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.http.service.GraphService;
import com.pengcheng.phoenixgraph.storage.aerospike.StoredVertex;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for vertex and vertex-property writes.
 */
@RestController
@RequestMapping("/api/v1/vertices")
public class VertexController {

    private final GraphService service;

    public VertexController(GraphService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WriteResult> upsert(@RequestBody VertexRequest request) {
        return WriteResponses.of(request.id(), service.upsertVertex(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoredVertex> get(@PathVariable("id") Object id) {
        StoredVertex vertex = service.getVertex(id);
        return vertex == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vertex);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<WriteResult> delete(@PathVariable("id") Object id) {
        return WriteResponses.of(id, service.deleteVertex(id));
    }

    @PutMapping("/{id}/properties/{index}")
    public ResponseEntity<WriteResult> updateProperty(@PathVariable("id") Object id,
                                                      @PathVariable("index") int index,
                                                      @RequestBody PropertyUpdateRequest request) {
        return WriteResponses.of(id, service.updateVertexProperty(id, index, request));
    }

    @DeleteMapping("/{id}/properties/{index}")
    public ResponseEntity<WriteResult> deleteProperty(@PathVariable("id") Object id,
                                                      @PathVariable("index") int index) {
        return WriteResponses.of(id, service.deleteVertexProperty(id, index));
    }
}
