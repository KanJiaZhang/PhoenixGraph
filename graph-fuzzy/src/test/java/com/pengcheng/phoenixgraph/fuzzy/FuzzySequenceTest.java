package com.pengcheng.phoenixgraph.fuzzy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sequence patterns: an event list read in time order, with the steps of the pattern and the time between
 * them.
 *
 * <p>The pattern of these tests is the one the demo data carries:
 *
 * <pre>
 * fl_2_L0(ip2(Login.IP))_[&lt;100s]_B1_[&lt;100s]_P2_[&lt;100s]_D3(Dispute.DisputeReason)
 * </pre>
 *
 * <p>It reads a Login, a BalanceCheck, a Payment and a Dispute in that order, each within 100 seconds of the
 * one before, and keys the account on the /24 of the login address and the dispute reason - so two accounts
 * whose sessions look alike land on one link node.
 */
class FuzzySequenceTest {

    private static final String EXPRESSION =
            "fl_2_L0(ip2(Login.IP))_[<100s]_B1_[<100s]_P2_[<100s]_D3(Dispute.DisputeReason)";

    /** The example event list, as the demo documents it. */
    private static Map<String, Object> events(long login, long balance, long payment, long dispute,
                                              String ip, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Login", Map.of("ID", 123, "IP", ip, "Type", "Mobile", "EventTime", login));
        out.put("BalanceCheck", Map.of("ID", 123, "IP", ip, "EventTime", balance));
        out.put("Payment", Map.of("ID", 123, "USD", 1000, "CBIN", "035", "EventTime", payment));
        out.put("Dispute", Map.of("ID", 123, "DisputeReason", reason, "IP", ip, "EventTime", dispute));
        return out;
    }

    /** The example order: Login, BalanceCheck, Payment, Dispute, each step within a minute. */
    private static Map<String, Object> example() {
        return events(1790147176281L, 1790147177881L, 1790147178881L, 1790147179881L,
                "10.122.13.14", "NotMyCard");
    }

    private static FuzzyContext contextOf(Map<String, Object> events) {
        return FuzzyContexts.fromJson(FuzzyContexts.toJson(FuzzyContext.of(events)));
    }

    @Test
    void aSequenceIsReadByItsShape() {
        assertTrue(FuzzySequence.looksLikeSequence(EXPRESSION));
        assertTrue(FuzzySequence.looksLikeSequence("L0(ip2(Login.IP))_[<100s]_D1"));
        assertFalse(FuzzySequence.looksLikeSequence("emailDomain(email)+'_'+ip2(ip)"),
                "a flat pattern is not a sequence");
        assertFalse(FuzzySequence.looksLikeSequence("ip2(ip)+'_'+cardBin(cardNumber)"),
                "a flat pattern that starts with a call is not a sequence either");

        FuzzySequence sequence = FuzzySequence.parse(EXPRESSION);
        assertEquals(4, sequence.steps().size());
        assertEquals(2, sequence.declaredPatternId(), "the expression names the pattern it belongs to");
        assertEquals(List.of("L", "B", "P", "D"),
                sequence.steps().stream().map(FuzzySequence.Step::eventPrefix).toList());
        assertEquals(List.of(0, 100, 100, 100),
                sequence.steps().stream().map(FuzzySequence.Step::withinSeconds).toList(),
                "the first step has no window; the others carry the one written before them");
        assertEquals("ip2(Login.IP)", sequence.steps().get(0).valueExpression());
        assertEquals("Dispute.DisputeReason", sequence.steps().get(3).valueExpression());
        assertNull(sequence.steps().get(1).valueExpression(), "a step may contribute no value");
    }

    @Test
    void theKeyIsWhatTheStepsContribute() {
        FuzzySequence sequence = FuzzySequence.parse(EXPRESSION);
        FuzzyContext context = contextOf(example());
        assertEquals(List.of("10.122", "NotMyCard"), sequence.fragments(context));
        assertEquals("10.122_NotMyCard", sequence.key(context));

        FuzzyPattern pattern = new FuzzyPattern(2, "session", EXPRESSION, "", true);
        assertEquals("fl_2_10.122_notmycard", pattern.linkVertexId(context),
                "the key is lowercased on its way into the link id");
        assertTrue(pattern.sequence());
    }

