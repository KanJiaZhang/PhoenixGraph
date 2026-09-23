package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two patterns the demo graph ships with, and the context a caller fills in.
 *
 * <p>They are the shape a risk analyst writes by hand: fragments that survive an attempt to hide,
 * joined in the order that reads best. {@link #DEMO_PATTERNS} is what the builder page loads as an
 * example, and {@link #demoContext()} is a context both of them apply to.
 */
public final class FuzzyDemo {

    /**
     * Pattern 0 - the "open a new account, keep the habits" pattern: the same email provider, the
     * same country, the same network and a user name whose leading letters have the same shape.
     *
     * <p>The regular expression contributes <b>its own formula</b> to the key (not what it matched),
     * so {@code zhang1988} and {@code xia1999} - two values of that shape - are on one node. Extracting
     * the word instead would link the zhangs to each other and leave the xias out, which is not what
     * "a name written as letters" asks for.
     */
    public static final FuzzyPattern SHARED_HABITS = withDemo(new FuzzyPattern(0,
            "email domain + country + ip/16 + name shape",
            "emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+regex(username, '^[a-z]+')",
            "Accounts that share an email provider, a country, a /16 network and a user name of the "
                    + "same shape (letters before the digits).",
            true));

    /**
     * Pattern 1 - the "same address block, machine-made email" pattern: a /24 network, one card BIN,
     * and an email local part of the machine-made shape. The regular expression contributes its own
     * shape to the key, so every account on that network with that BIN whose address has that shape
     * is on one node - which is what asking for a shape is for.
     */
    public static final FuzzyPattern MACHINE_MAIL = withDemo(new FuzzyPattern(1,
            "ip/24 + machine-made email + card BIN",
            "ip3(ip)+'_'+cardBin(cardNumber)+'_'+regex(emailName(email), '^\\d{4}')",
            "Accounts whose email local part is four digits, on the same /24 network and with the "
                    + "same card BIN.",
            true));

    /** The patterns the demo graphs start with. */
    /**
     * Pattern 2 - the session: a login, a balance check, a payment and a dispute, in that order, each
     * within a hundred seconds of the step before it. The key is the /16 of the login address and the
     * dispute reason, so two accounts whose sessions look alike are on one node.
     */
    public static final FuzzyPattern SESSION_100S = withDemo(new FuzzyPattern(2,
            "Login -> BalanceCheck -> Payment -> Dispute, each within 100s",
            "fl_2_L0(ip2(Login.IP))_[<100s]_B1_[<100s]_P2_[<100s]_D3(Dispute.DisputeReason)",
            "One account's event list, read in time order: a Login, a BalanceCheck, a Payment and a "
                    + "Dispute, all four present, the steps at most 100 seconds apart. The key is the /16 "
                    + "of the login address and the dispute reason.",
            true));

    /**
     * Pattern 3 - the same idea over a wider window, keyed on the /24, with the reason as a condition
     * rather than as part of the key (every match of it is a NotMyCard dispute by construction).
     */
    public static final FuzzyPattern SESSION_300S_NOT_MY_CARD = withDemo(new FuzzyPattern(3,
            "Login then a disputed payment on the same /24, within 5 minutes",
            "fl_3_L0(ip3(Login.IP))_[<300s]_P1_[<300s]_D2(Dispute.DisputeReason)=='NotMyCard'",
            "A login followed by a payment and a dispute within five minutes each, only when the reason "
                    + "is NotMyCard. Accounts that share the network are linked.",
            true));

    public static final List<FuzzyPattern> DEMO_PATTERNS =
            List.of(SHARED_HABITS, MACHINE_MAIL, SESSION_100S, SESSION_300S_NOT_MY_CARD);

    /**
     * The fragment the first demo pattern's regular expression contributes: the shape of
     * {@code ^[a-z]+}, so any user name written as letters shares it.
     */
    public static final String NAME_SHAPE = FuzzyFunctions.shape("^[a-z]+");

    /** A context both demo patterns apply to. */
    public static FuzzyContext demoContext() {
        return FuzzyContext.of(exampleContext());
    }

    /** The context the demo patterns carry, as the values an editor shows. */
    public static Map<String, Object> exampleContext() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", "19880101@mail.ru");
        values.put("phone", "+8613800138000");
        values.put("ip", "10.20.31.7");
        values.put("username", "zhang1988");
        values.put("cardNumber", "6217000010001234");
        return values;
    }

    /**
     * Everything a UI button needs to run the demo of a pattern: what it shows, the context to use
     * and the steps to walk after the link node.
     */
    public static FuzzyPattern withDemo(FuzzyPattern pattern) {
        Map<String, Object> context = exampleContext();
        String steps = pattern.id() == 0
                // the first pattern walks all the way: the ring, its outgoing payments and where
                // they went - which is the question a risk analyst actually asks
                ? ".out('" + FuzzySchema.LINK_EDGE + "').outE('Payment').otherV()"
                : ".out('" + FuzzySchema.LINK_EDGE + "')";
        String title = pattern.id() == 0
                ? "pattern -> ring -> payments -> receiver"
                : "pattern -> the accounts that share the key";
        return pattern.withDemo(title, steps, context);
    }

    /** The context of one of the demo accounts, so a caller can link it the way the loader did. */
    public static FuzzyContext contextOf(String username, String email, String phone, String ip,
                                         String cardNumber) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("username", username);
        values.put("email", email);
        values.put("phone", phone);
        values.put("ip", ip);
        values.put("cardNumber", cardNumber);
        return FuzzyContext.of(values);
    }

    /** The demo patterns as a list a caller can hand to a registry. */
    public static FuzzyPatternRegistry demoRegistry() {
        return FuzzyPatternRegistry.of(DEMO_PATTERNS);
    }

    /** The pattern ids of the demo, for a caller that has to clean them up. */
    public static List<Integer> demoIds() {
        List<Integer> ids = new ArrayList<>();
        for (FuzzyPattern pattern : DEMO_PATTERNS) {
            ids.add(pattern.id());
        }
        return ids;
    }

    private FuzzyDemo() {
    }
}
