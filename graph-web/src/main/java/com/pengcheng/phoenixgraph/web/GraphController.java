package com.pengcheng.phoenixgraph.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for PhoenixGraph.
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    @GetMapping("/health")
    public String health() {
        return "PhoenixGraph is running";
    }
}
