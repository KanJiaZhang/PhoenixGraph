package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Groovy-style script layer: statements, variables over traversal results, {@code print} and the
 * subgraph the traversals touched. The traversals are answered by a stub, so the tests are about the
 * scripting, not about storage.
 */
class GremlinScriptTest {

    /** A runner that answers a traversal with fixed values and records what it was asked to run. */
    private static final class Stub implements GremlinScript.Runner {
        final List<String> ran = new ArrayList<>();
        final List<GremlinGraphQuery.Result> answers;

        Stub(GremlinGraphQuery.Result... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public GremlinGraphQuery.Result run(String traversal) {
            ran.add(traversal);
            GremlinGraphQuery.Result answer = answers.get(Math.min(ran.size() - 1, answers.size() - 1));
            return answer;
        }
    }

    private static GremlinGraphQuery.Result values(Object... items) {
        return new GremlinGraphQuery.Result(List.of(items), Subgraph.EMPTY);
    }

    @Test
    void aScriptPicksTheDirectedHalfOutOfABothWalk() {
        // both('knows').bothE('knows') already holds the out/out and the in/in halves; the edge says
        // which side it was read from, so the payer-to-payee half is a filter, not another traversal
        GremlinPath outOut = new GremlinPath(List.of(vertex("p1"), sidedEdge("e1", EdgeSide.OUT, "p2"),
                vertex("p2"), sidedEdge("e2", EdgeSide.OUT, "p3")));
        GremlinPath outIn = new GremlinPath(List.of(vertex("p1"), sidedEdge("e1", EdgeSide.OUT, "p2"),
                vertex("p2"), sidedEdge("e3", EdgeSide.IN, "p0")));
        Stub stub = new Stub(values(outOut, outIn));

        GremlinScript.Result result = GremlinScript.parse("""
                def sAny = g.V('p1', 'person').both('knows').bothE('knows').path().toList()
                def sOut = sAny.findAll { p -> p.get(1).value('side') == 'out' && p.get(3).value('side') == 'out' }
                sOut.size()
                """).execute(stub);

        assertEquals(1L, result.value(),
                "only the walk whose two hops both leave their vertex is the payer-to-payee half");
    }

    private static GremlinEdge sidedEdge(String id, EdgeSide side, String other) {
        return new GremlinEdge("knows", id, side, other, Map.of(), 1L, 2L);
    }

    @Test
    void aScriptIsToldApartFromATraversal() {
        assertTrue(GremlinScript.looksLikeScript(
                "def ids = g.V(1001, 'Account').values('id').toList()\nprint(ids)"));
        assertTrue(GremlinScript.looksLikeScript("print(g.V(1,'Account'))"));
        assertTrue(GremlinScript.looksLikeScript("return g.V(1,'Account')"));
        assertTrue(GremlinScript.looksLikeScript("x = g.V(1,'Account')"));

        assertFalse(GremlinScript.looksLikeScript("g.V(1,'Account').out('Payment').values('id')"));
        assertFalse(GremlinScript.looksLikeScript(
                "g.V(1,'Account')\n  .out('Payment')\n  .values('id')"),
                "a traversal written over several lines is one statement");
        assertFalse(GremlinScript.looksLikeScript(
                "g.V(1,'Account').filter { it.get().value('print') == 'x' }"),
                "a property named print inside a closure is not a print statement");
        assertFalse(GremlinScript.looksLikeScript("   "));
    }

    @Test
    void aDecimalIsOneNumberAndNotAChain() {
        GremlinScript.Result result = GremlinScript.parse("def x = 1.5 + 1\nx * 2").execute(new Stub(values()));
        assertEquals(5.0, result.value(), "the dot in 1.5 starts no method chain");
    }

    @Test
    void aValueIsKeptAndPrinted() {
        Stub stub = new Stub(values(1001L, 1004L));
        GremlinScript.Result result = GremlinScript.parse(
                "def ids = g.V(1001, 'Account').values('id').toList()\nprint(ids)").execute(stub);

        assertEquals(List.of("g.V(1001, 'Account').values('id').toList()"), stub.ran);
        assertEquals(List.of(1001L, 1004L), result.value(), "the printed value is the answer");
        assertEquals(List.of("[1001, 1004]"), result.output());
        assertEquals(List.of(List.of(1001L, 1004L)), result.printed());
    }

