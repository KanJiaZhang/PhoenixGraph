package com.pengcheng.phoenixgraph.integration.sanguo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The RDF view of the 《三国演义》 knowledge graph: a tiny in-memory model plus
 * N-Triples and Turtle writers.
 *
 * <p>The pipeline produces two graphs (see {@link SanguoRdfGenerator}):
 *
 * <ul>
 *   <li>the <b>TBox</b> — the entity / relation split of {@link SanguoOntology}
 *   as {@code owl:Class} and {@code owl:ObjectProperty} statements with Chinese
 *   labels, {@code rdfs:subPropertyOf}, {@code owl:inverseOf},
 *   {@code rdfs:domain}/{@code rdfs:range} and the OWL characteristics, plus the
 *   {@code sg:relationName} / {@code sg:relationFamily} annotations the property
 *   graph stores on its edges;</li>
 *   <li>the <b>ABox</b> — one {@code rdf:type} statement per entity, the person
 *   attributes ({@code rdfs:label}, {@code sg:courtesyName}, {@code sg:historicity},
 *   {@code sg:historicalDescription}, {@code sg:novelDescription}) and every
 *   relation triple, with the ontology's inverse / symmetric rules materialized.</li>
 * </ul>
 *
 * <p>Entities are IRIs: Chinese names are percent-encoded in N-Triples and kept
 * as readable {@code sg:刘备} prefixed names in Turtle whenever the name is a
 * legal Turtle {@code PN_LOCAL} ({@code 第1回} is, a place such as
 * {@code 豫州沛国谯(安徽亳州市亳县)} is not and falls back to the full IRI).
 */
public final class SanguoRdf {

    /** The {@code rdf:type} predicate. */
    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /** The {@code rdfs:label} predicate. */
    public static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    /** One RDF term: an IRI or a literal. */
    public sealed interface Term permits Iri, Literal {
    }

    /** An IRI term. */
    public record Iri(String value) implements Term {
    }

    /** A literal term with an optional language tag or datatype IRI. */
    public record Literal(String value, String language, String datatype) implements Term {

        public static Literal of(String value) {
            return new Literal(value, null, null);
        }

        public static Literal lang(String value, String language) {
            return new Literal(value, language, null);
        }

        public static Literal typed(String value, String datatype) {
            return new Literal(value, null, datatype);
        }
    }

    /** One triple; subject and predicate are always IRIs. */
    public record Triple(String subject, String predicate, Term object) {
    }

    private SanguoRdf() {
    }

    /** The IRI of a local name in the PhoenixGraph knowledge-graph namespace. */
    public static String iri(String localName) {
        return SanguoOntology.NAMESPACE + escapeIriRef(localName);
    }

    /** The IRI of a relation property. */
    public static String propertyIri(String relation) {
        return SanguoOntology.NAMESPACE + relation;
    }

    // --- TBox ---

