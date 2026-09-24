package com.pengcheng.phoenixgraph.integration.sanguo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The 《三国演义》 document dataset, parsed into the <em>entity and relation
 * split</em> the knowledge graph is built from.
 *
 * <p>Two inputs are combined (see {@code src/test/resources/kg/sanguo/README.md}):
 *
 * <ol>
 *   <li><b>331 person documents</b> ({@code renwu_process_v1.csv}) — one row per
 *   person, the field values are the "document text" this parser reads: 名字
 *   (name), 字号 (courtesy name), 性别 (gender), 正史 (historical/fictional),
 *   籍贯 (native place), 官职 (offices), 效力 (allegiances), 章节列表 (chapters
 *   the person appears in) and 历史/小说 (biographies);</li>
 *   <li><b>153 curated relation triples</b> ({@code triples.csv}) — the relation
 *   extraction shipped with the dataset, {@code "head","tail","relation","label"}.</li>
 * </ol>
 *
 * <p>The parser then derives the document links the upstream project extracted
 * with a separate script (and that this pipeline wants to own): 父亲/母亲 →
 * {@code father}/{@code mother}, 兄弟 → {@code brother}/{@code sister}, 配偶 →
 * {@code husband}/{@code wife}, 孩子 → {@code son}/{@code daughter}, 效力 →
 * {@code serves}, 官职 → {@code holds}, 籍贯 → {@code nativePlace}, 章节列表 →
 * {@code appearsIn}. Placeholder values ({@code 未知}, {@code 暂无相关记载},
 * {@code ？}, {@code 谥曰…}) and the upstream name-disambiguation suffix
 * ({@code 王业[魏]}) are dropped.
 *
 * <p>Entities are typed exactly the way the RDF view types them: a documented
 * person becomes {@code Male}/{@code Female}, a relation endpoint without a
 * document stays the untyped {@code Person}, and the document-attribute targets
 * become {@code Faction}/{@code Place}/{@code Position}/{@code Chapter}.
 */
public final class SanguoDataset {

    /** Directory (classpath) of the gzipped resources. */
    public static final String DEFAULT_DIRECTORY = "/kg/sanguo";

    /** Values that mean "the document does not say". */
    private static final Set<String> PLACEHOLDERS = Set.of("未知", "暂无相关记载", "？", "");

    /** The kind of a knowledge-graph entity. */
    public enum EntityType {
        MALE("Male"),
        FEMALE("Female"),
        /** A person that only appears as a relation endpoint (no document). */
        PERSON("Person"),
        FACTION("Faction"),
        PLACE("Place"),
        POSITION("Position"),
        CHAPTER("Chapter");

        private final String rdfClass;

        EntityType(String rdfClass) {
            this.rdfClass = rdfClass;
        }

        /** The RDF class / vertex `type` property value. */
        public String rdfClass() {
            return rdfClass;
        }

        public boolean isPerson() {
            return this == MALE || this == FEMALE || this == PERSON;
        }

        /** The type a documented person of the given 性别 has. */
        static EntityType ofGender(String gender) {
            return "女".equals(gender) ? FEMALE : MALE;
        }

        /** The entity type a document-attribute relation points at, or {@code null} for person-person relations. */
        static EntityType ofProperty(String relation) {
            return switch (relation) {
                case "serves" -> FACTION;
                case "holds" -> POSITION;
                case "nativePlace" -> PLACE;
                case "appearsIn" -> CHAPTER;
                default -> null;
            };
        }
    }

    /** Where a relation came from. */
    public enum RelationSource {
        /** The curated {@code triples.csv} shipped upstream. */
        CURATED,
        /** The local supplement {@code triples-extra.csv} (facts the upstream file left out). */
        EXTRA,
        /** Derived from a person document's field. */
        DOCUMENT,
        /** Materialized by the inverse rule of the ontology. */
        INVERSE,
        /** Materialized because the ontology declares the relation symmetric. */
        SYMMETRIC
    }

    /** One person document: the raw field values, placeholders already removed. */
    public record Document(String id, String name, String gender, String courtesyName, String historicity,
                           String nativePlace, String father, String mother, String brothers, String spouses,
                           String children, String offices, String allegiance, String chapters,
                           String history, String novel) {

        /** The RDF class of this person. */
        public EntityType entityType() {
            return EntityType.ofGender(gender);
        }

        /** {@code 男}/{@code 女} as the ontology's {@code male}/{@code female}. */
        public String ontologyGender() {
            return "女".equals(gender) ? "female" : "male";
        }
    }