    @Test
    void aVariableStandsForTheIdsOfATraversal() {
        Stub stub = new Stub(values(1001L, 1004L), values());
        GremlinScript.Result result = GremlinScript.parse(
                "def ids = g.V(1001, 'Account').values('id')\n"
                        + "g.V(ids, 'Account').out('Payment')").execute(stub);

        assertEquals(2, stub.ran.size());
        assertTrue(stub.ran.get(1).contains("1001"), stub.ran.get(1));
        assertTrue(stub.ran.get(1).contains("1004"), stub.ran.get(1));
        assertTrue(stub.ran.get(1).endsWith("'Account').out('Payment')"), stub.ran.get(1));
        assertEquals(List.of(), result.value(), "the last statement's value is the answer");
    }

    @Test
    void aChainOnAVariableCountsAndReads() {
        Stub stub = new Stub(values(1001L, 1004L, 1007L));
        GremlinScript.Result result = GremlinScript.parse(
                "def ids = g.V(1001, 'Account').values('id')\n"
                        + "print(ids.size())\n"
                        + "print(ids.get(1))\n"
                        + "print(ids.isEmpty())").execute(stub);

        assertEquals(List.of("3", "1004", "false"), result.output());
        assertEquals(List.of(3L, 1004L, false), result.printed());
        assertEquals(List.of(3L, 1004L, false), result.value(), "several prints become a list");
    }

    @Test
    void aReturnStopsTheScript() {
        Stub stub = new Stub(values(1L), values(2L));
        GremlinScript.Result result = GremlinScript.parse(
                "def a = g.V(1, 'person')\n"
                        + "return a.size()\n"
                        + "print(g.V(2, 'person'))").execute(stub);

        assertEquals(1, stub.ran.size(), "the statement after return does not run");
        assertEquals(1L, result.value());
    }

    @Test
    void aListAndALiteralCanBeKept() {
        Stub stub = new Stub();
        GremlinScript.Result result = GremlinScript.parse(
                "def mixed = [1, 'a', true]\nprint(mixed)").execute(stub);

        assertEquals(List.of(List.of(1L, "a", true)), result.printed());
        assertEquals(List.of(1L, "a", true), result.value());
    }

    @Test
    void theSubgraphOfEveryTraversalIsCollected() {
        Subgraph first = new Subgraph(List.of(new Subgraph.Vertex("person", "v1", Map.of(), 1L, 2L)),
                List.of());
        Subgraph second = new Subgraph(List.of(new Subgraph.Vertex("person", "v2", Map.of(), 1L, 2L)),
                List.of());
        Stub stub = new Stub(new GremlinGraphQuery.Result(List.of("v1"), first),
                new GremlinGraphQuery.Result(List.of("v2"), second));

        GremlinScript.Result result = GremlinScript.parse(
                "def a = g.V('v1', 'person')\ndef b = g.V('v2', 'person')\nprint(a.size())")
                .execute(stub);

        assertEquals(2, result.subgraph().vertices().size(), "both traversals' vertices are drawn");
    }

    @Test
    void onlyTheLastValueIsTheAnswerWithoutPrints() {
        Stub stub = new Stub(values(1L), values("done"));
        GremlinScript.Result result = GremlinScript.parse(
                "def a = g.V(1, 'person')\ng.V(2, 'person').values('name')").execute(stub);

        assertEquals(List.of("done"), result.value());
        assertEquals(List.of(), result.output());
    }

    @Test
    void aValueItCannotReadSaysWhatIsSupported() {
        QueryException unknown = assertThrows(QueryException.class, () -> GremlinScript.parse("print(nope)")
                .execute(new Stub()));
        assertTrue(unknown.getMessage().contains("Unknown script variable 'nope'"), unknown.getMessage());

        QueryException badMethod = assertThrows(QueryException.class, () -> GremlinScript.parse(
                "def a = g.V(1, 'person')\nprint(a.shuffle())").execute(new Stub(values(1L))));
        assertTrue(badMethod.getMessage().contains("Unknown script method"), badMethod.getMessage());

        assertThrows(QueryException.class, () -> GremlinScript.parse("  "));
    }

    @Test
    void anEmptyIdListSaysWhatIsWrong() {
        QueryException error = assertThrows(QueryException.class, () -> GremlinScript.parse(
                "def ids = g.V(1001, 'Account').values('id')\ng.V(ids, 'Account')")
                .execute(new Stub(values())));
        assertTrue(error.getMessage().contains("'ids' is empty"), error.getMessage());
    }

