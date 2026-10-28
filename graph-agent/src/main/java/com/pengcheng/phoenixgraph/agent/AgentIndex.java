package com.pengcheng.phoenixgraph.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The artifact one ingestion leaves behind: the entities with their vectors and the descriptions those
 * vectors came from, the relations with their provenance, the chunks, and the quality report.
 *
 * <p>It is a file next to the graph's schema (JSON, so it can be read, diffed and shipped), and it is what
 * makes the AI tools cheap: the <b>recall</b> stage of a search - "which entities does this question
 * mention, or come close to" - is answered here, in memory, without touching storage; the graph itself is
 * then asked only for the <b>expansion</b> (neighbours, paths), where the engine is strong. Both stages
 * are needed for a good answer and neither is good at the other's job.
 *
 * <p>Nothing about the file is hidden: {@code GET /api/v1/agent/index/{graph}} returns it, and the tools
 * report what they used, so an answer can always be traced back to the vectors and the sentences it was
 * assembled from.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentIndex(String graph, String embedding, int dimensions, String createdAt,
                         List<Entity> entities, List<Relation> relations, List<Chunk> chunks,
                         Map<String, Object> quality) {

    public AgentIndex {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        quality = quality == null ? Map.of() : Map.copyOf(quality);
    }

    /** One entity: the name an AI quotes, the type it is, the vector, and where it was read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(String id, String vertexType, String type, String name, String description,
                         float[] vector, List<String> chunkIds, List<String> cites, List<String> aliases,
                         Long validFrom, Long validTo) {

        public Entity {
            // the vertex type the graph stores it under (what a traversal names), and the domain type the
            // documents imply (what an answer says): they are different things and both are useful
            vertexType = vertexType == null || vertexType.isBlank() ? "Entity" : vertexType;
            chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
            cites = cites == null ? List.of() : List.copyOf(cites);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public Entity(String id, String vertexType, String type, String name, String description,
                      float[] vector, List<String> chunkIds, List<String> cites) {
            this(id, vertexType, type, name, description, vector, chunkIds, cites, List.of(), null, null);
        }

        public Entity(String id, String vertexType, String type, String name, String description,
                      float[] vector, List<String> chunkIds, List<String> cites, List<String> aliases) {
            this(id, vertexType, type, name, description, vector, chunkIds, cites, aliases, null, null);
        }

        /** The window this entity is valid in; unversioned entities are valid at every instant. */
        public Validity validity() {
            return new Validity(validFrom, validTo);
        }

        /** Whether the entity exists at {@code instant} (epoch millis). */
        public boolean validAt(long instant) {
            return validity().validAt(instant);
        }
    }

    /** One relation of the graph, with the sentence it was read from and the window it holds in. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(String head, String relation, String tail, String rule, String chunkId, String cite,
                           String sentence, Long validFrom, Long validTo) {

        /** A relation from before the validity dimension existed: valid at every instant. */
        public Relation(String head, String relation, String tail, String rule, String chunkId, String cite,
                        String sentence) {
            this(head, relation, tail, rule, chunkId, cite, sentence, null, null);
        }

        /** The window this relation holds in. */
        public Validity validity() {
            return new Validity(validFrom, validTo);
        }

        /** Whether the relation held at {@code instant} (epoch millis). */
        public boolean validAt(long instant) {
            return validity().validAt(instant);
        }
    }

    /** One chunk of one document (the citation unit). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chunk(String chunkId, String documentId, String title, int ordinal, String text) {
    }

    /** The chunk of a citation, or null when the index does not hold it. */
    public Chunk chunk(String chunkId) {
        return chunks.stream().filter(chunk -> chunk.chunkId().equals(chunkId)).findFirst().orElse(null);
    }

    /** The entities whose name or alias matches a term, best (longest) match first. */
    public List<Entity> match(String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        String needle = term.strip();
        List<Entity> exact = entities.stream().filter(entity -> needle.equals(entity.id())
                || entity.aliases().contains(needle)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return entities.stream()
                .filter(entity -> needle.contains(entity.id()) || entity.id().contains(needle)
                        || entity.aliases().stream().anyMatch(alias -> needle.contains(alias) || alias.contains(needle)))
                .sorted((a, b) -> Integer.compare(b.id().length(), a.id().length()))
                .toList();
    }

    /** The entity with an id, or null. */
    public Entity entity(String id) {
        return entities.stream().filter(entity -> entity.id().equals(id)).findFirst().orElse(null);
    }

    /** A term-to-entities postings map, for the recall stage (names split into the pieces a question uses). */
    public Map<String, List<Entity>> postings() {
        Map<String, List<Entity>> postings = new LinkedHashMap<>();
        for (Entity entity : entities) {
            for (String term : termsOf(entity)) {
                postings.computeIfAbsent(term, key -> new ArrayList<>()).add(entity);
            }
        }
        return postings;
    }

    /** The searchable terms of an entity: its name, aliases and every 2-character slice (Chinese-friendly). */
    public static Set<String> termsOf(Entity entity) {
        Set<String> terms = new LinkedHashSet<>();
        String name = entity.name() == null ? entity.id() : entity.name();
        addTerms(terms, name);
        for (String alias : entity.aliases()) {
            addTerms(terms, alias);
        }
        return terms;
    }

    private static void addTerms(Set<String> terms, String name) {
        if (name != null && name.length() >= 2) {
            terms.add(name);
            for (int i = 0; i + 2 <= name.length(); i++) {
                terms.add(name.substring(i, i + 2));
            }
        }
    }

    /** Writes the artifact (JSON) to a path, creating the parent directory. */
    public void write(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the agent index to " + path, e);
        }
    }

    /** Reads an artifact back. */
    public static AgentIndex read(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), AgentIndex.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the agent index from " + path, e);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The mapper the artifact and every tool answer are written with. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
