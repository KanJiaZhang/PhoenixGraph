package com.pengcheng.phoenixgraph.fuzzy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Link keys, link plans and the pattern registry: what makes two accounts land on one node. */
class FuzzyLinksTest {

    private static final FuzzyContext FRAUD_A = FuzzyContext.of(Map.of(
            "email", "19880101@mail.ru", "phone", "+8613800138000", "ip", "10.20.31.7",
            "username", "zhang1988", "cardNumber", "6217000010001234"));
    private static final FuzzyContext FRAUD_B = FuzzyContext.of(Map.of(
            "email", "19790101@mail.ru", "phone", "+8613900139000", "ip", "10.20.88.4",
            "username", "zhang1979", "cardNumber", "6217000099998888"));
    private static final FuzzyContext HONEST = FuzzyContext.of(Map.of(
            "email", "li.wei@qq.com", "phone", "+8613700137000", "ip", "172.16.5.9",
            "username", "liwei", "cardNumber", "6222020011112222"));

    @Test
    void twoAccountsThatShareFragmentsEndUpOnOneNode() {
        FuzzyPattern pattern = FuzzyDemo.SHARED_HABITS;

        String first = pattern.linkVertexId(FRAUD_A);
        String second = pattern.linkVertexId(FRAUD_B);

        assertEquals(first, second, "the same email provider, country, /16 and name word is the link");
        assertEquals("fl_0_mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, first);
        assertNotEquals(first, pattern.linkVertexId(HONEST), "an unrelated account is on its own node");
    }

    @Test
    void twoDifferentNameWordsOfOneShapeLinkUp() {
        // the point of keeping the formula: zhang1988 and xia1999 are two values of '^[a-z]+', so
        // extracting the word would link the zhangs to each other and leave the xias out
        FuzzyPattern pattern = FuzzyDemo.SHARED_HABITS;
        FuzzyContext xia = FuzzyContext.of(Map.of(
                "email", "19990101@mail.ru", "phone", "+8613800138001", "ip", "10.20.77.9",
                "username", "xia1999"));

        assertEquals(pattern.linkVertexId(FRAUD_A), pattern.linkVertexId(xia),
                "same provider, country, /16 and name shape: one node, whatever the letters are");
    }

    @Test
    void aPatternThatDoesNotApplyLinksNothing() {
        FuzzyPattern pattern = FuzzyDemo.MACHINE_MAIL;

        assertNull(pattern.linkVertexId(FuzzyContext.of(Map.of("ip", "10.20.31.7"))),
                "no card number, no BIN fragment, no link");
        assertNull(pattern.key(FuzzyContext.of(Map.of("email", "someone@mail.ru", "ip", "10.20.31.7",
                "cardNumber", "6217000010001234"))), "an email local part without digits does not apply");
    }

    @Test
    void theLinkOfAPatternIsDeterministicAndReadable() {
        FuzzyPattern pattern = FuzzyPattern.of(7, "ip2(ip)");

        assertEquals("fl_7_10.20", pattern.linkVertexId(FRAUD_A));
        assertEquals("fl_7_10.20", pattern.linkVertexId(FRAUD_A), "the same context always gives the same id");
        assertEquals("pattern_7", pattern.vertexId());
    }

    @Test
    void keysAreSafeForARecordKey() {
        assertEquals("mail.ru_86_10.20", FuzzyKey.of("mail.ru+86 10.20"));
        assertEquals("a_b", FuzzyKey.of("  a   b  "));
        assertEquals("a.b-c_d", FuzzyKey.of("a.b-c_d"));
        assertEquals("a_b", FuzzyKey.of("a\nb"));
        assertTrue(FuzzyKey.looksNormalised(FuzzyKey.of("A/B\\C")));
        assertThrows(FuzzyPatternException.class, () -> FuzzyKey.of("   "));
    }

    @Test
    void aVeryLongKeyBecomesAHashOfItself() {
        String longKey = "x".repeat(500);

        String key = FuzzyKey.of(longKey);

        assertTrue(key.length() <= FuzzyKey.MAX_LENGTH, key.length() + " characters");
        assertTrue(key.contains("~"), key);
        assertEquals(key, FuzzyKey.of(longKey), "hashing must stay deterministic");
        assertNotEquals(key, FuzzyKey.of(longKey + "y"));
    }

    @Test
    void aPlanSaysWhatToWriteForEveryPattern() {
        List<FuzzyLink> links = FuzzyLinks.plan(FuzzyDemo.demoRegistry(), FRAUD_A);

        assertEquals(2, links.size(), "both demo patterns apply to this context");
        assertEquals(0, links.get(0).patternId());
        assertEquals("fl_0_mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, links.get(0).linkVertexId());
        assertEquals(List.of("mail.ru", "_", "86", "_", "10.20", "_", FuzzyDemo.NAME_SHAPE),
                links.get(0).fragments());
        assertTrue(links.get(0).complete());
        assertEquals(1, links.get(1).patternId());
        assertTrue(links.get(1).linkVertexId().startsWith("fl_1_10.20.31_621700_re_"),
                "the machine-made email contributes its shape: " + links.get(1).linkVertexId());
    }

    @Test
    void aDisabledPatternPlansNothing() {
        FuzzyPatternRegistry registry = FuzzyPatternRegistry.of(List.of(FuzzyDemo.SHARED_HABITS.disabled()));

        assertTrue(registry.patterns().isEmpty());
        assertEquals(List.of(), FuzzyLinks.plan(registry, FRAUD_A));
        assertThrows(FuzzyPatternException.class, () -> registry.require(0));
    }

    @Test
    void anUnknownPatternSaysWhatExists() {
        FuzzyPatternRegistry registry = FuzzyDemo.demoRegistry();

        FuzzyPatternException error = assertThrows(FuzzyPatternException.class, () -> registry.require(9));
        assertTrue(error.getMessage().contains("Unknown fuzzy pattern 9"), error.getMessage());
        assertTrue(error.getMessage().contains("0 ("), "the message lists the patterns that exist");
        assertEquals(0, registry.require(0).id());
    }

    @Test
    void aPatternIsValidatedWhenItIsWritten() {
        FuzzyPatternException error = assertThrows(FuzzyPatternException.class,
                () -> new FuzzyPattern(3, "broken", "noSuchThing(email)", "", true));
        assertTrue(error.getMessage().contains("Unknown fuzzy function"), error.getMessage());
        assertThrows(FuzzyPatternException.class, () -> new FuzzyPattern(-1, "x", "ip2(ip)", "", true));
        assertThrows(FuzzyPatternException.class, () -> FuzzyPattern.of(0, "  "));
    }

    @Test
    void aPatternReadsAndWritesAsJson() {
        FuzzyPatterns codec = new FuzzyPatterns();

        String json = codec.writeAll(Map.of("payment-graph", FuzzyDemo.DEMO_PATTERNS));
        List<FuzzyPattern> read = codec.read(json, "payment-graph");

        assertEquals(FuzzyDemo.DEMO_PATTERNS.size(), read.size());
        assertEquals(FuzzyDemo.SHARED_HABITS.expression(), read.get(0).expression());
        assertEquals(FuzzyDemo.MACHINE_MAIL.name(), read.get(1).name());
        assertEquals(FuzzyDemo.SESSION_100S.expression(), read.get(2).expression(),
                "the sequence patterns survive the JSON round trip too");
        assertEquals(FuzzyDemo.SESSION_AROUND_100S.expression(), read.get(3).expression());
        assertEquals(FuzzyDemo.SESSION_MOBILE_DISPUTE.expression(), read.get(4).expression(),
                "the conditions survive the JSON round trip");
        assertEquals(FuzzyDemo.SESSION_SHARED_FIELDS.expression(), read.get(5).expression(),
                "the anyFields UDF survives it too");
        assertEquals(List.of(), codec.read(json, "another-graph"));
    }

    @Test
    void aBrokenPatternDocumentSaysWhichPatternIsBroken() {
        FuzzyPatterns codec = new FuzzyPatterns();

        FuzzyPatternException broken = assertThrows(FuzzyPatternException.class,
                () -> codec.read("{ \"g\": [ { \"id\": 0, \"expression\": \"nope(email)\" } ] }", "g"));
        assertTrue(broken.getMessage().contains("Pattern 0"), broken.getMessage());
        assertTrue(broken.getMessage().contains("Unknown fuzzy function"), broken.getMessage());
        assertThrows(FuzzyPatternException.class, () -> codec.read("{ \"g\": [ { \"expression\": \"ip2(ip)\" } ] }", "g"));
        assertThrows(FuzzyPatternException.class, () -> codec.read("not json", "g"));
    }

    @Test
    void patternsAreEditedInAFileAndSeenByTheNextQuery(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fuzzy-patterns.json");
        FileFuzzyPatterns patterns = new FileFuzzyPatterns(file, "payment-graph");
        assertTrue(patterns.patterns().isEmpty(), "a graph starts without patterns");

        patterns.save(FuzzyDemo.SHARED_HABITS);
        assertEquals(1, patterns.patterns().size());
        assertTrue(Files.exists(file), "the pattern was written to the file");

        patterns.save(FuzzyDemo.MACHINE_MAIL);
        assertEquals(2, patterns.patterns().size(), "the second pattern was added next to the first");
        assertEquals("fl_0_mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, patterns.require(0).linkVertexId(FRAUD_A));

        // a pattern added to the file by hand is picked up, and one removed disappears
        Files.writeString(file, new FuzzyPatterns().writeAll(Map.of("payment-graph",
                List.of(new FuzzyPattern(5, "manual", "ip2(ip)", "added by hand", true)))));
        FileFuzzyPatterns reopened = new FileFuzzyPatterns(file, "payment-graph");
        assertEquals(1, reopened.patterns().size());
        assertEquals(5, reopened.patterns().get(0).id());

        reopened.remove(5);
        assertTrue(reopened.patterns().isEmpty());
        assertEquals(List.of(), new FileFuzzyPatterns(file, "payment-graph").patterns());
    }

    @Test
    void aPatternCanBeDisabledWithoutDeletingIt(@TempDir Path dir) {
        FileFuzzyPatterns patterns = new FileFuzzyPatterns(dir.resolve("p.json"), "g");
        patterns.save(new FuzzyPattern(0, "off", "ip2(ip)", "", false));

        assertTrue(patterns.patterns().isEmpty(), "a disabled pattern is not used by a query");
        assertEquals(1, patterns.allPatterns().size(), "but the editor still shows it");
        assertFalse(patterns.allPatterns().get(0).enabled());
    }

    @Test
    void aPatternCarriesTheExampleThatShowsItWorking() {
        FuzzyPattern pattern = FuzzyDemo.SHARED_HABITS;

        assertFalse(pattern.example().isEmpty(), "the shipped patterns come with a context to try");
        FuzzyLink link = pattern.exampleLink();
        assertEquals(0, link.patternId());
        assertEquals("mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, link.key());
        assertEquals("fl_0_mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, link.linkVertexId());
        assertEquals(".out('FuzzyLinkedTo').outE('Payment').otherV()", pattern.demoStepsOrDefault(),
                "the demo walks from the ring to where the money went");
        assertEquals("pattern -> ring -> payments -> receiver", pattern.demoTitle());
        assertEquals(".out('FuzzyLinkedTo')", FuzzyDemo.MACHINE_MAIL.demoStepsOrDefault());
    }

    @Test
    void theDemoPatternsUseARegularExpressionThatKeepsItsBackslashes() {
        // both shipped patterns keep the formula: the letters of the user name and the digits the
        // email local part starts with are shapes, so every value of that shape shares the fragment
        assertTrue(FuzzyDemo.SHARED_HABITS.expression().contains("regex(username, '^[a-z]+')"),
                "the name is reduced to the shape of its regular expression");
        assertTrue(FuzzyDemo.MACHINE_MAIL.expression().contains("regex(emailName(email), '^\\d{4}')"),
                "the digits are a backslash class, so it has to survive as written");

        assertEquals("mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, FuzzyDemo.SHARED_HABITS.key(FuzzyDemo.demoContext()));
        assertTrue(FuzzyDemo.MACHINE_MAIL.key(FuzzyDemo.demoContext()).startsWith("10.20.31_621700_re_"),
                FuzzyDemo.MACHINE_MAIL.key(FuzzyDemo.demoContext()));
    }

    @Test
    void aShapeLinksValuesThatDifferButLookAlike() {
        // the point of putting the shape in the key: zhang1988 and xia1999 are different accounts that
        // share the way their user name is built, and nothing else
        FuzzyPattern shape = FuzzyPattern.of(9,
                "ip2(ip)+'_'+regex(username, '^[a-z]+\\d+$')");
        FuzzyPattern value = FuzzyPattern.of(9,
                "ip2(ip)+'_'+regexValue(username, '^([a-z]+)\\d+$')");
        FuzzyContext zhang = FuzzyContext.of(Map.of("ip", "10.20.1.1", "username", "zhang1988"));
        FuzzyContext xia = FuzzyContext.of(Map.of("ip", "10.20.2.2", "username", "xia1999"));

        assertEquals(shape.key(zhang), shape.key(xia),
                "the shape is what they share, so a shape pattern links them");
        assertNotEquals(value.key(zhang), value.key(xia),
                "a value pattern keeps them apart, which is what it is for");
        assertNull(FuzzyPattern.of(9, "regex(username, '^\\d+$')").key(zhang),
                "a value of another shape does not satisfy the pattern at all");
    }

    @Test
    void anExampleAndADemoSurviveTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("p.json");
        FileFuzzyPatterns patterns = new FileFuzzyPatterns(file, "g");
        patterns.save(FuzzyDemo.SHARED_HABITS);

        FuzzyPattern read = new FileFuzzyPatterns(file, "g").require(0);

        assertEquals(FuzzyDemo.exampleContext(), read.example(), "the context comes back as it was written");
        assertEquals("pattern -> ring -> payments -> receiver", read.demoTitle());
        assertEquals(".out('FuzzyLinkedTo').outE('Payment').otherV()", read.demoSteps());
        assertEquals("mail.ru_86_10.20_" + FuzzyDemo.NAME_SHAPE, read.exampleLink().key());
    }

    @Test
    void aSequenceExampleSurvivesTheFileWithItsEventList(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("Login", Map.of("IP", "10.141.20.30", "Type", "Mobile", "EventTime", 1790147176281L));
        example.put("BalanceCheck", Map.of("IP", "10.141.20.30", "EventTime", 1790147177281L));
        example.put("Payment", Map.of("IP", "10.141.20.30", "EventTime", 1790147176781L));
        example.put("Dispute", Map.of("DisputeReason", "NotMyCard", "IP", "10.141.20.30",
                "EventTime", 1790147179781L));
        new FileFuzzyPatterns(file, "g").save(FuzzyDemo.SESSION_AROUND_100S.withExample(example));

        FuzzyPattern read = new FileFuzzyPatterns(file, "g").require(3);
        FuzzyContext context = FuzzyContext.of(read.example());

        assertEquals("10.141_NotMyCard", read.parsedSequence().key(context),
                "the event list came back as events, not as empty strings");
        assertEquals("fl_3_10.141_notmycard", read.linkVertexId(context));
    }

    @Test
    void aPatternWithoutAnExampleSaysNothingRatherThanGuessing() {
        FuzzyPattern plain = FuzzyPattern.of(4, "ip2(ip)");

        assertTrue(plain.example().isEmpty());
        assertNull(plain.exampleLink());
        assertEquals(".out('FuzzyLinkedTo')", plain.demoStepsOrDefault(), "a default demo step still works");
    }

    @Test
    void signaturesDescribeEveryFunctionForTheEditor() {
        List<FuzzyFunctions.Signature> signatures = FuzzyFunctions.signatures();

        assertTrue(signatures.size() >= 15, "the library is documented: " + signatures.size());
        for (FuzzyFunctions.Signature signature : signatures) {
            assertTrue(signature.usage().startsWith(signature.name()), signature.usage());
            assertFalse(signature.description().isBlank(), signature.name());
        }
        assertTrue(signatures.stream().anyMatch(s -> s.name().equals("regex")));
        assertTrue(signatures.stream().anyMatch(s -> s.name().equals("cardBin")));
        assertTrue(signatures.stream().anyMatch(s -> s.name().equals("anyFields")),
                "the context UDF is documented for the editor as well: " + signatures);
    }
}
