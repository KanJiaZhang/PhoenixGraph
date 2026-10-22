# The web UI

`graph-web` (port 8080) is a single-page canvas over the HTTP API. It is part of the running service, not
a separate product, and it is the quickest way to see what a query returned. For rendering options and
why the UI uses SVG, see [GRAPH-VISUALIZATION.md](GRAPH-VISUALIZATION.md).

## Document ingestion

The toolbar's document icon opens a form that posts to `POST /api/v1/agent/ingest` through `graph-web`:
choose a graph name (defaults to an unused `uploaded-docs`), a rule pack (`sanguo` or `incident`), and one or
more Markdown, text or HTML files. The optional dictionary and alias map are JSON objects. The result shows
the entity/relation counts and the quality report, then selects the new graph.

PDF is not parsed in the browser: use `tools/ingest-docs.mjs` (with `pdftotext`) or the CLI. With security
enabled, the key `graph-web` forwards must have the `admin` role, because ingestion is an admin endpoint.

## Query editor

The editor takes Gremlin, with example pickers per graph and a script mode for the Groovy subset
(`script: true`). A query that returns rows rather than a graph opens the result tab itself, so a
`count()`, a `groupCount()` or a list of ids is shown as a table instead of leaving the stats tab in
front. Values longer than a cell shows are clipped with a click-to-expand window.

## Canvas

The canvas draws the induced subgraph the query touched, with the properties the engine returned:

- force layout, with re-balance, zoom-to-fit, zoom/pan, brush selection and a text search;
- click a vertex for its properties; double-click expands one hop;
- a legend with per-type colours and counts, and filter toggles;
- CSV export of the current answer.

Edges are coloured by relation type, not one grey for everything, so a multi-relation answer stays
readable.

## Algorithm colouring

The toolbar runs Louvain, PageRank, connected components, the four centralities and shortest path on the
subgraph currently drawn. A centrality colours the canvas as a heat (cold for the lowest score, warm for
the highest) with the top 20% ringed in gold. The computation runs in the page over the largest single
vertex type, matching the way component colourings narrow a mixed graph. See
[ALGORITHMS.md](ALGORITHMS.md).

## Layouts

Besides the force layout, the toolbar offers:

- a **tree** layout (spanning, smooth links, click a vertex to re-root) in four orientations;
- a **layered DAG** layout: cycles broken with reversed edges drawn dashed, longest-path ranks,
  barycenter ordering within a layer, every edge kept. It can run left→right, right→left, top→bottom or
  bottom→top.

Both resolve each link's endpoints to its vertices directly, so links stay attached when the force
simulation is skipped.

## 3D view

The 3D view is a mode of the same canvas, toggled from the toolbar (or `Esc`/back to leave). It uses a
hand-rolled 3D force layout and a perspective projection on a plain canvas, with no WebGL and no new
dependency. It reads the colours the 2D canvas has already computed, so vertices and edges keep their
per-type colours and highlights (legend dimming, search hits, shortest path, centrality heat, selection)
look the same in 3D. Drag to rotate, wheel to zoom, click to select, double-click to expand; in a tree or
DAG a click re-roots.

## Schema builder and metrics

`builder.html` (the "图管理" button) authors a schema: vertices, edges, properties, storage type and path,
with a draft preview. It also edits the fuzzy-link patterns. Per-graph metrics are available from the
metrics endpoints; see [DESIGN.md § Monitoring](DESIGN.md#monitoring).

## Map (optional)

When the selected graph declares a coordinate property and the store supports scanning, a map icon
appears in the toolbar. It replaces the canvas with a Leaflet map of the graph's entities. A query that
reads coordinates (`valueMap('name','latitude','longitude')`) puts its own points on the map, and the
note switches between those points and everything the graph holds. Leaflet is vendored (no CDN),
OpenStreetMap is the basemap, and 高德 takes over with the WGS84 → GCJ-02 conversion when OSM is
unreachable. Geo queries themselves are documented in [QUERY.md](QUERY.md#geo-search-optional).
