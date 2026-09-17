package com.pengcheng.phoenixgraph.hnsw;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filtered vector search: a predicate pushed into the walk must return the closest
 * matching neighbours, not "the closest neighbours of which some match".
 *
 * <p>The dataset carries a {@code bucket} (0..9, so 10% selectivity) and a
 * {@code group} (0/1, 50%) attribute that are independent of the vector geometry,
 * which is exactly the situation where post-filtering a plain nearest-neighbour
 * result loses most of the matches.
 */
class HnswFilteredSearchTest {

    private static final int DIMENSIONS = 16;
    private static final int SIZE = 2_000;
    private static final int K = 20;

    @Test
    void pushedDownFilterReturnsOnlyMatchingAndCloseNeighbours() {
        Index index = build();
        HnswFilter filter = bucketFilter(3);

        Object probe = index.ids.get(11);
        List<HnswSearchResult> found = index.index.search(index.query(probe), K, 50, filter);

        assertEquals(K, found.size());
        assertTrue(found.stream().allMatch(hit -> index.bucketOf(hit.vertexId()) == 3),
                "every result matches the predicate");
        double recall = recall(found, index.exact(index.query(probe), K, filter));
        assertTrue(recall >= 0.9, "filtered recall@" + K + " = " + recall);
    }

    @Test
    void pushedDownFilterBeatsPostFilteringOnSelectivePredicates() {
        Index index = build();
        double pushed = 0;
        double post = 0;
        int queries = 20;
        for (int i = 0; i < queries; i++) {
            Object id = index.ids.get((i * 37) % SIZE);
            float[] query = index.query(id);
            HnswFilter filter = bucketFilter(i % 10);
            Set<Object> expected = index.exactIds(query, K, filter);

            List<HnswSearchResult> filtered = index.index.search(query, K, 50, filter);
            // the naive approach: ask for the K closest, then drop the ones that do not match
            List<HnswSearchResult> plain = index.index.search(query, K, 50);
            pushed += hits(filtered, expected) / (double) expected.size();
            post += hits(plain, expected) / (double) expected.size();
        }
        pushed /= queries;
        post /= queries;
        System.out.printf("[filtered] recall@%d: pushed-down=%.3f, post-filter=%.3f (%d queries, bucket filter)%n",
                K, pushed, post, queries);
        assertTrue(pushed >= 0.9, "pushed-down recall = " + pushed);
        assertTrue(pushed > post + 0.5, "pushing the filter down must beat post-filtering: "
                + pushed + " vs " + post);
    }

    @Test
    void theCandidateListIsWidenedByTheSelectivity() {
        Index index = build();
        // a declared selectivity decides the target up front: k / selectivity
        Object probe = index.ids.get(5);
        HnswSearchOutcome declared = index.index.searchFiltered(index.query(probe), K, 50,
                withSelectivity(bucketFilter(3), 0.1));
        assertEquals(200, declared.efTarget(), "k / selectivity = 20 / 0.1");
        assertEquals(50, declared.ef());
        assertTrue(declared.visited() > declared.ef(), "the walk visits more than the base ef");

        // without a hint the pass rate observed while searching widens the target
        HnswSearchOutcome adapted = index.index.searchFiltered(index.query(probe), K, 50, bucketFilter(3));
        assertTrue(adapted.efTarget() > 50, "an unknown selectivity widens the target: " + adapted.efTarget());
        assertTrue(adapted.visited() > adapted.ef(), "visited=" + adapted.visited());

        // a broad predicate needs no widening
        HnswSearchOutcome broad = index.index.searchFiltered(index.query(probe), K, 50, bucketFilter(-1, v -> true));
        assertEquals(50, broad.efTarget(), "a filter that matches everything keeps the base ef");
    }

    @Test
    void aHopelessFilterStopsAtTheVisitBound() {
        Index index = build();
        HnswFilter none = (vertexId, properties) -> false;
        Object probe = index.ids.get(3);

        HnswSearchOutcome narrow = index.index.searchFiltered(index.query(probe), K, 50, withMaxEf(none, 50));
        HnswSearchOutcome wide = index.index.searchFiltered(index.query(probe), K, 50, withMaxEf(none, 300));

        assertEquals(0, narrow.results().size());
        assertEquals(0, wide.results().size());
        // the examined vertices are bounded by the widened candidate list (times a factor),
        // so a predicate nothing matches cannot make the search walk the whole graph
        assertTrue(narrow.visited() < SIZE, "narrow bound stops early: visited=" + narrow.visited());
        assertTrue(wide.visited() > narrow.visited(),
                "a wider bound walks further: " + narrow.visited() + " -> " + wide.visited());
        assertTrue(wide.visited() < SIZE * 1.5, "still bounded: visited=" + wide.visited());
    }

    @Test
    void halfSelectiveFilterKeepsFullRecall() {
        Index index = build();
        HnswFilter half = bucketFilter(-1, id -> index.groupOf(id) == 0);
        Object probe = index.ids.get(21);

        List<HnswSearchResult> found = index.index.search(index.query(probe), K, 50, half);

        assertEquals(K, found.size());
        double recall = recall(found, index.exact(index.query(probe), K, half));
        assertTrue(recall >= 0.95, "recall@" + K + " = " + recall);
    }

