# Graph algorithms

Algorithm steps run over the induced subgraph a traversal reached. They run in-process by default and on
a Gemini cluster with the `DIST` marker; `DIST` is an error when no cluster is configured, never a silent
fallback. The UI colours the canvas with the result, which is the quickest way to see what an algorithm
computed.

![PageRank colouring the canvas](screenshots/olap-pagerank.png)

![Louvain communities on the canvas](screenshots/olap-communities.png)

| Step | Runs over | Distributed form |
| --- | --- | --- |
| `pagerank([iterations])` | the reached subgraph | `pagerank(DIST, 10)` |
| `louvain([iterations])` | communities | `louvain(DIST, 20)` |
| `cc()` | connected components | `cc(DIST)` |
| `degree()` | how many edges touch a vertex | `degree(DIST)` |
| `closeness()` | how close the rest of the graph is to a vertex | `closeness(DIST[, sources])` |
| `betweenness()` | how many shortest paths go through a vertex | `betweenness(DIST[, sources])` |
| `eigenvector()` | influence arriving along the edges | `eigenvector(DIST[, iterations])` |
| `sssp(DIST[, root])` | shortest paths (distributed only) | `sssp(DIST, 'a1')` |
| `bfs(DIST[, root])` | breadth-first reachability (distributed only) | `bfs(DIST, 'a1')` |

```groovy
g.V('135','Account').bothE('Payment').otherV().pagerank(10)   // in this process
g.V('135','Account').bothE('Payment').otherV().cc(DIST)       // on the Gemini cluster
```

## Centrality

Four centralities answer "how important is this vertex", each with its own idea of important. The
in-memory form takes no argument; the `DIST` form runs on the cluster and takes the one parameter in the
table above.

- **`degree()`** counts the edge records that touch a vertex. Both engines count what storage returned,
  so the number is the degree of the induced subgraph, self-loops included, and it is the figure the
  layout already holds — a distributed run exchanges no messages at all.
- **`closeness()`** is the Wasserman-Faust form, `(r-1)² / ((n-1) * Σd)`, where `r` counts the vertices a
  vertex can reach (itself included) and `Σd` the hops to them. Measuring a vertex against what it
  reaches rather than against the whole graph keeps a vertex of a small component from scoring zero; a
  vertex that reaches only itself scores zero by definition.
- **`betweenness()`** is Brandes' algorithm, normalised by the number of pairs, so a vertex every
  shortest path goes through scores one. The graph is undirected here (both directions of every edge are
  read), and parallel edges are counted as parallel paths.
- **`eigenvector()`** is the principal eigenvector of the adjacency as stored, by power iteration,
  normalised to unit length with its largest entry positive — so `eigenvector()` and `eigenvector(DIST)`
  answer the same vector, not merely the same direction. A bipartite graph has no single dominant
  direction (its spectrum is symmetric), so there the iteration oscillates rather than settles;
  `iterations` bounds it either way.

The two path centralities are exact by default and cost one cluster run per source: a closeness score is
a sum over destinations and a betweenness score a sum over sources, and neither is visible from one
vertex's superstep. `closeness(DIST, k)` and `betweenness(DIST, k)` run `k` evenly spaced sources instead
and scale the sums, which is the sampled approximation of Brandes & Pich — exact when every source runs.

```groovy
g.V('135','Account').bothE('Payment').otherV().betweenness()          // exact, in this process
g.V('135','Account').bothE('Payment').otherV().betweenness(DIST, 8)  // 8 sampled sources, on the cluster
```

## In the UI

The toolbar runs the same steps (Louvain, PageRank, connected components, the four centralities and
shortest path) on the subgraph currently drawn. A centrality colours the canvas as a heat (cold for the
lowest score, warm for the highest) with the top 20% ringed in gold, computed in the page over the
largest single vertex type, exactly as the component colourings narrow a mixed graph.

The distributed engine, the partitioned scan and the worker layout are described in
[DESIGN.md](DESIGN.md#distributed-execution-gemini).