    @Test
    void theEventsAreReadInTimeOrderWhateverOrderTheyArriveIn() {
        Map<String, Object> shuffled = new LinkedHashMap<>();
        Map<String, Object> example = example();
        shuffled.put("Dispute", example.get("Dispute"));
        shuffled.put("Payment", example.get("Payment"));
        shuffled.put("Login", example.get("Login"));
        shuffled.put("BalanceCheck", example.get("BalanceCheck"));
        assertEquals("10.122_NotMyCard", FuzzySequence.parse(EXPRESSION).key(contextOf(shuffled)));
    }

    @Test
    void extraEventsAreIgnored() {
        Map<String, Object> withExtras = new LinkedHashMap<>(example());
        withExtras.put("BalanceCheck2", Map.of("IP", "10.122.13.14", "EventTime", 1790147177000L));
        withExtras.put("Logout", Map.of("IP", "10.122.13.14", "EventTime", 1790147199999L));
        assertEquals("10.122_NotMyCard", FuzzySequence.parse(EXPRESSION).key(contextOf(withExtras)));
    }

    @Test
    void aGapOverTheWindowDoesNotMatch() {
        // the payment arrives 101 seconds after the balance check
        Map<String, Object> late = events(1790147176281L, 1790147177881L, 1790147278882L, 1790147279881L,
                "10.122.13.14", "NotMyCard");
        assertNull(FuzzySequence.parse(EXPRESSION).key(contextOf(late)),
                "a step beyond the 100s window makes the pattern not apply");

        // exactly on the window still matches: 100s after the balance check
        Map<String, Object> justInTime = events(1790147176281L, 1790147177881L, 1790147277881L,
                1790147278881L, "10.122.13.14", "NotMyCard");
        assertNotNull(FuzzySequence.parse(EXPRESSION).key(contextOf(justInTime)));
    }

    @Test
    void theOrderOfTheEventsMatters() {
        // the dispute arrives before the payment: the steps are there, the order is not
        Map<String, Object> outOfOrder = events(1790147176281L, 1790147177881L, 1790147179881L,
                1790147178881L, "10.122.13.14", "NotMyCard");
        assertNull(FuzzySequence.parse(EXPRESSION).key(contextOf(outOfOrder)));

        // a missing event is the same: not applicable, not an empty key
        Map<String, Object> withoutPayment = new LinkedHashMap<>(example());
        withoutPayment.remove("Payment");
        assertNull(FuzzySequence.parse(EXPRESSION).key(contextOf(withoutPayment)));
    }

    @Test
    void aRequiredValueIsACondition() {
        FuzzySequence conditional = FuzzySequence.parse(
                "fl_3_L0(ip3(Login.IP))_[<300s]_P1_[<300s]_D2(Dispute.DisputeReason)=='NotMyCard'");
        Map<String, Object> wrongReason = events(1790147176281L, 1790147177881L, 1790147278881L,
                1790147378881L, "10.122.13.14", "Fraud");
        assertNull(conditional.key(contextOf(wrongReason)), "another reason does not match this pattern");

        Map<String, Object> rightReason = events(1790147176281L, 1790147177881L, 1790147278881L,
                1790147378881L, "10.122.13.14", "NotMyCard");
        assertEquals("10.122.13", conditional.key(contextOf(rightReason)),
                "the reason is a condition here, so it is not part of the key");
    }

    @Test
    void twoAccountsOnOneNetworkAndReasonShareTheLink() {
        FuzzyPattern pattern = new FuzzyPattern(2, "session", EXPRESSION, "", true);
        FuzzyContext first = contextOf(events(1790147176281L, 1790147177881L, 1790147178881L,
                1790147179881L, "10.122.13.14", "NotMyCard"));
        FuzzyContext second = contextOf(events(1790148000000L, 1790148000500L, 1790148000900L,
                1790148001200L, "10.122.13.99", "NotMyCard"));
        FuzzyContext other = contextOf(events(1790147176281L, 1790147177881L, 1790147178881L,
                1790147179881L, "10.130.7.14", "NotMyCard"));

        assertEquals(pattern.linkVertexId(first), pattern.linkVertexId(second),
                "same /24 and same reason: one link node");
        assertFalse(pattern.linkVertexId(first).equals(pattern.linkVertexId(other)),
                "another network is another group");
    }