    /** One directed relation {@code head --relation--> tail}. */
    public record Relation(String head, String relation, String tail, RelationSource source) {
    }

    /** One entity with its type. */
    public record Entity(String name, EntityType type) {
    }

    private final List<Document> documents;
    private final List<Relation> curatedRelations;
    private final List<Relation> extraRelations;
    private final List<Relation> relations;
    private final List<Relation> closedRelations;
    private final Map<String, Entity> entities;
    private final SanguoOntology ontology;

    private SanguoDataset(List<Document> documents, List<Relation> curatedRelations, List<Relation> extraRelations,
                          List<Relation> relations, List<Relation> closedRelations, Map<String, Entity> entities,
                          SanguoOntology ontology) {
        this.documents = List.copyOf(documents);
        this.curatedRelations = List.copyOf(curatedRelations);
        this.extraRelations = List.copyOf(extraRelations);
        this.relations = List.copyOf(relations);
        this.closedRelations = List.copyOf(closedRelations);
        // insertion order, not Map.copyOf: the RDF writers iterate the entities, and a
        // salted iteration order would make the generated files differ between JVM runs
        this.entities = Collections.unmodifiableMap(new LinkedHashMap<>(entities));
        this.ontology = ontology;
    }

    /** Loads the dataset bundled with the tests. */
    public static SanguoDataset load() {
        return load(DEFAULT_DIRECTORY, SanguoOntology.load());
    }

    /** Loads the dataset from a classpath directory, using the given ontology for closures. */
    public static SanguoDataset load(String directory, SanguoOntology ontology) {
        List<Document> documents = parseDocuments(readText(directory + "/renwu_process_v1.csv.gz"));
        List<Relation> curated = parseCuratedTriples(readText(directory + "/triples.csv.gz"));
        List<Relation> extra = parseExtraTriples(readText(directory + "/triples-extra.csv"));
        return of(documents, curated, extra, ontology);
    }

    /** Loads from plain files on disk (used by the RDF generator's {@code main}). */
    public static SanguoDataset loadFiles(Path documentCsv, Path tripleCsv, SanguoOntology ontology) {
        try {
            List<Document> documents = parseDocuments(Files.readString(documentCsv, StandardCharsets.UTF_8));
            List<Relation> curated = parseCuratedTriples(Files.readString(tripleCsv, StandardCharsets.UTF_8));
            Path extraCsv = tripleCsv.resolveSibling("triples-extra.csv");
            List<Relation> extra = Files.exists(extraCsv)
                    ? parseExtraTriples(Files.readString(extraCsv, StandardCharsets.UTF_8)) : List.of();
            return of(documents, curated, extra, ontology);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the dataset files", e);
        }
    }

    /** The whole parse with no local supplement. */
    public static SanguoDataset of(List<Document> documents, List<Relation> curated, SanguoOntology ontology) {
        return of(documents, curated, List.of(), ontology);
    }

