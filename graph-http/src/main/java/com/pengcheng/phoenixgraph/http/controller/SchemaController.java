package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.GraphInfo;
import com.pengcheng.phoenixgraph.http.service.QueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints describing the available graph schemas.
 */
@RestController
@RequestMapping("/api/v1")
public class SchemaController {

    private final QueryService queryService;

    public SchemaController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** Lists every graph this instance serves with its vertex and edge type names. */
    @GetMapping("/graphs")
    public List<GraphInfo> graphs() {
        return queryService.graphs().stream().map(GraphInfo::of).toList();
    }
}
