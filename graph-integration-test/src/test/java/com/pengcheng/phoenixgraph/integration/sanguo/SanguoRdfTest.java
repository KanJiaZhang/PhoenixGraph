package com.pengcheng.phoenixgraph.integration.sanguo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline test of the document → RDF pipeline of the 《三国演义》 knowledge graph
 * (no Aerospike needed).
 *
 * <p>Covers the first two steps of the pipeline:
 * <ol>
 *   <li><b>parse</b> — the 331 person documents and the 153 curated triples become
 *   a typed entity set and a set of named relations, with the ontology's inverse
 *   and symmetric rules materialized;</li>
 *   <li><b>validate</b> — the parse satisfies the ontology (declared predicates,
 *   domain/range, irreflexivity/asymmetry, no dangling IRIs);</li>
 *   <li><b>freeze</b> — the generated RDF equals the committed artifacts under
 *   {@code src/test/resources/kg/sanguo/rdf/};</li>
 *   <li><b>round-trip</b> — the N-Triples parse back into the same triple set.</li>
 * </ol>
 *
 * <p>Set {@code -Dsanguo.rdf.write=true} (surefire passes system properties to the
 * forked JVM) to regenerate the frozen artifacts instead of comparing them.
 */
class SanguoRdfTest {

    private static final String RDF_DIR = "/kg/sanguo/rdf";

    private static final SanguoDataset DATASET = SanguoDataset.load();

    @Test
    void parsesThePersonDocuments() {
        List<SanguoDataset.Document> documents = DATASET.documents();
        assertEquals(4487, documents.size());
        assertEquals(4241, documents.stream().filter(d -> d.gender().equals("男")).count());
        assertEquals(246, documents.stream().filter(d -> d.gender().equals("女")).count());

        // the fields the RDF turns into attributes are read, not dropped
        SanguoDataset.Document caoCao = DATASET.documentsByName().get("曹操");
        assertNotNull(caoCao, "曹操 must be one of the person documents");
        assertEquals("男", caoCao.gender());
        assertEquals("male", caoCao.ontologyGender());
        assertFalse(caoCao.historicity().isEmpty());

        // the full export documents 刘备; the earlier 331-row copy stopped before him
        assertTrue(DATASET.documentsByName().containsKey("刘备"));
        assertEquals(SanguoDataset.EntityType.MALE, DATASET.entities().get("刘备").type());

        // the upstream 名字[势力] tag is KEPT — it is what tells the 359 homonyms apart, and it is
        // the only way 刘协's 配偶 resolves to the empress 曹节[献穆皇后] rather than to the eunuch
        assertEquals(359, documents.stream().filter(d -> d.name().contains("[")).count(),
                "the upstream disambiguation tag stays part of the name");
        assertTrue(DATASET.entities().containsKey("曹节[献穆皇后]"),
                "the empress and the eunuch 曹节 stay distinct entities");
        assertTrue(DATASET.entities().containsKey("曹节[东汉]"));
        // 谥曰… is a posthumous name, not an office
        assertTrue(DATASET.relations().stream().noneMatch(r -> r.tail().startsWith("谥曰")));
        // 籍贯 keeps the historical part only
        assertTrue(DATASET.relations().stream().filter(r -> r.relation().equals("nativePlace"))
                .noneMatch(r -> r.tail().contains("(")));
    }

