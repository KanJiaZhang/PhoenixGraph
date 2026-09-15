package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.dto.GraphInfo;
import com.pengcheng.phoenixgraph.schema.GraphSchemaManagers;
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

    /** Lists every registered graph schema with its vertex and edge type names. */
    @GetMapping("/graphs")
    public List<GraphInfo> graphs() {
        return GraphSchemaManagers.manager().all().stream().map(GraphInfo::of).toList();
    }
}
