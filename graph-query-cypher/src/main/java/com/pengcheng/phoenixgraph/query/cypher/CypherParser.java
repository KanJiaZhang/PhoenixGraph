package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.QueryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A hand-written recursive-descent parser for the supported openCypher subset.
 *
 * <p>Grammar (case-insensitive keywords):
 * <pre>
 *   query      := MATCH patterns [WHERE expr] RETURN [DISTINCT] items
 *                 [ORDER BY key (',' key)*] [SKIP n] [LIMIT n] [';']
 *   patterns   := path (',' path)*
 *   path       := node (rel node)*
 *   node       := '(' [var] [':' Label] ['{' key ':' expr (',' ...)* '}'] ')'
 *   rel        := ('-' | '&lt;-' | '-[' detail ']') ('-' | '-&gt;')          // see parseRelationship
 *   detail     := [var] [':' Type ('|' Type)*] ['*' [n] ['..' [m]]]
 *   items      := item (',' item)*
 *   item       := expr [AS name]
 *   expr       := or
 *   or         := and (OR and)*
 *   and        := not (AND not)*
 *   not        := [NOT] comparison
 *   comparison := stringpred (('=' | '&lt;&gt;' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;=')
 *                             stringpred | IN '[' expr (',' expr)* ']' | IS [NOT] NULL)?
 *   stringpred := primary (('CONTAINS' | 'STARTS WITH' | 'ENDS WITH' | '=~') primary)?
 *   primary    := number | string | TRUE | FALSE | NULL | '$' name | name '.' key
 *                 | name '(' [DISTINCT] (expr (',' expr)* | '*') ')'
 *                 | '(' expr ')' | '[' expr (',' expr)* ']'
 * </pre>
 *
 * <p>Errors carry the position: {@code line 1, column 23: expected ')'}.
 */
public final class CypherParser {

    /** Upper bound of a variable-length range, so a query cannot walk away. */
    static final int MAX_HOPS = 6;

    private final String source;
    private final List<Token> tokens;
    private int position;

    private CypherParser(String source, List<Token> tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    /** Parses a query string. */
    public static CypherQuery parse(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new QueryException("the Cypher query must not be blank");
        }
        return new CypherParser(cypher, tokenize(cypher)).parseQuery();
    }

    // --- statement -------------------------------------------------------------

    private CypherQuery parseQuery() {
        expectKeyword("MATCH", "a read query must start with MATCH");
        List<CypherPattern.Path> matches = new ArrayList<>();
        matches.add(parsePath());
        while (matchSymbol(",")) {
            matches.add(parsePath());
        }

        CypherExpression where = null;
        if (matchKeyword("WHERE")) {
            where = parseExpression();
        }

        expectKeyword("RETURN", "expected RETURN");
        boolean distinct = matchKeyword("DISTINCT");
        List<CypherQuery.ReturnItem> projection = parseReturnItems();

        List<CypherQuery.OrderKey> orderBy = new ArrayList<>();
        if (matchKeyword("ORDER")) {
            expectKeyword("BY", "expected BY after ORDER");
            orderBy.add(parseOrderKey());
            while (matchSymbol(",")) {
                orderBy.add(parseOrderKey());
            }
        }
        long skip = 0;
        if (matchKeyword("SKIP")) {
            skip = numberValue(advance(), "SKIP expects a number");
        }
        long limit = -1;
        if (matchKeyword("LIMIT")) {
            limit = numberValue(advance(), "LIMIT expects a number");
        }
        matchSymbol(";");
        rejectUnsupportedClause();
        if (!atEnd()) {
            throw error("unexpected input '" + peek().text() + "'");
        }
        return new CypherQuery(matches, where, projection, distinct, orderBy, skip, limit);
    }

    private void rejectUnsupportedClause() {
        if (atEnd()) {
            return;
        }
        String keyword = peek().text().toUpperCase(Locale.ROOT);
        if (List.of("CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "WITH", "UNWIND", "CALL",
                "UNION", "FOREACH", "LOAD", "USING", "OPTIONAL").contains(keyword)) {
            throw error(keyword + " is not supported (only MATCH ... RETURN read queries are)");
        }
    }

    private List<CypherQuery.ReturnItem> parseReturnItems() {
        List<CypherQuery.ReturnItem> items = new ArrayList<>();
        items.add(parseReturnItem());
        while (matchSymbol(",")) {
            items.add(parseReturnItem());
        }
        return items;
    }

    private CypherQuery.ReturnItem parseReturnItem() {
        if (matchSymbol("*")) {
            return new CypherQuery.ReturnItem(new CypherExpression.Literal("*"), "*", null);
        }
        int start = peek().start();
        CypherExpression expression = parseExpression();
        String text = sourceBetween(start, peek().start()).trim();
        String alias = null;
        if (matchKeyword("AS")) {
            alias = advance().text();
        }
        if (expression.isAggregating() && !(expression instanceof CypherExpression.Function)) {
            throw new QueryException("An aggregation cannot be nested in another expression: " + text);
        }
        return new CypherQuery.ReturnItem(expression, text, alias);
    }

    private CypherQuery.OrderKey parseOrderKey() {
        int start = peek().start();
        CypherExpression expression = parseExpression();
        String text = sourceBetween(start, peek().start()).trim();
        boolean descending = false;
        if (matchKeyword("DESC") || matchKeyword("DESCENDING")) {
            descending = true;
        } else {
            matchKeyword("ASC");
            matchKeyword("ASCENDING");
        }
        return new CypherQuery.OrderKey(expression, text, descending);
    }

    // --- patterns --------------------------------------------------------------

    private CypherPattern.Path parsePath() {
        CypherPattern.Node start = parseNode();
        List<CypherPattern.Step> steps = new ArrayList<>();
        while (startsRelationship()) {
            CypherPattern.Relationship relationship = parseRelationship();
            CypherPattern.Node node = parseNode();
            steps.add(new CypherPattern.Step(relationship, node));
        }
        return new CypherPattern.Path(start, steps);
    }

    private CypherPattern.Node parseNode() {
        expectSymbol("(", "a node pattern must start with '('");
        String variable = null;
        if (peek().kind() == TokenKind.IDENTIFIER) {
            variable = advance().text();
        }
        String label = null;
        if (matchSymbol(":")) {
            label = advanceName("a node label");
        }
        Map<String, CypherExpression> properties = new LinkedHashMap<>();
        if (matchSymbol("{")) {
            if (!matchSymbol("}")) {
                do {
                    String key = advanceName("a property name");
                    expectSymbol(":", "expected ':' after the property name");
                    properties.put(key, parseExpression());
                } while (matchSymbol(","));
                expectSymbol("}", "expected '}' to close the property map");
            }
        }
        expectSymbol(")", "expected ')' to close the node pattern");
        return new CypherPattern.Node(variable, label, properties);
    }

    private boolean startsRelationship() {
        Token token = peek();
        return token.kind() == TokenKind.SYMBOL
                && (token.text().equals("-") || token.text().equals("<-"));
    }

    /**
     * {@code -->}, {@code --}, {@code <--}, {@code -[r:TYPE*1..2]->} and every mix of them.
     */
    private CypherPattern.Relationship parseRelationship() {
        CypherPattern.Direction direction = CypherPattern.Direction.BOTH;
        if (peek().text().equals("<-")) {
            advance();
            direction = CypherPattern.Direction.IN;
        } else {
            expectSymbol("-", "expected '-'");
        }

        String variable = null;
        List<String> types = new ArrayList<>();
        int minHops = 1;
        int maxHops = 1;
        if (matchSymbol("[")) {
            if (peek().kind() == TokenKind.IDENTIFIER) {
                variable = advance().text();
            }
            if (matchSymbol(":")) {
                types.add(advanceName("a relationship type"));
                while (matchSymbol("|")) {
                    matchSymbol(":");
                    types.add(advanceName("a relationship type"));
                }
            }
            if (matchSymbol("*")) {
                if (peek().kind() == TokenKind.NUMBER) {
                    minHops = (int) numberValue(advance(), "a hop count");
                    maxHops = minHops;
                    if (matchSymbol("..")) {
                        maxHops = peek().kind() == TokenKind.NUMBER
                                ? (int) numberValue(advance(), "a hop count") : minHops;
                    }
                } else {
                    throw error("a variable-length relationship needs an explicit range, e.g. *1..3");
                }
                if (maxHops > MAX_HOPS) {
                    throw error("at most " + MAX_HOPS + " hops are supported, got " + maxHops);
                }
            }
            expectSymbol("]", "expected ']' to close the relationship pattern");
        }

        if (matchSymbol("->")) {
            if (direction == CypherPattern.Direction.IN) {
                throw error("a relationship cannot point both ways: '<-[...]->'");
            }
            direction = CypherPattern.Direction.OUT;
        } else {
            expectSymbol("-", "expected '-' or '->' to close the relationship pattern");
        }
        if (direction == CypherPattern.Direction.BOTH && variable == null && types.isEmpty()) {
            // (a)--(b): undirected, any type
            return new CypherPattern.Relationship(null, types, direction, minHops, maxHops);
        }
        return new CypherPattern.Relationship(variable, types, direction, minHops, maxHops);
    }

    // --- expressions -----------------------------------------------------------

    private CypherExpression parseExpression() {
        return parseOr();
    }

    private CypherExpression parseOr() {
        CypherExpression left = parseAnd();
        if (!matchKeyword("OR")) {
            return left;
        }
        List<CypherExpression> operands = new ArrayList<>();
        operands.add(left);
        do {
            operands.add(parseAnd());
        } while (matchKeyword("OR"));
        return new CypherExpression.Or(operands);
    }

    private CypherExpression parseAnd() {
        CypherExpression left = parseNot();
        if (!matchKeyword("AND")) {
            return left;
        }
        List<CypherExpression> operands = new ArrayList<>();
        operands.add(left);
        do {
            operands.add(parseNot());
        } while (matchKeyword("AND"));
        return new CypherExpression.And(operands);
    }

    private CypherExpression parseNot() {
        if (matchKeyword("NOT")) {
            return new CypherExpression.Not(parseNot());
        }
        return parseComparison();
    }

    private CypherExpression parseComparison() {
        CypherExpression left = parseStringPredicate();
        String operator = comparisonOperator();
        if (operator != null) {
            advance();
            return new CypherExpression.Compare(left, operator, parseStringPredicate());
        }
        if (matchKeyword("IN")) {
            expectSymbol("[", "expected '[' after IN");
            List<CypherExpression> list = new ArrayList<>();
            if (!matchSymbol("]")) {
                do {
                    list.add(parseExpression());
                } while (matchSymbol(","));
                expectSymbol("]", "expected ']' to close the list");
            }
            return new CypherExpression.In(left, list);
        }
        if (matchKeyword("IS")) {
            boolean negated = matchKeyword("NOT");
            expectKeyword("NULL", "expected NULL after IS");
            return new CypherExpression.IsNull(left, negated);
        }
        return left;
    }

    private String comparisonOperator() {
        Token token = peek();
        if (token.kind() != TokenKind.SYMBOL) {
            return null;
        }
        return switch (token.text()) {
            case "=", "<>", "!=", "<", "<=", ">", ">=" -> token.text();
            default -> null;
        };
    }

    private CypherExpression parseStringPredicate() {
        CypherExpression left = parsePrimary();
        if (matchKeyword("CONTAINS")) {
            return new CypherExpression.StringOp(left, "CONTAINS", parsePrimary());
        }
        if (matchKeyword("STARTS")) {
            expectKeyword("WITH", "expected WITH after STARTS");
            return new CypherExpression.StringOp(left, "STARTS WITH", parsePrimary());
        }
        if (matchKeyword("ENDS")) {
            expectKeyword("WITH", "expected WITH after ENDS");
            return new CypherExpression.StringOp(left, "ENDS WITH", parsePrimary());
        }
        if (matchSymbol("=~")) {
            return new CypherExpression.StringOp(left, "=~", parsePrimary());
        }
        return left;
    }

    private CypherExpression parsePrimary() {
        Token token = peek();
        switch (token.kind()) {
            case NUMBER -> {
                advance();
                return new CypherExpression.Literal(numberValue(token, "a number"));
            }
            case STRING -> {
                advance();
                return new CypherExpression.Literal(token.text());
            }
            case PARAMETER -> {
                advance();
                return new CypherExpression.Parameter(token.text());
            }
            case SYMBOL -> {
                if (matchSymbol("(")) {
                    CypherExpression inner = parseExpression();
                    expectSymbol(")", "expected ')'");
                    return inner;
                }
                if (matchSymbol("[")) {
                    List<CypherExpression> items = new ArrayList<>();
                    if (!matchSymbol("]")) {
                        do {
                            items.add(parseExpression());
                        } while (matchSymbol(","));
                        expectSymbol("]", "expected ']'");
                    }
                    return new CypherExpression.ListLiteral(items);
                }
                if (matchSymbol("-")) {
                    Token number = advance();
                    return new CypherExpression.Literal(-numberValue(number, "a number"));
                }
                throw error("unexpected '" + token.text() + "'");
            }
            case IDENTIFIER -> {
                advance();
                String name = token.text();
                if (matchSymbol("(")) {
                    return parseFunction(name);
                }
                if (matchSymbol(".")) {
                    String key = advanceName("a property name");
                    return new CypherExpression.Property(name, key);
                }
                return switch (name.toUpperCase(Locale.ROOT)) {
                    case "TRUE" -> new CypherExpression.Literal(Boolean.TRUE);
                    case "FALSE" -> new CypherExpression.Literal(Boolean.FALSE);
                    case "NULL" -> new CypherExpression.Literal(null);
                    default -> new CypherExpression.VariableReference(name);
                };
            }
            default -> throw error("unexpected '" + token.text() + "'");
        }
    }

    private CypherExpression parseFunction(String name) {
        boolean distinct = matchKeyword("DISTINCT");
        boolean star = matchSymbol("*");
        List<CypherExpression> arguments = new ArrayList<>();
        if (star) {
            expectSymbol(")", "expected ')' after * in " + name + "()");
        } else if (!matchSymbol(")")) {
            do {
                arguments.add(parseExpression());
            } while (matchSymbol(","));
            expectSymbol(")", "expected ')' to close the arguments of " + name + "()");
        }
        if (distinct && star) {
            throw error("DISTINCT is not meaningful with *");
        }
        return new CypherExpression.Function(name, arguments, distinct, star);
    }

    // --- tokens ----------------------------------------------------------------

    private enum TokenKind {
        IDENTIFIER, NUMBER, STRING, PARAMETER, SYMBOL, END
    }

    private record Token(TokenKind kind, String text, Object value, int start) {
    }

    private static List<Token> tokenize(String cypher) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = cypher.length();
        while (i < length) {
            char c = cypher.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < length && cypher.charAt(i + 1) == '/') {
                while (i < length && cypher.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = i + 1;
                StringBuilder text = new StringBuilder();
                while (end < length && cypher.charAt(end) != c) {
                    if (cypher.charAt(end) == '\\' && end + 1 < length) {
                        end++;
                        char escaped = cypher.charAt(end);
                        text.append(escaped == 'n' ? '\n' : escaped == 't' ? '\t' : escaped);
                    } else {
                        text.append(cypher.charAt(end));
                    }
                    end++;
                }
                if (end >= length) {
                    throw new QueryException("line 1, column " + (i + 1) + ": unterminated string");
                }
                tokens.add(new Token(TokenKind.STRING, text.toString(), text.toString(), i));
                i = end + 1;
                continue;
            }
            if (c == '$') {
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(cypher.charAt(end))
                        || cypher.charAt(end) == '_')) {
                    end++;
                }
                if (end == i + 1) {
                    throw new QueryException("line 1, column " + (i + 1) + ": '$' must be followed by a name");
                }
                String name = cypher.substring(i + 1, end);
                tokens.add(new Token(TokenKind.PARAMETER, name, name, i));
                i = end;
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < length && Character.isDigit(cypher.charAt(i + 1))
                    && !isValueBefore(tokens))) {
                int end = i + (c == '-' ? 1 : 0);
                boolean decimal = false;
                while (end < length) {
                    char next = cypher.charAt(end);
                    if (Character.isDigit(next)) {
                        end++;
                        continue;
                    }
                    // a dot only belongs to the number when it is not the '..' of a hop range
                    if (next == '.' && !decimal && !cypher.startsWith("..", end)) {
                        decimal = true;
                        end++;
                        continue;
                    }
                    break;
                }
                String text = cypher.substring(i, end);
                Object value = decimal ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
                tokens.add(new Token(TokenKind.NUMBER, text, value, i));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < length && (Character.isLetterOrDigit(cypher.charAt(end))
                        || cypher.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new Token(TokenKind.IDENTIFIER, cypher.substring(i, end),
                        cypher.substring(i, end), i));
                i = end;
                continue;
            }
            String symbol = symbol(cypher, i);
            tokens.add(new Token(TokenKind.SYMBOL, symbol, symbol, i));
            i += symbol.length();
        }
        tokens.add(new Token(TokenKind.END, "", null, length));
        return tokens;
    }

    /** Multi-character symbols first. */
    private static String symbol(String cypher, int at) {
        for (String candidate : List.of("<-", "->", "<=", ">=", "<>", "!=", "=~", "..")) {
            if (cypher.startsWith(candidate, at)) {
                return candidate;
            }
        }
        return String.valueOf(cypher.charAt(at));
    }

    /** Whether the previous token can be followed by a negative number. */
    private static boolean isValueBefore(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return false;
        }
        Token last = tokens.get(tokens.size() - 1);
        return last.kind() == TokenKind.IDENTIFIER && !List.of("AND", "OR", "NOT", "WHERE", "RETURN")
                .contains(last.text().toUpperCase(Locale.ROOT))
                || last.kind() == TokenKind.NUMBER || last.kind() == TokenKind.STRING;
    }

    // --- token helpers ---------------------------------------------------------

    private Token peek() {
        return tokens.get(position);
    }

    private Token advance() {
        Token token = tokens.get(position);
        if (token.kind() != TokenKind.END) {
            position++;
        }
        return token;
    }

    private boolean atEnd() {
        return peek().kind() == TokenKind.END;
    }

    private boolean matchKeyword(String keyword) {
        Token token = peek();
        if (token.kind() == TokenKind.IDENTIFIER && token.text().equalsIgnoreCase(keyword)) {
            advance();
            return true;
        }
        return false;
    }

    private void expectKeyword(String keyword, String message) {
        if (matchKeyword(keyword)) {
            return;
        }
        rejectUnsupportedClause();
        throw error(message);
    }

    private boolean matchSymbol(String symbol) {
        if (peek().kind() == TokenKind.SYMBOL && peek().text().equals(symbol)) {
            advance();
            return true;
        }
        return false;
    }

    private void expectSymbol(String symbol, String message) {
        if (!matchSymbol(symbol)) {
            throw error(message);
        }
    }

    private String advanceName(String what) {
        Token token = advance();
        if (token.kind() != TokenKind.IDENTIFIER) {
            throw error("expected " + what);
        }
        return token.text();
    }

    private long numberValue(Token token, String what) {
        if (token.kind() != TokenKind.NUMBER) {
            throw error("expected " + what);
        }
        return ((Number) token.value()).longValue();
    }

    private String sourceBetween(int from, int to) {
        int end = Math.min(to, source.length());
        return from <= end ? source.substring(from, end) : "";
    }

    private QueryException error(String message) {
        Token token = peek();
        return new QueryException("line 1, column " + (token.start() + 1) + ": " + message);
    }

}
