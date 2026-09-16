package com.pengcheng.phoenixgraph.hnsw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Write-through neighbour cache around another {@link HnswStore}.
 *
 * <p>A build reads a vertex's neighbour list far more often than it writes it
 * (every expansion of the insertion search, every re-prune), so wrapping the
 * storage store in this decorator removes the repeated round trips while keeping
 * the cached view exact: {@link #linkAll} and {@link #unlink} maintain both
 * endpoints in the cache.
 */
public final class CachingHnswStore implements HnswStore {

    private final HnswStore delegate;
    private final Map<String, List<HnswNeighbor>> cache = new LinkedHashMap<>();
    private final boolean cacheReads;

    public CachingHnswStore(HnswStore delegate) {
        this(delegate, true);
    }

    /**
     * @param cacheReads whether to keep neighbour lists in the cache; a build
     *                   wants reads cached, a long-lived reader may prefer not to
     */
    public CachingHnswStore(HnswStore delegate, boolean cacheReads) {
        this.delegate = delegate;
        this.cacheReads = cacheReads;
    }

    @Override
    public List<HnswNeighbor> neighbours(Object vertexId, int level) {
        String key = key(level, vertexId);
        List<HnswNeighbor> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<HnswNeighbor> loaded = delegate.neighbours(vertexId, level);
        if (cacheReads) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public List<List<HnswNeighbor>> neighboursBatch(List<Object> vertexIds, int level) {
        List<List<HnswNeighbor>> out = new ArrayList<>(vertexIds.size());
        for (Object vertexId : vertexIds) {
            out.add(neighbours(vertexId, level));
        }
        return out;
    }

    @Override
    public void linkAll(Object from, List<Object> to, int level) {
        List<HnswLink> links = new ArrayList<>(to.size());
        for (Object other : to) {
            links.add(new HnswLink(edgeId(from, other, level), from, other, level));
        }
        linkBatch(links);
    }

    @Override
    public void linkBatch(List<HnswLink> links) {
        delegate.linkBatch(links);
        for (HnswLink link : links) {
            cache.remove(key(link.level(), link.from()));
            cache.remove(key(link.level(), link.to()));
        }
    }

    @Override
    public void unlink(String edgeId, Object from, Object to, int level) {
        delegate.unlink(edgeId, from, to, level);
        cache.remove(key(level, from));
        cache.remove(key(level, to));
    }

    @Override
    public void unlinkBatch(List<HnswUnlink> unlinks) {
        delegate.unlinkBatch(unlinks);
        for (HnswUnlink unlink : unlinks) {
            cache.remove(key(unlink.level(), unlink.from()));
            cache.remove(key(unlink.level(), unlink.to()));
        }
    }

    @Override
    public String edgeId(Object from, Object to, int level) {
        return delegate.edgeId(from, to, level);
    }

    /** Drops everything cached so far (e.g. before switching from build to search). */
    public void invalidate() {
        cache.clear();
    }

    private static String key(int level, Object vertexId) {
        return level + ":" + vertexId;
    }
}
