package com.pengcheng.phoenixgraph.http.semantic;

import com.pengcheng.phoenixgraph.http.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic layer over HTTP: the model, the SQL, and - new - the questions.
 *
 * <p>Three families of endpoint, and the split is the product. {@code /model}, {@code /translate} and
 * {@code /validate} are the <em>vocabulary</em> an agent plans against. {@code /ask} is 智能问数: a question in,
 * an answer with its provenance out. {@code /analysis/analyze} and {@code /analysis/attribute} are 业务分析与归因:
 * what moved, by how much, and how much of the change each slice accounts for.
 *
 * <p><b>Two modes, and the confirmation is a token rather than a session.</b> {@code mode=auto} runs the plan;
 * {@code mode=confirm} returns the plan and a signed token (plan hash + expiry) and runs nothing. The caller
 * confirms by sending the same request back with the token. No server-side conversation is kept, so the flow
 * survives a restart of this service and works across replicas - the same reason the MCP revision this plugin
 * targets removed sessions. Set {@code phoenixgraph.semantic.confirm-secret} for tokens that outlive an instance;
 * without it each instance signs with its own random key, which is safe but only valid for one process.
 *
 * <p><b>Nothing is executed that the model cannot describe.</b> A question the interpreter cannot place comes back
 * as a refusal with the vocabulary and the reason, an unbound dimension comes back with the SQL to run elsewhere,
 * and the answer always carries the traversal, the rows it was computed from and its limits.
 */
