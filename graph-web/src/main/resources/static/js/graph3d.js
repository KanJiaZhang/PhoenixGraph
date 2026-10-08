/*
 * 3D 图视图 —— 最小代价版本：**不引 WebGL、不引 three.js**，只用 canvas 2D 做透视投影。
 *
 * 为什么这样最省：三维视角对「结构问题」（聚簇、桥、壳层）确实多一层信息，值得让人看一眼自己的数据；
 * 但真正贵的是三件事 —— 依赖（three.js/3d-force-graph ~14MB 解包）、标签与拾取要重写、以及可读性代价
 * （见 docs/GRAPH-VISUALIZATION.md：3D 对路径追踪类问题的优势在 2D 补上边路由与交互后并不普遍）。
 * 所以这一页的定位是 **demo / 洞察模式**：同一份查询结果 `{vertices, edges}`，原地跑一个 3D 力导向，
 * 透视投影后按深度排序画点线 —— 一页、零依赖，够让人判断「我的数据适不适合 3D」。
 *
 * 与主界面的关系：主界面（index.html）是分析工作台（算法着色、树/DAG 布局、结果面板、地图）；
 * 这一页只做一件事，就是从任意角度把答案转起来看。真要在产品里上 3D，再换 three.js / 3d-force-graph
 * （同一份数据形状，接入点就是下面的 `state`）。
 */
