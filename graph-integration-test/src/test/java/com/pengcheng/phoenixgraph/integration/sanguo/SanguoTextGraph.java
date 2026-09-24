package com.pengcheng.phoenixgraph.integration.sanguo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>方案 C</b>'s graph: the base dataset plus everything {@link SanguoTextExtractor}
 * reads out of the document free text, destined for its own <b>RocksDB-Edge</b>
 * instance ({@code sanguo-kg-text}).
 *
 * <p>Composition (in precedence order, as everywhere in this pipeline):
 *
 * <pre>{@code
 * base = the 153 upstream curated triples + the document-derived links      (sanguo-kg-rocksdb-edge)
 * text = base + the manual supplement triples-extra.csv                      (方案 B)
 *             + the text-extracted relations                                 (方案 C)
 * }</pre>
 *
 * <p>Both supplements are passed as {@link SanguoDataset.RelationSource#EXTRA}, so the
 * base graph's own numbers are untouched. The extractor runs against the <em>base</em>
 * entity set (its gazetteer), so a mention is always a known name; the new entities it
 * finds are the ones the relation slots introduce (offices such as {@code 隃麋侯}, people
 * such as {@code 麹义}).
 */
public final class SanguoTextGraph {

    /** The graph name / schema of the text instance. */
    public static final String GRAPH = "sanguo-kg-text";

    /** The default RocksDB root, relative to the repository root. */
    public static final String DEFAULT_ROOT = ".rocksdb";

    private SanguoTextGraph() {
    }

    /** The text dataset plus the extraction result it was built from. */
    public record Built(SanguoDataset dataset, SanguoTextExtractor.Result extraction) {

        /** How many entities/relations the text graph adds on top of the base graph. */
        public String describe() {
            return String.format("%d entities, %d relations (%d materialized) — extracted %d relations, "
                            + "%d new offices, %d new people",
                    dataset.entities().size(), dataset.relations().size(), dataset.closedRelations().size(),
                    extraction.relations().size(), extraction.newPositions().size(),
                    extraction.newPersons().size());
        }
    }

    /** Builds the text dataset from the base one (which is re-read from the classpath). */
    public static Built build() {
        return build(SanguoDataset.load());
    }

    /** Builds the text dataset from a given base dataset. */
    public static Built build(SanguoDataset base) {
        SanguoTextExtractor.Result extraction = SanguoTextExtractor.extract(base);

        // 方案 B's manual supplement, when it is on the classpath, plus 方案 C's extraction
        List<SanguoDataset.Relation> supplements = new ArrayList<>(readManualSupplement());
        for (SanguoTextExtractor.Extracted extracted : extraction.relations()) {
            supplements.add(new SanguoDataset.Relation(extracted.head(), extracted.relation(), extracted.tail(),
                    SanguoDataset.RelationSource.EXTRA));
        }

        SanguoOntology ontology = base.ontology().withTextProfile();
        SanguoDataset dataset = SanguoDataset.of(base.documents(), base.curatedRelations(), supplements, ontology);
        return new Built(dataset, extraction);
    }

    /** The manual supplement if it ships with the tests, otherwise nothing. */
    private static List<SanguoDataset.Relation> readManualSupplement() {
        try (InputStream in = SanguoTextGraph.class.getResourceAsStream("/kg/sanguo/triples-extra.csv")) {
            if (in == null) {
                return List.of();
            }
            return SanguoDataset.parseExtraTriples(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read triples-extra.csv", e);
        }
    }

    /** The schema of the text instance. */
    public static com.pengcheng.phoenixgraph.schema.GraphSchema loadSchema() {
        return SanguoRocksDbLoader.loadSchema(GRAPH);
    }

    /** Loads the text graph into {@code .rocksdb/sanguo-kg-text}. */
    public static SanguoRocksDbLoader.Result load(String rocksDbRoot, boolean fresh) {
        return load(build(), rocksDbRoot, fresh);
    }

    /** Loads a built text graph into {@code rocksDbRoot}. */
    public static SanguoRocksDbLoader.Result load(Built built, String rocksDbRoot, boolean fresh) {
        com.pengcheng.phoenixgraph.schema.GraphSchema schema = loadSchema();
        String root = rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot;
        java.nio.file.Path path = java.nio.file.Path.of(root).resolve(schema.storage().requireRocksDbPath());
        return SanguoRocksDbLoader.load(schema, built.dataset(), path, fresh);
    }

    /** Whether the text graph directory already holds a database. */
    public static boolean isLoaded(String rocksDbRoot) {
        java.nio.file.Path path = java.nio.file.Path.of(
                        rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot)
                .resolve(loadSchema().storage().requireRocksDbPath());
        return java.nio.file.Files.exists(path.resolve("CURRENT"));
    }

    /** A summary of the extraction, per rule, with a sample each (used by the report). */
    public static Map<String, String> summarise(SanguoTextExtractor.Result extraction) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> rules = extraction.countByRule().keySet();
        for (String rule : rules) {
            out.put(rule, extraction.samplesByRule().getOrDefault(rule, List.of()).stream()
                    .findFirst().orElse(""));
        }
        return out;
    }
}
