package com.pengcheng.phoenixgraph.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Turning text into vectors, so a knowledge graph can answer "what is near what" as well as "what is
 * connected to what".
 *
 * <p>The default implementation is a <b>hashing bag of character n-grams</b>: deterministic, offline,
 * dependency-free, and good enough to make the retrieval funnel work end to end (an inverted-index seed
 * stage, then a vector rerank). It is not a semantic embedder - a language model is better at that - and
 * the interface is the point: {@link Embedding.Function} is what the toolkit asks for, so an installation
 * that has a real model plugs it in and everything downstream (ingestion, the index artifact, the tools)
 * keeps working. What the hashing embedder does guarantee is that ingestion is reproducible: the same
 * document produces the same vectors on any machine, which is what a quality report needs to be
 * comparable.
 *
 * <p>Character n-grams (2 and 3 characters, plus the whole-token bag for ASCII words) are chosen because
 * Chinese has no spaces: a word-based embedder would need a segmenter, and a segmenter is one more thing
 * that can differ between the machine that ingests and the machine that queries.
 */
public interface Embedding {

    /** The number of dimensions every vector has. */
    int dimensions();

    /** Embeds one text (an entity's description, a question) into a unit-length vector. */
    float[] embed(String text);

    /** A vector of the given length, or null when the value is not a usable vector. */
    static float[] parse(Object value, int dimensions) {
        if (value == null) {
            return null;
        }
        float[] parsed;
        if (value instanceof float[] floats) {
            parsed = floats;
        } else if (value instanceof double[] doubles) {
            parsed = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                parsed[i] = (float) doubles[i];
            }
        } else if (value instanceof java.util.List<?> list) {
            parsed = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                parsed[i] = item instanceof Number number ? number.floatValue() : 0f;
            }
        } else {
            return null;
        }
        return dimensions <= 0 || parsed.length == dimensions ? parsed : null;
    }

    /** Cosine similarity of two vectors (0 when either is empty; both are unit length by construction). */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** The default embedder: hashed character n-grams, unit length, deterministic. */
    static Embedding hashing(int dimensions) {
        return new HashingEmbedding(dimensions);
    }

    /** The embedder the toolkit uses unless a caller brings its own (768 dimensions, like a small model). */
    static Embedding hashing() {
        return hashing(768);
    }

    /** Character n-grams hashed into a fixed number of dimensions. */
    final class HashingEmbedding implements Embedding {

        private final int dimensions;

        HashingEmbedding(int dimensions) {
            if (dimensions < 16) {
                throw new IllegalArgumentException("An embedding needs at least 16 dimensions");
            }
            this.dimensions = dimensions;
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimensions];
            if (text == null || text.isBlank()) {
                return vector;
            }
            String normalised = text.toLowerCase().replaceAll("\\s+", " ").strip();
            Map<String, Integer> counts = new HashMap<>();
            // character bigrams and trigrams: they work for Chinese without a segmenter
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= normalised.length(); i++) {
                    String gram = normalised.substring(i, i + n);
                    if (gram.isBlank()) {
                        continue;
                    }
                    counts.merge(gram, 1, Integer::sum);
                }
            }
            // plus whole words, so an ASCII identifier keeps its own signal
            for (String word : normalised.split("[^\\p{L}\\p{N}]+")) {
                if (word.length() > 1) {
                    counts.merge(word, 1, Integer::sum);
                }
            }
            double squares = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                int bucket = Math.floorMod(entry.getKey().hashCode(), dimensions);
                // sublinear term frequency, so a repeated phrase does not dominate the vector
                double weight = 1 + Math.log(entry.getValue());
                vector[bucket] += (float) weight;
                squares += weight * weight;
            }
            double norm = Math.sqrt(squares);
            if (norm > 0) {
                for (int i = 0; i < dimensions; i++) {
                    vector[i] /= (float) norm;
                }
            }
            return vector;
        }
    }
}
