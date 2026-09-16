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
    void composedIdsAlwaysLandInAnEntitySchema() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();

        for (int id = 0; id < dataset.entityNames().size(); id++) {
            String name = dataset.entityName(id);
            int slot = KnowledgeGraphIds.slot(name);
            assertTrue(slot >= 0 && slot < KnowledgeGraphIds.ENTITY_COUNT, "slot for " + name);
            assertEquals(slot + "_" + name, KnowledgeGraphIds.composeId(name));
            assertEquals("Entity" + slot, KnowledgeGraphIds.vertexLabel(name));
        }
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
    void everyEntityPairAndRelationProducesAKnownEdgeSchema() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();

        Set<String> edgeLabels = new TreeSet<>();
        for (KnowledgeGraphDataset.Triple triple : dataset.triples()) {
            edgeLabels.add(KnowledgeGraphIds.edgeLabel(dataset.entityName(triple.headId()),
                    dataset.entityName(triple.tailId())));
        }
        // all 100 ordered EntityI_EntityJ combinations are actually used, including
        // the 10 self-referencing ones
        assertEquals(100, edgeLabels.size());
        assertTrue(edgeLabels.contains("Entity0_Entity0"));
    }

    @Test
    void evenlySampledTriplesKeepEveryEdgeSchema() {
        KnowledgeGraphDataset dataset = KnowledgeGraphDataset.load();
        List<KnowledgeGraphDataset.Triple> sampled = KnowledgeGraphSeeder.sample(dataset.triples(), 5_000);

        assertEquals(5_000, sampled.size());
        Set<String> edgeLabels = new TreeSet<>();
        for (KnowledgeGraphDataset.Triple triple : sampled) {
            edgeLabels.add(KnowledgeGraphIds.edgeLabel(dataset.entityName(triple.headId()),
                    dataset.entityName(triple.tailId())));
        }
        assertEquals(100, edgeLabels.size());
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