    @Test
    void anEventListUnderEventsOrAsJsonTextIsReadToo() {
        // a list of named events, the shape an ingest sends
        Map<String, Object> asList = Map.of("events", List.of(
                Map.of("name", "Login", "IP", "10.122.13.14", "EventTime", 1790147176281L),
                Map.of("name", "BalanceCheck", "EventTime", 1790147177881L),
                Map.of("name", "Payment", "EventTime", 1790147178881L),
                Map.of("name", "Dispute", "DisputeReason", "NotMyCard", "EventTime", 1790147179881L)));
        assertEquals("10.122_NotMyCard", FuzzySequence.parse(EXPRESSION).key(contextOf(asList)));

        // the same list kept as one JSON string property of an account
        Map<String, Object> asText = Map.of("eventLog", "{\"events\":["
                + "{\"name\":\"Login\",\"IP\":\"10.122.13.14\",\"EventTime\":1790147176281},"
                + "{\"name\":\"BalanceCheck\",\"EventTime\":1790147177881},"
                + "{\"name\":\"Payment\",\"EventTime\":1790147178881},"
                + "{\"name\":\"Dispute\",\"DisputeReason\":\"NotMyCard\",\"EventTime\":1790147179881}]}");
        assertEquals("10.122_NotMyCard", FuzzySequence.parse(EXPRESSION).key(contextOf(asText)));
    }

    @Test
    void theExplanationNamesTheEventEachStepMatched() {
        List<FuzzyExpression.Step> steps = FuzzySequence.parse(EXPRESSION).explain(contextOf(example()));
        assertEquals(5, steps.size(), "one per step plus the key");
        assertTrue(steps.get(0).value().contains("Login @1790147176281"), steps.get(0).value());
        assertTrue(steps.get(0).value().contains("+0s"));
        assertTrue(steps.get(2).value().contains("Payment"), steps.get(2).value());
        assertEquals("10.122_NotMyCard", steps.get(4).value());
        assertEquals("key", steps.get(4).part());

        // a context the pattern does not apply to explains itself as empty steps
        List<FuzzyExpression.Step> missing = FuzzySequence.parse(EXPRESSION)
                .explain(contextOf(Map.of("Login", Map.of("IP", "10.0.0.1", "EventTime", 1L))));
        assertNull(missing.get(4).value());
    }

    @Test
    void aSequenceThatCannotBeReadSaysWhatIsWrong() {
        FuzzyPatternException unbalanced = assertThrows(FuzzyPatternException.class,
                () -> FuzzySequence.parse("L0(ip2(Login.IP)_[<100s]_D1"));
        assertTrue(unbalanced.getMessage().contains("parentheses"), unbalanced.getMessage());

        FuzzyPatternException oneStep = assertThrows(FuzzyPatternException.class,
                () -> FuzzySequence.parse("fl_2_L0(ip2(Login.IP))"));
        assertTrue(oneStep.getMessage().contains("at least two steps"), oneStep.getMessage());

        FuzzyPatternException wrongId = assertThrows(FuzzyPatternException.class,
                () -> new FuzzyPattern(5, "session", EXPRESSION, "", true));
        assertTrue(wrongId.getMessage().contains("names pattern 2"), wrongId.getMessage());

        FuzzyPatternException badCondition = assertThrows(FuzzyPatternException.class,
                () -> FuzzySequence.parse("L0(ip2(Login.IP))_[<100s]_D1[Dispute.ip = Login.ip]"));
        assertTrue(badCondition.getMessage().contains("=="), badCondition.getMessage());
    }

    // --- the two patterns of the second round: a two-sided window and conditions over the matched events ---

    private static final String AROUND = "fl_3_L0(ip2(Login.ip))_[<100s]_B1_[-100s<time_interval<100s]_P2_"
            + "[<100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip]";

    private static final String MOBILE = "fl_4_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_"
            + "[-100s<time_interval<100s]_P2_[<100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip || "
            + "Dispute.cookie == Payment.Cookie || Dispute.DisputeReason == 'NotMyCard']";

