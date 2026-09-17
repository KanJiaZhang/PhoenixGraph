const fs = require('fs');
const path = 'graph-http/src/test/java/com/pengcheng/phoenixgraph/http/controller/QueryControllerTest.java';
let s = fs.readFileSync(path, 'utf8');

// every pre-existing stub/verify predates the includeResult flag: add it as false
const swaps = [
  [`        verify(queryService).query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false);`,
   `        verify(queryService).query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false, false);`],
  [`        verify(queryService).query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), eq(true));`,
   `        verify(queryService).query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), eq(true),
                eq(true));`],
  [`        when(queryService.query(anyString(), anyString(), anyMap(), anyString(), anyBoolean()))`,
   `        when(queryService.query(anyString(), anyString(), anyMap(), anyString(), anyBoolean(), anyBoolean()))`],
  [`        verify(queryService).query("payment-graph", "g.V()", Map.of(), "gremlin", false);`,
   `        verify(queryService).query("payment-graph", "g.V()", Map.of(), "gremlin", false, false);`],
  [`        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), anyString(), anyBoolean()))`,
   `        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), anyString(), anyBoolean(),
                anyBoolean()))`],
  [`        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), anyBoolean()))`,
   `        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("cypher"), anyBoolean(),
                anyBoolean()))`],
  [`        when(queryService.query(eq("nope"), anyString(), anyMap(), anyString(), anyBoolean()))`,
   `        when(queryService.query(eq("nope"), anyString(), anyMap(), anyString(), anyBoolean(), anyBoolean()))`],
  [`        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("sparql"), anyBoolean()))`,
   `        when(queryService.query(eq("payment-graph"), anyString(), anyMap(), eq("sparql"), anyBoolean(),
                anyBoolean()))`],
  // the two "full shape" stubs that spell out the language argument
  [`        when(queryService.query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false)).thenReturn(List.of(6L));`,
   `        when(queryService.query("payment-graph", "g.V('${id}','Account').count()", Map.of("id", "1001"),
                "gremlin", false, false)).thenReturn(List.of(6L));`],
  [`        when(queryService.query("payment-graph", "g.V(888,'Account').out('Payment')", Map.of(),
                "gremlin", true)).thenReturn(subgraph);`,
   `        when(queryService.query("payment-graph", "g.V(888,'Account').out('Payment')", Map.of(),
                "gremlin", true, false)).thenReturn(subgraph);`],
  [`        when(queryService.query("payment-graph",
                "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.amount",
                Map.of("id", "100"), "cypher", true)).thenReturn(result);`,
   `        when(queryService.query("payment-graph",
                "MATCH (a:Account {id: $id})-[p:Payment]->(b:Account) RETURN b.id, p.amount",
                Map.of("id", "100"), "cypher", true, false)).thenReturn(result);`],
];

for (const [from, to] of swaps) {
  if (s.includes(from)) {
    s = s.replace(from, to);
  } else {
    console.log('skip (not present):', from.trim().split('\n')[0].trim().slice(0, 70));
  }
}
fs.writeFileSync(path, s);
console.log('stubs updated to the includeResult signature');
