package com.pengcheng.phoenixgraph.integration.sanguo;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The 《三国演义》 OWL ontology: the <em>entity / relation split</em> of the
 * knowledge graph.
 *
 * <p>Reads {@code theory.owl} — the ontology shipped with the OpenKG
 * <em>四大名著人物关系知识图谱和 OWL 本体</em> dataset (三国演义 part) — and turns it
 * into the two things the rest of the pipeline needs:
 *
 * <ul>
 *   <li><b>the entity split</b> — the classes {@code Male} / {@code Female} (the
 *   source ontology's only declared classes), re-homed under {@code Person}, plus
 *   the document-derived classes {@code Faction} (势力), {@code Place} (籍贯),
 *   {@code Position} (官职) and {@code Chapter} (章回);</li>
 *   <li><b>the relation split</b> — the 60 {@code owl:ObjectProperty} relations
 *   (local name, Chinese {@code rdfs:label}, {@code rdfs:subPropertyOf},
 *   {@code rdfs:domain}/{@code rdfs:range}, OWL characteristics) grouped into a
 *   coarse <em>relation family</em> used by the queries, plus the four
 *   document-derived properties {@code serves} / {@code holds} /
 *   {@code nativePlace} / {@code appearsIn}.</li>
 * </ul>
 *
 * <p>The ontology encodes inverses in two shapes — a direct
 * {@code owl:inverseOf} and an anonymous {@code rdfs:subPropertyOf} whose value
 * is the inverse ({@code wife ⊑ husband⁻}). {@link #inverse(String)} resolves
 * both into a single <b>involution</b> ({@code inverse(inverse(r)) == r}), which
 * is what lets {@code relation.equals(R) || xrelation.equals(R)} find a relation
 * from either endpoint; the documented "primary" term wins a conflict
 * ({@code husband} is the inverse of {@code wife}, so {@code concubine} stays its
 * own inverse).
 */
public final class SanguoOntology {

    /** Namespace of the PhoenixGraph knowledge-graph profile. */
    public static final String NAMESPACE = "http://pengcheng.com/phoenixgraph/kg/sanguo#";

    /** Namespace of the upstream OWL file (kept for provenance). */
    public static final String SOURCE_NAMESPACE =
            "http://www.semanticweb.org/shen-pc/ontologies/2018/3/untitled-ontology-61#";

    /** Classpath location of the gzipped ontology. */
    public static final String DEFAULT_RESOURCE = "/kg/sanguo/theory.owl.gz";

    /** Classes of the entity split, in the order they are documented. */
    public static final List<String> CLASSES =
            List.of("Person", "Male", "Female", "Faction", "Place", "Position", "Chapter");

    /**
     * The classes only the text extraction introduces (方案 ③). Like {@link #TEXT_PROPERTIES}
     * they are declared by {@link #withTextProfile()} alone, so the base graph's TBox is untouched.
     */
    private static final List<String> TEXT_CLASSES = List.of("Event", "Artifact", "Idiom");

    /** Chinese labels of {@link #CLASSES}. */
    private static final Map<String, String> CLASS_LABELS = Map.ofEntries(
            Map.entry("Person", "人物"), Map.entry("Male", "男性人物"), Map.entry("Female", "女性人物"),
            Map.entry("Faction", "势力"), Map.entry("Place", "籍贯地"), Map.entry("Position", "官职"),
            Map.entry("Chapter", "章回"),
            Map.entry("Event", "事件"), Map.entry("Artifact", "器物"), Map.entry("Idiom", "成语"));

    /** Properties derived from the person documents rather than from {@code theory.owl}. */
    private static final Map<String, String[]> EXTENSION_PROPERTIES = new LinkedHashMap<>();

    static {
        // name -> {label, domain, range}
        EXTENSION_PROPERTIES.put("serves", new String[] {"效力", "Person", "Faction"});
        EXTENSION_PROPERTIES.put("holds", new String[] {"官职", "Person", "Position"});
        EXTENSION_PROPERTIES.put("nativePlace", new String[] {"籍贯", "Person", "Place"});
        EXTENSION_PROPERTIES.put("appearsIn", new String[] {"出场", "Person", "Chapter"});
    }

    /**
     * Datatype properties: the person attributes the documents carry as text. They
     * are declared so that the TBox covers every predicate of the ABox, but they are
     * not relations (their object is a literal, not an entity).
     */
    private static final Map<String, String> ATTRIBUTE_PROPERTIES = new LinkedHashMap<>();

    static {
        ATTRIBUTE_PROPERTIES.put("courtesyName", "字");
        ATTRIBUTE_PROPERTIES.put("historicity", "正史与虚构");
        ATTRIBUTE_PROPERTIES.put("historicalDescription", "历史简介");
        ATTRIBUTE_PROPERTIES.put("novelDescription", "演义简介");
    }

    /**
     * Coarse relation families. The OWL hierarchy is deep (60 properties, up to
     * four {@code rdfs:subPropertyOf} levels); a query wants a handful of buckets,
     * so every property maps to one family name plus a Chinese label.
     */
    private static final Map<String, String> FAMILY_OF = new LinkedHashMap<>();

    static {
        for (String p : List.of("family", "relatives", "brother_and_sister", "brother", "sister", "same_clan",
                "uncle", "son_of_uncle", "brother_consin", "sister_and_brother_cousin", "sister_cousin",
                "son", "daughter", "father", "mother", "adoptive_father", "adopted_son", "adopted_daughter",
                "son_in_law", "mother_in_law", "sister_in_law", "husband_of_younger_sister", "elder_brother",
                "younger_brother", "elder_sister", "younger_sister", "elder_male_cousin", "younger_male_cousin",
                "elder_female_cousin")) {
            FAMILY_OF.put(p, "kindship");
        }
        for (String p : List.of("wife", "husband", "concubine", "fiance", "matchmaker")) {
            FAMILY_OF.put(p, "marriage");
        }
        for (String p : List.of("sworn", "elder_sworn_brother", "younger_sworn_brother", "younger_sworn_sister",
                "hand_in_hand", "old_friend", "townee", "fellow", "friend")) {
            FAMILY_OF.put(p, "friendship");
        }
        for (String p : List.of("lord", "minister", "princess")) {
            FAMILY_OF.put(p, "allegiance");
        }
        for (String p : List.of("master", "apprentice")) {
            FAMILY_OF.put(p, "mentorship");
        }
        for (String p : List.of("capitulate", "surrender", "advising")) {
            FAMILY_OF.put(p, "submission");
        }
        for (String p : List.of("recruit", "recommended")) {
            FAMILY_OF.put(p, "recruitment");
        }
        FAMILY_OF.put("serves", "service");
        FAMILY_OF.put("holds", "office");
        FAMILY_OF.put("nativePlace", "origin");
        FAMILY_OF.put("appearsIn", "appearance");
        // the text-extraction relations (方案 C, only present in the text profile)
        FAMILY_OF.put("mentions", "mention");
        FAMILY_OF.put("strategistOf", "mentorship");
        FAMILY_OF.put("advisorOf", "mentorship");
        FAMILY_OF.put("fought", "conflict");
        FAMILY_OF.put("killed", "conflict");
        FAMILY_OF.put("participatedIn", "event");
        FAMILY_OF.put("capital", "geography");
        FAMILY_OF.put("subordinate", "service");
    }

    /** Chinese labels of the families. */
    private static final Map<String, String> FAMILY_LABELS = Map.ofEntries(
            Map.entry("kindship", "亲属"), Map.entry("marriage", "婚姻"), Map.entry("friendship", "朋友"),
            Map.entry("allegiance", "君臣"), Map.entry("mentorship", "师承"), Map.entry("submission", "归降"),
            Map.entry("recruitment", "招募"), Map.entry("service", "效力"), Map.entry("office", "官职"),
            Map.entry("origin", "籍贯"), Map.entry("appearance", "出场"), Map.entry("other", "其他"),
            Map.entry("mention", "提及"), Map.entry("conflict", "冲突"), Map.entry("event", "事件"),
            Map.entry("geography", "地缘"));

    /**
     * The relations only the text extraction introduces (方案 C):
     * {@code name, 中文标签, domain, range}. {@code fought} is symmetric (the parser
     * materializes the mirror), {@code killed} is asymmetric.
     */
    private static final List<String[]> TEXT_PROPERTIES = List.of(
            new String[] {"mentions", "提及", null, null},
            new String[] {"strategistOf", "军师", "Person", "Person"},
            new String[] {"advisorOf", "谋士", "Person", "Person"},
            new String[] {"fought", "交战", "Person", "Person"},
            new String[] {"killed", "斩杀", "Person", "Person"},
            // 参战: 事件图谱 — 传记主人参与了某战役/事件（方案 ⑧）
            new String[] {"participatedIn", "参战", "Person", "Event"},
            // 都城: 策展边（外部知识）— X 的都城是 Y
            new String[] {"capital", "都城", null, "Place"},
            // 部将: 策展边（外部知识）— X 是 Y 的部将
            new String[] {"subordinate", "部将", "Person", "Person"});

    /** The family of the 方案 ③ allusion edges. They reuse the {@code mentions} relation. */
    public static final String ALLUSION_FAMILY = "allusion";

    /**
     * Preference when two properties claim the same inverse: the first name in
     * this list wins. {@code husband} is the inverse of {@code wife} (the primary
     * relation) even though {@code concubine} also declares it, so
     * {@code concubine} stays its own inverse and the table stays an involution.
     */
    private static final List<String> INVERSE_PREFERENCE = List.of(
            "wife", "husband", "apprentice", "master", "lord", "minister",
            "elder_brother", "younger_brother", "elder_sister", "younger_sister",
            "elder_sworn_brother", "younger_sworn_brother");

    /** Gender-safe inverse pairs the source ontology leaves implicit. */
    private static final List<String[]> CURATED_INVERSE_PAIRS = List.of(
            new String[] {"elder_brother", "younger_brother"},
            new String[] {"elder_sister", "younger_sister"},
            new String[] {"elder_sworn_brother", "younger_sworn_brother"});

    /** One relation of the split. */
    public record Property(String name, List<String> labels, String superProperty, String domain, String range,
                           Set<String> characteristics) {

        public Property {
            labels = List.copyOf(labels);
            // LinkedHashSet, not Set.copyOf: the characteristic order decides the order of the
            // rdf:type owl:...Property statements, and a salted order would break the frozen RDF
            characteristics = Collections.unmodifiableSet(new LinkedHashSet<>(characteristics));
        }

        /** The Chinese label, or the local name when the ontology has none. */
        public String label() {
            return labels.isEmpty() ? name : labels.get(0);
        }

        public boolean symmetric() {
            return characteristics.contains("SymmetricProperty");
        }

        public boolean irreflexive() {
            return characteristics.contains("IrreflexiveProperty");
        }

        public boolean asymmetric() {
            return characteristics.contains("AsymmetricProperty");
        }
    }

    private final List<Property> properties;
    private final List<String> classes;
    private final Map<String, Property> byName;
    private final Map<String, String> inverse;
    private final Map<String, String> declaredInverse;

    private SanguoOntology(List<Property> properties, Map<String, String> declaredInverse) {
        this(properties, CLASSES, declaredInverse);
    }

    private SanguoOntology(List<Property> properties, List<String> classes, Map<String, String> declaredInverse) {
        this.properties = List.copyOf(properties);
        this.classes = List.copyOf(classes);
        Map<String, Property> index = new LinkedHashMap<>();
        for (Property property : properties) {
            index.put(property.name(), property);
        }
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(index));
        this.declaredInverse = Collections.unmodifiableMap(new LinkedHashMap<>(declaredInverse));
        this.inverse = deriveInverses(index.keySet(), declaredInverse);
    }

    /** Loads the ontology bundled with the tests. */
    public static SanguoOntology load() {
        return load(DEFAULT_RESOURCE);
    }

    /** Loads the ontology from a classpath resource ({@code .gz} is decompressed). */
    public static SanguoOntology load(String resource) {
        try (InputStream raw = SanguoOntology.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Missing ontology resource: " + resource
                        + " (see src/test/resources/kg/sanguo/README.md)");
            }
            InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }

    /** Loads a plain {@code .owl} file from disk (used by the RDF generator's {@code main}). */
    public static SanguoOntology loadFile(Path owl) {
        try {
            return parse(Files.readString(owl, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + owl, e);
        }
    }

    /** Parses an OWL/XML document. */
    public static SanguoOntology parse(String owlXml) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            // the ontology is a local, trusted resource; no external entity resolution
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(owlXml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IllegalStateException("Failed to parse the OWL ontology", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse the OWL ontology", e);
        }

        List<Property> properties = new ArrayList<>();
        Map<String, String> declared = new LinkedHashMap<>();
        for (Element element : elements(document.getDocumentElement(), "owl:ObjectProperty")) {
            String name = localName(element.getAttribute("rdf:about"));
            if (name == null) {
                continue;
            }
            List<String> labels = new ArrayList<>();
            for (Element child : elements(element, "rdfs:label")) {
                labels.add(child.getTextContent().trim());
            }
            String superProperty = null;
            Set<String> characteristics = new LinkedHashSet<>();
            for (Element child : children(element)) {
                switch (child.getNodeName()) {
                    case "rdfs:subPropertyOf" -> {
                        String resource = localName(child.getAttribute("rdf:resource"));
                        if (resource != null) {
                            superProperty = resource;
                        } else {
                            // nested shape: wife ⊑ (inverseOf husband), the inverse sits in rdf:Description
                            for (Element description : elements(child, "rdf:Description")) {
                                for (Element nested : elements(description, "owl:inverseOf")) {
                                    String inverseOf = localName(nested.getAttribute("rdf:resource"));
                                    if (inverseOf != null) {
                                        declared.put(name, inverseOf);
                                    }
                                }
                            }
                        }
                    }
                    case "owl:inverseOf" -> {
                        // only a *direct* child counts; the same tag nested inside an
                        // owl:propertyChainAxiom describes a chain element, not an inverse
                        String inverseOf = localName(child.getAttribute("rdf:resource"));
                        if (inverseOf != null) {
                            declared.put(name, inverseOf);
                        }
                    }
                    case "rdf:type" -> {
                        if (child.getAttribute("rdf:resource").startsWith("http://www.w3.org/2002/07/owl#")) {
                            characteristics.add(localName(child.getAttribute("rdf:resource")));
                        }
                    }
                    default -> {
                        // rdfs:domain / rdfs:range are read below through the helper
                    }
                }
            }
            String domain = resource(element, "rdfs:domain");
            String range = resource(element, "rdfs:range");
            properties.add(new Property(name, labels, superProperty, domain, range, characteristics));
        }

        // document-derived properties, so that every predicate of the ABox is declared
        for (Map.Entry<String, String[]> entry : EXTENSION_PROPERTIES.entrySet()) {
            String[] spec = entry.getValue();
            properties.add(new Property(entry.getKey(), List.of(spec[0]), null, spec[1], spec[2], Set.of()));
        }
        return new SanguoOntology(properties, declared);
    }

    /** All relations of the split, in declaration order (the OWL file order, then the extensions). */
    public List<Property> properties() {
        return properties;
    }

    /**
     * The same ontology plus what the text extraction introduces (方案 C): five relations and
     * 方案 ③'s three classes. The base graph's TBox is untouched — only the text graph declares them.
     */
    public SanguoOntology withTextProfile() {
        List<Property> all = new ArrayList<>(properties);
        for (String[] spec : TEXT_PROPERTIES) {
            Set<String> characteristics = switch (spec[0]) {
                case "fought" -> Set.of("SymmetricProperty");
                case "killed" -> Set.of("AsymmetricProperty");
                default -> Set.of();
            };
            all.add(new Property(spec[0], List.of(spec[1]), null, spec[2], spec[3], characteristics));
        }
        List<String> withClasses = new ArrayList<>(classes);
        for (String name : TEXT_CLASSES) {
            if (!withClasses.contains(name)) {
                withClasses.add(name);
            }
        }
        return new SanguoOntology(all, withClasses, declaredInverse);
    }

    /** A relation by local name, or {@code null}. */
    public Property property(String name) {
        return byName.get(name);
    }

    /** The relation's Chinese label, or its local name when it is not declared. */
    public String label(String name) {
        Property property = byName.get(name);
        return property == null ? name : property.label();
    }

    /** The inverse of a relation; the relation itself when it has none. Always an involution. */
    public String inverse(String name) {
        return inverse.getOrDefault(name, name);
    }

    /** The relations whose inverse the ontology declares explicitly (name -> inverse). */
    public Map<String, String> declaredInverses() {
        return declaredInverse;
    }

    /** The coarse family of a relation (see {@link #familyLabel}). */
    public String family(String name) {
        String family = FAMILY_OF.get(name);
        if (family != null) {
            return family;
        }
        // fall back to the outermost rdfs:subPropertyOf, which the table also covers
        String current = name;
        Set<String> seen = new LinkedHashSet<>();
        while (seen.add(current)) {
            Property property = byName.get(current);
            if (property == null || property.superProperty() == null) {
                break;
            }
            current = property.superProperty();
        }
        return FAMILY_OF.getOrDefault(current, "other");
    }

    /** The Chinese label of a family. */
    public String familyLabel(String family) {
        return FAMILY_LABELS.getOrDefault(family, "其他");
    }

    /** The Chinese label of a family of a relation. */
    public String relationFamilyLabel(String name) {
        return familyLabel(family(name));
    }

    public List<String> classes() {
        return classes;
    }

    /** The declared datatype properties (person attributes), name -> Chinese label. */
    public Map<String, String> datatypeProperties() {
        return ATTRIBUTE_PROPERTIES;
    }

    /** Whether the TBox declares a predicate (relation or attribute). */
    public boolean isDeclared(String name) {
        return byName.containsKey(name) || ATTRIBUTE_PROPERTIES.containsKey(name);
    }

    public String classLabel(String name) {
        return CLASS_LABELS.getOrDefault(name, name);
    }

    // --- inverse resolution ---

    /**
     * Turns the declared (possibly conflicting) inverse hints plus the curated
     * sibling pairs into a single involution. Pairs are matched greedily in
     * {@link #INVERSE_PREFERENCE} order and a property that loses a conflict keeps
     * itself as its inverse.
     */
    private static Map<String, String> deriveInverses(Set<String> names, Map<String, String> declared) {
        Set<String> pairs = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String a = entry.getKey();
            String b = entry.getValue();
            if (!a.equals(b) && names.contains(b)) {
                pairs.add(a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a);
            }
        }
        for (String[] pair : CURATED_INVERSE_PAIRS) {
            String a = pair[0];
            String b = pair[1];
            if (names.contains(a) && names.contains(b)) {
                pairs.add(a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a);
            }
        }

        List<String[]> ordered = new ArrayList<>();
        for (String pair : pairs) {
            String[] parts = pair.split("\u0000", -1);
            ordered.add(parts);
        }
        ordered.sort(Comparator.comparingInt(p -> rank(p[0]) * 1000 + rank(p[1])));

        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, name);
        }
        Map<String, String> assigned = new LinkedHashMap<>();
        for (String[] pair : ordered) {
            String a = pair[0];
            String b = pair[1];
            if (!assigned.containsKey(a) && !assigned.containsKey(b)) {
                assigned.put(a, b);
                assigned.put(b, a);
            }
        }
        result.putAll(assigned);
        return result;
    }

    private static int rank(String name) {
        int index = INVERSE_PREFERENCE.indexOf(name);
        return index < 0 ? INVERSE_PREFERENCE.size() : index;
    }

    // --- DOM helpers ---

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static List<Element> elements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Element child : children(parent)) {
            if (child.getNodeName().equals(name)) {
                result.add(child);
            }
        }
        return result;
    }

    private static String resource(Element parent, String name) {
        List<Element> found = elements(parent, name);
        return found.isEmpty() ? null : localName(found.get(0).getAttribute("rdf:resource"));
    }

    private static String localName(String iri) {
        if (iri == null || iri.isEmpty()) {
            return null;
        }
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut < 0 ? iri : iri.substring(cut + 1);
    }
}
