package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small Groovy-style <b>script</b> over Gremlin traversals: statements, variables, and {@code print}.
 *
 * <pre>
 * def ids = g.V(1001, 'Account').values('id').toList()
 * print(ids)
 * </pre>
 *
 * <p>The text area used to accept one traversal only ({@code g.V(...)...}). A script is the same
 * traversals written as a few statements, which is what an analyst types when a question needs a
 * value kept: find these ids, then walk from them, then count the answer. Supported statements:
 *
 * <ul>
 *   <li>{@code def name = <expression>} / {@code name = <expression>} - keep the value;</li>
 *   <li>{@code print(<expression>)} / {@code println(<expression>)} - add it to the output;</li>
 *   <li>{@code return <expression>} - stop and answer with it;</li>
 *   <li>{@code <expression>} - the value of the last such statement is the answer when nothing is printed.</li>
 * </ul>
 *
 * <p>An expression is a traversal ({@code g.V(...)...}, run by the same engine, its subgraph collected), a
 * variable, a variable with a small chain ({@code ids.size()}, {@code ids.toList()}, {@code ids.next()},
 * {@code ids.get(0)}, {@code ids.isEmpty()}), a list ({@code [ids, 5]}), a string, a number, a boolean or
 * {@code null}. A variable may stand for the ids of a traversal: {@code g.V(ids, 'Account')} substitutes
 * them (a list becomes its comma-joined ids, and an element becomes its id), so a query can be split across
 * statements.
 *
 * <p><b>It is not a Groovy runtime.</b> The engine deliberately has no {@code GroovyShell} and no class
 * loading (see {@link GremlinGraphQuery}), so this is a Groovy-<i>shaped</i> subset on the project's own
 * parser: statements, variables, {@code def}, {@code print}, lists and method chains, which is what a query
 * needs - not loops, classes or reflection. An expression it cannot read is refused with a message naming
 * what is supported, rather than silently returning nothing.
 */
public final class GremlinScript {

    /** Runs one traversal and returns its values and the subgraph it touched. */
    @FunctionalInterface
    public interface Runner {
        GremlinGraphQuery.Result run(String traversal);
    }

    /** What one script produced: the answer, what was printed, and the subgraph of every traversal it ran. */
    public record Result(Object value, List<Object> printed, List<String> output, Subgraph subgraph) {

        public Result {
            printed = printed == null ? List.of() : List.copyOf(printed);
            output = output == null ? List.of() : List.copyOf(output);
            subgraph = subgraph == null ? Subgraph.EMPTY : subgraph;
        }
    }

    /**
     * A statement: the flat ones ({@code def}/{@code print}/{@code return}/expression) plus the control flow a
     * Groovy script may use ({@code if}/{@code for}/{@code while}/{@code break}/{@code continue}).
     */
    private sealed interface Stmt {
    }

    private record Assign(String name, String expression, String text) implements Stmt {
    }

    private record Print(String expression, String text) implements Stmt {
    }

    private record Return(String expression, String text) implements Stmt {
    }

    private record Eval(String expression, String text) implements Stmt {
    }

    private record If(String condition, List<Stmt> thenBlock, List<Stmt> elseBlock, String text)
            implements Stmt {
    }

    private record For(String variable, String iterable, List<Stmt> body, String text) implements Stmt {
    }

    private record While(String condition, List<Stmt> body, String text) implements Stmt {
    }

    private record Break() implements Stmt {
    }

    private record Continue() implements Stmt {
    }

    /** {@code assert expr} (with an optional {@code : message}): a failed assertion stops the script. */
    private record Assert(String expression, String message, String text) implements Stmt {
    }

    /** {@code do { ... } while (cond)}: the body runs once before the condition is looked at. */
    private record DoWhile(String condition, List<Stmt> body, String text) implements Stmt {
    }

    /**
     * {@code switch (subject) { case a: ...; default: ... }}. A case matches by {@code ==} (so text
     * compares like everywhere else in a script), the first match wins, and there is no fall-through -
     * a script is written to be read, and a case that silently continued into the next one is the kind of
     * surprise the rest of this grammar refuses.
     */
    private record Switch(String subject, List<SwitchCase> cases, List<Stmt> defaultBody, String text)
            implements Stmt {
    }

    /** One {@code case a, b: ...} of a {@link Switch}. */
    private record SwitchCase(List<String> values, List<Stmt> body) {
    }

    /** A closure: its parameter names ({@code it} when there are none) and its body. */
    private record Closure(List<String> params, List<Stmt> body) {
    }

