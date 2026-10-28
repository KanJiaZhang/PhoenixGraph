package com.pengcheng.phoenixgraph.agent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tools an AI calls, and the one answer shape they all return.
 *
 * <p>Four kinds of question, and the combinations between them, are what a knowledge graph is for:
 *
 * <ul>
 *   <li><b>vector</b> - {@link #similar(String, int)}: the entities whose meaning is nearest (the HNSW index
 *       the graph maintains);</li>
 *   <li><b>graph</b> - {@link #related(String, String, int)}: what an entity is connected to, with the
 *       relation names and the sentence each relation was read from;</li>
 *   <li><b>graph then vector</b> - {@link #relatedSimilar(String, int)}: the lookalikes of everything
 *       around an entity;</li>
 *   <li><b>vector then graph</b> - {@link #similarRelated(String, int)}: what the lookalikes of an entity
 *       are connected to (the query the README opens with);</li>
 *   <li>and the two ends of a question - {@link #context(String, int, int)}: which entities a question is
 *       about, and what sits around them.</li>
 * </ul>
 *
 * <p>Every one of them answers with a {@link Bundle}: the entities it found (with the score and the
 * sentence they came from), the relations among them (with provenance), the paths when the tool walked
 * any, the chunks those came from, and a coverage block saying <em>how much</em> was covered - how many
 * entities, how many relations, how many citations, and whether the request was capped. That block is what
 * lets an agent say "from 6 related entities and 11 relations" instead of asserting a fact from nowhere,
 * and it is what the example in the README quotes.
 *
 * <p>Retrieval is two-staged on purpose. <b>Recall</b> happens in the {@link AgentIndex} (in memory: name
 * postings plus a cosine rerank), because a graph store answers keyed reads, not "which of your million
 * entities does this question resemble". <b>Expansion</b> happens in the graph engine, because that is
 * what an adjacency is for. A tool therefore returns what the graph said, not what a language model
 * remembers.
 */
public final class AgentQuery {

    /** What the tools need from the engine: the answer to a Gremlin query, as the engine returns it. */
    @FunctionalInterface
    public interface Engine {

        /** Runs a traversal and returns its answer (the values the engine produced). */
        List<Object> execute(String gremlin);
    }

    private final Engine engine;
    /** The artifact the tools read; replaced by a filtered copy when a call asks for an instant. */
    private AgentIndex index;
    private final Embedding embedding;
    /** The edge class the entity-to-entity relations live in (an ingestion creates it). */
    private final String relationEdge;
    private final Reranker reranker;

    public AgentQuery(Engine engine, AgentIndex index) {
        this(engine, index, Embedding.hashing(), "Relates");
    }

    public AgentQuery(Engine engine, AgentIndex index, Embedding embedding) {
        this(engine, index, embedding, "Relates");
    }

    public AgentQuery(Engine engine, AgentIndex index, Embedding embedding, String relationEdge) {
        this(engine, index, embedding, relationEdge, Reranker.none());
    }

    public AgentQuery(Engine engine, AgentIndex index, Embedding embedding, String relationEdge,
                      Reranker reranker) {
        this.engine = engine;
        this.index = index;
        this.embedding = embedding == null ? Embedding.hashing() : embedding;
        this.relationEdge = relationEdge == null || relationEdge.isBlank() ? "Relates" : relationEdge;
        this.reranker = reranker == null ? Reranker.none() : reranker;
    }

    /**
     * Answers as of an instant: only the entities and relations whose validity window contains it are visible
     * to the tools, so a question about a release that had not happened yet gets the graph as it stood then.
     * Everything downstream - recall, expansion, paths, coverage - reads the filtered index, so no tool can
     * accidentally answer from a fact that is no longer (or not yet) true.
     *
     * @param asOf epoch millis, or null to answer with every fact the graph has ever had
     */
    public AgentQuery asOf(Long asOf) {
        this.index = Validity.at(index, asOf);
        this.asOf = asOf;
        return this;
    }

    /** The instant this query answers as of, or null when it answers with everything. */
    public Long asOf() {
        return asOf;
    }

    private Long asOf;

    // ------------------------------------------------------------------ the tools

    /**
     * Which entities a question is about. Name matches come first (a question that names 关羽 is about
     * 关羽), then the entities closest to the question's vector - which is what finds an entity the question
     * describes without naming.
     */
    public Bundle findEntities(String question, int k) {
        int limit = Math.max(1, k);
        float[] asked = embedding.embed(question);
        Map<AgentIndex.Entity, Double> scored = new LinkedHashMap<>();
        Map<AgentIndex.Entity, String> reasons = new LinkedHashMap<>();
        for (AgentIndex.Entity entity : index.entities()) {
            double named = nameScore(question, entity);
            double cosine = Embedding.cosine(asked, entity.vector());
            if (named <= 0 && cosine <= 0.05) {
                continue; // far in both ways: not a candidate
            }
            // a name hit is worth more than a vector hit: 0.6/0.4 keeps the order stable and explainable
            scored.put(entity, 0.6 * named + 0.4 * cosine);
            reasons.put(entity, named > 0 ? "name" : "vector");
        }
        List<Bundle.Hit> hits = reranker.rerank(question, scored.entrySet().stream()
                .sorted(Map.Entry.<AgentIndex.Entity, Double>comparingByValue().reversed())
                .map(entry -> hit(entry.getKey(), entry.getValue(), reasons.get(entry.getKey())))
                .toList(), limit);
        return bundle("find_entities", Map.of("question", question, "k", limit), hits, List.of(), List.of(),
                citationsOf(hits), Map.of("candidates", scored.size(), "returned", hits.size(),
                        "truncated", scored.size() > hits.size()));
    }

    /**
     * Hybrid recall: a name hit, the terms of the question found in the entity's description, and the
     * vector, in one ranking. This is the tool to prefer when the question is a phrase rather than an
     * entity id; {@code find_entities} remains the name-first shortcut.
     */
    public Bundle search(String question, int k) {
        int limit = Math.max(1, k);
        float[] asked = embedding.embed(question);
        Set<String> questionTerms = terms(question);
        Map<AgentIndex.Entity, Double> scored = new LinkedHashMap<>();
        Map<AgentIndex.Entity, String> reasons = new LinkedHashMap<>();
        for (AgentIndex.Entity entity : index.entities()) {
            double named = nameScore(question, entity);
            double lexical = lexicalScore(questionTerms, entity);
            double cosine = Embedding.cosine(asked, entity.vector());
            if (named <= 0 && lexical <= 0 && cosine <= 0.05) {
                continue;
            }
            scored.put(entity, 0.5 * named + 0.3 * lexical + 0.2 * cosine);
            reasons.put(entity, named > 0 ? "name" : lexical > 0 ? "hybrid" : "vector");
        }
        List<Bundle.Hit> hits = reranker.rerank(question, scored.entrySet().stream()
                .sorted(Map.Entry.<AgentIndex.Entity, Double>comparingByValue().reversed())
                .map(entry -> hit(entry.getKey(), entry.getValue(), reasons.get(entry.getKey())))
                .toList(), limit);
        return bundle("search", Map.of("question", question, "k", limit), hits, List.of(), List.of(),
                citationsOf(hits), Map.of("candidates", scored.size(), "returned", hits.size(),
                        "truncated", scored.size() > hits.size(),
                        "method", "name + lexical + vector"));
    }

    /** What an entity is connected to, optionally only over one relation name, up to {@code hops} hops. */
    public Bundle related(String entityId, String relation, int hops) {
        AgentIndex.Entity entity = index.entity(entityId);
        int depth = Math.max(1, Math.min(hops, 3));
        String filtered = relation == null || relation.isBlank() ? "" : ".filter(relation.equals('" + relation + "'))";
        StringBuilder gremlin = new StringBuilder("g.V('").append(escape(entityId)).append("','")
                .append(escape(typeOf(entity))).append("')");
        if (depth == 1) {
            gremlin.append(".bothE('").append(relationEdge).append("')").append(filtered)
                    .append(".otherV().dedup()");
        } else {
            gremlin.append(".repeat(bothE('").append(relationEdge).append("')").append(filtered)
                    .append(".otherV().dedup()).times(").append(depth).append(").dedup()");
        }
        List<Object> rows = engine.execute(gremlin.append(".elementMap()").toString());
        List<Bundle.Hit> hits = new ArrayList<>();
        if (entity != null) {
            hits.add(hit(entity, 1.0, "seed")); // the entity asked about belongs in the answer
        }
        List<Bundle.Hit> neighbours = rowsToHits(rows, "related");
        hits.addAll(neighbours);
        List<Bundle.Edge> edges = relationsAmong(hits);
        return bundle("related", Map.of("entity", entityId, "relation", relation == null ? "" : relation,
                        "hops", depth), hits, edges, List.of(), citationsOf(hits),
                Map.of("neighbours", neighbours.size(), "relations", edges.size(), "hops", depth));
    }

    /** The entities nearest to this one in vector space (its lookalikes). */
    public Bundle similar(String entityId, int k) {
        List<Bundle.Hit> hits = nearestInIndex(entityId, Math.max(1, k));
        List<Bundle.Edge> edges = relationsAmong(hits);
        return bundle("similar", Map.of("entity", entityId, "k", Math.max(1, k)), hits, edges, List.of(),
                citationsOf(hits), Map.of("entities", hits.size(), "relations", edges.size(),
                        "method", "cosine over the index's vectors"));
    }

    /** The lookalikes of everything around an entity: graph first, then vector. */
    public Bundle relatedSimilar(String entityId, int k) {
        Bundle around = related(entityId, null, 1);
        List<Bundle.Hit> hits = new ArrayList<>(around.entities());
        Set<String> seen = new LinkedHashSet<>();
        hits.forEach(hit -> seen.add(hit.id()));
        for (Bundle.Hit neighbour : around.entities()) {
            if ("seed".equals(neighbour.via())) {
                continue; // the entity itself is already in the answer
            }
            for (Bundle.Hit lookalike : nearestInIndex(neighbour.id(), Math.max(1, k))) {
                if (seen.add(lookalike.id())) {
                    hits.add(lookalike);
                }
            }
        }
        List<Bundle.Edge> edges = relationsAmong(hits);
        return bundle("related_similar", Map.of("entity", entityId, "k", Math.max(1, k)), hits, edges, List.of(),
                citationsOf(hits), Map.of("entities", hits.size(), "relations", edges.size(),
                        "neighbours", around.coverage().getOrDefault("neighbours", 0)));
    }

    /** What the lookalikes of an entity are connected to: vector first, then graph. */
    public Bundle similarRelated(String entityId, int k) {
        List<Bundle.Hit> lookalikes = nearestInIndex(entityId, Math.max(1, k));
        List<Bundle.Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (index.entity(entityId) != null) {
            hits.add(hit(index.entity(entityId), 1.0, "seed"));
            seen.add(entityId);
        }
        for (Bundle.Hit lookalike : lookalikes) {
            if (seen.add(lookalike.id())) {
                hits.add(lookalike);
            }
            for (Bundle.Hit neighbour : related(lookalike.id(), null, 1).entities()) {
                if (seen.add(neighbour.id())) {
                    hits.add(neighbour);
                }
            }
        }
        List<Bundle.Edge> edges = relationsAmong(hits);
        return bundle("similar_related", Map.of("entity", entityId, "k", Math.max(1, k)), hits, edges, List.of(),
                citationsOf(hits), Map.of("entities", hits.size(), "relations", edges.size(),
                        "lookalikes", lookalikes.size()));
    }

    /**
     * The k entities whose vectors are nearest to this entity's, by cosine - computed over the index, in
     * memory. That is exact and needs nothing from the store (the graph's own HNSW index answers the same
     * question in sublinear time through {@code nearest(k)}, which is what a graph with millions of
     * entities should use); either way the candidate set and the scores are the artifact's.
     */
    private List<Bundle.Hit> nearestInIndex(String entityId, int k) {
        AgentIndex.Entity entity = index.entity(entityId);
        if (entity == null || entity.vector() == null) {
            return List.of();
        }
        return index.entities().stream()
                .filter(candidate -> !candidate.id().equals(entityId) && candidate.vector() != null)
                .map(candidate -> Map.entry(candidate, Embedding.cosine(entity.vector(), candidate.vector())))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<AgentIndex.Entity, Double>comparingByValue().reversed())
                .limit(k)
                .map(entry -> hit(entry.getKey(), entry.getValue(), "similar"))
                .toList();
    }

    /**
     * Whether two entities are connected, and how (the shortest walks between them, up to {@code maxHops}).
     *
     * <p>A breadth-first walk over the relations the index holds: the artifact has them all, so the answer
     * is a walk of named entities and the relations along it, which is what a reader (or a model) needs -
     * and it does not depend on the store supporting a path traversal.
     */
    public Bundle pathBetween(String from, String to, int maxHops) {
        int depth = Math.max(1, Math.min(maxHops, 6));
        Map<String, List<AgentIndex.Relation>> adjacency = new LinkedHashMap<>();
        for (AgentIndex.Relation relation : index.relations()) {
            adjacency.computeIfAbsent(relation.head(), key -> new ArrayList<>()).add(relation);
            adjacency.computeIfAbsent(relation.tail(), key -> new ArrayList<>()).add(relation);
        }
        List<Bundle.Path> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<List<String>> queue = new ArrayDeque<>();
        queue.add(List.of(from));
        int asked = 0;
        while (!queue.isEmpty()) {
            List<String> walk = queue.poll();
            String last = walk.get(walk.size() - 1);
            if (walk.size() - 1 > depth) {
                break; // every longer walk is past the limit (breadth first, so the rest is too)
            }
            asked = Math.max(asked, walk.size() - 1);
            if (last.equals(to) && walk.size() > 1) {
                paths.add(new Bundle.Path(walk, walk.size() - 1));
                if (paths.size() >= 5) {
                    break;
                }
                continue;
            }
            for (AgentIndex.Relation relation : adjacency.getOrDefault(last, List.of())) {
                String next = relation.head().equals(last) ? relation.tail() : relation.head();
                if (walk.contains(next) || !seen.add(next + "@" + (walk.size()))) {
                    continue;
                }
                List<String> longer = new ArrayList<>(walk);
                longer.add(next);
                queue.add(longer);
            }
        }
        List<Bundle.Hit> hits = new ArrayList<>();
        for (String id : List.of(from, to)) {
            AgentIndex.Entity entity = index.entity(id);
            if (entity != null) {
                hits.add(hit(entity, 1.0, "endpoint"));
            }
        }
        List<Bundle.Edge> edges = relationsAlong(paths);
        return bundle("path_between", Map.of("from", from, "to", to, "maxHops", depth), hits, edges, paths,
                citationsOf(hits), Map.of("paths", paths.size(), "hops", asked));
    }

    /** The relations every hop of the found walks used (the edges behind a path). */
    private List<Bundle.Edge> relationsAlong(List<Bundle.Path> paths) {
        List<Bundle.Edge> edges = new ArrayList<>();
        for (Bundle.Path path : paths) {
            for (int i = 0; i + 1 < path.vertices().size(); i++) {
                String left = path.vertices().get(i);
                String right = path.vertices().get(i + 1);
                for (AgentIndex.Relation relation : index.relations()) {
                    boolean matches = (relation.head().equals(left) && relation.tail().equals(right))
                            || (relation.head().equals(right) && relation.tail().equals(left));
                    if (matches && edges.stream().noneMatch(edge -> edge.head().equals(relation.head())
                            && edge.relation().equals(relation.relation()) && edge.tail().equals(relation.tail()))) {
                        edges.add(edge(relation));
                    }
                }
            }
        }
        return edges;
    }

    /**
     * The whole context of a question: the entities it is about, what sits around them, and the sentences
     * all of that came from - the shape an agent feeds to a model when it wants an answer with sources.
     */
    public Bundle context(String question, int k, int hops) {
        Bundle seeds = findEntities(question, k);
        int depth = Math.max(1, Math.min(hops, 2));
        Set<String> seen = new LinkedHashSet<>();
        List<Bundle.Hit> entities = new ArrayList<>(seeds.entities());
        seeds.entities().forEach(hit -> seen.add(hit.id()));
        List<Bundle.Edge> edges = new ArrayList<>(seeds.relations());
        for (Bundle.Hit seed : seeds.entities()) {
            Bundle around = related(seed.id(), null, depth);
            for (Bundle.Hit hit : around.entities()) {
                if (seen.add(hit.id())) {
                    entities.add(hit);
                }
            }
            for (Bundle.Edge edge : around.relations()) {
                if (edges.stream().noneMatch(existing -> existing.head().equals(edge.head())
                        && existing.relation().equals(edge.relation()) && existing.tail().equals(edge.tail()))) {
                    edges.add(edge);
                }
            }
        }
        return bundle("context", Map.of("question", question, "k", k, "hops", depth), entities, edges, List.of(),
                citationsOf(entities), Map.of("seeds", seeds.entities().size(), "entities", entities.size(),
                        "relations", edges.size(), "hops", depth));
    }

    /** The tools as the catalog of a function-calling model (see {@link AgentToolCatalog}). */
    public List<Map<String, Object>> tools() {
        return AgentToolCatalog.tools();
    }

    // ------------------------------------------------------------------ internals

    /** A bundle with the coverage block filled in from what the tool collected. */
    private Bundle bundle(String tool, Map<String, Object> request, List<Bundle.Hit> entities,
                          List<Bundle.Edge> relations, List<Bundle.Path> paths, List<Bundle.Citation> citations,
                          Map<String, Object> extra) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("entities", entities.size());
        coverage.put("relations", relations.size());
        if (!paths.isEmpty()) {
            coverage.put("paths", paths.size());
        }
        coverage.put("citations", citations.size());
        coverage.putAll(extra);
        return new Bundle(tool, index.graph(), request, entities, relations, paths, citations, coverage, next(tool));
    }

    /** What an agent may ask next, so a model does not have to guess the tool names. */
    private static List<String> next(String tool) {
        return switch (tool) {
            case "find_entities", "search" -> List.of("related(entity, relation, hops)", "context(question, k, hops)");
            case "related" -> List.of("similar(entity, k)", "related_similar(entity, k)", "path_between(from, to, maxHops)");
            case "similar" -> List.of("similar_related(entity, k)", "related(entity, relation, hops)");
            case "related_similar", "similar_related" -> List.of("related(entity, relation, hops)", "context(question, k, hops)");
            default -> List.of("context(question, k, hops)");
        };
    }

    /** The engine rows (elementMap answers) as bundle hits, keeping whatever score the row carries. */
    private List<Bundle.Hit> rowsToHits(List<Object> rows, String via) {
        List<Bundle.Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> element)) {
                continue;
            }
            Object id = element.get("id");
            if (id == null || !seen.add(String.valueOf(id))) {
                continue;
            }
            AgentIndex.Entity known = index.entity(String.valueOf(id));
            Double score = number(element.get("score"));
            if (known != null) {
                hits.add(hit(known, score == null ? 1.0 : score, via));
            } else {
                Object label = element.get("label");
                hits.add(new Bundle.Hit(String.valueOf(id),
                        String.valueOf(label == null ? "Unknown" : label),
                        String.valueOf(firstProperty(element, "name", String.valueOf(id))), score, via, List.of()));
            }
        }
        return hits;
    }

    /** The relations of the index that run between the entities a tool collected (with provenance). */
    private List<Bundle.Edge> relationsAmong(List<Bundle.Hit> hits) {
        Set<String> ids = new LinkedHashSet<>();
        hits.forEach(hit -> ids.add(hit.id()));
        List<Bundle.Edge> edges = new ArrayList<>();
        for (AgentIndex.Relation relation : index.relations()) {
            if (ids.contains(relation.head()) && ids.contains(relation.tail())) {
                edges.add(edge(relation));
            }
        }
        return edges;
    }

    /** The citations of the hits: the chunks their facts were read from, in order, without duplicates. */
    private List<Bundle.Citation> citationsOf(List<Bundle.Hit> hits) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Bundle.Hit hit : hits) {
            wanted.addAll(hit.chunkIds());
        }
        List<Bundle.Citation> citations = new ArrayList<>();
        for (String chunkId : wanted) {
            AgentIndex.Chunk chunk = index.chunk(chunkId);
            if (chunk != null) {
                citations.add(new Bundle.Citation(chunk.chunkId(), chunk.documentId(),
                        chunk.title() == null || chunk.title().isBlank()
                                ? chunk.documentId() + " §" + (chunk.ordinal() + 1)
                                : chunk.title() + " §" + (chunk.ordinal() + 1),
                        chunk.text()));
            }
        }
        return citations;
    }

    private Bundle.Hit hit(AgentIndex.Entity entity, double score, String via) {
        List<String> cites = new ArrayList<>();
        for (String chunkId : entity.chunkIds()) {
            AgentIndex.Chunk chunk = index.chunk(chunkId);
            cites.add(chunk == null ? chunkId : (chunk.title() == null || chunk.title().isBlank()
                    ? chunk.documentId() + " §" + (chunk.ordinal() + 1)
                    : chunk.title() + " §" + (chunk.ordinal() + 1)));
        }
        return new Bundle.Hit(entity.id(), entity.type(), entity.name(), Math.round(score * 10000.0) / 10000.0,
                via, cites, entity.chunkIds());
    }

    private static Bundle.Edge edge(AgentIndex.Relation relation) {
        return new Bundle.Edge(relation.head(), relation.relation(), relation.tail(), relation.rule(),
                relation.cite(), relation.sentence());
    }

    /** The terms a question and an entity can share: ASCII words and Chinese character bigrams. */
    private static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normal = text.toLowerCase();
        for (String word : normal.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2) {
                terms.add(word);
            }
        }
        for (int i = 0; i + 2 <= normal.length(); i++) {
            String gram = normal.substring(i, i + 2);
            if (gram.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                terms.add(gram);
            }
        }
        return terms;
    }

    /** Share of the question's terms that occur in the entity's name and description (0..1). */
    private static double lexicalScore(Set<String> questionTerms, AgentIndex.Entity entity) {
        if (questionTerms.isEmpty()) {
            return 0;
        }
        String text = (entity.name() == null ? "" : entity.name()) + " "
                + String.join(" ", entity.aliases()) + " "
                + (entity.description() == null ? "" : entity.description());
        Set<String> entityTerms = terms(text);
        if (entityTerms.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String term : questionTerms) {
            if (entityTerms.contains(term)) {
                matched++;
            }
        }
        return (double) matched / questionTerms.size();
    }

    /** How well a question names an entity: the full name beats a piece of it, and nothing beats nothing. */
    private static double nameScore(String question, AgentIndex.Entity entity) {
        if (question == null || question.isBlank()) {
            return 0;
        }
        String lower = question.toLowerCase();
        String name = entity.name() == null ? entity.id() : entity.name();
        List<String> names = new ArrayList<>();
        if (name != null) {
            names.add(name);
        }
        names.addAll(entity.aliases());
        boolean han = false;
        for (String candidate : names) {
            if (candidate != null && candidate.length() >= 2 && lower.contains(candidate.toLowerCase())) {
                return 1.0;
            }
            han = han || isHan(candidate);
        }
        // A two-character slice is a name hit for Chinese (诸葛亮 -> 诸葛), but for an ASCII name it is a
        // false positive: "checkout-api" contains "ch", and "why did the checkout-api go out" would then
        // name every entity whose name happens to contain "ch". English partial matching belongs to the
        // lexical score, which uses whole words.
        if (!han) {
            return 0;
        }
        for (String term : AgentIndex.termsOf(entity)) {
            if (term.length() >= 2 && question.contains(term)) {
                return 0.5;
            }
        }
        return 0;
    }
    private static boolean isHan(String value) {
        return value != null && !value.isEmpty()
                && value.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** The vertex type a traversal must name (the graph stores every entity under one schema). */
    private static String typeOf(AgentIndex.Entity entity) {
        return entity == null ? "Entity" : entity.vertexType();
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Object firstProperty(Map<?, ?> element, String key, Object fallback) {
        Object value = element.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return value == null ? fallback : value;
    }

    /** Reads a property of an engine answer without tripping over the wildcard key type. */
    private static String text(Map<?, ?> element, String key) {
        return String.valueOf(firstProperty(element, key, ""));
    }

    /** A single quote in an id would break the traversal text, and ids come from documents. */
    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "");
    }

    /** What every tool answers: entities, relations, paths, citations and how much of each. */
    public record Bundle(String tool, String graph, Map<String, Object> request, List<Hit> entities,
                         List<Edge> relations, List<Path> paths, List<Citation> citations,
                         Map<String, Object> coverage, List<String> next) {

        public Bundle {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            paths = List.copyOf(paths);
            citations = List.copyOf(citations);
            coverage = Map.copyOf(coverage);
            next = List.copyOf(next);
        }

        /** One entity of an answer: what to name it, how it scored and where it was read. */
        public record Hit(String id, String type, String name, Double score, String via, List<String> cites,
                          List<String> chunkIds) {

            public Hit(String id, String type, String name, Double score, String via, List<String> cites) {
                this(id, type, name, score, via, cites, List.of());
            }

            public Hit {
                cites = cites == null ? List.of() : List.copyOf(cites);
                chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
            }
        }

        /** One relation of an answer, with the sentence it was read from. */
        public record Edge(String head, String relation, String tail, String rule, String cite, String sentence) {
        }

        /** One walk between two entities (vertex ids in order). */
        public record Path(List<String> vertices, int hops) {

            public Path {
                vertices = List.copyOf(vertices);
            }
        }

        /** One source chunk an answer is grounded in. */
        public record Citation(String chunkId, String documentId, String cite, String text) {
        }

        /** The answer as JSON-ready maps (what the HTTP layer returns unchanged). */
        public Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", tool);
            out.put("graph", graph);
            out.put("request", request);
            out.put("entities", entities);
            out.put("relations", relations);
            if (!paths.isEmpty()) {
                out.put("paths", paths);
            }
            out.put("citations", citations);
            out.put("coverage", coverage);
            out.put("next", next);
            return out;
        }
    }
}
