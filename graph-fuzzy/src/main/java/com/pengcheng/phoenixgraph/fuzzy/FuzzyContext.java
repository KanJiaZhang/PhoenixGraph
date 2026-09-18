package com.pengcheng.phoenixgraph.fuzzy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The values a fuzzy pattern reads, addressed by name.
 *
 * <p>In a risk engine the same facts arrive under different names depending on where they came
 * from - a sign-up form says {@code email}, a payment message says {@code emailAddress}, a device
 * fingerprint says {@code ip} or {@code ipAddress}. A lookup is therefore case-insensitive and
 * ignores underscores, so {@code email_address}, {@code emailAddress} and {@code EMAIL} are the
 * same field, and a pattern does not break because a source spelled it differently.
 *
 * <p>Values are kept as text: a pattern is about fragments of strings (a domain, a BIN, the first
 * two octets), and numbers (a phone number, a card number, an IP) lose their leading zeros or
 * their exact spelling on the way through a numeric type.
 */
public final class FuzzyContext {

    private final Map<String, Object> values;

    private FuzzyContext(Map<String, Object> values) {
        this.values = values;
    }

    /** A context over the given values ({@code null} values are ignored). */
    public static FuzzyContext of(Map<String, ?> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new FuzzyContext(out);
    }

    /** An empty context. */
    public static FuzzyContext empty() {
        return new FuzzyContext(Map.of());
    }

    /** This context with the given values added (the ones added win). */
    public FuzzyContext with(Map<String, ?> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(values);
        if (extra != null) {
            for (Map.Entry<String, ?> entry : extra.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new FuzzyContext(merged);
    }

    /**
     * The value of a field as text, or {@code null} when it is not there.
     *
     * <p>A lookup tries the exact name first (so a context that distinguishes {@code ip} from
     * {@code ipAddress} keeps working), then the normalised name, then the aliases below.
     */
    public String value(String field) {
        if (field == null) {
            return null;
        }
        Object direct = values.get(field);
        if (direct != null) {
            return text(direct);
        }
        String wanted = normalise(field);
        String wantedAlias = ALIASES.get(wanted);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String candidate = normalise(entry.getKey());
            if (candidate.equals(wanted) || candidate.equals(wantedAlias)
                    || ALIASES.get(candidate) != null && ALIASES.get(candidate).equals(wanted)) {
                return text(entry.getValue());
            }
        }
        return null;
    }

    /** Whether the field is present with a non-blank value. */
    public boolean has(String field) {
        String value = value(field);
        return value != null && !value.isBlank();
    }

    /** The names this context knows, in the order they were added. */
    public Iterable<String> fieldNames() {
        return values.keySet();
    }

    /** The raw values, for a caller that has to pass them on (a write, a log line). */
    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }

    /** The text of a value: enums and numbers are spelled the way a pattern expects them. */
    private static String text(Object value) {
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Double number && number == Math.floor(number) && !number.isInfinite()) {
            // 13800138000.0 is a phone number written by a JSON reader, not a fraction
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value);
    }

    /** Case-insensitive, underscore-insensitive key of a field name. */
    private static String normalise(String field) {
        return field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * Names the same fact is seen under. A pattern says {@code ip2(ip)} and keeps working when the
     * context calls it {@code ipAddress}, which is what an ingest from a device feed tends to do.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ip", "ipaddress"), Map.entry("ipaddress", "ip"),
            Map.entry("email", "emailaddress"), Map.entry("emailaddress", "email"),
            Map.entry("phone", "phonenumber"), Map.entry("phonenumber", "phone"),
            Map.entry("mobile", "phonenumber"), Map.entry("msisdn", "phonenumber"),
            Map.entry("username", "loginname"), Map.entry("loginname", "username"),
            Map.entry("cardnumber", "card"), Map.entry("card", "cardnumber"),
            Map.entry("cardno", "cardnumber"), Map.entry("pannumber", "cardnumber"),
            Map.entry("name", "fullname"), Map.entry("fullname", "name"));
}
