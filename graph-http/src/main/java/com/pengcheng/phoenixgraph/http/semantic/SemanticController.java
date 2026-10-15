package com.pengcheng.phoenixgraph.http.semantic;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic layer over HTTP: what the model holds, what SQL a question becomes, and whether it is sound.
 *
 * <p>Three endpoints, and the split matters. {@code /model} is the contract an agent (or a human) reads before
 * asking anything: metrics, dimensions, the fact-graph binding, and the reference queries. {@code /translate}
 * turns a semantic query into warehouse SQL <em>without running it</em>, which is what makes the plan
 * reviewable. {@code /validate} answers "is this request answerable at all" - the refusal path that keeps a
 * wrong number from looking like a smaller answer.
 *
 * <p>The SQL is rendered by the same rules as {@code tools/semantic-sql.mjs} and compared against it
 * byte-for-byte by {@code verification/verify-semantic-api.mjs}.
 */
@RestController
@RequestMapping("/api/v1/semantic")
public class SemanticController {

    private final SemanticService semantics;

    public SemanticController(SemanticService semantics) {
        this.semantics = semantics;
    }

    /** One semantic query, as a client sends it. */
    public record SemanticRequest(String metric, String dimension, List<String> dimensions, String grain,
                                  String dialect, String expectedSql) {

        String dimensionId() {
            if (dimension != null && !dimension.isBlank()) {
                return dimension;
            }
            if (dimensions == null || dimensions.isEmpty()) {
                return null;
            }
            // the renderer groups by one dimension and the CLI does too: a second one is refused by name rather
            // than silently dropped, because dropping it changes the number
            if (dimensions.size() > 1) {
                throw new IllegalArgumentException("One dimension per semantic query for now; got "
                        + dimensions + ". docs/DATA-AGENT.md §6 says why nothing is dropped silently.");
            }
            return dimensions.get(0);
        }
    }

    /** The model of one graph: the vocabulary, the lineage, the bindings and the reference queries. */
    @GetMapping(value = "/{graph}/model", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> model(@PathVariable("graph") String graph) {
        return semantics.model(graph).describe();
    }

    /** The SQL a semantic query becomes, with its lineage. Does not execute anything. */
    @PostMapping(value = "/{graph}/translate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> translate(@PathVariable("graph") String graph,
                                         @RequestBody SemanticRequest request) {
        requireMetric(request);
        return SqlRenderer.describe(semantics.translate(graph, request.metric(), request.dimensionId(),
                request.grain(), request.dialect()));
    }

    /**
     * Whether the request is answerable, and what it would become.
     *
     * <p>The answer is a verdict, not an exception: {@code answerable=false} with a {@code problem} that names
     * the missing piece, plus the SQL when the model can still express it (a warehouse can run it even when this
     * deployment's graph cannot).
     */
    @PostMapping(value = "/{graph}/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> validate(@PathVariable("graph") String graph,
                                        @RequestBody SemanticRequest request) {
        Map<String, Object> answer = new LinkedHashMap<>();
        if (request.metric() == null || request.metric().isBlank()) {
            answer.put("answerable", false);
            answer.put("problem", "metric must not be blank");
            return answer;
        }
        try {
            SqlRenderer.Plan plan = semantics.translate(graph, request.metric(), request.dimensionId(),
                    request.grain(), request.dialect());
            answer.put("answerable", true);
            answer.put("plan", SqlRenderer.describe(plan));
            answer.put("warnings", plan.warnings());
            if (request.expectedSql() != null && !request.expectedSql().isBlank()) {
                answer.put("matchesExpected", normalise(request.expectedSql()).equals(normalise(plan.statement())));
            }
            return answer;
        } catch (RuntimeException refused) {
            // a refused request is information, not a failure: the caller learns which piece is missing
            answer.put("answerable", false);
            answer.put("problem", refused.getMessage());
            return answer;
        }
    }

    private static void requireMetric(SemanticRequest request) {
        if (request == null || request.metric() == null || request.metric().isBlank()) {
            throw new IllegalArgumentException("metric must not be blank: pass metric/revenue or revenue");
        }
    }

    /** The CLI's comparison rule: whitespace, header comments and a trailing semicolon are not the statement. */
    static String normalise(String sql) {
        return sql.replaceAll("--[^\\n]*\\n", "").replaceAll("\\s+", " ").trim()
                .replaceAll(";\\s*$", "").toLowerCase();
    }
}
