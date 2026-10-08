package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.AgentIndex;
import com.pengcheng.phoenixgraph.agent.AgentQuery;
import com.pengcheng.phoenixgraph.agent.Chunker;
import com.pengcheng.phoenixgraph.agent.Embedding;
import com.pengcheng.phoenixgraph.agent.KnowledgeExtractor;
import com.pengcheng.phoenixgraph.agent.QualityReport;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgePropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.SchemaDraft;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Documents in, a queryable knowledge graph out - and the artifact the AI tools read.
 *
 * <p>One call does the whole pipeline: chunk the documents, extract entities and relations (with
 * provenance), measure how good that reading is, create the graph's schema if it does not exist, write
 * the vertices and edges through the ordinary write path (so the vector index is maintained by the same
 * code that maintains every other graph's), and leave an {@link AgentIndex} next to the schema file.
 *
 * <p>It is deliberately not a black box. The response is the {@link QualityReport}'s metrics, the file it
 * wrote, and how many vertices/edges went in, and the same numbers are readable afterwards from the
 * artifact. {@code apply=false} runs the whole pipeline and writes nothing, which is the way to look at
 * what an ingestion <em>would</em> produce before trusting it with a graph.
 *
 * <p>The write path needs a store that accepts REST writes (Aerospike today; the RocksDB schemes are
 * loaded by their loaders), so ingestion is refused with that message rather than half done. The
 * <em>query</em> half has no such limit: the artifact plus the graph engine answer on any scheme.
 */
@Service
public class AgentIngestService {

    /** The vertex type of a document chunk. */
    public static final String CHUNK_TYPE = "Chunk";
    /** The vertex type of an extracted entity. */
    public static final String ENTITY_TYPE = "Entity";
    /** The edge type between two entities. */
    public static final String RELATION_EDGE = "Relates";
    /** The edge type from a chunk to an entity it mentions. */
    public static final String MENTION_EDGE = "Mentions";

    /** Property indices of {@link #ENTITY_TYPE} (the declaration order of the schema below). */
    private static final int ENTITY_NAME = 1;
    private static final int ENTITY_KIND = 2;
    private static final int ENTITY_DESCRIPTION = 3;
    private static final int ENTITY_CHUNKS = 4;
    private static final int ENTITY_VECTOR = 5;

    /** Property indices of {@link #CHUNK_TYPE}. */
    private static final int CHUNK_DOCUMENT = 1;
    private static final int CHUNK_TITLE = 2;
    private static final int CHUNK_ORDINAL = 3;
    private static final int CHUNK_TEXT = 4;

    /** Property indices of {@link #RELATION_EDGE}. */
    private static final int RELATION_NAME = 3;
    private static final int RELATION_RULE = 4;
    private static final int RELATION_SENTENCE = 5;
    private static final int RELATION_CHUNK = 6;

    /** Property indices of {@link #MENTION_EDGE}. */
    private static final int MENTION_SENTENCE = 3;

    private final GraphAuthoringService authoring;
    private final GraphRegistry graphs;
    private final GraphService writes;
    private final QueryService queries;

    public AgentIngestService(GraphAuthoringService authoring, GraphRegistry graphs, GraphService writes,
                              QueryService queries) {
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /** A document of the request. */
    public record DocumentRequest(String id, String title, String text) {
    }

    /**
     * What to ingest.
     *
     * @param graph      the graph to build (created when it does not exist)
     * @param documents  the documents themselves
     * @param dictionary optional known entities (name to type), the gazetteer
     * @param aliases    optional alias to canonical name
     * @param gold       optional gold triples ({@code head|relation|tail}) for the accuracy metrics
     * @param apply      whether to write (false runs the pipeline and only reports)
     * @param replace    whether an existing graph may be replaced (its vertices are upserted either way)
     */
    public record IngestRequest(String graph, String description, List<DocumentRequest> documents,
                                Map<String, String> dictionary, Map<String, String> aliases, List<String> gold,
                                Boolean apply, Boolean replace) {

        public IngestRequest {
            documents = documents == null ? List.of() : List.copyOf(documents);
            dictionary = dictionary == null ? Map.of() : Map.copyOf(dictionary);
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
            gold = gold == null ? List.of() : List.copyOf(gold);
        }
    }

    /** What the ingestion did. */
    public record IngestResult(String graph, boolean applied, Map<String, Object> quality, int entities,
                               int relations, int vertices, int edges, String indexFile, List<String> notes) {
    }

    /** Runs the pipeline (and writes, when the request asks for it). */
    public IngestResult ingest(IngestRequest request) {
        if (request.graph() == null || request.graph().isBlank()) {
            throw new IllegalArgumentException("The ingestion needs a graph name");
        }
        List<Chunker.Document> documents = new ArrayList<>();
        for (DocumentRequest document : request.documents()) {
            if (document == null || document.text() == null || document.text().isBlank()) {
                continue;
            }
            documents.add(new Chunker.Document(document.id() == null || document.id().isBlank()
                    ? "doc-" + (documents.size() + 1) : document.id(), document.title(), document.text()));
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("The ingestion needs at least one document with text");
        }

        KnowledgeExtractor.Options options = new KnowledgeExtractor.Options(request.dictionary(), request.aliases());
        KnowledgeExtractor.Result extraction = KnowledgeExtractor.extract(documents, options);
        QualityReport report = QualityReport.of(extraction, options, request.gold());
        AgentIndex index = toIndex(request.graph(), extraction, report);

        boolean apply = !Boolean.FALSE.equals(request.apply());
        if (!apply) {
            return new IngestResult(request.graph(), false, report.metrics(), index.entities().size(),
                    index.relations().size(), 0, 0, null,
                    List.of("dry run: nothing was written (pass apply=true to build the graph)"));
        }

        List<String> notes = new ArrayList<>();
        ensureSchema(request.graph(), request.description(), notes);
        BatchRequest batch = toBatch(index);
        GraphService target = writes.forGraph(graphs, request.graph());
        List<WriteResult> results = target.processBatch(batch);
        long failed = results.stream().filter(result -> result != null && result.status() != null
                && result.status().equals("FAILED")).count();
        if (failed > 0) {
            notes.add(failed + " writes failed; see the write results");
        }
        Path file = indexFile(request.graph());
        index.write(file);
        return new IngestResult(request.graph(), true, report.metrics(), index.entities().size(),
                index.relations().size(), batch.vertices().size(), batch.edges().size(),
                file == null ? null : file.toString(), notes);
    }

    /** The artifact of a graph, or a message saying which file is missing. */
    public AgentIndex index(String graph) {
        Path file = indexFile(graph);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No agent index for graph '" + graph + "' at " + file.toAbsolutePath()
                    + ": ingest its documents first (POST /api/v1/agent/ingest)");
        }
        return AgentIndex.read(file);
    }

    /** The tools of a graph: the artifact for recall, the graph engine for expansion. */
    public AgentQuery tools(String graph) {
        AgentIndex index = index(graph);
        return new AgentQuery(gremlin -> {
            Object body = queries.queryCapped(graph, gremlin, Map.of(), "gremlin", false, true, "algorithm", null)
                    .body();
            if (body instanceof List<?> list) {
                return new ArrayList<>(list);
            }
            return List.of();
        }, index, Embedding.hashing(index.dimensions()));
    }

    // ------------------------------------------------------------------ the graph it writes

    /**
     * Creates the graph when it is not served yet: two vertex types (the chunks, and the entities with
     * their embedding) and two edge types (entity to entity, chunk to entity). The embedding property is
     * what makes {@code nearest(k)} work on the result, because the vector index is declared in the schema.
     */
    private void ensureSchema(String graph, String description, List<String> notes) {
        boolean known = graphs.schemas().stream().anyMatch(schema -> schema.name().equals(graph));
        SchemaDraft draft = schemaDraft(graph, description);
        if (known) {
            notes.add("graph '" + graph + "' already exists: its schema was left as it is and the extracted "
                    + "vertices/edges were upserted into it");
            return;
        }
        GraphAuthoringService.SaveResult saved = authoring.save(draft, true);
        if (!saved.saved()) {
            throw new IllegalArgumentException("Cannot create graph '" + graph + "': " + saved.errors());
        }
        notes.add("created graph '" + graph + "' (" + saved.file() + ")");
    }

    /** The schema of an ingested graph: the shape every ingestion uses, so tools always find it. */
    private static SchemaDraft schemaDraft(String graph, String description) {
        List<SchemaDraft.PropertyDraft> entity = List.of(
                new SchemaDraft.PropertyDraft("name", "string", "The name the documents use", true, false, false,
                        null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("entityType", "string", "Person / Place / Mention ...", false, false,
                        false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("description", "string",
                        "The context card the embedding was built from", false, false, false, null, null, null,
                        null, null, null),
                new SchemaDraft.PropertyDraft("chunkIds", "list<string>", "The chunks this entity was read from",
                        false, false, false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("embedding", "list<double>", "The entity's vector (HNSW indexed)",
                        false, true, false, null, null, null, null, null, null));
        List<SchemaDraft.PropertyDraft> chunk = List.of(
                new SchemaDraft.PropertyDraft("documentId", "string", "The document id", false, false, false,
                        null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("title", "string", "The document title", false, false, false,
                        null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("ordinal", "long", "The position of the chunk in its document",
                        false, false, false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("text", "string", "The chunk itself", false, false, false,
                        null, null, null, null, null, null));
        List<SchemaDraft.PropertyDraft> relation = List.of(
                new SchemaDraft.PropertyDraft("relation", "string", "The canonical relation name", false, false,
                        false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("rule", "string", "The rule that read it", false, false, false,
                        null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("sentence", "string", "The sentence it was read from", false, false,
                        false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft("chunkId", "string", "The chunk it was read from", false, false,
                        false, null, null, null, null, null, null));
        List<SchemaDraft.PropertyDraft> mention = List.of(
                new SchemaDraft.PropertyDraft("sentence", "string", "The sentence the mention occurs in",
                        false, false, false, null, null, null, null, null, null));
        return new SchemaDraft(graph,
                description == null || description.isBlank()
                        ? "Built by the agent toolkit from documents (entities, relations, chunks and vectors)"
                        : description,
                "string", new SchemaDraft.StorageDraft("Aerospike", null, null, null, null),
                List.of(new SchemaDraft.VertexDraft(ENTITY_TYPE, "An extracted entity", "name", entity),
                        new SchemaDraft.VertexDraft(CHUNK_TYPE, "A chunk of an ingested document", null, chunk)),
                List.of(new SchemaDraft.EdgeDraft(RELATION_EDGE, "An extracted relation", "DIRECTED",
                                ENTITY_TYPE, ENTITY_TYPE, false, null, null, null, relation),
                        new SchemaDraft.EdgeDraft(MENTION_EDGE, "A chunk mentions an entity", "DIRECTED",
                                CHUNK_TYPE, ENTITY_TYPE, false, null, null, null, mention)));
    }

    /** The artifact as the vertices and edges the write path takes. */
    private BatchRequest toBatch(AgentIndex index) {
        List<VertexRequest> vertices = new ArrayList<>();
        List<EdgeRequest> edges = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AgentIndex.Entity entity : index.entities()) {
            List<VertexPropertyRequest> properties = List.of(
                    new VertexPropertyRequest(ENTITY_NAME, entity.name(), null),
                    new VertexPropertyRequest(ENTITY_KIND, entity.type(), null),
                    new VertexPropertyRequest(ENTITY_DESCRIPTION, entity.description(), null),
                    new VertexPropertyRequest(ENTITY_CHUNKS, entity.chunkIds(), null),
                    new VertexPropertyRequest(ENTITY_VECTOR, toDoubles(entity.vector()), null));
            vertices.add(new VertexRequest(entity.id(), ENTITY_TYPE, properties, now, now));
        }
        for (AgentIndex.Chunk chunk : index.chunks()) {
            List<VertexPropertyRequest> properties = List.of(
                    new VertexPropertyRequest(CHUNK_DOCUMENT, chunk.documentId(), null),
                    new VertexPropertyRequest(CHUNK_TITLE, chunk.title(), null),
                    new VertexPropertyRequest(CHUNK_ORDINAL, (long) chunk.ordinal(), null),
                    new VertexPropertyRequest(CHUNK_TEXT, chunk.text(), null));
            vertices.add(new VertexRequest(chunk.chunkId(), CHUNK_TYPE, properties, now, now));
        }
        int sequence = 0;
        for (AgentIndex.Relation relation : index.relations()) {
            String edgeId = "rel-" + (sequence++);
            edges.add(new EdgeRequest(edgeId, RELATION_EDGE, relation.head(), relation.tail(),
                    List.of(new EdgePropertyRequest(RELATION_NAME, relation.relation(), now, now),
                            new EdgePropertyRequest(RELATION_RULE, relation.rule(), now, now),
                            new EdgePropertyRequest(RELATION_SENTENCE, relation.sentence(), now, now),
                            new EdgePropertyRequest(RELATION_CHUNK, relation.chunkId(), now, now)),
                    now, now));
        }
        for (AgentIndex.Entity entity : index.entities()) {
            String sentence = firstSentence(entity, index);
            for (String chunkId : entity.chunkIds()) {
                edges.add(new EdgeRequest("mention-" + chunkId + "-" + entity.id(), MENTION_EDGE, chunkId, entity.id(),
                        List.of(new EdgePropertyRequest(MENTION_SENTENCE, sentence, now, now)), now, now));
            }
        }
        return new BatchRequest(vertices, edges, null);
    }

    /** The first sentence that mentions an entity, for the mention edge's own provenance. */
    private static String firstSentence(AgentIndex.Entity entity, AgentIndex index) {
        for (AgentIndex.Relation relation : index.relations()) {
            if (relation.head().equals(entity.id()) || relation.tail().equals(entity.id())) {
                return relation.sentence();
            }
        }
        AgentIndex.Chunk chunk = entity.chunkIds().isEmpty() ? null : index.chunk(entity.chunkIds().get(0));
        return chunk == null ? "" : chunk.text();
    }

    /** A float vector as the double list the storage takes. */
    private static List<Double> toDoubles(float[] vector) {
        List<Double> values = new ArrayList<>(vector == null ? 0 : vector.length);
        if (vector != null) {
            for (float value : vector) {
                values.add((double) value);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ the index artifact

    /** Builds the artifact: the entities with their context cards and vectors, and the relations. */
    private AgentIndex toIndex(String graph, KnowledgeExtractor.Result extraction, QualityReport report) {
        Embedding embedding = Embedding.hashing();
        Map<String, List<Chunker.Chunk>> chunksByEntity = new LinkedHashMap<>();
        for (Chunker.Chunk chunk : extraction.chunks()) {
            for (String sentence : Chunker.sentences(chunk.text())) {
                for (KnowledgeExtractor.Entity entity : extraction.entities()) {
                    if (sentence.contains(entity.id())) {
                        chunksByEntity.computeIfAbsent(entity.id(), key -> new ArrayList<>()).add(chunk);
                    }
                }
            }
        }
        List<AgentIndex.Entity> entities = new ArrayList<>();
        for (KnowledgeExtractor.Entity entity : extraction.entities()) {
            List<Chunker.Chunk> chunks = chunksByEntity.getOrDefault(entity.id(), List.of());
            String description = describe(entity, extraction, chunks);
            Set<String> chunkIds = new LinkedHashSet<>();
            List<String> cites = new ArrayList<>();
            for (Chunker.Chunk chunk : chunks) {
                chunkIds.add(chunk.chunkId());
                cites.add(chunk.cite());
            }
            for (String chunkId : entity.chunkIds()) {
                chunkIds.add(chunkId);
            }
            entities.add(new AgentIndex.Entity(entity.id(), ENTITY_TYPE, entity.type(), entity.name(), description,
                    embedding.embed(description), List.copyOf(chunkIds), List.copyOf(cites)));
        }
        Map<String, String> cites = new LinkedHashMap<>();
        for (Chunker.Chunk chunk : extraction.chunks()) {
            cites.put(chunk.chunkId(), chunk.cite());
        }
        List<AgentIndex.Relation> relations = new ArrayList<>();
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            relations.add(new AgentIndex.Relation(relation.head(), relation.relation(), relation.tail(),
                    relation.rule(), relation.chunkId(), cites.getOrDefault(relation.chunkId(), relation.chunkId()),
                    relation.sentence()));
        }
        List<AgentIndex.Chunk> chunks = new ArrayList<>();
        for (Chunker.Chunk chunk : extraction.chunks()) {
            chunks.add(new AgentIndex.Chunk(chunk.chunkId(), chunk.documentId(), chunk.title(), chunk.ordinal(),
                    chunk.text()));
        }
        return new AgentIndex(graph, "hashing-" + embedding.dimensions(), embedding.dimensions(),
                Instant.now().toString(), entities, relations, chunks, report.metrics());
    }

    /**
     * The context card an entity's vector is built from: what it is, what it is related to, and the
     * sentences it appears in. A card is a far better thing to embed than a name - "关羽，Person；relation:
     * killed 颜良；sentence: 关羽斩杀颜良" has something for the vector to work with.
     */
    private static String describe(KnowledgeExtractor.Entity entity, KnowledgeExtractor.Result extraction,
                                   List<Chunker.Chunk> chunks) {
        StringBuilder card = new StringBuilder(entity.name()).append('，').append(entity.type());
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            if (relation.head().equals(entity.id())) {
                card.append("；").append(relation.relation()).append(' ').append(relation.tail());
            } else if (relation.tail().equals(entity.id())) {
                card.append("；被 ").append(relation.head()).append(' ').append(relation.relation());
            }
        }
        for (Chunker.Chunk chunk : chunks) {
            card.append('。').append(chunk.text());
            if (card.length() > 600) {
                break; // a card is a summary: the whole document is not the entity
            }
        }
        return card.toString();
    }

    /**
     * Where the artifact of a graph lives: in the working directory, next to where a schema created at
     * runtime would be. A shipped schema (on the classpath) has no writable directory of its own, so the
     * artifact always goes beside the process - one place to look for it, and one place to back it up.
     */
    public Path indexFile(String graph) {
        return Path.of(graph + ".agent.json");
    }

    /** Reads a text file for a caller that gives paths instead of text (the CLI-ish entry point). */
    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
