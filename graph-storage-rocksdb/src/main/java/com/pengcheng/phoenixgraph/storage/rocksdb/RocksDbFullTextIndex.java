package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.pengcheng.phoenixgraph.schema.FullTextIndexConfig;
import com.pengcheng.phoenixgraph.schema.FullTextIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.FullTextTokens;
import org.rocksdb.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The inverted full-text index of a RocksDB graph: one table (column family) per full-text
 * property, keyed by character n-gram.
 *
 * <pre>
 * key    ngram (utf8) : vertexId (utf8)
 * value  (empty — the text stays in the vertex table, the index only narrows candidates)
 * </pre>
 *
 * <p>A {@code containing(term)} query looks up the posting list of every character of the term
 * and intersects them, which yields the candidate vertices that contain <em>all</em> the
 * characters — then the caller verifies each candidate against the stored text, because
 * character intersection alone does not guarantee the characters are contiguous. This is what
 * turns a table scan into a handful of point reads.
 *
 * <p>The index is maintained by the vertex writes themselves ({@link RocksDbVertexTable} adds
 * and removes rows in the same batch as the record), the same automatic contract as the geo
 * index: writing a vertex with a full-text property indexes it, changing or removing the
 * property reindexes it.
 */
public final class RocksDbFullTextIndex {

    /** Prefix of a full-text index table name. */
    public static final String FAMILY_PREFIX = "ftx:";

    /** Separator between the n-gram and the vertex id in a key. */
    private static final byte SEPARATOR = (byte) ':';

    private final RocksDbHandle handle;
    private final GraphSchema schema;

    public RocksDbFullTextIndex(RocksDbHandle handle, GraphSchema schema) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** The column family (table) of a full-text index. */
    public static String familyOf(String vertexName, String propertyName) {
        return FAMILY_PREFIX + vertexName + ":" + propertyName;
    }

    /** The column families every full-text property of a schema needs. */
    public static Set<String> columnFamilies(GraphSchema schema) {
        Set<String> families = new LinkedHashSet<>();
        for (FullTextIndexSchemas.FullTextProperty property :
                FullTextIndexSchemas.fullTextProperties(schema)) {
            families.add(familyOf(property.vertexName(), property.propertyName()));
        }
        return families;
    }

    /** The index key of one n-gram of one vertex. */
    public static byte[] key(String ngram, Object vertexId) {
        byte[] token = RocksDbBytes.utf8(ngram);
        byte[] id = RocksDbBytes.utf8(String.valueOf(vertexId));
        byte[] key = new byte[token.length + 1 + id.length];
        System.arraycopy(token, 0, key, 0, token.length);
        key[token.length] = SEPARATOR;
        System.arraycopy(id, 0, key, token.length + 1, id.length);
        return key;
    }

    /** The vertex id a key belongs to. */
    public static String vertexIdOfKey(byte[] key) {
        int separator = indexOfSeparator(key);
        if (separator < 0) {
            throw new RocksDbException("Malformed full-text index key of " + key.length + " bytes");
        }
        return RocksDbBytes.text(Arrays.copyOfRange(key, separator + 1, key.length));
    }

