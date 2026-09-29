package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.pengcheng.phoenixgraph.schema.FullTextTokens;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The inverted full-text index of an Aerospike graph.
 *
 * <p>Aerospike has no secondary index for arbitrary substring search over CJK text, so the index
 * is a client-maintained set: one record per character n-gram, whose single list bin holds the
 * vertex ids whose text contains that n-gram.
 *
 * <pre>
 * set    {vertexSet}_ftx
 * key    vertexName : propertyName : ngram
 * bin    ids = [vertexId, ...]
 * </pre>
 *
 * <p>A {@code containing(term)} query reads the posting list of every character of the term and
 * intersects them — the same candidate/verify contract as the RocksDB index, so the caller must
 * still verify each candidate against the stored text. Unlike the RocksDB index, which the vertex
 * writes maintain automatically, this one is built by the loaders ({@code SanguoGraphSeeder}
 * rebuilds it after seeding): Aerospike records are read-modify-write, so a whole-graph rebuild
 * is a serial pass rather than a per-write merge.
 */
public final class AerospikeFullTextIndex {

    /** The bin that holds the posting list. */
    public static final String BIN = "ids";

    private final AerospikeClient client;
    private final String namespace;
    private final String vertexSet;
    private final String indexSet;

    public AerospikeFullTextIndex(AerospikeClient client, String namespace, String vertexSet) {
        this.client = client;
        this.namespace = namespace;
        this.vertexSet = vertexSet;
        this.indexSet = vertexSet + "_ftx";
    }

    /** The set that holds the index records. */
    public static String indexSetOf(String vertexSet) {
        return vertexSet + "_ftx";
    }

    public String indexSet() {
        return indexSet;
    }

    /** The record key of one n-gram of one property. */
    public Key key(String vertexName, String propertyName, String ngram) {
        return new Key(namespace, indexSet, vertexName + ":" + propertyName + ":" + ngram);
    }

    /**
     * Adds the vertex to the posting list of every n-gram of the text. This is a read-modify-write,
     * so it is meant for a serial rebuild pass, not for concurrent per-write maintenance.
     */
    public void index(String vertexName, String propertyName, Object vertexId, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String id = String.valueOf(vertexId);
        for (String ngram : FullTextTokens.ngrams(text, 1)) {
            Key key = key(vertexName, propertyName, ngram);
            Record record = client.get(null, key);
            List<String> ids = new ArrayList<>();
            if (record != null && record.getList(BIN) != null) {
                for (Object existing : record.getList(BIN)) {
                    if (existing != null) {
                        ids.add(String.valueOf(existing));
                    }
                }
            }
            if (!ids.contains(id)) {
                ids.add(id);
                client.put(null, key, new Bin(BIN, ids));
            }
        }
    }

    /**
     * The candidate vertex ids whose text contains every character of the term: the posting lists
     * of the term's characters are read (smallest first) and intersected.
     */
    public List<Object> search(String vertexName, String propertyName, String term) {
        List<String> tokens = new ArrayList<>(FullTextTokens.lookupTokens(term, 1));
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<List<String>> postings = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            Record record = client.get(null, key(vertexName, propertyName, token));
            List<String> ids = new ArrayList<>();
            if (record != null && record.getList(BIN) != null) {
                for (Object existing : record.getList(BIN)) {
                    if (existing != null) {
                        ids.add(String.valueOf(existing));
                    }
                }
            }
            postings.add(ids);
        }
        postings.sort((a, b) -> Integer.compare(a.size(), b.size()));
        Set<String> candidates = new LinkedHashSet<>(postings.get(0));
        for (int i = 1; i < postings.size() && !candidates.isEmpty(); i++) {
            candidates.retainAll(postings.get(i));
        }
        return List.copyOf(candidates);
    }

    /** Removes every index record of this graph (used before a rebuild). */
    public void clear() {
        // Aerospike has no cheap "delete set" from the client; the loaders recreate the namespace
        // or truncate the set before a reseed, so this is a no-op placeholder for symmetry.
    }
}