    private static GremlinVertex vertex(String id) {
        return new GremlinVertex("Account", id, Map.of(), 0L, 0L);
    }

    private static GremlinEdge edge(String id, String other, String source) {
        return new GremlinEdge("Payment", id, EdgeSide.OUT, other, Map.of(), 1L, 2L, source);
    }

    /** A script's answer, without any traversal: the syntax tests only need the statement and value layers. */
    private static Object script(String text) {
        return GremlinScript.parse(text).execute(new Stub(values())).value();
    }

    @Test
    void compoundAssignmentAndIncrementRewriteToAnAssignment() {
        assertEquals(3L, script("def x = 1\nx += 2\nx"));
        assertEquals(4L, script("def x = 10\nx -= 6\nx"));
        assertEquals(6L, script("def x = 2\nx *= 3\nx"));
        assertEquals(3.5, script("def x = 7\nx /= 2\nx"), "Groovy's / is a real division");
        assertEquals(2L, script("def x = 1\nx++\nx"));
        assertEquals(2L, script("def x = 1\n++x\nx"));
        assertEquals(0L, script("def x = 1\nx--\nx"));
        assertEquals(0L, script("def x = 1\n--x\nx"));
        assertEquals(4L, script("def xs = [1, 2]\ndef i = 0\ni += xs.size() + 2\ni"),
                "the right side is one expression, so i + (2 + 2)");
    }

    @Test
    void ternaryAndElvis() {
        assertEquals("yes", script("def x = 1\nx > 0 ? 'yes' : 'no'"));
        assertEquals("no", script("def x = -1\nx > 0 ? 'yes' : 'no'"));
        assertEquals("no", script("def x = 1\ndef y = 2\nx > y ? 'yes' : 'no'"));
        assertEquals("d", script("def x = null\nx ?: 'd'"));
        assertEquals(5L, script("def x = 5\nx ?: 9"));
        assertEquals("yes", script("def x = 1\ndef y = 2\nx == 1 && y == 2 ? 'yes' : 'no'"),
                "the condition is the whole expression before the '?'");
    }

    @Test
    void aTripleQuotedStringIsOneValueNewlinesIncluded() {
        assertEquals("a\nb", script("def s = '''a\nb'''\ns"));
        assertEquals("a\nb", script("def s = \"\"\"a\nb\"\"\"\ns"));
    }

    @Test
    void aStringHasToBeOneValueSoRegexIsRefusedRatherThanSwallowed() {
        QueryException error = assertThrows(QueryException.class, () -> script("def m = 'abc' =~ 'b'\nm"));
        assertTrue(error.getMessage().contains("string has to be one value"), error.getMessage());
        assertTrue(error.getMessage().contains("regular-expression"), error.getMessage());
        assertEquals("it's", script("def s = 'it\\'s'\ns"), "an escaped quote is still one string");
    }

    @Test
    void aListPlusAppendsWhileTextStaysText() {
        assertEquals(List.of(1L, 2L, 3L), script("def xs = [1, 2]\nxs + 3\n"),
                "Groovy appends to a list");
        assertEquals(List.of(1L, 2L, 3L, 4L), script("def xs = [1, 2]\nxs + [3, 4]"));
        assertEquals("ids: [1, 2]", script("def xs = [1, 2]\n'ids: ' + xs"),
                "a String on either side still makes text");
        assertEquals(5L, script("def x = 2\nx + 3"), "numbers still add");
    }

    @Test
    void edgeEndedHalvesJoinOnTheirFarVertex() {
        // the second hop is outE(), so each half ends on an edge whose far vertex is the shared one
        GremlinPath sPath = new GremlinPath(List.of(vertex("s"), edge("e1", "v1", "s"), vertex("v1"),
                edge("e2", "c", "v1")));
        GremlinPath rPath = new GremlinPath(List.of(vertex("r"), edge("f1", "w1", "r"), vertex("w1"),
                edge("f2", "c", "w1")));
        Stub stub = new Stub(values(sPath), values(rPath), values(vertex("c")));

        GremlinScript.Result result = GremlinScript.parse(
                "def sPaths = g.V('s','Account').out('Payment').outE('Payment').path()\n"
                        + "def rPaths = g.V('r','Account').out('Payment').outE('Payment').path()\n"
                        + "def common = sPaths.endIds().retainAll(rPaths.endIds())\n"
                        + "def mids = g.V(common, 'Account')\n"
                        + "def four = sPaths.joinOnEnd(rPaths, mids)\n"
                        + "print(four.size())\n"
                        + "print(four.get(0).size())").execute(stub);

        assertEquals(List.of(1L, 9L), result.printed(), "one four-hop walk, 9 elements");
    }

