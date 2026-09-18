package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyContexts;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyExpression;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyFunctions;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyKey;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLink;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPattern;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.http.dto.FuzzyPatternRequest;
import com.pengcheng.phoenixgraph.http.dto.FuzzyPreviewRequest;
import com.pengcheng.phoenixgraph.http.service.FuzzyLinkingService;
import com.pengcheng.phoenixgraph.http.service.FuzzyPatternStore;
import com.pengcheng.phoenixgraph.http.service.GraphRegistry;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.http.service.GraphBinding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuzzy linking: the patterns, what they would produce for a context, and the links themselves.
 *
 * <p>The endpoints are what the pattern editor needs, and they are also the answer to "why did this
 * account group with that one": {@code POST /validate} runs a pattern against a context and shows
 * what each part contributed, and {@code POST /{graph}/links} links a vertex and reports the keys.
 */
@RestController
@RequestMapping("/api/v1/fuzzy")
public class FuzzyController {

    private final FuzzyPatternStore patterns;
    private final FuzzyLinkingService linking;
    private final GraphRegistry graphs;

    public FuzzyController(FuzzyPatternStore patterns, FuzzyLinkingService linking, GraphRegistry graphs) {
        this.patterns = patterns;
        this.linking = linking;
        this.graphs = graphs;
    }

