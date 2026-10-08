package com.pengcheng.phoenixgraph.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads entities and relations out of documents.
 *
 * <p>Two stages, both deliberately inspectable - an extraction nobody can audit is an extraction nobody
 * can trust:
 *
 * <ol>
 *   <li><b>Mentions.</b> A gazetteer (the dictionary a caller supplies: names already known, from a
 *       curated list, a schema, or a previous ingestion) is scanned over every sentence, longest match
 *       first, aliases resolved. That is what makes an extraction deterministic: the same document and the
 *       same dictionary give the same entities on any machine.</li>
 *   <li><b>Relations.</b> For every ordered pair of mentions in a sentence, the text <em>between</em> them
 *       and the text right after the second one are matched against a small rule table
 *       ({@link #RULES}). Chinese relation words are not verb-final the way English is, so the rules read
 *       the gap ("关羽<b>是</b>刘备<b>的臣</b>") rather than parse a sentence. A rule that finds no second
 *       known name runs in <em>window</em> mode: the 2-4 characters on the other side of its verb become a
 *       new entity, typed by the rule's slot hint - that is how a document introduces names the gazetteer
 *       did not have yet.</li>
 * </ol>
 *
 * <p>Everything that comes out carries provenance: the chunk id and the sentence. The quality report
 * checks that sentence mechanically (does it really contain both ends?), which is the accuracy half of
 * "can this knowledge be trusted".
 */
public final class KnowledgeExtractor {

    /**
     * The relation words the rules understand, mapped to the canonical relation in the graph.
     *
     * <p>The direction is always "the head is the word of the tail": {@code A是B的臣} and {@code B的臣是A}
     * are both {@code A --minister_of--> B}, so a word means the same thing whichever way the sentence puts
     * it - which is why {@link #apply} swaps the ends for the {@code X的Y是Z} form.
     */
    private static final Map<String, String> RELATION_WORDS = Map.ofEntries(
            Map.entry("臣", "minister_of"), Map.entry("部下", "subordinate_of"), Map.entry("部将", "subordinate_of"),
            Map.entry("谋士", "advisor_of"), Map.entry("军师", "strategist_of"), Map.entry("主公", "lord_of"),
            Map.entry("师傅", "teaches"), Map.entry("师父", "teaches"), Map.entry("徒弟", "studies_under"),
            Map.entry("父亲", "parent_of"), Map.entry("儿子", "child_of"), Map.entry("哥哥", "sibling_of"),
            Map.entry("弟弟", "sibling_of"), Map.entry("妻子", "spouse_of"), Map.entry("夫人", "spouse_of"),
            Map.entry("兄弟", "sibling_of"), Map.entry("义弟", "sibling_of"), Map.entry("义兄", "sibling_of"));

    /** What a rule may find around a pair of mentions. */
    private enum Slot {
        /** Nothing is said about the ends beyond the relation: any entity type will do. */
        ANY,
        /** The end is expected to be a person. */
        PERSON,
        /** The end is expected to be a place. */
        PLACE
    }

    /**
     * One relation rule: what the text between two mentions looks like, what may follow the second one,
     * what the relation is called and what the ends are expected to be.
     *
     * <p>{@code between} is matched against the whole gap and must therefore say what it consumes
     * ({@code 是}, {@code 效力于}); {@code after} is optional and tries the start of what follows the second
     * mention ({@code 的臣}).
     */
    private record Rule(String name, String relation, Pattern between, Pattern after, Slot head, Slot tail) {
    }

    private static final List<Rule> RULES = List.of(
            // 关羽 是 刘备 的臣 / 张飞 是 刘备 的 义弟
            new Rule("is-of", null, Pattern.compile("^是?$"), Pattern.compile("^(?:的|之)([\\u4e00-\\u9fa5]{1,3})"),
                    Slot.ANY, Slot.ANY),
            // 关羽 的 主公 是 刘备
            new Rule("of-is", null, Pattern.compile("^(?:的|之)([\\u4e00-\\u9fa5]{1,3}?)(?:是)?$"), null,
                    Slot.ANY, Slot.ANY),
            new Rule("serves", "serves", Pattern.compile("^(?:投奔|投效|效力于|归顺|归降|跟随|从属)$"), null,
                    Slot.PERSON, Slot.ANY),
            new Rule("studies_under", "studies_under", Pattern.compile("^拜$"), Pattern.compile("^为师$"),
                    Slot.PERSON, Slot.PERSON),
            new Rule("killed", "killed", Pattern.compile("^(?:斩杀|斩|诛杀|诛|杀死|刺杀|击杀|大破|击败)$"), null,
                    Slot.PERSON, Slot.PERSON),
            new Rule("fought", "fought", Pattern.compile("^(?:与|和)$"), Pattern.compile("^(?:交战|大战|决战|战)$"),
                    Slot.PERSON, Slot.PERSON),
            new Rule("advises", "advisor_of", Pattern.compile("^(?:为|担任|做了)$"), Pattern.compile("^(?:谋士|军师)(?:之一)?$"),
                    Slot.PERSON, Slot.PERSON),
            new Rule("held_office", "held_office", Pattern.compile("^(?:任|曾任|担任|被任命为|为)$"),
                    Pattern.compile("^(?:太守|将军|丞相|刺史|都尉|校尉|尚书|侍中)$"), Slot.PERSON, Slot.ANY),
            new Rule("stationed_at", "stationed_at", Pattern.compile("^(?:驻守|镇守|守|屯于|驻扎于)$"), null,
                    Slot.PERSON, Slot.PLACE),
            new Rule("born_in", "born_in", Pattern.compile("^(?:出生于|生于|籍贯)$"), null,
                    Slot.PERSON, Slot.PLACE));

    /** The verbs the window rule looks for and the slot they open. */
    private static final List<Window> WINDOW_VERBS = List.of(
            new Window("效力于", "serves", Slot.PERSON),
            new Window("投奔", "serves", Slot.PERSON),
            new Window("投效", "serves", Slot.PERSON),
            new Window("拜", "studies_under", Slot.PERSON),
            new Window("斩杀", "killed", Slot.PERSON),
            new Window("杀死", "killed", Slot.PERSON),
            new Window("诛杀", "killed", Slot.PERSON),
            new Window("大破", "killed", Slot.PERSON),
            new Window("击败", "defeated", Slot.PERSON),
            new Window("驻守", "stationed_at", Slot.PLACE),
            new Window("镇守", "stationed_at", Slot.PLACE));

    /** A verb, the relation it marks and what the name in its slot is expected to be. */
    private record Window(String verb, String relation, Slot slot) {
    }

    private static final Pattern HANZI_NAME = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("[的了之与和及、，。；：是]$");

    private KnowledgeExtractor() {
    }

    /**
     * What a caller tells the extractor.
     *
     * @param dictionary known entities (name to type); entities found outside it are typed by the rule
     *                   that found them, or {@code Mention} when the rule says nothing
     * @param aliases    alias to canonical name (a document may say 玄德 where the graph says 刘备)
     */
    public record Options(Map<String, String> dictionary, Map<String, String> aliases) {

        public Options {
            dictionary = dictionary == null ? Map.of() : Map.copyOf(dictionary);
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        }

        public static Options of(Map<String, String> dictionary) {
            return new Options(dictionary, Map.of());
        }

        public static Options empty() {
            return new Options(Map.of(), Map.of());
        }
    }

    /** An entity of the draft graph, with the chunks it was seen in. */
    public record Entity(String id, String type, String name, List<String> chunkIds, int mentions) {
    }

    /** A relation of the draft graph, with the provenance the report verifies. */
    public record Relation(String head, String relation, String tail, String rule, String chunkId, String sentence) {
    }

    /** The chunks that mention one entity, in document order (what the entity's vector is built from). */
    public record Mentions(Map<String, List<Chunker.Chunk>> byEntity) {
    }

    /** The extraction result: the draft graph plus what produced it. */
    public record Result(List<Entity> entities, List<Relation> relations, List<Chunker.Chunk> chunks,
                         Map<String, Integer> relationsByRule, Map<String, Integer> relationsByType,
                         List<String> warnings) {

        public Result {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            chunks = List.copyOf(chunks);
            relationsByRule = Map.copyOf(relationsByRule);
            relationsByType = Map.copyOf(relationsByType);
            warnings = List.copyOf(warnings);
        }

        /** The ids of every entity, in extraction order. */
        public List<String> entityIds() {
            return entities.stream().map(Entity::id).toList();
        }
    }

    /** Extracts the draft graph of a set of documents. */
    public static Result extract(List<Chunker.Document> documents, Options options) {
        return extractChunks(Chunker.chunk(documents), options);
    }

    /** Extracts the draft graph of already chunked documents. */
    public static Result extractChunks(List<Chunker.Chunk> chunks, Options options) {
        Map<String, String> dictionary = new LinkedHashMap<>(options.dictionary());
        Map<String, String> aliases = new LinkedHashMap<>(options.aliases());
        // names sorted longest first: 诸葛亮 must win over 诸葛, and a mention is matched once
        List<String> gazetteer = dictionary.keySet().stream()
                .filter(name -> name != null && name.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(name -> name))
                .toList();
        List<String> aliasNames = aliases.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();

        Map<String, String> discoveredTypes = new LinkedHashMap<>();
        Map<String, Integer> mentions = new LinkedHashMap<>();
        Map<String, Set<String>> chunkIds = new LinkedHashMap<>();
        Map<String, Integer> byRule = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Relation> relations = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int unsupported = 0;

        for (Chunker.Chunk chunk : chunks) {
            for (String sentence : Chunker.sentences(chunk.text())) {
                List<Mention> found = mentionsIn(sentence, gazetteer, aliases, aliasNames, dictionary);
                for (Mention mention : found) {
                    mentions.merge(mention.name(), 1, Integer::sum);
                    chunkIds.computeIfAbsent(mention.name(), key -> new LinkedHashSet<>()).add(chunk.chunkId());
                }
                // Pairs are read across the whole sentence, but a gap that runs over a comma is cut at the
                // last one: a Chinese sentence changes subject at a comma, and the rule that fires is the
                // one in the clause the second mention sits in ("关羽字云长，是刘备的义弟").
                for (int i = 0; i < found.size(); i++) {
                    for (int j = 0; j < found.size(); j++) {
                        if (i == j || found.get(i).start() > found.get(j).start()) {
                            continue; // ordered pairs only: the rules read left to right
                        }
                        Mention head = found.get(i);
                        Mention tail = found.get(j);
                        String rawGap = sentence.substring(head.end(), tail.start());
                        String suffix = firstClause(sentence.substring(tail.end())).strip();
                        Relation relation = apply(head, tail, rawGap, suffix, chunk, sentence, found.get(0) == head);
                        if (relation != null) {
                            record(relation, relations, byRule, byType);
                        }
                    }
                }
                // the window rules: one known end is enough, the other side may be a new name. They read the
                // clause itself, because a clause is where a verb and its slot are.
                for (String clause : sentence.split("[，,、；;：:]")) {
                    List<Mention> inClause = mentionsIn(clause, gazetteer, aliases, aliasNames, dictionary);
                    for (Window window : WINDOW_VERBS) {
                        for (Mention mention : inClause) {
                            Relation relation = byWindow(window, mention, clause, chunk, discoveredTypes, sentence);
                            if (relation != null) {
                                record(relation, relations, byRule, byType);
                            }
                        }
                    }
                }
            }
        }

        List<Entity> entities = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(dictionary.keySet());
        names.addAll(discoveredTypes.keySet());
        for (String name : names) {
            if ("".equals(name) || name == null || name.length() < 2) {
                continue;
            }
            String type = dictionary.getOrDefault(name, discoveredTypes.getOrDefault(name, "Mention"));
            entities.add(new Entity(name, type, name,
                    List.copyOf(chunkIds.getOrDefault(name, Set.of())), mentions.getOrDefault(name, 0)));
        }
        if (unsupported > 0) {
            warnings.add(unsupported + " relation candidates had no rule that understood them");
        }
        return new Result(entities, new ArrayList<>(relations.values()), chunks, byRule, byType, warnings);
    }

    /** First occurrence wins: the earliest sentence a relation was read from is the one it cites. */
    private static void record(Relation relation, Map<String, Relation> relations,
                               Map<String, Integer> byRule, Map<String, Integer> byType) {
        String key = relation.head() + "|" + relation.relation() + "|" + relation.tail();
        if (relations.putIfAbsent(key, relation) == null) {
            byRule.merge(relation.rule(), 1, Integer::sum);
            byType.merge(relation.relation(), 1, Integer::sum);
        }
    }

    /** The text after the last clause separator: the clause the second mention starts in. */
    private static String lastClause(String gap) {
        int at = Math.max(Math.max(gap.lastIndexOf('，'), gap.lastIndexOf(',')),
                Math.max(gap.lastIndexOf('；'), gap.lastIndexOf('：')));
        return at < 0 ? gap : gap.substring(at + 1);
    }

    /** The text up to the first clause separator, without the punctuation that closes a sentence. */
    private static String firstClause(String rest) {
        int at = rest.length();
        for (char separator : new char[] {'，', ',', '；', ';', '：', ':'}) {
            int found = rest.indexOf(separator);
            if (found >= 0 && found < at) {
                at = found;
            }
        }
        return rest.substring(0, at).replaceAll("[。！？!?；;]+", "");
    }

    /** A name found in a sentence: the canonical name, its span, and the type the gazetteer gave it. */
    private record Mention(String name, int start, int end, String type) {
    }

    /** Every gazetteer mention of a sentence, longest match first, no overlap, aliases resolved. */
    private static List<Mention> mentionsIn(String sentence, List<String> gazetteer, Map<String, String> aliases,
                                            List<String> aliasNames, Map<String, String> dictionary) {
        List<Mention> found = new ArrayList<>();
        boolean[] taken = new boolean[sentence.length()];
        for (String name : aliasNames) {
            String canonical = aliases.get(name);
            for (int at = sentence.indexOf(name); at >= 0; at = sentence.indexOf(name, at + 1)) {
                if (overlaps(taken, at, at + name.length())) {
                    continue;
                }
                mark(taken, at, at + name.length());
                found.add(new Mention(canonical, at, at + name.length(),
                        dictionary.getOrDefault(canonical, "Mention")));
            }
        }
        for (String name : gazetteer) {
            for (int at = sentence.indexOf(name); at >= 0; at = sentence.indexOf(name, at + 1)) {
                if (overlaps(taken, at, at + name.length())) {
                    continue;
                }
                mark(taken, at, at + name.length());
                found.add(new Mention(name, at, at + name.length(), dictionary.getOrDefault(name, "Mention")));
            }
        }
        found.sort(Comparator.comparingInt(Mention::start));
        return found;
    }

    private static boolean overlaps(boolean[] taken, int from, int to) {
        for (int i = from; i < to && i < taken.length; i++) {
            if (taken[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] taken, int from, int to) {
        for (int i = from; i < to && i < taken.length; i++) {
            taken[i] = true;
        }
    }

    /**
     * Tries every rule on one ordered pair of mentions; the first that matches wins.
     *
     * <p>{@code headIsFirst} is the sentence's topic test: when the gap between the two mentions runs over a
     * clause boundary, only the sentence's first mention may be the subject - otherwise
     * "关羽斩杀颜良，又与文丑交战" would also read as 颜良 fighting 文丑, which the sentence never says.
     *
     * <p>{@code gap} is the raw text between the mentions; only the clause the second mention sits in is
     * matched, and a leading connective ("也是", "又与") is not part of the relation.
     */
    private static Relation apply(Mention head, Mention tail, String gap, String suffix, Chunker.Chunk chunk,
                                  String sentence, boolean headIsFirst) {
        if (head.name().equals(tail.name())) {
            return null;
        }
        boolean acrossClause = gap.matches(".*[，,、；;：:].*");
        if (acrossClause && !headIsFirst) {
            return null;
        }
        String trimmed = lastClause(gap).replaceFirst("^(?:也|又|再|遂|乃|亦|后|便|即|则|都|还|而)", "").strip();
        for (Rule rule : RULES) {
            Matcher between = rule.between().matcher(trimmed);
            if (!between.matches()) {
                continue;
            }
            String relation = rule.relation();
            if (relation == null && between.groupCount() >= 1 && between.group(1) != null) {
                // the "是 X 的 Y" family: the relation word is in the text, the canonical name comes from it
                relation = RELATION_WORDS.get(between.group(1));
                if (relation == null) {
                    continue; // a word this toolkit has no relation for: skipping beats inventing one
                }
            }
            if (rule.after() != null) {
                Matcher after = rule.after().matcher(suffix);
                if (!after.find()) {
                    continue;
                }
                if (rule.relation() == null && after.groupCount() >= 1 && after.group(1) != null) {
                    relation = RELATION_WORDS.get(after.group(1));
                    if (relation == null) {
                        continue;
                    }
                }
            }
            if (relation == null) {
                continue;
            }
            // "X 的 Y 是 Z" says Z is the Y of X: the relation word describes the second name, so the ends
            // swap and the graph keeps one direction per relation whatever the sentence's word order
            boolean swapped = "of-is".equals(rule.name());
            String from = swapped ? tail.name() : head.name();
            String to = swapped ? head.name() : tail.name();
            return new Relation(from, relation, to, rule.name(), chunk.chunkId(), sentence.strip());
        }
        return null;
    }

    /**
     * A rule whose other end is not a known name: the characters next to the verb become one, so a document
     * can introduce entities the dictionary did not have. Which side is the new name depends on where the
     * known one is - before the verb means the slot is after it, and the other way round - and the rule name
     * in the relation says the type came from the verb rather than from a dictionary, which is what the
     * quality report counts as a lower-confidence read.
     */
    private static Relation byWindow(Window window, Mention mention, String clause, Chunker.Chunk chunk,
                                     Map<String, String> discovered, String sentence) {
        int verbAt = clause.indexOf(window.verb());
        if (verbAt < 0) {
            return null;
        }
        boolean mentionBefore = mention.end() <= verbAt;
        String candidate;
        if (mentionBefore) {
            // the slot is on the right: the name starts right after the verb
            Matcher after = HANZI_NAME.matcher(clause.substring(verbAt + window.verb().length()));
            if (!after.lookingAt()) {
                return null;
            }
            candidate = after.group();
        } else {
            // the slot is on the left: the name ends right before the verb
            String before = clause.substring(0, verbAt);
            Matcher left = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})$").matcher(before);
            if (!left.find()) {
                return null;
            }
            candidate = left.group(1);
        }
        candidate = candidate.replaceAll(TRAILING_PARTICLE.pattern(), "");
        if (candidate.length() < 2 || candidate.equals(mention.name())) {
            return null;
        }
        discovered.putIfAbsent(candidate, switch (window.slot()) {
            case PERSON -> "Person";
            case PLACE -> "Place";
            case ANY -> "Mention";
        });
        String head = mentionBefore ? mention.name() : candidate;
        String tail = mentionBefore ? candidate : mention.name();
        return new Relation(head, window.relation(), tail, window.verb(), chunk.chunkId(), sentence.strip());
    }
}
