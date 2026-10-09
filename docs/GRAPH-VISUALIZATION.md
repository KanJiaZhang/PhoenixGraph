# Graph visualization: what we have, and what is worth adopting

> A survey of the options, a comparison, and a staged recommendation for this repository. The numbers
> here come from the libraries' own documentation and from vendor-published comparisons unless a neutral
> source is named: treat them as orders of magnitude, and measure on your own data before committing.
> The two things that are *not* second-hand are what this repository already does (measured on the served
> graphs) and the layout work in `graph-web` (see the verification scripts in `.logs/`).

## 1. What the UI does today

`graph-web` (8080) renders the answer of a query as an induced subgraph:

| Aspect | Today |
| --- | --- |
| Renderer | **SVG**, one `<g>` per vertex and one `<path>` per edge, drawn by D3 |
| Layout | `d3-force` (with x/y/charge/collide), ticked **synchronously** before the first paint so a picture never "explodes" into place |
| Reading modes | force layout; **tree layout** in four orientations (left→right, top→bottom, bottom→top, radial) with a click-to-re-root; algorithm colouring (PageRank, Louvain, components, four centralities, heat for scores); the 三国 map as a Leaflet view inside the same panel |
| Interaction | zoom/pan, brush filtering, drag (pinned), search, legend filters, click for properties, double-click expands one hop, CSV export |

Where it stops being pleasant: SVG cost grows with *elements*, not with nodes. The rule of thumb in the
wild (a 2018 TU Dresden poster put visible loss above ~400 nodes / ~8k elements; Cylynx measured SVG
topping out around 2k nodes on a laptop) matches what we see: a few thousand elements is the comfortable
range, and the engine can produce induced subgraphs far larger than that (one `both('Payment')` over the
payment demo returns tens of thousands of edges).

## 2. The option landscape, by rendering engine

### 2.1 SVG (what we use)

- **Pros**: crisp at any zoom, CSS/DOM selectable and styleable, text labels for free, accessibility,
  easy to debug in the inspector, animations with `d3-transition`.
- **Cons**: every element is a layout object; thousands of nodes cost tens of thousands of DOM nodes.
- **Verdict**: keep it. It is the right renderer for the *answer* of a query (usually a few hundred
  elements), which is what this UI shows.

### 2.2 Canvas 2D

- **Libraries**: `d3-force` + hand-drawn canvas (`force-graph`, `vis-network`, Cytoscape.js default).
- **Scale**: ~10k nodes/edges comfortably; no DOM, so no CSS styling and no per-node DOM handles.
- **Verdict**: a cheap upgrade if the subgraph sizes grow past SVG's comfort: same D3 data model, one
  `<canvas>` and a draw loop. Labels and hit-testing become our code.

### 2.3 WebGL / GPU rendering

- **Libraries**: **sigma.js + graphology** (MIT; graphology is data/algorithms, sigma is the WebGL
  renderer), **cosmos.gl** (MIT, now under the OpenJS Foundation; v3 rebuilds the force simulation itself
  in shaders and advertises hundreds of thousands to **1M+ nodes/links** in real time),
  **Cosmograph** (vendor comparison claims >50k simulated / >1M rendered, beating sigma and
  react-force-graph-3d *by its own table*), **AntV G6** (MIT; 5.0 made the renderer pluggable and added
  `@antv/g6-extension-3d`; the UMD bundle reportedly shrank from 4.0 to 1.8 MB with modularisation).
