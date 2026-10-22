package com.pengcheng.phoenixgraph.agent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 *       ({@link RulePack}). Chinese relation words are not verb-final the way English is, so the rules read
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
     * @param rules      the relation vocabulary and rules; the default is the 三国 pack
     */
    public record Options(Map<String, String> dictionary, Map<String, String> aliases, RulePack rules,
                          boolean dedupe) {

        public Options {
            dictionary = dictionary == null ? Map.of() : Map.copyOf(dictionary);
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
            rules = rules == null ? RulePack.sanguo() : rules;
        }

        /** The default pack, for callers that have no domain vocabulary of their own. */
        public Options(Map<String, String> dictionary, Map<String, String> aliases, RulePack rules) {
            this(dictionary, aliases, rules, true);
        }

        public Options(Map<String, String> dictionary, Map<String, String> aliases) {
            this(dictionary, aliases, RulePack.sanguo(), true);
        }

        public static Options of(Map<String, String> dictionary) {
            return new Options(dictionary, Map.of());
        }

        public static Options empty() {
            return new Options(Map.of(), Map.of());
        }
    }

    /** An entity of the draft graph, with the chunks it was seen in. */
    public record Entity(String id, String type, String name, List<String> chunkIds, int mentions,
                         List<String> aliases) {

        public Entity {
            chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public Entity(String id, String type, String name, List<String> chunkIds, int mentions) {
            this(id, type, name, chunkIds, mentions, List.of());
        }
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
                         List<String> warnings, int duplicatesRemoved) {

        public Result {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            chunks = List.copyOf(chunks);
            relationsByRule = Map.copyOf(relationsByRule);
            relationsByType = Map.copyOf(relationsByType);
            warnings = List.copyOf(warnings);
        }

        public Result(List<Entity> entities, List<Relation> relations, List<Chunker.Chunk> chunks,
                      Map<String, Integer> relationsByRule, Map<String, Integer> relationsByType,
                      List<String> warnings) {
            this(entities, relations, chunks, relationsByRule, relationsByType, warnings, 0);
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
        RulePack pack = options.rules();
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
                        Relation relation = apply(head, tail, rawGap, suffix, chunk, sentence, found.get(0) == head, pack);
                        if (relation != null) {
                            record(relation, relations, byRule, byType);
                        }
                    }
                }
                // the window rules: one known end is enough, the other side may be a new name. They read the
                // clause itself, because a clause is where a verb and its slot are.
                for (String clause : sentence.split("[，,、；;：:]")) {
                    List<Mention> inClause = mentionsIn(clause, gazetteer, aliases, aliasNames, dictionary);
                    for (RulePack.Window window : pack.windows()) {
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
        int duplicates = options.dedupe()
                ? dedupeEntities(entities, relations, byRule, byType, dictionary)
                : 0;
        if (duplicates > 0) {
            warnings.add(duplicates + " duplicate entity names merged (case/whitespace/punctuation)");
        }
        return new Result(entities, new ArrayList<>(relations.values()), chunks, byRule, byType, warnings,
                duplicates);
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

    /**
     * Merges entities whose names differ only by case, whitespace or punctuation. The canonical entity is
     * the dictionary entry when there is one, otherwise the one with the most mentions. The other spellings
     * are kept as aliases so a relation's sentence can still support both of its ends.
     */
    private static int dedupeEntities(List<Entity> entities, Map<String, Relation> relations,
                                      Map<String, Integer> byRule, Map<String, Integer> byType,
                                      Map<String, String> dictionary) {
        Map<String, List<Entity>> groups = new LinkedHashMap<>();
        for (Entity entity : entities) {
            groups.computeIfAbsent(normalizedEntityName(entity.name()), key -> new ArrayList<>()).add(entity);
        }
        if (groups.values().stream().allMatch(group -> group.size() == 1)) {
            return 0;
        }
        Map<String, String> canonicalOf = new LinkedHashMap<>();
        List<Entity> resolved = new ArrayList<>();
        int removed = 0;
        for (List<Entity> group : groups.values()) {
            Entity canonical = group.stream().min(Comparator
                    .comparingInt((Entity entity) -> dictionary.containsKey(entity.name()) ? 0 : 1)
                    .thenComparingInt(entity -> -entity.mentions())
                    .thenComparingInt(entity -> -entity.name().length())
                    .thenComparing(Entity::name)).orElseThrow();
            Set<String> aliases = new LinkedHashSet<>();
            Set<String> chunkIds = new LinkedHashSet<>();
            int mentions = 0;
            for (Entity entity : group) {
                if (!entity.name().equals(canonical.name())) {
                    aliases.add(entity.name());
                }
                chunkIds.addAll(entity.chunkIds());
                mentions += entity.mentions();
                canonicalOf.put(entity.id(), canonical.id());
            }
            String type = canonical.type();
            if ("Mention".equals(type)) {
                type = group.stream().map(Entity::type).filter(value -> !"Mention".equals(value))
                        .findFirst().orElse(type);
            }
            resolved.add(new Entity(canonical.id(), type, canonical.name(), List.copyOf(chunkIds), mentions,
                    List.copyOf(aliases)));
            removed += group.size() - 1;
        }

        entities.clear();
        entities.addAll(resolved);
        Map<String, Relation> rewritten = new LinkedHashMap<>();
        byRule.clear();
        byType.clear();
        for (Relation relation : relations.values()) {
            Relation next = new Relation(canonicalOf.getOrDefault(relation.head(), relation.head()),
                    relation.relation(), canonicalOf.getOrDefault(relation.tail(), relation.tail()),
                    relation.rule(), relation.chunkId(), relation.sentence());
            record(next, rewritten, byRule, byType);
        }
        relations.clear();
        relations.putAll(rewritten);
        return removed;
    }

    /** Case, width, whitespace and punctuation are not an entity's identity. */
    private static String normalizedEntityName(String name) {
        String normalized = ChineseSimplifier.toSimplified(Normalizer.normalize(name, Normalizer.Form.NFKC))
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}]+", "");
        return normalized.isEmpty() ? name.strip().toLowerCase(Locale.ROOT) : normalized;
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
                                  String sentence, boolean headIsFirst, RulePack pack) {
        if (head.name().equals(tail.name())) {
            return null;
        }
        boolean acrossClause = gap.matches(".*[，,、；;：:].*");
        if (acrossClause && !headIsFirst) {
            return null;
        }
        String trimmed = lastClause(gap).replaceFirst("^(?:也|又|再|遂|乃|亦|后|便|即|则|都|还|而)", "").strip();
        for (RulePack.Rule rule : pack.rules()) {
            Matcher between = rule.between().matcher(trimmed);
            if (!between.matches()) {
                continue;
            }
            String relation = rule.relation();
            if (relation == null && between.groupCount() >= 1 && between.group(1) != null) {
                // the "是 X 的 Y" family: the relation word is in the text, the canonical name comes from it
                relation = pack.relationWords().get(between.group(1));
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
                    relation = pack.relationWords().get(after.group(1));
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
            boolean swapped = rule.swap();
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
    private static Relation byWindow(RulePack.Window window, Mention mention, String clause, Chunker.Chunk chunk,
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
