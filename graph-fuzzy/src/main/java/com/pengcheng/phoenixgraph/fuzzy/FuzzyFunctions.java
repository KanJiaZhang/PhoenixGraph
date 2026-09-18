package com.pengcheng.phoenixgraph.fuzzy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The functions a fuzzy pattern is built from.
 *
 * <p>Every function turns a value into a fragment that survives a fraudster's cosmetics: an email
 * address is reduced to its domain, an IP to its first octets, a card number to its BIN, a phone
 * number to its country code and prefix, a user name to the letters it starts with. A fragment
 * is {@code null} when the value is missing or does not have the expected shape - a pattern then
 * does not apply, rather than linking on an empty fragment (which would merge unrelated records).
 * {@code opt(...)} is how a pattern says "this part may be missing".
 *
 * <p>Functions nest: {@code lower(emailDomain(email))}, {@code opt(regex(username, '^[a-z]+'))}.
 */
public final class FuzzyFunctions {

    /** What a function does, for the pattern editor's help. */
    public record Signature(String name, String usage, String description) {
    }

    /** A function: the value of its first argument plus the context it may read more of. */
    @FunctionalInterface
    private interface Function {
        String apply(String value, List<String> args);
    }

    private record Entry(String usage, String description, int minArgs, Function function) {
    }

    private static final Map<String, Entry> FUNCTIONS = new LinkedHashMap<>();

    private static void register(String name, String usage, int minArgs, String description,
                                Function function) {
        FUNCTIONS.put(name, new Entry(usage, description, minArgs, function));
    }