    @Test
    void twoPathListsJoinOnTheirSharedEnd() {
        GremlinPath s1 = new GremlinPath(List.of(vertex("s"), vertex("a"), vertex("m1")));
        GremlinPath s2 = new GremlinPath(List.of(vertex("s"), vertex("b"), vertex("m2")));
        GremlinPath r1 = new GremlinPath(List.of(vertex("r"), vertex("c"), vertex("m1")));
        GremlinPath r2 = new GremlinPath(List.of(vertex("r"), vertex("d"), vertex("m2")));
        Stub stub = new Stub(values(s1, s2), values(r1, r2));

        GremlinScript.Result result = GremlinScript.parse(
                "def sPaths = g.V('s','Account').out('Payment').out('Payment').path()\n"
                        + "def rPaths = g.V('r','Account').out('Payment').out('Payment').path()\n"
                        + "def four = sPaths.joinOnEnd(rPaths)\n"
                        + "print(four.size())").execute(stub);

        assertEquals(List.of(2L), result.printed(), "both pairs meet at a shared middle vertex");
    }

    @Test
    void aJoinedWalkThatVisitsAVertexTwiceIsDropped() {
        // the two walks meet at m1 and both went through 'a': the four-hop walk would repeat a vertex
        GremlinPath left = new GremlinPath(List.of(vertex("s"), vertex("a"), vertex("m1")));
        GremlinPath right = new GremlinPath(List.of(vertex("r"), vertex("a"), vertex("m1")));
        Stub stub = new Stub(values(left), values(right));

        GremlinScript.Result result = GremlinScript.parse(
                "def sPaths = g.V('s','Account').out('Payment').out('Payment').path()\n"
                        + "def rPaths = g.V('r','Account').out('Payment').out('Payment').path()\n"
                        + "print(sPaths.joinOnEnd(rPaths).size())").execute(stub);

        assertEquals(List.of(0L), result.printed());
    }

    @Test
    void retainAllGivesTheCommonIds() {
        Stub stub = new Stub(values("m1", "m2"), values("m2", "m3"));

        GremlinScript.Result result = GremlinScript.parse(
                "def sMids = g.V('s','Account').out().values('id')\n"
                        + "def rMids = g.V('r','Account').out().values('id')\n"
                        + "print(sMids.retainAll(rMids))").execute(stub);

        assertEquals(List.of(List.of("m2")), result.printed());
    }

    @Test
    void aPathAnswersItsTwoEnds() {
        GremlinPath path = new GremlinPath(List.of(vertex("s"), vertex("m"), vertex("r")));
        GremlinScript.Result result = GremlinScript.parse(
                "def one = g.V('s','Account').out().out().path()\n"
                        + "print(one.get(0).start())\n"
                        + "print(one.get(0).end())").execute(new Stub(values(path)));

        assertEquals(2, result.printed().size());
        assertEquals("s", ((GremlinVertex) result.printed().get(0)).id());
        assertEquals("r", ((GremlinVertex) result.printed().get(1)).id());
    }

    @Test
    void theValueAfterThePrintsIsTheAnswer() {
        GremlinScript.Result result = GremlinScript.parse(
                "def a = g.V('s','Account').out().values('id')\n"
                        + "print('count: ' + a.size())\n"
                        + "a").execute(new Stub(values("m1", "m2")));

        assertEquals(List.of("m1", "m2"), result.value(), "the last expression is the answer");
        assertEquals(List.of("count: 2"), result.output(), "the print is still reported");
    }

    @Test
    void anIfChoosesTheBranch() {
        GremlinScript.Result result = GremlinScript.parse(
                "def n = 3\nif (n > 2) { print('big') } else { print('small') }").execute(new Stub());
        assertEquals(List.of("big"), result.output());
    }

    @Test
    void anElseIfNests() {
        GremlinScript.Result result = GremlinScript.parse(
                "def n = 1\nif (n > 2) { print('big') } else if (n > 0) { print('small') } "
                        + "else { print('zero') }").execute(new Stub());
        assertEquals(List.of("small"), result.output());
    }