    @Test
    void splitsEntitiesAndRelations() {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (SanguoDataset.Entity entity : DATASET.entities().values()) {
            byType.merge(entity.type().rdfClass(), 1, Integer::sum);
        }
        assertEquals(4241, byType.get("Male"));
        assertEquals(246, byType.get("Female"));
        assertEquals(237, byType.get("Person"), "relation-only persons");
        assertEquals(120, byType.get("Chapter"), "三国演义 has 120 章回");
        assertTrue(byType.get("Faction") >= 11, "the 11 generic powers plus the warlord regimes");
        assertTrue(byType.get("Place") > 60);
        assertTrue(byType.get("Position") > 50);
        assertTrue(DATASET.entities().size() > 6_000);

        // every relation of the ABox is declared by the ontology (TBox)
        for (SanguoDataset.Relation relation : DATASET.relations()) {
            assertTrue(DATASET.ontology().isDeclared(relation.relation()),
                    "undeclared relation: " + relation.relation());
        }
        assertEquals(21_050, DATASET.relations().size());
        assertEquals(153, DATASET.curatedRelations().size());
        assertEquals(8, DATASET.extraRelations().size(),
                "the local supplement: 6 五虎上将 offices + 2 关羽/张飞 臣属 edges");

        // the supplement is what makes 五虎上将 and the missing 臣 edges queryable
        assertTrue(DATASET.relations().stream().anyMatch(r -> r.relation().equals("holds")
                && r.head().equals("关羽") && r.tail().equals("五虎上将之首")));
        assertTrue(DATASET.relations().stream().anyMatch(r -> r.relation().equals("lord")
                && r.head().equals("刘备") && r.tail().equals("关羽")));
        assertEquals(SanguoDataset.EntityType.POSITION, DATASET.entities().get("五虎上将").type());

        // a warlord regime is a faction, not the person of the same name
        assertTrue(DATASET.entities().containsKey("袁绍势力"), "效力=袁绍 becomes the 袁绍势力 faction");
        assertEquals(SanguoDataset.EntityType.FACTION, DATASET.entities().get("袁绍势力").type());
        assertEquals(SanguoDataset.EntityType.MALE, DATASET.entities().get("袁绍").type());
        assertTrue(DATASET.relations().stream().noneMatch(r -> r.relation().equals("serves")
                && r.head().equals(r.tail())), "no 'serves itself' after the faction rename");

        // 刘备 is the hub of the classic relations
        Map<String, Integer> liuBeiRelations = new LinkedHashMap<>();
        for (SanguoDataset.Relation relation : DATASET.relations()) {
            if (relation.head().equals("刘备")) {
                liuBeiRelations.merge(relation.relation(), 1, Integer::sum);
            }
        }
        assertTrue(liuBeiRelations.getOrDefault("lord", 0) >= 10, "刘备 is the 主公 of many");
        assertTrue(DATASET.relations().stream().anyMatch(r -> r.head().equals("刘备")
                && r.relation().equals("lord") && r.tail().equals("诸葛亮")));
        assertTrue(DATASET.relations().stream().anyMatch(r -> r.head().equals("诸葛亮")
                && r.relation().equals("master") && r.tail().equals("姜维")));
    }

    @Test
    void everyShardIsUsed() {
        Set<Integer> shards = new HashSet<>();
        for (SanguoDataset.Relation relation : DATASET.relations()) {
            int shard = SanguoGraphIds.shard(relation.relation());
            assertTrue(shard >= 0 && shard < SanguoGraphIds.SHARD_COUNT);
            shards.add(shard);
            // both endpoints of a triple land in the shard of its relation
            assertEquals(SanguoGraphIds.composeId(relation.head(), relation.relation()),
                    relation.head() + "_" + shard);
            assertEquals(SanguoGraphIds.composeId(relation.tail(), relation.relation()),
                    relation.tail() + "_" + shard);
        }
        assertEquals(9, shards.size(),
                "nine of the ten shards carry relations; the unused one is documented in the README");
    }

    @Test
    void materializesTheOntologyRules() {
        SanguoOntology ontology = DATASET.ontology();
        // the inverse table is an involution, which is what makes the relation filter work
        for (SanguoOntology.Property property : ontology.properties()) {
            String inverse = ontology.inverse(property.name());
            assertEquals(property.name(), ontology.inverse(inverse),
                    "inverse(inverse(" + property.name() + ")) must be itself");
        }
        assertTrue(DATASET.closedRelations().size() > DATASET.relations().size(),
                "the inverse / symmetric closure must add relations");

        Set<String> keys = new LinkedHashSet<>();
        for (SanguoDataset.Relation relation : DATASET.closedRelations()) {
            keys.add(relation.head() + "|" + relation.relation() + "|" + relation.tail());
        }
        // 诸葛亮 master 姜维  =>  姜维 apprentice 诸葛亮 (owl:inverseOf)
        assertTrue(keys.contains("姜维|apprentice|诸葛亮"));
        // husband <-> wife is declared through the anonymous (inverseOf) subPropertyOf
        assertEquals("wife", ontology.inverse("husband"));
        assertEquals("husband", ontology.inverse("wife"));
        // sworn is owl:SymmetricProperty, so the mirror of 关平/张苞 exists
        assertTrue(DATASET.relations().stream().anyMatch(r -> r.relation().equals("sworn")));
        assertTrue(keys.contains("张苞|sworn|关平") || keys.contains("关平|sworn|张苞"));
    }

