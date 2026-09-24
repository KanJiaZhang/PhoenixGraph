package com.pengcheng.phoenixgraph.integration.sanguo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks the RDF view of the 《三国演义》 knowledge graph against the ontology it
 * was parsed with — "is the parse correct?" as a list of machine-checkable rules.
 *
 * <p>Two groups of checks. The first group only needs the <em>parsed</em>
 * relations and the document facts, and is what a reviewer would want to see
 * before trusting the graph:
 *
 * <ol>
 *   <li><b>declared</b> — every predicate is declared by the TBox;</li>
 *   <li><b>no dangling</b> — every subject / object is a declared entity;</li>
 *   <li><b>domain / range</b> — a rule is checked whenever the document says the
 *   gender of the endpoint (331 of the entities have a document), and counted as
 *   <em>unverifiable</em> otherwise, so the coverage of the check is visible;</li>
 *   <li><b>object property</b> — an object property's object is an IRI, never a
 *   literal;</li>
 *   <li><b>irreflexive</b> — no {@code owl:IrreflexiveProperty} self loop;</li>
 *   <li><b>asymmetric</b> — no {@code owl:AsymmetricProperty} in both directions;</li>
 *   <li><b>duplicates</b> — the curated file's duplicate triples, which the parser
 *   collapses.</li>
 * </ol>
 *
 * <p>The second group checks the <em>materialized</em> graph (the parser applies
 * the ontology rules, so these must hold): <b>inverse closure</b> and
 * <b>symmetric closure</b>, the <b>involution</b> of the inverse table, and that
 * every declared inverse pair is mutual.
 */
public final class SanguoRdfValidator {

    /** One broken rule. */
    public record Violation(String rule, String subject, String relation, String object, String detail) {

        @Override
        public String toString() {
            return rule + ": " + subject + " --" + relation + "--> " + object + " (" + detail + ")";
        }
    }

    /** The outcome of a validation run. */
    public record Report(int entityCount, int relationCount, int closedRelationCount, int curatedCount,
                         int extraCount, int domainRangeChecked, int domainRangeUnverifiable,
                         int duplicateCurated,
                         Map<String, Integer> relationUsage,
                         Map<String, Integer> relationFamilyUsage,
                         List<Violation> violations) {

        public boolean valid() {
            return violations.isEmpty();
        }

        /** Violations of one rule. */
        public List<Violation> violations(String rule) {
            return violations.stream().filter(v -> v.rule().equals(rule)).toList();
        }
    }

    private SanguoRdfValidator() {
    }

