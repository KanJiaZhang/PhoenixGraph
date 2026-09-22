package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.QueryRequest;
import com.pengcheng.phoenixgraph.http.service.QueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for traversal queries (Gremlin, or openCypher with
 * {@code "language": "cypher"}). Both accept a query template plus parameters:
 * {@code } placeholders are substituted in the text, and the Cypher engine also
 * resolves native {@code $name} references.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final QueryService service;

    public QueryController(QueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public Object query(@RequestBody QueryRequest request, HttpServletResponse response) {
        QueryService.Answer answer = service.queryCapped(request.graphName(), request.query(),
                request.params(), request.languageOrDefault(), request.returnSubgraph(),
                request.includeResultOrDefault(), request.sourceOrDefault());
        if (answer.truncated()) {
            // the answer is capped so the response stays parseable: tell the client, which can say
            // "narrow the query" instead of silently showing a partial graph
            response.setHeader("X-PhoenixGraph-Truncated", String.valueOf(answer.limit()));
        }
        return answer.body();
    }
}