    @Test
    void validatesTheParse() {
        SanguoRdfValidator.Report report = SanguoRdfValidator.validate(DATASET);
        report.violations().forEach(System.out::println);
        // One known upstream inconsistency survives the full 4,487-person dataset: 刘建[安国侯]'s
        // 母亲 field names 刘英, and the only 刘英 in the data is documented as 男 — so the `mother`
        // edge contradicts the ontology's domain (female). It is asserted byte-for-byte instead of
        // filtered out, so a *new* violation still fails the build.
        assertEquals(List.of("domain: 刘英 --mother--> 刘建[安国侯] (刘英 should be female but is Male, 男)"),
                report.violations().stream().map(Object::toString).toList(),
                "only the documented upstream inconsistency may violate the ontology");
        assertTrue(report.domainRangeChecked() > 100,
                "domain/range must actually be checked: " + report.domainRangeChecked());
        assertTrue(report.domainRangeUnverifiable() > 0,
                "the relation-only persons are reported as unverifiable, not silently passed");
        assertEquals(7, report.duplicateCurated(), "the curated file has 7 duplicate triples");
        assertEquals(8, report.extraCount(), "the local supplement has 8 triples");
        assertFalse(report.relationUsage().isEmpty());
    }

    @Test
    void writesTheFrozenRdfArtifacts() throws IOException {
        SanguoRdfGenerator.Artifacts artifacts = SanguoRdfGenerator.generate(DATASET);
        Path target = Path.of("target", "sanguo-rdf");
        SanguoRdf.write(target.resolve("sanguo-ontology.ttl"), artifacts.ontologyTurtle());
        SanguoRdf.write(target.resolve("sanguo-kg.ttl"), artifacts.instanceTurtle());
        SanguoRdf.write(target.resolve("sanguo-kg.nt"), artifacts.instanceNTriples());
        SanguoRdf.write(target.resolve("VALIDATION.md"), artifacts.validationReport());

        if (Boolean.getBoolean("sanguo.rdf.write")) {
            Path frozen = Path.of("src", "test", "resources", "kg", "sanguo", "rdf");
            SanguoRdf.write(frozen.resolve("sanguo-ontology.ttl"), artifacts.ontologyTurtle());
            SanguoRdf.write(frozen.resolve("sanguo-kg.ttl"), artifacts.instanceTurtle());
            SanguoRdf.write(frozen.resolve("sanguo-kg.nt"), artifacts.instanceNTriples());
            SanguoRdf.write(frozen.resolve("VALIDATION.md"), artifacts.validationReport());
            return;
        }

        assertEquals(resource(RDF_DIR + "/sanguo-ontology.ttl"), artifacts.ontologyTurtle());
        assertEquals(resource(RDF_DIR + "/sanguo-kg.ttl"), artifacts.instanceTurtle());
        assertEquals(resource(RDF_DIR + "/sanguo-kg.nt"), artifacts.instanceNTriples());
        assertEquals(resource(RDF_DIR + "/VALIDATION.md"), artifacts.validationReport());
    }

    @Test
    void roundTripsTheNTriples() {
        List<SanguoRdf.Triple> triples = SanguoRdf.instanceTriples(DATASET);
        String ntriples = SanguoRdf.toNTriples(triples);
        List<SanguoRdf.Triple> reparsed = parseNTriples(ntriples);
        assertEquals(triples.size(), reparsed.size());
        assertEquals(triples, reparsed, "N-Triples must parse back into the same triples");

        // the instance graph references the ontology: every predicate is declared
        Set<String> predicates = new LinkedHashSet<>();
        for (SanguoRdf.Triple triple : triples) {
            predicates.add(triple.predicate());
        }
        for (String predicate : predicates) {
            if (predicate.startsWith(SanguoOntology.NAMESPACE)) {
                String relation = predicate.substring(SanguoOntology.NAMESPACE.length());
                assertTrue(DATASET.ontology().isDeclared(relation), "predicate not in the TBox: " + relation);
            }
        }
        // every entity has exactly one rdf:type, and every relation points between entities
        Map<String, Integer> types = new LinkedHashMap<>();
        for (SanguoRdf.Triple triple : triples) {
            if (triple.predicate().equals(SanguoRdf.RDF_TYPE)) {
                types.merge(triple.subject(), 1, Integer::sum);
            }
        }
        assertEquals(DATASET.entities().size(), types.size());
        assertTrue(types.values().stream().allMatch(n -> n == 1));
    }

