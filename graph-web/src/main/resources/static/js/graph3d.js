/*
 * The 3D view of the canvas panel - a renderer, not a page.
 *
 * <p>It draws the answer the panel is already showing, from an angle: a hand-rolled 3D force layout, a
 * perspective projection and a painter's-algorithm paint on a plain 2D canvas. No WebGL, no three.js, no
 * new dependency and no build step, because the input is small (one query's answer) and the data shape is
 * already fixed. The cost of the "real" libraries is not the download: it is that picking, labels and the
 * whole colour/highlight pipeline would have to be rewritten against their scene graph.
 *
 * <p>So this module deliberately knows <b>nothing</b> about vertices, graph names or queries: the caller
 * hands it nodes and edges *with their colours, alphas and rings already resolved* from what the 2D canvas
 * is showing (see `readCanvasState` in app.js). That is what makes 3D "compatible with everything": a
 * legend filter, a search hit, a shortest path, a PageRank colouring or a selected vertex looks the same
 * here as it does in 2D, because it *is* the same computed colour.
 *
 * <p>What stays 2D-only is the gestures that are drags: box selection and vertex dragging (here a drag on
 * empty space rotates the scene). Filters, selection, expansion, panels and the legend all keep working.
 *
 * Data contract, one call to {@link mount}:
 *   nodes: {key, label, id, fill, alpha, ring, seed:{x,y}}
 *   links: {source, target, stroke, alpha, width, dashed}
 */
