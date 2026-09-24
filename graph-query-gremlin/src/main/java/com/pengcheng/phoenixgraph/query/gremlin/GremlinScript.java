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
    private static final Pattern PRINT = Pattern.compile("^(print|println)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL);
    private static final Pattern RETURN = Pattern.compile("^return\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
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
                    throw new QueryException("Expected '(' after '." + rest + "'");
                }
                name = rest.substring(0, open).trim();
                close = matchingParen(rest, open);
                args = rest.substring(open + 1, close).trim();
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
            default:
                throw new QueryException("Unknown closure method '." + name + "'. Supported: each(), "
                        + "eachWithIndex(), collect(), findAll(), find(), any(), every(), count(), sum()");
        }
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
            case "id":
                return value instanceof GremlinElement element ? element.id() : value;
            case "label":
                return value instanceof GremlinElement element ? element.label() : value;
            case "value": {
                if (!(value instanceof GremlinElement element)) {
                    throw new QueryException(".value(key) needs a vertex or an edge, got " + text(value));
                }
                return element.properties().get(text(evaluate(args, state, runner)));
            }
            case "values": {
                if (!(value instanceof GremlinElement element)) {
                    throw new QueryException(".values(keys) needs a vertex or an edge, got " + text(value));
                }
                List<Object> out = new ArrayList<>();
                for (String key : splitArgs(args)) {
                    out.add(element.properties().get(text(evaluate(key, state, runner))));
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
                        + "endIds(), joinOnEnd(paths[, middleObjects])");
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
            return list(text, state, runner);
        }
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1)
                    .replace("\\'", "'").replace("\\\"", "\"");
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
