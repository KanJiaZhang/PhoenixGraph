package com.pengcheng.phoenixgraph.fuzzy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pattern language: the fragments it produces and the keys two tricks cannot escape. */
class FuzzyExpressionTest {

    private static FuzzyContext context(Map<String, ?> values) {
        return FuzzyContext.of(values);
    }

    private static final FuzzyContext ZHANG = context(Map.of(
            "email", "Zhang1988@mail.ru",
            "phone", "+8613800138000",
            "ip", "10.20.31.7",
            "username", "zhang1988",
            "cardNumber", "6217 0000 1000 1234"));

    @Test
    void everyPartOfAPatternBecomesAFragment() {
        FuzzyExpression expression = FuzzyExpression.parse(
                "emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+alphaPrefix(username)");

        assertEquals(List.of("mail.ru", "_", "86", "_", "10.20", "_", "zhang"),
                expression.fragments(ZHANG));
        assertEquals("mail.ru_86_10.20_zhang", expression.key(ZHANG));
        assertEquals(List.of("email", "phone", "ip", "username"), expression.fields());
    }

    @Test
    void aPartThatHasNoValueMakesThePatternNotApply() {
        FuzzyExpression expression = FuzzyExpression.parse("emailDomain(email)+'_'+ip2(ip)");

        assertNull(expression.key(context(Map.of("ip", "10.20.31.7"))),
                "an account without an email is not linked by a pattern that needs the domain");
        assertEquals(Arrays.asList(null, "_", "10.20"),
                expression.fragments(context(Map.of("ip", "10.20.31.7"))));
    }

    @Test
    void optKeepsAPartThatMayBeMissing() {
        FuzzyExpression expression = FuzzyExpression.parse("opt(emailDomain(email))+'_'+ip2(ip)");

        assertEquals("*_10.20", expression.key(context(Map.of("ip", "10.20.31.7"))));
        assertEquals("mail.ru_10.20", expression.key(ZHANG));
    }

    @Test
    void functionsNest() {
        FuzzyExpression expression = FuzzyExpression.parse(
                "upper(first(emailDomain(email), 4))+'_'+hash(username, 8)");

        String key = expression.key(ZHANG);
        assertTrue(key.startsWith("MAIL_"), key);
        assertEquals(5 + 8, key.length(), key);
        assertEquals(key, expression.key(ZHANG), "the same context gives the same key");
    }

    @Test
    void regexTakesTheFirstGroupWhenThePatternHasOne() {
        FuzzyExpression expression = FuzzyExpression.parse("regex(username, '([a-z]+)[0-9]+')");

        assertEquals("zhang", expression.key(ZHANG));
        assertNull(expression.key(context(Map.of("username", "1988"))),
                "a value the pattern does not match contributes nothing");
    }

    @Test
    void aPatternOverAMissingFieldDoesNotApply() {
        FuzzyExpression expression = FuzzyExpression.parse("cardBin(cardNumber)");

        assertEquals("621700", expression.key(ZHANG), "spaces in a card number are not part of the BIN");
        assertNull(expression.key(context(Map.of())));
        assertNull(expression.key(context(Map.of("cardNumber", "not-a-card"))));
    }

    @Test
    void ipFragmentsIgnorePortsAndCidrSuffixes() {
        assertEquals("10.20", FuzzyExpression.parse("ip2(ip)").key(context(Map.of("ip", "10.20.31.7:8080"))));
        assertEquals("10.20.31", FuzzyExpression.parse("ip3(ip)").key(context(Map.of("ip", "10.20.31.7/24"))));
        assertEquals("10.20.31.7", FuzzyExpression.parse("ip4(ip)").key(context(Map.of("ip", "10.20.31.7"))));
        assertNull(FuzzyExpression.parse("ip3(ip)").key(context(Map.of("ip", "10.20"))));
    }

