package com.pengcheng.phoenixgraph.integration.sanguo;

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
 * <b>方案 C</b>: extract entities and relations from the free-text columns the
 * structured parse ignores — {@code 历史} (historical biography) and {@code 小说}
 * (novel biography) of every {@link SanguoDataset.Document}.
 *
 * <p>Every rule is a documented pattern with a Chinese label, and every hit keeps its
 * <em>provenance</em> (document, field, sentence), so the extraction can be audited
 * line by line. Nothing here uses a model: it is a gazetteer plus a pattern table, so
 * the result is deterministic and reproducible.
 *
 * <table border="1">
 *   <caption>rules</caption>
 *   <tr><th>relation</th><th>中文</th><th>how it is recognised</th><th>new entities</th></tr>
 *   <tr><td>{@code mentions}</td><td>提及</td><td>the sentence contains a <em>known</em> entity
 *       name (longest match, the document's own name excluded)</td><td>no</td></tr>
 *   <tr><td>{@code holds}</td><td>官职</td><td>{@code 为|拜|封|迁|领|任|加|授|表|除 + <title>} where the
 *       title ends in an office suffix, contains no known entity and is not followed by a person
 *       name; plus a dedicated {@code 五虎} rule</td><td>yes (new offices)</td></tr>
 *   <tr><td>{@code strategistOf}</td><td>军师</td><td>{@code X辟为军师} / {@code X之军师} /
 *       {@code 投效与X ... 军师}</td><td>no</td></tr>
 *   <tr><td>{@code advisorOf}</td><td>谋士</td><td>{@code X谋士} (the owner) / {@code X谋士Y}
 *       (Y is the advisor)</td><td>yes ({@code Y})</td></tr>
 *   <tr><td>{@code fought}</td><td>交战</td><td>{@code 与X大战|交战|决战|战} / {@code 大破|击败|大败|攻破 X},
 *       rejected when the name before the match is another known person (distant-supervision guard)</td>
 *       <td>yes</td></tr>
 *   <tr><td>{@code killed}</td><td>斩杀</td><td>{@code 为X所杀} (X killed the owner) /
 *       {@code 斩杀|刺杀|杀死|诛杀|诱杀 X}</td><td>yes</td></tr>
 * </table>
 *
 * <p>Deliberately <b>left out</b>: place extraction (too noisy without a gazetteer) and
 * anything that needs coreference or syntax. Where a sentence's subject is not the
 * document's own person, the hit is attributed to that person anyway — this is
 * distant supervision, so the counts are an upper bound and the provenance is kept for
 * review.
 */
public final class SanguoTextExtractor {

    /** One extracted triple with its provenance. */
    public record Extracted(String relation, String head, String tail, String source, String sentence) {

        /** {@code document}:{@code field} — which free-text column it came from. */
        public String provenance() {
            return source + " «" + sentence + "»";
        }
    }

    /** What a run found. */
    public record Result(List<Extracted> relations, Map<String, Integer> countByRule,
                         Map<String, List<String>> samplesByRule,
                         Set<String> newPositions, Set<String> newPersons) {

        /** The relations of one rule. */
        public List<Extracted> of(String relation) {
            return relations.stream().filter(r -> r.relation().equals(relation)).toList();
        }
    }

    /** Office-name suffixes; a {@code holds} candidate must end with one of them. */
    private static final String TITLE_SUFFIX =
            "将军|校尉|太守|刺史|中郎将|都尉|侯|王|丞相|相国|尚书|令|都督|从事|主簿|长史|功曹|太傅|太尉|侍中|常侍|护军|监军|太常|郡守";

    /** Words that must never be treated as a name. */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "[0-9０-９]|(?:" + TITLE_SUFFIX + ")$|郡$|州$|城$|关$|县$|山$|水$|军$|众$|人$|等$|之$|其$|后$|中$|上$|下$|氏$"
                    + "|臣$|将$|相$|官$|吏$|市$|门$");

    /** Pronouns / collectives that are not entities. */
    private static final Pattern PRONOUN = Pattern.compile(
            "之|其|等|此|是|了|与|和|被|为|所|众|一族|家族|众人|手下|左右|宦官|军队|黄巾|先主|后主|主公|敌军|大军"
                    + "|之一|其中|以上|以下");

    /** A name candidate must not start with a collective prefix or a fragment. */
    private static final Pattern BAD_NAME_HEAD =
            Pattern.compile("^(将|军|兵|众|大小|前|后|左|右|东|西|南|北|上|下|中|其|此|各|诸|"
                    + "一|二|三|四|五|六|七|八|九|十|百|千|万|数|几|于|在|至|自|从|由|因)");

    /** A title candidate must not start with one of these (it would be a verb or a fragment). */
    private static final Pattern BAD_TITLE_HEAD =
            Pattern.compile("^(之|为|修|拜|以|其|汉|加|授|表|除|领|任|迁|封|进|是)");

    private static final Pattern TITLE = Pattern.compile(
            "(?:为|拜|封|迁|领|任|加|授|表|除)([\\u4e00-\\u9fa5]{2,6}?(?:" + TITLE_SUFFIX + "))");

    private static final Pattern STRATEGIST_JOIN = Pattern.compile("投效(?:与|于)([\\u4e00-\\u9fa5]{2,4})");
    /** Right after a known person: {@code 谋士<name>} — the <em>name</em> is the advisor. */
    private static final Pattern ADVISOR_OTHER_AFTER =
            Pattern.compile("^(?:帐下|之|的)?谋士([\\u4e00-\\u9fa5]{2,4})");
    /** Right after a known person: {@code 谋士} with no name after — the document's own person advises. */
    private static final Pattern ADVISOR_OWN_AFTER =
            Pattern.compile("^(?:帐下|之|的)?谋士(?:之一|其中)?(?![\\u4e00-\\u9fa5])");
    private static final Pattern FOUGHT_WITH = Pattern.compile("(?:与|和)([\\u4e00-\\u9fa5]{2,4}?)(?:大战|交战|决战|战)");
    private static final Pattern FOUGHT_BEAT = Pattern.compile("(?:大破|击败|大败|攻破)([\\u4e00-\\u9fa5]{2,4}?)(?=[，。、]|$)");
    private static final Pattern KILLED_BY = Pattern.compile("为([\\u4e00-\\u9fa5]{2,4})所杀");
    private static final Pattern KILLED = Pattern.compile("(?:斩杀|斩|诛杀|诛|刺杀|杀死|诱杀)([\\u4e00-\\u9fa5]{2,4}?)(?=[，。、]|$)");

    /**
     * The biographies abbreviate a person to one character once the surname is clear
     * ({"忠与羽连战"} = 黄忠 vs 关羽). This table plus {@link #VERB_START} turns such a fragment
     * back into the full name — a documented alias rule, not a guess.
     */
    private static final Map<Character, String> ALIAS = Map.ofEntries(
            Map.entry('羽', "关羽"), Map.entry('飞', "张飞"), Map.entry('备', "刘备"), Map.entry('操', "曹操"),
            Map.entry('权', "孙权"), Map.entry('亮', "诸葛亮"), Map.entry('懿', "司马懿"), Map.entry('维', "姜维"),
            Map.entry('超', "马超"), Map.entry('忠', "黄忠"), Map.entry('云', "赵云"), Map.entry('布', "吕布"),
            Map.entry('绍', "袁绍"), Map.entry('术', "袁术"), Map.entry('策', "孙策"), Map.entry('瑜', "周瑜"),
            Map.entry('蒙', "吕蒙"), Map.entry('逊', "陆逊"), Map.entry('盖', "黄盖"), Map.entry('统', "庞统"));

    /** What may follow an abbreviated name without being part of it. */
    private static final Pattern VERB_START = Pattern.compile(
            "^(连|又|遂|乃|大|与|和|战|破|攻|围|退|走|率|引|自|从|为|以|于|在|至|入|出|降|死|亡|军|兵|众|等|亦|不|即|所|因|由|欲|将|使|令|屯|守|据|奔|袭|击|败|胜|杀|斩|诛|弃|归|投|请|遣|召|拜|封|迁|领|任|加|授|表|除).*");

    /** Strips a collective prefix and resolves a one-character alias back to the full name. */
    private String normaliseName(String name) {
        String stripped = name.replaceFirst("^[曹魏吴蜀汉]将", "");
        if (stripped.length() >= 2 && ALIAS.containsKey(stripped.charAt(0))
                && VERB_START.matcher(stripped.substring(1)).find()) {
            return ALIAS.get(stripped.charAt(0));
        }
        return stripped;
    }

    private final SanguoDataset base;
    private final List<String> gazetteer;
    private final Map<String, SanguoDataset.EntityType> types;

    private SanguoTextExtractor(SanguoDataset base) {
        this.base = base;
        this.gazetteer = base.entities().keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        Map<String, SanguoDataset.EntityType> map = new LinkedHashMap<>();
        base.entities().forEach((name, entity) -> map.put(name, entity.type()));
        this.types = map;
    }

    /** Runs every rule over the documents of {@code base}. */
    public static Result extract(SanguoDataset base) {
        return new SanguoTextExtractor(base).run();
    }

    private Result run() {
        List<Extracted> relations = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> samples = new LinkedHashMap<>();
        Set<String> newPositions = new LinkedHashSet<>();
        Set<String> newPersons = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();

        for (SanguoDataset.Document document : base.documents()) {
            for (String[] field : new String[][] {{"历史", document.history()}, {"小说", document.novel()}}) {
                if (field[1] == null || field[1].isEmpty()) {
                    continue;
                }
                for (String sentence : field[1].split("[。！？；]")) {
                    String text = sentence.strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    scan(document.name(), field[0], text, relations, counts, samples, newPositions, newPersons, seen);
                }
            }
        }
        return new Result(List.copyOf(relations), Map.copyOf(counts), Map.copyOf(samples),
                Set.copyOf(newPositions), Set.copyOf(newPersons));
    }

    private void scan(String owner, String field, String sentence, List<Extracted> relations,
                      Map<String, Integer> counts, Map<String, List<String>> samples,
                      Set<String> newPositions, Set<String> newPersons, Set<String> seen) {
        String source = owner + ":" + field;
        List<String> mentioned = mention(sentence, owner);

        // 1) mentions — a known entity name occurs in the sentence
        for (String other : mentioned) {
            add("mentions", owner, other, source, sentence, relations, counts, samples, seen, false,
                    newPositions, newPersons);
        }

        // 2) 五虎 — the pattern the manual supplement (方案 B) had to be told by hand
        if (sentence.contains("五虎")) {
            add("holds", owner, "五虎上将", source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
            if (sentence.matches(".*(之首|为首|第一|居首).*")) {
                add("holds", owner, "五虎上将之首", source, sentence, relations, counts, samples, seen, true,
                        newPositions, newPersons);
            }
        }

        // 3) 军师 / 谋士 — anchored on the gazetteer: the lord must be a KNOWN person name,
        //    and the phrase must sit immediately after it (this is what keeps "迁为前军师" out)
        for (String lord : mentioned) {
            if (!isPerson(lord)) {
                continue;
            }
            int at = sentence.indexOf(lord);
            while (at >= 0) {
                String after = sentence.substring(at + lord.length());
                if (after.startsWith("辟为军师") || after.startsWith("拜为军师")
                        || after.matches("^(之|帐下|的)?军师.*")) {
                    add("strategistOf", owner, lord, source, sentence, relations, counts, samples, seen, false,
                            newPositions, newPersons);
                }
                Matcher other = ADVISOR_OTHER_AFTER.matcher(after);
                if (other.find() && plausibleName(other.group(1))) {
                    add("advisorOf", other.group(1), lord, source, sentence, relations, counts, samples, seen, true,
                            newPositions, newPersons);
                } else if (ADVISOR_OWN_AFTER.matcher(after).find()) {
                    add("advisorOf", owner, lord, source, sentence, relations, counts, samples, seen, false,
                            newPositions, newPersons);
                }
                at = sentence.indexOf(lord, at + 1);
            }
        }
        // “投效与X … 军师” — the document's own person becomes X's strategist
        if (sentence.contains("军师")) {
            for (Matcher m = STRATEGIST_JOIN.matcher(sentence); m.find(); ) {
                if (isPerson(m.group(1))) {
                    add("strategistOf", owner, m.group(1), source, sentence, relations, counts, samples, seen, false,
                            newPositions, newPersons);
                }
            }
        }

        // 4) holds — office titles, minus the ones that belong to somebody else in the sentence
        for (Matcher m = TITLE.matcher(sentence); m.find(); ) {
            String title = m.group(1);
            if (BAD_TITLE_HEAD.matcher(title).find() || !plausibleTitle(title)) {
                continue;
            }
            if (knownPersonAt(sentence, m.end()) != null) {
                continue; // "为河内太守王匡从事" — the office is 王匡's, not the owner's
            }
            String before = sentence.substring(0, m.start());
            if (!before.contains(owner) && !before.matches("^(初|后|迁|拜|封|领|任|加|授|除|以).*")) {
                continue;
            }
            newPositions.add(title);
            add("holds", owner, title, source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
        }

        // 5) fought
        for (Matcher m = FOUGHT_WITH.matcher(sentence); m.find(); ) {
            String other = m.group(1);
            String preceding = knownPersonAt(sentence, m.start());
            if (PRONOUN.matcher(other).find() || (preceding != null && !preceding.equals(owner))) {
                continue;
            }
            add("fought", owner, personTarget(other), source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
        }
        for (Matcher m = FOUGHT_BEAT.matcher(sentence); m.find(); ) {
            String other = m.group(1);
            if (PRONOUN.matcher(other).find() || isKnownNonPerson(other)) {
                continue;
            }
            add("fought", owner, personTarget(other), source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
        }

        // 6) killed — "为X所杀" puts X first, "斩杀X" keeps the owner first
        for (Matcher m = KILLED_BY.matcher(sentence); m.find(); ) {
            String other = m.group(1);
            if (PRONOUN.matcher(other).find()) {
                continue;
            }
            add("killed", personTarget(other), owner, source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
        }
        for (Matcher m = KILLED.matcher(sentence); m.find(); ) {
            String other = m.group(1);
            if (PRONOUN.matcher(other).find()) {
                continue;
            }
            add("killed", owner, personTarget(other), source, sentence, relations, counts, samples, seen, true,
                    newPositions, newPersons);
        }
    }

    private void add(String relation, String head, String tail, String source, String sentence,
                     List<Extracted> relations, Map<String, Integer> counts, Map<String, List<String>> samples,
                     Set<String> seen, boolean mayBeNew, Set<String> newPositions, Set<String> newPersons) {
        if (head == null || tail == null || head.isEmpty() || tail.isEmpty() || head.equals(tail)) {
            return;
        }
        if (!seen.add(relation + "|" + head + "|" + tail)) {
            return;
        }
        relations.add(new Extracted(relation, head, tail, source, sentence));
        counts.merge(relation, 1, Integer::sum);
        List<String> sample = samples.computeIfAbsent(relation, key -> new ArrayList<>());
        if (sample.size() < 8) {
            sample.add(head + " --" + relation + "--> " + tail + "  «" + brief(sentence) + "»");
        }
        if (mayBeNew && plausibleName(tail) && !types.containsKey(tail)) {
            if (relation.equals("holds")) {
                newPositions.add(tail);
            } else {
                newPersons.add(tail);
            }
        }
    }

    // --- gazetteer helpers ---

    /** The known entity names occurring in the sentence (longest match), excluding {@code owner}. */
    private List<String> mention(String sentence, String owner) {
        Set<String> found = new LinkedHashSet<>();
        int i = 0;
        while (i < sentence.length()) {
            String matched = null;
            for (String name : gazetteer) {
                if (name.length() > 1 && sentence.startsWith(name, i)) {
                    matched = name;
                    break;
                }
            }
            if (matched != null) {
                if (!matched.equals(owner)) {
                    found.add(matched);
                }
                i += matched.length();
            } else {
                i++;
            }
        }
        return List.copyOf(found);
    }

    /** The known person name ending exactly at {@code end} (2..4 chars back), or {@code null}. */
    private String knownPersonAt(String sentence, int end) {
        for (int length = Math.min(4, end); length >= 2; length--) {
            String candidate = sentence.substring(end - length, end);
            if (types.containsKey(candidate) && isPerson(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A known person name contained in {@code text}, else the normalised {@code text} itself. */
    private String personInside(String text) {
        for (String name : gazetteer) {
            if (name.length() >= 2 && isPerson(name) && text.contains(name)) {
                return name;
            }
        }
        return normaliseName(text);
    }

    /**
     * The person a slot names: a known person, or a plausible <em>new</em> name; {@code null}
     * when neither ({@code 过五关斩六将} must not become a person called 六将).
     */
    private String personTarget(String raw) {
        String name = personInside(raw);
        if (types.containsKey(name)) {
            return isPerson(name) ? name : null;
        }
        return plausibleName(name) ? name : null;
    }

    private boolean isPerson(String name) {
        SanguoDataset.EntityType type = types.get(name);
        return type != null && type.isPerson();
    }

    private boolean isKnownNonPerson(String name) {
        SanguoDataset.EntityType type = types.get(name);
        return type != null && !type.isPerson();
    }

    /** A title must not swallow a known entity name (e.g. {@code 荆州牧刘表从事}). */
    private boolean plausibleTitle(String title) {
        if (title.length() < 2) {
            return false;
        }
        for (String name : types.keySet()) {
            if (name.length() > 1 && title.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private boolean plausibleName(String name) {
        return name.length() >= 2 && name.length() <= 4
                && !NOT_A_NAME.matcher(name).find()
                && !PRONOUN.matcher(name).find()
                && !BAD_NAME_HEAD.matcher(name).find();
    }

    private static String brief(String sentence) {
        return sentence.length() <= 46 ? sentence : sentence.substring(0, 46) + "…";
    }
}