- **Scale**: 100k–1M+ elements, provided the *layout* is also GPU/worker-side.
- **Cost**: labels, hit-testing and interaction are yours; a second graph model (graphology or the
  library's own) next to the D3 one; and the subgraph still has to arrive over HTTP first.
- **Verdict**: the only honest answer for "the whole graph", and the wrong answer for "the answer of a
  query". Behind a toggle, and only after the payload/streaming story is settled.

### 2.4 3D

- **Libraries**: **three.js** (MIT) directly; **3d-force-graph** / **react-force-graph** (MIT; ThreeJS +
  `d3-force-3d`; ~13.9 MB unpacked, 5 dependencies; the React wrapper is documented as incompatible with
  React 19 ref handling); **deck.gl** (WebGL2; its graph layer with GPU layout is an **RFC**, not
  shipped); **ECharts GL** (`graphGL`, BSD-3-Clause, with globe/3D plot extensions).
- **Why people want it**: depth + motion is genuinely more information per pixel for *structure*
  questions (clusters, bridges, shells), and it demos extremely well.
- **What the evidence says**: the classic result (Ware & Franck) that a VR display is "three times as
  good as a 2D diagram" came from low-resolution hardware; later work ("Reevaluating stereo and motion
  cues") re-tested with high-resolution stereo, and a 2023 preregistered study of *path tracing* questions
  whether 3D keeps its advantage once 2D gets edge-routing and interaction. Read as: 3D helps some
  questions on good hardware, it is not a general upgrade, and it costs labelling and selection.
- **Verdict**: a **demo/insight mode**, not the default reading surface. Implementable in this stack
  (three.js, one `Graph3D` view behind the same canvas panel), and honest to describe as
  "impressive, harder to read precisely".

### 2.5 Immersive / VR

- Research-stage but real: immersive egocentric layouts (ACM SUI 2026), Anu.js for web immersive analytics
  (CHI 2026), bimanual mid-air graph manipulation (CHI 2026), GAV-VR (GD 2024), and `nemosyne` as an
  experimental WebXR framework with seven layout algorithms.
- **Verdict**: watch it; a WebXR view of a knowledge graph is a conference demo, not a product surface
  for analysts today.

### 2.6 Layout is a separate decision from rendering

The renderer decides how many elements you can draw; the layout decides whether the picture means
anything. Worth knowing:

| Layout | Library | Fits |
| --- | --- | --- |
| Force-directed | `d3-force` (today), `d3-force-3d`, cosmos.gl's shader version | dense, cluster-shaped answers |
| Tree / radial | `d3-hierarchy` (**used by the toolbar's tree layout**) | a hierarchy: attribution chains, schemas, call graphs |
| Layered DAG (Sugiyama) | **d3-dag** (TypeScript, "optimal edge-crossing minimisation", a fraction of elkjs's ~500 KB), **dagre**, **elkjs** | metric DAGs, lineage, dependency graphs - where minimising crossings is the whole point |
| Orthogonal / port-aware | **elkjs** | engineering/architecture diagrams |

Our semantic layer is a **DAG** (`contributesTo`, `computedFrom`, `usesColumn`), which is the case where a
layered layout beats both force and a spanning tree: force ignores direction, and a tree has to cut the
extra edges. That is the single most valuable layout addition after the tree we just built.

## 3. Comparison

| Option | Engine | Practical scale | Layouts built in | 3D | Bundle / deps | License | Integration cost here |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **d3 + SVG** (today) | SVG | ~1–2k elements | force, tree (ours), radial (ours) | no | ~280 KB (vendored) | ISC | - |
| d3 + Canvas 2D | Canvas | ~10k elements | force + whatever we write | no | ~280 KB | ISC | low: one draw loop |
| **sigma.js + graphology** | WebGL | ~100k–1M | force (graphology), no layout engine of its own | no | moderate | MIT | medium: second graph model, own labels/hit-test |
| **cosmos.gl v3** | WebGL + GPU sim | 1M+ (vendor) | GPU force | no (2D/2.5D) | moderate | MIT (OpenJS) | medium-high: own rendering of labels/selection |
| **AntV G6 5** | Canvas/WebGL, pluggable | ~10k–100k | many (incl. dagre-like), 3D extension | yes (`g6-extension-3d`) | UMD ~1.8 MB | MIT | medium: its own data model and layout system |
| Cytoscape.js | Canvas (WebGL add-on) | ~10k | many | no | moderate | MIT | medium |
| vis-network | Canvas | ~10k | few | no | moderate | MIT/Apache | low-medium |
| **three.js / 3d-force-graph** | WebGL 3D | ~10k nodes | `d3-force-3d` | yes | 3d-force-graph ~13.9 MB unpacked | MIT | medium: labels/selection/legibility work |
| deck.gl | WebGL2 | ~1M (2D), graph layer is an RFC | GPU force (RFC) | yes | large | MIT | high: RFC features, map-first API |
| ECharts GL (`graphGL`) | WebGL | ~10k–100k | force, 3D | yes | ~1 MB+ | BSD-3 | medium: ECharts option model |
| WebXR (Anu.js, nemosyne) | WebXR | small | several | VR/AR | research | varies | high, research-stage |

Two caveats that matter more than the table: (1) most scale figures are the libraries' own, measured on
hardware nobody writes down; (2) the *payload* is usually the real limit in a database UI - a renderer
that can draw a million nodes is useless if the answer takes a minute to arrive and a gigabyte to hold.
The engine has the answer to that question (partitioned scans, `limit`, projections), and the UI already
caps what it draws.

## 4. Recommendation for PhoenixGraph

Staged, each step useful on its own. Steps 1 and 4 are **done in this repository today**:

1. **Keep SVG for the query answer; keep and extend the *layouts*.** Done: the tree layout (four
   orientations - left→right, **right→left**, top→bottom, bottom→top - plus radial, with click-to-re-root)
   **and the layered DAG** (`layoutDag` in `js/app.js`: depth-first cycle breaking with the reversed edges
   drawn dashed, longest-path ranking, four barycenter sweeps, **four orientations, every edge kept**). Both
   are ~120 lines of our own code with **no new dependency and no build step**, because the input is small
   (a query's answer) and the data shape is fixed (`{vertices, edges}`). Right-to-left is the same layering
   with the depth axis negated, so the crossing order the barycenter sweeps found is preserved rather than
   mirrored into a different count. If crossing minimisation ever matters more than zero dependencies,
   `d3-dag` is the drop-in upgrade: the layout function is the only thing that would change.
2. **Canvas 2D when the subgraph is big.** A `renderer: svg | canvas` switch: past ~2k elements draw the
   same D3 data on `<canvas>` (labels only above a zoom threshold). Low risk, no new dependency, and it
   removes the first hard wall.
3. **WebGL behind a toggle for "the whole graph"** (sigma.js if the graph model matters, cosmos.gl if the
   size does). This is where a *streaming/partitioned* render path belongs; it is also the point where
   the UI stops being "the answer" and becomes "the map".
4. **A 3D view as a mode of the canvas - done at the smallest possible cost, and then made to agree with
   everything else.** `js/graph3d.js` is a *renderer* (a 3D spring/charge layout, a perspective
   projection, painter's algorithm, labels only for the near vertices, a chevron on each edge so the
   direction survives the projection) and `app.js` hands it the answer with **the colours, alphas and
   rings the 2D canvas has already computed** (`readCanvasState` reads the DOM, so 3D can never disagree
   with 2D about what a filter dimmed or a search hit). It is toggled by the toolbar's cube icon inside
   the visualization panel - the same place as the map, and the two views are mutually exclusive - so
   there is no second page, no second palette and no second data path to keep in step. **Zero
   dependencies, no build step.** Measured on this machine: the payment 2-hop answer (51 / 136) settles in
   ~12 ms for 260 iterations; the semantic layer's 39 / 36 draws with its four vertex types and three
   relation types in the colours the legend shows; the 三国 1-hop (178 / 225) redraws; rotating moves every
   projected point, and the canvas is genuinely painted (sampled). Two bugs this step found and fixed, both
   invisible in 2D: pulling once per *edge* let parallel edges multiply the spring force (333 edges over 54
   vertices flew apart until the projection collapsed to a dot - the layout now has one weighted spring per
   *pair*, and normalises the settled world before projecting), and a `<canvas>` is a replaced element, so
   `inset: 0` alone left it at its intrinsic 320×240 - it needs `width/height: 100%`. If 3D ever stops
   being a demo, three.js or `3d-force-graph` goes behind the same data contract: the module already only
   knows `{key, label, fill, alpha, ring}` and `{source, target, stroke, alpha, width, dashed}`.
5. **Immersive: watch, do not build yet.**

What we should *not* do: swap the whole UI to a graph framework (G6/Cytoscape/vis-network). Our canvas is
not a generic graph viewer - it is the answer of a query inside a database workbench, with the builder,
the metrics, the Rows panel and the map in one place. A framework would take over the data model and the
interaction model and give us back layout variety we can add one layout at a time.

## 5. Trying an option (sketch)

```bash
# sigma.js: WebGL, 100k+ elements, needs graphology for the model
npm i sigma graphology graphology-layout-forceatlas2

# cosmos.gl: the force simulation runs on the GPU
npm i @cosmos.gl/graph

# 3d-force-graph: ThreeJS + d3-force-3d (also usable without React)
npm i 3d-force-graph

# d3-dag: the layered DAG layout the semantic layer wants next
npm i d3-dag
```

The integration shape in this repository is always the same, and it is why the options above are
swappable: the engine hands the UI `{vertices, edges}` for a query (plus `result` rows and provenance),
and a view mode is a function from that shape to pixels - rendering, layout and interaction in one place,
behind a toolbar switch, next to `treeSelect` (`js/app.js`: `layoutTree` is the tree one).