    @Test
    void phoneFragmentsKnowTheCountryCode() {
        assertEquals("86", FuzzyExpression.parse("countryCode(phone)").key(context(Map.of("phone", "+8613800138000"))));
        assertEquals("86", FuzzyExpression.parse("countryCode(phone)").key(context(Map.of("phone", "008613800138000"))));
        assertEquals("1380", FuzzyExpression.parse("phonePrefix(phone)").key(context(Map.of("phone", "+8613800138000"))));
        assertEquals("852", FuzzyExpression.parse("countryCode(phone)").key(context(Map.of("phone", "+85298765432"))),
                "the longest known code wins");
        assertNull(FuzzyExpression.parse("countryCode(phone)").key(context(Map.of("phone", "13800138000"))),
                "a number without a country prefix does not say where it is from");
    }

    @Test
    void aPatternThatCannotBeReadSaysWhy() {
        FuzzyPatternException unknown = assertThrows(FuzzyPatternException.class,
                () -> FuzzyExpression.parse("noSuchFunction(email)"));
        assertTrue(unknown.getMessage().contains("Unknown fuzzy function"), unknown.getMessage());

        FuzzyPatternException missingPlus = assertThrows(FuzzyPatternException.class,
                () -> FuzzyExpression.parse("ip2(ip) ip3(ip)"));
        assertTrue(missingPlus.getMessage().contains("'+'"), missingPlus.getMessage());

        FuzzyPatternException dangling = assertThrows(FuzzyPatternException.class,
                () -> FuzzyExpression.parse("ip2(ip)+"));
        assertTrue(dangling.getMessage().contains("ends with"), dangling.getMessage());

        FuzzyPatternException blank = assertThrows(FuzzyPatternException.class,
                () -> FuzzyExpression.parse("  "));
        assertTrue(blank.getMessage().contains("must not be blank"), blank.getMessage());

        FuzzyPatternException unclosed = assertThrows(FuzzyPatternException.class,
                () -> FuzzyExpression.parse("ip2(ip"));
        assertTrue(unclosed.getMessage().contains("not complete")
                || unclosed.getMessage().contains("','"), unclosed.getMessage());

        assertFalse(FuzzyExpression.isValid("ip2(ip)+'_'+"));
        assertTrue(FuzzyExpression.isValid("ip2(ip)"));
    }

    @Test
    void explainShowsWhatEachPartContributed() {
        FuzzyPattern pattern = FuzzyPattern.of(0,
                "emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+alphaPrefix(username)");

        List<FuzzyExpression.Step> steps = pattern.explain(ZHANG);

        assertEquals(8, steps.size(), "four calls, three separators and the key");
        assertEquals("mail.ru", steps.get(0).value());
        assertEquals("_", steps.get(1).value());
        assertEquals("mail.ru_86_10.20_zhang", steps.get(steps.size() - 1).value());
        assertEquals("key", steps.get(steps.size() - 1).part());
    }

    @Test
    void aFieldIsFoundWhateverASourceCallsIt() {
        FuzzyContext context = context(Map.of(
                "email_address", "a@b.com",
                "IPAddress", "10.20.31.7",
                "PHONE", "+8613800138000"));

        assertEquals("b.com", FuzzyExpression.parse("emailDomain(emailAddress)").key(context));
        assertEquals("10.20", FuzzyExpression.parse("ip2(ip)").key(context));
        assertEquals("86", FuzzyExpression.parse("countryCode(phone)").key(context));
    }

    @Test
    void aNumberThatIsAWholeNumberIsSpelledWithoutTheFraction() {
        FuzzyContext context = context(Map.of("phone", 8613800138000.0, "ip", "10.20.31.7"));

        assertEquals("8613800138000", context.value("phone"),
                "a phone number that went through a JSON number keeps its digits");
        assertEquals("10.20", FuzzyExpression.parse("ip2(ip)").key(context));
        assertNull(FuzzyExpression.parse("countryCode(phone)").key(context),
                "a number without an international prefix still does not name a country");
        assertNotNull(FuzzyExpression.parse("digits(phone, 4)").key(context));
    }

    @Test
    void theDemoPatternsApplyToTheDemoContext() {
        assertEquals("mail.ru_86_10.20_zhang", FuzzyDemo.SHARED_HABITS.key(FuzzyDemo.demoContext()));
        assertEquals("10.20.31_621700_1988", FuzzyDemo.MACHINE_MAIL.key(FuzzyDemo.demoContext()));
    }
}
