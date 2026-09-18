/**
 * The graph builder: describe a graph (vertex types, edge types, properties), save it —
 * which validates it, writes `<name>.json` and makes it queryable straight away — and
 * optionally fill it with vertices and edges.
 *
 * The model the page edits is the draft the backend accepts, so the JSON preview is
 * exactly what will be saved, and "load an existing graph" is the backend describing a
 * stored schema as a draft again.
 */
(() => {
  const el = (id) => document.getElementById(id);
  const state = {
    draft: null,
    storageTypes: [],
    graphs: [],
  };

  const PROPERTY_TYPES = ['string', 'int', 'long', 'double', 'float', 'boolean', 'object',
    'list<string>', 'list<long>', 'list<double>', 'map<string>', 'map<long>', 'enum:A|B'];

  // ------------------------------------------------------------------ plumbing

  async function api(path, body, method = 'POST') {
    const res = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
    return { ok: res.ok, status: res.status, body: json };
  }

  function toast(message) {
    const node = el('toast');
    node.textContent = message;
    node.classList.add('show');
    setTimeout(() => node.classList.remove('show'), 2600);
  }

  function blankDraft() {
    return {
      name: '', description: '', idType: 'string', storage: { type: 'Aerospike' },
      vertices: [{ name: 'Entity', description: '', display: 'name', properties: [
        { name: 'name', type: 'string', description: '' } ] }],
      edges: [],
    };
  }

  // ------------------------------------------------------------------ rendering

  function propertyRow(kind, typeIndex, propertyIndex, property) {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td><input data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}" data-field="name"
                 value="${property.name ?? ''}" placeholder="名称"></td>
      <td><select data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}" data-field="type">
        ${PROPERTY_TYPES.map((type) => `<option value="${type}"${type === (property.type ?? 'string') ? ' selected' : ''}>${type}</option>`).join('')}
      </select></td>
      <td class="flag"><input type="checkbox" data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}"
                 data-field="display" ${property.display ? 'checked' : ''} title="作为显示名"></td>
      <td class="flag"><input type="checkbox" data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}"
                 data-field="isVector" ${property.isVector ? 'checked' : ''} title="向量属性（HNSW）"></td>
      <td class="flag"><input type="checkbox" data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}"
                 data-field="isGeo" ${property.isGeo ? 'checked' : ''} title="地理属性（经纬度）"></td>
      <td class="flag"><button class="btn small ghost" data-remove="${kind}" data-type="${typeIndex}"
                 data-prop="${propertyIndex}" type="button">×</button></td>`;
    return row;
  }

  function propertiesTable(kind, typeIndex, properties) {
    const table = document.createElement('table');
    table.className = 'prop-table';
    table.innerHTML = `<thead><tr><th>属性</th><th>类型</th><th class="flag">显示</th>
      <th class="flag">向量</th><th class="flag">地理</th><th></th></tr></thead>`;
    const body = document.createElement('tbody');
    properties.forEach((property, index) => body.appendChild(propertyRow(kind, typeIndex, index, property)));
    table.appendChild(body);
    return table;
  }

  function vertexCard(vertex, index) {
    const card = document.createElement('div');
    card.className = 'type-card';
    card.innerHTML = `
      <div class="type-card-head">
        <strong>点 ${index + 1}</strong>
        <button class="btn small ghost" data-remove-vertex="${index}" type="button">删除</button>
      </div>
      <div class="grid-2">
        <label>名称<input data-vertex="${index}" data-field="name" value="${vertex.name ?? ''}" placeholder="Person"></label>
        <label>显示属性<input data-vertex="${index}" data-field="display" value="${vertex.display ?? ''}" placeholder="name"></label>
      </div>`;
    card.appendChild(propertiesTable('vertex', index, vertex.properties ?? []));
    const add = document.createElement('button');
    add.className = 'btn small ghost';
    add.type = 'button';
    add.textContent = '+ 属性';
    add.addEventListener('click', () => {
      vertex.properties = vertex.properties ?? [];
      vertex.properties.push({ name: '', type: 'string', description: '' });
      render();
    });
    card.appendChild(add);
    return card;
  }

  function edgeCard(edge, index, vertexNames) {
    const card = document.createElement('div');
    card.className = 'type-card';
    const options = (selected) => ['', ...vertexNames]
      .map((name) => `<option value="${name}"${name === (selected ?? '') ? ' selected' : ''}>${name || '（无）'}</option>`)
      .join('');
    card.innerHTML = `
      <div class="type-card-head">
        <strong>边 ${index + 1}</strong>
        <button class="btn small ghost" data-remove-edge="${index}" type="button">删除</button>
      </div>
      <div class="grid-2">
        <label>名称<input data-edge="${index}" data-field="name" value="${edge.name ?? ''}" placeholder="Knows"></label>
        <label>方向<select data-edge="${index}" data-field="direction">
          ${['DIRECTED', 'UNDIRECTED', 'BIDIRECTIONAL'].map((d) => `<option value="${d}"${d === (edge.direction ?? 'DIRECTED') ? ' selected' : ''}>${d}</option>`).join('')}
        </select></label>
      </div>
      <div class="grid-2">
        <label>起点类型<select data-edge="${index}" data-field="source">${options(edge.source)}</select></label>
        <label>终点类型<select data-edge="${index}" data-field="target">${options(edge.target)}</select></label>
      </div>
      <label class="checkbox"><input type="checkbox" data-edge="${index}" data-field="targetIsNull"
        ${edge.targetIsNull ? 'checked' : ''}> 终点可为空（isNull）</label>`;
    card.appendChild(propertiesTable('edge', index, edge.properties ?? []));
    const add = document.createElement('button');
    add.className = 'btn small ghost';
    add.type = 'button';
    add.textContent = '+ 属性';
    add.addEventListener('click', () => {
      edge.properties = edge.properties ?? [];
      edge.properties.push({ name: '', type: 'string', description: '' });
      render();
    });
    card.appendChild(add);
    return card;
  }

  function render() {
    const draft = state.draft;
    el('graphName').value = draft.name ?? '';
    el('graphDescription').value = draft.description ?? '';
    el('idType').value = draft.idType ?? 'string';
    el('storageType').value = (draft.storage && draft.storage.type) || 'Aerospike';
    el('storagePath').value = (draft.storage && draft.storage.path) || '';
    syncStorageFields();

    const vertices = el('vertexList');
    vertices.innerHTML = '';
    draft.vertices.forEach((vertex, index) => vertices.appendChild(vertexCard(vertex, index)));

    const edges = el('edgeList');
    edges.innerHTML = '';
    const vertexNames = draft.vertices.map((vertex) => vertex.name).filter(Boolean);
    draft.edges.forEach((edge, index) => edges.appendChild(edgeCard(edge, index, vertexNames)));

    el('jsonPreview').textContent = JSON.stringify(draft, null, 2);
    renderDataPickers();
  }

  function syncStorageFields() {
    const type = el('storageType').value;
    const rocks = type.startsWith('RocksDB');
    el('storagePathWrap').hidden = !rocks;
    el('storageHint').textContent = rocks
      ? 'RocksDB 图的数据放在这个目录里（相对仓库根目录或绝对路径）'
      : 'Aerospike 图的点写入独立的 vertex set，无需额外配置';
  }

  // ------------------------------------------------------------------ draft edits

  function bindDraftInputs() {
    el('graphName').addEventListener('input', (e) => { state.draft.name = e.target.value; preview(); });
    el('graphDescription').addEventListener('input', (e) => { state.draft.description = e.target.value; preview(); });
    el('idType').addEventListener('change', (e) => { state.draft.idType = e.target.value; preview(); });
    el('storageType').addEventListener('change', (e) => {
      state.draft.storage = { ...(state.draft.storage || {}), type: e.target.value };
      syncStorageFields();
      preview();
    });
    el('storagePath').addEventListener('input', (e) => {
      state.draft.storage = { ...(state.draft.storage || {}), type: el('storageType').value, path: e.target.value };
      preview();
    });

    // one delegated handler keeps the dynamic tables simple
    document.addEventListener('input', onFieldChange);
    document.addEventListener('change', onFieldChange);
    document.addEventListener('click', onClick);
  }

  function onFieldChange(event) {
    const t = event.target;
    const draft = state.draft;
    if (t.dataset.vertex !== undefined) {
      const vertex = draft.vertices[Number(t.dataset.vertex)];
      vertex[t.dataset.field] = t.value;
      preview();
      return;
    }
    if (t.dataset.edge !== undefined) {
      const edge = draft.edges[Number(t.dataset.edge)];
      const field = t.dataset.field;
      edge[field] = field === 'targetIsNull' ? t.checked : t.value;
      preview();
      return;
    }
    if (t.dataset.kind !== undefined && t.dataset.prop !== undefined) {
      const list = t.dataset.kind === 'vertex'
        ? draft.vertices[Number(t.dataset.type)].properties
        : draft.edges[Number(t.dataset.type)].properties;
      const property = list[Number(t.dataset.prop)];
      const field = t.dataset.field;
      property[field] = t.type === 'checkbox' ? t.checked : t.value;
      preview();
    }
  }

  function onClick(event) {
    const t = event.target;
    const draft = state.draft;
    if (t.dataset.remove) {
      const list = t.dataset.remove === 'vertex'
        ? draft.vertices[Number(t.dataset.type)].properties
        : draft.edges[Number(t.dataset.type)].properties;
      list.splice(Number(t.dataset.prop), 1);
      render();
    } else if (t.dataset.removeVertex !== undefined) {
      draft.vertices.splice(Number(t.dataset.removeVertex), 1);
      render();
    } else if (t.dataset.removeEdge !== undefined) {
      draft.edges.splice(Number(t.dataset.removeEdge), 1);
      render();
    }
  }

  function preview() {
    el('jsonPreview').textContent = JSON.stringify(state.draft, null, 2);
  }

  // ------------------------------------------------------------------ saving

  function showResult(node, ok, title, lines) {
    node.hidden = false;
    node.className = `result ${ok ? 'ok' : 'bad'}`;
    node.innerHTML = `${title}${lines && lines.length
      ? `<ul>${lines.map((line) => `<li>${String(line).replace(/[<>&]/g, '')}</li>`).join('')}</ul>` : ''}`;
  }

  async function validateDraft() {
    const res = await api('/api/schema/validate', state.draft);
    const body = res.body || {};
    showResult(el('saveResult'), !!body.valid,
      body.valid ? '校验通过' : '校验未通过',
      [...(body.errors || []), ...(body.warnings || []).map((w) => '提醒：' + w)]);
    return !!body.valid;
  }

  async function saveDraft() {
    const replace = el('replaceGraph').checked;
    const res = await api(`/api/schema?replace=${replace}`, state.draft);
    const body = res.body || {};
    if (res.ok && body.saved) {
      showResult(el('saveResult'), true,
        `已保存并加载：${body.graph}`,
        [`文件：${body.file}`, '现在可以直接查询这张图（下拉里已经出现）']);
      toast(`已保存并加载 ${body.graph}`);
      await loadGraphs(body.graph);
    } else {
      showResult(el('saveResult'), false, '保存失败', [...(body.errors || [body.error || res.status])]);
      toast('保存失败');
    }
    return res.ok;
  }

  function exportDraft() {
    const name = (state.draft.name || 'graph') + '.json';
    const blob = new Blob([JSON.stringify(state.draft, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
    toast(`已导出 ${name}`);
  }

  // ------------------------------------------------------------------ data building

  function renderDataPickers() {
    const vertexNames = state.draft.vertices.map((vertex) => vertex.name).filter(Boolean);
    el('dataVertexType').innerHTML = vertexNames.map((n) => `<option value="${n}">${n}</option>`).join('');
    el('dataEdgeType').innerHTML = state.draft.edges.map((edge) => edge.name).filter(Boolean)
      .map((n) => `<option value="${n}">${n}</option>`).join('');
    renderPropertyInputs();
  }

  /** The value inputs for the selected vertex/edge type (property indexes come from the order). */
  function renderPropertyInputs() {
    const vertex = state.draft.vertices.find((v) => v.name === el('dataVertexType').value);
    el('dataVertexProps').innerHTML = (vertex?.properties ?? []).map((property, index) =>
      `<label>${property.name}<input data-value-vertex="${index + 1}" placeholder="${property.type ?? 'string'}"></label>`).join('');
    const edge = state.draft.edges.find((e) => e.name === el('dataEdgeType').value);
    el('dataEdgeProps').innerHTML = (edge?.properties ?? []).map((property, index) =>
      `<label>${property.name}<input data-value-edge="${index + 1}" placeholder="${property.type ?? 'string'}"></label>`).join('');
  }

  function valueOf(text, type) {
    const value = (text ?? '').trim();
    if (value === '') return null;
    if (/^(int|long|double|float)/.test(type ?? '')) {
      const number = Number(value);
      return Number.isNaN(number) ? value : number;
    }
    if ((type ?? '') === 'boolean') return value === 'true';
    return value;
  }

  async function writeVertex() {
    const graph = state.draft.name;
    const typeName = el('dataVertexType').value;
    const vertex = state.draft.vertices.find((v) => v.name === typeName);
    const props = [...document.querySelectorAll('[data-value-vertex]')].map((input) => ({
      index: Number(input.dataset.valueVertex),
      value: valueOf(input.value, (vertex?.properties ?? [])[Number(input.dataset.valueVertex) - 1]?.type),
      lastModifiedTime: Date.now(),
    })).filter((entry) => entry.value !== null);
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/vertices`, {
      id: el('dataVertexId').value, vertexSchemaName: typeName, properties: props });
    showResult(el('dataResult'), res.ok, `写入点：${res.body?.status ?? res.status}`, [JSON.stringify(res.body).slice(0, 200)]);
  }

  async function writeEdge() {
    const graph = state.draft.name;
    const typeName = el('dataEdgeType').value;
    const edge = state.draft.edges.find((e) => e.name === typeName);
    const props = [...document.querySelectorAll('[data-value-edge]')].map((input) => ({
      index: Number(input.dataset.valueEdge),
      value: valueOf(input.value, (edge?.properties ?? [])[Number(input.dataset.valueEdge) - 1]?.type),
      lastModifiedTime: Date.now(),
    })).filter((entry) => entry.value !== null);
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/edges`, {
      edgeId: el('dataEdgeId').value, edgeSchemaName: typeName,
      source: el('dataEdgeSource').value, destination: el('dataEdgeTarget').value, properties: props });
    showResult(el('dataResult'), res.ok, `写入边：${res.body?.status ?? res.status}`, [JSON.stringify(res.body).slice(0, 200)]);
  }

  // ------------------------------------------------------------------ startup

  async function loadStorageTypes() {
    const res = await api('/api/schema-storage-types', undefined, 'GET');
    state.storageTypes = Array.isArray(res.body) ? res.body : [];
    el('storageType').innerHTML = state.storageTypes
      .map((type) => `<option value="${type.name}">${type.name}</option>`).join('');
  }

  async function loadGraphs(select) {
    const res = await api('/api/graphs', undefined, 'GET');
    state.graphs = Array.isArray(res.body) ? res.body : [];
    el('graphPicker').innerHTML = ['', ...state.graphs.map((g) => g.name)]
      .map((name) => `<option value="${name}">${name || '打开已有的图…'}</option>`).join('');
    if (select) el('graphPicker').value = select;
  }

  async function openGraph(name) {
    if (!name) {
      state.draft = blankDraft();
      render();
      return;
    }
    const res = await api(`/api/schema/${encodeURIComponent(name)}/draft`, undefined, 'GET');
    if (!res.ok) {
      toast(`读不到 ${name} 的 schema`);
      return;
    }
    state.draft = res.body;
    render();
    el('fuzzyGraph').value = name;
    loadFuzzyPatterns().catch((err) => toast('读不到模糊模式: ' + err.message));
    toast(`已打开 ${name}`);
  }

  // ------------------------------------------------------------------ fuzzy linking

  /**
   * The fuzzy patterns of a graph, and what they produce for a context.
   *
   * A pattern is data an analyst edits while the graph runs, so the page reads the graph's patterns
   * from the service, validates an expression against a sample context before saving it, and shows
   * what each part of the pattern contributed - the key is only as good as the fragments it is made of.
   */
  async function loadFuzzyPatterns() {
    const graph = fuzzyGraph();
    if (!graph) return;
    const res = await api('/api/fuzzy/patterns?graph=' + encodeURIComponent(graph), undefined, 'GET');
    const patterns = res.body?.patterns ?? [];
    el('fuzzyList').innerHTML = patterns.length === 0
      ? '<p class="hint">这张图还没有模式。</p>'
      : patterns.map((pattern) => `<div class="type-card">
          <div class="type-card-head"><strong>#${pattern.id}</strong><span>${escapeHtml(pattern.name)}</span>
            <span class="hint">${pattern.enabled ? '启用' : '停用'}</span>
            <button class="btn ghost small" type="button" data-fuzzy-edit="${pattern.id}">编辑</button></div>
          <div class="json-preview">${escapeHtml(pattern.expression)}</div>
        </div>`).join('');
    el('fuzzyList').querySelectorAll('[data-fuzzy-edit]').forEach((button) => {
      button.addEventListener('click', () => {
        const pattern = patterns.find((p) => String(p.id) === button.dataset.fuzzyEdit);
        el('fuzzyId').value = pattern.id;
        el('fuzzyName').value = pattern.name ?? '';
        el('fuzzyExpression').value = pattern.expression ?? '';
        el('fuzzyDescription').value = pattern.description ?? '';
      });
    });
    showResult(el('fuzzyResult'), true, `${graph}：${patterns.length} 个模式`,
      [res.body?.file ? '文件：' + res.body.file : '']);
  }

  function fuzzyGraph() {
    return (el('fuzzyGraph').value || state.draft?.name || '').trim();
  }

  function escapeHtml(text) {
    return String(text ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  async function previewFuzzy() {
    const expression = el('fuzzyExpression').value.trim();
    const context = el('fuzzyContext').value.trim();
    const res = await api('/api/fuzzy/validate', {
      patternId: el('fuzzyId').value === '' ? null : Number(el('fuzzyId').value),
      expression, context: context || '{}', name: el('fuzzyName').value,
      description: el('fuzzyDescription').value,
    });
    const body = res.body ?? {};
    if (body.valid === false) {
      showResult(el('fuzzyResult'), false, '表达式有问题', body.errors ?? []);
      return;
    }
    const steps = (body.steps ?? []).map((step) => `${step.index}. ${step.part} → ${step.value ?? '（缺失）'}`);
    showResult(el('fuzzyResult'), true, body.key ? 'key：' + body.key : '这个 context 下模式不生效',
      [`链接节点：${body.linkVertexId ?? '（不生成）'}`, ...steps]);
  }

  async function saveFuzzy() {
    const res = await api('/api/fuzzy/patterns', {
      graph: fuzzyGraph(), id: Number(el('fuzzyId').value), name: el('fuzzyName').value,
      expression: el('fuzzyExpression').value.trim(), description: el('fuzzyDescription').value,
      enabled: true,
    });
    showResult(el('fuzzyResult'), res.ok, res.ok ? '已保存模式' : '保存失败',
      [res.body?.error ?? JSON.stringify(res.body).slice(0, 200)]);
    if (res.ok) await loadFuzzyPatterns();
  }

  async function removeFuzzy() {
    const res = await api(`/api/fuzzy/patterns/${encodeURIComponent(fuzzyGraph())}/${el('fuzzyId').value}`,
      undefined, 'DELETE');
    showResult(el('fuzzyResult'), res.ok, res.ok ? '已删除' : '删除失败',
      [res.body?.error ?? JSON.stringify(res.body).slice(0, 200)]);
    if (res.ok) await loadFuzzyPatterns();
  }

  async function showFuzzyFunctions() {
    const res = await api('/api/fuzzy/functions', undefined, 'GET');
    const lines = (res.body ?? []).map((f) => `${f.usage} — ${f.description}`);
    showResult(el('fuzzyResult'), true, `可用函数（${lines.length}）`, lines);
  }

  async function linkFuzzyVertex() {
    const res = await api(`/api/fuzzy/graphs/${encodeURIComponent(fuzzyGraph())}/links`, {
      vertexName: el('fuzzyVertexType').value.trim(),
      vertexId: el('fuzzyVertexId').value.trim(),
    });
    const links = res.body?.links ?? [];
    showResult(el('fuzzyResult'), res.ok, res.ok ? `已关联 ${links.length} 个节点` : '关联失败',
      res.ok ? links.map((link) => `#${link.patternId} ${link.key} → ${link.linkVertexId}`)
        : [res.body?.error ?? JSON.stringify(res.body).slice(0, 200)]);
  }

  async function init() {
    await loadStorageTypes();
    await loadGraphs();
    state.draft = blankDraft();
    bindDraftInputs();
    render();

    el('graphPicker').addEventListener('change', (e) => openGraph(e.target.value));
    el('fuzzyGraph').value = state.draft?.name ?? '';
    el('newGraph').addEventListener('click', () => { state.draft = blankDraft(); el('graphPicker').value = ''; render(); });
    el('backToGraph').addEventListener('click', () => { window.location.href = '/'; });
    el('addVertex').addEventListener('click', () => {
      state.draft.vertices.push({ name: '', description: '', properties: [] });
      render();
    });
    el('addEdge').addEventListener('click', () => {
      state.draft.edges.push({ name: '', description: '', direction: 'DIRECTED',
        source: state.draft.vertices[0]?.name ?? null, target: state.draft.vertices[0]?.name ?? null,
        targetIsNull: false, properties: [] });
      render();
    });
    el('validateBtn').addEventListener('click', validateDraft);
    el('saveBtn').addEventListener('click', saveDraft);
    el('exportBtn').addEventListener('click', exportDraft);
    el('dataVertexType').addEventListener('change', renderPropertyInputs);
    el('dataEdgeType').addEventListener('change', renderPropertyInputs);
    el('addVertexData').addEventListener('click', writeVertex);
    el('addEdgeData').addEventListener('click', writeEdge);
    el('fuzzyValidate').addEventListener('click', previewFuzzy);
    el('fuzzySave').addEventListener('click', saveFuzzy);
    el('fuzzyRemove').addEventListener('click', removeFuzzy);
    el('fuzzyFunctions').addEventListener('click', showFuzzyFunctions);
    el('fuzzyLink').addEventListener('click', linkFuzzyVertex);
    el('fuzzyGraph').addEventListener('change', () => { loadFuzzyPatterns().catch(() => {}); });
  }

  init().catch((err) => toast('初始化失败: ' + err.message));
})();
