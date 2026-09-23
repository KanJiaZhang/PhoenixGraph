package com.pengcheng.phoenixgraph.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Forwards the UI's {@code /api/graphs} and {@code /api/query} calls to the
 * graph-http backend, so the browser talks to a single origin (graph-web).
 */
@RestController
@RequestMapping("/api")
public class ProxyController {

    private final RestTemplate restTemplate;
    private final String httpUrl;

    public ProxyController(RestTemplate restTemplate,
                           @Value("${phoenixgraph.http-url:http://localhost:8081}") String httpUrl) {
        this.restTemplate = restTemplate;
        this.httpUrl = httpUrl.endsWith("/") ? httpUrl.substring(0, httpUrl.length() - 1) : httpUrl;
    }

    @GetMapping("/graphs")
    public ResponseEntity<String> graphs() {
        return forward(HttpMethod.GET, "/api/v1/graphs", null);
    }

    /** The system metrics of one graph (see {@code MetricsController}). */
    @GetMapping("/metrics/{graph}")
    public ResponseEntity<String> metrics(@PathVariable("graph") String graph) {
        return forward(HttpMethod.GET, "/api/v1/metrics/" + graph, null);
    }

    /** Writes a graph's metrics through to storage on demand. */
    @PostMapping("/metrics/{graph}/flush")
    public ResponseEntity<String> flushMetrics(@PathVariable("graph") String graph) {
        return forward(HttpMethod.POST, "/api/v1/metrics/" + graph + "/flush", new HttpEntity<>("{}", jsonHeaders()));
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // --- the graph builder: schemas are read, validated, saved and then filled ---

    /** A graph schema as the editor reads it (the file's JSON). */
    @GetMapping("/schema/{graph}")
    public ResponseEntity<String> schemaJson(@PathVariable("graph") String graph) {
        return forward(HttpMethod.GET, "/api/v1/schema/" + graph, null);
    }

    /** An existing graph described the way the builder edits it. */
    @GetMapping("/schema/{graph}/draft")
    public ResponseEntity<String> schemaDraft(@PathVariable("graph") String graph) {
        return forward(HttpMethod.GET, "/api/v1/schema/" + graph + "/draft", null);
    }

    /** The storage types a new graph can declare. */
    @GetMapping("/schema-storage-types")
    public ResponseEntity<String> storageTypes() {
        return forward(HttpMethod.GET, "/api/v1/schema/storage-types", null);
    }

    /** Checks a draft without saving it. */
    @PostMapping("/schema/validate")
    public ResponseEntity<String> validateSchema(@RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/schema/validate", new HttpEntity<>(body, jsonHeaders()));
    }

    /** Saves a draft: validated, written to a file and made queryable at once. */
    @PostMapping("/schema")
    public ResponseEntity<String> saveSchema(@RequestBody String body,
                                             @RequestParam(value = "replace", defaultValue = "false")
                                             boolean replace) {
        return forward(HttpMethod.POST, "/api/v1/schema?replace=" + replace,
                new HttpEntity<>(body, jsonHeaders()));
    }

    /** Writes a vertex into a named graph (the builder fills the graph it just created). */
    @PostMapping("/graphs/{graph}/vertices")
    public ResponseEntity<String> upsertVertex(@PathVariable("graph") String graph, @RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/graphs/" + graph + "/vertices",
                new HttpEntity<>(body, jsonHeaders()));
    }