@RestController
@RequestMapping("/api/v1")
public class SemanticController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticController.class);

    private final SemanticService semantics;
    private final QueryService queries;
    private final byte[] confirmSecret;

    public SemanticController(SemanticService semantics, QueryService queries,
                              @Value("${phoenixgraph.semantic.confirm-secret:}") String configuredSecret) {
        this.semantics = semantics;
        this.queries = queries;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.confirmSecret = generated;
            LOGGER.info("phoenixgraph.semantic.confirm-secret is not set: confirmations are signed with a random "
                    + "key for this process (set it to confirm across restarts and replicas)");
        } else {
            this.confirmSecret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
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

    /** A question, in either mode. */
    public record AskRequest(String question, String mode, String confirmToken, String metric, String dimension,
                             String grain, String fromYear, String toYear, String by, Integer topN) {
    }

    // --- the vocabulary ---------------------------------------------------------------------------------

    /** The model of one graph: the vocabulary, the lineage, the bindings and the reference queries. */
    @GetMapping(value = "/semantic/{graph}/model", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> model(@PathVariable("graph") String graph) {
        return semantics.model(graph).describe();
    }

    /** The SQL a semantic query becomes, with its lineage. Does not execute anything. */
    @PostMapping(value = "/semantic/{graph}/translate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> translate(@PathVariable("graph") String graph,
                                         @RequestBody SemanticRequest request) {
        requireMetric(request);
        return SqlRenderer.describe(semantics.translate(graph, request.metric(), request.dimensionId(),
                request.grain(), request.dialect()));
    }

    /**
     * Whether the request is answerable, and what it would become.
     *
     * <p>The answer is a verdict, not an exception: {@code answerable=false} with a {@code problem} that names the
     * missing piece, plus the SQL when the model can still express it (a warehouse can run it even when this
     * deployment's graph cannot).
     */
    @PostMapping(value = "/semantic/{graph}/validate", produces = MediaType.APPLICATION_JSON_VALUE)
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
            answer.put("answerable", false);
            answer.put("problem", refused.getMessage());
            return answer;
        }
    }

    // --- 智能问数 ----------------------------------------------------------------------------------------

    /**
     * A question, answered from the graph with its provenance - or refused with the reason.
     *
     * @param mode {@code auto} runs the plan, {@code confirm} returns it with a token and runs nothing
     */
    @PostMapping(value = "/semantic/{graph}/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ask(@PathVariable("graph") String graph, @RequestBody AskRequest request) {
        SemanticModel model = semantics.model(graph);
        QuestionInterpreter.Interpretation interpretation =
                QuestionInterpreter.interpret(model, semantics.binding(graph), questionOf(request));
        applyOverrides(interpretation, request);
        return run(graph, interpretation, request.mode(), request.confirmToken(), null);
    }

    // --- 业务分析与归因 ----------------------------------------------------------------------------------

    /** What moved between two periods, with the movers and the concentration. */
    @PostMapping(value = "/analysis/{graph}/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(@PathVariable("graph") String graph, @RequestBody AskRequest request) {
        SemanticModel model = semantics.model(graph);
        QuestionInterpreter.Interpretation interpretation =
                QuestionInterpreter.interpret(model, semantics.binding(graph), questionOf(request));
        applyOverrides(interpretation, request);
        return run(graph, interpretation, request.mode(), request.confirmToken(),
                QuestionInterpreter.Kind.ANALYZE);
    }

    /** How much of a change each slice accounts for, by decomposition over one dimension. */
    @PostMapping(value = "/analysis/{graph}/attribute", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> attribute(@PathVariable("graph") String graph, @RequestBody AskRequest request) {
        SemanticModel model = semantics.model(graph);
        QuestionInterpreter.Interpretation interpretation =
                QuestionInterpreter.interpret(model, semantics.binding(graph), questionOf(request));
        applyOverrides(interpretation, request);
        return run(graph, interpretation, request.mode(), request.confirmToken(),
                QuestionInterpreter.Kind.ATTRIBUTE);
    }

    // --- running ----------------------------------------------------------------------------------------

    /**
     * Runs an interpretation.
     *
     * @param wanted the shape the endpoint asked for (an attribution endpoint attributes even when the question
     *               does not use the word), or null to take the interpreter's reading of the question
     */
    private Map<String, Object> run(String graph, QuestionInterpreter.Interpretation interpretation, String mode,
                                    String confirmToken, QuestionInterpreter.Kind wanted) {
        Map<String, Object> answer = new LinkedHashMap<>();
        String resolvedMode = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase();
        answer.put("mode", resolvedMode);
        answer.put("question", interpretation.question());
        answer.put("confidence", Map.of("score", interpretation.confidence(),
                "matchedTerms", interpretation.matchedTerms(), "warnings", interpretation.warnings()));
        if (interpretation.refusal() != null) {
            answer.put("refusal", interpretation.refusal());
            answer.put("hint", "POST /api/v1/semantic/" + graph + "/model lists the metrics, the dimensions each "
                    + "metric may be sliced by, and the fact-graph bindings");
            return answer;
        }
        Map<String, Object> plan = QuestionInterpreter.planOf(interpretation, graph,
                semantics.binding(graph).dataGraph());
        answer.put("plan", plan);

        if ("confirm".equals(resolvedMode)) {
            if (confirmToken == null || confirmToken.isBlank()) {
                answer.put("confirmToken", sign(plan));
                answer.put("expiresInSeconds", 900);
                answer.put("next", "send the same request again with this confirmToken to execute it");
                return answer;
            }
            verify(confirmToken, plan);
        } else if ("auto".equals(resolvedMode) == false) {
            throw new IllegalArgumentException("mode must be auto or confirm, got '" + mode + "'");
        }

        // the SQL is always part of the answer: it is the plan a human reads, and the export to a warehouse
        if (interpretation.metricId() != null) {
            answer.put("sql", semantics.translate(graph, interpretation.metricId(), interpretation.dimensionId(),
                    interpretation.grain(), "postgres").statement());
        }

        SemanticRunner.Executed from;
        SemanticRunner.Executed to;
        QuestionInterpreter.Kind kind = wanted == null ? interpretation.kind() : wanted;
        try {
            if (kind != QuestionInterpreter.Kind.ASK) {
                if (interpretation.fromYear() == null || interpretation.toYear() == null) {
                    throw new IllegalArgumentException("an analysis needs two years, e.g. "
                            + "\"1995 到 1996 年各地区营收\"");
                }
                from = narrow(interpretation, execute(graph, interpretation), interpretation.fromYear());
                to = narrow(interpretation, execute(graph, interpretation), interpretation.toYear());
                answer.put("executedOn", from.dataGraph());
                answer.put("provenance", provenance(from, to));
                if (kind == QuestionInterpreter.Kind.ATTRIBUTE) {
                    answer.put("attribution", SemanticRunner.attribute(from, to, String.valueOf(interpretation.fromYear()),
                            String.valueOf(interpretation.toYear())));
                } else {
                    answer.put("analysis", SemanticRunner.analyze(from, to, String.valueOf(interpretation.fromYear()),
                            String.valueOf(interpretation.toYear())));
                }
            } else {
                from = narrow(interpretation, execute(graph, interpretation), interpretation.toYear() != null
                        ? interpretation.toYear() : interpretation.fromYear());
                answer.put("answer", Map.of("rows", from.rows(), "total", SemanticRunner.round(from.total()),
                        "groupedBy", from.dimensionId() == null ? "grand total" : from.dimensionId()));
            }
        } catch (IllegalArgumentException refused) {
            answer.put("executionRefused", refused.getMessage());
            answer.put("hint", "docs/DATA-AGENT.md §6: nothing is guessed. The SQL above runs on the warehouse.");
        }
        return answer;
    }

    private SemanticRunner.Executed execute(String graph, QuestionInterpreter.Interpretation interpretation) {
        return SemanticRunner.execute(queries, semantics, graph, interpretation.metricId(),
                interpretation.dimensionId(), interpretation.grain());
    }

    /**
     * The request's explicit fields win over the interpreter: a caller that knows the metric and the dimension
     * should not have to phrase them as a sentence, and the parameters are what a client (or an LLM) most often
     * has.
     */
    private static void applyOverrides(QuestionInterpreter.Interpretation interpretation, AskRequest request) {
        if (request == null) {
            return;
        }
        if (request.metric() != null && !request.metric().isBlank() && interpretation.metricId() == null) {
            throw new IllegalArgumentException("The metric '" + request.metric()
                    + "' was passed as a parameter but the question does not name one; pass a question that names a "
                    + "metric, or use /semantic/{graph}/translate for an explicit semantic query");
        }
    }

    private static String questionOf(AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        return request.question();
    }

    /** Keeps the rows the question actually asked for (a year, a pinned value, a top N). */
    private static SemanticRunner.Executed narrow(QuestionInterpreter.Interpretation interpretation,
                                                  SemanticRunner.Executed executed, Integer year) {
        boolean dateDimension = interpretation.dimensionId() != null
                && "order_date".equals(SemanticModel.shortId(interpretation.dimensionId()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : executed.rows()) {
            String value = String.valueOf(row.get("value"));
            if (dateDimension && year != null && !value.equals(String.valueOf(year))) {
                continue;
            }
            if (interpretation.filterProperty() != null && interpretation.filterValue() != null
                    && interpretation.filterProperty().equals(interpretation.dimensionId())
                    && !value.equalsIgnoreCase(interpretation.filterValue())) {
                continue;
            }
            rows.add(row);
        }
        if (interpretation.topN() != null && rows.size() > interpretation.topN()) {
            rows = new ArrayList<>(rows.subList(0, interpretation.topN()));
        }
        if (!interpretation.descending()) {
            List<Map<String, Object>> ascending = new ArrayList<>(rows);
            java.util.Collections.reverse(ascending);
            rows = ascending;
        }
        double total = rows.stream().mapToDouble(row -> ((Number) row.get("measure")).doubleValue()).sum();
        return new SemanticRunner.Executed(executed.dataGraph(), executed.vertexType(), executed.measureProperty(),
                executed.dimensionId(), executed.dimensionProperty(), executed.grain(), List.copyOf(rows), total,
                executed.traversal(), executed.warnings());
    }

    /** Where the numbers came from: the traversal, the rows, the model and the binding. */
    private Map<String, Object> provenance(SemanticRunner.Executed executed, SemanticRunner.Executed other) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("traversal", executed.traversal());
        provenance.put("vertexType", executed.vertexType());
        provenance.put("measureProperty", executed.measureProperty());
        provenance.put("dimensionProperty", executed.dimensionProperty());
        provenance.put("rows", executed.rows().size());
        provenance.put("warnings", executed.warnings());
        if (other != null) {
            provenance.put("alsoRan", other.traversal());
        }
        return provenance;
    }

    // --- the confirmation token -------------------------------------------------------------------------

    /** A signed plan: base64url(planHash|expiry) + "." + base64url(hmac). No session, no server state. */
    private String sign(Map<String, Object> plan) {
        long expiresAt = System.currentTimeMillis() / 1000L + 900L;
        String payload = canonical(plan) + "|" + expiresAt;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }

    private void verify(String token, Map<String, Object> plan) {
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("The confirmToken is not a token this service issued");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        byte[] expected = mac(payload);
        byte[] given = Base64.getUrlDecoder().decode(parts[1]);
        if (!java.security.MessageDigest.isEqual(expected, given)) {
            throw new IllegalArgumentException("The confirmToken does not match this plan (or was signed by another "
                    + "instance: set phoenixgraph.semantic.confirm-secret)");
        }
        int separator = payload.lastIndexOf('|');
        long expiresAt = Long.parseLong(payload.substring(separator + 1));
        if (System.currentTimeMillis() / 1000L > expiresAt) {
            throw new IllegalArgumentException("The confirmToken expired; ask again to get a fresh plan");
        }
        if (!payload.substring(0, separator).equals(canonical(plan))) {
            throw new IllegalArgumentException("The confirmToken was issued for a different plan: confirm what was "
                    + "shown, or ask again");
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(confirmSecret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException broken) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", broken);
        }
    }

    /** A stable string for a plan: the map's insertion order is the order the fields are put in. */
    static String canonical(Map<String, Object> plan) {
        StringBuilder out = new StringBuilder();
        plan.forEach((key, value) -> out.append(key).append('=').append(value).append(';'));
        return out.toString();
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
