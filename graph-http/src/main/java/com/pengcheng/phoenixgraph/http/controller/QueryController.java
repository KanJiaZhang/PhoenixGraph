package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.QueryRequest;
import com.pengcheng.phoenixgraph.http.service.QueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for Gremlin traversal queries.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final QueryService service;

    public QueryController(QueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public Object query(@RequestBody QueryRequest request) {
        if (request.returnSubgraph()) {
            return service.querySubgraph(request.graphName(), request.query(), request.params());
        }
        return service.query(request.graphName(), request.query(), request.params());
    }
}
