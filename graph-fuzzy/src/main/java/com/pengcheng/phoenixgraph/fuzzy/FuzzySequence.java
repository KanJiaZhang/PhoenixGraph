package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pattern over a <b>sequence of events</b>: the order of the events, the time between them, and the
 * fragments they contribute to the key.
 *
 * <pre>
 * fl_2_L0(ip2(Login.ip))_[&lt;100s]_B1_[&lt;100s]_P2_[&lt;100s]_D3(Dispute.DisputeReason)
 * </pre>
 *
 * <p>Reading the expression: a step is an event-name prefix and its position ({@code L0} = the first
 * {@code L} event - {@code Login} - {@code B1} the {@code BalanceCheck} that follows, and so on), with an
 * optional value in parentheses and an optional required value ({@code D3(Dispute.Reason)=='NotMyCard'}).
 * Between two steps {@code _[&lt;100s]_} bounds the time: the two events are at most that many seconds apart.
 * A step may also carry no value at all ({@code B1}), which only says "this event happened here".
 *
 * <p>Evaluating a context - the event list of an account, in any of the shapes
 * {@link FuzzyContext#events()} reads - yields the <b>fragments</b> the steps contribute and their
 * concatenation, the key: accounts whose events match the same steps with the same fragments land on one
 * link node. Events the pattern does not name are ignored, so a list may carry more than the pattern asks
 * for; what the pattern does require is that its steps appear in order, each within the window of the
 * previous one, and that every value it needs is there (a step whose value is missing makes the whole
 * pattern not apply, exactly as in a flat pattern).
 *
 * <p>The leading {@code fl_&lt;id&gt;_} is the link id's own prefix ({@link FuzzySchema#linkVertexId}); when a
 * pattern writes it, it has to name the pattern it belongs to.
 */
public final class FuzzySequence {

    /** One step of the sequence: which event, which value it contributes, in which window. */
    public record Step(int index, String eventPrefix, int position, String valueExpression,
                       String requiredValue, int withinSeconds, String text, FuzzyExpression value) {

        /** Whether an event of this name is the one this step waits for. */
        public boolean matches(Event event) {
            return event.name().toLowerCase(Locale.ROOT).startsWith(eventPrefix.toLowerCase(Locale.ROOT));
        }

        /** The text of the step as the pattern wrote it. */
        public String describe() {
            return text;
        }
    }

    /** One event of a context, as the sequence reads it. */
    public record Event(String name, long eventTime, Map<String, Object> values) {
    }

    /** What a context produced: the events that matched (in order) and the fragments they gave. */
    public record Match(List<Event> events, List<String> fragments, long startTime, long endTime) {

        /** The key: the fragments joined, the way a flat pattern joins its parts. */
        public String key() {
            return String.join("_", fragments);
        }

        /** How long the sequence took, in milliseconds. */
        public long spanMillis() {
            return endTime - startTime;
        }
    }

    private static final Pattern PREFIX = Pattern.compile("^\\s*fl_(\\d+)_");
    private static final Pattern GAP = Pattern.compile("^_\\s*\\[<\\s*(\\d+)\\s*(?:s|sec|secs|seconds)?\\s*\\]\\s*_");
    private static final Pattern STEP_HEAD = Pattern.compile("^([A-Za-z]+?)(\\d+)");

    private final String text;
    private final int declaredPatternId;
    private final List<Step> steps;

    private FuzzySequence(String text, int declaredPatternId, List<Step> steps) {
        this.text = text;
        this.declaredPatternId = declaredPatternId;
        this.steps = List.copyOf(steps);
    }

    /** Whether an expression describes a sequence rather than the flat {@code part+part} form. */
    public static boolean looksLikeSequence(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String text = expression.trim();
        Matcher prefix = PREFIX.matcher(text);
        boolean prefixed = prefix.lookingAt();
        String rest = prefixed ? text.substring(prefix.end()) : text;
        if (!STEP_HEAD.matcher(rest).find()) {
            return false;
        }
        return prefixed || text.contains("_[<");
    }

    /** Parses a sequence; the message says which step is wrong and what was expected. */
    public static FuzzySequence parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FuzzyPatternException("A fuzzy pattern must not be blank");
        }
        String text = expression.trim();
        int declaredId = -1;
        Matcher prefix = PREFIX.matcher(text);
        int cursor = 0;
        if (prefix.find()) {
            declaredId = Integer.parseInt(prefix.group(1));
            cursor = prefix.end();
        }
        List<Step> steps = new ArrayList<>();
        int pendingWindow = 0;
        while (cursor < text.length()) {
            Matcher gap = GAP.matcher(text);
            gap.region(cursor, text.length());
            if (gap.lookingAt()) {
                if (steps.isEmpty()) {
                    throw new FuzzyPatternException("A sequence cannot start with a time window: " + text);
                }
                pendingWindow = Integer.parseInt(gap.group(1));
                cursor = gap.end();
                continue;
            }
            int start = cursor;
            Matcher head = STEP_HEAD.matcher(text);
            head.region(cursor, text.length());
            if (!head.lookingAt()) {
                throw new FuzzyPatternException("Cannot read step " + (steps.size() + 1) + " of '" + text
                        + "': expected an event name such as L0 (the first 'L' event) at "
                        + text.substring(cursor));
            }
            String letters = head.group(1);
            int position = Integer.parseInt(head.group(2));
            cursor = head.end();
            String valueExpression = null;
            if (cursor < text.length() && text.charAt(cursor) == '(') {
                int depth = 1;
                int end = cursor + 1;
                while (end < text.length() && depth > 0) {
                    char c = text.charAt(end);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                    end++;
                }
                if (depth != 0) {
                    throw new FuzzyPatternException("Unbalanced parentheses in step " + letters + position
                            + " of '" + text + "'");
                }
                valueExpression = text.substring(cursor + 1, end - 1).trim();
                cursor = end;
            }
            String requiredValue = null;
            Matcher required = Pattern.compile("^\\s*==\\s*'([^']*)'").matcher(text);
            required.region(cursor, text.length());
            if (required.lookingAt()) {
                requiredValue = required.group(1);
                cursor = required.end();
            }
            String stepText = text.substring(start, cursor).trim();
            steps.add(new Step(steps.size(), letters, position, valueExpression, requiredValue, pendingWindow,
                    stepText, valueExpression == null ? null : FuzzyExpression.parse(asCall(valueExpression))));
            pendingWindow = 0;
        }
        if (steps.size() < 2) {
            throw new FuzzyPatternException("A sequence needs at least two steps, got " + steps.size()
                    + " in '" + text + "'");
        }
        return new FuzzySequence(text, declaredId, steps);
    }

    /**
     * A step value as the flat expression grammar reads it: a bare field path ({@code Dispute.DisputeReason})
     * is wrapped in {@code raw(...)}, which returns that field as it stands, while anything that already calls
     * a function is left alone.
     */
    private static String asCall(String valueExpression) {
        String text = valueExpression.trim();
        return text.indexOf('(') >= 0 ? text : "raw(" + text + ")";
    }

    /** The expression as written. */
    public String text() {
        return text;
    }

    /** The pattern id the expression named with {@code fl_<id>_}, or {@code -1} when it named none. */
    public int declaredPatternId() {
        return declaredPatternId;
    }

    /** The steps, in order. */
    public List<Step> steps() {
        return steps;
    }

    /**
     * Matches a context, or {@code null} when the sequence is not there: a required event missing, a window
     * exceeded, a required value different, or a value the key needs missing.
     *
     * <p>Events are read in time order and the steps are matched greedily from the earliest event that fits,
     * which is the reading "this happened, then that, within the window" - the first occurrence is the one an
     * analyst means.
     */
    public Match match(FuzzyContext context) {
        List<Event> events = new ArrayList<>();
        for (FuzzyContext.Event event : context.events()) {
            events.add(new Event(event.name(), event.eventTime(), event.values()));
        }
        if (events.isEmpty()) {
            return null;
        }
        events.sort((a, b) -> Long.compare(a.eventTime(), b.eventTime()));

        List<Event> matched = new ArrayList<>(steps.size());
        List<String> fragments = new ArrayList<>(steps.size());
        int cursor = 0;
        Event previous = null;
        for (Step step : steps) {
            Event found = null;
            for (int i = cursor; i < events.size(); i++) {
                Event event = events.get(i);
                if (previous != null && step.withinSeconds() > 0
                        && event.eventTime() - previous.eventTime() > step.withinSeconds() * 1000L) {
                    break; // events are in time order: everything after this one is further away
                }
                if (!step.matches(event)) {
                    continue;
                }
                String value = valueOf(step, context, event);
                if (step.requiredValue() != null && !step.requiredValue().equals(value)) {
                    continue;
                }
                if (step.valueExpression() != null && value == null) {
                    continue; // the step needs a value this event does not carry: keep looking
                }
                found = event;
                cursor = i + 1;
                break;
            }
            if (found == null) {
                return null;
            }
            matched.add(found);
            if (step.valueExpression() != null && step.requiredValue() == null) {
                // a step that requires a value is a condition, not a fragment: the value is the same for
                // every match of the pattern, so the key names the group the pattern already fixed.
                String fragment = valueOf(step, context, found);
                if (fragment == null || fragment.isBlank()) {
                    return null; // a key built around a missing fragment would merge accounts that share nothing
                }
                fragments.add(fragment);
            }
            previous = found;
        }
        return new Match(List.copyOf(matched), List.copyOf(fragments), matched.get(0).eventTime(),
                matched.get(matched.size() - 1).eventTime());
    }

    /** The key a context produces, or {@code null} when the pattern does not apply. */
    public String key(FuzzyContext context) {
        Match match = match(context);
        return match == null ? null : match.key();
    }

    /** The fragments, one per step that carries a value. */
    public List<String> fragments(FuzzyContext context) {
        Match match = match(context);
        return match == null ? List.of() : match.fragments();
    }

    /**
     * The sequence part by part, the way the pattern editor shows it: which event each step matched, when it
     * happened and what it contributed, then the key - the same shape a flat pattern's explanation has.
     */
    public List<FuzzyExpression.Step> explain(FuzzyContext context) {
        Match match = match(context);
        List<FuzzyExpression.Step> out = new ArrayList<>(steps.size() + 1);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (match == null) {
                out.add(new FuzzyExpression.Step(i, step.describe(), null));
                continue;
            }
            Event event = match.events().get(i);
            String value = valueOf(step, context, event);
            long gap = i == 0 ? 0 : event.eventTime() - match.events().get(i - 1).eventTime();
            out.add(new FuzzyExpression.Step(i, step.describe(),
                    event.name() + " @" + event.eventTime() + " (+" + (gap / 1000) + "s)"
                            + (step.valueExpression() == null ? "" : " -> " + value)));
        }
        out.add(new FuzzyExpression.Step(steps.size(), "key", match == null ? null : match.key()));
        return out;
    }

    /** The value a step reads from a matched event, evaluated with the event's own fields in view. */
    private static String valueOf(Step step, FuzzyContext context, Event event) {
        if (step.value() == null) {
            return null;
        }
        return step.value().key(context.with(event.values()));
    }

    /** The fields a key of this sequence would read, for the editor's diagnostics. */
    public Map<String, List<String>> fieldsByStep() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Step step : steps) {
            out.put(step.describe(), step.value() == null ? List.of() : step.value().fields());
        }
        return out;
    }
}
