package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPattern;
import com.pengcheng.phoenixgraph.http.service.FuzzyLinkingService;
import com.pengcheng.phoenixgraph.http.service.FuzzyPatternStore;
import com.pengcheng.phoenixgraph.http.service.GraphRegistry;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.storage.VertexQueryResult;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fuzzy endpoints: the patterns an analyst edits, what an expression produces for a context, and
 * what the editor is told when an expression is wrong.
 *
 * <p>The patterns are file-backed, so a test writes its own file; the graph is read-only here (the
 * linking itself runs against a real store in the integration tests), which is why the store of this
 * test's binding is a small in-memory query.
 */
class FuzzyControllerTest {

    private static final String GRAPH = "payment-graph";

    @TempDir
    Path dir;
    private MockMvc api;
    private FuzzyPatternStore patterns;

    @BeforeEach
    void setUp() throws Exception {
        GraphSchema schema = new SchemaJsonCodec().fromJson(Files.readString(
                Path.of("src", "main", "resources", "payment-graph.json"), StandardCharsets.UTF_8));
        GraphQuery query = new GraphQuery() {
            @Override
            public VertexQueryResult readVertex(Object vertexId, String vertexName, List<String> propertyNames) {
                if (!"fz_a1".equals(String.valueOf(vertexId))) {
                    return null;
                }
                return new VertexQueryResult("Account", vertexId, Map.of(
                        "username", "zhang1988", "email", "19880101@mail.ru", "phoneNumber", "+8613800138001",
                        "cardNumber", "6217000010001111", "ipAddress", "10.20.31.7"), 1L, 2L);
            }

            @Override
            public List<EdgeQueryResult> readEdges(Object vertexId, String vertexName, String edgeName,
                                                   EdgeSide direction, List<String> propertyNames) {
                return List.of();
            }
        };
        GraphRegistry graphs = new GraphRegistry(List.of(
                com.pengcheng.phoenixgraph.http.service.GraphBinding.of(schema, query)));
        patterns = new FuzzyPatternStore(dir.resolve("fuzzy-patterns.json").toString());
        FuzzyLinkingService linking = new FuzzyLinkingService(null, graphs, patterns);
        api = MockMvcBuilders.standaloneSetup(new FuzzyController(patterns, linking, graphs))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void forget() {
        patterns.reload(GRAPH);
    }

    @Test
    void aGraphWithoutPatternsSaysSo() throws Exception {
        api.perform(get("/api/v1/fuzzy/patterns").param("graph", GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph").value(GRAPH))
                .andExpect(jsonPath("$.patterns.length()").value(0));
    }

    @Test
    void aPatternIsCreatedListedAndRemoved() throws Exception {
        api.perform(post("/api/v1/fuzzy/patterns").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"graph":"payment-graph","id":0,"name":"shared habits",
                                 "expression":"emailDomain(email)+'_'+ip2(ip)",
                                 "description":"provider and network"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.fields[0]").value("email"));

        api.perform(get("/api/v1/fuzzy/patterns").param("graph", GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patterns.length()").value(1))
                .andExpect(jsonPath("$.patterns[0].name").value("shared habits"))
                .andExpect(jsonPath("$.patterns[0].enabled").value(true));

        api.perform(delete("/api/v1/fuzzy/patterns/{graph}/{id}", GRAPH, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(0))
                .andExpect(jsonPath("$.patterns").value(0));
    }

    @Test
    void aPatternThatCannotBeParsedIsRejected() throws Exception {
        api.perform(post("/api/v1/fuzzy/patterns").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"graph":"payment-graph","id":3,"expression":"noSuchFunction(email)"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "Unknown fuzzy function")));
    }

    @Test
    void validatingShowsWhatEachPartContributed() throws Exception {
        api.perform(post("/api/v1/fuzzy/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":0,
                                 "expression":"emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)",
                                 "context":"{\\"email\\":\\"19880101@mail.ru\\",\\"phone\\":\\"+8613800138001\\",\\"ip\\":\\"10.20.31.7\\"}"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.key").value("mail.ru_86_10.20"))
                .andExpect(jsonPath("$.linkVertexId").value("fl_0_mail.ru_86_10.20"))
                .andExpect(jsonPath("$.steps[0].value").value("mail.ru"))
                .andExpect(jsonPath("$.steps[1].value").value("_"));
    }

    @Test
    void validatingABrokenExpressionAnswersInsteadOfFailing() throws Exception {
        api.perform(post("/api/v1/fuzzy/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expression":"ip2(ip)+","context":"{}"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("ends with")));
    }

    @Test
    void aPatternThatDoesNotApplyHasNoKey() throws Exception {
        api.perform(post("/api/v1/fuzzy/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patternId":0,"expression":"emailDomain(email)+'_'+ip2(ip)",
                                 "context":"{\\"ip\\":\\"10.20.31.7\\"}"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.key").doesNotExist())
                .andExpect(jsonPath("$.steps[0].value").doesNotExist())
                .andExpect(jsonPath("$.steps[1].value").value("_"));
    }

    @Test
    void theContextCanComeFromAVertex() throws Exception {
        patterns.save(GRAPH, FuzzyDemo.SHARED_HABITS);

        api.perform(post("/api/v1/fuzzy/graphs/{graph}/plan", GRAPH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vertexName":"Account","vertexId":"fz_a1","patternId":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links[0].patternId").value(0))
                .andExpect(jsonPath("$.links[0].key").value("mail.ru_86_10.20_zhang"))
                .andExpect(jsonPath("$.links[0].linkVertexId").value("fl_0_mail.ru_86_10.20_zhang"));
    }

    @Test
    void linkingAVertexThatDoesNotExistSaysSo() throws Exception {
        api.perform(post("/api/v1/fuzzy/graphs/{graph}/plan", GRAPH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vertexName":"Account","vertexId":"nobody"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "does not exist")));
    }

    @Test
    void anUnknownGraphIsRejected() throws Exception {
        api.perform(get("/api/v1/fuzzy/patterns").param("graph", "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theExampleQueryOfAGraphComesFromItsPatterns() throws Exception {
        patterns.save(GRAPH, FuzzyDemo.SHARED_HABITS);

        api.perform(get("/api/v1/fuzzy/demo").param("graph", GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patternId").value(0))
                .andExpect(jsonPath("$.title").value("pattern -> ring -> payments -> receiver"))
                .andExpect(jsonPath("$.key").value("mail.ru_86_10.20_zhang"))
                .andExpect(jsonPath("$.query").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("g.V().fuzzyLink(0, '"),
                        org.hamcrest.Matchers.containsString("\"email\":\"19880101@mail.ru\""),
                        org.hamcrest.Matchers.containsString(
                                ".out('FuzzyLinkedTo').outE('Payment').otherV()"))));
    }

    @Test
    void aGraphWhosePatternsHaveNoExampleSaysSo() throws Exception {
        patterns.save(GRAPH, FuzzyPattern.of(3, "ip2(ip)"));

        api.perform(get("/api/v1/fuzzy/demo").param("graph", GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.containsString("example")));
    }

    @Test
    void theFunctionLibraryIsDocumentedForTheEditor() throws Exception {
        api.perform(get("/api/v1/fuzzy/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='ip2')]").exists())
                .andExpect(jsonPath("$[?(@.name=='regex')]").exists())
                .andExpect(jsonPath("$[?(@.name=='cardBin')]").exists());
    }

    @Test
    void aDisabledPatternStaysVisibleToTheEditor() throws Exception {
        patterns.save(GRAPH, new FuzzyPattern(9, "off", "ip2(ip)", "", false));

        api.perform(get("/api/v1/fuzzy/patterns").param("graph", GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patterns[0].id").value(9))
                .andExpect(jsonPath("$.patterns[0].enabled").value(false));

        assertEquals(List.of(), com.pengcheng.phoenixgraph.fuzzy.FuzzyLinks.plan(
                        patterns.registryOf(GRAPH), FuzzyContext.of(Map.of("ip", "10.20.1.1"))),
                "a disabled pattern plans nothing");
    }

    @Test
    void theDemoPatternsReadTheContextTheyDocument() {
        FuzzyContext context = FuzzyDemo.demoContext();

        assertEquals("mail.ru_86_10.20_zhang", FuzzyDemo.SHARED_HABITS.key(context));
        assertEquals("10.20.31_621700_1988", FuzzyDemo.MACHINE_MAIL.key(context));
    }
}
