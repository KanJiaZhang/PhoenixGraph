package com.pengcheng.phoenixgraph.integration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the bundled knowledge-graph dataset (no Aerospike needed): its size,
 * the composed-id scheme and the derived inverse-relation table.
 */
class KnowledgeGraphDatasetTest {

    @Test
    void loadsTheBundledTripleDataset() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();

        assertEquals(75_492, dataset.entityNames().size());
        assertEquals(200, dataset.relationNames().size());
        assertEquals(149_678, dataset.triples().size());
        assertTrue(dataset.entityNames().stream().noneMatch(java.util.Objects::isNull));
        assertTrue(dataset.relationNames().stream().noneMatch(java.util.Objects::isNull));
    }

    @Test
    void composedIdsAreShardedByRelation() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();

        Set<Integer> shards = new java.util.TreeSet<>();
        for (KnowledgeGraphDataset.Triple triple : dataset.triples()) {
            String relation = dataset.relationName(triple.relationId());
            int shard = KnowledgeGraphIds.shard(relation);
            assertTrue(shard >= 0 && shard < KnowledgeGraphIds.SHARD_COUNT, "shard for " + relation);
            shards.add(shard);

            // both endpoints of a triple are stored in the shard of its relation,
            // so the edges of one relation never leave that shard
            String head = dataset.entityName(triple.headId());
            String tail = dataset.entityName(triple.tailId());
            assertEquals(head + "_" + shard, KnowledgeGraphIds.composeId(head, relation));
            assertEquals(tail + "_" + shard, KnowledgeGraphIds.composeId(tail, relation));
        }
        assertEquals(KnowledgeGraphIds.SHARD_COUNT, shards.size(), "all shards are used");
    }

    @Test
    void everyEntityPairAndRelationProducesAKnownEdgeSchema() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();

        Set<String> edgeLabels = new TreeSet<>();
        Set<String> vertexLabels = new TreeSet<>();
        for (KnowledgeGraphDataset.Triple triple : dataset.triples()) {
            edgeLabels.add(KnowledgeGraphIds.EDGE_LABEL);
            vertexLabels.add(KnowledgeGraphIds.VERTEX_LABEL);
            // the id is always composed from the relation's shard, never from the name
            assertTrue(KnowledgeGraphIds.composeId(dataset.entityName(triple.headId()),
                    dataset.relationName(triple.relationId())).endsWith(
                    "_" + KnowledgeGraphIds.shard(dataset.relationName(triple.relationId()))));
        }
        // one vertex schema and one edge schema, regardless of the data
        assertEquals(Set.of("Entity"), vertexLabels);
        assertEquals(Set.of("Entity_Entity"), edgeLabels);
    }

    @Test
    void inverseRelationsFormAnInvolution() {
        int[] inverse = KnowledgeGraphDataset.load().inverseRelations();

        for (int relation = 0; relation < inverse.length; relation++) {
            int reverse = inverse[relation];
            assertTrue(reverse >= 0 && reverse < inverse.length, "reverse in range for " + relation);
            assertEquals(relation, inverse[reverse],
                    "inverse must be an involution: inverse(inverse(r)) == r for relation " + relation);
        }
    }

    @Test
    void knownInversePairsAndSymmetricRelationsAreRecognised() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();
        int[] inverse = dataset.inverseRelations();

        // mutual pairs found by majority vote over the reverse triples
        assertInverseOf(dataset, inverse, "concept:bookwriter", "concept:agentcreated");
        assertInverseOf(dataset, inverse, "concept:worksfor", "concept:organizationhiredperson");

        // symmetric relations are their own reverse
        assertEquals("concept:teamplaysagainstteam",
                dataset.inverseRelationName(relationId(dataset, "concept:teamplaysagainstteam")));
        assertEquals("concept:clothingtogowithclothing",
                dataset.inverseRelationName(relationId(dataset, "concept:clothingtogowithclothing")));
        // 'mutualproxyfor' is both the best reverse of 'proxyfor' and of itself, so the
        // mutual-only rule keeps it symmetric instead of pairing it with 'proxyfor'
        assertEquals("concept:mutualproxyfor",
                dataset.inverseRelationName(relationId(dataset, "concept:mutualproxyfor")));

        // a handful of real inverse pairs, the rest are symmetric/unknown
        long mutual = 0;
        for (int relation = 0; relation < inverse.length; relation++) {
            if (inverse[relation] != relation && relation < inverse[relation]) {
                mutual++;
            }
        }
        assertEquals(6, mutual, "mutual inverse pairs in the dataset");
    }

    @Test
    void evenlySampledTriplesKeepEveryShard() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();
        List<KnowledgeGraphDataset.Triple> sampled = KnowledgeGraphSeeder.sample(dataset.triples(), 5_000);

        assertEquals(5_000, sampled.size());
        Set<Integer> shards = new TreeSet<>();
        for (KnowledgeGraphDataset.Triple triple : sampled) {
            shards.add(KnowledgeGraphIds.shard(dataset.relationName(triple.relationId())));
        }
        assertEquals(KnowledgeGraphIds.SHARD_COUNT, shards.size());
    }

    private static void assertInverseOf(KnowledgeGraphDataset dataset, int[] inverse,
                                        String relation, String expectedReverse) {
        int id = relationId(dataset, relation);
        assertEquals(expectedReverse, dataset.relationNames().get(inverse[id]),
                relation + " should be the reverse of " + expectedReverse);
    }

    private static int relationId(KnowledgeGraphDataset dataset, String name) {
        List<String> names = new ArrayList<>(dataset.relationNames());
        int id = names.indexOf(name);
        assertTrue(id >= 0, "unknown relation " + name);
        return id;
    }
}
