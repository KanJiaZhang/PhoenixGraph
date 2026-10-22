package com.pengcheng.phoenixgraph.agent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Near-duplicate entity names that the deterministic deduper deliberately did not merge.
 *
 * <p>This is a review aid, not an automatic merge: a prefix, a one-character spelling difference or a
 * shared token set can be a real alias or two different things. The candidates carry their reason so a
 * human (or a later, approved resolver) can decide.
 */
public final class DedupSuggestions {

    public record Candidate(String left, String right, double score, String reason) {
    }

    private DedupSuggestions() {
    }

    /** Keep the pairwise scan bounded on very large graphs; the most-mentioned names are the useful ones. */
    private static final int MAX_ENTITIES = 2_000;

    public static List<Candidate> find(List<KnowledgeExtractor.Entity> entities, int limit) {
        List<KnowledgeExtractor.Entity> unique = entities.stream()
                .filter(entity -> entity != null && entity.name() != null && entity.name().length() >= 3)
                .sorted(Comparator.comparingInt(KnowledgeExtractor.Entity::mentions).reversed())
                .limit(MAX_ENTITIES)
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            for (int j = i + 1; j < unique.size(); j++) {
                KnowledgeExtractor.Entity left = unique.get(i);
                KnowledgeExtractor.Entity right = unique.get(j);
                if (left.id().equals(right.id())) {
                    continue;
                }
                Candidate candidate = compare(left, right);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(Candidate::left).thenComparing(Candidate::right))
                .limit(Math.max(0, limit))
                .toList();
    }

    private static Candidate compare(KnowledgeExtractor.Entity left, KnowledgeExtractor.Entity right) {
        String a = normalize(left.name());
        String b = normalize(right.name());
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        if (a.startsWith(b) || b.startsWith(a)) {
            return new Candidate(left.id(), right.id(), 0.9, "one name is a prefix of the other");
        }
        if (Math.min(a.length(), b.length()) >= 4 && levenshtein(a, b) <= 1) {
            return new Candidate(left.id(), right.id(), 0.8, "one-character spelling difference");
        }
        double jaccard = jaccard(left.name(), right.name());
        if (jaccard >= 0.75) {
            return new Candidate(left.id(), right.id(), 0.6, "shared tokens");
        }
        return null;
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}]+", "");
    }

    private static double jaccard(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        long common = a.stream().filter(b::contains).count();
        return (double) common / union.size();
    }

    private static Set<String> tokens(String name) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            previous = current;
        }
        return previous[b.length()];
    }
}
