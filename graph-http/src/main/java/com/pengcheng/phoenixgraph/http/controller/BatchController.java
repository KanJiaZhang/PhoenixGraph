package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.http.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoint for batch writes (inline or from a URL/file).
 */
@RestController
@RequestMapping("/api/v1")
public class BatchController {

    private final GraphService service;

    public BatchController(GraphService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    public List<WriteResult> batch(@RequestBody BatchRequest request) {
        return service.processBatch(request);
    }

    @GetMapping("/health")
    public String health() {
        return "PhoenixGraph HTTP is running";
    }
}
