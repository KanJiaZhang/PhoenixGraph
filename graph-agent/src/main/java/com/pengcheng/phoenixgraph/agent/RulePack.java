package com.pengcheng.phoenixgraph.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The vocabulary and rules an extraction uses. A pack is data: the default one is the 三国 relation table,
 * and another domain supplies a JSON file under {@code agent-rules/} (or a caller-built pack) rather than
 * changing the extractor.
 *
 * <p>A rule reads the text <em>between</em> two known mentions. {@code after} optionally reads the start of
 * what follows the second mention. {@code swap} reverses the direction, for a passive sentence
 * ("A was caused by B" means B caused A). The compiled patterns are kept here so a malformed pack fails at
 * load time, with the rule's name in the message.
 */
public record RulePack(String name, Map<String, String> relationWords, List<Rule> rules, List<Window> windows) {

    /** What a rule expects at an end. The extractor currently only uses it for documentation and windows. */
    public enum Slot {
        ANY, PERSON, PLACE
    }

    /** One relation rule. */
    public record Rule(String name, String relation, Pattern between, Pattern after, Slot head, Slot tail,
                       boolean swap) {

        public Rule {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(between, "between");
        }
    }

    /** A verb that may introduce the other end of a relation when only one end is known. */
    public record Window(String verb, String relation, Slot slot) {
    }

    public RulePack {
        relationWords = relationWords == null ? Map.of() : Map.copyOf(relationWords);
        rules = rules == null ? List.of() : List.copyOf(rules);
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    private static final String RESOURCE_DIR = "/agent-rules/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RulePack SANGUO = sanguoPack();

    /** The pack the toolkit has always used: the relation words of the 三国 corpus. */
    public static RulePack sanguo() {
        return SANGUO;
    }

    /** Resolve a pack by name. {@code null}/blank/{@code sanguo} is the built-in default. */
    public static RulePack byName(String name) {
        if (name == null || name.isBlank() || "sanguo".equalsIgnoreCase(name)) {
            return sanguo();
        }
        String resource = RESOURCE_DIR + name.toLowerCase() + ".json";
        try (InputStream in = RulePack.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Unknown rule pack '" + name
                        + "'; available: sanguo, incident (add agent-rules/<name>.json for another)");
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the rule pack " + resource, e);
        }
    }

    /** Read a pack from JSON. Exposed so a caller can load a pack from its own storage. */
    public static RulePack load(InputStream in) {
        try {
            JsonNode root = MAPPER.readTree(in);
            String name = root.path("name").asText("custom");
            Map<String, String> words = new LinkedHashMap<>();
            root.path("relationWords").fields()
                    .forEachRemaining(entry -> words.put(entry.getKey(), entry.getValue().asText()));
            List<Rule> rules = new ArrayList<>();
            for (JsonNode node : root.withArray("rules")) {
                String ruleName = required(node, "name");
                String relation = node.hasNonNull("relation") ? node.get("relation").asText() : null;
                Pattern between;
                try {
                    between = Pattern.compile(required(node, "between"));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("rule pack '" + name + "': rule '" + ruleName
                            + "' has an invalid between pattern: " + e.getMessage(), e);
                }
                Pattern after = node.hasNonNull("after") ? Pattern.compile(node.get("after").asText()) : null;
                rules.add(new Rule(ruleName, relation, between, after,
                        slot(node.path("head").asText("ANY")), slot(node.path("tail").asText("ANY")),
                        node.path("swap").asBoolean(false)));
            }
            List<Window> windows = new ArrayList<>();
            for (JsonNode node : root.withArray("windows")) {
                windows.add(new Window(required(node, "verb"), required(node, "relation"),
                        slot(node.path("slot").asText("ANY"))));
            }
            return new RulePack(name, words, rules, windows);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse a rule pack", e);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a rule pack entry needs '" + field + "'");
        }
        return value;
    }

    private static Slot slot(String value) {
        try {
            return Slot.valueOf(value == null ? "ANY" : value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown slot '" + value + "' (use ANY, PERSON or PLACE)");
        }
    }

    /** The built-in 三国 pack, expressed with the same rules the extractor used before packs were data. */
    private static RulePack sanguoPack() {
        Map<String, String> words = new LinkedHashMap<>();
        words.put("臣", "minister_of");
        words.put("部下", "subordinate_of");
        words.put("部将", "subordinate_of");
        words.put("谋士", "advisor_of");
        words.put("军师", "strategist_of");
        words.put("主公", "lord_of");
        words.put("师傅", "teaches");
        words.put("师父", "teaches");
        words.put("徒弟", "studies_under");
        words.put("父亲", "parent_of");
        words.put("儿子", "child_of");
        words.put("哥哥", "sibling_of");
        words.put("弟弟", "sibling_of");
        words.put("妻子", "spouse_of");
        words.put("夫人", "spouse_of");
        words.put("兄弟", "sibling_of");
        words.put("义弟", "sibling_of");
        words.put("义兄", "sibling_of");

        List<Rule> rules = List.of(
                rule("is-of", null, "^是?$", "^(?:的|之)([\\u4e00-\\u9fa5]{1,3})", false),
                rule("of-is", null, "^(?:的|之)([\\u4e00-\\u9fa5]{1,3}?)(?:是)?$", null, true),
                rule("serves", "serves", "^(?:投奔|投效|效力于|归顺|归降|跟随|从属)$", null, false),
                rule("studies_under", "studies_under", "^拜$", "^为师$", false),
                rule("killed", "killed", "^(?:斩杀|斩|诛杀|诛|杀死|刺杀|击杀|大破|击败)$", null, false),
                rule("fought", "fought", "^(?:与|和)$", "^(?:交战|大战|决战|战)$", false),
                rule("advises", "advisor_of", "^(?:为|担任|做了)$", "^(?:谋士|军师)(?:之一)?$", false),
                rule("held_office", "held_office", "^(?:任|曾任|担任|被任命为|为)$",
                        "^(?:太守|将军|丞相|刺史|都尉|校尉|尚书|侍中)$", false),
                rule("stationed_at", "stationed_at", "^(?:驻守|镇守|守|屯于|驻扎于)$", null, false),
                rule("born_in", "born_in", "^(?:出生于|生于|籍贯)$", null, false));

        List<Window> windows = List.of(
                window("效力于", "serves", Slot.PERSON),
                window("投奔", "serves", Slot.PERSON),
                window("投效", "serves", Slot.PERSON),
                window("拜", "studies_under", Slot.PERSON),
                window("斩杀", "killed", Slot.PERSON),
                window("杀死", "killed", Slot.PERSON),
                window("诛杀", "killed", Slot.PERSON),
                window("大破", "killed", Slot.PERSON),
                window("击败", "defeated", Slot.PERSON),
                window("驻守", "stationed_at", Slot.PLACE),
                window("镇守", "stationed_at", Slot.PLACE));
        return new RulePack("sanguo", words, rules, windows);
    }

    private static Rule rule(String name, String relation, String between, String after, boolean swap) {
        return new Rule(name, relation, Pattern.compile(between),
                after == null ? null : Pattern.compile(after), Slot.ANY, Slot.ANY, swap);
    }

    private static Window window(String verb, String relation, Slot slot) {
        return new Window(verb, relation, slot);
    }
}