    @Test
    void searchByIdHonoursTheFilterAndExcludesTheVertexItself() {
        Index index = build();
        Object id = index.ids.get(100);

        List<HnswSearchResult> found = index.index.searchById(id, K, 50, bucketFilter(7));

        assertEquals(K, found.size());
        assertTrue(found.stream().noneMatch(hit -> id.equals(hit.vertexId())));
        assertTrue(found.stream().allMatch(hit -> index.bucketOf(hit.vertexId()) == 7));
    }

    // ------------------------------------------------------------------ helpers

    private static double recall(List<HnswSearchResult> found, List<HnswSearchResult> expected) {
        Set<Object> ids = new HashSet<>();
        found.forEach(hit -> ids.add(hit.vertexId()));
        long hits = expected.stream().limit(K).filter(hit -> ids.contains(hit.vertexId())).count();
        return (double) hits / Math.min(K, expected.size());
    }

    private static long hits(List<HnswSearchResult> found, Set<Object> expected) {
        return found.stream().filter(hit -> expected.contains(hit.vertexId())).count();
    }

    private static HnswFilter bucketFilter(int bucket) {
        return bucketFilter(bucket, null);
    }

    private static HnswFilter bucketFilter(int bucket, java.util.function.Predicate<Object> extra) {
        return new HnswFilter() {
            @Override
            public List<String> propertyNames() {
                return List.of("bucket", "group");
            }

            @Override
            public boolean test(Object vertexId, Map<String, Object> properties) {
                if (extra != null) {
                    return extra.test(vertexId);
                }
                return properties.get("bucket") instanceof Number number && number.intValue() == bucket;
            }
        };
    }

    private static HnswFilter withSelectivity(HnswFilter delegate, double selectivity) {
        return new HnswFilter() {
            @Override
            public List<String> propertyNames() {
                return delegate.propertyNames();
            }

            @Override
            public double selectivity() {
                return selectivity;
            }

            @Override
            public boolean test(Object vertexId, Map<String, Object> properties) {
                return delegate.test(vertexId, properties);
            }
        };
    }

    private static HnswFilter withMaxEf(HnswFilter delegate, int maxEf) {
        return new HnswFilter() {
            @Override
            public List<String> propertyNames() {
                return delegate.propertyNames();
            }

            @Override
            public boolean test(Object vertexId, Map<String, Object> properties) {
                return delegate.test(vertexId, properties);
            }

            @Override
            public int maxEf(int ef, int k) {
                return maxEf;
            }
        };
    }

    /** The built index plus the attributes and brute-force reference it is measured against. */
    private static final class Index {

        private final HnswIndex index;
        private final Map<Object, float[]> vectors = new HashMap<>();
        private final Map<Object, Integer> buckets = new HashMap<>();
        private final Map<Object, Integer> groups = new HashMap<>();
        private final List<Object> ids = new ArrayList<>();

        private Index() {
            Random random = new Random(20240916L);
            float[][] centres = new float[20][];
            for (int c = 0; c < centres.length; c++) {
                centres[c] = randomVector(random);
            }
            HeapHnswVectorSource source = new HeapHnswVectorSource();
            InMemoryHnswStore store = new InMemoryHnswStore();
            Random attributes = new Random(4711L);
            for (int i = 0; i < SIZE; i++) {
                float[] centre = centres[i % centres.length];
                float[] vector = new float[DIMENSIONS];
                for (int d = 0; d < DIMENSIONS; d++) {
                    vector[d] = centre[d] + (float) random.nextGaussian() * 0.15f;
                }
                String id = "v" + i;
                int bucket = attributes.nextInt(10);
                int group = attributes.nextInt(2);
                ids.add(id);
                vectors.put(id, vector);
                buckets.put(id, bucket);
                groups.put(id, group);
                source.put(id, vector);
                source.putProperties(id, Map.of("bucket", bucket, "group", group, "name", "doc-" + i));
            }
            this.index = HnswIndex.builder()
                    .vertexName("Doc").vectorProperty("embedding")
                    .store(store).vectors(source)
                    .config(HnswConfig.defaults())
                    .size(SIZE)
                    .build();
            index.insertAll(ids);
        }

        float[] query(Object vertexId) {
            return vectors.get(vertexId);
        }

        int bucketOf(Object vertexId) {
            return buckets.get(vertexId);
        }

        int groupOf(Object vertexId) {
            return groups.get(vertexId);
        }

        /** Exact filtered neighbours, closest first. */
        List<HnswSearchResult> exact(float[] query, int k, HnswFilter filter) {
            List<HnswSearchResult> all = new ArrayList<>(SIZE);
            for (Object id : ids) {
                Map<String, Object> properties = Map.of("bucket", buckets.get(id), "group", groups.get(id));
                if (!filter.test(id, properties)) {
                    continue;
                }
                all.add(new HnswSearchResult(id, HnswMetric.L2.distance(query, vectors.get(id))));
            }
            all.sort(Comparator.comparingDouble(HnswSearchResult::distance));
            return all.size() > k ? new ArrayList<>(all.subList(0, k)) : all;
        }

        Set<Object> exactIds(float[] query, int k, HnswFilter filter) {
            Set<Object> out = new HashSet<>();
            for (HnswSearchResult hit : exact(query, k, filter)) {
                out.add(hit.vertexId());
            }
            return out;
        }

        private static float[] randomVector(Random random) {
            float[] vector = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] = (float) random.nextGaussian();
            }
            return vector;
        }
    }

    private static Index build() {
        Index index = new Index();
        assertNotNull(index.index);
        return index;
    }
}