    @Test
    void generatesFromThePlainDatasetDirectory(@TempDir Path work) throws IOException {
        // the documented CLI reads the three plain files; the shipped copies are gzipped,
        // so decompress them first and then run exactly what SANGUO-KG.md documents
        Path plain = Files.createDirectories(work.resolve("dataset"));
        for (String name : List.of("renwu_process_v1.csv", "theory.owl", "triples.csv")) {
            try (InputStream gz = SanguoRdfTest.class.getResourceAsStream("/kg/sanguo/" + name + ".gz")) {
                assertNotNull(gz, "missing resource /kg/sanguo/" + name + ".gz");
                try (InputStream in = new java.util.zip.GZIPInputStream(gz)) {
                    Files.write(plain.resolve(name), in.readAllBytes());
                }
            }
        }
        // the local supplement is shipped plain (it is meant to be edited); the CLI picks it up
        // from the same directory as triples.csv
        try (InputStream extra = SanguoRdfTest.class.getResourceAsStream("/kg/sanguo/triples-extra.csv")) {
            assertNotNull(extra, "missing resource /kg/sanguo/triples-extra.csv");
            Files.write(plain.resolve("triples-extra.csv"), extra.readAllBytes());
        }
        Path out = work.resolve("out");
        SanguoRdfGenerator.main(new String[] {plain.toString(), out.toString()});

        assertEquals(artifacts().ontologyTurtle(),
                Files.readString(out.resolve("sanguo-ontology.ttl"), StandardCharsets.UTF_8));
        assertEquals(artifacts().instanceTurtle(),
                Files.readString(out.resolve("sanguo-kg.ttl"), StandardCharsets.UTF_8));
        assertEquals(artifacts().instanceNTriples(),
                Files.readString(out.resolve("sanguo-kg.nt"), StandardCharsets.UTF_8));
        assertEquals(artifacts().validationReport(),
                Files.readString(out.resolve("VALIDATION.md"), StandardCharsets.UTF_8));
        // and the classpath form of the same dataset produces the same artifacts
        assertEquals(artifacts().instanceTurtle(), SanguoRdfGenerator.generate(DATASET).instanceTurtle());
    }

    // --- helpers ---

    private static SanguoRdfGenerator.Artifacts artifacts() {
        return SanguoRdfGenerator.generate(DATASET);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = SanguoRdfTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing committed artifact: " + path
                    + " (regenerate with -Dsanguo.rdf.write=true)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A minimal N-Triples reader, good enough for the terms this generator writes. */
    static List<SanguoRdf.Triple> parseNTriples(String ntriples) {
        List<SanguoRdf.Triple> triples = new java.util.ArrayList<>();
        for (String line : ntriples.split("\n")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            assertTrue(text.endsWith(" ."), "statement must end with ' .': " + text);
            text = text.substring(0, text.length() - 2).trim();
            int first = text.indexOf('>');
            String subject = text.substring(1, first);
            int second = text.indexOf('<', first + 1);
            int third = text.indexOf('>', second);
            String predicate = text.substring(second + 1, third);
            String object = text.substring(third + 1).trim();
            if (object.startsWith("<")) {
                triples.add(new SanguoRdf.Triple(subject, predicate,
                        new SanguoRdf.Iri(object.substring(1, object.length() - 1))));
            } else {
                int quote = object.lastIndexOf('"');
                String value = object.substring(1, quote).replace("\\\"", "\"").replace("\\\\", "\\");
                String suffix = object.substring(quote + 1);
                triples.add(new SanguoRdf.Triple(subject, predicate,
                        suffix.startsWith("@") ? SanguoRdf.Literal.lang(value, suffix.substring(1))
                                : SanguoRdf.Literal.of(value)));
            }
        }
        return triples;
    }

    /** Sorted copy for readable diffs in test failures. */
    static <T extends Comparable<T>> List<T> sorted(java.util.Collection<T> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    static {
        // touch Files so the import survives when the write branch is compiled out
        assertTrue(Files.isDirectory(Path.of(".")));
    }
}