(function () {
  'use strict';

  var API = '/api/query';
  var canvas = document.getElementById('view');
  var ctx = canvas.getContext('2d');
  var statusEl = document.getElementById('status');

  var state = {
    nodes: [],           // {id, label, type, x, y, z, vx, vy, vz, r, hue}
    edges: [],           // {source: node, target: node, label}
    yaw: -0.6, pitch: 0.35, zoom: 1, focal: 900, running: false,
  };

  function status(text) {
    statusEl.textContent = text;
  }

  // ---------------------------------------------------------------- data

  function ask(graph, query) {
    return fetch(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ graphName: graph, query: query, includeResult: true, returnSubgraph: true, source: 'ui' }),
    }).then(function (response) { return response.json().then(function (body) { return { status: response.status, body: body }; }); })
      .then(function (answer) {
        if (answer.body && answer.body.error) { throw new Error(answer.body.error); }
        if (answer.status >= 400) { throw new Error('HTTP ' + answer.status); }
        return answer.body.subgraph || answer.body;
      });
  }

  function hueOf(text) {
    var hash = 0;
    for (var i = 0; i < text.length; i += 1) { hash = (hash * 31 + text.charCodeAt(i)) % 360; }
    return hash;
  }

  function build(subgraph) {
    var vertices = (subgraph && subgraph.vertices) || [];
    var edges = (subgraph && subgraph.edges) || [];
    var byId = new Map();
    state.nodes = vertices.map(function (vertex) {
      var id = String(vertex.id);
      var node = {
        id: id,
        label: displayName(vertex) || id,
        type: vertex.label || '?',
        // a deterministic start on a sphere: the same query twice gives the same first picture
        x: (Math.sin(hueOf(id) * 2.399) * 220) + (hueOf(id + 'x') % 40),
        y: (Math.cos(hueOf(id) * 1.618) * 220) + (hueOf(id + 'y') % 40),
        z: (Math.sin(hueOf(id) * 0.707) * 220) + (hueOf(id + 'z') % 40),
        vx: 0, vy: 0, vz: 0,
        hue: hueOf(vertex.label || '?'),
      };
      byId.set(id, node);
      return node;
    });
    state.edges = [];
    for (var i = 0; i < edges.length; i += 1) {
      var source = byId.get(String(edges[i].source));
      var target = byId.get(String(edges[i].target));
      if (source && target) { state.edges.push({ source: source, target: target, label: edges[i].label || '' }); }
    }
  }

  function displayName(vertex) {
    var properties = vertex.properties || {};
    var fields = ['name', 'title', 'username', 'id'];
    for (var i = 0; i < fields.length; i += 1) {
      if (properties[fields[i]] !== undefined && properties[fields[i]] !== null) { return String(properties[fields[i]]); }
    }
    return null;
  }

  // ---------------------------------------------------------------- 3D force layout (spring + charge)
  //
  // Barnes-Hut would be the next step (O(n log n)); for the sizes a demo page draws (a few hundred to a
  // couple of thousand) the plain O(n²) pass with a distance cutoff is simpler and fast enough.

  function simulate(iterations) {
    var nodes = state.nodes;
    var n = nodes.length;
    if (!n) { return; }
    var cutoff = 520;
    var cutoff2 = cutoff * cutoff;
    var linkDistance = n > 400 ? 70 : 110;
    for (var step = 0; step < iterations; step += 1) {
      for (var i = 0; i < n; i += 1) {
        var a = nodes[i];
        for (var j = i + 1; j < n; j += 1) {
          var b = nodes[j];
          var dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
          var d2 = dx * dx + dy * dy + dz * dz;
          if (d2 > cutoff2 || d2 === 0) { continue; }
          var d = Math.sqrt(d2);
          var force = 2600 / d2;             // charge
          var ux = dx / d, uy = dy / d, uz = dz / d;
          a.vx += ux * force; a.vy += uy * force; a.vz += uz * force;
          b.vx -= ux * force; b.vy -= uy * force; b.vz -= uz * force;
        }
      }
      for (var e = 0; e < state.edges.length; e += 1) {
        var edge = state.edges[e];
        var sx = edge.source, tx = edge.target;
        var ddx = tx.x - sx.x, ddy = tx.y - sx.y, ddz = tx.z - sx.z;
        var dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) || 1;
        var pull = (dist - linkDistance) * 0.06;
        var px = ddx / dist * pull, py = ddy / dist * pull, pz = ddz / dist * pull;
        sx.vx += px; sx.vy += py; sx.vz += pz;
        tx.vx -= px; tx.vy -= py; tx.vz -= pz;
      }
      for (var k = 0; k < n; k += 1) {
        var node = nodes[k];
        node.vx -= node.x * 0.0016; node.vy -= node.y * 0.0016; node.vz -= node.z * 0.0016; // gravity
        node.vx *= 0.82; node.vy *= 0.82; node.vz *= 0.82;
        node.x += Math.max(-30, Math.min(30, node.vx));
        node.y += Math.max(-30, Math.min(30, node.vy));
        node.z += Math.max(-30, Math.min(30, node.vz));
      }
    }
  }

  // ---------------------------------------------------------------- projection and painting

  function project() {
    var cosYaw = Math.cos(state.yaw), sinYaw = Math.sin(state.yaw);
    var cosPitch = Math.cos(state.pitch), sinPitch = Math.sin(state.pitch);
    var cx = canvas.width / 2, cy = canvas.height / 2;
    var zoom = state.zoom * Math.min(canvas.width, canvas.height) / 900;
    var out = [];
    for (var i = 0; i < state.nodes.length; i += 1) {
      var node = state.nodes[i];
      var x = node.x * cosYaw - node.z * sinYaw;
      var z = node.x * sinYaw + node.z * cosYaw;
      var y = node.y * cosPitch - z * sinPitch;
      z = node.y * sinPitch + z * cosPitch;
      var scale = state.focal / Math.max(1, state.focal + z);
      out.push({ node: node, x: cx + x * scale * zoom, y: cy + y * scale * zoom, z: z, scale: scale * zoom });
    }
    return out;
  }

  function draw() {
    var projected = project();
    var byNode = new Map();
    projected.forEach(function (point) { byNode.set(point.node, point); });

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    // painter's algorithm: far first, so the near nodes overlap the far ones
    projected.sort(function (a, b) { return b.z - a.z; });
    var order = new Map(projected.map(function (point, at) { return [point.node, at]; }));

    ctx.lineWidth = 1;
    for (var e = 0; e < state.edges.length; e += 1) {
      var edge = state.edges[e];
      var from = byNode.get(edge.source), to = byNode.get(edge.target);
      if (!from || !to) { continue; }
      // the edge belongs with the nearer of its two ends, so it is drawn just before it
      var depth = Math.min(order.get(edge.source), order.get(edge.target));
      var scale = (from.scale + to.scale) / 2;
      ctx.strokeStyle = 'rgba(148, 178, 255, ' + Math.max(0.04, Math.min(0.5, scale * 0.5 - 0.1)) + ')';
      ctx.beginPath();
      ctx.moveTo(from.x, from.y);
      ctx.lineTo(to.x, to.y);
      ctx.stroke();
      void depth;
    }

    for (var i = projected.length - 1; i >= 0; i -= 1) {
      var point = projected[i];
      var radius = Math.max(1.4, 7 * point.scale);
      var light = Math.max(24, Math.min(62, 30 + point.scale * 26));
      ctx.beginPath();
      ctx.arc(point.x, point.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = 'hsl(' + point.node.hue + ', 68%, ' + light + '%)';
      ctx.fill();
      if (point.scale > 0.95) { // only the near vertices carry a label, or nothing is readable
        ctx.fillStyle = 'rgba(226, 232, 240, ' + Math.min(0.95, point.scale * 0.6) + ')';
        ctx.font = '11px system-ui, sans-serif';
        ctx.fillText(point.node.label, point.x + radius + 3, point.y + 4);
      }
    }
  }

  // ---------------------------------------------------------------- interaction

  function resize() {
    var stage = document.getElementById('stage');
    var ratio = window.devicePixelRatio || 1;
    canvas.width = Math.max(320, Math.round(stage.clientWidth * ratio));
    canvas.height = Math.max(240, Math.round(stage.clientHeight * ratio));
    canvas.style.width = stage.clientWidth + 'px';
    canvas.style.height = stage.clientHeight + 'px';
    draw();
  }

  var dragging = null;
  canvas.addEventListener('mousedown', function (event) {
    dragging = { x: event.clientX, y: event.clientY, yaw: state.yaw, pitch: state.pitch };
    canvas.style.cursor = 'grabbing';
  });
  window.addEventListener('mousemove', function (event) {
    if (!dragging) { return; }
    state.yaw = dragging.yaw + (event.clientX - dragging.x) * 0.008;
    state.pitch = Math.max(-1.45, Math.min(1.45, dragging.pitch - (event.clientY - dragging.y) * 0.008));
    draw();
  });
  window.addEventListener('mouseup', function () {
    dragging = null;
    canvas.style.cursor = 'grab';
  });
  canvas.addEventListener('wheel', function (event) {
    event.preventDefault();
    state.zoom = Math.max(0.2, Math.min(6, state.zoom * (event.deltaY > 0 ? 0.92 : 1.08)));
    draw();
  }, { passive: false });
  window.addEventListener('resize', resize);

  // ---------------------------------------------------------------- wiring

  function drawQuery() {
    var graph = document.getElementById('graphSelect').value;
    var query = document.getElementById('query').value.trim();
    if (!graph) { status('请先选择图实例'); return; }
    if (!query) { status('请填写查询'); return; }
    status('正在查询 ' + graph + ' …');
    ask(graph, query).then(function (subgraph) {
      build(subgraph);
      if (!state.nodes.length) { status('该查询没有画出点（换个查询或图实例）'); draw(); return; }
      var started = Date.now();
      var iterations = state.nodes.length > 1200 ? 90 : 260;
      simulate(iterations);
      draw();
      status(state.nodes.length + ' 个点 / ' + state.edges.length + ' 条边（' + iterations + ' 轮 3D 力导向，'
        + (Date.now() - started) + ' ms）\n拖动旋转 · 滚轮缩放 · 深度越近越亮越大');
      // what a test (or a probe) reads back
      window.graph3d.nodes = state.nodes;
      window.graph3d.edges = state.edges;
      window.graph3d.projected = project;
    }).catch(function (error) {
      status('查询失败：' + (error && error.message ? error.message : error));
    });
  }

  fetch('/api/graphs').then(function (response) { return response.json(); }).then(function (graphs) {
    var select = document.getElementById('graphSelect');
    (graphs || []).forEach(function (graph) {
      var option = document.createElement('option');
      option.value = graph.name;
      option.textContent = graph.storage && graph.storage !== 'Aerospike' ? graph.name + ' · ' + graph.storage : graph.name;
      select.appendChild(option);
    });
    var withAccounts = (graphs || []).find(function (graph) { return graph.name === 'payment-graph'; });
    if (withAccounts) { select.value = 'payment-graph'; }
    // the first draw waits for the picker: a query is sent with a graph, and an empty one is not a graph
    drawQuery();
  }).catch(function () {
    status('无法读取图列表（/api/graphs）：请确认 graph-web 与 graph-http 都在运行');
  });

  document.getElementById('draw').addEventListener('click', drawQuery);
  window.graph3d = { nodes: state.nodes, edges: state.edges, projected: project, rotate: function (yaw, pitch) {
    state.yaw = yaw; state.pitch = pitch; draw();
  } };
  resize();
})();
