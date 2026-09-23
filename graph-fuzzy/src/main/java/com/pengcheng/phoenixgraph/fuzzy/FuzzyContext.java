package com.pengcheng.phoenixgraph.fuzzy;

import java.util.LinkedHashMap;
import java.util.List;
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

    /** One event a sequence pattern reads: its name, its time (epoch millis) and its own fields. */
    public record Event(String name, long eventTime, Map<String, Object> values) {

        /** The fields of the event, addressed by name like any other context. */
        public FuzzyContext context() {
            return FuzzyContext.of(values);
        }
    }

    /**
     * The events this context carries, in time order - or an empty list when it carries none.
     *
     * <p>Three shapes are read, because an event list arrives from three places: the example a caller
     * writes ({@code {"Login": {"EventTime": ...}, "Payment": {...}}}, one entry per event type), a list
     * under {@code events} ({@code {"events": [{"name": "Login", "EventTime": ...}, ...]}}), and a field
     * holding that list as JSON text (an account property that keeps its event log as a string).
     */
    public List<Event> events() {
        List<Event> out = new java.util.ArrayList<>();
        Object explicit = find("events");
        if (explicit instanceof List<?> list) {
            for (Object element : list) {
                addEvent(out, null, element);
            }
        } else if (explicit instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                addEvent(out, String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (out.isEmpty()) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = parseJson(entry.getValue());
                if (!(value instanceof Map<?, ?> inner)) {
                    continue;
                }
                Map<String, Object> fields = fieldsOf(inner);
                Object nestedEvents = field(fields, "events");
                if (nestedEvents instanceof List<?> list) {
                    // a property that keeps the whole document ({"events": [...]}) as its value
                    for (Object element : list) {
                        addEvent(out, null, element);
                    }
                    continue;
                }
                if (timeOf(fields) != null) {
                    addEvent(out, entry.getKey(), fields);
                }
            }
        }
        out.removeIf(event -> event.eventTime() < 0);
        out.sort(java.util.Comparator.comparingLong(Event::eventTime));
        return List.copyOf(out);
    }

    /** A nested map as a name-addressed one (JSON keys are text; a caller may hand us anything). */
    private static Map<String, Object> fieldsOf(Map<?, ?> map) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return fields;
    }

    private static void addEvent(List<Event> out, String fallbackName, Object element) {
        Object value = element instanceof String text ? parseJson(text) : element;
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        Long time = timeOf(fields);
        if (time == null) {
            return; // an entry without a time is not an event
        }
        String name = fallbackName != null ? fallbackName : nameOf(fields);
        out.add(new Event(name == null ? "" : name, time, Map.copyOf(fields)));
    }

    /** The name of an event, under the spellings an ingest uses. */
    private static String nameOf(Map<String, Object> fields) {
        for (String key : List.of("name", "eventName", "eventType", "type", "event")) {
            Object value = field(fields, key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** The event time in epoch millis, from a number (seconds or millis) or an ISO instant. */
    private static Long timeOf(Map<String, Object> fields) {
        for (String key : List.of("eventTime", "time", "timestamp", "ts", "eventtime")) {
            Object value = field(fields, key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                long raw = number.longValue();
                // a second-resolution timestamp is still a timestamp: treat anything short as seconds
                return raw > 100_000_000_000L ? raw : raw * 1000L;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                if (text.matches("-?\\d+")) {
                    long raw = Long.parseLong(text);
                    return raw > 100_000_000_000L ? raw : raw * 1000L;
                }
                return java.time.Instant.parse(text).toEpochMilli();
            } catch (RuntimeException ignored) {
                // not a time after all
            }
        }
        return null;
    }

    /** A field of a nested map, matched the way a top-level field is. */
    private static Object field(Map<String, Object> fields, String wanted) {
        Object direct = fields.get(wanted);
        if (direct != null) {
            return direct;
        }
        String normalised = normalise(wanted);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (normalise(entry.getKey()).equals(normalised)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * The raw value a dotted path names ({@code Login.IP}), a JSON-text field parsed on the way.
     *
     * <p>A field that holds JSON text - an account whose event log is one string property - is read as the
     * document it is, so a pattern cannot tell the two ways of carrying the same list apart.
     */
    Object find(String path) {
        String[] segments = path.split("\\.");
        Object current = rootValue(segments[0]);
        for (int i = 1; current != null && i < segments.length; i++) {
            current = child(current, segments[i]);
        }
        return current;
    }

    private Object rootValue(String field) {
        Object direct = values.get(field);
        if (direct != null) {
            return parseJson(direct);
        }
        String wanted = normalise(field);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (normalise(entry.getKey()).equals(wanted)) {
                return parseJson(entry.getValue());
            }
        }
        return null;
    }

    private static Object child(Object parent, String field) {
        if (parent instanceof Map<?, ?> map) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return field(fields, field);
        }
        if (parent instanceof List<?> list) {
            try {
                int index = Integer.parseInt(field);
                return index >= 0 && index < list.size() ? parseJson(list.get(index)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A value that is JSON text becomes the document it is; anything else is itself. */
    private static Object parseJson(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return value;
        }
        try {
            return JSON.readValue(trimmed, Object.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return value;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
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
        if (field.indexOf('.') >= 0) {
            // a dotted path reads inside a nested value: a sequence pattern says Login.IP, Dispute.Reason.
            // When there is no such nested value, the last segment is tried on its own - an event list that
            // arrives as a list of events carries each event's fields flat, and the step that says
            // Dispute.Reason means the reason of the event it matched.
            Object nested = find(field);
            if (nested == null) {
                nested = find(field.substring(field.lastIndexOf('.') + 1));
            }
            if (nested != null) {
                return text(nested);
            }
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
