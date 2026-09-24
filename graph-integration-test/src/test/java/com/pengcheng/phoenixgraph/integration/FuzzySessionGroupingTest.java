package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyDemo;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLink;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLinks;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo sessions and the patterns, with no storage involved: every pattern is asked which link each
 * seeded session would produce, and the groups are read back. This is the "each pattern links some
 * accounts" claim without needing an Aerospike, so it runs everywhere.
 *
 * <p>It is also the place where the two new patterns are told apart: {@code ev_a6} is on both, {@code ev_a7}
 * only on pattern 4 (its dispute is tied by the reason), {@code ev_a8} only on pattern 4 (tied by the cookie)
 * and {@code ev_a9} only on pattern 3 (its login was not from a mobile).
 */
class FuzzySessionGroupingTest {

    /** patternId -> linkVertexId -> the accounts whose session produced that link. */
    private static Map<Integer, Map<String, Set<String>>> groups() {
        FuzzyPatternRegistry patterns = FuzzyDemo.demoRegistry();
        Map<Integer, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        for (String[] session : FuzzyLinkDemoLoader.eventSessions()) {
            FuzzyContext context = FuzzyContext.of(FuzzyLinkDemoLoader.eventsOf(session));
            for (FuzzyLink link : FuzzyLinks.plan(patterns, context)) {
                groups.computeIfAbsent(link.patternId(), id -> new LinkedHashMap<>())
                        .computeIfAbsent(link.linkVertexId(), id -> new LinkedHashSet<>())
                        .add(session[0]);
            }
        }
        return groups;
    }

    private static int largest(Map<Integer, Map<String, Set<String>>> groups, int patternId) {
        return groups.getOrDefault(patternId, Map.of()).values().stream()
                .mapToInt(Set::size).max().orElse(0);
    }

    private static Set<String> members(Map<Integer, Map<String, Set<String>>> groups, int patternId,
                                       String linkVertexId) {
        return groups.getOrDefault(patternId, Map.of()).getOrDefault(linkVertexId, Set.of());
    }

    @Test
    void everySequencePatternLinksSomeSeededAccounts() {
        Map<Integer, Map<String, Set<String>>> groups = groups();

        assertEquals(Set.of("ev_a1", "ev_a2"), members(groups, 2, "fl_2_10.122_notmycard"),
                "the ordered pattern links the two sessions with a forward step each");
        assertEquals(Set.of("ev_a6", "ev_a9"), members(groups, 3, "fl_3_10.141_notmycard"),
                "pattern 3 links by the dispute address, so the web login is in and the moved address is out");
        assertEquals(Set.of("ev_a6", "ev_a7"), members(groups, 4, "fl_4_10.141_notmycard"),
                "pattern 4 also takes the NotMyCard dispute whose address moved, and drops the web login");
        assertEquals(Set.of("ev_a8"), members(groups, 4, "fl_4_10.141_fraud"),
                "and it takes the Fraud dispute tied by the cookie, as its own group");

        assertEquals(2, largest(groups, 2), "pattern 2's biggest group: " + groups.get(2));
        assertEquals(2, largest(groups, 3), "pattern 3's biggest group: " + groups.get(3));
        assertEquals(2, largest(groups, 4), "pattern 4's biggest group: " + groups.get(4));
        assertEquals(3, largest(groups, 5), "pattern 5's biggest group: " + groups.get(5));
    }

    /**
     * Pattern 5 is the anyFields UDF: the session's signature is the fields the login, the payment and the
     * dispute agree on. {@code ev_a1}, {@code ev_a2} and {@code ev_a5} agree on cookie, ip and type on one /16,
     * so they are one group; {@code ev_b1} agrees on its own network; {@code ev_b2} agrees on only one field and
     * {@code ev_b3} puts 60 seconds between the balance check and the payment, so neither is linked.
     */
    @Test
    void anyFieldsLinksTheSessionsThatAgreeOnThreeFields() {
        Map<Integer, Map<String, Set<String>>> groups = groups();

        assertEquals(Set.of("ev_a1", "ev_a2", "ev_a5"),
                members(groups, 5, "fl_5_10.122_cookie_ip_type"),
                "the fragment is the agreed field names, in alphabetical order");
        assertEquals(Set.of("ev_a3"), members(groups, 5, "fl_5_10.130_cookie_ip_type"));
        assertEquals(Set.of("ev_b1"), members(groups, 5, "fl_5_10.155_cookie_ip_type"));

        assertFalse(groups.getOrDefault(5, Map.of()).values().stream()
                .anyMatch(members -> members.contains("ev_b2")),
                "ev_b2 agrees on too few fields to carry a signature");
        assertFalse(groups.getOrDefault(5, Map.of()).values().stream()
                .anyMatch(members -> members.contains("ev_b3")),
                "ev_b3's payment is 60 seconds after the balance check, past the 50s window");
    }

    @Test
    void aSessionThePatternDoesNotApplyToProducesNoLink() {
        Map<Integer, Map<String, Set<String>>> groups = groups();

        assertFalse(members(groups, 3, "fl_3_10.141_notmycard").contains("ev_a7"),
                "pattern 3 needs the dispute to come from the login address");
        assertFalse(members(groups, 4, "fl_4_10.141_notmycard").contains("ev_a9"),
                "pattern 4 needs a mobile login");

        for (String[] session : FuzzyLinkDemoLoader.eventSessions()) {
            FuzzyContext context = FuzzyContext.of(FuzzyLinkDemoLoader.eventsOf(session));
            if (session[0].equals("ev_a4")) {
                assertNull(FuzzyDemo.SESSION_100S.key(context), "the step 102s away is outside every window");
                assertNull(FuzzyDemo.SESSION_AROUND_100S.key(context));
                assertNull(FuzzyDemo.SESSION_MOBILE_DISPUTE.key(context));
                assertNull(FuzzyDemo.SESSION_SHARED_FIELDS.key(context));
            }
            if (session[0].equals("ev_b2") || session[0].equals("ev_b3")) {
                assertNull(FuzzyDemo.SESSION_SHARED_FIELDS.key(context),
                        session[0] + " does not carry a three-field signature");
            }
        }
    }

    @Test
    void theEventListIsWhatIsLinkedRatherThanAStoredProperty() {
        // two sessions of one shape produce one node, and the node's key is the /16 and the reason
        Map<Integer, Map<String, Set<String>>> groups = groups();
        assertTrue(groups.get(3).keySet().stream().allMatch(id -> id.startsWith("fl_3_")));
        assertEquals(List.of("10.141", "NotMyCard"),
                FuzzyDemo.SESSION_AROUND_100S.fragments(FuzzyContext.of(
                        FuzzyLinkDemoLoader.eventsOf(FuzzyLinkDemoLoader.eventSessions().get(5)))),
                "the key is the /16 of the login address and the dispute reason");
    }
}