    /**
     * A session with the fields the two patterns read. The balance check and the dispute may carry a cookie,
     * which is what the second alternative of pattern 4 looks at.
     */
    private static Map<String, Object> session(String loginIp, String loginType, String reason,
                                               String disputeIp, String paymentCookie,
                                               String disputeCookie) {
        long start = 1790147176281L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Login", Map.of("IP", loginIp, "Type", loginType, "EventTime", start));
        out.put("BalanceCheck", Map.of("IP", loginIp, "EventTime", start + 1000));
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("IP", loginIp);
        payment.put("Cookie", paymentCookie);
        payment.put("EventTime", start + 500); // before the balance check: the two-sided window allows it
        out.put("Payment", payment);
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("DisputeReason", reason);
        dispute.put("IP", disputeIp);
        dispute.put("Cookie", disputeCookie);
        dispute.put("EventTime", start + 3500);
        out.put("Dispute", dispute);
        return out;
    }

    @Test
    void aTwoSidedWindowLetsThePaymentComeFirst() {
        FuzzyContext context = contextOf(session("10.141.20.30", "Mobile", "NotMyCard",
                "10.141.20.30", "ck_pay", "ck_dispute"));
        assertEquals("10.141_NotMyCard", FuzzySequence.parse(AROUND).key(context),
                "the payment happened 500 ms before the balance check and still fits the window");
        assertNull(FuzzySequence.parse(EXPRESSION).key(context),
                "the forward window of pattern 2 does not allow the payment to come first");

        Map<String, Object> late = session("10.141.20.30", "Mobile", "NotMyCard",
                "10.141.20.30", "ck_pay", "ck_dispute");
        ((Map<String, Object>) late.get("Payment")).put("EventTime", 1790147176281L + 300_000L);
        assertNull(FuzzySequence.parse(AROUND).key(contextOf(late)),
                "five minutes apart is outside the two-sided window too");
    }

    @Test
    void aConditionReadsTheFieldsOfTheMatchedEvents() {
        FuzzySequence pattern = FuzzySequence.parse(AROUND);
        FuzzyContext sameAddress = contextOf(session("10.141.20.30", "Mobile", "NotMyCard",
                "10.141.20.30", "ck_pay", "ck_dispute"));
        FuzzyContext movedAddress = contextOf(session("10.141.20.30", "Mobile", "NotMyCard",
                "10.99.99.99", "ck_pay", "ck_dispute"));

        assertEquals("10.141_NotMyCard", pattern.key(sameAddress));
        assertNull(pattern.key(movedAddress),
                "the dispute came from another address: the condition does not hold");

        List<FuzzyExpression.Step> steps = pattern.explain(sameAddress);
        assertEquals("[Dispute.ip==Login.ip]", steps.get(4).part());
        assertEquals("true", steps.get(4).value());
        assertNull(pattern.explain(movedAddress).get(4).value());
    }

    @Test
    void aStepConditionConstrainsTheEventItFollows() {
        FuzzySequence pattern = FuzzySequence.parse(MOBILE);
        assertNotNull(pattern.key(contextOf(session("10.141.20.30", "Mobile", "NotMyCard",
                "10.141.20.30", "ck_pay", "ck_dispute"))));
        assertNull(pattern.key(contextOf(session("10.141.20.30", "Web", "NotMyCard",
                "10.141.20.30", "ck_pay", "ck_dispute"))),
                "a web login is not what pattern 4 asks for");
    }

    @Test
    void theAlternativesOfAConditionEachMatchOnTheirOwn() {
        FuzzySequence pattern = FuzzySequence.parse(MOBILE);
        // the address agrees, the cookie does not, and the reason is Fraud: the first alternative carries it
        assertNotNull(pattern.key(contextOf(session("10.141.20.30", "Mobile", "Fraud",
                "10.141.20.30", "ck_pay", "ck_dispute"))));
        // the address moved, the cookies agree: the second alternative carries it
        assertEquals("10.141_Fraud", pattern.key(contextOf(session("10.141.20.30", "Mobile", "Fraud",
                "10.99.99.99", "ck_shared", "ck_shared"))));
        // the address moved, the cookies differ, but the reason is NotMyCard: the third alternative carries it
        assertEquals("10.141_NotMyCard", pattern.key(contextOf(session("10.141.20.30", "Mobile", "NotMyCard",
                "10.99.99.99", "ck_pay", "ck_other"))));
        // none of the three: the pattern does not apply
        assertNull(pattern.key(contextOf(session("10.141.20.30", "Mobile", "Fraud",
                "10.99.99.99", "ck_pay", "ck_other"))));
    }

    // --- anyFields: the fields several matched events agree on ---

    private static final String SHARED_FIELDS = "fl_5_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_"
            + "[<50s]_P2_[<100s]_D3(anyFields([Login, Payment, Dispute], 3, false))";