    @Test
    void aForLoopWalksAList() {
        GremlinScript.Result result = GremlinScript.parse(
                "def total = 0\nfor (x in [1, 2, 3]) { total = total + x }\nprint(total)")
                .execute(new Stub());
        assertEquals(List.of(6L), result.printed());
    }

    @Test
    void aLoopCanBreakAndContinue() {
        GremlinScript.Result result = GremlinScript.parse(
                "def out = []\nfor (x in [1, 2, 3, 4, 5]) {\n  if (x == 2) { continue }\n"
                        + "  if (x == 4) { break }\n  out = out.add(x)\n}\nprint(out)")
                .execute(new Stub());
        assertEquals(List.of(List.of(1L, 3L)), result.printed());
    }

    @Test
    void closuresCollectFilterAndAggregate() {
        GremlinScript.Result result = GremlinScript.parse(
                "def ids = [1, 2, 3, 4]\n"
                        + "def doubled = ids.collect { it * 2 }\n"
                        + "def even = ids.findAll { it % 2 == 0 }\n"
                        + "def firstBig = ids.find { it > 2 }\n"
                        + "print(doubled)\nprint(even)\nprint(firstBig)\nprint(ids.count { it > 2 })")
                .execute(new Stub());
        assertEquals(List.of(List.of(2L, 4L, 6L, 8L), List.of(2L, 4L), 3L, 2L), result.printed());
    }

    @Test
    void aClosureWithANamedParameterAndEach() {
        GremlinScript.Result result = GremlinScript.parse(
                "def out = []\n[10, 20].eachWithIndex { value, index -> out = out.add(index + ':' + value) }\n"
                        + "print(out)").execute(new Stub());
        assertEquals(List.of(List.of("0:10", "1:20")), result.printed());
    }

    @Test
    void stringHelpersBehaveLikeGroovy() {
        GremlinScript.Result result = GremlinScript.parse(
                "def s = 'abc'\nprint(s.size())\nprint(s.isEmpty())\nprint(''.isEmpty())")
                .execute(new Stub());
        assertEquals(List.of(3L, false, true), result.printed());
    }

    @Test
    void aClosureCanReadTheElementsOfATraversal() {
        GremlinVertex a = new GremlinVertex("Account", "a", Map.of("status", "ACTIVE"), 0L, 0L);
        GremlinVertex b = new GremlinVertex("Account", "b", Map.of("status", "FROZEN"), 0L, 0L);
        Stub stub = new Stub(values(a, b));

        GremlinScript.Result result = GremlinScript.parse(
                "def nodes = g.V('Account')\n"
                        + "def statuses = nodes.collect { it.value('status') }\n"
                        + "def active = nodes.findAll { it.value('status') == 'ACTIVE' }.collect { it.id() }\n"
                        + "print(statuses)\nprint(active)").execute(stub);

        assertEquals(List.of(List.of("ACTIVE", "FROZEN"), List.of("a")), result.printed());
    }

    @Test
    void aForLoopWalksTheElementsOfATraversal() {
        GremlinVertex a = new GremlinVertex("Account", "a", Map.of(), 0L, 0L);
        GremlinVertex b = new GremlinVertex("Account", "b", Map.of(), 0L, 0L);
        Stub stub = new Stub(values(a, b));

        GremlinScript.Result result = GremlinScript.parse(
                "def nodes = g.V('Account')\n"
                        + "def out = []\n"
                        + "for (node in nodes) { out = out.add(node.id()) }\n"
                        + "print(out)").execute(stub);

        assertEquals(List.of(List.of("a", "b")), result.printed());
    }

    @Test
    void aPlusJoinsLabelsAndAddsNumbers() {
        GremlinScript.Result result = GremlinScript.parse(
                "def a = g.V('s','Account').out().values('id')\n"
                        + "print('ids: ' + a)\n"
                        + "print(2 + 3)").execute(new Stub(values("m1", "m2")));

        assertEquals(List.of("ids: [m1, m2]", 5L), result.printed());
    }

    @Test
    void aStringIdWithSpacesIsQuotedWhenSubstituted() {
        Stub stub = new Stub(values("an id"), values());
        GremlinScript.parse("def ids = g.V('v1', 'person').values('id')\ng.V(ids, 'person')").execute(stub);

        assertTrue(stub.ran.get(1).startsWith("g.V('an id', 'person')"), stub.ran.get(1));
    }
}