    /** Builds the ontology (TBox) triples of the entity / relation split. */
    public static List<Triple> ontologyTriples(SanguoOntology ontology) {
        List<Triple> triples = new ArrayList<>();
        String sg = SanguoOntology.NAMESPACE;
        triples.add(new Triple(sg, RDF_TYPE, new Iri("http://www.w3.org/2002/07/owl#Ontology")));
        triples.add(new Triple(sg, RDFS_LABEL, Literal.lang("三国演义知识图谱本体", "zh")));

        for (String className : ontology.classes()) {
            String classIri = sg + className;
            triples.add(new Triple(classIri, RDF_TYPE, new Iri("http://www.w3.org/2002/07/owl#Class")));
            triples.add(new Triple(classIri, RDFS_LABEL, Literal.lang(ontology.classLabel(className), "zh")));
            if (className.equals("Male") || className.equals("Female")) {
                triples.add(new Triple(classIri,
                        "http://www.w3.org/2000/01/rdf-schema#subClassOf", new Iri(sg + "Person")));
            }
        }
        // the source ontology calls the classes male/female; keep the link
        triples.add(new Triple(sg + "male", "http://www.w3.org/2002/07/owl#sameAs", new Iri(sg + "Male")));
        triples.add(new Triple(sg + "female", "http://www.w3.org/2002/07/owl#sameAs", new Iri(sg + "Female")));

        for (SanguoOntology.Property property : ontology.properties()) {
            String iri = propertyIri(property.name());
            triples.add(new Triple(iri, RDF_TYPE, new Iri("http://www.w3.org/2002/07/owl#ObjectProperty")));
            triples.add(new Triple(iri, RDFS_LABEL, Literal.lang(property.label(), "zh")));
            triples.add(new Triple(iri, sg + "relationName", Literal.of(property.name())));
            triples.add(new Triple(iri, sg + "relationFamily",
                    Literal.lang(ontology.relationFamilyLabel(property.name()), "zh")));
            if (property.superProperty() != null) {
                triples.add(new Triple(iri, "http://www.w3.org/2000/01/rdf-schema#subPropertyOf",
                        new Iri(propertyIri(property.superProperty()))));
            }
            if (!ontology.inverse(property.name()).equals(property.name())) {
                triples.add(new Triple(iri, "http://www.w3.org/2002/07/owl#inverseOf",
                        new Iri(propertyIri(ontology.inverse(property.name())))));
            }
            if (property.domain() != null) {
                triples.add(new Triple(iri, "http://www.w3.org/2000/01/rdf-schema#domain",
                        new Iri(sg + className(property.domain()))));
            }
            if (property.range() != null) {
                triples.add(new Triple(iri, "http://www.w3.org/2000/01/rdf-schema#range",
                        new Iri(sg + className(property.range()))));
            }
            for (String characteristic : property.characteristics()) {
                triples.add(new Triple(iri, RDF_TYPE, new Iri("http://www.w3.org/2002/07/owl#" + characteristic)));
            }
        }
        // the person attributes are datatype properties: declared, but not relations
        for (Map.Entry<String, String> entry : ontology.datatypeProperties().entrySet()) {
            String iri = propertyIri(entry.getKey());
            triples.add(new Triple(iri, RDF_TYPE, new Iri("http://www.w3.org/2002/07/owl#DatatypeProperty")));
            triples.add(new Triple(iri, RDFS_LABEL, Literal.lang(entry.getValue(), "zh")));
            triples.add(new Triple(iri, "http://www.w3.org/2000/01/rdf-schema#domain", new Iri(sg + "Person")));
            triples.add(new Triple(iri, "http://www.w3.org/2000/01/rdf-schema#range",
                    new Iri("http://www.w3.org/2001/XMLSchema#string")));
        }
        return triples;
    }

    private static String className(String ontologyClass) {
        return switch (ontologyClass) {
            case "male" -> "Male";
            case "female" -> "Female";
            default -> ontologyClass;
        };
    }

    // --- ABox ---

    /** Builds the instance (ABox) triples: entity types, person attributes and relations. */
    public static List<Triple> instanceTriples(SanguoDataset dataset) {
        List<Triple> triples = new ArrayList<>();
        SanguoOntology ontology = dataset.ontology();
        String sg = SanguoOntology.NAMESPACE;

        for (SanguoDataset.Entity entity : dataset.entities().values()) {
            triples.add(new Triple(iri(entity.name()), RDF_TYPE,
                    new Iri(sg + entity.type().rdfClass())));
        }
        for (SanguoDataset.Document document : dataset.documents()) {
            String subject = iri(document.name());
            triples.add(new Triple(subject, RDFS_LABEL, Literal.lang(document.name(), "zh")));
            if (!document.courtesyName().isEmpty()) {
                triples.add(new Triple(subject, sg + "courtesyName", Literal.lang(document.courtesyName(), "zh")));
            }
            if (!document.historicity().isEmpty()) {
                triples.add(new Triple(subject, sg + "historicity", Literal.lang(document.historicity(), "zh")));
            }
            if (!document.history().isEmpty()) {
                triples.add(new Triple(subject, sg + "historicalDescription", Literal.lang(trim(document.history()), "zh")));
            }
            if (!document.novel().isEmpty()) {
                triples.add(new Triple(subject, sg + "novelDescription", Literal.lang(trim(document.novel()), "zh")));
            }
        }
        for (SanguoDataset.Relation relation : dataset.closedRelations()) {
            triples.add(new Triple(iri(relation.head()), propertyIri(relation.relation()), new Iri(iri(relation.tail()))));
        }
        // the ontology is referenced by the ABox, so a consumer can resolve the properties
        triples.add(new Triple(sg, RDFS_LABEL, Literal.lang("三国演义知识图谱", "zh")));
        return dedupe(triples);
    }