    /**
     * A session whose login, payment and dispute carry the same fields; the caller decides where they agree and
     * how far apart the steps are.
     */
    private static Map<String, Object> sharedFields(String loginIp, String loginType, String disputeIp,
                                                    String payCookie, String disputeCookie,
                                                    long balanceGap, long paymentGap) {
        long start = 1790147176281L;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Login", Map.of("ID", "s", "IP", loginIp, "Type", loginType, "Cookie", payCookie,
                "EventTime", start));
        out.put("BalanceCheck", Map.of("ID", "s", "IP", loginIp, "EventTime", start + balanceGap));
        out.put("Payment", Map.of("ID", "s", "IP", loginIp, "Type", loginType, "Cookie", payCookie,
                "EventTime", start + balanceGap + paymentGap));
        out.put("Dispute", Map.of("ID", "s", "IP", disputeIp, "Type", loginType, "Cookie", disputeCookie,
                "EventTime", start + balanceGap + paymentGap + 1000));
        return out;
    }

    @Test
    void anyFieldsListsTheFieldsEveryEventAgreesOn() {
        FuzzyContext context = FuzzyContext.of(Map.of(
                "Login", Map.of("IP", "10.1.2.3", "Type", "Mobile", "Cookie", "c1"),
                "Payment", Map.of("IP", "10.1.2.3", "Type", "Mobile", "Cookie", "c1"),
                "Dispute", Map.of("IP", "10.1.2.3", "Type", "Mobile", "Cookie", "c1")));

        assertEquals("cookie_ip_type", FuzzyFunctions.applyContext("anyFields", context,
                List.of("Login,Payment,Dispute", "3", "false")), "the names in alphabetical order");
        assertEquals("cookie=c1_ip=10.1.2.3_type=Mobile", FuzzyFunctions.applyContext("anyFields", context,
                List.of("Login,Payment,Dispute", "3", "true")), "with true each name keeps its value");
        assertNull(FuzzyFunctions.applyContext("anyFields", context,
                List.of("Login,Payment,Dispute", "4", "false")), "asking for more than agree makes it not apply");
    }

    @Test
    void anyFieldsNeverCountsTheBookkeepingFields() {
        // the id and the event time are equal across the three, but they say nothing about the session
        FuzzyContext context = FuzzyContext.of(Map.of(
                "Login", Map.of("ID", "s", "EventTime", 1L),
                "Payment", Map.of("ID", "s", "EventTime", 2L),
                "Dispute", Map.of("ID", "s", "EventTime", 3L)));

        assertNull(FuzzyFunctions.applyContext("anyFields", context,
                List.of("Login,Payment,Dispute", "1", "false")),
                "there is no agreed identity field to link on");
    }

    @Test
    void anyFieldsReadsTheEventsTheStepsMatched() {
        FuzzySequence pattern = FuzzySequence.parse(SHARED_FIELDS);

        assertEquals("10.155_cookie_ip_type", pattern.key(contextOf(sharedFields(
                "10.155.30.10", "Mobile", "10.155.30.10", "ck", "ck", 1000, 2000))));

        // the payment is 60 seconds after the balance check: past the [<50s] window
        assertNull(pattern.key(contextOf(sharedFields(
                "10.155.30.10", "Mobile", "10.155.30.10", "ck", "ck", 1000, 60000))));

        // the dispute moved and its cookie differs: only the type agrees, so there is no three-field signature
        assertNull(pattern.key(contextOf(sharedFields(
                "10.155.30.10", "Mobile", "10.99.99.9", "ck", "other", 1000, 2000))));
    }

    @Test
    void anyFieldsParsesAsAListOfEventsAndABoolean() {
        FuzzySequence pattern = FuzzySequence.parse(SHARED_FIELDS);

        assertEquals("anyFields([Login, Payment, Dispute], 3, false)",
                pattern.steps().get(3).valueExpression());
        assertTrue(pattern.fieldsByStep().get("D3(anyFields([Login, Payment, Dispute], 3, false))")
                .containsAll(List.of("Login", "Payment", "Dispute")),
                "the editor is told which events the UDF reads");
        assertTrue(FuzzyFunctions.isContextFunction("anyFields"));
        assertTrue(FuzzyFunctions.isFunction("anyFields"), "the parser accepts it as a function");
    }
}
