package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.service.GraphAuthoringService;
import com.pengcheng.phoenixgraph.schema.SchemaValidationResult;
import com.pengcheng.phoenixgraph.http.dto.SchemaDraft;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authoring side of the graph API: the graph-building screen validates a schema,
 * saves it as {@code <name>.json} and makes it queryable without a restart.
 *
 * <pre>
 * GET  /api/v1/schema/{graph}            the stored JSON (also the editor's starting point)
 * GET  /api/v1/schema/{graph}/draft      the same graph as the editor's draft
 * POST /api/v1/schema/validate           check a schema, save nothing
 * POST /api/v1/schema                    save it (replace=true overwrites a served graph)
 * GET  /api/v1/schema/storage-types      what a new graph can be stored in
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/schema")
public class SchemaAuthoringController {

    private final GraphAuthoringService authoring;

    public SchemaAuthoringController(GraphAuthoringService authoring) {
        this.authoring = authoring;
    }

    /** The schema file of a graph, verbatim; the editor downloads this. */
    @GetMapping(value = "/{graph}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> json(@PathVariable("graph") String graph) {
        String json = authoring.storedJson(graph);
        return json == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(json);
    }

    /** An existing graph as the editor's draft (opens it in the builder). */
    @GetMapping("/{graph}/draft")
    public ResponseEntity<SchemaDraft> draft(@PathVariable("graph") String graph) {
        try {
            return ResponseEntity.ok(authoring.draftOf(graph));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Validates a schema without touching the disk or the service. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody SchemaDraft draft) {
        SchemaValidationResult result = authoring.validate(draft);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", result.isValid());
        out.put("errors", result.errors());
        out.put("warnings", result.warnings());
        return out;
    }

    /**
     * Saves a schema: validation, then {@code <name>.json} in the working directory, then
     * registration, then binding — a graph saved here answers queries immediately.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody SchemaDraft draft,
                                                    @RequestParam(value = "replace", defaultValue = "false")
                                                    boolean replace) {
        GraphAuthoringService.SaveResult result = authoring.save(draft, replace);
        if (!result.saved()) {
            return ResponseEntity.badRequest().body(result.toMap());
        }
        Map<String, Object> body = result.toMap();
        // what the freshly bound graph can do, so the UI can say so
        body.put("queryable", true);
        return ResponseEntity.ok(body);
    }

    /** The storage types a new graph can declare. */
    @GetMapping("/storage-types")
    public List<Map<String, Object>> storageTypes() {
        return authoring.storageTypes();
    }
}