    /**
     * The whole parse: documents + curated triples + the local supplement + document-derived
     * links + closures. Precedence is curated &gt; extra &gt; document.
     */
    public static SanguoDataset of(List<Document> documents, List<Relation> curated, List<Relation> extra,
                                   SanguoOntology ontology) {
        Map<String, String> gender = new LinkedHashMap<>();
        for (Document document : documents) {
            gender.put(document.name(), document.gender());
        }

        // dedupe by (head, relation, tail): the curated extraction wins, so a relation
        // confirmed by both the file and a document keeps its CURATED provenance
        Map<String, Relation> byKey = new LinkedHashMap<>();
        for (Relation relation : curated) {
            byKey.put(key(relation), relation);
        }
        // the local supplement fills facts the upstream file left out; it never overrides curated
        for (Relation relation : extra) {
            byKey.putIfAbsent(key(relation), relation);
        }

        List<Relation> documentRelations = new ArrayList<>();
        for (Document document : documents) {
            documentRelations.addAll(deriveRelations(document, gender));
        }

        // 效力 names a power, and some powers are named after their lord (袁绍, 刘表,
        // 董卓, 袁术, 刘璋). The same name is also a person, so the faction gets the
        // 势力 suffix: the IRI space stays injective and 董卓 does not serve himself.
        Set<String> personNames = new LinkedHashSet<>();
        for (Document document : documents) {
            personNames.add(document.name());
        }
        for (Relation relation : byKey.values()) {
            // only person-person relations contribute: an attribute relation's target is a
            // faction/place/office/chapter, and `mentions` may point at any of them
            if (isPersonRelation(ontology, relation.relation())) {
                personNames.add(relation.head());
                personNames.add(relation.tail());
            }
        }
        for (Relation relation : documentRelations) {
            if (isPersonRelation(ontology, relation.relation())) {
                personNames.add(relation.head());
                personNames.add(relation.tail());
            }
        }
        for (Relation relation : documentRelations) {
            Relation normalized = relation.relation().equals("serves")
                    ? new Relation(relation.head(), relation.relation(), factionName(relation.tail(), personNames),
                            relation.source())
                    : relation;
            byKey.putIfAbsent(key(normalized), normalized);
        }
        List<Relation> relations = List.copyOf(byKey.values());

        Map<String, Entity> entities = new LinkedHashMap<>();
        for (Document document : documents) {
            entities.put(document.name(), new Entity(document.name(), document.entityType()));
        }
        // two passes: an attribute relation (holds/serves/nativePlace/appearsIn) decides the type of
        // its target first, so a later person-person relation (mentions, fought, …) cannot turn
        // 中郎将 / 第1回 / 乌戈国 into a Person before the schema has had its say
        for (Relation relation : relations) {
            EntityType target = EntityType.ofProperty(relation.relation());
            if (target != null) {
                entities.putIfAbsent(relation.tail(), new Entity(relation.tail(), target));
            }
        }
        for (Relation relation : relations) {
            if (EntityType.ofProperty(relation.relation()) == null) {
                putPerson(entities, relation.head(), gender);
                putPerson(entities, relation.tail(), gender);
            }
        }

        // materialize the ontology rules, so the stored graph (and the RDF) answers a
        // relation from either endpoint and in both directions
        List<Relation> closed = new ArrayList<>(relations);
        Set<String> seen = new LinkedHashSet<>();
        for (Relation relation : relations) {
            seen.add(key(relation));
        }
        for (Relation relation : relations) {
            String inverse = ontology.inverse(relation.relation());
            if (!inverse.equals(relation.relation())) {
                Relation reversed = new Relation(relation.tail(), inverse, relation.head(), RelationSource.INVERSE);
                if (seen.add(key(reversed))) {
                    closed.add(reversed);
                }
            }
            SanguoOntology.Property property = ontology.property(relation.relation());
            if (property != null && property.symmetric()) {
                Relation mirrored = new Relation(relation.tail(), relation.relation(), relation.head(),
                        RelationSource.SYMMETRIC);
                if (seen.add(key(mirrored))) {
                    closed.add(mirrored);
                }
            }
        }
        return new SanguoDataset(documents, curated, extra, relations, closed, entities, ontology);
    }

    /** The person documents, in file order. */
    public List<Document> documents() {
        return documents;
    }

    /** The curated triples exactly as the upstream file contains them (duplicates included). */
    public List<Relation> curatedRelations() {
        return curatedRelations;
    }

    /** The local supplement ({@code triples-extra.csv}), in file order. */
    public List<Relation> extraRelations() {
        return extraRelations;
    }

    /** The relations parsed from the documents and the curated triples (before closure). */
    public List<Relation> relations() {
        return relations;
    }

    /** {@link #relations()} plus the materialized inverse / symmetric edges. */
    public List<Relation> closedRelations() {
        return closedRelations;
    }

    /** Every entity of the graph, by name. */
    public Map<String, Entity> entities() {
        return entities;
    }

    public SanguoOntology ontology() {
        return ontology;
    }

    /** The documents by person name. */
    public Map<String, Document> documentsByName() {
        Map<String, Document> byName = new LinkedHashMap<>();
        for (Document document : documents) {
            byName.put(document.name(), document);
        }
        return byName;
    }

    /** {@code 男}/{@code 女} for every documented person (the validated part of the gender map). */
    public Map<String, String> genderByName() {
        Map<String, String> gender = new LinkedHashMap<>();
        for (Document document : documents) {
            gender.put(document.name(), document.gender());
        }
        return gender;
    }

    // --- parsing ---

