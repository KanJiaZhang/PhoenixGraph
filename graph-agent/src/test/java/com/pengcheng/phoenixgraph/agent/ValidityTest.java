package com.pengcheng.phoenixgraph.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validity window: what "true at an instant" means, how a client writes it, and what an unversioned fact
 * does. The point of the dimension is that a fact stops holding without being deleted (an incident in
 * September must not explain a fault in October), so the boundary cases are the tests.
 */
class ValidityTest {

    private static final long SEPTEMBER = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli();
    private static final long OCTOBER = Instant.parse("2026-10-24T10:00:00Z").toEpochMilli();

    @Test
    void anElementWithNoWindowIsValidAtEveryInstant() {
        assertTrue(Validity.always().isAlways());
        assertTrue(Validity.always().validAt(0));
        assertTrue(Validity.always().validAt(Long.MAX_VALUE));
        assertTrue(Validity.ofProperties(Map.of()).validAt(SEPTEMBER));
        assertTrue(Validity.ofProperties(Map.of("relation", "caused")).validAt(SEPTEMBER),
                "a property map without a window is an unversioned element");
    }

    @Test
    void theStartIsInclusiveAndTheEndIsExclusive() {
        Validity window = new Validity(SEPTEMBER, OCTOBER);
        assertFalse(window.validAt(SEPTEMBER - 1));
        assertTrue(window.validAt(SEPTEMBER), "validFrom is inclusive");
        assertTrue(window.validAt(OCTOBER - 1));
        assertFalse(window.validAt(OCTOBER), "validTo is exclusive: the instant the next fact starts");
    }

    @Test
    void oneOpenEndIsEnough() {
        assertTrue(new Validity(SEPTEMBER, null).validAt(Long.MAX_VALUE), "still holds");
        assertFalse(new Validity(SEPTEMBER, null).validAt(SEPTEMBER - 1));
        assertTrue(new Validity(null, OCTOBER).validAt(0), "always held until the end");
        assertFalse(new Validity(null, OCTOBER).validAt(OCTOBER));
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Validity(OCTOBER, SEPTEMBER));
        assertThrows(IllegalArgumentException.class, () -> new Validity(OCTOBER, OCTOBER));
    }

    @Test
    void aClientMayWriteADateAnInstantOrMillis() {
        assertEquals(SEPTEMBER, Validity.parseMillis("2026-09-12"));
        assertEquals(SEPTEMBER, Validity.parseMillis("2026-09-12T00:00:00Z"));
        assertEquals(SEPTEMBER, Validity.parseMillis("2026-09-12T00:00:00+00:00"));
        assertEquals(SEPTEMBER + 3 * 3_600_000L, Validity.parseMillis("2026-09-12T03:00:00"));
        assertEquals(SEPTEMBER, Validity.parseMillis(SEPTEMBER));
        assertEquals(SEPTEMBER, Validity.parseMillis(String.valueOf(SEPTEMBER)));
        assertNull(Validity.parseMillis(null));
        assertNull(Validity.parseMillis("  "));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Validity.parseMillis("last tuesday"));
        assertTrue(error.getMessage().contains("2026-10-24"), error.getMessage());
    }

    @Test
    void anEntityReadInTwoDocumentsHoldsForBoth() {
        Validity early = new Validity(SEPTEMBER, OCTOBER);
        Validity late = new Validity(OCTOBER, null);
        Validity union = early.union(late);
        assertEquals(SEPTEMBER, union.from());
        assertNull(union.to(), "an open end wins: the entity still exists");
        assertTrue(union.validAt(OCTOBER + 1));
    }

    @Test
    void anIndexAtAnInstantKeepsTheChunksSoAnswersStayCitable() {
        AgentIndex index = new AgentIndex("g", "hashing-8", 8, "now",
                List.of(
                        new AgentIndex.Entity("deploy-42", "Entity", "Deploy", "deploy-42", "card",
                                new float[] {1f}, List.of("c1"), List.of("doc §1"), List.of(), SEPTEMBER, null),
                        new AgentIndex.Entity("deploy-48", "Entity", "Deploy", "deploy-48", "card",
                                new float[] {2f}, List.of("c2"), List.of("doc §2"), List.of(), OCTOBER, null)),
                List.of(
                        new AgentIndex.Relation("deploy-42", "caused", "cache-eviction-storm", "caused", "c1",
                                "doc §1", "deploy-42 caused the storm.", SEPTEMBER, OCTOBER),
                        new AgentIndex.Relation("deploy-48", "caused", "cache-key-prefix-churn", "caused", "c2",
                                "doc §2", "deploy-48 caused the churn.", OCTOBER, null)),
                List.of(new AgentIndex.Chunk("c1", "doc", "doc", 0, "deploy-42 caused the storm."),
                        new AgentIndex.Chunk("c2", "doc", "doc", 1, "deploy-48 caused the churn.")),
                Map.of());

        AgentIndex inSeptember = Validity.at(index, SEPTEMBER);
        assertEquals(1, inSeptember.entities().size());
        assertEquals("deploy-42", inSeptember.entities().get(0).id());
        assertEquals(1, inSeptember.relations().size());
        assertEquals(2, inSeptember.chunks().size(), "citations are not time-versioned");

        AgentIndex inOctober = Validity.at(index, OCTOBER);
        assertEquals(2, inOctober.entities().size(), "deploy-42 still exists, its relation has closed");
        assertEquals("deploy-48", inOctober.relations().get(0).head());

        assertEquals(2, Validity.at(index, null).relations().size(), "no instant means every fact ever");
    }
}