    /** Control flow leaves a block by throwing; the block executor catches it. */
    private static final class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            this.value = value;
        }
    }

    private static final class BreakSignal extends RuntimeException {
    }

    private static final class ContinueSignal extends RuntimeException {
    }

    private static final Pattern TRAVERSAL = Pattern.compile("^\\s*g\\s*\\.\\s*(?:[VE]|inject)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGN_HEAD = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*\\s*=(?!=)");
    private static final Pattern DEF = Pattern.compile("^def\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=(?!=)(.*)$",
            Pattern.DOTALL);
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)\\s*=(?!=)(.*)$",
            Pattern.DOTALL);
    /** {@code x += 1}: rewritten into {@code x = x + (1)} so one expression evaluator serves both. */
    private static final Pattern COMPOUND_ASSIGN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\+=|-=|\\*=|/=|%=)\\s*(.+)$", Pattern.DOTALL);
    /** {@code x++} / {@code ++x} / {@code x--} / {@code --x}: {@code x = x + 1} to the letter. */
    private static final Pattern INCREMENT = Pattern.compile(
            "^(?:([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\+\\+|--)|(\\+\\+|--)\\s*([A-Za-z_$][A-Za-z0-9_$]*))$");
    private static final Pattern PRINT = Pattern.compile("^(print|println)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL);
    private static final Pattern RETURN = Pattern.compile("^return\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    /** The two built-in functions a query script uses to format text. */
    private static final Pattern BUILTIN_CALL = Pattern.compile("^(sprintf|printf)\\s*\\((.*)\\)\\s*$",
            Pattern.DOTALL);
    private static final Pattern SIMPLE_TOKEN = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    /** A {@code while} that never stops is a typo; the loop is stopped rather than hanging the query. */
    private static final int MAX_LOOP_ITERATIONS = 100_000;

    private final String text;
    private final List<Stmt> statements;

    private GremlinScript(String text, List<Stmt> statements) {
        this.text = text;
        this.statements = List.copyOf(statements);
    }

    /** The script as written. */
    public String text() {
        return text;
    }

    /**
     * Whether the text reads as a script rather than one traversal.
     *
     * <p>It does when a top-level statement declares a variable, prints, returns, assigns - or when the text
     * is more than one statement. A traversal written over several lines is one statement (a line that
     * continues with {@code .} is not a separator), so a plain query is not mistaken for a script.
     */
    public static boolean looksLikeScript(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        List<String> raw = splitStatements(GremlinQuery.stripComments(query));
        for (String statement : raw) {
            String t = statement.trim();
            if (t.startsWith("def ") || t.startsWith("print(") || t.startsWith("println(")
                    || t.startsWith("return ") || t.startsWith("if (") || t.startsWith("if(")
                    || t.startsWith("for (") || t.startsWith("for(") || t.startsWith("while ")
                    || t.startsWith("while(") || t.equals("break") || t.equals("continue")
                    || ASSIGN_HEAD.matcher(t).find()) {
                return true;
            }
        }
        return raw.size() > 1;
    }

    /** Parses a script; the message says which statement could not be read and what was expected. */
    public static GremlinScript parse(String script) {
        if (script == null || script.isBlank()) {
            throw new QueryException("A Gremlin script must not be blank");
        }
        String stripped = GremlinQuery.stripComments(script).trim();
        List<Stmt> statements = parseBlock(stripped);
        if (statements.isEmpty()) {
            throw new QueryException("A Gremlin script must have at least one statement");
        }
        return new GremlinScript(script.trim(), statements);
    }

    /** The statements of one block: the whole script, or the body between a pair of braces. */
    private static List<Stmt> parseBlock(String block) {
        List<Stmt> out = new ArrayList<>();
        for (String raw : splitStatements(block)) {
            String statement = raw.trim();
            if (!statement.isEmpty()) {
                out.add(parseStatement(statement));
            }
        }
        return out;
    }

    /** Runs the script and answers with what was printed (or the last value) and the subgraph. */
    public Result execute(Runner runner) {
        State state = new State();
        try {
            execute(statements, state, runner);
        } catch (ReturnSignal signal) {
            return new Result(signal.value, state.printed, state.output, state.subgraph);
        }
        return result(state);
    }

    /** Runs one block; {@code return}/{@code break}/{@code continue} leave it by throwing. */
    private static void execute(List<Stmt> block, State state, Runner runner) {
        for (Stmt statement : block) {
            if (statement instanceof Assign assign) {
                state.vars.put(assign.name(), evaluate(assign.expression(), state, runner));
                state.last = null;
                state.lastWasPrint = false;
            } else if (statement instanceof Print print) {
                Object value = evaluate(print.expression(), state, runner);
                state.printed.add(value);
                state.output.add(text(value));
                state.last = value;
                state.lastWasPrint = true;
            } else if (statement instanceof Return ret) {
                throw new ReturnSignal(evaluate(ret.expression(), state, runner));
            } else if (statement instanceof Eval eval) {
                state.last = evaluate(eval.expression(), state, runner);
                state.lastWasPrint = false;
            } else if (statement instanceof If branch) {
                execute(truth(branch.condition(), state, runner) ? branch.thenBlock() : branch.elseBlock(),
                        state, runner);
            } else if (statement instanceof For loop) {
                for (Object item : iterable(loop.iterable(), state, runner)) {
                    state.vars.put(loop.variable(), item);
                    try {
                        execute(loop.body(), state, runner);
                    } catch (ContinueSignal ignored) {
                        // on to the next item
                    } catch (BreakSignal ignored) {
                        break;
                    }
                }
            } else if (statement instanceof While loop) {
                int guard = 0;
                while (truth(loop.condition(), state, runner)) {
                    if (++guard > MAX_LOOP_ITERATIONS) {
                        throw new QueryException("The while loop ran " + MAX_LOOP_ITERATIONS
                                + " times; check its condition");
                    }
                    try {
                        execute(loop.body(), state, runner);
                    } catch (ContinueSignal ignored) {
                        // on to the next turn
                    } catch (BreakSignal ignored) {
                        break;
                    }
                }
            } else if (statement instanceof DoWhile loop) {
                int guard = 0;
                do {
                    if (++guard > MAX_LOOP_ITERATIONS) {
                        throw new QueryException("The do/while loop ran " + MAX_LOOP_ITERATIONS
                                + " times; check its condition");
                    }
                    try {
                        execute(loop.body(), state, runner);
                    } catch (ContinueSignal ignored) {
                        // on to the condition
                    } catch (BreakSignal ignored) {
                        break;
                    }
                } while (truth(loop.condition(), state, runner));
            } else if (statement instanceof Switch branch) {
                Object subject = evaluate(branch.subject(), state, runner);
                List<Stmt> chosen = null;
                for (SwitchCase candidate : branch.cases()) {
                    for (String value : candidate.values()) {
                        if (truthy(compareWith(subject, "==", evaluate(value, state, runner)))) {
                            chosen = candidate.body();
                            break;
                        }
                    }
                    if (chosen != null) {
                        break;
                    }
                }
                execute(chosen != null ? chosen : branch.defaultBody(), state, runner);
            } else if (statement instanceof Assert check) {
                if (!truthy(evaluate(check.expression(), state, runner))) {
                    String message = check.message() == null ? check.text()
                            : check.text() + " - " + text(evaluate(check.message(), state, runner));
                    throw new QueryException("Assertion failed: " + message);
                }
            } else if (statement instanceof Break) {
                throw new BreakSignal();
            } else if (statement instanceof Continue) {
                throw new ContinueSignal();
            }
        }
    }

    private static Result result(State state) {
        Object value;
        if (state.lastWasPrint && !state.printed.isEmpty()) {
            // the script ended by printing: that is what the reader asked to see
            value = state.printed.size() == 1 ? state.printed.get(0) : List.copyOf(state.printed);
        } else {
            // the script ended with an expression: that value is the answer, the prints are its output
            value = state.last;
        }
        return new Result(value, state.printed, state.output, state.subgraph);
    }

    /** The values a {@code for (x in ...)} walks. */
    private static List<Object> iterable(String expression, State state, Runner runner) {
        Object value = evaluate(expression, state, runner);
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.entrySet());
        }
        if (value instanceof Object[] array) {
            return new ArrayList<>(java.util.Arrays.asList(array));
        }
        return value == null ? List.of() : List.of(value);
    }

    /** The mutable state of one run: the variables, what was printed, and the last value. */
    private static final class State {
        final Map<String, Object> vars = new LinkedHashMap<>();
        final List<Object> printed = new ArrayList<>();
        final List<String> output = new ArrayList<>();
        Subgraph subgraph = Subgraph.EMPTY;
        Object last;
        boolean lastWasPrint;
    }

    // --- statements ---

    private static Stmt parseStatement(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("if") && nextIsBoundary(trimmed, 2)) {
            return parseIf(trimmed);
        }
        if (trimmed.startsWith("for") && nextIsBoundary(trimmed, 3)) {
            return parseFor(trimmed);
        }
        if (trimmed.startsWith("while") && nextIsBoundary(trimmed, 5)) {
            return parseWhile(trimmed);
        }
        if (trimmed.startsWith("do") && nextIsBoundary(trimmed, 2)) {
            return parseDoWhile(trimmed);
        }
        if (trimmed.startsWith("switch") && nextIsBoundary(trimmed, 6)) {
            return parseSwitch(trimmed);
        }
        if (trimmed.startsWith("assert") && nextIsBoundary(trimmed, 6)) {
            String rest = trimmed.substring(6).trim();
            int colon = topLevelColon(rest);
            return colon < 0 ? new Assert(rest, null, trimmed)
                    : new Assert(rest.substring(0, colon).trim(), rest.substring(colon + 1).trim(), trimmed);
        }
        if (trimmed.equals("break")) {
            return new Break();
        }
        if (trimmed.equals("continue")) {
            return new Continue();
        }
        Matcher def = DEF.matcher(trimmed);
        if (def.matches()) {
            return new Assign(def.group(1), def.group(2).trim(), trimmed);
        }
        Matcher print = PRINT.matcher(trimmed);
        if (print.matches()) {
            return new Print(print.group(2).trim(), trimmed);
        }
        Matcher ret = RETURN.matcher(trimmed);
        if (ret.matches()) {
            return new Return(ret.group(1).trim(), trimmed);
        }
        Matcher increment = INCREMENT.matcher(trimmed);
        if (increment.matches()) {
            // the value of x++ / ++x is not used by a statement, so both spellings rewrite the same way
            String name = increment.group(1) != null ? increment.group(1) : increment.group(4);
            String delta = increment.group(2) != null ? increment.group(2) : increment.group(3);
            return new Assign(name, name + " " + delta.charAt(0) + " 1", trimmed);
        }
        Matcher compound = COMPOUND_ASSIGN.matcher(trimmed);
        if (compound.matches()) {
            String name = compound.group(1);
            String operator = compound.group(2).substring(0, 1);
            return new Assign(name, name + " " + operator + " (" + compound.group(3).trim() + ")", trimmed);
        }
        Matcher assign = ASSIGN.matcher(trimmed);
        if (assign.matches()) {
            return new Assign(assign.group(1), assign.group(2).trim(), trimmed);
        }
        return new Eval(trimmed, trimmed);
    }

    private static boolean nextIsBoundary(String text, int at) {
        return at >= text.length() || !Character.isLetterOrDigit(text.charAt(at));
    }

    /** {@code if (cond) { ... } else { ... }}; an {@code else if} nests. */
    private static Stmt parseIf(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            throw new QueryException("if expects a condition in parentheses: " + text);
        }
        int close = matchingParen(text, open);
        String condition = text.substring(open + 1, close).trim();
        int brace = text.indexOf('{', close);
        if (brace < 0) {
            throw new QueryException("if expects a { ... } block: " + text);
        }
        int end = matchingBrace(text, brace);
        List<Stmt> thenBlock = parseBlock(text.substring(brace + 1, end));
        String rest = text.substring(end + 1).trim();
        List<Stmt> elseBlock = List.of();
        if (rest.startsWith("else")) {
            String after = rest.substring(4).trim();
            if (after.startsWith("if") && nextIsBoundary(after, 2)) {
                elseBlock = List.of(parseIf(after));
            } else {
                int elseBrace = after.indexOf('{');
                if (elseBrace < 0) {
                    throw new QueryException("else expects a { ... } block: " + text);
                }
                elseBlock = parseBlock(after.substring(elseBrace + 1, matchingBrace(after, elseBrace)));
            }
        } else if (!rest.isEmpty()) {
            throw new QueryException("Unexpected '" + rest + "' after the if block in: " + text);
        }
        return new If(condition, thenBlock, elseBlock, text);
    }

    /** {@code for (x in <list>) { ... }}. */
    private static Stmt parseFor(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            throw new QueryException("for expects for (name in list): " + text);
        }
        int close = matchingParen(text, open);
        String header = text.substring(open + 1, close).trim();
        Matcher in = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)\\s+in\\s+(.+)$", Pattern.DOTALL)
                .matcher(header);
        if (!in.matches()) {
            throw new QueryException("for expects for (name in list), got: for (" + header + ")");
        }
        int brace = text.indexOf('{', close);
        if (brace < 0) {
            throw new QueryException("for expects a { ... } block: " + text);
        }
        int end = matchingBrace(text, brace);
        String rest = text.substring(end + 1).trim();
        if (!rest.isEmpty()) {
            throw new QueryException("Unexpected '" + rest + "' after the for block in: " + text);
        }
        return new For(in.group(1), in.group(2).trim(), parseBlock(text.substring(brace + 1, end)), text);
    }

    /** {@code do { ... } while (cond)}. */
    private static Stmt parseDoWhile(String text) {
        int brace = text.indexOf('{');
        if (brace < 0) {
            throw new QueryException("do expects a { ... } block: " + text);
        }
        int end = matchingBrace(text, brace);
        String rest = text.substring(end + 1).trim();
        if (!rest.startsWith("while")) {
            throw new QueryException("do must be followed by 'while (condition)' in: " + text);
        }
        int open = rest.indexOf('(');
        if (open < 0) {
            throw new QueryException("do/while expects a condition in parentheses: " + text);
        }
        int close = matchingParen(rest, open);
        if (!rest.substring(close + 1).trim().isEmpty()) {
            throw new QueryException("Unexpected '" + rest.substring(close + 1).trim()
                    + "' after the do/while condition in: " + text);
        }
        return new DoWhile(rest.substring(open + 1, close).trim(),
                parseBlock(text.substring(brace + 1, end)), text);
    }

    /**
     * {@code switch (subject) { case a: ...; case b, c: ...; default: ... }}.
     *
     * <p>The body is split into statements and then cut at the {@code case}/{@code default} labels, so a
     * label and its first statement may sit on one line (as a Groovy script writes them).
     */
    private static Stmt parseSwitch(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            throw new QueryException("switch expects a subject in parentheses: " + text);
        }
        int close = matchingParen(text, open);
        int brace = text.indexOf('{', close);
        if (brace < 0) {
            throw new QueryException("switch expects a { ... } block: " + text);
        }
        int end = matchingBrace(text, brace);
        String rest = text.substring(end + 1).trim();
        if (!rest.isEmpty()) {
            throw new QueryException("Unexpected '" + rest + "' after the switch block in: " + text);
        }

        List<SwitchCase> cases = new ArrayList<>();
        List<Stmt> defaultBody = new ArrayList<>();
        List<Stmt> current = null;
        List<String> currentValues = new ArrayList<>();
        boolean inDefault = false;
        for (String raw : splitStatements(text.substring(brace + 1, end))) {
            String statement = raw.trim();
            if (statement.isEmpty()) {
                continue;
            }
            String label = labelOf(statement);
            if (label != null) {
                int colon = statement.indexOf(':');
                String head = statement.substring(label.length(), colon).trim();
                String remainder = statement.substring(colon + 1).trim();
                if (label.equals("case")) {
                    if (inDefault) {
                        throw new QueryException("case after default in: " + text);
                    }
                    if (current != null) {
                        cases.add(new SwitchCase(List.copyOf(currentValues), List.copyOf(current)));
                        currentValues = new ArrayList<>();
                    }
                    for (String value : splitArgs(head)) {
                        currentValues.add(value);
                    }
                    current = new ArrayList<>();
                    if (!remainder.isEmpty()) {
                        current.add(parseStatement(remainder));
                    }
                } else {
                    if (current != null) {
                        cases.add(new SwitchCase(List.copyOf(currentValues), List.copyOf(current)));
                        currentValues = new ArrayList<>();
                        current = null;
                    }
                    inDefault = true;
                    if (!remainder.isEmpty()) {
                        defaultBody.add(parseStatement(remainder));
                    }
                    current = defaultBody;
                }
                continue;
            }
            if (current == null) {
                throw new QueryException("A statement before the first case in: " + text);
            }
            current.add(parseStatement(statement));
        }
        if (current != null && !inDefault) {
            cases.add(new SwitchCase(List.copyOf(currentValues), List.copyOf(current)));
        }
        if (cases.isEmpty() && defaultBody.isEmpty()) {
            throw new QueryException("switch has no cases in: " + text);
        }
        return new Switch(text.substring(open + 1, close).trim(), List.copyOf(cases),
                List.copyOf(defaultBody), text);
    }

    /** {@code case}/{@code default} when the statement starts with one of them, else {@code null}. */
    private static String labelOf(String statement) {
        if (statement.startsWith("case") && nextIsBoundary(statement, 4)
                && statement.indexOf(':') > 0) {
            return "case";
        }
        if (statement.startsWith("default") && nextIsBoundary(statement, 7)
                && statement.indexOf(':') > 0) {
            return "default";
        }
        return null;
    }

    /** The index of the first {@code :} at the top level that does not belong to an elvis {@code ?:}. */
    private static int topLevelColon(String text) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ':' && depth == 0 && (i == 0 || text.charAt(i - 1) != '?')) {
                return i;
            }
        }
        return -1;
    }

    /** {@code while (cond) { ... }}. */
    private static Stmt parseWhile(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            throw new QueryException("while expects a condition in parentheses: " + text);
        }
        int close = matchingParen(text, open);
        int brace = text.indexOf('{', close);
        if (brace < 0) {
            throw new QueryException("while expects a { ... } block: " + text);
        }
        int end = matchingBrace(text, brace);
        String rest = text.substring(end + 1).trim();
        if (!rest.isEmpty()) {
            throw new QueryException("Unexpected '" + rest + "' after the while block in: " + text);
        }
        return new While(text.substring(open + 1, close).trim(),
                parseBlock(text.substring(brace + 1, end)), text);
    }

    /**
     * Splits a script on {@code ;} and line breaks that are not inside brackets or strings.
     *
     * <p>A line break followed by {@code .} continues the statement (a traversal written over several lines is
     * one statement), and so does a statement ending in an operator that expects more text.
     */
    private static List<String> splitStatements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            if (depth <= 0 && (c == ';' || c == '\n')) {
                if (c == '\n' && continues(text, i + 1, current)) {
                    current.append(c);
                    continue;
                }
                add(out, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        add(out, current);
        return out;
    }

    /** Whether the line break at {@code from} continues the statement rather than ending it. */
    private static boolean continues(String text, int from, StringBuilder current) {
        String soFar = current.toString().trim();
        if (soFar.isEmpty()) {
            return false;
        }
        char last = soFar.charAt(soFar.length() - 1);
        // `x++` / `x--` end with an operator character but are a whole statement: a trailing '++' does not
        // mean the next line is part of it (only a lone '+' does, as in a multi-line sum)
        if ((last == '+' || last == '-') && soFar.length() >= 2
                && soFar.charAt(soFar.length() - 2) == last) {
            return false;
        }
        if (last == '.' || last == ',' || last == '+' || last == '=' || last == '(' || last == '['
                || last == '{') {
            return true;
        }
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '.';
    }

    private static void add(List<String> out, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            out.add(statement);
        }
    }

    // --- expressions ---

    private static Object evaluate(String expression, State state, Runner runner) {
        String text = expression.trim();
        if (text.isEmpty()) {
            throw new QueryException("A script statement has no expression");
        }
        // elvis before the ternary: `a ?: b` is one token, and `a ? b : c` is the general form
        int elvis = indexOfTopLevelWord(text, "?:");
        if (elvis >= 0) {
            Object value = evaluate(text.substring(0, elvis).trim(), state, runner);
            return truthy(value) ? value : evaluate(text.substring(elvis + 2).trim(), state, runner);
        }
        int question = indexOfTopLevelWord(text, "?");
        if (question >= 0) {
            int colon = indexOfTopLevelWord(text.substring(question + 1), ":");
            if (colon < 0) {
                throw new QueryException("A ternary needs ':' after the '?' in: " + text);
            }
            Object condition = evaluate(text.substring(0, question).trim(), state, runner);
            int colonAt = question + 1 + colon;
            return truthy(condition)
                    ? evaluate(text.substring(question + 1, colonAt).trim(), state, runner)
                    : evaluate(text.substring(colonAt + 1).trim(), state, runner);
        }
        // boolean logic is a value too, and it short-circuits: `p.get(1).value('side') == 'out' && ...`
        int or = indexOfTopLevelWord(text, "||");
        if (or >= 0) {
            return truthy(evaluate(text.substring(0, or).trim(), state, runner))
                    || truthy(evaluate(text.substring(or + 2).trim(), state, runner));
        }
        int and = indexOfTopLevelWord(text, "&&");
        if (and >= 0) {
            return truthy(evaluate(text.substring(0, and).trim(), state, runner))
                    && truthy(evaluate(text.substring(and + 2).trim(), state, runner));
        }
        // Groovy's spaceship: the comparator a sort closure answers with
        int spaceship = indexOfTopLevelWord(text, "<=>");
        if (spaceship >= 0) {
            Object left = evaluate(text.substring(0, spaceship).trim(), state, runner);
            Object right = evaluate(text.substring(spaceship + 3).trim(), state, runner);
            return (long) naturalOrder(left, right);
        }
        // a comparison is a value too (Groovy returns a boolean): `findAll { it % 2 == 0 }`
        for (String operator : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            int index = indexOfTopLevelWord(text, operator);
            if (index >= 0) {
                Object left = evaluate(text.substring(0, index).trim(), state, runner);
                Object right = evaluate(text.substring(index + operator.length()).trim(), state, runner);
                return compareWith(left, operator, right);
            }
        }
        if (text.startsWith("!")) {
            return !truthy(evaluate(text.substring(1), state, runner));
        }
        return additive(text, state, runner);
    }

    /** {@code +}/{@code -}, left to right, over the multiplicative terms. */
    private static Object additive(String text, State state, Runner runner) {
        List<String> operands = new ArrayList<>();
        List<Character> operators = new ArrayList<>();
        scanBinary(text, "+-", operands, operators);
        if (operators.isEmpty()) {
            return multiplicative(text, state, runner);
        }
        Object value = multiplicative(operands.get(0), state, runner);
        for (int i = 0; i < operators.size(); i++) {
            Object right = multiplicative(operands.get(i + 1), state, runner);
            value = operators.get(i) == '+' ? plus(value, right) : minus(value, right);
        }
        return value;
    }

    /** {@code *}/{@code /}/{@code %}, left to right, over the primary expressions. */
    private static Object multiplicative(String text, State state, Runner runner) {
        List<String> operands = new ArrayList<>();
        List<Character> operators = new ArrayList<>();
        scanBinary(text, "*/%", operands, operators);
        if (operators.isEmpty()) {
            return primary(text, state, runner);
        }
        Object value = primary(operands.get(0), state, runner);
        for (int i = 0; i < operators.size(); i++) {
            Object right = primary(operands.get(i + 1), state, runner);
            value = switch (operators.get(i)) {
                case '*' -> times(value, right);
                case '/' -> divide(value, right);
                default -> modulo(value, right);
            };
        }
        return value;
    }

    /** The plain expression: a traversal, a variable (with a chain), or a literal. */
    private static Object primary(String text, State state, Runner runner) {
        // a grouping: (1 + 2) * 3, and a grouped head with a chain: (-3).abs()
        if (text.startsWith("(")) {
            int close = matchingParenthesis(text);
            if (close > 0) {
                Object grouped = evaluate(text.substring(1, close), state, runner);
                String rest = text.substring(close + 1).trim();
                if (rest.isEmpty()) {
                    return grouped;
                }
                if (rest.startsWith(".")) {
                    return applyChain(grouped, rest, state, runner);
                }
            }
        }
        // xs[0] / m['k']: index into what the name (or literal) before the brackets evaluates to
        int bracket = topLevelBracket(text);
        if (bracket > 0 && matchingBracket(text, bracket) == text.length() - 1) {
            Object base = evaluate(text.substring(0, bracket).trim(), state, runner);
            Object index = evaluate(text.substring(bracket + 1, text.length() - 1).trim(), state, runner);
            return indexInto(base, index, text);
        }
        if (TRAVERSAL.matcher(text).find()) {
            String traversal = substitute(text, state.vars);
            GremlinGraphQuery.Result result = runner.run(traversal);
            state.subgraph = merge(state.subgraph, result.subgraph());
            return result.values();
        }
        int dot = chainDot(text);
        if (dot >= 0) {
            String head = text.substring(0, dot).trim();
            Object base;
            if (NAME.matcher(head).matches()) {
                if (!state.vars.containsKey(head)) {
                    throw new QueryException("Unknown script variable '" + head + "' in: " + text);
                }
                base = state.vars.get(head);
            } else {
                base = literal(head, state, runner); // 'abc'.size(), [1, 2, 3].size()
            }
            return applyChain(base, text.substring(dot), state, runner);
        }
        if (NAME.matcher(text).matches()) {
            if (state.vars.containsKey(text)) {
                return state.vars.get(text);
            }
            if (text.equals("true") || text.equals("false") || text.equals("null")) {
                return literal(text, state, runner);
            }
            throw new QueryException("Unknown script variable '" + text
                    + "'. Assign it first: def " + text + " = g.V(...)");
        }
        java.util.regex.Matcher builtin = BUILTIN_CALL.matcher(text);
        if (builtin.matches()) {
            // sprintf('%.2f', x) returns the text; printf(...) prints it and answers null
            List<Object> arguments = new ArrayList<>();
            for (String part : splitArgs(builtin.group(2))) {
                if (!part.isBlank()) {
                    arguments.add(evaluate(part.trim(), state, runner));
                }
            }
            if (arguments.isEmpty()) {
                throw new QueryException(builtin.group(1) + " needs a format string");
            }
            String formatted = String.format(java.util.Locale.ROOT, text(arguments.get(0)),
                    arguments.subList(1, arguments.size()).toArray());
            if (builtin.group(1).equalsIgnoreCase("printf")) {
                state.printed.add(formatted);
                state.output.add(formatted);
                state.last = formatted;
                state.lastWasPrint = true;
                return null;
            }
            return formatted;
        }
        return literal(text, state, runner);
    }

    /** The index of the {@code .} that starts a method chain after a leading variable name, or -1. */
    private static int chainDot(String text) {
        if (text.isEmpty()) {
            return -1;
        }
        char first = text.charAt(0);
        if (first == '\'' || first == '"') {
            // a string literal may carry a method: 'abc'.size()
            int i = 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == first) {
                    i++;
                    break;
                }
                i++;
            }
            return afterHeadDot(text, i);
        }
        if (first == '[') {
            // a list literal may carry a method: [1, 2, 3].size()
            return afterHeadDot(text, matchingBracket(text, 0) + 1);
        }
        int end = 0;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'
                || text.charAt(end) == '$')) {
            end++;
        }
        if (end > 0 && Character.isDigit(text.charAt(0))) {
            // a decimal literal is one token: the dot in 1.5 starts no method chain, the one in 1.5.size() does
            while (end + 1 < text.length() && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) {
                end++;
                while (end < text.length() && Character.isDigit(text.charAt(end))) {
                    end++;
                }
            }
        }
        if (end == 0 || end == text.length() || text.charAt(end) != '.') {
            return -1;
        }
        return end;
    }

    /** The index of the {@code .} after a literal head, or -1. */
    private static int afterHeadDot(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '.' ? i : -1;
    }

    /** Applies {@code .size()} and friends to a variable's value; a {@code { ... }} block is a closure. */
    private static Object applyChain(Object value, String chain, State state, Runner runner) {
        Object current = value;
        String rest = chain.trim();
        while (!rest.isEmpty()) {
            if (!rest.startsWith(".")) {
                throw new QueryException("Expected '.' before the method in: " + rest);
            }
            rest = rest.substring(1).trim();
            int open = indexOfTopLevel(rest, '(');
            int brace = indexOfTopLevel(rest, '{');
            boolean closureSyntax = brace >= 0 && (open < 0 || brace < open);
            String name;
            String args;
            int close;
            if (closureSyntax) {
                // the Groovy spelling with no parentheses: list.each { ... }
                name = rest.substring(0, brace).trim();
                close = matchingBrace(rest, brace);
                args = rest.substring(brace + 1, close).trim();
                current = applyWithClosure(name.toLowerCase(Locale.ROOT), current, parseClosure(args),
                        state, runner);
            } else {
                if (open < 0) {
                    // Groovy's property access: m.a is m['a'] when the value is a map (a bare word after a
                    // dot is only ever a property here, since a method without arguments is written ())
                    if (current instanceof Map<?, ?> map && NAME.matcher(rest).matches()) {
                        current = map.get(rest);
                        break;
                    }
                    throw new QueryException("Expected '(' after '." + rest + "' (or a map property: m.a)");
                }
                name = rest.substring(0, open).trim();
                close = matchingParen(rest, open);
                args = rest.substring(open + 1, close).trim();
                // inject(seed) { ... }: a closure right after the call is part of it, not a new chain link
                int after = close + 1;
                while (after < rest.length() && Character.isWhitespace(rest.charAt(after))) {
                    after++;
                }
                if (after < rest.length() && rest.charAt(after) == '{') {
                    int end = matchingBrace(rest, after);
                    args = (args.isEmpty() ? "" : args + " ") + rest.substring(after, end + 1);
                    close = end;
                }
                current = applyMethod(name, current, args, state, runner);
            }
            rest = rest.substring(close + 1).trim();
        }
        return current;
    }

    /** The index of a bracket at the top level of a method chain (not inside quotes or a nested pair). */
    private static int indexOfTopLevel(String text, char wanted) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                if (c == wanted && depth == 0) {
                    return i;
                }
                depth++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                continue;
            }
            if (depth == 0 && c == wanted) {
                return i;
            }
        }
        return -1;
    }

    /** A closure written as a method argument: {@code { it ... }} or {@code { x -> ... }}. */
    private static Closure parseClosure(String body) {
        String text = body.trim();
        int arrow = indexOfTopLevelWord(text, "->");
        List<String> params = new ArrayList<>();
        if (arrow >= 0) {
            for (String part : splitArgs(text.substring(0, arrow))) {
                if (!part.isBlank()) {
                    params.add(part.trim());
                }
            }
            text = text.substring(arrow + 2).trim();
        }
        if (text.isEmpty()) {
            throw new QueryException("A closure must not be empty");
        }
        return new Closure(List.copyOf(params), parseBlock(text));
    }

    /**
     * The methods that take a closure: Groovy's iteration/collection helpers. The closure is invoked once per
     * item with {@code it} (or its declared parameters) bound, and a {@code return} inside it answers that call.
     */
    private static Object applyWithClosure(String name, Object value, Closure closure, State state,
                                           Runner runner) {
        List<Object> items = value instanceof Collection<?> collection ? new ArrayList<>(collection)
                : (value == null ? List.of() : List.of(value));
        switch (name) {
            case "each": {
                for (Object item : items) {
                    invoke(closure, List.of(item), state, runner);
                }
                return value;
            }
            case "eachwithindex": {
                long index = 0;
                for (Object item : items) {
                    invoke(closure, List.of(item, index++), state, runner);
                }
                return value;
            }
            case "collect": {
                List<Object> out = new ArrayList<>(items.size());
                for (Object item : items) {
                    out.add(invoke(closure, List.of(item), state, runner));
                }
                return out;
            }
            case "findall", "filter": {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    if (truthy(invoke(closure, List.of(item), state, runner))) {
                        out.add(item);
                    }
                }
                return out;
            }
            case "find": {
                for (Object item : items) {
                    if (truthy(invoke(closure, List.of(item), state, runner))) {
                        return item;
                    }
                }
                return null;
            }
            case "any": {
                for (Object item : items) {
                    if (truthy(invoke(closure, List.of(item), state, runner))) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            case "every": {
                for (Object item : items) {
                    if (!truthy(invoke(closure, List.of(item), state, runner))) {
                        return Boolean.FALSE;
                    }
                }
                return Boolean.TRUE;
            }
            case "count": {
                long count = 0;
                for (Object item : items) {
                    if (truthy(invoke(closure, List.of(item), state, runner))) {
                        count++;
                    }
                }
                return count;
            }
            case "sum": {
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (Object item : items) {
                    total = total.add(new java.math.BigDecimal(
                            text(invoke(closure, List.of(item), state, runner))));
                }
                return total.stripTrailingZeros().scale() <= 0 ? (Object) total.longValueExact() : total;
            }
            case "sort": {
                List<Object> out = new ArrayList<>(items);
                out.sort((left, right) -> ordering(left, right, closure, state, runner));
                return out;
            }
            case "max", "min": {
                boolean max = name.equals("max");
                Object best = null;
                for (Object item : items) {
                    if (best == null
                            || (ordering(best, item, closure, state, runner) < 0) == max) {
                        best = item;
                    }
                }
                return best;
            }
            case "groupby": {
                Map<String, List<Object>> groups = new LinkedHashMap<>();
                for (Object item : items) {
                    groups.computeIfAbsent(text(invoke(closure, List.of(item), state, runner)),
                            key -> new ArrayList<>()).add(item);
                }
                return new LinkedHashMap<String, Object>(groups);
            }
            case "collectentries": {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object item : items) {
                    Object entry = invoke(closure, List.of(item), state, runner);
                    if (entry instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            out.put(text(e.getKey()), e.getValue());
                        }
                    } else if (entry instanceof List<?> pair && pair.size() == 2) {
                        out.put(text(pair.get(0)), pair.get(1));
                    } else {
                        throw new QueryException("collectEntries() needs each closure call to answer a"
                                + " [key, value] pair or a map, got: " + text(entry));
                    }
                }
                return out;
            }
            default:
                throw new QueryException("Unknown closure method '." + name + ". Supported: each(), "
                        + "eachWithIndex(), collect(), findAll(), find(), any(), every(), count(), sum(), "
                        + "sort(), max(), min(), groupBy(), collectEntries()");
        }
    }

    /**
     * The order of two items under a closure: Groovy's comparator may answer a number ({@code a <=> b}),
     * a boolean ({@code a < b}) or a truthy value, so both are read - the shapes a script really writes.
     */
    private static int ordering(Object left, Object right, Closure closure, State state, Runner runner) {
        Object verdict = invoke(closure, List.of(left, right), state, runner);
        if (verdict instanceof Number number) {
            return Double.compare(number.doubleValue(), 0) < 0 ? -1
                    : number.doubleValue() > 0 ? 1 : 0;
        }
        if (truthy(verdict)) {
            return -1; // `{ a, b -> a < b }` means "left comes first"
        }
        return 1;
    }

    /** The values of a collection-like value: a collection, an array, or the value itself. */
    private static List<Object> items(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Object[] array) {
            return new ArrayList<>(List.of(array));
        }
        return value == null ? List.of() : new ArrayList<>(List.of(value));
    }

    /** A number as a double, or an error naming what was not one. */
    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException e) {
            throw new QueryException("Expected a number, got: " + text(value));
        }
    }

    /** Natural order of two values: numbers by value, everything else by its text form. */
    private static int naturalOrder(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return text(left).compareTo(text(right));
    }

    /** Runs a closure for one set of arguments: parameters shadow the outer scope and are restored after. */
    private static Object invoke(Closure closure, List<Object> arguments, State state, Runner runner) {
        List<String> names = closure.params().isEmpty() ? List.of("it") : closure.params();
        Map<String, Object> saved = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            saved.put(name, state.vars.containsKey(name) ? state.vars.get(name) : null);
            state.vars.put(name, i < arguments.size() ? arguments.get(i) : null);
        }
        try {
            execute(closure.body(), state, runner);
        } catch (ReturnSignal signal) {
            return signal.value;
        } finally {
            for (String name : names) {
                Object previous = saved.get(name);
                if (previous == null) {
                    state.vars.remove(name);
                } else {
                    state.vars.put(name, previous);
                }
            }
        }
        return state.last;
    }

    /** The closure an argument text is, or {@code null} when the argument is an ordinary expression. */
    private static Closure closureArgument(String args) {
        String text = args == null ? "" : args.trim();
        if (!text.startsWith("{")) {
            return null;
        }
        int end = matchingBrace(text, 0);
        if (end != text.length() - 1) {
            throw new QueryException("Unexpected text after the closure: " + text);
        }
        return parseClosure(text.substring(1, end));
    }

    /**
     * One method on a variable's value: the list helpers a query needs (count, read, join two sets of paths,
     * and so on). Collection methods answer with a new list, so the script assigns the result rather than
     * relying on a hidden mutation.
     */
    private static Object applyMethod(String name, Object value, String args, State state, Runner runner) {
        String method = name.toLowerCase(Locale.ROOT);
        if (method.equals("inject")) {
            // inject(seed) { a, b -> ... }: the seed comes before the closure, so this call is handled
            // before the general "has a closure" route (which has no arguments of its own)
            int brace = indexOfTopLevel(args, '{');
            // the closure is the part in braces; the seed is what comes before it
            Closure fold = brace < 0 ? null
                    : parseClosure(args.substring(brace + 1, matchingBrace(args, brace)));
            if (fold == null) {
                throw new QueryException("inject(seed) { carry, x -> ... } needs a closure");
            }
            String seedText = brace < 0 ? args : args.substring(0, brace).replace(",", " ").trim();
            Object carry = seedText.isEmpty() ? null : evaluate(seedText, state, runner);
            for (Object item : items(value)) {
                carry = invoke(fold, new ArrayList<>(List.of(carry, item)), state, runner);
            }
            return carry;
        }
        Closure closure = closureArgument(args);
        if (closure != null) {
            return applyWithClosure(method, value, closure, state, runner);
        }
        Collection<?> collection = value instanceof Collection<?> c ? c : null;
        List<Object> list = collection == null ? null : new ArrayList<>(collection);
        switch (method) {
            case "size", "count":
                if (value instanceof CharSequence sequence) {
                    return (long) sequence.length();
                }
                if (value instanceof Map<?, ?> map) {
                    return (long) map.size();
                }
                if (value instanceof Object[] array) {
                    return (long) array.length;
                }
                return collection == null ? (value == null ? 0L : 1L) : (long) collection.size();
            case "tolist":
                return collection == null ? new ArrayList<>(value == null ? List.of() : List.of(value))
                        : new ArrayList<>(collection);
            case "toset":
                return collection == null ? new LinkedHashSet<>(value == null ? List.of() : List.of(value))
                        : new LinkedHashSet<>(collection);
            case "isempty":
                return isEmptyValue(value);
            case "next", "first":
                return list == null || list.isEmpty() ? null : list.get(0);
            case "last":
                return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
            case "start":
                return value instanceof GremlinPath path ? path.start()
                        : (list == null || list.isEmpty() ? null : list.get(0));
            case "end":
                return value instanceof GremlinPath path ? path.end()
                        : (list == null || list.isEmpty() ? null : list.get(list.size() - 1));
            case "reverse": {
                List<Object> out = list == null ? new ArrayList<>() : list;
                java.util.Collections.reverse(out);
                return out;
            }
            case "get": {
                if (list == null) {
                    throw new QueryException(".get(i) needs a list, got "
                            + (value == null ? "null" : value.getClass().getSimpleName()));
                }
                Object indexArg = evaluate(args, state, runner);
                if (!(indexArg instanceof Number number)) {
                    throw new QueryException(".get(i) needs a whole number, got " + indexArg);
                }
                int index = number.intValue();
                if (index < 0 || index >= list.size()) {
                    throw new QueryException(".get(" + index + ") is outside the list of " + list.size()
                            + " item(s)");
                }
                return list.get(index);
            }
            case "contains":
                return collection != null && collection.contains(evaluate(args, state, runner));
            case "indexof":
                return list == null ? -1L : (long) list.indexOf(evaluate(args, state, runner));
            case "add": {
                List<Object> out = list == null ? new ArrayList<>() : list;
                out.add(evaluate(args, state, runner));
                return out;
            }
            case "addall": {
                List<Object> out = list == null ? new ArrayList<>() : list;
                Object other = evaluate(args, state, runner);
                if (other instanceof Collection<?> items) {
                    out.addAll(items);
                } else if (other != null) {
                    out.add(other);
                }
                return out;
            }
            case "retainall", "intersect": {
                // the common values, each once: the question is which ids both sides reached
                List<Object> out = new ArrayList<>();
                Set<Object> seen = new LinkedHashSet<>();
                Object other = evaluate(args, state, runner);
                if (list != null && other instanceof Collection<?> items) {
                    for (Object item : list) {
                        if (items.contains(item) && seen.add(item)) {
                            out.add(item);
                        }
                    }
                }
                return out;
            }
            case "distinct": {
                if (list == null) {
                    return value;
                }
                return new ArrayList<>(new LinkedHashSet<>(list));
            }
            // --- collection helpers a query script reaches for --------------------------------------
            case "unique": {
                if (list == null) {
                    return value;
                }
                return new ArrayList<>(new LinkedHashSet<>(list));
            }
            case "flatten": {
                List<Object> out = new ArrayList<>();
                for (Object item : items(value)) {
                    if (item instanceof Collection<?> nested) {
                        out.addAll(nested);
                    } else if (item instanceof Object[] array) {
                        out.addAll(List.of(array));
                    } else {
                        out.add(item);
                    }
                }
                return out;
            }
            case "take", "drop": {
                long count = (long) number(evaluate(args, state, runner));
                List<Object> items = items(value);
                int from = name.equals("take") ? 0 : (int) Math.max(0, Math.min(count, items.size()));
                int to = name.equals("take") ? (int) Math.max(0, Math.min(count, items.size()))
                        : items.size();
                return new ArrayList<>(items.subList(from, to));
            }
            case "withindex": {
                List<Object> out = new ArrayList<>();
                List<Object> items = items(value);
                for (int i = 0; i < items.size(); i++) {
                    out.add(new ArrayList<>(List.of(items.get(i), (long) i)));
                }
                return out;
            }
            case "tosorted", "sort": {
                List<Object> out = new ArrayList<>(items(value));
                out.sort(GremlinScript::naturalOrder);
                return out;
            }
            case "max", "min": {
                List<Object> items = items(value);
                Object best = null;
                for (Object item : items) {
                    if (best == null || (naturalOrder(best, item) < 0) == name.equals("max")) {
                        best = item;
                    }
                }
                return best;
            }
            case "sum": {
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (Object item : items(value)) {
                    total = total.add(new java.math.BigDecimal(text(item)));
                }
                return total.stripTrailingZeros().scale() <= 0 ? (Object) total.longValueExact() : total;
            }
            case "inject": {
                throw new QueryException("inject(seed) { ... } is handled before this switch");
            }
            // --- text and number helpers ----------------------------------------------------------
            case "touppercase":
                return text(value).toUpperCase(java.util.Locale.ROOT);
            case "tolowercase":
                return text(value).toLowerCase(java.util.Locale.ROOT);
            case "trim":
                return text(value).trim();
            case "length":
                return (long) text(value).length();
            case "startswith":
                return text(value).startsWith(text(evaluate(args, state, runner)));
            case "endswith":
                return text(value).endsWith(text(evaluate(args, state, runner)));
            case "substring": {
                List<String> bounds = splitArgs(args);
                String source = text(value);
                int from = (int) number(evaluate(bounds.get(0), state, runner));
                int to = bounds.size() > 1 ? (int) number(evaluate(bounds.get(1), state, runner))
                        : source.length();
                if (from < 0 || to > source.length() || from > to) {
                    throw new QueryException("substring(" + from + ", " + to + ") is outside a string of "
                            + source.length() + " characters");
                }
                return source.substring(from, to);
            }
            case "replace": {
                List<String> parts = splitArgs(args);
                if (parts.size() != 2) {
                    throw new QueryException("replace expects replace(from, to)");
                }
                return text(value).replace(text(evaluate(parts.get(0), state, runner)),
                        text(evaluate(parts.get(1), state, runner)));
            }
            case "split": {
                String[] pieces = text(value).split(java.util.regex.Pattern.quote(
                        text(evaluate(args, state, runner))), -1);
                return new ArrayList<Object>(List.of(pieces));
            }
            case "abs": {
                // keep the type: an integer's absolute value is an integer (Groovy's rule)
                if (value instanceof Number number) {
                    return isIntegral(number) ? (Object) Math.abs(number.longValue())
                            : (Object) Math.abs(number.doubleValue());
                }
                return Math.abs(number(value));
            }
            case "intvalue", "tointeger":
                return (long) number(value);
            case "todouble":
                return number(value);
            case "round":
                return Math.round(number(value));
            case "id":
                return value instanceof GremlinElement element ? element.id() : value;
            case "label":
                return value instanceof GremlinElement element ? element.label() : value;
            case "value": {
                if (!(value instanceof GremlinElement element)) {
                    throw new QueryException(".value(key) needs a vertex or an edge, got " + text(value));
                }
                // the same resolver the traversal language uses, so the pseudo keys (id, label,
                // createdTime, source, target, side) read here too
                return GremlinElementExpression.value(element, text(evaluate(args, state, runner)));
            }
            case "values": {
                if (!(value instanceof GremlinElement element)) {
                    throw new QueryException(".values(keys) needs a vertex or an edge, got " + text(value));
                }
                List<Object> out = new ArrayList<>();
                for (String key : splitArgs(args)) {
                    out.add(GremlinElementExpression.value(element, text(evaluate(key, state, runner))));
                }
                return out;
            }
            case "properties", "valuemap": {
                if (!(value instanceof GremlinElement element)) {
                    throw new QueryException(".properties() needs a vertex or an edge, got " + text(value));
                }
                return new LinkedHashMap<String, Object>(element.properties());
            }
            case "join": {
                if (list == null) {
                    return text(value);
                }
                String separator = args.isEmpty() ? "," : text(evaluate(args, state, runner));
                List<String> parts = new ArrayList<>(list.size());
                for (Object item : list) {
                    parts.add(text(item));
                }
                return String.join(separator, parts);
            }
            case "joinonend": {
                List<String> parts = splitArgs(args);
                if (parts.isEmpty()) {
                    throw new QueryException(".joinOnEnd(paths[, middleObjects]) needs the other path list");
                }
                Object other = evaluate(parts.get(0), state, runner);
                Object middles = parts.size() > 1 ? evaluate(parts.get(1), state, runner) : null;
                return joinOnEnd(value, other, middles);
            }
            case "endids": {
                // the vertex each path ends at: the last element if it is a vertex, otherwise the far end
                // of the last edge - which is why a two-hop walk whose second hop is outE() needs no vertex read
                Collection<?> paths = value instanceof GremlinPath ? List.of(value) : collection;
                List<Object> out = new ArrayList<>();
                Set<Object> seen = new LinkedHashSet<>();
                if (paths != null) {
                    for (Object item : paths) {
                        GremlinPath path = item instanceof GremlinPath p ? p
                                : (item instanceof List<?> elements
                                        ? new GremlinPath(new ArrayList<>(elements)) : null);
                        if (path == null) {
                            continue;
                        }
                        String id = pathEndId(path);
                        if (id != null && seen.add(id)) {
                            out.add(id);
                        }
                    }
                }
                return out;
            }
            default:
                throw new QueryException("Unknown script method '." + name + "'. Supported: size(), count(), "
                        + "toList(), toSet(), isEmpty(), next(), first(), last(), start(), end(), reverse(), "
                        + "get(i), contains(x), indexOf(x), add(x), addAll(xs), retainAll(xs), intersect(xs), "
                        + "distinct(), id(), label(), value(k), values(k...), properties(), join(sep), "
                        + "endIds(), joinOnEnd(paths[, middleObjects]), unique(), flatten(), take(n), drop(n), "
                        + "withIndex(), toSorted(), sort(), max(), min(), sum(), inject(seed) { ... }, "
                        + "toUpperCase(), toLowerCase(), trim(), length(), startsWith(s), endsWith(s), "
                        + "substring(from[, to]), replace(from, to), split(sep), abs(), intValue(), "
                        + "toInteger(), toDouble(), round()");
        }
    }

    /**
     * Joins two lists of paths on the vertex they end at: every left path ending where a right path ends becomes
     * one longer path. This is how a four-hop walk is assembled from a two-hop walk from each side - the shared
     * vertex appears once, the right half is walked backwards, and a walk that meets a vertex twice (a cycle) is
     * dropped.
     *
     * <p>A half whose second hop was {@code outE()} ends on an <em>edge</em>; its end is that edge's far vertex,
     * and the shared vertex is taken from {@code middleObjects} (the vertices step 2 looked up) so the joint is a
     * real vertex object rather than just an id.
     */
    private static List<Object> joinOnEnd(Object leftValue, Object rightValue, Object middleValue) {
        List<GremlinPath> left = asPaths(leftValue, "the receiver");
        List<GremlinPath> right = asPaths(rightValue, "the argument");
        Map<String, Object> middles = indexById(middleValue);

        // index the right walks by the vertex they end at, so each left walk only meets its own group
        // (the first version compared every pair: |left| * |right| instead of |left| + |right|)
        Map<String, List<GremlinPath>> rightByEnd = new LinkedHashMap<>();
        for (GremlinPath fromRight : right) {
            String end = pathEndId(fromRight);
            if (end != null) {
                rightByEnd.computeIfAbsent(end, key -> new ArrayList<>()).add(fromRight);
            }
        }

        List<Object> out = new ArrayList<>();
        for (GremlinPath fromLeft : left) {
            String leftEnd = pathEndId(fromLeft);
            List<GremlinPath> candidates = leftEnd == null ? null : rightByEnd.get(leftEnd);
            if (candidates == null) {
                continue;
            }
            for (GremlinPath fromRight : candidates) {
                List<Object> joined = new ArrayList<>(fromLeft);
                boolean vertexEnd = fromLeft.end() instanceof GremlinVertex;
                if (!vertexEnd) {
                    Object middle = middles.get(leftEnd);
                    if (middle != null) {
                        joined.add(middle);
                    }
                }
                List<Object> tail = new ArrayList<>(fromRight);
                java.util.Collections.reverse(tail);
                if (vertexEnd) {
                    tail.remove(0); // the shared vertex is already the end of the left half
                }
                joined.addAll(tail);
                if (repeatsAVertex(joined)) {
                    continue;
                }
                out.add(new GremlinPath(joined));
            }
        }
        return out;
    }

    /** The vertex a path ends at: the last element when it is a vertex, else the far end of the last edge. */
    private static String pathEndId(GremlinPath path) {
        Object end = path.end();
        if (end instanceof GremlinEdge edge && edge.otherVertexId() != null) {
            return String.valueOf(edge.otherVertexId());
        }
        return end == null ? null : elementId(end);
    }

    /** The looked-up vertices by id, so a joined walk can put the object in the middle. */
    private static Map<String, Object> indexById(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof GremlinVertex vertex) {
                    out.putIfAbsent(String.valueOf(vertex.id()), vertex);
                }
            }
        }
        return out;
    }

    private static List<GremlinPath> asPaths(Object value, String what) {
        if (!(value instanceof Collection<?> collection)) {
            throw new QueryException(".joinOnEnd(" + what + ") needs a list of paths, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        List<GremlinPath> out = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item instanceof GremlinPath path) {
                out.add(path);
            } else if (item instanceof List<?> elements) {
                out.add(new GremlinPath(new ArrayList<>(elements)));
            } else {
                throw new QueryException(".joinOnEnd(" + what + ") needs paths, got " + item);
            }
        }
        return out;
    }

    /** {@code +}: numbers add, anything else is joined as text (Groovy's rule for a query script). */
    private static Object plus(Object left, Object right) {
        // Groovy's +, in Groovy's order: a String on either side makes text (so 'ids: ' + list still reads
        // as a label followed by the list); a List appends (which is why count() + count() is [3, 9] and
        // not 6 - a traversal answers with a list, so arithmetic on one value needs first()/get(0));
        // numbers add; anything else joins as text.
        if (left instanceof String || right instanceof String) {
            return text(left) + text(right);
        }
        if (left instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list);
            if (right instanceof List<?> other) {
                out.addAll(other);
            } else {
                out.add(right);
            }
            return out;
        }
        if (right instanceof List<?> other) {
            List<Object> out = new ArrayList<>();
            out.add(left);
            out.addAll(other);
            return out;
        }
        if (left instanceof Number a && right instanceof Number b) {
            if (isIntegral(a) && isIntegral(b)) {
                return a.longValue() + b.longValue();
            }
            return a.doubleValue() + b.doubleValue();
        }
        return text(left) + text(right);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte;
    }

    /** Groovy {@code isEmpty()} over strings, collections, maps and arrays. */
    private static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Object[] array) {
            return array.length == 0;
        }
        return false;
    }

    /** {@code -}: numbers only (Groovy would throw too when the left side is a string). */
    private static Object minus(Object left, Object right) {
        Number a = requireNumber(left, "-");
        Number b = requireNumber(right, "-");
        return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() - b.longValue())
                : a.doubleValue() - b.doubleValue();
    }

    private static Object times(Object left, Object right) {
        Number a = requireNumber(left, "*");
        Number b = requireNumber(right, "*");
        return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() * b.longValue())
                : a.doubleValue() * b.doubleValue();
    }

    private static Object divide(Object left, Object right) {
        return requireNumber(left, "/").doubleValue() / requireNumber(right, "/").doubleValue();
    }

    private static Object modulo(Object left, Object right) {
        Number a = requireNumber(left, "%");
        Number b = requireNumber(right, "%");
        return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() % b.longValue())
                : a.doubleValue() % b.doubleValue();
    }

    private static Number requireNumber(Object value, String operator) {
        if (value instanceof Number number) {
            return number;
        }
        throw new QueryException("The '" + operator + "' operator needs numbers, got " + text(value));
    }

    /**
     * Scans a text for a binary operator at the top level, filling the operands around it. A sign that starts
     * an operand (after {@code (} or another operator) is part of the number, not a subtraction.
     */
    private static void scanBinary(String text, String operators, List<String> operands,
                                   List<Character> found) {
        char quote = 0;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                continue;
            }
            if (depth == 0 && operators.indexOf(c) >= 0 && isBinaryPosition(text, i)) {
                operands.add(text.substring(start, i).trim());
                found.add(c);
                start = i + 1;
            }
        }
        operands.add(text.substring(start).trim());
    }

    /** Whether the character at {@code at} has an operand on its left (so it is a binary operator). */
    private static boolean isBinaryPosition(String text, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char previous = text.charAt(i);
        return previous != '(' && previous != '[' && previous != ','
                && "+-*/%=<>!&|".indexOf(previous) < 0;
    }

    /** Splits on a top-level operator, ignoring quotes and brackets (a traversal's own text is kept whole). */
    private static List<String> splitTopLevel(String text, char operator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            if (c == operator && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString().trim());
        return parts;
    }

    /**
     * The truth of a Groovy condition: {@code &&}/{@code ||}/{@code !}, comparisons ({@code == != < <= > >=}),
     * parentheses, and otherwise the value's Groovy truthiness (null, false, 0, an empty string/collection are
     * false). Each side of a comparison is a normal script expression, so {@code g.V(...).count() > 0} works.
     */
    private static boolean truth(String expression, State state, Runner runner) {
        String text = expression.trim();
        if (text.isEmpty()) {
            throw new QueryException("A condition must not be blank");
        }
        List<String> ors = splitTopLevelWord(text, "||");
        if (ors.size() > 1) {
            for (String part : ors) {
                if (truth(part, state, runner)) {
                    return true;
                }
            }
            return false;
        }
        List<String> ands = splitTopLevelWord(text, "&&");
        if (ands.size() > 1) {
            for (String part : ands) {
                if (!truth(part, state, runner)) {
                    return false;
                }
            }
            return true;
        }
        if (text.startsWith("!")) {
            return !truth(text.substring(1), state, runner);
        }
        if (text.startsWith("(") && matchingParen(text, 0) == text.length() - 1) {
            return truth(text.substring(1, text.length() - 1), state, runner);
        }
        for (String operator : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            int index = indexOfTopLevelWord(text, operator);
            if (index >= 0) {
                Object left = evaluate(text.substring(0, index).trim(), state, runner);
                Object right = evaluate(text.substring(index + operator.length()).trim(), state, runner);
                return compareWith(left, operator, right);
            }
        }
        return truthy(evaluate(text, state, runner));
    }

    /** Groovy truthiness: null, false, zero, an empty string/collection/map are false. */
    private static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static boolean compareWith(Object left, String operator, Object right) {
        int comparison;
        if (left instanceof Number a && right instanceof Number b) {
            comparison = Double.compare(a.doubleValue(), b.doubleValue());
        } else if (left == null || right == null) {
            comparison = left == right ? 0 : (left == null ? -1 : 1);
        } else {
            comparison = String.valueOf(left).compareTo(String.valueOf(right));
        }
        return switch (operator) {
            case "==" -> comparison == 0;
            case "!=" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new QueryException("Unknown comparison operator '" + operator + "'");
        };
    }

    /** The index of a top-level word/operator, ignoring quotes and brackets. */
    private static int indexOfTopLevelWord(String text, String word) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i + word.length() <= text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && text.startsWith(word, i)) {
                return i;
            }
        }
        return -1;
    }

    /** Splits on a top-level word/operator, ignoring quotes and brackets. */
    private static List<String> splitTopLevelWord(String text, String word) {
        List<String> out = new ArrayList<>();
        char quote = 0;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                continue;
            }
            if (depth == 0 && text.startsWith(word, i)) {
                out.add(text.substring(start, i).trim());
                start = i + word.length();
                i += word.length() - 1;
            }
        }
        out.add(text.substring(start).trim());
        return out;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new QueryException("Unbalanced braces in: " + s);
    }

    /** The id a path element stands for (a vertex id, an edge id, or the value itself). */
    private static String elementId(Object element) {
        if (element instanceof GremlinVertex vertex) {
            return String.valueOf(vertex.id());
        }
        if (element instanceof GremlinEdge edge) {
            return String.valueOf(edge.id());
        }
        return String.valueOf(element);
    }

    /** Whether a joined walk visits a vertex twice: that is a cycle, not a simple walk. */
    private static boolean repeatsAVertex(List<Object> path) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object element : path) {
            if (element instanceof GremlinVertex vertex && !seen.add(String.valueOf(vertex.id()))) {
                return true;
            }
        }
        return false;
    }

    private static Object literal(String text, State state, Runner runner) {
        if (text.startsWith("[")) {
            // [a, b] is a list; [a: 1, b: 2] is a map (Groovy's two uses of the same brackets)
            return mapOrList(text, state, runner);
        }
        // a triple-quoted string is one value, newlines included
        if (text.length() >= 6 && (text.startsWith("'''") || text.startsWith("\"\"\""))) {
            String quote = text.substring(0, 3);
            if (!text.endsWith(quote)) {
                throw new QueryException("A triple-quoted string has to be closed with " + quote + " in: "
                        + text);
            }
            return text.substring(3, text.length() - 3);
        }
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')) {
            int close = closingQuote(text);
            if (close != text.length() - 1) {
                // before this check, `'abc' =~ 'b'` was read as one string (the outer quotes were
                // stripped) - a silent answer to a question the engine cannot ask. A string has to be
                // one value; anything after it is an operator this engine does not have.
                throw new QueryException("Cannot read the script value '" + text + "': a string has to be"
                        + " one value. (There is no regular-expression support: compare with"
                        + " filter(key.equals('x')) or has(key, containing('x')).)");
            }
            return text.substring(1, close).replace("\\'", "'").replace("\\\"", "\"");
        }
        if (text.equals("true")) {
            return Boolean.TRUE;
        }
        if (text.equals("false")) {
            return Boolean.FALSE;
        }
        if (text.equals("null")) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // not a whole number
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            // not a number either
        }
        throw new QueryException("Cannot read the script value '" + text + "'. A value is a traversal "
                + "(g.V(...)), a variable, a list, a string, a number, true/false or null");
    }

    /** A map key as written: {@code 'a'} and {@code a} name the same key. */
    private static String unquote(String token) {
        String text = token.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if ((first == '\'' || first == '"') && text.charAt(text.length() - 1) == first) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    /** The index of the quote that closes the string starting at 0, or -1 when it is never closed. */
    private static int closingQuote(String text) {
        char quote = text.charAt(0);
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code [a, b]} (a list) or {@code [a: 1, b: 2]} (a map): Groovy tells them apart by whether the
     * entries are {@code key: value} pairs, so this does too - all entries or none, and a script that
     * mixes them gets told rather than silently read as one of the two.
     */
    private static Object mapOrList(String text, State state, Runner runner) {
        int close = matchingBracket(text, 0);
        String body = text.substring(1, close).trim();
        if (body.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> parts = splitArgs(body);
        boolean anyMap = false;
        for (String part : parts) {
            if (topLevelColon(part) >= 0) {
                anyMap = true;
            }
        }
        if (!anyMap) {
            return list(text, state, runner);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String part : parts) {
            int colon = topLevelColon(part);
            if (colon < 0) {
                throw new QueryException("Cannot read the script value '" + text + "': a map literal needs"
                        + " a key: value in every entry, and '" + part.trim() + "' has none");
            }
            map.put(unquote(part.substring(0, colon).trim()),
                    evaluate(part.substring(colon + 1).trim(), state, runner));
        }
        return map;
    }

    /** The index of the first {@code [} that is not inside brackets or a string, or -1. */
    private static int topLevelBracket(String text) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == '[' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code xs[i]} / {@code m['k']}: list, array, text and map indexing, with a negative index counting
     * from the end (Groovy's rule) and an out-of-range index reported rather than answered with null.
     */
    private static Object indexInto(Object base, Object index, String text) {
        if (base instanceof Map<?, ?> map) {
            return map.get(index instanceof String key ? key : String.valueOf(index));
        }
        long at = index instanceof Number number ? number.longValue() : 0;
        if (!(index instanceof Number)) {
            throw new QueryException("Cannot index with '" + index + "': a list index has to be a number"
                    + " in: " + text);
        }
        if (base instanceof String string) {
            int i = at < 0 ? string.length() + (int) at : (int) at;
            if (i < 0 || i >= string.length()) {
                throw new QueryException("Index " + at + " is outside a string of " + string.length()
                        + " characters in: " + text);
            }
            return String.valueOf(string.charAt(i));
        }
        List<?> list = base instanceof List<?> items ? items
                : base instanceof Object[] array ? List.of(array) : null;
        if (list == null) {
            throw new QueryException("Cannot index " + (base == null ? "null"
                    : base.getClass().getSimpleName()) + " in: " + text);
        }
        int i = at < 0 ? list.size() + (int) at : (int) at;
        if (i < 0 || i >= list.size()) {
            throw new QueryException("Index " + at + " is outside a list of " + list.size()
                    + " items in: " + text);
        }
        return list.get(i);
    }

    private static List<Object> list(String text, State state, Runner runner) {
        int close = matchingBracket(text, 0);
        String body = text.substring(1, close).trim();
        List<Object> items = new ArrayList<>();
        if (body.isEmpty()) {
            return items;
        }
        for (String part : splitArgs(body)) {
            items.add(evaluate(part, state, runner));
        }
        return items;
    }

    // --- variables inside a traversal ---

    /**
     * Replaces a variable that stands for the ids in {@code g.V(...)} / {@code g.E(...)}: a list becomes its
     * comma-joined ids and an element becomes its id, so {@code def ids = g.V(...).values('id'); g.V(ids,
     * 'Account')} walks from the ids the first statement found.
     */
    private static String substitute(String text, Map<String, Object> vars) {
        if (vars.isEmpty()) {
            return text;
        }
        Matcher head = Pattern.compile("g\\s*\\.\\s*[VE]\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!head.find()) {
            return text;
        }
        int open = head.end() - 1;
        int close = matchingParen(text, open);
        List<String> parts = splitArgs(text.substring(open + 1, close));
        List<String> rebuilt = new ArrayList<>(parts.size());
        for (String part : parts) {
            String token = part.trim();
            if (NAME.matcher(token).matches() && vars.containsKey(token)) {
                Object value = vars.get(token);
                if (value instanceof Collection<?> collection && collection.isEmpty()) {
                    throw new QueryException("The script variable '" + token
                            + "' is empty, so there is no id to walk from");
                }
                rebuilt.add(expand(value));
            } else {
                rebuilt.add(token);
            }
        }
        return text.substring(0, open + 1) + String.join(", ", rebuilt) + text.substring(close);
    }

    private static String expand(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> parts = new ArrayList<>(collection.size());
            for (Object item : collection) {
                parts.add(idToken(idOf(item)));
            }
            return String.join(",", parts);
        }
        return idToken(idOf(value));
    }

    /** The id an element stands for; anything else is itself. */
    private static Object idOf(Object value) {
        if (value instanceof GremlinVertex vertex) {
            return vertex.id();
        }
        if (value instanceof GremlinEdge edge) {
            return edge.id();
        }
        return value;
    }

    private static String idToken(Object id) {
        String token = text(id);
        if (SIMPLE_TOKEN.matcher(token).matches()) {
            return token;
        }
        return "'" + token.replace("'", "\\'") + "'";
    }

    // --- small text helpers ---

    private static Subgraph merge(Subgraph left, Subgraph right) {
        if (right == null || (right.vertices().isEmpty() && right.edges().isEmpty())) {
            return left;
        }
        if (left == null || (left.vertices().isEmpty() && left.edges().isEmpty())) {
            return right;
        }
        List<Subgraph.Vertex> vertices = new ArrayList<>(left.vertices());
        vertices.addAll(right.vertices());
        List<Subgraph.Edge> edges = new ArrayList<>(left.edges());
        edges.addAll(right.edges());
        return new Subgraph(vertices, edges);
    }

    private static String text(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new QueryException("Unbalanced parentheses in: " + s);
    }

    /** The index of the {@code ')'} that closes the {@code '('} at 0, or -1 when it does not pair up. */
    private static int matchingParenthesis(String text) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int matchingBracket(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new QueryException("Unbalanced brackets in: " + s);
    }

    /** Splits comma-separated arguments, ignoring commas inside quotes and brackets. */
    private static List<String> splitArgs(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    current.append(s.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '[' || c == '(' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ']' || c == ')' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return result;
    }
}
