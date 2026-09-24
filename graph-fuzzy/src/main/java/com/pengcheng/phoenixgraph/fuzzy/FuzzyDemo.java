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
     * Pattern 3 - a session keyed on the /16 of the login address and the dispute reason, where the
     * payment and the balance check may happen in either order within the same hundred seconds, and the
     * dispute has to come from the address the login came from. Two accounts whose sessions look alike are
     * on one node; the {@code [Dispute.ip==Login.ip]} condition is what tells a session that moved network
     * halfway through from one that did not.
     */
    public static final FuzzyPattern SESSION_AROUND_100S = withDemo(new FuzzyPattern(3,
            "Login -> BalanceCheck <-> Payment -> Dispute, the dispute from the login address",
            "fl_3_L0(ip2(Login.ip))_[<100s]_B1_[-100s<time_interval<100s]_P2_[<100s]_D3(Dispute.DisputeReason)"
                    + "[Dispute.ip==Login.ip]",
            "A login, a balance check, a payment and a dispute, each within a hundred seconds of the step "
                    + "before it; the balance check and the payment may happen in either order, and the "
                    + "dispute has to come from the address the login came from. The key is the /16 of the "
                    + "login address and the dispute reason.",
            true));

    /**
     * Pattern 4 - the same session, but only when the login was made from a phone and one of three things
     * ties the dispute to the session: the dispute came from the login address, or its cookie is the
     * payment's cookie, or the reason is NotMyCard. This is what a condition over the matched events buys:
     * the alternatives are written in one bracket, and each of them links a different set of accounts.
     */
    public static final FuzzyPattern SESSION_MOBILE_DISPUTE = withDemo(new FuzzyPattern(4,
            "mobile Login session, dispute tied by address, cookie or reason",
            "fl_4_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_[-100s<time_interval<100s]_P2_"
                    + "[<100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip || "
                    + "Dispute.cookie == Payment.Cookie || Dispute.DisputeReason == 'NotMyCard']",
            "The same session as pattern 3, but only when the login was from a mobile device and the "
                    + "dispute is tied to it by the address, the cookie it shares with the payment, or a "
                    + "NotMyCard reason. A session matches when any one of the three holds.",
            true));

    /**
     * Pattern 5 - the signature a session carries without any one step owning it: the fields the login, the
     * payment and the dispute all agree on. The step value is a UDF, {@code anyFields([...], 3, false)}, which
     * finds the fields present in every listed event with the same value and needs at least three of them; the
     * fragment is their names in alphabetical order, so two sessions that agree on the same set of fields land
     * on one node. With the last argument {@code true} the value each field agreed on is kept in the fragment.
     * Bookkeeping fields (id, event time, event name) never count.
     */
    public static final FuzzyPattern SESSION_SHARED_FIELDS = withDemo(new FuzzyPattern(5,
            "mobile session whose login, payment and dispute agree on three fields",
            "fl_5_L0(ip2(Login.ip))[Login.type=='Mobile']_[<100s]_B1_[<50s]_P2_[<100s]_"
                    + "D3(anyFields([Login, Payment, Dispute], 3, false))",
            "A mobile login, a balance check, a payment and a dispute (each within 100s, the payment within "
                    + "50s of the balance check), where the login, the payment and the dispute share at least "
                    + "three field values. The key is the /16 of the login address and the names of those "
                    + "fields (cookie_ip_type), which is a signature no single event carries.",
            true));

    public static final List<FuzzyPattern> DEMO_PATTERNS =
            List.of(SHARED_HABITS, MACHINE_MAIL, SESSION_100S, SESSION_AROUND_100S, SESSION_MOBILE_DISPUTE,
                    SESSION_SHARED_FIELDS);

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