    /** Writes an edge into a named graph. */
    @PostMapping("/graphs/{graph}/edges")
    public ResponseEntity<String> upsertEdge(@PathVariable("graph") String graph, @RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/graphs/" + graph + "/edges",
                new HttpEntity<>(body, jsonHeaders()));
    }

    /**
     * Writes many vertices and edges into one graph in a single request: what a CSV import uses, so a file of
     * a few hundred rows is a handful of round trips rather than one per row.
     */
    @PostMapping("/graphs/{graph}/batch")
    public ResponseEntity<String> batch(@PathVariable("graph") String graph, @RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/graphs/" + graph + "/batch",
                new HttpEntity<>(body, jsonHeaders()));
    }

    /**
     * Deletes a vertex. The payload is the graph's own state, not this page's: the builder confirms the
     * consequences before calling this (the edges that touched the vertex are not deleted with it).
     */
    @DeleteMapping("/graphs/{graph}/vertices/{id}")
    public ResponseEntity<String> deleteVertex(@PathVariable("graph") String graph,
                                               @PathVariable("id") String id) {
        return forward(HttpMethod.DELETE, "/api/v1/graphs/" + graph + "/vertices/" + id, null);
    }

    /** Deletes one edge: an edge is stored at both endpoints, so both have to be named. */
    @DeleteMapping("/graphs/{graph}/edges/{edgeId}")
    public ResponseEntity<String> deleteEdge(@PathVariable("graph") String graph,
                                             @PathVariable("edgeId") String edgeId,
                                             @RequestParam("edgeSchemaName") String edgeSchemaName,
                                             @RequestParam("source") String source,
                                             @RequestParam("destination") String destination) {
        return forward(HttpMethod.DELETE, "/api/v1/graphs/" + graph + "/edges/" + edgeId
                + "?edgeSchemaName=" + edgeSchemaName + "&source=" + source + "&destination=" + destination,
                null);
    }

    // --- fuzzy linking (patterns are edited in the builder page) ---

    /** The fuzzy patterns of a graph. */
    @GetMapping("/fuzzy/patterns")
    public ResponseEntity<String> fuzzyPatterns(@RequestParam("graph") String graph) {
        return forward(HttpMethod.GET, "/api/v1/fuzzy/patterns?graph=" + graph, null);
    }

    /** The functions a fuzzy pattern is built from. */
    @GetMapping("/fuzzy/functions")
    public ResponseEntity<String> fuzzyFunctions() {
        return forward(HttpMethod.GET, "/api/v1/fuzzy/functions", null);
    }

    /** The example query of a graph's patterns, for the UI's "run an example" button. */
    @GetMapping("/fuzzy/demo")
    public ResponseEntity<String> fuzzyDemo(@RequestParam("graph") String graph) {
        return forward(HttpMethod.GET, "/api/v1/fuzzy/demo?graph=" + graph, null);
    }

    /** Validates a fuzzy expression and shows what it produced for a context. */
    @PostMapping("/fuzzy/validate")
    public ResponseEntity<String> fuzzyValidate(@RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/fuzzy/validate", new HttpEntity<>(body, jsonHeaders()));
    }

    /** Adds or replaces a fuzzy pattern. */
    @PostMapping("/fuzzy/patterns")
    public ResponseEntity<String> fuzzySavePattern(@RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/fuzzy/patterns", new HttpEntity<>(body, jsonHeaders()));
    }

    /** Removes a fuzzy pattern. */
    @DeleteMapping("/fuzzy/patterns/{graph}/{id}")
    public ResponseEntity<String> fuzzyRemovePattern(@PathVariable("graph") String graph,
                                                     @PathVariable("id") int id) {
        return forward(HttpMethod.DELETE, "/api/v1/fuzzy/patterns/" + graph + "/" + id, null);
    }

    /** What a graph's patterns would produce for a context. */
    @PostMapping("/fuzzy/graphs/{graph}/plan")
    public ResponseEntity<String> fuzzyPlan(@PathVariable("graph") String graph, @RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/fuzzy/graphs/" + graph + "/plan",
                new HttpEntity<>(body, jsonHeaders()));
    }

    /** Links a vertex: writes the link nodes its context produces and the edges to them. */
    @PostMapping("/fuzzy/graphs/{graph}/links")
    public ResponseEntity<String> fuzzyLink(@PathVariable("graph") String graph, @RequestBody String body) {
        return forward(HttpMethod.POST, "/api/v1/fuzzy/graphs/" + graph + "/links",
                new HttpEntity<>(body, jsonHeaders()));
    }

    @PostMapping("/query")
    public ResponseEntity<String> query(@RequestBody String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return forward(HttpMethod.POST, "/api/v1/query", new HttpEntity<>(body, headers));
    }

    private ResponseEntity<String> forward(HttpMethod method, String path, HttpEntity<String> entity) {
        try {
            return restTemplate.exchange(httpUrl + path, method, entity, String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"graph-http unreachable\"}");
        }
    }
}
