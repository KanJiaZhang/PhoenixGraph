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
     * same country, the same network and a user name that is a word followed by digits.
     */
    public static final FuzzyPattern SHARED_HABITS = new FuzzyPattern(0, "email domain + country + ip/16 + name word",
            "emailDomain(email)+'_'+countryCode(phone)+'_'+ip2(ip)+'_'+alphaPrefix(username)",
            "Accounts that share an email provider, a country, a /16 network and a name prefix.",
            true);

    /**
     * Pattern 1 - the "same address block, machine-made email" pattern: a /24 network, the same
     * card BIN, and an email local part that starts with four digits (its first group is the
     * registration year).
     */
    public static final FuzzyPattern MACHINE_MAIL = new FuzzyPattern(1, "ip/24 + email digits + card BIN",
            "ip3(ip)+'_'+cardBin(cardNumber)+'_'+regex(emailName(email), '^([0-9]{4})')",
            "Accounts whose email local part is digits only, on the same /24 network and with the "
                    + "same card BIN.",
            true);

    /** The patterns the demo graphs start with. */
    public static final List<FuzzyPattern> DEMO_PATTERNS = List.of(SHARED_HABITS, MACHINE_MAIL);

    /** A context both demo patterns apply to. */
    public static FuzzyContext demoContext() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", "19880101@mail.ru");
        values.put("phone", "+8613800138000");
        values.put("ip", "10.20.31.7");
        values.put("username", "zhang1988");
        values.put("cardNumber", "6217000010001234");
        return FuzzyContext.of(values);
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