(function () {
  'use strict';

  /** Resolves any CSS colour to rgb() with an alpha, so the paint can fade by depth. */
  function withAlpha(color, alpha) {
    if (alpha >= 0.999) return color;
    const text = String(color || '');
    let rgb = null;
    const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(text.trim());
    if (hex) {
      const raw = hex[1].length === 3 ? hex[1].split('').map((c) => c + c).join('') : hex[1];
      rgb = [parseInt(raw.slice(0, 2), 16), parseInt(raw.slice(2, 4), 16), parseInt(raw.slice(4, 6), 16)];
    } else {
      const match = /rgba?\(([^)]+)\)/i.exec(text);
      if (match) {
        const parts = match[1].split(/[,\s/]+/).filter(Boolean).map(Number);
        rgb = [parts[0] || 0, parts[1] || 0, parts[2] || 0];
        if (parts.length > 3) alpha *= parts[3];
      }
    }
    if (!rgb) return text; // a named colour or a gradient: leave it to the browser
    return `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${Math.max(0, Math.min(1, alpha)).toFixed(3)})`;
  }

  /**
   * Mounts the 3D view on a canvas. Returns the controller the panel drives:
   * setData, settle, start, stop, resize, draw, rotate, zoomBy, resetView, pick, project, state.
   */
  function mount(canvas, options) {
    const opts = options || {};
    const ctx = canvas.getContext('2d');
    const state = {
      nodes: [],                 // {key, ..., x, y, z, vx, vy, vz}
      byKey: new Map(),
      edges: [],                 // {source, target, stroke, alpha, width, dashed, a, b}
      springs: [],               // one per vertex pair, weighted by how many edges connect it
      yaw: opts.yaw == null ? -0.62 : opts.yaw,
      pitch: opts.pitch == null ? 0.34 : opts.pitch,
      zoom: 1,
      focal: 900,
      hover: null,
      labelScale: 0.95,          // labels only above this depth scale, or nothing is readable
      energy: 0,
      frames: 0,
      drawn: false,
      raf: 0,
    };

    // ---------------------------------------------------------------- data

    /** The world-space seed of a vertex: the 2D position when there is one, else a deterministic sphere. */
    function seedOf(node, box) {
      if (node.seed && Number.isFinite(node.seed.x) && Number.isFinite(node.seed.y)) {
        // keep the shape the 2D layout just gave the drawing (including a tree or a layered DAG): the
        // 3D pass is a relaxation *from* that reading, not a different reading
        const span = Math.max(160, box.halfX, box.halfY);
        return { x: node.seed.x - box.cx, y: node.seed.y - box.cy, z: (hash(node.key + '|z') - 0.5) * span * 0.25 };
      }
      return {
        x: Math.sin(hash(node.key) * 2.399) * 220,
        y: Math.cos(hash(node.key) * 1.618) * 220,
        z: Math.sin(hash(node.key) * 0.707) * 220,
      };
    }

    function hash(text) {
      let value = 0;
      const source = String(text == null ? '' : text);
      for (let i = 0; i < source.length; i += 1) value = (value * 31 + source.charCodeAt(i)) % 100000;
      return value;
    }

    /**
     * Replaces the scene. A vertex that was already there keeps its position and its velocity, so
     * running a second query keeps the shared part of the answer where the reader last left it and only
     * the new part flies in - which is what makes the 3D view read *with* the query, not flash on it.
     */
    function setData(nodes, links, settings) {
      const wanted = Array.isArray(nodes) ? nodes : [];
      const box = boundingBox(wanted);
      const next = [];
      for (const node of wanted) {
        const old = state.byKey.get(node.key);
        if (old) {
          Object.assign(old, node);
          next.push(old);
          continue;
        }
        const seed = seedOf(node, box);
        next.push(Object.assign({}, node, seed, { vx: 0, vy: 0, vz: 0 }));
      }
      state.nodes = next;
      state.byKey = new Map(next.map((node) => [node.key, node]));
      state.edges = [];
      for (const link of (Array.isArray(links) ? links : [])) {
        const a = state.byKey.get(link.source);
        const b = state.byKey.get(link.target);
        if (!a || !b || a === b) continue;
        state.edges.push({
          source: link.source,
          target: link.target,
          stroke: link.stroke || 'rgba(148, 163, 184, 0.9)',
          alpha: link.alpha == null ? 0.6 : link.alpha,
          width: link.width || 1.4,
          dashed: !!link.dashed,
          a,
          b,
        });
      }
      state.springs = buildSprings();
      if (settings && Number.isFinite(settings.iterations)) settle(settings.iterations);
      if (state.hover && !state.byKey.has(state.hover)) state.hover = null;
      return state;
    }

    /**
     * The springs of the layout: **one per vertex pair**, weighted by how many edges connect them.
     *
     * <p>A drawn subgraph can hold hundreds of parallel edges (this repository's payment demo has a pair
     * joined by a dozen payments). Pulling once per edge multiplies the spring by the multiplicity and the
     * relaxation flies apart - which is exactly what happened before this: with 333 edges over 54 vertices
     * the step hit its clamp every iteration and the world grew until the projection collapsed to a dot.
     * The weight is logarithmic because the tenth parallel edge is not ten times the evidence of the first.
     */
    function buildSprings() {
      const pairs = new Map();
      for (const edge of state.edges) {
        const key = edge.source < edge.target ? `${edge.source}|${edge.target}` : `${edge.target}|${edge.source}`;
        const found = pairs.get(key);
        if (found) found.count += 1;
        else pairs.set(key, { a: edge.a, b: edge.b, count: 1 });
      }
      return [...pairs.values()].map((pair) => ({ a: pair.a, b: pair.b, weight: 1 + Math.log2(pair.count) }));
    }

    /**
     * Puts the settled world where the projection expects it: centred on its own centroid and about 200
     * units across, whatever the forces did with it. The perspective divide is what makes this necessary -
     * it is not scale invariant, so a world that drifted ten times too far would project to a single dot.
     */
    function normalise() {
      const nodes = state.nodes;
      if (!nodes.length) return;
      let cx = 0, cy = 0, cz = 0;
      for (const node of nodes) { cx += node.x; cy += node.y; cz += node.z; }
      cx /= nodes.length; cy /= nodes.length; cz /= nodes.length;
      let radius = 0;
      for (const node of nodes) {
        radius += (node.x - cx) ** 2 + (node.y - cy) ** 2 + (node.z - cz) ** 2;
      }
      radius = Math.sqrt(radius / nodes.length);
      const scale = radius > 1 ? 200 / radius : 1;
      for (const node of nodes) {
        node.x = (node.x - cx) * scale;
        node.y = (node.y - cy) * scale;
        node.z = (node.z - cz) * scale;
        node.vx = 0; node.vy = 0; node.vz = 0;
      }
    }

    function boundingBox(nodes) {
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
      for (const node of nodes) {
        if (!node.seed) continue;
        minX = Math.min(minX, node.seed.x); maxX = Math.max(maxX, node.seed.x);
        minY = Math.min(minY, node.seed.y); maxY = Math.max(maxY, node.seed.y);
      }
      if (!Number.isFinite(minX)) return { cx: 0, cy: 0, halfX: 0, halfY: 0 };
      return {
        cx: (minX + maxX) / 2,
        cy: (minY + maxY) / 2,
        halfX: (maxX - minX) / 2,
        halfY: (maxY - minY) / 2,
      };
    }

    // ---------------------------------------------------------------- 3D force layout
    //
    // Spring + charge, straight O(n^2) with a distance cutoff: Barnes-Hut would be O(n log n) and is the
    // next step if the answers ever get big, but a query's answer is hundreds of vertices, where the
    // simple pass is faster than the tree it would build.

    function tick() {
      const nodes = state.nodes;
      const n = nodes.length;
      if (!n) { state.energy = 0; return; }
      const cutoff2 = 520 * 520;
      const linkDistance = n > 400 ? 70 : 110;
      for (let i = 0; i < n; i += 1) {
        const a = nodes[i];
        for (let j = i + 1; j < n; j += 1) {
          const b = nodes[j];
          const dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
          const d2 = dx * dx + dy * dy + dz * dz;
          if (d2 > cutoff2 || d2 === 0) continue;
          const d = Math.sqrt(d2);
          const force = 2600 / d2;
          const ux = (dx / d) * force, uy = (dy / d) * force, uz = (dz / d) * force;
          a.vx += ux; a.vy += uy; a.vz += uz;
          b.vx -= ux; b.vy -= uy; b.vz -= uz;
        }
      }
      for (const spring of state.springs) {
        const a = spring.a, b = spring.b;
        const dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
        const pull = (dist - linkDistance) * 0.06 * spring.weight;
        const px = (dx / dist) * pull, py = (dy / dist) * pull, pz = (dz / dist) * pull;
        a.vx += px; a.vy += py; a.vz += pz;
        b.vx -= px; b.vy -= py; b.vz -= pz;
      }
      let energy = 0;
      for (const node of nodes) {
        node.vx -= node.x * 0.0016; node.vy -= node.y * 0.0016; node.vz -= node.z * 0.0016; // gravity
        node.vx *= 0.82; node.vy *= 0.82; node.vz *= 0.82;
        const mx = Math.max(-30, Math.min(30, node.vx));
        const my = Math.max(-30, Math.min(30, node.vy));
        const mz = Math.max(-30, Math.min(30, node.vz));
        node.x += mx; node.y += my; node.z += mz;
        energy += mx * mx + my * my + mz * mz;
      }
      state.energy = energy / n;
    }

    /** Runs the relaxation synchronously: the first paint after a query is already settled. */
    function settle(iterations) {
      for (let i = 0; i < Math.max(0, iterations || 0); i += 1) tick();
      normalise();
      return state;
    }

    /**
     * Keeps relaxing on animation frames until the scene is calm, so a new query's vertices visibly
     * settle instead of appearing fully formed - and so rotating while it settles stays smooth.
     */
    function start(limit) {
      stop();
      state.frames = 0;
      const maxFrames = limit || 420;
      const stepsPerFrame = state.nodes.length > 600 ? 1 : state.nodes.length > 200 ? 2 : 3;
      const step = () => {
        if (!state.nodes.length) { state.raf = 0; return; }
        for (let i = 0; i < stepsPerFrame; i += 1) tick();
        normalise();
        state.frames += 1;
        draw();
        if (!opts.autoRelax || state.energy < 0.02 || state.frames >= maxFrames) { state.raf = 0; return; }
        state.raf = requestAnimationFrame(step);
      };
      if (opts.autoRelax) state.raf = requestAnimationFrame(step);
      draw();
      return state;
    }

    function stop() {
      if (state.raf) cancelAnimationFrame(state.raf);
      state.raf = 0;
    }

    // ---------------------------------------------------------------- projection and painting

    function viewport() {
      const width = canvas.width;
      const height = canvas.height;
      return { cx: width / 2, cy: height / 2, zoom: state.zoom * Math.min(width, height) / 900 };
    }

    /** Rotates every vertex by yaw (around Y) and pitch (around X), then projects it with perspective. */
    function project() {
      const { cx, cy, zoom } = viewport();
      const cosYaw = Math.cos(state.yaw), sinYaw = Math.sin(state.yaw);
      const cosPitch = Math.cos(state.pitch), sinPitch = Math.sin(state.pitch);
      const out = [];
      for (const node of state.nodes) {
        const x1 = node.x * cosYaw - node.z * sinYaw;
        const z1 = node.x * sinYaw + node.z * cosYaw;
        const y2 = node.y * cosPitch - z1 * sinPitch;
        const z2 = node.y * sinPitch + z1 * cosPitch;
        const scale = state.focal / Math.max(1, state.focal + z2);
        out.push({
          key: node.key,
          node,
          x: cx + x1 * scale * zoom,
          y: cy + y2 * scale * zoom,
          z: z2,
          scale: scale * zoom,
          r: Math.max(1.6, 7 * scale * zoom),
        });
      }
      return out;
    }

    function draw() {
      const width = canvas.width, height = canvas.height;
      if (!width || !height) return; // still hidden: nothing to paint into
      const points = project();
      const byKey = new Map(points.map((point) => [point.key, point]));
      const depthOf = new Map(points.map((point, at) => [point.key, at]));
      points.sort((a, b) => b.z - a.z); // far first: the painter's algorithm

      ctx.clearRect(0, 0, width, height);
      ctx.lineCap = 'round';
      ctx.save();
      ctx.setLineDash([]);
      for (const edge of state.edges) {
        const from = byKey.get(edge.source);
        const to = byKey.get(edge.target);
        if (!from || !to) continue;
        const scale = (from.scale + to.scale) / 2;
        // depth fades: alpha follows how near the edge is, but a dimmed edge (filtered out in 2D) stays dim
        const alpha = Math.max(0, Math.min(1, edge.alpha * (0.35 + Math.min(1, scale) * 0.65)));
        ctx.strokeStyle = withAlpha(edge.stroke, alpha);
        ctx.lineWidth = Math.max(0.5, edge.width * Math.max(0.6, Math.min(1.6, scale)));
        ctx.setLineDash(edge.dashed ? [5, 4] : []);
        ctx.beginPath();
        ctx.moveTo(from.x, from.y);
        ctx.lineTo(to.x, to.y);
        ctx.stroke();
        // a chevron near the target keeps the direction readable, which a bare line in 3D does not
        if (scale > 0.9) {
          const dx = to.x - from.x, dy = to.y - from.y;
          const len = Math.hypot(dx, dy) || 1;
          const ux = dx / len, uy = dy / len;
          const tipX = from.x + ux * len * 0.72, tipY = from.y + uy * len * 0.72;
          const back = 7 * Math.min(1.4, scale), side = 4 * Math.min(1.4, scale);
          ctx.beginPath();
          ctx.moveTo(tipX, tipY);
          ctx.lineTo(tipX - ux * back - uy * side, tipY - uy * back + ux * side);
          ctx.moveTo(tipX, tipY);
          ctx.lineTo(tipX - ux * back + uy * side, tipY - uy * back - ux * side);
          ctx.stroke();
        }
      }
      ctx.setLineDash([]);

      for (let i = points.length - 1; i >= 0; i -= 1) {
        const point = points[i];
        const node = point.node;
        const hovered = state.hover === node.key;
        const near = Math.max(0, Math.min(1, (point.scale - 0.3) / 0.9));
        ctx.globalAlpha = Math.max(0, Math.min(1, node.alpha * (0.45 + near * 0.55)));
        ctx.beginPath();
        ctx.arc(point.x, point.y, point.r, 0, Math.PI * 2);
        ctx.fillStyle = node.fill || '#94a3b8';
        ctx.fill();
        if (node.ring || hovered) {
          ctx.lineWidth = hovered ? 3 : 2.4;
          ctx.strokeStyle = hovered ? '#ffffff' : node.ring;
          ctx.stroke();
        }
        ctx.globalAlpha = 1;
        if (point.scale > state.labelScale || hovered) {
          const label = String(node.label == null ? '' : node.label);
          ctx.font = `${hovered ? '600 ' : ''}11px system-ui, -apple-system, "Segoe UI", sans-serif`;
          ctx.lineWidth = 3;
          ctx.strokeStyle = 'rgba(15, 23, 42, 0.85)'; // a dark halo, so a label stays readable over an edge
          ctx.strokeText(label, point.x + point.r + 3, point.y + 4);
          ctx.fillStyle = `rgba(226, 232, 240, ${Math.min(0.95, 0.35 + point.scale * 0.6)})`;
          ctx.fillText(label, point.x + point.r + 3, point.y + 4);
        }
      }
      ctx.restore();
      state.drawn = true;
      void depthOf;
    }

    // ---------------------------------------------------------------- interaction

    /** The vertex under a canvas-relative point, or null: nearest centre within its own radius, plus slack. */
    function pick(x, y) {
      let best = null;
      let bestDistance = Infinity;
      for (const point of project()) {
        const dx = point.x - x, dy = point.y - y;
        const distance = Math.hypot(dx, dy);
        if (distance > point.r + 5) continue;
        if (distance < bestDistance) { bestDistance = distance; best = point.key; }
      }
      return best;
    }

    function localPoint(event) {
      const box = canvas.getBoundingClientRect();
      return { x: event.clientX - box.left, y: event.clientY - box.top };
    }

    function rotate(yaw, pitch) {
      state.yaw = yaw;
      if (pitch != null) state.pitch = Math.max(-1.45, Math.min(1.45, pitch));
      draw();
      return state;
    }

    function zoomBy(factor) {
      state.zoom = Math.max(0.2, Math.min(6, state.zoom * factor));
      draw();
      return state;
    }

    function resetView() {
      state.yaw = opts.yaw == null ? -0.62 : opts.yaw;
      state.pitch = opts.pitch == null ? 0.34 : opts.pitch;
      state.zoom = 1;
      draw();
      return state;
    }

    /** Fits the canvas to its CSS box at the device pixel ratio, and paints. */
    function resize() {
      const ratio = window.devicePixelRatio || 1;
      const width = Math.max(320, Math.round(canvas.clientWidth * ratio));
      const height = Math.max(240, Math.round(canvas.clientHeight * ratio));
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      draw();
      return state;
    }

    let drag = null;
    canvas.addEventListener('mousedown', (event) => {
      if (event.button !== 0) return;
      event.preventDefault();
      const point = localPoint(event);
      drag = {
        x: event.clientX,
        y: event.clientY,
        yaw: state.yaw,
        pitch: state.pitch,
        moved: 0,
        key: pick(point.x, point.y),
      };
      canvas.style.cursor = 'grabbing';
    });
    window.addEventListener('mousemove', (event) => {
      if (!drag) {
        const point = localPoint(event);
        const key = pick(point.x, point.y);
        if (key !== state.hover) {
          state.hover = key;
          canvas.style.cursor = key ? 'pointer' : 'grab';
          if (opts.onHover) opts.onHover(key, key ? state.byKey.get(key) : null);
          draw();
        }
        return;
      }
      drag.moved += Math.abs(event.clientX - drag.x) + Math.abs(event.clientY - drag.y);
      state.yaw = drag.yaw + (event.clientX - drag.x) * 0.008;
      state.pitch = Math.max(-1.45, Math.min(1.45, drag.pitch - (event.clientY - drag.y) * 0.008));
      draw();
    });
    window.addEventListener('mouseup', () => {
      if (!drag) return;
      const picked = drag.moved < 5 ? drag.key : null; // a drag rotates, a click picks
      drag = null;
      canvas.style.cursor = 'grab';
      if (picked && opts.onPick) opts.onPick(picked, state.byKey.get(picked));
    });
    canvas.addEventListener('mouseleave', () => {
      if (state.hover) {
        state.hover = null;
        if (opts.onHover) opts.onHover(null, null);
        draw();
      }
    });
    canvas.addEventListener('wheel', (event) => {
      event.preventDefault();
      zoomBy(event.deltaY > 0 ? 0.92 : 1.08);
    }, { passive: false });

    /** What a test (or a probe) reads back. */
    function snapshot() {
      return {
        nodes: state.nodes,
        edges: state.edges,
        yaw: state.yaw,
        pitch: state.pitch,
        zoom: state.zoom,
        energy: state.energy,
        frames: state.frames,
        drawn: state.drawn,
        hover: state.hover,
        raf: state.raf,
      };
    }

    canvas.style.cursor = 'grab';
    return {
      setData, settle, start, stop, resize, draw, rotate, zoomBy, resetView, pick, project,
      snapshot,
      nodes: () => state.nodes,
      edges: () => state.edges,
      energy: () => state.energy,
      hover: () => state.hover,
    };
  }

  window.PhoenixGraph3D = { mount, withAlpha };
})();
