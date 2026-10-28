package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.agent.AgentQuery;
import com.pengcheng.phoenixgraph.agent.AgentToolCatalog;
import com.pengcheng.phoenixgraph.agent.Validity;
import com.pengcheng.phoenixgraph.http.service.AgentIngestService;
import com.pengcheng.phoenixgraph.http.service.AgentIngestService.IngestRequest;
import com.pengcheng.phoenixgraph.http.service.AgentIngestService.IngestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AI-facing API: one endpoint per tool, all answering in the same shape, plus ingestion and the
 * artifact they read.
 *
 * <pre>
 * GET  /api/v1/agent/tools                        the catalog (what to call, and with what)
 * POST /api/v1/agent/ingest                       documents -> graph + vectors + quality metrics
 * GET  /api/v1/agent/index/{graph}                the entity/relation/vector artifact
 * GET  /api/v1/agent/quality/{graph}              just the metrics of that artifact
 * POST /api/v1/agent/find-entities                which entities a question is about
 * POST /api/v1/agent/related                      what an entity is connected to
 * POST /api/v1/agent/similar                      what an entity is like (vector)
 * POST /api/v1/agent/related-similar              the lookalikes of a neighbour (graph then vector)
 * POST /api/v1/agent/similar-related              what a lookalike is connected to (vector then graph)
 * POST /api/v1/agent/path                         whether two entities are connected
 * POST /api/v1/agent/context                      the whole context of a question, with sources
 * </pre>
 *
 * <p>Every tool answers with the same document - {@code entities}, {@code relations}, {@code citations},
 * {@code coverage} and {@code next} - so an agent registers the catalog once and reads one shape. The
 * {@code coverage} block is the part a model should quote: it says how many entities and relations the
 * answer actually rests on.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentIngestService agent;

    public AgentController(AgentIngestService agent) {
        this.agent = agent;
    }

    /** The tool catalog, in the order an agent should try the tools. */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return AgentToolCatalog.describe();
    }

    /** Ingests documents: extraction, quality metrics, the graph (when asked) and the artifact. */
    @PostMapping("/ingest")
    public IngestResult ingest(@RequestBody IngestRequest request) {
        return agent.ingest(request);
    }

    /**
     * Accepts a merge suggestion: folds the {@code drop} entity into {@code keep}, rewrites its edges, and
     * records the name as an alias. This is the reviewer's answer to a {@code mergeCandidates} entry.
     */
    @PostMapping("/merge")
    public AgentIngestService.MergeResult merge(@RequestBody MergeRequest request) {
        return agent.merge(request.graph(), request.keep(), request.drop());
    }

    public record MergeRequest(String graph, String keep, String drop) {
    }

    /** The artifact of a graph: entities with their vectors, relations with their provenance. */
    @GetMapping("/index/{graph}")
    public Object index(@PathVariable("graph") String graph,
                        @RequestParam(value = "vectors", defaultValue = "false") boolean vectors) {
        Object index = agent.index(graph);
        if (vectors) {
            return index;
        }
        // the vectors are the bulk of the artifact: an agent reading the index usually wants the names,
        // the relations and the citations, not 768 floats per entity
        com.pengcheng.phoenixgraph.agent.AgentIndex read = agent.index(graph);
        return new LinkedHashMap<>(Map.of(
                "graph", read.graph(),
                "embedding", read.embedding(),
                "dimensions", read.dimensions(),
                "entities", read.entities().stream().map(entity -> Map.of(
                        "id", entity.id(), "type", entity.type(), "name", entity.name(),
                        "description", entity.description(), "cites", entity.cites(),
                        "aliases", entity.aliases())).toList(),
                "relations", read.relations(),
                "chunks", read.chunks(),
                "quality", read.quality()));
    }

    /** The quality metrics of an ingested graph (accuracy and coverage). */
    @GetMapping("/quality/{graph}")
    public Object quality(@PathVariable("graph") String graph) {
        return agent.index(graph).quality();
    }

    /** Which entities a text is about (name matches first, then the nearest vectors). */
    @PostMapping("/find-entities")
    public Map<String, Object> findEntities(@RequestBody ToolRequest request) {
        return tools(request).findEntities(request.question(), orDefault(request.k(), 5)).toJson();
    }

    /** Hybrid recall: name, description terms and vector in one ranking. */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody ToolRequest request) {
        return tools(request).search(request.question(), orDefault(request.k(), 5)).toJson();
    }

    /** What an entity is connected to, with the relation names and the sentences behind them. */
    @PostMapping("/related")
    public Map<String, Object> related(@RequestBody ToolRequest request) {
        return tools(request).related(request.entity(), request.relation(), orDefault(request.hops(), 1)).toJson();
    }

    /** The entities nearest to this one in vector space. */
    @PostMapping("/similar")
    public Map<String, Object> similar(@RequestBody ToolRequest request) {
        return tools(request).similar(request.entity(), orDefault(request.k(), 5)).toJson();
    }

    /** The lookalikes of everything around an entity (graph first, then vector). */
    @PostMapping("/related-similar")
    public Map<String, Object> relatedSimilar(@RequestBody ToolRequest request) {
        return tools(request).relatedSimilar(request.entity(), orDefault(request.k(), 3)).toJson();
    }

    /** What the lookalikes of an entity are connected to (vector first, then graph). */
    @PostMapping("/similar-related")
    public Map<String, Object> similarRelated(@RequestBody ToolRequest request) {
        return tools(request).similarRelated(request.entity(), orDefault(request.k(), 3)).toJson();
    }

    /** Whether two entities are connected, and by which walks. */
    @PostMapping("/path")
    public Map<String, Object> path(@RequestBody ToolRequest request) {
        return tools(request).pathBetween(request.from(), request.to(), orDefault(request.maxHops(), 3)).toJson();
    }

    /** The whole context of a question: entities, what sits around them, and the source sentences. */
    @PostMapping("/context")
    public Map<String, Object> context(@RequestBody ToolRequest request) {
        return tools(request).context(request.question(), orDefault(request.k(), 5), orDefault(request.hops(), 1))
                .toJson();
    }

    /**
     * One request shape for every tool: a tool reads the fields it needs, so an agent that passes the whole
     * question object does not have to know which tool will answer it.
     */
    public record ToolRequest(String graph, String question, String entity, String relation, String from,
                              String to, Integer k, Integer hops, Integer maxHops, String asOf) {
    }

    private AgentQuery tools(ToolRequest request) {
        if (request.graph() == null || request.graph().isBlank()) {
            throw new IllegalArgumentException("A tool call needs the graph it is about");
        }
        AgentQuery tools = agent.tools(request.graph());
        // A tool call may ask what was true at an instant: every tool then answers from the facts whose
        // validity window contains it (a September incident stops explaining an October fault).
        tools.asOf(Validity.parseMillis(request.asOf()));
        for (String required : requiredFields(request)) {
            throw new IllegalArgumentException(required);
        }
        return tools;
    }

    /** The fields a tool needs, checked before the query so the error says which one is missing. */
    private static java.util.List<String> requiredFields(ToolRequest request) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (request.entity() == null && request.question() == null && request.from() == null) {
            problems.add("A tool call needs 'question' (find-entities, context), 'entity' (related, similar, "
                    + "related-similar, similar-related) or 'from' and 'to' (path)");
        }
        if (request.from() != null && request.to() == null) {
            problems.add("path needs both 'from' and 'to'");
        }
        return problems;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
