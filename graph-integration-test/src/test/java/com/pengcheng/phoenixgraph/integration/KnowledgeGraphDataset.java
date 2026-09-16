package com.pengcheng.phoenixgraph.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The knowledge-graph dataset used to populate the {@code kg-entity-graph}
 * schema.
 *
 * <p>Reads a standard triple benchmark laid out as three gzip-compressed
 * resources (see {@code src/test/resources/kg/nell995/README.md}):
 * <ul>
 *   <li>{@code entity2id.txt.gz} — {@code name<TAB>id}, first line = entity count</li>
 *   <li>{@code relation2id.txt.gz} — {@code name<TAB>id}, first line = relation count</li>
 *   <li>{@code train2id.txt.gz} — {@code headId tailId relationId}, first line = triple count</li>
 * </ul>
 *
 * <p>It also derives the <em>inverse</em> relation table used to fill the edge's
 * {@code xrelation} property: for every relation {@code r} the most frequent
 * relation {@code r'} such that {@code (head, r, tail)} and {@code (tail, r', head)}
 * both occur is taken as its reverse, but only when the choice is mutual
 * ({@code r'} also points back at {@code r}) and well supported. Every other
 * relation is its own reverse, which keeps {@code inverse} an involution — the
 * property that makes {@code relation.equals(R) || xrelation.equals(R)} find a
 * relation in both directions.
 */
public final class KnowledgeGraphDataset {

    /** Directory (classpath) of the gzipped resources. */
    public static final String DEFAULT_DIRECTORY = "/kg/nell995";

    /** Default directory of the gzipped resources. */
    private static final int MIN_INVERSE_SUPPORT = 10;

    /** One dataset triple: {@code head --relation--> tail}. */
    public record Triple(int headId, int tailId, int relationId) {
    }

    private final List<String> entities;
    private final List<String> relations;
    private final List<Triple> triples;
    private final int[] inverseRelation;

    private KnowledgeGraphDataset(List<String> entities, List<String> relations, List<Triple> triples,
                                  int[] inverseRelation) {
        this.entities = entities;
        this.relations = relations;
        this.triples = triples;
        this.inverseRelation = inverseRelation;
    }

    /** Loads the dataset bundled with the tests. */
    public static KnowledgeGraphDataset load() {
        return load(DEFAULT_DIRECTORY);
    }

    /** Loads the dataset from the given classpath directory. */
    public static KnowledgeGraphDataset load(String directory) {
        List<String> entities = readIndexedNames(directory + "/entity2id.txt.gz");
        List<String> relations = readIndexedNames(directory + "/relation2id.txt.gz");
        List<Triple> triples = readTriples(directory + "/train2id.txt.gz");
        return new KnowledgeGraphDataset(entities, relations, triples, deriveInverseRelations(relations.size(), triples));
    }

    /** Entity names indexed by entity id. */
    public List<String> entityNames() {
        return entities;
    }

    /** Relation names indexed by relation id. */
    public List<String> relationNames() {
        return relations;
    }

    /** All triples, in file order. */
    public List<Triple> triples() {
        return triples;
    }

    public String entityName(int id) {
        return entities.get(id);
    }

    public String relationName(int id) {
        return relations.get(id);
    }

    /** The reverse relation name of a relation id (may be the relation itself). */
    public String inverseRelationName(int id) {
        return relations.get(inverseRelation[id]);
    }

    /** Relation id -> inverse relation id ({@code inverse[inverse[r]] == r}). */
    public int[] inverseRelations() {
        return inverseRelation.clone();
    }

    // --- loading ---

    private static List<String> readIndexedNames(String resource) {
        List<String> names = new ArrayList<>();
        forEachLine(resource, (line, index) -> {
            if (index == 0) {
                return; // header: the element count
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                throw new IllegalStateException("Malformed line " + index + " in " + resource + ": " + line);
            }
            int id = Integer.parseInt(parts[1].trim());
            while (names.size() <= id) {
                names.add(null);
            }
            names.set(id, parts[0].trim());
        });
        return List.copyOf(names);
    }

    private static List<Triple> readTriples(String resource) {
        List<Triple> triples = new ArrayList<>();
        forEachLine(resource, (line, index) -> {
            if (index == 0) {
                return; // header: the triple count
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3) {
                throw new IllegalStateException("Malformed line " + index + " in " + resource + ": " + line);
            }
            triples.add(new Triple(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        });
        return List.copyOf(triples);
    }

    private static void forEachLine(String resource, LineConsumer consumer) {
        try (InputStream raw = KnowledgeGraphDataset.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Missing dataset resource: " + resource
                        + " (see src/test/resources/kg/nell995/README.md)");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                String line;
                int index = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    consumer.accept(line, index++);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }

    @FunctionalInterface
    private interface LineConsumer {
        void accept(String line, int index);
    }

    // --- inverse relation derivation ---

    private static int[] deriveInverseRelations(int relationCount, List<Triple> triples) {
        Map<Long, Set<Integer>> relationsByPair = new HashMap<>();
        for (Triple triple : triples) {
            relationsByPair.computeIfAbsent(entityPairKey(triple.headId(), triple.tailId()),
                    k -> new HashSet<>()).add(triple.relationId());
        }

        Map<Long, Integer> support = new HashMap<>();
        for (Triple triple : triples) {
            Set<Integer> reverseRelations = relationsByPair.get(entityPairKey(triple.tailId(), triple.headId()));
            if (reverseRelations == null) {
                continue;
            }
            for (int candidate : reverseRelations) {
                support.merge(relationPairKey(triple.relationId(), candidate), 1, Integer::sum);
            }
        }

        // best[r] = the most frequent reverse candidate with enough support
        int[] best = new int[relationCount];
        int[] bestCount = new int[relationCount];
        Arrays.fill(best, -1);
        for (Map.Entry<Long, Integer> entry : support.entrySet()) {
            int count = entry.getValue();
            if (count < MIN_INVERSE_SUPPORT) {
                continue;
            }
            int relation = first(entry.getKey());
            int candidate = second(entry.getKey());
            if (count > bestCount[relation]) {
                best[relation] = candidate;
                bestCount[relation] = count;
            }
        }

        // keep only mutual choices, so the result is an involution;
        // everything else is its own reverse (symmetric or unknown)
        int[] inverse = new int[relationCount];
        for (int relation = 0; relation < relationCount; relation++) {
            int candidate = best[relation];
            inverse[relation] = candidate >= 0 && candidate != relation && best[candidate] == relation
                    ? candidate : relation;
        }
        return inverse;
    }

    private static long entityPairKey(int head, int tail) {
        return ((long) head << 20) | tail;
    }

    private static long relationPairKey(int relation, int candidate) {
        return ((long) relation << 20) | candidate;
    }

    private static int first(long key) {
        return (int) (key >>> 20);
    }

    private static int second(long key) {
        return (int) (key & 0xFFFFFL);
    }
}
