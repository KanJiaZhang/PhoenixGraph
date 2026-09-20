package com.pengcheng.phoenixgraph.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accuracy of the Aerospike query path: twenty traversals, every answer compared with what the
 * stored records say it should be.
 *
 * <p>Ten one-hop queries (out and in, edges and vertices, with a condition, a count, a cap, a
 * projection and a creation-time window) and ten two-hop queries (five ending on vertices, five ending
 * on edges, over the payment, IP and phone relations, with a filter in the middle, a projection, a cap,
 * a count and a deduplication).
 *
 * <p>The reference is computed by {@link AerospikeHopSuite} from the bins themselves, not from a query,
 * so these are checks against the data: a hop that silently drops an edge, a condition that keeps the
 * wrong rows, a projection that reads the wrong property or a cap that cuts the wrong end all show up
 * as a difference, and the failure message names the query and what differed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AerospikeHopQueryAccuracyTest {

    private AerospikeHopSuite suite;

    @BeforeAll
    void openTheDemoGraph() {
        suite = AerospikeHopSuite.open("payment-graph-accuracy");
    }

    @AfterAll
    void close() {
        if (suite != null) {
            suite.close();
        }
    }

    @Test
    void tenOneHopTraversalsAnswerWhatTheStoredRecordsSay() {
        assertGroup(suite.oneHopCases(), 10);
    }

    @Test
    void fiveTwoHopTraversalsEndingOnVerticesAnswerWhatTheStoredRecordsSay() {
        List<AerospikeHopSuite.Case> vertices = suite.twoHopCases().stream()
                .filter(testCase -> testCase.endsAt() == AerospikeHopSuite.EndsAt.VERTEX).toList();
        assertGroup(vertices, 5);
    }

    @Test
    void fiveTwoHopTraversalsEndingOnEdgesAnswerWhatTheStoredRecordsSay() {
        List<AerospikeHopSuite.Case> edges = suite.twoHopCases().stream()
                .filter(testCase -> testCase.endsAt() == AerospikeHopSuite.EndsAt.EDGE).toList();
        assertGroup(edges, 5);
    }

    /** Every case of a group is run once and compared with its reference answer. */
    private void assertGroup(List<AerospikeHopSuite.Case> cases, int expectedCount) {
        assertEquals(expectedCount, cases.size(), "the group has the number of cases it promises");
        for (AerospikeHopSuite.Case testCase : cases) {
            Object expected = testCase.expected().get();
            List<Object> actual = suite.gremlin().execute(testCase.query());
            assertNotNull(actual, testCase.id() + ": the query answered nothing at all");
            String difference = AerospikeHopSuite.difference(testCase, expected, actual);
            assertNull(difference, () -> testCase.id() + " " + testCase.label() + "\n  query: "
                    + testCase.query());
            assertTrue(actual.size() > 0, testCase.id() + ": an empty answer for " + testCase.query());
        }
    }

    /**
     * The demo data itself has to have the shape the twenty queries assume: a hub that pays a crowd, a
     * ring account with all five edge classes, and both directions present. A check that would pass on
     * empty data is not a check.
     */
    @Test
    void theDemoDataHasTheShapeTheQueriesAssume() {
        int hubPayments = suite.edges(AerospikeHopSuite.HUB, "Payment",
                com.pengcheng.phoenixgraph.query.storage.EdgeSide.OUT).size();
        assertTrue(hubPayments >= 10, "the hub pays a crowd, got " + hubPayments);
        assertTrue(!suite.edges(AerospikeHopSuite.RING, "Payment",
                com.pengcheng.phoenixgraph.query.storage.EdgeSide.OUT).isEmpty(), "the ring account pays");
        assertTrue(!suite.edges(AerospikeHopSuite.RING, "Payment",
                com.pengcheng.phoenixgraph.query.storage.EdgeSide.IN).isEmpty(), "the ring account is paid");
        for (String edgeName : List.of("AccountToIP", "AccountToPhone")) {
            assertTrue(!suite.edges(AerospikeHopSuite.RING, edgeName,
                    com.pengcheng.phoenixgraph.query.storage.EdgeSide.OUT).isEmpty(),
                    "the ring account has " + edgeName);
        }
        assertTrue(!suite.vertex(AerospikeHopSuite.HUB).isEmpty(), "the hub vertex has properties");
    }
}