    private static String trim(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    private static List<Triple> dedupe(List<Triple> triples) {
        Set<String> seen = new LinkedHashSet<>();
        List<Triple> unique = new ArrayList<>(triples.size());
        for (Triple triple : triples) {
            if (seen.add(key(triple))) {
                unique.add(triple);
            }
        }
        return unique;
    }

    private static String key(Triple triple) {
        return triple.subject() + " " + triple.predicate() + " " + format(triple.object(), false);
    }

    // --- writers ---

    /** Serializes triples as N-Triples (one statement per line, UTF-8 percent-encoded IRIs). */
    public static String toNTriples(List<Triple> triples) {
        StringBuilder out = new StringBuilder();
        for (Triple triple : triples) {
            out.append('<').append(triple.subject()).append("> <").append(triple.predicate())
                    .append("> ").append(format(triple.object(), false)).append(" .\n");
        }
        return out.toString();
    }

    /** Serializes triples as Turtle, grouping by subject and shortening term IRIs to {@code sg:local}. */
    public static String toTurtle(List<Triple> triples) {
        Map<String, List<Triple>> bySubject = new LinkedHashMap<>();
        for (Triple triple : triples) {
            bySubject.computeIfAbsent(triple.subject(), k -> new ArrayList<>()).add(triple);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Triple>> entry : bySubject.entrySet()) {
            String subject = term(entry.getKey(), true);
            List<Triple> statements = entry.getValue();
            for (int i = 0; i < statements.size(); i++) {
                Triple triple = statements.get(i);
                out.append(i == 0 ? subject + " " : "    ");
                out.append(term(triple.predicate(), true)).append(' ')
                        .append(format(triple.object(), true))
                        .append(i == statements.size() - 1 ? " ." : " ;").append('\n');
            }
        }
        return out.toString();
    }

    /** The Turtle prologue shared by both documents. */
    public static String prefixes() {
        return "@prefix sg:   <" + SanguoOntology.NAMESPACE + "> .\n"
                + "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n"
                + "@prefix dcterms: <http://purl.org/dc/terms/> .\n";
    }

    private static String format(Term term, boolean turtle) {
        if (term instanceof Iri iri) {
            return term(iri.value(), turtle);
        }
        Literal literal = (Literal) term;
        StringBuilder out = new StringBuilder("\"").append(escape(literal.value())).append('"');
        if (literal.language() != null) {
            out.append('@').append(literal.language());
        } else if (literal.datatype() != null) {
            out.append("^^<").append(literal.datatype()).append('>');
        }
        return out.toString();
    }

    /** An IRI as {@code sg:local} when it is safe, otherwise the full {@code <...>} form. */
    private static String term(String iri, boolean turtle) {
        if (turtle && iri.startsWith(SanguoOntology.NAMESPACE)) {
            String local = iri.substring(SanguoOntology.NAMESPACE.length());
            if (isQNameSafe(local)) {
                return "sg:" + local;
            }
        }
        return "<" + iri + ">";
    }

    /** Whether a local name is a legal Turtle {@code PN_LOCAL} (so {@code sg:刘备} can be written). */
    static boolean isQNameSafe(String local) {
        if (local.isEmpty()) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '\u00B7'
                    || (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Escapes only what an N-Triples {@code IRIREF} forbids; Chinese characters stay
     * verbatim, which keeps the names readable ({@code sg:刘备}, {@code sg:第49回}).
     */
    static String escapeIriRef(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            boolean forbidden = codePoint <= 0x20
                    || codePoint == '<' || codePoint == '>' || codePoint == '"' || codePoint == '{'
                    || codePoint == '}' || codePoint == '|' || codePoint == '^' || codePoint == '`'
                    || codePoint == '\\';
            if (forbidden) {
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else {
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** Writes a text file, creating parents. */
    public static void write(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIo(e);
        }
    }

    /** An unchecked wrapper so the writers can be used in lambdas. */
    public static final class UncheckedIo extends RuntimeException {
        public UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}
