package com.pengcheng.phoenixgraph.fuzzy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pattern over a <b>sequence of events</b>: the order of the events, the time between them, the
 * conditions they have to satisfy, and the fragments they contribute to the key.
 *
 * <pre>
 * fl_2_L0(ip2(Login.ip))_[&lt;100s]_B1_[&lt;100s]_P2_[&lt;100s]_D3(Dispute.DisputeReason)
 * fl_3_L0(ip2(Login.ip))_[&lt;100s]_B1_[-100s&lt;time_interval&lt;100s]_P2_[&lt;100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip]
 * fl_4_L0(ip2(Login.ip))[Login.type=='Mobile']_[&lt;100s]_B1_[-100s&lt;time_interval&lt;100s]_P2_[&lt;100s]_D3(Dispute.DisputeReason)[Dispute.ip==Login.ip || Dispute.cookie == Payment.Cookie || Dispute.DisputeReason == 'NotMyCard']
 * </pre>
 *
 * <p>Reading the expression: a step is an event-name prefix and its position ({@code L0} = the first
 * {@code L} event - {@code Login} - {@code B1} the {@code BalanceCheck} that follows, and so on), with an
 * optional value in parentheses and an optional required value ({@code D3(Dispute.Reason)=='NotMyCard'}).
 * Between two steps {@code _[&lt;100s]_} bounds the time: the two events are at most that many seconds apart.
 * A two-sided window ({@code [-100s&lt;time_interval&lt;100s]}) also gives a lower bound, and a negative one
 * says the step may come <em>before</em> the one written before it - two events of a session that happen
 * around each other, within the same hundred seconds. A bracket after a step ({@code [Login.type=='Mobile']})
 * is a condition over the fields of the events the steps matched; the condition of the whole sequence is
 * written the same way after the last step. A step may also carry no value at all ({@code B1}), which only
 * says "this event happened here".
 *
 * <p>Evaluating a context - the event list of an account, in any of the shapes {@link FuzzyContext#events()}
 * reads - yields the <b>fragments</b> the steps contribute and their concatenation, the key: accounts whose
 * events match the same steps with the same fragments land on one link node. Events the pattern does not
 * name are ignored, so a list may carry more than the pattern asks for; what the pattern does require is
 * that its steps appear in the windows written between them, that every condition holds, and that every
 * value it needs is there (a step whose value is missing makes the whole pattern not apply, exactly as in a
 * flat pattern).
 *
 * <p>The leading {@code fl_&lt;id&gt;_} is the link id's own prefix ({@link FuzzySchema#linkVertexId}); when a
 * pattern writes it, it has to name the pattern it belongs to.
 */
public final class FuzzySequence {

    /** One step of the sequence: which event, which value it contributes, in which window. */
    public record Step(int index, String eventPrefix, int position, String valueExpression,
                       String requiredValue, int withinSeconds, int minSeconds, String text,
                       FuzzyExpression value) {

        /** Whether an event of this name is the one this step waits for. */
        public boolean matches(Event event) {
            return event.name().toLowerCase(Locale.ROOT).startsWith(eventPrefix.toLowerCase(Locale.ROOT));
        }

        /** The text of the step as the pattern wrote it. */
        public String describe() {
            return text;
        }

        /**
         * Whether a gap of {@code gapMillis} between the previous matched event and a candidate fits this
         * step's window.
         *
         * <p>No window written means the order alone is kept (the next event may be any time later); the
         * bounds written are inclusive, and a negative lower bound lets the step move back in time.
         */
        public boolean allows(long gapMillis) {
            if (withinSeconds <= 0) {
                return gapMillis >= 0;
            }
            return gapMillis >= minSeconds * 1000L && gapMillis <= withinSeconds * 1000L;
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
    private static final String UNIT = "(?:s|sec|secs|seconds)?";
    private static final Pattern GAP = Pattern.compile(
            "^_\\s*\\[<\\s*(\\d+)\\s*" + UNIT + "\\s*\\]\\s*_");
    private static final Pattern GAP_RANGE = Pattern.compile(
            "^_\\s*\\[\\s*(-?\\d+)\\s*" + UNIT + "\\s*<\\s*(?:[A-Za-z_][A-Za-z0-9_.]*\\s*)?<\\s*(-?\\d+)\\s*"
                    + UNIT + "\\s*\\]\\s*_");
    /** Any gap at all, so an expression with a two-sided window is still read as a sequence. */
    private static final Pattern GAP_ANYWHERE = Pattern.compile("_\\s*\\[\\s*(?:<\\s*)?-?\\d");
    private static final Pattern STEP_HEAD = Pattern.compile("^([A-Za-z]+?)(\\d+)");

    /** How many candidate assignments are tried before the matcher gives up on a list that is too long. */
    private static final int MAX_TRIES = 500_000;

    private final String text;
    private final int declaredPatternId;
    private final List<Step> steps;
    private final List<FuzzyCondition> conditions;

    private FuzzySequence(String text, int declaredPatternId, List<Step> steps,
                          List<FuzzyCondition> conditions) {
        this.text = text;
        this.declaredPatternId = declaredPatternId;
        this.steps = List.copyOf(steps);
        this.conditions = List.copyOf(conditions);
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
        return prefixed || GAP_ANYWHERE.matcher(text).find();
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
        List<FuzzyCondition> conditions = new ArrayList<>();
        int pendingMin = 0;
        int pendingMax = 0;
        while (cursor < text.length()) {
            Matcher range = GAP_RANGE.matcher(text);
            range.region(cursor, text.length());
            if (range.lookingAt()) {
                if (steps.isEmpty()) {
                    throw new FuzzyPatternException("A sequence cannot start with a time window: " + text);
                }
                pendingMin = Integer.parseInt(range.group(1));
                pendingMax = Integer.parseInt(range.group(2));
                cursor = range.end();
                continue;
            }
            Matcher gap = GAP.matcher(text);
            gap.region(cursor, text.length());
            if (gap.lookingAt()) {
                if (steps.isEmpty()) {
                    throw new FuzzyPatternException("A sequence cannot start with a time window: " + text);
                }
                pendingMin = 0;
                pendingMax = Integer.parseInt(gap.group(1));
                cursor = gap.end();
                continue;
            }
            if (text.charAt(cursor) == '[') {
                int end = text.indexOf(']', cursor);
                if (end < 0) {
                    throw new FuzzyPatternException("The condition starting at " + cursor + " of '" + text
                            + "' is not closed");
                }
                conditions.add(FuzzyCondition.parse(text.substring(cursor + 1, end)));
                cursor = end + 1;
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
            steps.add(new Step(steps.size(), letters, position, valueExpression, requiredValue, pendingMax,
                    pendingMin, stepText,
                    valueExpression == null ? null : FuzzyExpression.parse(asCall(valueExpression))));
            pendingMin = 0;
            pendingMax = 0;
        }
        if (steps.size() < 2) {
            throw new FuzzyPatternException("A sequence needs at least two steps, got " + steps.size()
                    + " in '" + text + "'");
        }
        return new FuzzySequence(text, declaredId, steps, conditions);
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

    /** The conditions the whole sequence has to satisfy, in the order they were written. */
    public List<FuzzyCondition> conditions() {
        return conditions;
    }

    /**
     * Matches a context, or {@code null} when the sequence is not there: a required event missing, a window
     * exceeded, a condition false, or a value the key needs missing.
     *
     * <p>Events are read in time order and the steps are matched from the earliest assignment that works,
     * which is the reading "this happened, then that, within the window" - the first occurrence is the one
     * an analyst means. When a window allows a step to move back in time, the matcher backs up and tries
     * another assignment rather than giving up on the first one.
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

        List<Event> chosen = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            chosen.add(null);
        }
        boolean[] used = new boolean[events.size()];
        int[] tries = {0};
        if (!search(context, events, 0, chosen, used, tries)) {
            return null;
        }

        List<Event> matched = List.copyOf(chosen);
        FuzzyContext matchedContext = withMatchedEvents(context, matched);
        List<String> fragments = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.valueExpression() != null && step.requiredValue() == null) {
                // a step that requires a value is a condition, not a fragment: the value is the same for
                // every match of the pattern, so the key names the group the pattern already fixed.
                String fragment = valueOf(step, matchedContext, matched.get(i));
                if (fragment == null || fragment.isBlank()) {
                    return null; // a key built around a missing fragment would merge accounts that share nothing
                }
                fragments.add(fragment);
            }
        }
        return new Match(matched, List.copyOf(fragments), matched.get(0).eventTime(),
                matched.get(matched.size() - 1).eventTime());
    }

    /**
     * Greedy-with-backtracking assignment of events to steps, earliest first. A step may reuse no event the
     * earlier steps took, its window bounds are read against the event the step before it matched, and at the
     * end every condition of the sequence has to hold.
     */
    private boolean search(FuzzyContext context, List<Event> events, int stepIndex, List<Event> chosen,
                           boolean[] used, int[] tries) {
        if (++tries[0] > MAX_TRIES) {
            throw new FuzzyPatternException("The event list is too large to match the sequence '" + text
                    + "' within " + MAX_TRIES + " tries; narrow the window or name fewer events");
        }
        if (stepIndex == steps.size()) {
            FuzzyContext matched = withMatchedEvents(context, chosen);
            // every value the key needs has to be there, and it is read from the whole match (a UDF such as
            // anyFields compares several of the matched events), so this is checked once the assignment is
            // complete - a value that is missing sends the search back for another assignment.
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (step.valueExpression() == null) {
                    continue;
                }
                String value = valueOf(step, matched, chosen.get(i));
                if (value == null || value.isBlank()) {
                    return false;
                }
                if (step.requiredValue() != null && !step.requiredValue().equals(value)) {
                    return false;
                }
            }
            for (FuzzyCondition condition : conditions) {
                if (!condition.test(matched)) {
                    return false;
                }
            }
            return true;
        }
        Step step = steps.get(stepIndex);
        for (int i = 0; i < events.size(); i++) {
            if (used[i]) {
                continue;
            }
            Event event = events.get(i);
            if (!step.matches(event)) {
                continue;
            }
            if (stepIndex > 0) {
                long gap = event.eventTime() - chosen.get(stepIndex - 1).eventTime();
                if (!step.allows(gap)) {
                    continue;
                }
            }
            chosen.set(stepIndex, event);
            used[i] = true;
            if (search(context, events, stepIndex + 1, chosen, used, tries)) {
                return true;
            }
            used[i] = false;
            chosen.set(stepIndex, null);
        }
        return false;
    }

    /**
     * The context a condition reads: the original context with every matched event added under the name the
     * event carries ({@code Login}) and under the step that matched it ({@code L0}, {@code L}), so a condition
     * can say {@code Dispute.ip == Login.ip} - the two events the steps matched, whichever they were.
     */
    private FuzzyContext withMatchedEvents(FuzzyContext context, List<Event> chosen) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < chosen.size(); i++) {
            Event event = chosen.get(i);
            Step step = steps.get(i);
            values.put(event.name(), event.values());
            values.put(step.eventPrefix() + step.position(), event.values());
            if (!values.containsKey(step.eventPrefix())) {
                values.put(step.eventPrefix(), event.values());
            }
        }
        return context.with(values);
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
     * happened and what it contributed, then the conditions and the key - the same shape a flat pattern's
     * explanation has.
     */
    public List<FuzzyExpression.Step> explain(FuzzyContext context) {
        Match match = match(context);
        FuzzyContext matchedContext = match == null ? null : withMatchedEvents(context, match.events());
        List<FuzzyExpression.Step> out = new ArrayList<>(steps.size() + conditions.size() + 1);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (match == null) {
                out.add(new FuzzyExpression.Step(i, step.describe(), null));
                continue;
            }
            Event event = match.events().get(i);
            String value = valueOf(step, matchedContext, event);
            long gap = i == 0 ? 0 : event.eventTime() - match.events().get(i - 1).eventTime();
            out.add(new FuzzyExpression.Step(i, step.describe(),
                    event.name() + " @" + event.eventTime() + " (+" + (gap / 1000) + "s)"
                            + (step.valueExpression() == null ? "" : " -> " + value)));
        }
        for (int i = 0; i < conditions.size(); i++) {
            FuzzyCondition condition = conditions.get(i);
            out.add(new FuzzyExpression.Step(steps.size() + i, "[" + condition.text() + "]",
                    matchedContext == null ? null : String.valueOf(condition.test(matchedContext))));
        }
        out.add(new FuzzyExpression.Step(steps.size() + conditions.size(), "key",
                match == null ? null : match.key()));
        return out;
    }

    /**
     * The value a step reads, evaluated against the events the steps matched (so a UDF such as
     * {@code anyFields([Login, Payment, Dispute], 3)} sees all three) plus the current event's own fields.
     */
    private static String valueOf(Step step, FuzzyContext matchedContext, Event event) {
        if (step.value() == null) {
            return null;
        }
        return step.value().key(matchedContext.with(event.values()));
    }

    /** The fields a key of this sequence would read, for the editor's diagnostics. */
    public Map<String, List<String>> fieldsByStep() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Step step : steps) {
            out.put(step.describe(), step.value() == null ? List.of() : step.value().fields());
        }
        for (FuzzyCondition condition : conditions) {
            out.put("[" + condition.text() + "]", condition.fields());
        }
        return out;
    }
}