    static {
        register("raw", "raw(field)", 0, "The value as it is, trimmed",
                (value, args) -> trimmed(value));
        register("lower", "lower(field)", 0, "Lower case",
                (value, args) -> trimmed(value) == null ? null : trimmed(value).toLowerCase(Locale.ROOT));
        register("upper", "upper(field)", 0, "Upper case",
                (value, args) -> trimmed(value) == null ? null : trimmed(value).toUpperCase(Locale.ROOT));
        register("trim", "trim(field)", 0, "The value without surrounding spaces",
                (value, args) -> trimmed(value));
        register("digits", "digits(field[, n])", 0, "Only the digits, the first n of them",
                (value, args) -> first(digits(value), count(args, 1, 0)));
        register("first", "first(field, n)", 1, "The first n characters",
                (value, args) -> first(trimmed(value), count(args, 1, 1)));
        register("last", "last(field, n)", 1, "The last n characters",
                (value, args) -> last(trimmed(value), count(args, 1, 1)));
        register("alphaPrefix", "alphaPrefix(field)", 0, "The leading letters (zhang123 -> zhang)",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    int end = 0;
                    while (end < text.length() && Character.isLetter(text.charAt(end))) {
                        end++;
                    }
                    return end == 0 ? null : text.substring(0, end).toLowerCase(Locale.ROOT);
                });
        register("digitSuffix", "digitSuffix(field)", 0, "The trailing digits (zhang123 -> 123)",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    int start = text.length();
                    while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
                        start--;
                    }
                    return start == text.length() ? null : text.substring(start);
                });
        register("regex", "regex(field, 'pattern')", 1,
                "What the pattern matches: its first group if it has one, else the whole match",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    Matcher matcher = compile(args.get(1), "regex").matcher(text);
                    if (!matcher.find()) {
                        return null;
                    }
                    String matched = matcher.groupCount() >= 1 && matcher.group(1) != null
                            ? matcher.group(1) : matcher.group();
                    return matched.isBlank() ? null : matched.toLowerCase(Locale.ROOT);
                });
        register("emailDomain", "emailDomain(field)", 0, "The domain of an email address (mail.ru)",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    int at = text.lastIndexOf('@');
                    return at < 0 || at == text.length() - 1
                            ? null : text.substring(at + 1).toLowerCase(Locale.ROOT);
                });
        register("emailName", "emailName(field)", 0, "The part before the @ of an email address",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    int at = text.indexOf('@');
                    return at <= 0 ? null : text.substring(0, at).toLowerCase(Locale.ROOT);
                });
        register("ip2", "ip2(field)", 0, "The first two octets of an address (10.20.30.40 -> 10.20)",
                (value, args) -> octets(value, 2));
        register("ip3", "ip3(field)", 0, "The first three octets of an address",
                (value, args) -> octets(value, 3));
        register("ip4", "ip4(field)", 0, "All four octets of an address (the whole address)",
                (value, args) -> octets(value, 4));
        register("countryCode", "countryCode(field)", 0,
                "The country calling code of a phone number (+8613800138000 -> 86)",
                (value, args) -> countryCode(value));
        register("phonePrefix", "phonePrefix(field[, n])", 0,
                "The first n digits of the number after the country code (default 4)",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    String all = digits(dropPrefix(text));
                    String code = countryCode(text);
                    String rest = code != null && all != null && all.startsWith(code)
                            ? all.substring(code.length()) : all;
                    return first(rest, count(args, 1, 4));
                });
        register("cardBin", "cardBin(field[, n])", 0,
                "The first n digits of a card number, the BIN (default 6)",
                (value, args) -> first(digits(value), count(args, 1, 6)));
        register("hash", "hash(field[, n])", 0,
                "The first n hex characters of the SHA-256 of the value (default 16), for a key that "
                        + "does not carry the value itself",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    String hex = sha256(text);
                    return hex.substring(0, Math.min(count(args, 1, 16), hex.length()));
                });
        register("opt", "opt(field)", 0,
                "A part that may be missing: it becomes * instead of making the pattern not apply",
                (value, args) -> trimmed(value) == null ? "*" : trimmed(value));
    }

    private FuzzyFunctions() {
    }

    /** Whether a name is a function of this library. */
    public static boolean isFunction(String name) {
        return FUNCTIONS.containsKey(name);
    }

    /** The signatures of every function, for the pattern editor. */
    public static List<Signature> signatures() {
        List<Signature> out = new ArrayList<>(FUNCTIONS.size());
        for (Map.Entry<String, Entry> entry : FUNCTIONS.entrySet()) {
            out.add(new Signature(entry.getKey(), entry.getValue().usage(), entry.getValue().description()));
        }
        return out;
    }

    /**
     * Applies a function to an already evaluated first argument.
     *
     * @param name  the function name
     * @param value the value of the first argument (may be {@code null}: a missing field)
     * @param args  the remaining arguments (literals or numbers)
     * @return the fragment, or {@code null} when the value is missing or has an unexpected shape
     */
    static String apply(String name, String value, List<String> args) {
        Entry entry = FUNCTIONS.get(name);
        if (entry == null) {
            throw new FuzzyPatternException("Unknown fuzzy function '" + name + "'. Known functions: "
                    + String.join(", ", FUNCTIONS.keySet()));
        }
        List<String> withValue = new ArrayList<>(args.size() + 1);
        withValue.add(value);
        withValue.addAll(args);
        if (withValue.size() - 1 < entry.minArgs()) {
            throw new FuzzyPatternException("Function '" + name + "' expects at least " + entry.minArgs()
                    + " argument(s) besides the field: " + entry.usage());
        }
        return entry.function().apply(value, withValue);
    }

    // --- value helpers ---

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String digits(String value) {
        String text = trimmed(value);
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                out.append(text.charAt(i));
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String first(String value, int n) {
        if (value == null || value.length() < n) {
            return value == null ? null : value;
        }
        return value.substring(0, n);
    }

    private static String last(String value, int n) {
        if (value == null || value.length() < n) {
            return value;
        }
        return value.substring(value.length() - n);
    }

    private static int count(List<String> args, int index, int fallback) {
        if (args.size() <= index) {
            return fallback;
        }
        String text = args.get(index);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(text.trim());
            if (n < 1) {
                throw new FuzzyPatternException("A count must be positive, got '" + text + "'");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new FuzzyPatternException("A count must be a number, got '" + text + "'");
        }
    }

    /** The first {@code n} octets of an IPv4 or IPv6 address, without a port or a CIDR suffix. */
    private static String octets(String value, int n) {
        String text = trimmed(value);
        if (text == null) {
            return null;
        }
        String address = text;
        int slash = address.indexOf('/');
        if (slash > 0) {
            address = address.substring(0, slash);
        }
        // an IPv6 address has colons and no port; an IPv4 may carry ":port" or a "[ip]:port" form
        String separator = address.contains(":") && !address.contains(".") ? ":" : "\\.";
        if (address.contains(".") && address.contains(":")) {
            int port = address.lastIndexOf(':');
            address = address.substring(0, port);
        }
        address = address.replace("[", "").replace("]", "");
        String[] parts = address.split(separator);
        if (parts.length < n) {
            return null;
        }
        List<String> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (parts[i].isBlank()) {
                return null;
            }
            kept.add(parts[i].toLowerCase(Locale.ROOT));
        }
        return String.join(separator.equals("\\.") ? "." : ":", kept);
    }

    /** The country calling code of a phone number, or {@code null} when it is not in E.164 form. */
    private static String countryCode(String value) {
        String text = trimmed(value);
        if (text == null) {
            return null;
        }
        if (!text.startsWith("+") && !text.startsWith("00")) {
            // without a prefix the number does not say where it is from: a leading 1 is as likely
            // to be a mobile prefix as the North American code, and guessing would link numbers
            // that only look alike, so a pattern that needs the code does not apply
            return null;
        }
        String digits = digits(dropPrefix(text));
        if (digits == null || digits.length() < 4) {
            return null;
        }
        // A table rather than a guess: the codes a risk context is seen with, longest first, so
        // 852 (Hong Kong) is not read as 8 or 85. A number whose code is not in the table does not
        // say where it is from, and a pattern that needs the code then does not apply - inventing
        // a code would link numbers that only look alike.
        for (String code : COUNTRY_CODES) {
            if (digits.startsWith(code) && digits.length() - code.length() >= 4) {
                return code;
            }
        }
        return null;
    }

    /** Drops the international prefix ({@code +}, {@code 00}) a written number may carry. */
    private static String dropPrefix(String text) {
        if (text.startsWith("+")) {
            return text.substring(1);
        }
        return text.startsWith("00") ? text.substring(2) : text;
    }

    /** Country calling codes this library recognises, longest first. */
    private static final List<String> COUNTRY_CODES = List.of(
            "852", "853", "886", "971", "972", "966", "962", "880", "886",
            "86", "81", "82", "84", "91", "92", "65", "66", "60", "62", "63", "64", "61",
            "44", "49", "39", "34", "33", "31", "32", "41", "43", "46", "47", "48", "30", "36",
            "27", "20", "55", "54", "52", "51", "56", "57", "90", "94", "95", "98", "7", "1");

    private static Pattern compile(String regex, String function) {
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException e) {
            throw new FuzzyPatternException("Function " + function + " has an invalid regular expression '"
                    + regex + "': " + e.getMessage(), e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
