package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.AgentIndex;
import com.pengcheng.phoenixgraph.agent.AgentQuery;
import com.pengcheng.phoenixgraph.agent.Chunker;
import com.pengcheng.phoenixgraph.agent.Embedding;
import com.pengcheng.phoenixgraph.agent.KnowledgeExtractor;
import com.pengcheng.phoenixgraph.agent.QualityReport;
import com.pengcheng.phoenixgraph.agent.RulePack;
import com.pengcheng.phoenixgraph.agent.Validity;
import com.pengcheng.phoenixgraph.http.dto.BatchRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgePropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.SchemaDraft;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.http.dto.WriteResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
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
    private static final int ENTITY_VALID_FROM = 6;
    private static final int ENTITY_VALID_TO = 7;

    /** Property indices of {@link #CHUNK_TYPE}. */
    private static final int CHUNK_DOCUMENT = 1;
    private static final int CHUNK_TITLE = 2;
    private static final int CHUNK_ORDINAL = 3;
    private static final int CHUNK_TEXT = 4;

    /** Property indices of {@link #RELATION_EDGE} (the declaration order of the schema below). */
    private static final int RELATION_NAME = 1;
    private static final int RELATION_RULE = 2;
    private static final int RELATION_SENTENCE = 3;
    private static final int RELATION_CHUNK = 4;
    private static final int RELATION_VALID_FROM = 5;
    private static final int RELATION_VALID_TO = 6;

    /** Property indices of {@link #MENTION_EDGE}. */
    private static final int MENTION_SENTENCE = 1;

    private final GraphAuthoringService authoring;
    private final GraphRegistry graphs;
    private final GraphService writes;
    private final QueryService queries;
    private final EmbeddingFactory embeddings;
    private final RulePackRegistry rules;
    private final RerankerFactory rerankers;

    public AgentIngestService(GraphAuthoringService authoring, GraphRegistry graphs, GraphService writes,
                              QueryService queries, EmbeddingFactory embeddings, RulePackRegistry rules, RerankerFactory rerankers) {
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.rerankers = Objects.requireNonNull(rerankers, "rerankers");
    }

    /**
     * A document of the request.
     *
     * <p>It may state when its facts hold: {@code validFrom} is inclusive and {@code validTo} exclusive, in
     * ISO-8601 ({@code 2026-10-24}, {@code 2026-10-24T10:00:00Z}) or epoch millis. Either end may be absent -
     * a topology document is valid from the beginning of time, a change window runs to the next change. A
     * document with neither is unversioned, and every fact read from it is valid at every instant.
     */
    public record DocumentRequest(String id, String title, String text, String validFrom, String validTo) {

        /** A document with no window: its facts hold at every instant. */
        public DocumentRequest(String id, String title, String text) {
            this(id, title, text, null, null);
        }
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
     * @param rulePack   the extraction vocabulary: {@code sanguo} (default), {@code incident}, or a pack in
     *                   {@code agent-rules/<name>.json}
     * @param dedupe     merge names that differ only by case/whitespace/punctuation (default true)
     */
    public record IngestRequest(String graph, String description, List<DocumentRequest> documents,
                                Map<String, String> dictionary, Map<String, String> aliases, List<String> gold,
                                Boolean apply, Boolean replace, String rulePack, Boolean dedupe) {

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
        // The window each document's facts hold in. A document may state when it happened; a fact without a
        // window is valid at every instant, which is what every graph built before this dimension was.
        Map<String, Validity> windows = new LinkedHashMap<>();
        for (DocumentRequest document : request.documents()) {
            if (document == null || document.text() == null || document.text().isBlank()) {
                continue;
            }
            String documentId = document.id() == null || document.id().isBlank()
                    ? "doc-" + (documents.size() + 1) : document.id();
            documents.add(new Chunker.Document(documentId, document.title(), document.text()));
            Validity window = Validity.parse(document.validFrom(), document.validTo());
            if (!window.isAlways()) {
                windows.put(documentId, window);
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("The ingestion needs at least one document with text");
        }

        RulePack pack = rules.resolve(request.rulePack());
        KnowledgeExtractor.Options options = new KnowledgeExtractor.Options(request.dictionary(), request.aliases(), pack,
                !Boolean.FALSE.equals(request.dedupe()));
        KnowledgeExtractor.Result extraction = KnowledgeExtractor.extract(documents, options);
        QualityReport report = QualityReport.of(extraction, options, request.gold());
        AgentIndex index = toIndex(request.graph(), extraction, report, windows);

        boolean apply = !Boolean.FALSE.equals(request.apply());
        if (!apply) {
            return new IngestResult(request.graph(), false, report.metrics(), index.entities().size(),
                    index.relations().size(), 0, 0, null,
                    List.of("dry run: nothing was written (pass apply=true to build the graph)",
                            "rule pack: " + pack.name()));
        }

        List<String> notes = new ArrayList<>();
        notes.add("rule pack: " + pack.name());
        ensureSchema(request.graph(), request.description(), notes);
        index = mergeExistingChunks(request.graph(), index, notes);
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
        }, index, embeddings.forIndex(index.embedding()), "Relates", rerankers.current());
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
    static SchemaDraft schemaDraft(String graph, String description) {
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
        List<SchemaDraft.PropertyDraft> temporal = List.of(
                new SchemaDraft.PropertyDraft(Validity.FROM_PROPERTY, "long",
                        "When this fact started to hold (epoch millis, inclusive); absent means always", false, false,
                        false, null, null, null, null, null, null),
                new SchemaDraft.PropertyDraft(Validity.TO_PROPERTY, "long",
                        "When it stopped holding (epoch millis, exclusive); absent means still holds", false, false,
                        false, null, null, null, null, null, null));
        List<SchemaDraft.PropertyDraft> mention = List.of(
                new SchemaDraft.PropertyDraft("sentence", "string", "The sentence the mention occurs in",
                        false, false, false, null, null, null, null, null, null));
        return new SchemaDraft(graph,
                description == null || description.isBlank()
                        ? "Built by the agent toolkit from documents (entities, relations, chunks and vectors)"
                        : description,
                "string", new SchemaDraft.StorageDraft("Aerospike", null, null, null, null),
                List.of(new SchemaDraft.VertexDraft(ENTITY_TYPE, "An extracted entity", "name",
                                concat(entity, temporal)),
                        new SchemaDraft.VertexDraft(CHUNK_TYPE, "A chunk of an ingested document", null, chunk)),
                List.of(new SchemaDraft.EdgeDraft(RELATION_EDGE, "An extracted relation", "DIRECTED",
                                ENTITY_TYPE, ENTITY_TYPE, false, null, null, null, concat(relation, temporal)),
                        new SchemaDraft.EdgeDraft(MENTION_EDGE, "A chunk mentions an entity", "DIRECTED",
                                CHUNK_TYPE, ENTITY_TYPE, false, null, null, null, mention)));
    }

    /** Two property lists, one after the other (the schema draft takes them as one). */
    private static List<SchemaDraft.PropertyDraft> concat(List<SchemaDraft.PropertyDraft> first,
                                                          List<SchemaDraft.PropertyDraft> second) {
        List<SchemaDraft.PropertyDraft> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /** The artifact as the vertices and edges the write path takes. */
    static BatchRequest toBatch(AgentIndex index) {
        List<VertexRequest> vertices = new ArrayList<>();
        List<EdgeRequest> edges = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AgentIndex.Entity entity : index.entities()) {
            List<VertexPropertyRequest> properties = new ArrayList<>(List.of(
                    new VertexPropertyRequest(ENTITY_NAME, entity.name(), null),
                    new VertexPropertyRequest(ENTITY_KIND, entity.type(), null),
                    new VertexPropertyRequest(ENTITY_DESCRIPTION, entity.description(), null),
                    new VertexPropertyRequest(ENTITY_CHUNKS, entity.chunkIds(), null),
                    new VertexPropertyRequest(ENTITY_VECTOR, toDoubles(entity.vector()), null)));
            addWindow(properties, entity.validFrom(), ENTITY_VALID_FROM, entity.validTo(), ENTITY_VALID_TO);
            vertices.add(new VertexRequest(entity.id(), ENTITY_TYPE, List.copyOf(properties), now, now));
        }
        for (AgentIndex.Chunk chunk : index.chunks()) {
            List<VertexPropertyRequest> properties = List.of(
                    new VertexPropertyRequest(CHUNK_DOCUMENT, chunk.documentId(), null),
                    new VertexPropertyRequest(CHUNK_TITLE, chunk.title(), null),
                    new VertexPropertyRequest(CHUNK_ORDINAL, (long) chunk.ordinal(), null),
                    new VertexPropertyRequest(CHUNK_TEXT, chunk.text(), null));
            vertices.add(new VertexRequest(chunk.chunkId(), CHUNK_TYPE, properties, now, now));
        }
        for (AgentIndex.Relation relation : index.relations()) {
            // The id has to be a function of the triple, not of the position in this ingestion: re-ingesting a
            // document is documented as idempotent, and a `rel-<n>` counter made every run write a fresh set of
            // edges whose ids happened to collide with the previous run's different triples - the graph ended up
            // holding both readings of the same document. A hash keeps the id stable without needing a separator
            // that cannot appear in an extracted name ("payment service" contains a space, "deploy-48" a dash).
            String edgeId = relationEdgeId(relation.head(), relation.relation(), relation.tail());
            edges.add(new EdgeRequest(edgeId, RELATION_EDGE, relation.head(), relation.tail(),
                    relationProperties(relation, now), now, now));
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

    /**
     * The id of a relation edge: stable across ingestions, and different for every (head, relation, tail). The
     * triple is hashed rather than concatenated because any separator could also appear inside an extracted name.
     */
    static String relationEdgeId(String head, String relation, String tail) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((head + '\u001f' + relation + '\u001f' + tail).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("rel-");
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /** Adds the two window properties of a vertex, when the fact has a window at all. */
    private static void addWindow(List<VertexPropertyRequest> properties, Long from, int fromIndex,
                                  Long to, int toIndex) {
        if (from != null) {
            properties.add(new VertexPropertyRequest(fromIndex, from, null));
        }
        if (to != null) {
            properties.add(new VertexPropertyRequest(toIndex, to, null));
        }
    }

    /**
     * The properties of a relation edge, including its window. The window is written only when the fact has
     * one: an absent property is what makes an element valid at every instant, so writing 0 would mean
     * "valid since 1970".
     */
    private static List<EdgePropertyRequest> relationProperties(AgentIndex.Relation relation, long now) {
        List<EdgePropertyRequest> properties = new ArrayList<>(List.of(
                new EdgePropertyRequest(RELATION_NAME, relation.relation(), now, now),
                new EdgePropertyRequest(RELATION_RULE, relation.rule(), now, now),
                new EdgePropertyRequest(RELATION_SENTENCE, relation.sentence(), now, now),
                new EdgePropertyRequest(RELATION_CHUNK, relation.chunkId(), now, now)));
        if (relation.validFrom() != null) {
            properties.add(new EdgePropertyRequest(RELATION_VALID_FROM, relation.validFrom(), now, now));
        }
        if (relation.validTo() != null) {
            properties.add(new EdgePropertyRequest(RELATION_VALID_TO, relation.validTo(), now, now));
        }
        return List.copyOf(properties);
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
    private AgentIndex toIndex(String graph, KnowledgeExtractor.Result extraction, QualityReport report,
                               Map<String, Validity> windows) {
        // A chunk id is "<documentId>#<ordinal>", so the window of a fact is the window of its document.
        java.util.function.Function<String, Validity> windowOf = chunkId -> {
            if (chunkId == null) {
                return Validity.always();
            }
            int hash = chunkId.lastIndexOf('#');
            String documentId = hash < 0 ? chunkId : chunkId.substring(0, hash);
            return windows.getOrDefault(documentId, Validity.always());
        };
        Embedding embedding = embeddings.current();
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
            // An entity read in two documents exists for both, so its window is the union of theirs - but one
            // structural (unversioned) mention makes it permanent, which is why "always" is tracked apart from
            // "no window seen yet": folding both into one accumulator would make every entity unbounded.
            List<Validity> entityWindows = new ArrayList<>();
            for (String chunkId : chunkIds) {
                entityWindows.add(windowOf.apply(chunkId));
            }
            Long validFrom = null;
            Long validTo = null;
            if (!entityWindows.isEmpty() && entityWindows.stream().noneMatch(Validity::isAlways)) {
                for (Validity window : entityWindows) {
                    // a null end means "for ever", and "for ever" wins the union: min/max cannot compare it
                    if (window.from() != null) {
                        validFrom = validFrom == null ? window.from() : Math.min(validFrom, window.from());
                    }
                    if (window.to() == null) {
                        validTo = null;
                    } else if (validTo != null) {
                        validTo = Math.max(validTo, window.to());
                    }
                }
            }
            entities.add(new AgentIndex.Entity(entity.id(), ENTITY_TYPE, entity.type(), entity.name(), description,
                    embedding.embed(description), List.copyOf(chunkIds), List.copyOf(cites), entity.aliases(),
                    validFrom, validTo));
        }
        Map<String, String> cites = new LinkedHashMap<>();
        for (Chunker.Chunk chunk : extraction.chunks()) {
            cites.put(chunk.chunkId(), chunk.cite());
        }
        List<AgentIndex.Relation> relations = new ArrayList<>();
        for (KnowledgeExtractor.Relation relation : extraction.relations()) {
            Validity window = windowOf.apply(relation.chunkId());
            relations.add(new AgentIndex.Relation(relation.head(), relation.relation(), relation.tail(),
                    relation.rule(), relation.chunkId(), cites.getOrDefault(relation.chunkId(), relation.chunkId()),
                    relation.sentence(), window.from(), window.to()));
        }
        List<AgentIndex.Chunk> chunks = new ArrayList<>();
        for (Chunker.Chunk chunk : extraction.chunks()) {
            chunks.add(new AgentIndex.Chunk(chunk.chunkId(), chunk.documentId(), chunk.title(), chunk.ordinal(),
                    chunk.text()));
        }
        return new AgentIndex(graph, embedding.name(), embedding.dimensions(),
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

    /**
     * Merges an entity's chunks from an earlier ingestion into this one, so the same entity read in two
     * documents keeps both provenance lists instead of the second overwriting the first. The description and
     * vector stay the current reading (a context card is a snapshot, not a sum); what accumulates is the
     * {@code chunkIds} list, which is what a citation and a coverage count read.
     */
    private AgentIndex mergeExistingChunks(String graph, AgentIndex current, List<String> notes) {
        Path file = indexFile(graph);
        if (!Files.exists(file)) {
            return current;
        }
        Map<String, Integer> currentSizes = new LinkedHashMap<>();
        for (AgentIndex.Entity entity : current.entities()) {
            currentSizes.put(entity.id(), entity.chunkIds().size());
        }
        AgentIndex merged = mergeChunks(AgentIndex.read(file), current);
        long extended = merged.entities().stream()
                .filter(entity -> entity.chunkIds().size() > currentSizes.getOrDefault(entity.id(), 0))
                .count();
        if (extended > 0) {
            notes.add(extended + " entities carried chunks over from an earlier ingestion of '" + graph + "'");
        }
        return merged;
    }

    /** The pure merge: an entity keeps its old chunks plus the new ones, keyed by id. */
    static AgentIndex mergeChunks(AgentIndex previous, AgentIndex current) {
        Map<String, Set<String>> prior = new LinkedHashMap<>();
        for (AgentIndex.Entity entity : previous.entities()) {
            prior.put(entity.id(), new LinkedHashSet<>(entity.chunkIds()));
        }
        List<AgentIndex.Entity> merged = new ArrayList<>();
        for (AgentIndex.Entity entity : current.entities()) {
            Set<String> ids = new LinkedHashSet<>(prior.getOrDefault(entity.id(), Set.of()));
            ids.addAll(entity.chunkIds());
            if (ids.size() == entity.chunkIds().size()) {
                merged.add(entity);
            } else {
                merged.add(new AgentIndex.Entity(entity.id(), entity.vertexType(), entity.type(), entity.name(),
                        entity.description(), entity.vector(), List.copyOf(ids), entity.cites(), entity.aliases()));
            }
        }
        return new AgentIndex(current.graph(), current.embedding(), current.dimensions(), current.createdAt(),
                merged, current.relations(), current.chunks(), current.quality());
    }

    // ------------------------------------------------------------------ the review workflow: merging two entities

    /** A read edge: the endpoints and properties a merge has to rewrite. */
    public record EdgeView(String label, Object id, Object source, Object target, Map<String, Object> properties) {
    }

    /** What a merge will do: the edges to write, the edges to delete, and the merged chunk ids. */
    public record MergePlan(List<EdgeView> writes, List<Object[]> deletes, List<String> chunkIds) {
    }

    /** What the merge did. */
    public record MergeResult(String graph, String keep, String drop, int edgesRewritten, int chunkCount,
                              String indexFile) {
    }

    /**
     * Merges one entity into another: the {@code drop} vertex is folded into {@code keep}, its edges are
     * rewritten to point at {@code keep}, its name becomes an alias, and the artifact and the graph agree.
     *
     * <p>This is the human-in-the-loop answer to a {@code mergeCandidates} suggestion: the deduper never
     * merges a near-duplicate on its own, but a reviewer can accept one here.
     */
    public MergeResult merge(String graph, String keep, String drop) {
        if (keep == null || drop == null || keep.isBlank() || drop.isBlank()) {
            throw new IllegalArgumentException("merge needs keep and drop, two entity ids");
        }
        if (keep.equals(drop)) {
            throw new IllegalArgumentException("keep and drop are the same entity: " + keep);
        }
        GraphService target = writes.forGraph(graphs, graph);
        if (target.getVertex(keep) == null) {
            throw new IllegalArgumentException("no entity '" + keep + "' in graph '" + graph + "'");
        }
        if (target.getVertex(drop) == null) {
            throw new IllegalArgumentException("no entity '" + drop + "' in graph '" + graph + "'");
        }
        List<EdgeView> edges = new ArrayList<>();
        edges.addAll(readEdges(graph, drop, "Relates"));
        edges.addAll(readEdges(graph, drop, "Mentions"));
        List<String> keepChunks = chunkIds(graph, keep);
        List<String> dropChunks = chunkIds(graph, drop);
        MergePlan plan = mergePlan(keep, drop, edges, keepChunks, dropChunks);

        long now = System.currentTimeMillis();
        for (Object[] deletion : plan.deletes()) {
            target.deleteEdge(deletion[0], String.valueOf(deletion[1]), deletion[2], deletion[3]);
        }
        for (EdgeView edge : plan.writes()) {
            target.upsertEdge(toEdgeRequest(edge, now));
        }
        if (!plan.chunkIds().equals(keepChunks)) {
            target.updateVertexProperty(keep, ENTITY_CHUNKS,
                    new com.pengcheng.phoenixgraph.http.dto.PropertyUpdateRequest(plan.chunkIds(), now));
        }
        target.deleteVertex(drop);

        AgentIndex index = index(graph);
        index = mergeIndex(index, keep, drop);
        Path file = indexFile(graph);
        index.write(file);
        return new MergeResult(graph, keep, drop, plan.writes().size(), plan.chunkIds().size(),
                file.toString());
    }

    /** The pure rewrite: every edge touching {@code drop} becomes an edge touching {@code keep}. */
    static MergePlan mergePlan(String keep, String drop, List<EdgeView> edges,
                               List<String> keepChunks, List<String> dropChunks) {
        List<EdgeView> writes = new ArrayList<>();
        List<Object[]> deletes = new ArrayList<>();
        for (EdgeView edge : edges) {
            Object source = drop.equals(edge.source()) ? keep : edge.source();
            Object target = drop.equals(edge.target()) ? keep : edge.target();
            writes.add(new EdgeView(edge.label(), edge.id(), source, target, edge.properties()));
            deletes.add(new Object[] {edge.id(), edge.label(), edge.source(), edge.target()});
        }
        Set<String> chunks = new LinkedHashSet<>(keepChunks);
        chunks.addAll(dropChunks);
        return new MergePlan(writes, deletes, List.copyOf(chunks));
    }

    /** The pure index merge: {@code drop} becomes an alias of {@code keep} and its relations move over. */
    static AgentIndex mergeIndex(AgentIndex index, String keep, String drop) {
        AgentIndex.Entity keepEntity = null;
        AgentIndex.Entity dropEntity = null;
        List<AgentIndex.Entity> others = new ArrayList<>();
        for (AgentIndex.Entity entity : index.entities()) {
            if (entity.id().equals(keep)) {
                keepEntity = entity;
            } else if (entity.id().equals(drop)) {
                dropEntity = entity;
            } else {
                others.add(entity);
            }
        }
        if (keepEntity == null) {
            throw new IllegalArgumentException("no entity '" + keep + "' in the index of '" + index.graph() + "'");
        }
        if (dropEntity == null) {
            throw new IllegalArgumentException("no entity '" + drop + "' in the index of '" + index.graph() + "'");
        }
        Set<String> chunks = new LinkedHashSet<>(keepEntity.chunkIds());
        chunks.addAll(dropEntity.chunkIds());
        List<String> aliases = new ArrayList<>(keepEntity.aliases());
        aliases.add(dropEntity.name());
        aliases.addAll(dropEntity.aliases());
        List<AgentIndex.Entity> entities = new ArrayList<>(others);
        entities.add(new AgentIndex.Entity(keepEntity.id(), keepEntity.vertexType(), keepEntity.type(),
                keepEntity.name(), keepEntity.description(), keepEntity.vector(), List.copyOf(chunks),
                keepEntity.cites(), List.copyOf(aliases)));
        List<AgentIndex.Relation> relations = new ArrayList<>();
        for (AgentIndex.Relation relation : index.relations()) {
            String head = drop.equals(relation.head()) ? keep : relation.head();
            String tail = drop.equals(relation.tail()) ? keep : relation.tail();
            relations.add(new AgentIndex.Relation(head, relation.relation(), tail, relation.rule(),
                    relation.chunkId(), relation.cite(), relation.sentence()));
        }
        return new AgentIndex(index.graph(), index.embedding(), index.dimensions(), index.createdAt(),
                entities, relations, index.chunks(), index.quality());
    }

    private List<EdgeView> readEdges(String graph, String vertex, String label) {
        List<Object> rows = queries.query(graph, "g.V('" + vertex + "','Entity').bothE('" + label + "')", Map.of());
        List<EdgeView> edges = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof GremlinEdge edge) {
                Object source = edge.side() == EdgeSide.IN ? edge.otherVertexId() : vertex;
                Object target = edge.side() == EdgeSide.IN ? vertex : edge.otherVertexId();
                edges.add(new EdgeView(edge.label(), edge.id(), source, target, edge.properties()));
            }
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private List<String> chunkIds(String graph, String vertex) {
        List<Object> rows = queries.query(graph, "g.V('" + vertex + "','Entity').values('chunkIds')", Map.of());
        List<String> chunks = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof List<?> list) {
                for (Object item : list) {
                    chunks.add(String.valueOf(item));
                }
            } else if (row != null) {
                chunks.add(String.valueOf(row));
            }
        }
        return chunks;
    }

    private static EdgeRequest toEdgeRequest(EdgeView edge, long now) {
        List<EdgePropertyRequest> properties = new ArrayList<>();
        for (Map.Entry<String, Object> property : edge.properties().entrySet()) {
            int index = edgeIndex(edge.label(), property.getKey());
            if (index >= 0) {
                properties.add(new EdgePropertyRequest(index, property.getValue(), now, now));
            }
        }
        return new EdgeRequest(edge.id(), edge.label(), edge.source(), edge.target(), properties, now, now);
    }

    /** The property index of a named property on an agent edge, or -1 when the edge does not declare it. */
    private static int edgeIndex(String label, String name) {
        if ("Relates".equals(label)) {
            return switch (name) {
                case "relation" -> RELATION_NAME;
                case "rule" -> RELATION_RULE;
                case "sentence" -> RELATION_SENTENCE;
                case "chunk" -> RELATION_CHUNK;
                default -> -1;
            };
        }
        if ("Mentions".equals(label)) {
            return "sentence".equals(name) ? MENTION_SENTENCE : -1;
        }
        return -1;
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
