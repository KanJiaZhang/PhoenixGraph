package com.pengcheng.phoenixgraph.hnsw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An HNSW (Hierarchical Navigable Small World) index stored as a layered graph.
 *
 * <p><b>Layers.</b> A dataset of {@code size} vectors is laid out over
 * {@code ceil(log2(size)) + 1} layers ({@link HnswLevels}): {@code 4096} vectors
 * become the 13 layers {@code 0..12}, materialised as the 13 bidirectional edge
 * classes {@code vector_0 .. vector_12}. A vertex's top layer is the exponentially
 * decaying draw {@code floor(-log2(u))}, so layer 0 holds every vertex while the
 * top layer holds about one - the entry point a search starts from.
 *
 * <p><b>Insert.</b> Inserting a vertex {@code q} with top layer {@code L}:
 * <ol>
 *   <li>start at the entry point on the highest layer, greedily descend layer by
 *       layer down to {@code L + 1} (one step per layer, no expansion);</li>
 *   <li>for every layer {@code l} from {@code min(L, topLayer)} down to {@code 0}:
 *       run a best-first search with {@code efConstruction} candidates on that
 *       layer ({@link HnswConfig#efConstructionAt(int)}), keep at most
 *       {@code m = 15} of them with the neighbour-selection heuristic
 *       ({@code maxLinksLevel0 = 2m} on layer 0), link {@code q} to them and
 *       re-prune each of those neighbours so its degree stays within the cap;</li>
 *   <li>if {@code L} is high enough, {@code q} joins the entry-point registry
 *       (at most {@value HnswLevels#MAX_ENTRY_POINTS} vertices, highest layer
 *       first), which is persisted so the next process can start from it.</li>
 * </ol>
 *
 * <p><b>Search.</b> Greedy descent from the entry point(s) down to layer 1, then
 * one best-first search with {@code efSearch} candidates on layer 0; the closest
 * {@code k} results are returned.
 *
 * <p>Reads go through the {@link HnswStore} / {@link HnswVectorSource} ports, so
 * the same code builds and queries an index over Aerospike, memory or anything
 * else. Neighbour lists and vectors are fetched in batches and memoised per
 * operation, which keeps a search at roughly one storage read per expanded node.
 *
 * <p>{@link #insert} is synchronised (a build is a single writer); searches use
 * their own per-call state and are safe to run concurrently.
 */
public final class HnswIndex {

    /** Safety valve: max node expansions per layer search. */
    private static final int MAX_EXPANSIONS = 100_000;

    private final String vertexName;
    private final String vectorProperty;
    private final HnswStore store;
    private final HnswVectorSource vectors;
    private final HnswMetadataStore metadataStore;
    private final HnswConfig config;
    private final int topLevel;
    private final List<HnswEntryPoint> entryPoints = new ArrayList<>();
    private final AtomicLong inserts = new AtomicLong();
    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong distances = new AtomicLong();
    private int metadataWriteInterval = 100;
    private long size;
    private long insertsSincePersist;
    private long updatedAt;

    private HnswIndex(Builder builder, HnswMetadata metadata) {
        this.vertexName = builder.vertexName;
        this.vectorProperty = builder.vectorProperty;
        this.store = builder.store;
        this.vectors = builder.vectors;
        this.metadataStore = builder.metadataStore;
        this.metadataWriteInterval = builder.metadataWriteInterval;
        if (metadata == null) {
            this.config = builder.config;
            this.topLevel = HnswLevels.topLevel(builder.size);
        } else {
            this.config = metadata.toConfig().withEfSearch(builder.config.efSearch());
            this.topLevel = Math.max(metadata.topLevel(), HnswLevels.topLevel(metadata.size()));
            this.size = metadata.size();
            this.entryPoints.addAll(metadata.entryPoints());
            this.updatedAt = metadata.updatedAt();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String vertexName() {
        return vertexName;
    }

    public String vectorProperty() {
        return vectorProperty;
    }

    public HnswConfig config() {
        return config;
    }

    /** Highest layer of the index ({@code ceil(log2(size))}). */
    public int topLevel() {
        return topLevel;
    }

    /** Entry points currently kept, highest layer first. */
    public List<HnswEntryPoint> entryPoints() {
        return List.copyOf(entryPoints);
    }

    /** Top layer of the given vertex (deterministic, see {@link HnswLevels#levelFor}). */
    public int levelOf(Object vertexId) {
        return HnswLevels.levelFor(vertexId, topLevel);
    }

    public HnswIndexStats stats() {
        return new HnswIndexStats(size, topLevel,
                entryPoints.isEmpty() ? -1 : entryPoints.get(0).level(), List.copyOf(entryPoints),
                inserts.get(), searches.get(), distances.get());
    }

    /** How often the metadata record is rewritten while building (every N inserts). */
    public HnswIndex metadataWriteInterval(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("interval must be >= 1, got " + interval);
        }
        this.metadataWriteInterval = interval;
        return this;
    }

    // ------------------------------------------------------------------ insert

    /** Inserts a vertex, reading its vector from the {@link HnswVectorSource}. */
    public HnswInsertResult insert(Object vertexId) {
        float[] vector = vectors.vector(vertexId);
        if (vector == null) {
            throw new IllegalArgumentException("No vector found for vertex " + vertexId
                    + " (property '" + vectorProperty + "' of '" + vertexName + "')");
        }
        return insert(vertexId, vector);
    }

    /**
     * Inserts one vertex into the layered graph, linking it to its nearest
     * neighbours on every layer it reaches.
     */
    public synchronized HnswInsertResult insert(Object vertexId, float[] vector) {
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(vector, "vector");
        int level = levelOf(vertexId);
        if (entryPoints.isEmpty()) {
            // first vertex: it is the sole entry point of every layer
            registerEntryPoint(vertexId, level);
            size++;
            inserts.incrementAndGet();
            persist(true);
            return new HnswInsertResult(vertexId, level, 0, 0);
        }

        Session session = new Session(vector);
        List<Object> seeds = entryPoints.stream().map(HnswEntryPoint::vertexId).toList();
        session.prefetch(seeds);
        int entryLevel = entryPoints.get(0).level();
        Object current = closest(seeds, session);
        for (int l = entryLevel; l > level; l--) {
            current = greedySearch(current, l, session);
        }

        int links = 0;
        int pruned = 0;
        for (int l = Math.min(level, entryLevel); l >= 0; l--) {
            List<HnswSearchResult> candidates =
                    searchLayer(List.of(current), config.efConstructionAt(l), l, session);
            List<Object> selected = selectNeighbours(toCandidates(candidates), config.maxLinksFor(l), session);
            Set<Object> existing = neighbourIds(vertexId, l, session);
            List<Object> fresh = new ArrayList<>(selected.size());
            for (Object neighbour : selected) {
                if (!neighbour.equals(vertexId) && !existing.contains(neighbour)) {
                    fresh.add(neighbour);
                }
            }
            for (Object neighbour : fresh) {
                session.addLink(vertexId, neighbour, l);
                links++;
            }
            pruned += prune(fresh, l, session);
            if (!candidates.isEmpty()) {
                current = candidates.get(0).vertexId();
            }
        }
        // all links and prunes of this insert go to storage in one aggregated write
        session.flushWrites(store);

        registerEntryPoint(vertexId, level);
        size++;
        inserts.incrementAndGet();
        persist(false);
        return new HnswInsertResult(vertexId, level, links, pruned);
    }

    /** Inserts a batch of vertices in order (the build loop of a dataset). */
    public List<HnswInsertResult> insertAll(List<Object> vertexIds) {
        List<HnswInsertResult> results = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            results.add(insert(vertexId));
        }
        return results;
    }

    // ------------------------------------------------------------------ search

    /** {@code k} nearest vectors of {@code query}, using the configured {@code efSearch}. */
    public List<HnswSearchResult> search(float[] query, int k) {
        return search(query, k, config.efSearch());
    }

    /**
     * {@code k} nearest vectors of {@code query}.
     *
     * @param k  number of results
     * @param ef candidate list size on layer 0 (bigger = more accurate, slower)
     */
    public List<HnswSearchResult> search(float[] query, int k, int ef) {
        Objects.requireNonNull(query, "query");
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, got " + k);
        }
        if (entryPoints.isEmpty()) {
            return List.of();
        }
        searches.incrementAndGet();
        Session session = new Session(query);
        List<Object> seeds = entryPoints.stream().map(HnswEntryPoint::vertexId).toList();
        session.prefetch(seeds);
        Object current = closest(seeds, session);
        for (int l = entryPoints.get(0).level(); l >= 1; l--) {
            current = greedySearch(current, l, session);
        }
        List<HnswSearchResult> found = searchLayer(List.of(current), Math.max(ef, k), 0, session);
        return found.size() > k ? new ArrayList<>(found.subList(0, k)) : found;
    }

    /** {@code k} nearest neighbours of the vector stored for {@code vertexId} (the vertex itself excluded). */
    public List<HnswSearchResult> searchById(Object vertexId, int k) {
        return searchById(vertexId, k, config.efSearch());
    }

    /** {@code k} nearest neighbours of {@code vertexId}, excluding the vertex itself. */
    public List<HnswSearchResult> searchById(Object vertexId, int k, int ef) {
        float[] query = vectors.vector(vertexId);
        if (query == null) {
            return List.of();
        }
        List<HnswSearchResult> results = new ArrayList<>(k);
        for (HnswSearchResult hit : search(query, k + 1, ef)) {
            if (!hit.vertexId().equals(vertexId)) {
                results.add(hit);
            }
        }
        return results.size() > k ? results.subList(0, k) : results;
    }

    /** Neighbours of a vertex on one layer (the raw layered graph view). */
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        return store.neighbours(vertexId, level);
    }

    // ------------------------------------------------------------- graph walks

    /** Best-first search on one layer, returning at most {@code ef} candidates sorted by distance. */
    private List<HnswSearchResult> searchLayer(List<Object> entries, int ef, int level, Session session) {
        PriorityQueue<HnswCandidate> candidates =
                new PriorityQueue<>(Comparator.comparingDouble(HnswCandidate::distance));
        PriorityQueue<HnswCandidate> best = new PriorityQueue<>(
                Comparator.comparingDouble(HnswCandidate::distance).reversed());
        Set<Object> visited = new HashSet<>();
        session.prefetch(entries);
        for (Object entry : entries) {
            if (!visited.add(entry)) {
                continue;
            }
            double distance = session.distance(entry);
            candidates.add(new HnswCandidate(entry, distance));
            best.add(new HnswCandidate(entry, distance));
        }
        int expansions = 0;
        while (!candidates.isEmpty()) {
            HnswCandidate candidate = candidates.poll();
            if (best.size() >= ef && candidate.distance() > best.peek().distance()) {
                break;
            }
            if (++expansions > MAX_EXPANSIONS) {
                break;
            }
            List<Object> neighbours = new ArrayList<>();
            for (HnswNeighbor neighbour : session.neighbours(candidate.vertexId(), level)) {
                if (!visited.contains(neighbour.vertexId())) {
                    neighbours.add(neighbour.vertexId());
                }
            }
            session.prefetch(neighbours);
            for (Object neighbour : neighbours) {
                if (!visited.add(neighbour)) {
                    continue;
                }
                double distance = session.distance(neighbour);
                if (best.size() < ef || distance < best.peek().distance()) {
                    candidates.add(new HnswCandidate(neighbour, distance));
                    best.add(new HnswCandidate(neighbour, distance));
                    if (best.size() > ef) {
                        best.poll();
                    }
                }
            }
        }
        List<HnswSearchResult> out = new ArrayList<>(best.size());
        for (HnswCandidate candidate : best) {
            out.add(new HnswSearchResult(candidate.vertexId(), candidate.distance()));
        }
        out.sort(Comparator.comparingDouble(HnswSearchResult::distance));
        return out;
    }

    /** Greedy walk towards the query on one layer, stopping at a local minimum. */
    private Object greedySearch(Object start, int level, Session session) {
        Object best = start;
        double bestDistance = session.distance(best);
        boolean improved = true;
        while (improved) {
            improved = false;
            List<Object> neighbours = new ArrayList<>();
            for (HnswNeighbor neighbour : session.neighbours(best, level)) {
                neighbours.add(neighbour.vertexId());
            }
            session.prefetch(neighbours);
            for (Object neighbour : neighbours) {
                double distance = session.distance(neighbour);
                if (distance < bestDistance) {
                    best = neighbour;
                    bestDistance = distance;
                    improved = true;
                }
            }
        }
        return best;
    }

    private Object closest(List<Object> ids, Session session) {
        Object best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Object id : ids) {
            double distance = session.distance(id);
            if (distance < bestDistance) {
                best = id;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The HNSW neighbour-selection heuristic: walk the candidates closest-first
     * and keep a candidate only when it is closer to the reference point than to
     * every already selected candidate (which spreads the links over directions
     * instead of piling them up in one cluster). If that leaves room, the closest
     * of the rejected candidates fill the remaining slots.
     */
    private List<Object> selectNeighbours(List<HnswCandidate> candidates, int m, Session session) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<HnswCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(HnswCandidate::distance));
        List<Object> selected = new ArrayList<>(Math.min(m, sorted.size()));
        List<HnswCandidate> rejected = new ArrayList<>();
        for (HnswCandidate candidate : sorted) {
            if (selected.size() >= m) {
                break;
            }
            boolean closerThanAll = true;
            for (Object kept : selected) {
                if (distanceBetween(candidate.vertexId(), kept, session) < candidate.distance()) {
                    closerThanAll = false;
                    break;
                }
            }
            if (closerThanAll) {
                selected.add(candidate.vertexId());
            } else {
                rejected.add(candidate);
            }
        }
        for (HnswCandidate candidate : rejected) {
            if (selected.size() >= m) {
                break;
            }
            if (!selected.contains(candidate.vertexId())) {
                selected.add(candidate.vertexId());
            }
        }
        return selected;
    }

    /**
     * Re-prunes the neighbour lists of freshly linked vertices: a neighbour that
     * now holds more than {@link HnswConfig#maxLinksFor(int)} edges drops the
     * worst ones (selected with the same heuristic, measured from the neighbour).
     */
    private int prune(List<Object> neighbours, int level, Session session) {
        int keep = config.maxLinksFor(level);
        int removed = 0;
        for (Object neighbour : neighbours) {
            List<HnswNeighbor> current = session.neighbours(neighbour, level);
            if (current.size() <= keep) {
                continue;
            }
            session.prefetch(current.stream().map(HnswNeighbor::vertexId).toList());
            List<HnswCandidate> ranked = new ArrayList<>(current.size());
            for (HnswNeighbor edge : current) {
                ranked.add(new HnswCandidate(edge.vertexId(), distanceBetween(neighbour, edge.vertexId(), session),
                        edge.edgeId()));
            }
            Set<Object> kept = new LinkedHashSet<>(selectNeighbours(ranked, keep, session));
            for (HnswCandidate candidate : ranked) {
                if (!kept.contains(candidate.vertexId())) {
                    session.addUnlink(candidate.edgeId(), neighbour, candidate.vertexId(), level);
                    removed++;
                }
            }
        }
        return removed;
    }

    private List<HnswCandidate> toCandidates(List<HnswSearchResult> results) {
        List<HnswCandidate> candidates = new ArrayList<>(results.size());
        for (HnswSearchResult result : results) {
            candidates.add(new HnswCandidate(result.vertexId(), result.distance()));
        }
        return candidates;
    }

    private Set<Object> neighbourIds(Object vertexId, int level, Session session) {
        Set<Object> ids = new HashSet<>();
        for (HnswNeighbor neighbour : session.neighbours(vertexId, level)) {
            ids.add(neighbour.vertexId());
        }
        return ids;
    }

    // -------------------------------------------------------------- bookkeeping

    private void registerEntryPoint(Object vertexId, int level) {
        entryPoints.removeIf(point -> point.vertexId().equals(vertexId));
        entryPoints.add(new HnswEntryPoint(vertexId, level));
        entryPoints.sort(null);
        while (entryPoints.size() > HnswLevels.MAX_ENTRY_POINTS) {
            entryPoints.remove(entryPoints.size() - 1);
        }
    }

    /** Writes the metadata record (entry points, size) to the registry. */
    private void persist(boolean force) {
        insertsSincePersist++;
        if (!force && insertsSincePersist < metadataWriteInterval) {
            return;
        }
        insertsSincePersist = 0;
        updatedAt = System.currentTimeMillis();
        metadataStore.write(metadata());
    }

    /** Current metadata (entry points + size + configuration). */
    public HnswMetadata metadata() {
        return new HnswMetadata(HnswMetadata.CURRENT_VERSION, vertexName, vectorProperty, config.metric(),
                config.m(), config.efConstruction(), config.efSearch(), vectors.dimensions(), size,
                topLevel, List.copyOf(entryPoints), updatedAt);
    }

    /** Persists the metadata immediately (e.g. at the end of a build). */
    public void flush() {
        updatedAt = System.currentTimeMillis();
        insertsSincePersist = 0;
        metadataStore.write(metadata());
    }

    // ---------------------------------------------------------------- session

    /**
     * Per-operation read cache (vectors, distances to the reference point,
     * neighbour lists) plus the write buffer of one insert.
     *
     * <p>Batching the vector reads of a node's neighbours is what keeps the number
     * of storage round trips close to one per expanded node. The write buffer keeps
     * the links and prunes decided while inserting in memory - with an overlay so
     * the neighbour view already contains them - and hands them to the store as one
     * batch, which the storage layer aggregates per vertex bin.
     */
    private final class Session {

        private final float[] reference;
        private final Map<Object, float[]> vectorCache = new HashMap<>();
        private final Map<Object, Double> distanceCache = new HashMap<>();
        private final Map<String, List<HnswNeighbor>> neighbourCache = new HashMap<>();
        private final Map<String, List<HnswNeighbor>> pendingAdds = new HashMap<>();
        private final Map<String, Set<String>> pendingRemovals = new HashMap<>();
        private final Map<String, HnswLink> pendingLinks = new LinkedHashMap<>();
        private final Map<String, HnswUnlink> pendingUnlinks = new LinkedHashMap<>();

        private Session(float[] reference) {
            this.reference = reference;
        }

        /** Fetches the vectors of every id that is not cached yet with one batch read. */
        void prefetch(List<Object> ids) {
            List<Object> missing = new ArrayList<>();
            for (Object id : ids) {
                if (id != null && !vectorCache.containsKey(id)) {
                    missing.add(id);
                    vectorCache.put(id, null); // reserve; filled below
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            List<float[]> loaded = vectors.vectors(missing);
            for (int i = 0; i < missing.size(); i++) {
                vectorCache.put(missing.get(i), i < loaded.size() ? loaded.get(i) : null);
            }
        }

        float[] vectorOf(Object id) {
            if (!vectorCache.containsKey(id)) {
                vectorCache.put(id, vectors.vector(id));
            }
            return vectorCache.get(id);
        }

        /** Distance of {@code id} to the operation's reference vector. */
        double distance(Object id) {
            Double cached = distanceCache.get(id);
            if (cached != null) {
                return cached;
            }
            float[] vector = vectorOf(id);
            double distance = vector == null ? Double.MAX_VALUE : config.metric().distance(reference, vector);
            distanceCache.put(id, distance);
            distances.incrementAndGet();
            return distance;
        }

        /** Distance between two vertices (used by the selection heuristic). */
        double distanceBetween(Object a, Object b) {
            float[] va = vectorOf(a);
            float[] vb = vectorOf(b);
            if (va == null || vb == null) {
                return Double.MAX_VALUE;
            }
            distances.incrementAndGet();
            return config.metric().distance(va, vb);
        }

        /**
         * Neighbours of a vertex on a layer: the stored view with the links and
         * prunes of the current insert applied on top.
         */
        List<HnswNeighbor> neighbours(Object vertexId, int level) {
            String key = level + ":" + vertexId;
            List<HnswNeighbor> stored = loaded(vertexId, level, key);
            List<HnswNeighbor> added = pendingAdds.get(key);
            Set<String> removed = pendingRemovals.get(key);
            if ((added == null || added.isEmpty()) && (removed == null || removed.isEmpty())) {
                return stored;
            }
            List<HnswNeighbor> merged = new ArrayList<>(stored.size() + (added == null ? 0 : added.size()));
            for (HnswNeighbor neighbour : stored) {
                if (removed == null || !removed.contains(neighbour.edgeId())) {
                    merged.add(neighbour);
                }
            }
            if (added != null) {
                for (HnswNeighbor neighbour : added) {
                    if (removed == null || !removed.contains(neighbour.edgeId())) {
                        merged.add(neighbour);
                    }
                }
            }
            return merged;
        }

        private List<HnswNeighbor> loaded(Object vertexId, int level, String key) {
            List<HnswNeighbor> cached = neighbourCache.get(key);
            if (cached != null) {
                return cached;
            }
            List<HnswNeighbor> loaded = storeNeighbours(vertexId, level);
            neighbourCache.put(key, loaded);
            return loaded;
        }

        /** Buffers a link (visible to the following prune of the same insert). */
        void addLink(Object from, Object to, int level) {
            String edgeId = store.edgeId(from, to, level);
            pendingUnlinks.remove(edgeId);
            pendingLinks.put(edgeId, new HnswLink(edgeId, from, to, level));
            addPending(from, to, level, edgeId);
            addPending(to, from, level, edgeId);
        }

        /** Buffers the removal of a link; a link created by this insert is simply dropped again. */
        void addUnlink(String edgeId, Object from, Object to, int level) {
            if (pendingLinks.remove(edgeId) != null) {
                removePending(from, to, level, edgeId);
                removePending(to, from, level, edgeId);
                return;
            }
            pendingUnlinks.put(edgeId, new HnswUnlink(edgeId, from, to, level));
            removePending(from, to, level, edgeId);
            removePending(to, from, level, edgeId);
        }

        private void addPending(Object vertexId, Object other, int level, String edgeId) {
            String key = level + ":" + vertexId;
            List<HnswNeighbor> added = pendingAdds.computeIfAbsent(key, k -> new ArrayList<>());
            if (added.stream().noneMatch(neighbour -> neighbour.vertexId().equals(other))) {
                added.add(new HnswNeighbor(edgeId, other));
            }
            Set<String> removed = pendingRemovals.get(key);
            if (removed != null) {
                removed.remove(edgeId);
            }
        }

        private void removePending(Object vertexId, Object other, int level, String edgeId) {
            String key = level + ":" + vertexId;
            List<HnswNeighbor> added = pendingAdds.get(key);
            if (added != null) {
                added.removeIf(neighbour -> neighbour.vertexId().equals(other));
            }
            pendingRemovals.computeIfAbsent(key, k -> new HashSet<>()).add(edgeId);
        }

        /** Sends the buffered links and prunes to the store as one batch each. */
        void flushWrites(HnswStore target) {
            if (!pendingUnlinks.isEmpty()) {
                target.unlinkBatch(new ArrayList<>(pendingUnlinks.values()));
            }
            if (!pendingLinks.isEmpty()) {
                target.linkBatch(new ArrayList<>(pendingLinks.values()));
            }
            pendingLinks.clear();
            pendingUnlinks.clear();
            pendingAdds.clear();
            pendingRemovals.clear();
            neighbourCache.clear();
        }

        private List<HnswNeighbor> storeNeighbours(Object vertexId, int level) {
            return store.neighbours(vertexId, level);
        }
    }

    private double distanceBetween(Object a, Object b, Session session) {
        return session.distanceBetween(a, b);
    }

    // ------------------------------------------------------------------ builder

    /** Builder for an {@link HnswIndex}. */
    public static final class Builder {

        private String vertexName;
        private String vectorProperty = "vector";
        private HnswStore store;
        private HnswVectorSource vectors;
        private HnswMetadataStore metadataStore = HnswMetadataStore.inMemory();
        private HnswConfig config = HnswConfig.defaults();
        private long size;
        private int metadataWriteInterval = 100;
        private boolean overwrite;

        /** Vertex type the index is built over (e.g. {@code Vector}). */
        public Builder vertexName(String vertexName) {
            this.vertexName = vertexName;
            return this;
        }

        /** Name of the property holding the vector ({@code vector} by default). */
        public Builder vectorProperty(String vectorProperty) {
            this.vectorProperty = vectorProperty;
            return this;
        }

        /** Layered neighbour storage (layer {@code l} is edge class {@code vector_<l>}). */
        public Builder store(HnswStore store) {
            this.store = store;
            return this;
        }

        /** Where the vectors live. */
        public Builder vectors(HnswVectorSource vectors) {
            this.vectors = vectors;
            return this;
        }

        /** Entry-point / metadata registry; in-memory when omitted. */
        public Builder metadataStore(HnswMetadataStore metadataStore) {
            this.metadataStore = metadataStore;
            return this;
        }

        public Builder config(HnswConfig config) {
            this.config = config;
            return this;
        }

        /** Dataset size; required when no metadata exists yet (it fixes the layer count). */
        public Builder size(long size) {
            this.size = size;
            return this;
        }

        public Builder metadataWriteInterval(int metadataWriteInterval) {
            this.metadataWriteInterval = metadataWriteInterval;
            return this;
        }

        /** Ignores any existing metadata and starts a new (empty) index. */
        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public HnswIndex build() {
            Objects.requireNonNull(vertexName, "vertexName");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(vectors, "vectors");
            Objects.requireNonNull(metadataStore, "metadataStore");
            Objects.requireNonNull(config, "config");
            HnswMetadata metadata = overwrite ? null : metadataStore.read();
            if (metadata == null) {
                if (size < 1) {
                    throw new IllegalStateException("size is required to create a new HNSW index");
                }
                if (metadataStore.read() == null) {
                    metadataStore.write(HnswMetadata.empty(vertexName, vectorProperty, config,
                            vectors.dimensions(), size));
                }
            } else if (!metadata.vertexName().equals(vertexName)) {
                throw new IllegalStateException("HNSW metadata belongs to vertex type '"
                        + metadata.vertexName() + "', not '" + vertexName + "'");
            }
            return new HnswIndex(this, metadata);
        }
    }
}