    /** The patterns of a graph (disabled ones included, so the editor can switch them back on). */
    @GetMapping("/patterns")
    public Map<String, Object> patterns(@RequestParam("graph") String graph) {
        graphs.require(graph);
        List<Map<String, Object>> views = new ArrayList<>();
        for (FuzzyPattern pattern : patterns.patterns(graph)) {
            views.add(view(pattern));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("file", patterns.file().toString());
        out.put("patterns", views);
        return out;
    }

    /** The functions a pattern can be built from (the editor's help). */
    @GetMapping("/functions")
    public List<Map<String, Object>> functions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FuzzyFunctions.Signature signature : FuzzyFunctions.signatures()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", signature.name());
            view.put("usage", signature.usage());
            view.put("description", signature.description());
            out.add(view);
        }
        return out;
    }

    /**
     * Validates an expression, optionally against a context, and says what it produced.
     *
     * <p>A pattern that cannot be parsed answers with {@code valid: false} and the message - that is
     * a normal answer, not an error: the editor shows it next to the field.
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody FuzzyPreviewRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        FuzzyExpression expression;
        try {
            expression = FuzzyExpression.parse(request.expression());
        } catch (FuzzyPatternException e) {
            out.put("valid", false);
            out.put("errors", List.of(e.getMessage()));
            return out;
        }
        out.put("valid", true);
        out.put("errors", List.of());
        out.put("partCount", expression.partCount());
        out.put("fields", expression.fields());
        FuzzyContext context = FuzzyContexts.fromJson(request.context());
        if (request.patternId() != null && !request.context().isEmpty()) {
            FuzzyPattern pattern = new FuzzyPattern(request.patternId(), request.name(), request.expression(),
                    request.description(), true);
            out.put("key", pattern.key(context));
            out.put("linkVertexId", pattern.linkVertexId(context));
        } else if (!request.context().isEmpty()) {
            String key = expression.key(context);
            out.put("key", key == null ? null : FuzzyKey.of(key));
        }
        out.put("steps", expression.explain(context).stream().map(step -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("index", step.index());
            view.put("part", step.part());
            view.put("value", step.value());
            return view;
        }).toList());
        return out;
    }

    /**
     * The example query of a graph: a pattern that carries an example context, the context itself
     * and the query that walks from it.
     *
     * <p>What a "run the example" button needs, and why a pattern carries its example: a pattern an
     * analyst writes comes with a context that shows it working, instead of a placeholder that
     * returns nothing.
     */
    @GetMapping("/demo")
    public Map<String, Object> demo(@RequestParam("graph") String graph) {
        graphs.require(graph);
        for (FuzzyPattern pattern : patterns.patterns(graph)) {
            if (pattern.example().isEmpty() || !pattern.enabled()) {
                continue;
            }
            String context = FuzzyContexts.toJson(FuzzyContext.of(pattern.example()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("graph", graph);
            out.put("patternId", pattern.id());
            out.put("name", pattern.name());
            out.put("expression", pattern.expression());
            out.put("title", pattern.demoTitle());
            out.put("context", context);
            out.put("key", pattern.key(FuzzyContext.of(pattern.example())));
            out.put("linkVertexId", pattern.linkVertexId(FuzzyContext.of(pattern.example())));
            out.put("query", "g.V().fuzzyLink(" + pattern.id() + ", '" + context.replace("'", "\'")
                    + "')" + pattern.demoStepsOrDefault());
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("patternId", null);
        out.put("query", null);
        out.put("title", "");
        out.put("hint", "No pattern of this graph carries an example context, so there is nothing to run. "
                + "Add one in the builder page: the example is what the button uses.");
        return out;
    }

    /** Adds or replaces a pattern of a graph. */
    @PostMapping("/patterns")
    public Map<String, Object> save(@RequestBody FuzzyPatternRequest request) {
        graphs.require(request.graph());
        FuzzyPattern pattern = new FuzzyPattern(request.id(), request.name(), request.expression(),
                request.description(), request.enabled() == null || request.enabled());
        patterns.save(request.graph(), pattern);
        Map<String, Object> out = new LinkedHashMap<>(view(pattern));
        out.put("graph", request.graph());
        out.put("patterns", patterns.patterns(request.graph()).size());
        return out;
    }

    /** Removes a pattern. */
    @DeleteMapping("/patterns/{graph}/{id}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable("graph") String graph,
                                                      @PathVariable("id") int id) {
        graphs.require(graph);
        List<FuzzyPattern> left = patterns.remove(graph, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("removed", id);
        out.put("patterns", left.size());
        return ResponseEntity.ok(out);
    }

    /**
     * Links a vertex: writes the link nodes its context produces and the edges to them.
     *
     * <p>The context comes from the request, filled in with the vertex's own properties when it has
     * any - so a client can link an account by sending just its id.
     */
    @PostMapping("/graphs/{graph}/links")
    public Map<String, Object> link(@PathVariable("graph") String graph,
                                    @RequestBody FuzzyPreviewRequest request) {
        graphs.require(graph);
        if (request.vertexId() == null) {
            throw new FuzzyPatternException("Linking needs the id of the vertex to link");
        }
        FuzzyContext context = contextOf(graph, request);
        List<FuzzyLink> links = linking.link(graph, request.vertexId(), context);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("vertexId", request.vertexId());
        out.put("links", links.stream().map(FuzzyController::view).toList());
        return out;
    }

    /** What the patterns would produce for a context, without writing anything. */
    @PostMapping("/graphs/{graph}/plan")
    public Map<String, Object> plan(@PathVariable("graph") String graph,
                                    @RequestBody FuzzyPreviewRequest request) {
        graphs.require(graph);
        List<FuzzyLink> links = linking.plan(graph, contextOf(graph, request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graph", graph);
        out.put("links", links.stream().map(FuzzyController::view).toList());
        return out;
    }

    /** The pattern as the editor reads it, with the fields it needs. */
    private static Map<String, Object> view(FuzzyPattern pattern) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", pattern.id());
        view.put("name", pattern.name());
        view.put("expression", pattern.expression());
        view.put("description", pattern.description());
        view.put("enabled", pattern.enabled());
        view.put("fields", pattern.parsed().fields());
        view.put("vertexId", pattern.vertexId());
        view.put("example", pattern.example());
        view.put("demoTitle", pattern.demoTitle());
        view.put("demoSteps", pattern.demoStepsOrDefault());
        return view;
    }

    /** The link as a client reads it. */
    private static Map<String, Object> view(FuzzyLink link) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("patternId", link.patternId());
        view.put("pattern", link.patternText());
        view.put("key", link.key());
        view.put("fragments", link.fragments());
        view.put("linkVertexId", link.linkVertexId());
        view.put("complete", link.complete());
        return view;
    }

    /**
     * The context of a request: what the client sent, and the properties of the vertex when one is
     * named (so a pattern can be run against an account that is already in the graph).
     */
    private FuzzyContext contextOf(String graph, FuzzyPreviewRequest request) {
        FuzzyContext context = FuzzyContexts.fromJson(request.context());
        if (request.vertexId() != null && request.vertexName() != null) {
            context = context.with(propertiesOf(graph, request.vertexName(), request.vertexId()));
        }
        return context;
    }

    /** Every property of a vertex, as the context a pattern reads. */
    private Map<String, Object> propertiesOf(String graph, String vertexName, Object vertexId) {
        GraphBinding binding = graphs.require(graph);
        if (binding.schema().vertexSchema(vertexName) == null) {
            throw new FuzzyPatternException("Unknown vertex type '" + vertexName + "' in graph '" + graph + "'");
        }
        VertexQueryResult vertex = binding.graphQuery().readVertex(vertexId, vertexName, List.of());
        if (vertex == null) {
            throw new FuzzyPatternException("Vertex " + vertexId + " of type '" + vertexName
                    + "' does not exist in graph '" + graph + "', so there is nothing to link");
        }
        return new LinkedHashMap<>(vertex.properties());
    }
}