    private static int indexOfSeparator(byte[] key) {
        for (int i = 0; i < key.length; i++) {
            if (key[i] == SEPARATOR) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a vertex type declares any full-text property. */
    public boolean hasFullTextProperties(String vertexName) {
        return !FullTextIndexSchemas.fullTextProperties(schema, vertexName).isEmpty();
    }

    /** The property ids of the full-text properties of a vertex type. */
    public static List<Integer> propertyIds(GraphSchema schema, String vertexName) {
        List<Integer> ids = new ArrayList<>();
        for (FullTextIndexSchemas.FullTextProperty property :
                FullTextIndexSchemas.fullTextProperties(schema, vertexName)) {
            ids.add(property.propertyId());
        }
        return ids;
    }

    /** The full-text property schema of a vertex type and property index, or {@code null}. */
    public static VertexPropertySchema fullTextProperty(VertexSchema vertexSchema, int propertyId) {
        VertexPropertySchema property = vertexSchema.property(propertyId);
        return property != null && property.isFullText() ? property : null;
    }

    /**
     * Adds the index rows of a vertex to a batch: for every full-text property that has a value
     * in the record, one row per distinct n-gram.
     */
    public void index(WriteBatch batch, String vertexName, Object vertexId,
                      Map<Integer, RocksDbPropertyValue> properties) {
        VertexSchema vertexSchema = schema.vertexSchema(vertexName);
        if (vertexSchema == null) {
            return;
        }
        for (FullTextIndexSchemas.FullTextProperty property :
                FullTextIndexSchemas.fullTextProperties(schema, vertexName)) {
            RocksDbPropertyValue value = properties.get(property.propertyId());
            String text = textOf(value);
            if (text == null || text.isEmpty()) {
                continue;
            }
            String family = familyOf(vertexName, property.propertyName());
            // lower-case so the index agrees with the case-insensitive TextP.contains semantics
            for (String ngram : FullTextTokens.ngrams(text.toLowerCase(java.util.Locale.ROOT),
                    property.config().nGram())) {
                handle.put(batch, family, key(ngram, vertexId), new byte[0]);
            }
        }
    }

    /** Removes the index rows of a vertex (used when the text changes or the vertex goes). */
    public void unindex(WriteBatch batch, String vertexName, Object vertexId,
                        Map<Integer, RocksDbPropertyValue> properties) {
        for (FullTextIndexSchemas.FullTextProperty property :
                FullTextIndexSchemas.fullTextProperties(schema, vertexName)) {
            RocksDbPropertyValue value = properties.get(property.propertyId());
            String text = textOf(value);
            if (text == null || text.isEmpty()) {
                continue;
            }
            String family = familyOf(vertexName, property.propertyName());
            for (String ngram : FullTextTokens.ngrams(text.toLowerCase(java.util.Locale.ROOT),
                    property.config().nGram())) {
                handle.delete(batch, family, key(ngram, vertexId));
            }
        }
    }

    private static String textOf(RocksDbPropertyValue value) {
        if (value == null || value.value() == null) {
            return null;
        }
        return value.value() instanceof String string ? string : String.valueOf(value.value());
    }

    /**
     * Searches the index of one full-text property: walks the posting lists of the term's
     * characters with a k-way merge (every posting is ordered by vertex id, so the intersection
     * only advances the iterator at the smallest id and decodes lazily), returning the candidate
     * vertex ids that contain every character of the term. The caller must still verify each
     * candidate against the stored text.
     */
    public List<Object> search(String vertexName, String propertyName, String term) {
        FullTextIndexSchemas.FullTextProperty property =
                FullTextIndexSchemas.fullTextProperty(schema, vertexName, propertyName);
        if (property == null) {
            return List.of();
        }
        int nGram = property.config().nGram();
        List<String> tokens = new ArrayList<>(
                FullTextTokens.lookupTokens(term.toLowerCase(java.util.Locale.ROOT), nGram));
        if (tokens.isEmpty()) {
            return List.of();
        }
        String family = familyOf(vertexName, propertyName);
        List<RocksDbHandle.RocksDbRange> iterators = new ArrayList<>(tokens.size());
        List<String> ids = new ArrayList<>(tokens.size());
        try {
            for (String token : tokens) {
                RocksDbHandle.RocksDbRange iterator = handle.iterator(family, key(token, ""),
                        RocksDbKeys.prefixEnd(key(token, "")));
                iterators.add(iterator);
                ids.add(iterator.isValid() ? vertexIdOfKey(iterator.key()) : null);
            }
            List<Object> result = new ArrayList<>();
            while (true) {
                String smallest = null;
                boolean exhausted = false;
                for (String id : ids) {
                    if (id == null) {
                        exhausted = true;
                        break;
                    }
                    if (smallest == null || id.compareTo(smallest) < 0) {
                        smallest = id;
                    }
                }
                if (exhausted) {
                    break; // one posting is done: no more ids can be common to all
                }
                boolean allEqual = true;
                for (String id : ids) {
                    if (!id.equals(smallest)) {
                        allEqual = false;
                        break;
                    }
                }
                if (allEqual) {
                    result.add(smallest);
                    advanceAll(iterators, ids);
                } else {
                    advanceSmallest(iterators, ids, smallest);
                }
            }
            return List.copyOf(result);
        } finally {
            for (RocksDbHandle.RocksDbRange iterator : iterators) {
                iterator.close();
            }
        }
    }

    private static void advanceAll(List<RocksDbHandle.RocksDbRange> iterators, List<String> ids) {
        for (int i = 0; i < iterators.size(); i++) {
            iterators.get(i).next();
            ids.set(i, iterators.get(i).isValid() ? vertexIdOfKey(iterators.get(i).key()) : null);
        }
    }

    private static void advanceSmallest(List<RocksDbHandle.RocksDbRange> iterators, List<String> ids,
                                        String smallest) {
        for (int i = 0; i < iterators.size(); i++) {
            if (ids.get(i).equals(smallest)) {
                iterators.get(i).next();
                ids.set(i, iterators.get(i).isValid() ? vertexIdOfKey(iterators.get(i).key()) : null);
            }
        }
    }

    /** The number of indexed rows of a full-text property (diagnostics and tests). */
    public long count(String vertexName, String propertyName) {
        long count = 0;
        try (RocksDbHandle.RocksDbRange range =
                     handle.iterator(familyOf(vertexName, propertyName), null, null)) {
            while (range.isValid()) {
                count++;
                range.next();
            }
        }
        return count;
    }

    /** The n-gram settings of a vertex type's full-text property. */
    private FullTextIndexConfig configOf(String vertexName, String propertyName) {
        FullTextIndexSchemas.FullTextProperty property =
                FullTextIndexSchemas.fullTextProperty(schema, vertexName, propertyName);
        return property == null ? FullTextIndexConfig.DEFAULT : property.config();
    }
}