    /** Parses the person documents. */
    public static List<Document> parseDocuments(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) {
            throw new IllegalStateException("empty document CSV");
        }
        Map<String, Integer> header = Csv.header(rows.get(0));
        List<Document> documents = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String rawName = Csv.cell(row, header, "名字");
            if (rawName.isEmpty()) {
                continue;
            }
            // the upstream export disambiguates 28 homonyms as 名字[势力]; the suffix is a
            // type note, not part of the name. When two homonyms collapse onto the same
            // name the tag is kept in parentheses (郤俭 -> 郤俭（魏）, 郤俭（东汉）).
            String name = uniqueName(rawName, Csv.cell(row, header, "编号"), usedNames);
            documents.add(new Document(
                    Csv.cell(row, header, "编号"),
                    name,
                    Csv.cell(row, header, "性别"),
                    clean(Csv.cell(row, header, "字号")),
                    clean(Csv.cell(row, header, "正史")),
                    clean(Csv.cell(row, header, "籍贯")),
                    Csv.cell(row, header, "父亲"),
                    Csv.cell(row, header, "母亲"),
                    Csv.cell(row, header, "兄弟"),
                    Csv.cell(row, header, "配偶"),
                    Csv.cell(row, header, "孩子"),
                    Csv.cell(row, header, "官职"),
                    Csv.cell(row, header, "效力"),
                    Csv.cell(row, header, "章节列表"),
                    clean(Csv.cell(row, header, "历史")),
                    clean(Csv.cell(row, header, "小说"))));
        }
        return documents;
    }

    /** The entity name of a document: the bracket suffix becomes a parenthesized tag on a homonym. */
    private static String uniqueName(String rawName, String id, Set<String> usedNames) {
        int bracket = rawName.indexOf('[');
        String base = bracket > 0 ? rawName.substring(0, bracket).trim() : rawName.trim();
        String tag = bracket > 0 && rawName.endsWith("]")
                ? rawName.substring(bracket + 1, rawName.length() - 1).trim() : "";
        if (usedNames.add(base)) {
            return base;
        }
        String candidate = base + "（" + (tag.isEmpty() ? id : tag) + "）";
        while (!usedNames.add(candidate)) {
            candidate = candidate + "·";
        }
        return candidate;
    }

    /** Parses the curated triples. */
    public static List<Relation> parseCuratedTriples(String csv) {
        return parseTriples(csv, RelationSource.CURATED);
    }

    /**
     * Parses the local supplement. Lines whose first non-blank character is {@code #} are
     * comments and are dropped before the CSV reader sees them (the file has no multi-line
     * fields, so this cannot cut a quoted value in half).
     */
    public static List<Relation> parseExtraTriples(String csv) {
        StringBuilder stripped = new StringBuilder();
        for (String line : csv.split("\n", -1)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        return parseTriples(stripped.toString(), RelationSource.EXTRA);
    }

    private static List<Relation> parseTriples(String csv, RelationSource source) {
        List<List<String>> rows = Csv.parse(csv);
        Map<String, Integer> header = Csv.header(rows.get(0));
        List<Relation> relations = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String head = Csv.cell(row, header, "head");
            String tail = Csv.cell(row, header, "tail");
            String relation = Csv.cell(row, header, "relation");
            if (!head.isEmpty() && !tail.isEmpty() && !relation.isEmpty()) {
                relations.add(new Relation(head, relation, tail, source));
            }
        }
        return relations;
    }

    /** The relation triples a single person document implies. */
    private static List<Relation> deriveRelations(Document document, Map<String, String> gender) {
        List<Relation> relations = new ArrayList<>();
        String self = document.name();
        for (String father : values(document.father())) {
            relations.add(new Relation(father, "father", self, RelationSource.DOCUMENT));
        }
        for (String mother : values(document.mother())) {
            relations.add(new Relation(mother, "mother", self, RelationSource.DOCUMENT));
        }
        for (String sibling : values(document.brothers())) {
            // the documents do not order siblings; the gender picks brother/sister
            boolean sister = "女".equals(gender.get(sibling));
            relations.add(new Relation(sibling, sister ? "sister" : "brother", self, RelationSource.DOCUMENT));
        }
        for (String spouse : values(document.spouses())) {
            boolean male = !"女".equals(document.gender());
            relations.add(new Relation(self, male ? "husband" : "wife", spouse, RelationSource.DOCUMENT));
        }
        for (String child : values(document.children())) {
            boolean daughter = "女".equals(gender.get(child));
            relations.add(new Relation(child, daughter ? "daughter" : "son", self, RelationSource.DOCUMENT));
        }
        for (String faction : values(document.allegiance())) {
            relations.add(new Relation(self, "serves", faction, RelationSource.DOCUMENT));
        }
        for (String office : values(document.offices())) {
            relations.add(new Relation(self, "holds", office, RelationSource.DOCUMENT));
        }
        if (!PLACEHOLDERS.contains(document.nativePlace())) {
            relations.add(new Relation(self, "nativePlace", placeName(document.nativePlace()), RelationSource.DOCUMENT));
        }
        for (String chapter : values(document.chapters())) {
            relations.add(new Relation(self, "appearsIn", chapter, RelationSource.DOCUMENT));
        }
        return relations;
    }

    /**
     * Splits a {@code |}-separated document field, dropping placeholders, posthumous
     * names ({@code 谥曰…}) and the upstream {@code 名字[势力]} disambiguation suffix.
     */
    private static List<String> values(String field) {
        List<String> values = new ArrayList<>();
        for (String raw : field.split("\\|")) {
            String value = raw.trim();
            int bracket = value.indexOf('[');
            if (bracket > 0) {
                value = value.substring(0, bracket).trim();
            }
            if (value.isEmpty() || PLACEHOLDERS.contains(value) || value.startsWith("谥曰")) {
                continue;
            }
            values.add(value);
        }
        return values;
    }

    /**
     * Whether a relation links two <em>persons</em> — the only ones that can make a name
     * "also a person", which is what the {@code 势力} suffix of a faction is decided by.
     * Attribute relations ({@code serves}/{@code holds}/…) and the text-extraction
     * {@code mentions} point at factions, places, offices and chapters, so they must not.
     */
    private static boolean isPersonRelation(SanguoOntology ontology, String relation) {
        return EntityType.ofProperty(relation) == null && !relation.equals("mentions");
    }

    /** A power named after its lord becomes {@code 袁绍势力}, so it cannot clash with the person. */
    private static String factionName(String faction, Set<String> personNames) {
        return personNames.contains(faction) ? faction + "势力" : faction;
    }

    /**
     * The 籍贯 document field is "州郡(现代地名)"; the graph keeps the historical part
     * ({@code 豫州沛国谯}), which also merges places that only differ by the modern note.
     */
    private static String placeName(String nativePlace) {
        int parenthesis = nativePlace.indexOf('(');
        String place = parenthesis > 0 ? nativePlace.substring(0, parenthesis) : nativePlace;
        return place.trim();
    }

    private static String clean(String value) {
        return PLACEHOLDERS.contains(value) ? "" : value;
    }

    private static void putPerson(Map<String, Entity> entities, String name, Map<String, String> gender) {
        EntityType type = switch (gender.getOrDefault(name, "")) {
            case "女" -> EntityType.FEMALE;
            case "男" -> EntityType.MALE;
            default -> EntityType.PERSON;
        };
        entities.putIfAbsent(name, new Entity(name, type));
    }

    private static String key(Relation relation) {
        return relation.head() + "\u0000" + relation.relation() + "\u0000" + relation.tail();
    }

    private static String readText(String resource) {
        try (InputStream raw = SanguoDataset.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Missing dataset resource: " + resource
                        + " (see src/test/resources/kg/sanguo/README.md)");
            }
            InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }

    /** A small RFC 4180 reader: quoted fields, embedded commas / newlines / {@code ""}. */
    static final class Csv {

        private Csv() {
        }

        static List<List<String>> parse(String text) {
            String input = text.startsWith("\uFEFF") ? text.substring(1) : text;
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean started = false;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                            field.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                    started = true;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    started = true;
                } else if (c == '\n') {
                    row.add(field.toString());
                    field.setLength(0);
                    if (started || row.size() > 1) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    started = false;
                } else if (c != '\r') {
                    field.append(c);
                }
            }
            if (field.length() > 0 || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }

        static Map<String, Integer> header(List<String> row) {
            Map<String, Integer> header = new LinkedHashMap<>();
            for (int i = 0; i < row.size(); i++) {
                String name = row.get(i);
                if (name.startsWith("\uFEFF")) {
                    name = name.substring(1);
                }
                header.putIfAbsent(name.trim(), i);
            }
            return header;
        }

        static String cell(List<String> row, Map<String, Integer> header, String name) {
            Integer index = header.get(name);
            if (index == null || index >= row.size()) {
                return "";
            }
            return row.get(index).trim();
        }
    }
}
