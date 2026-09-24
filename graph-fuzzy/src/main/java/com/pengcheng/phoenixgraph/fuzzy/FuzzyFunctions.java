package com.pengcheng.phoenixgraph.fuzzy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * A function that reads the whole context rather than one value: {@code anyFields} compares several
     * events of a sequence at once, so a value-at-a-time signature cannot express it.
     */
    @FunctionalInterface
    private interface ContextFunction {
        String apply(FuzzyContext context, List<String> args);
    }

    private record ContextEntry(String usage, String description, ContextFunction function) {
    }

    private static final Map<String, Entry> FUNCTIONS = new LinkedHashMap<>();
    private static final Map<String, ContextEntry> CONTEXT_FUNCTIONS = new LinkedHashMap<>();

    private static void register(String name, String usage, int minArgs, String description,
                                Function function) {
        FUNCTIONS.put(name, new Entry(usage, description, minArgs, function));
    }

    private static void registerContext(String name, String usage, String description,
                                        ContextFunction function) {
        CONTEXT_FUNCTIONS.put(name, new ContextEntry(usage, description, function));
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
                "Requires the value to match the pattern and puts the pattern itself - the shape, not "
                        + "what it matched - into the key, so every value of that shape shares the "
                        + "fragment (use regexValue to put what it matched instead)",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    Matcher matcher = compile(args.get(1), "regex").matcher(text);
                    if (args.size() > 2 && !args.get(2).isBlank()) {
                        // a group the expression does not have is a mistake in the pattern, so it is
                        // reported even when this particular value does not match
                        int group = groupIndex(args, 2);
                        if (group > matcher.groupCount()) {
                            throw new FuzzyPatternException("The regular expression '" + args.get(1)
                                    + "' has " + matcher.groupCount() + " capturing group(s), so group "
                                    + group + " does not exist");
                        }
                    }
                    if (!matcher.find()) {
                        return null; // the shape does not hold: the pattern does not apply
                    }
                    return shape(args.get(1));
                });
        register("regexValue", "regexValue(field, 'pattern'[, group])", 1,
                "What the pattern matched - the whole match, or the group it is asked for (the first "
                        + "capturing group when the pattern has one) - as the fragment of the key",
                (value, args) -> {
                    String text = trimmed(value);
                    if (text == null) {
                        return null;
                    }
                    Matcher matcher = compile(args.get(1), "regexValue").matcher(text);
                    int group = args.size() > 2 && !args.get(2).isBlank() ? groupIndex(args, 2) : -1;
                    if (group > matcher.groupCount()) {
                        throw new FuzzyPatternException("The regular expression '" + args.get(1) + "' has "
                                + matcher.groupCount() + " capturing group(s), so group " + group
                                + " does not exist");
                    }
                    if (!matcher.find()) {
                        return null;
                    }
                    if (group >= 0) {
                        return lower(matcher.group(group));
                    }
                    return lower(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
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
        registerContext("anyFields", "anyFields([Event, ...], n[, true])",
                "The fields that every listed event carries with the same value - at least n of them, or the "
                        + "pattern does not apply. The names are sorted and joined (cookie_ip_type); when the "
                        + "last argument is true each name keeps the value they agreed on (ip=10.12.13.14)",
                FuzzyFunctions::anyFields);
    }

    private FuzzyFunctions() {
    }

    /** Whether a name is a function of this library. */
    public static boolean isFunction(String name) {
        return FUNCTIONS.containsKey(name) || CONTEXT_FUNCTIONS.containsKey(name);
    }

    /** Whether the function reads the whole context (it takes a list of events, not one value). */
    public static boolean isContextFunction(String name) {
        return CONTEXT_FUNCTIONS.containsKey(name);
    }

    /** The signatures of every function, for the pattern editor. */
    public static List<Signature> signatures() {
        List<Signature> out = new ArrayList<>(FUNCTIONS.size() + CONTEXT_FUNCTIONS.size());
        for (Map.Entry<String, Entry> entry : FUNCTIONS.entrySet()) {
            out.add(new Signature(entry.getKey(), entry.getValue().usage(), entry.getValue().description()));
        }
        for (Map.Entry<String, ContextEntry> entry : CONTEXT_FUNCTIONS.entrySet()) {
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

    /**
     * Applies a context function: the arguments arrive as the expression wrote them (a list as
     * {@code Login,Payment,Dispute}, a literal as its text), and the function reads the events it needs out
     * of the context.
     */
    static String applyContext(String name, FuzzyContext context, List<String> args) {
        ContextEntry entry = CONTEXT_FUNCTIONS.get(name);
        if (entry == null) {
            throw new FuzzyPatternException("Unknown context function '" + name + "'. Known functions: "
                    + String.join(", ", CONTEXT_FUNCTIONS.keySet()));
        }
        return entry.function().apply(context, args);
    }

    /**
     * The fields every event of a list carries with the same value: the similarity of a session that no single
     * step owns.
     *
     * <p>Bookkeeping fields ({@code id}, the event time, the event name) are ignored: they are either unique to
     * one event or say nothing about who the session belongs to. With the last argument false the fragment is
     * the field names in alphabetical order ({@code cookie_ip_type}); with true each name keeps the value the
     * events agreed on ({@code cookie=abc_ip=10.12.13.14_type=mobile}). Fewer than the required number of agreed
     * fields means the pattern does not apply - the session has no such signature to link on.
     */
    private static String anyFields(FuzzyContext context, List<String> args) {
        if (args.isEmpty() || args.get(0) == null || args.get(0).isBlank()) {
            throw new FuzzyPatternException("anyFields needs the events to compare, e.g. "
                    + "anyFields([Login, Payment, Dispute], 3)");
        }
        List<String> events = new ArrayList<>();
        for (String name : args.get(0).split(",")) {
            if (!name.isBlank()) {
                events.add(name.trim());
            }
        }
        if (events.size() < 2) {
            throw new FuzzyPatternException("anyFields needs at least two events to compare, got "
                    + events);
        }
        int required = args.size() > 1 ? count(args, 1, 1) : 1;
        boolean withValues = args.size() > 2 && Boolean.parseBoolean(args.get(2) == null
                ? "false" : args.get(2).trim());

        List<Map<String, Object>> fields = new ArrayList<>(events.size());
        for (String event : events) {
            Object value = context.find(event);
            if (!(value instanceof Map<?, ?> map)) {
                return null; // the event is not part of this match: the pattern does not apply
            }
            Map<String, Object> named = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                named.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            fields.add(named);
        }

        // the names every event carries, ignoring the bookkeeping fields
        Set<String> candidates = new LinkedHashSet<>();
        for (Map<String, Object> event : fields) {
            Set<String> names = new LinkedHashSet<>();
            for (String key : event.keySet()) {
                String normalised = normalise(key);
                if (!ANY_FIELDS_IGNORED.contains(normalised)) {
                    names.add(normalised);
                }
            }
            if (candidates.isEmpty()) {
                candidates.addAll(names);
            } else {
                candidates.retainAll(names);
            }
        }
        // of those, the ones whose values agree across every event, in alphabetical order
        Map<String, String> agreed = new TreeMap<>();
        for (String name : candidates) {
            String value = null;
            boolean first = true;
            boolean equal = true;
            for (Map<String, Object> event : fields) {
                String text = fieldText(event, name);
                if (first) {
                    value = text;
                    first = false;
                } else if (!Objects.equals(value, text)) {
                    equal = false;
                    break;
                }
            }
            if (equal && value != null) {
                agreed.put(name, value);
            }
        }
        if (agreed.size() < required) {
            return null; // not enough of a signature to link on
        }
        if (withValues) {
            List<String> parts = new ArrayList<>(agreed.size());
            for (Map.Entry<String, String> entry : agreed.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            return String.join("_", parts);
        }
        return String.join("_", agreed.keySet());
    }

    /** Fields that say nothing about a session's identity, so anyFields never counts them. */
    private static final Set<String> ANY_FIELDS_IGNORED = Set.of(
            "id", "eventtime", "time", "timestamp", "ts", "name", "eventname", "eventtype", "event");

    /** The value of a field, addressed by its normalised name, as text. */
    private static String fieldText(Map<String, Object> event, String normalised) {
        for (Map.Entry<String, Object> entry : event.entrySet()) {
            if (normalise(entry.getKey()).equals(normalised)) {
                Object value = entry.getValue();
                return value == null ? null : String.valueOf(value).trim();
            }
        }
        return null;
    }

    /** The key a field name is compared under (case and separators do not matter). */
    private static String normalise(String field) {
        return field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    // --- value helpers ---

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /** A regular expression group number: zero is the whole match. */
    private static int groupIndex(List<String> args, int index) {
        String text = args.get(index);
        try {
            int group = Integer.parseInt(text.trim());
            if (group < 0) {
                throw new FuzzyPatternException("A group number must not be negative, got '" + text + "'");
            }
            return group;
        } catch (NumberFormatException e) {
            throw new FuzzyPatternException("A group number must be a number, got '" + text + "'");
        }
    }

    /** A matched fragment, lower cased (a blank match counts as no match). */
    private static String lower(String matched) {
        if (matched == null || matched.isBlank()) {
            return null;
        }
        return matched.toLowerCase(Locale.ROOT);
    }

    /**
     * The fragment a regular expression contributes: its own shape, not what it matched.
     *
     * <p>Extracting the value would link {@code zhang1988} to {@code zhang1979} but leave
     * {@code xia1999} - a value of the same shape - on a node of its own, so a pattern that means
     * "a word followed by digits" would only group accounts that happen to share the word. The
     * shape is what the pattern asks for, so the shape is the fragment.
     *
     * <p>The shape is read as a fragment of a record key, so it is written as {@code re_<shape>_<hash>}:
     * a reader of the key sees which sub-pattern it stands for, and the hash keeps two shapes that
     * normalise alike ({\@code ^([a-z]+)} and {\@code ^[a-z]+}) apart. The pattern editor shows the expression itself.
     */
    static String shape(String regex) {
        String readable = regex.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        readable = readable.replaceAll("^_+", "").replaceAll("_+$", "");
        if (readable.length() > 24) {
            readable = readable.substring(0, 24);
        }
        return "re_" + readable + "_" + sha256(regex).substring(0, 8);
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

    private static String first(String value, int n) {        if (value == null || value.length() < n) {
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