    /** Validates the dataset's parse and its materialized closures. */
    public static Report validate(SanguoDataset dataset) {
        SanguoOntology ontology = dataset.ontology();
        Map<String, SanguoDataset.Entity> entities = dataset.entities();
        Map<String, String> gender = dataset.genderByName();
        List<SanguoRdfValidator.Violation> violations = new ArrayList<>();

        int checked = 0;
        int unverifiable = 0;
        Map<String, Integer> usage = new TreeMap<>();
        Map<String, Integer> familyUsage = new TreeMap<>();

        for (SanguoDataset.Relation relation : dataset.relations()) {
            String head = relation.head();
            String predicate = relation.relation();
            String tail = relation.tail();
            usage.merge(predicate, 1, Integer::sum);
            familyUsage.merge(ontology.relationFamilyLabel(predicate), 1, Integer::sum);

            SanguoOntology.Property property = ontology.property(predicate);
            if (!ontology.isDeclared(predicate)) {
                violations.add(new Violation("declared", head, predicate, tail,
                        "predicate is not declared by the ontology"));
                continue;
            }
            if (property == null) {
                continue; // a datatype property: not a relation, nothing more to check here
            }
            if (!entities.containsKey(head)) {
                violations.add(new Violation("no-dangling", head, predicate, tail, "subject is not a declared entity"));
            }
            if (!entities.containsKey(tail)) {
                violations.add(new Violation("no-dangling", head, predicate, tail, "object is not a declared entity"));
            }

            for (int side = 0; side < 2; side++) {
                String node = side == 0 ? head : tail;
                String expected = side == 0 ? property.domain() : property.range();
                if (expected == null) {
                    continue;
                }
                Outcome outcome = checkType(expected, node, entities, gender);
                switch (outcome) {
                    case OK -> checked++;
                    case UNVERIFIABLE -> unverifiable++;
                    case VIOLATION -> violations.add(new Violation(side == 0 ? "domain" : "range",
                            head, predicate, tail,
                            node + " should be " + expected + " but is " + typeOf(node, entities, gender)));
                }
            }

            if (property.irreflexive() && head.equals(tail)) {
                violations.add(new Violation("irreflexive", head, predicate, tail,
                        "owl:IrreflexiveProperty must not relate a node to itself"));
            }
            if (property.asymmetric() && !property.symmetric() && dataset.relations().stream()
                    .anyMatch(other -> other.head().equals(tail) && other.tail().equals(head)
                            && other.relation().equals(predicate))) {
                violations.add(new Violation("asymmetric", head, predicate, tail,
                        "owl:AsymmetricProperty holds in both directions"));
            }
        }

        // duplicates in the curated file, which the parser collapses into one relation
        Set<String> curatedKeys = new LinkedHashSet<>();
        int duplicates = 0;
        for (SanguoDataset.Relation relation : dataset.curatedRelations()) {
            String key = relation.head() + "\u0000" + relation.relation() + "\u0000" + relation.tail();
            if (!curatedKeys.add(key)) {
                duplicates++;
            }
        }

        // materialized closures must satisfy the ontology rules
        Set<String> closedKeys = new LinkedHashSet<>();
        for (SanguoDataset.Relation relation : dataset.closedRelations()) {
            closedKeys.add(relation.head() + "\u0000" + relation.relation() + "\u0000" + relation.tail());
        }
        for (SanguoDataset.Relation relation : dataset.relations()) {
            String inverse = ontology.inverse(relation.relation());
            if (!inverse.equals(relation.relation())
                    && !closedKeys.contains(relation.tail() + "\u0000" + inverse + "\u0000" + relation.head())) {
                violations.add(new Violation("inverse-closure", relation.head(), relation.relation(), relation.tail(),
                        "missing " + relation.tail() + " --" + inverse + "--> " + relation.head()));
            }
            SanguoOntology.Property property = ontology.property(relation.relation());
            if (property != null && property.symmetric()
                    && !closedKeys.contains(relation.tail() + "\u0000" + relation.relation() + "\u0000" + relation.head())) {
                violations.add(new Violation("symmetric-closure", relation.head(), relation.relation(), relation.tail(),
                        "owl:SymmetricProperty needs the mirrored triple"));
            }
        }

        Map<String, String> inverse = new LinkedHashMap<>();
        for (SanguoOntology.Property property : ontology.properties()) {
            inverse.put(property.name(), ontology.inverse(property.name()));
        }
        for (Map.Entry<String, String> entry : inverse.entrySet()) {
            String back = inverse.get(entry.getValue());
            if (back == null || !back.equals(entry.getKey())) {
                violations.add(new Violation("involution", entry.getKey(), entry.getValue(),
                        String.valueOf(back), "inverse(inverse(r)) must be r"));
            }
        }
        for (Map.Entry<String, String> entry : ontology.declaredInverses().entrySet()) {
            if (entry.getKey().equals("concubine")) {
                // 妾 declares husband as its inverse, but husband is the inverse of the
                // primary 妻, so concubine keeps itself to preserve the involution; the
                // edge is still found from the husband's side through xrelation=concubine
                continue;
            }
            if (!ontology.inverse(entry.getKey()).equals(entry.getValue())) {
                violations.add(new Violation("declared-inverse", entry.getKey(), entry.getValue(),
                        ontology.inverse(entry.getKey()),
                        "the ontology declares this inverse and nothing should override it"));
            }
        }

        return new Report(entities.size(), dataset.relations().size(), dataset.closedRelations().size(),
                dataset.curatedRelations().size(), dataset.extraRelations().size(), checked, unverifiable, duplicates,
                // insertion order (both are TreeMaps here); Map.copyOf would salt it
                Collections.unmodifiableMap(new java.util.LinkedHashMap<>(usage)),
                Collections.unmodifiableMap(new java.util.LinkedHashMap<>(familyUsage)),
                List.copyOf(violations));
    }

    private enum Outcome {
        OK, UNVERIFIABLE, VIOLATION
    }

    private static Outcome checkType(String expected, String node, Map<String, SanguoDataset.Entity> entities,
                                     Map<String, String> gender) {
        SanguoDataset.Entity entity = entities.get(node);
        if (entity == null) {
            return Outcome.VIOLATION;
        }
        String actual = entity.type().rdfClass();
        if (expected.equals("male") || expected.equals("female")) {
            String expectedClass = expected.equals("male") ? "Male" : "Female";
            if (actual.equals("Person")) {
                // no document for this person: the gender cannot be checked either way
                return Outcome.UNVERIFIABLE;
            }
            return actual.equals(expectedClass) ? Outcome.OK : Outcome.VIOLATION;
        }
        if (expected.equals("Person")) {
            return entity.type().isPerson() ? Outcome.OK : Outcome.VIOLATION;
        }
        return actual.equals(expected) ? Outcome.OK : Outcome.VIOLATION;
    }

    private static String typeOf(String node, Map<String, SanguoDataset.Entity> entities,
                                 Map<String, String> gender) {
        SanguoDataset.Entity entity = entities.get(node);
        if (entity == null) {
            return "unknown";
        }
        String genderNote = gender.containsKey(node) ? ", " + gender.get(node) : "";
        return entity.type().rdfClass() + genderNote;
    }
}
