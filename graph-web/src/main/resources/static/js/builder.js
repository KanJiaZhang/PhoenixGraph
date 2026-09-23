/**
 * The graph builder: describe a graph (vertex types, edge types, properties), save it —
 * which validates it, writes `<name>.json` and makes it queryable straight away — and
 * optionally fill it with vertices and edges (by hand or from a CSV file).
 *
 * The model the page edits is the draft the backend accepts, so the JSON preview is
 * exactly what will be saved, and "load an existing graph" is the backend describing a
 * stored schema as a draft again.
 *
 * Three rules the engine has are surfaced here rather than left to a failed save:
 * `id`, `createdTime` and `lastUpdatedTime` belong to every element (fixed indexes, no
 * property may take their name), a degree limit only means something for the schemes
 * that spread a hub over several rows, and deleting anything is confirmed with what it
 * costs (a stored record is not removed by deleting its type from the schema).
 */
(() => {
  const el = (id) => document.getElementById(id);
  const state = {
    draft: null,
    storageTypes: [],
    graphs: [],
    csv: { vertex: null, edge: null },
  };

  const PROPERTY_TYPES = ['string', 'int', 'long', 'double', 'float', 'boolean', 'object',
    'list<string>', 'list<long>', 'list<double>', 'map<string>', 'map<long>', 'enum:A|B'];

  /** Names the engine keeps for itself: their index is fixed, so no property may take them. */
  const RESERVED_VERTEX = ['id', 'createdTime', 'lastUpdatedTime'];
  const RESERVED_EDGE = ['id', 'source', 'target', 'createdTime', 'lastUpdatedTime'];

  const ID_DESCRIPTION = '引擎内置：每个点都有 id，索引固定为 0（类型跟图实例的 id 类型一致）';

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
    setTimeout(() => node.classList.remove('show'), 3200);
  }

  function attr(text) {
    return String(text ?? '').replace(/[&<>"]/g,
      (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  function escapeHtml(text) {
    return attr(text);
  }

  /**
   * Whether the storage spreads a hub over several rows — the only case where a degree limit means
   * anything. An inline scheme keeps every edge of a vertex in one record, where a limit could only
   * refuse writes, so the builder disables the fields there and says why instead of hiding them.
   */
  function degreeLimitsApply() {
    const type = el('storageType').value || (state.draft && state.draft.storage && state.draft.storage.type)
      || 'Aerospike';
    return type.endsWith('-Edge');
  }

  function blankDraft() {
    return {
      name: '', description: '', idType: 'string', storage: { type: 'Aerospike' },
      vertices: [{ name: 'Entity', description: '', display: 'name', properties: [
        { name: 'name', type: 'string', description: '' } ] }],
      edges: [],
    };
  }

  /** Confirms an action by naming what it costs; returns whether to go on. */
  function confirmCost(title, lines) {
    return window.confirm([title, '', ...lines].join('\n'));
  }

  // ------------------------------------------------------------------ rendering

  /** The fixed `id` row: its name and index belong to the engine, its type is the graph's id type. */
  function idRow(typeIndex) {
    const row = document.createElement('tr');
    row.className = 'id-row';
    row.innerHTML = `
      <td><input value="id" disabled title="${attr(ID_DESCRIPTION)}"></td>
      <td><select data-id-type="${typeIndex}" title="所有点共用一个 id 类型（改这里等于改整个图）">
        ${['string', 'long'].map((type) =>
          `<option value="${type}"${type === (state.draft.idType ?? 'string') ? ' selected' : ''}>${type}</option>`).join('')}
      </select></td>
      <td><input value="${attr(ID_DESCRIPTION)}" disabled></td>
      <td class="flag"><input type="checkbox" disabled title="id 始终可以作为显示名"></td>
      <td class="flag"><input type="checkbox" disabled title="id 不能是向量属性"></td>
      <td class="flag"><input type="checkbox" disabled title="id 不能是地理属性"></td>
      <td class="flag">🔒</td>`;
    return row;
  }

  function propertyRow(kind, typeIndex, propertyIndex, property) {
    const row = document.createElement('tr');
    const reserved = (kind === 'vertex' ? RESERVED_VERTEX : RESERVED_EDGE).includes(property.name);
    if (reserved) row.classList.add('reserved-row');
    row.innerHTML = `
      <td><input data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}" data-field="name"
                 value="${attr(property.name)}" placeholder="名称"
                 title="${reserved ? '这个名字属于引擎，保存会被拒绝' : ''}"></td>
      <td><select data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}" data-field="type">
        ${PROPERTY_TYPES.map((type) => `<option value="${type}"${type === (property.type ?? 'string') ? ' selected' : ''}>${type}</option>`).join('')}
      </select></td>
      <td><input data-kind="${kind}" data-type="${typeIndex}" data-prop="${propertyIndex}" data-field="description"
                 value="${attr(property.description)}" placeholder="说明"></td>
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

  function propertiesTable(kind, typeIndex, properties, withIdRow) {
    const table = document.createElement('table');
    table.className = 'prop-table';
    table.innerHTML = `<thead><tr><th>属性</th><th>类型</th><th>说明</th><th class="flag">显示</th>
      <th class="flag">向量</th><th class="flag">地理</th><th></th></tr></thead>`;
    const body = document.createElement('tbody');
    if (withIdRow) body.appendChild(idRow(typeIndex));
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
        <label>名称<input data-vertex="${index}" data-field="name" value="${attr(vertex.name)}" placeholder="Person"></label>
        <label>显示属性<input data-vertex="${index}" data-field="display" value="${attr(vertex.display)}" placeholder="name"></label>
      </div>
      <label>描述<input data-vertex="${index}" data-field="description" value="${attr(vertex.description)}"
                      placeholder="这个点类型表示什么"></label>`;
    card.appendChild(propertiesTable('vertex', index, vertex.properties ?? [], true));
    const add = document.createElement('button');
    add.className = 'btn small ghost';
    add.type = 'button';
    add.textContent = '+ 属性';
    add.addEventListener('click', () => {
      vertex.properties = vertex.properties ?? [];
      vertex.properties.push({ name: '', type: 'string', description: '' });
      render();
      refreshDataForm();
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
    const caps = degreeLimitsApply();
    const capHint = caps
      ? '超出上限的写入会被拒绝（-Edge 方案把大点拆成多行，所以上限有意义）'
      : '当前存储把每个点/边放在一条记录里，上限没有意义：保存时会被忽略（换成 -Edge 方案才生效）';
    card.innerHTML = `
      <div class="type-card-head">
        <strong>边 ${index + 1}</strong>
        <button class="btn small ghost" data-remove-edge="${index}" type="button">删除</button>
      </div>
      <div class="grid-2">
        <label>名称<input data-edge="${index}" data-field="name" value="${attr(edge.name)}" placeholder="Knows"></label>
        <label>方向<select data-edge="${index}" data-field="direction">
          ${['DIRECTED', 'UNDIRECTED', 'BIDIRECTIONAL'].map((d) => `<option value="${d}"${d === (edge.direction ?? 'DIRECTED') ? ' selected' : ''}>${d}</option>`).join('')}
        </select></label>
      </div>
      <label>描述<input data-edge="${index}" data-field="description" value="${attr(edge.description)}"
                      placeholder="这条边表示什么"></label>
      <div class="grid-2">
        <label>起点类型<select data-edge="${index}" data-field="source">${options(edge.source)}</select></label>
        <label>终点类型<select data-edge="${index}" data-field="target">${options(edge.target)}</select></label>
      </div>
      <div class="grid-2">
        <label>最大出度<input data-edge="${index}" data-field="maxOutDegree" type="number" min="0"
                 value="${attr(edge.maxOutDegree ?? '')}" placeholder="不限" ${caps ? '' : 'disabled'}></label>
        <label>最大入度<input data-edge="${index}" data-field="maxInDegree" type="number" min="0"
                 value="${attr(edge.maxInDegree ?? '')}" placeholder="不限" ${caps ? '' : 'disabled'}></label>
      </div>
      <p class="hint">${capHint}</p>
      <label class="checkbox"><input type="checkbox" data-edge="${index}" data-field="targetIsNull"
        ${edge.targetIsNull ? 'checked' : ''}> 终点可为空（isNull）</label>`;
    card.appendChild(propertiesTable('edge', index, edge.properties ?? [], false));
    const add = document.createElement('button');
    add.className = 'btn small ghost';
    add.type = 'button';
    add.textContent = '+ 属性';
    add.addEventListener('click', () => {
      edge.properties = edge.properties ?? [];
      edge.properties.push({ name: '', type: 'string', description: '' });
      render();
      refreshDataForm();
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

  /** Redraws the write form: its columns are the schema's properties, so a change lands here too. */
  function refreshDataForm() {
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
    el('idType').addEventListener('change', (e) => { state.draft.idType = e.target.value; render(); });
    el('storageType').addEventListener('change', (e) => {
      state.draft.storage = { ...(state.draft.storage || {}), type: e.target.value };
      syncStorageFields();
      render(); // the degree fields can become (un)available with the storage
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
    if (t.dataset.idType !== undefined) {
      // the id type is one value for the whole graph, so every row follows
      draft.idType = t.value;
      render();
      return;
    }
    if (t.dataset.vertex !== undefined) {
      const vertex = draft.vertices[Number(t.dataset.vertex)];
      vertex[t.dataset.field] = t.value;
      preview();
      if (t.dataset.field === 'name') renderDataPickers();
      return;
    }
    if (t.dataset.edge !== undefined) {
      const edge = draft.edges[Number(t.dataset.edge)];
      const field = t.dataset.field;
      if (field === 'maxOutDegree' || field === 'maxInDegree') {
        const number = t.value === '' ? null : Number(t.value);
        edge[field] = number === null || Number.isNaN(number) || number <= 0 ? null : number;
      } else {
        edge[field] = field === 'targetIsNull' ? t.checked : t.value;
      }
      preview();
      if (field === 'name') renderDataPickers();
      return;
    }
    if (t.dataset.kind !== undefined && t.dataset.prop !== undefined) {
      const list = t.dataset.kind === 'vertex'
        ? draft.vertices[Number(t.dataset.type)].properties
        : draft.edges[Number(t.dataset.type)].properties;
      const property = list[Number(t.dataset.prop)];
      const field = t.dataset.field;
      property[field] = t.type === 'checkbox' ? t.checked : t.value;
      if (field === 'name') {
        const reserved = (t.dataset.kind === 'vertex' ? RESERVED_VERTEX : RESERVED_EDGE)
          .includes(property.name);
        t.closest('tr').classList.toggle('reserved-row', reserved);
        if (reserved) toast(`“${property.name}” 属于引擎（索引固定），保存会被拒绝`);
        // the write form's columns are the property names
        refreshDataForm();
      }
      preview();
    }
  }

  /** What deleting a piece of the schema costs, spelled out before it happens. */
  function confirmRemoveProperty(kind, typeIndex, propertyIndex) {
    const owner = kind === 'vertex'
      ? state.draft.vertices[Number(typeIndex)] : state.draft.edges[Number(typeIndex)];
    const property = (owner.properties ?? [])[Number(propertyIndex)];
    return confirmCost(`从「${owner.name || '（未命名）'}」删除属性「${property.name || '（未命名）'}」？`, [
      '· 存量的值不会被删除：记录里那个 bin 还在，只是这张 schema 不再描述它（查询里看不见）。',
      '· 属性索引按声明顺序分配，删除后面的属性会让它的索引前移（存量数据仍按写入时的索引存放）。',
      '· 如果这个属性是显示名/向量/地理属性，相关的画布标注与索引也随之取消。',
    ]);
  }

  function onClick(event) {
    const t = event.target;
    const draft = state.draft;
    if (t.dataset.remove) {
      if (!confirmRemoveProperty(t.dataset.remove, t.dataset.type, t.dataset.prop)) return;
      const list = t.dataset.remove === 'vertex'
        ? draft.vertices[Number(t.dataset.type)].properties
        : draft.edges[Number(t.dataset.type)].properties;
      list.splice(Number(t.dataset.prop), 1);
      render();
      refreshDataForm();
    } else if (t.dataset.removeVertex !== undefined) {
      const vertex = draft.vertices[Number(t.dataset.removeVertex)];
      if (!confirmCost(`从 schema 删除点类型「${vertex.name || '（未命名）'}」？`, [
        '· 已经写进存储的点不会被删除：它们只是不再被这张 schema 描述（查询里看不见，重加同名类型后又能看到）。',
        `· 指向它的边类型仍然声明着这个类型（${draft.edges.filter((e) => e.source === vertex.name || e.target === vertex.name).map((e) => e.name).join('、') || '目前没有'}），保存会校验失败，除非一并改掉。`,
      ])) return;
      draft.vertices.splice(Number(t.dataset.removeVertex), 1);
      render();
      refreshDataForm();
    } else if (t.dataset.removeEdge !== undefined) {
      const edge = draft.edges[Number(t.dataset.removeEdge)];
      if (!confirmCost(`从 schema 删除边类型「${edge.name || '（未命名）'}」？`, [
        '· 存量的边不会被删除：两个端点上的那两行还在，查询里看不见而已。',
        '· 动态索引（向量层、地理）如果建在它上面，也随之不再被描述。',
      ])) return;
      draft.edges.splice(Number(t.dataset.removeEdge), 1);
      render();
      refreshDataForm();
    }
  }

  function preview() {
    el('jsonPreview').textContent = JSON.stringify(state.draft, null, 2);
  }

  // ------------------------------------------------------------------ saving

  function showResult(node, ok, title, lines) {
    node.hidden = false;
    node.className = `result ${ok ? 'ok' : 'bad'}`;
    node.innerHTML = `${escapeHtml(title)}${lines && lines.length
      ? `<ul>${lines.map((line) => `<li>${escapeHtml(line)}</li>`).join('')}</ul>` : ''}`;
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
        [`文件：${body.file}`, '现在可以直接查询这张图（下拉里已经出现）',
          '下面「造数据」的属性列已经跟着新 schema 更新']);
      toast(`已保存并加载 ${body.graph}`);
      await loadGraphs(body.graph);
      // the write form reads the draft, and the draft is what was just saved: refresh it so a property
      // added in this session shows up as a column right away
      render();
      refreshDataForm();
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
    const chosen = el('dataVertexType').value;
    el('dataVertexType').innerHTML = vertexNames.map((n) => `<option value="${attr(n)}">${escapeHtml(n)}</option>`).join('');
    if (vertexNames.includes(chosen)) el('dataVertexType').value = chosen;
    const edgeNames = state.draft.edges.map((edge) => edge.name).filter(Boolean);
    const chosenEdge = el('dataEdgeType').value;
    el('dataEdgeType').innerHTML = edgeNames.map((n) => `<option value="${attr(n)}">${escapeHtml(n)}</option>`).join('');
    if (edgeNames.includes(chosenEdge)) el('dataEdgeType').value = chosenEdge;
    el('dataDeleteVertexType').innerHTML = el('dataVertexType').innerHTML;
    el('dataDeleteEdgeType').innerHTML = el('dataEdgeType').innerHTML;
    renderPropertyInputs();
  }

  /** The value inputs for the selected vertex/edge type (property indexes come from the order). */
  function renderPropertyInputs() {
    const vertex = state.draft.vertices.find((v) => v.name === el('dataVertexType').value);
    el('dataVertexProps').innerHTML = (vertex?.properties ?? []).map((property, index) =>
      `<label>${escapeHtml(property.name)}<input data-value-vertex="${index + 1}" placeholder="${attr(property.type ?? 'string')}"></label>`).join('');
    const edge = state.draft.edges.find((e) => e.name === el('dataEdgeType').value);
    el('dataEdgeProps').innerHTML = (edge?.properties ?? []).map((property, index) =>
      `<label>${escapeHtml(property.name)}<input data-value-edge="${index + 1}" placeholder="${attr(property.type ?? 'string')}"></label>`).join('');
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

  function vertexPropertyRequests() {
    const vertex = state.draft.vertices.find((v) => v.name === el('dataVertexType').value);
    return [...document.querySelectorAll('[data-value-vertex]')].map((input) => ({
      index: Number(input.dataset.valueVertex),
      value: valueOf(input.value, (vertex?.properties ?? [])[Number(input.dataset.valueVertex) - 1]?.type),
      lastModifiedTime: Date.now(),
    })).filter((entry) => entry.value !== null);
  }

  function edgePropertyRequests() {
    const edge = state.draft.edges.find((e) => e.name === el('dataEdgeType').value);
    return [...document.querySelectorAll('[data-value-edge]')].map((input) => ({
      index: Number(input.dataset.valueEdge),
      value: valueOf(input.value, (edge?.properties ?? [])[Number(input.dataset.valueEdge) - 1]?.type),
      lastModifiedTime: Date.now(),
    })).filter((entry) => entry.value !== null);
  }

  /** Whether an element of this id is already stored (the write would merge into it, not create it). */
  async function exists(graph, type, id) {
    const res = await api('/api/query', {
      graphName: graph, includeResult: true, returnSubgraph: false,
      query: `g.V('${String(id).replace(/'/g, '')}','${String(type).replace(/'/g, '')}').limit(1)`,
    });
    const value = res.body?.result ?? res.body;
    return Array.isArray(value) && value.length > 0;
  }

  async function writeVertex() {
    const graph = state.draft.name;
    const typeName = el('dataVertexType').value;
    const id = el('dataVertexId').value.trim();
    if (!graph || !typeName || !id) {
      showResult(el('dataResult'), false, '写入点：缺参数', ['需要图名、点类型和 id']);
      return;
    }
    // an id that already exists is not created again: the write merges properties (newer timestamps win)
    const known = await exists(graph, typeName, id);
    if (known && !confirmCost(`点 ${id} 已经存在（${typeName}）`, [
      '· 这次写入不会新建，而是按属性时间戳合并：这次填的值会覆盖旧值（时间戳更新）。',
      '· 没填的属性保持原样（旧值不会被清空）。',
      '· 想新建请换一个 id。',
    ])) {
      showResult(el('dataResult'), false, `已取消：${id} 已存在`, ['没有写入']);
      return;
    }
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/vertices`, {
      id, vertexSchemaName: typeName, properties: vertexPropertyRequests() });
    showResult(el('dataResult'), res.ok,
      `写入点：${res.body?.status ?? res.status}${known ? '（已存在的 id，合并写入）' : ''}`,
      [JSON.stringify(res.body).slice(0, 200)]);
  }

  async function writeEdge() {
    const graph = state.draft.name;
    const typeName = el('dataEdgeType').value;
    const edgeId = el('dataEdgeId').value.trim();
    const source = el('dataEdgeSource').value.trim();
    const destination = el('dataEdgeTarget').value.trim();
    if (!graph || !typeName || !edgeId || !source) {
      showResult(el('dataResult'), false, '写入边：缺参数', ['需要图名、边类型、边 id 和起点']);
      return;
    }
    const known = await exists(graph, typeName, edgeId);
    if (known && !confirmCost(`边 ${edgeId} 已经存在（${typeName}）`, [
      '· 这次写入按属性时间戳合并到已有的边上，不会新建。',
      '· 起点/终点以这次填的为准（两个端点上的那两行都会改写）。',
    ])) {
      showResult(el('dataResult'), false, `已取消：${edgeId} 已存在`, ['没有写入']);
      return;
    }
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/edges`, {
      edgeId, edgeSchemaName: typeName, source, destination, properties: edgePropertyRequests() });
    showResult(el('dataResult'), res.ok,
      `写入边：${res.body?.status ?? res.status}${known ? '（已存在的边，合并写入）' : ''}`,
      [JSON.stringify(res.body).slice(0, 200)]);
  }

  // ------------------------------------------------------------------ deleting stored data

  async function deleteVertexData() {
    const graph = state.draft.name;
    const typeName = el('dataDeleteVertexType').value;
    const id = el('dataDeleteVertexId').value.trim();
    if (!graph || !id) {
      showResult(el('dataResult'), false, '删除点：缺参数', ['需要图名和 id']);
      return;
    }
    if (!confirmCost(`删除点 ${id}（${typeName || '类型未知'}）？`, [
      '· 这个点的记录被删除，之后按 id 读它返回空。',
      '· 它的出边/入边不会被一起删除：那些边仍然存在于对端（形成悬空边），除非另外删掉。',
      '· 不可撤销（除非重新写入）。',
    ])) return;
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/vertices/${encodeURIComponent(id)}`,
      undefined, 'DELETE');
    showResult(el('dataResult'), res.ok, `删除点：${res.body?.status ?? res.status}`,
      [JSON.stringify(res.body).slice(0, 200)]);
  }

  async function deleteEdgeData() {
    const graph = state.draft.name;
    const typeName = el('dataDeleteEdgeType').value;
    const edgeId = el('dataDeleteEdgeId').value.trim();
    const source = el('dataDeleteEdgeSource').value.trim();
    const destination = el('dataDeleteEdgeTarget').value.trim();
    if (!graph || !typeName || !edgeId || !source) {
      showResult(el('dataResult'), false, '删除边：缺参数', ['需要图名、边类型、边 id、起点和终点']);
      return;
    }
    if (!confirmCost(`删除边 ${edgeId}（${typeName}）？`, [
      `· 这条边存在两处（${source} 的出边、${destination} 的入边），两处都会被删除。`,
      '· 起点/终点必须与写入时一致，否则找不到这条边（会报 not found）。',
      '· 不可撤销（除非重新写入）。',
    ])) return;
    const res = await api(`/api/graphs/${encodeURIComponent(graph)}/edges/${encodeURIComponent(edgeId)}`
      + `?edgeSchemaName=${encodeURIComponent(typeName)}&source=${encodeURIComponent(source)}`
      + `&destination=${encodeURIComponent(destination)}`, undefined, 'DELETE');
    showResult(el('dataResult'), res.ok, `删除边：${res.body?.status ?? res.status}`,
      [JSON.stringify(res.body).slice(0, 200)]);
  }

  // ------------------------------------------------------------------ CSV import

  /**
   * A small RFC4180 reader: quoted fields, doubled quotes and commas or newlines inside them. The
   * builder writes one request per row, so the file's shape is the caller's business - the header row
   * names the columns (id, plus the properties, plus source/destination for edges).
   */
  function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quoted) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false;
        } else field += c;
      } else if (c === '"') {
        quoted = true;
      } else if (c === ',') {
        row.push(field); field = '';
      } else if (c === '\n') {
        row.push(field); rows.push(row); row = []; field = '';
      } else if (c !== '\r') {
        field += c;
      }
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }
    return rows.filter((columns) => columns.some((column) => column.trim() !== ''));
  }

  /** The property indexes a CSV header row maps to (index = declaration order, id is the element's id). */
  function headerMapping(kind, header) {
    const owner = kind === 'vertex'
      ? state.draft.vertices.find((v) => v.name === el('dataVertexType').value)
      : state.draft.edges.find((e) => e.name === el('dataEdgeType').value);
    const properties = owner?.properties ?? [];
    const mapping = { id: header.indexOf('id'), unknown: [] };
    properties.forEach((property, index) => {
      mapping[property.name] = header.indexOf(property.name);
      mapping['#' + property.name] = index + 1;
      if (mapping[property.name] < 0) mapping.unknown.push(property.name);
    });
    if (kind === 'edge') {
      mapping.source = header.indexOf('source');
      mapping.destination = header.indexOf('destination');
    }
    return mapping;
  }

  async function importCsv(kind) {
    const file = state.csv[kind];
    const graph = state.draft.name;
    const typeName = kind === 'vertex' ? el('dataVertexType').value : el('dataEdgeType').value;
    const node = el('dataResult');
    if (!file) { showResult(node, false, '导入 CSV：还没有选文件', []); return; }
    if (!graph || !typeName) { showResult(node, false, '导入 CSV：先选好图和类型', []); return; }

    const rows = parseCsv(await file.text());
    if (rows.length < 2) { showResult(node, false, '导入 CSV：文件里只有表头', []); return; }
    const header = rows[0].map((column) => column.trim());
    const mapping = headerMapping(kind, header);
    const body = rows.slice(1);
    const unknownColumns = header.filter((column) => column !== 'id' && column !== 'source'
      && column !== 'destination' && !(mapping[column] >= 0));

    const missing = body.filter((columns) => mapping.id < 0 || !columns[mapping.id]?.trim());
    if (mapping.unknown?.length) {
      showResult(node, false, `导入 CSV：表头缺属性列`, [
        `schema 里的属性没有对应的列：${mapping.unknown.join('、')}`,
        `文件表头：${header.join('、')}`,
      ]);
      return;
    }
    if (kind === 'edge' && (mapping.source < 0 || mapping.destination < 0)) {
      showResult(node, false, '导入 CSV：边需要 source/destination 两列', [`表头：${header.join('、')}`]);
      return;
    }

    const lines = [
      `文件：${file.name}`,
      `表头：${header.join('、')}`,
      `数据行：${body.length}${missing.length ? `（其中 ${missing.length} 行没有 id，会被跳过）` : ''}`,
      unknownColumns.length ? `未识别的列（忽略）：${unknownColumns.join('、')}` : '每列都对应一个属性',
    ];
    if (!confirmCost(`把这个 CSV 写进 ${graph}.${typeName}？`, lines)) return;

    const requests = [];
    for (const columns of body) {
      const id = columns[mapping.id]?.trim();
      if (!id) continue;
      const properties = (kind === 'vertex'
        ? (state.draft.vertices.find((v) => v.name === typeName)?.properties ?? [])
        : (state.draft.edges.find((e) => e.name === typeName)?.properties ?? []))
        .map((property, index) => ({
          index: index + 1,
          value: valueOf(columns[mapping[property.name]] ?? '', property.type),
          lastModifiedTime: Date.now(),
        })).filter((entry) => entry.value !== null);
      if (kind === 'vertex') {
        requests.push({ id, vertexSchemaName: typeName, properties });
      } else {
        requests.push({
          edgeId: id, edgeSchemaName: typeName,
          source: columns[mapping.source]?.trim(), destination: columns[mapping.destination]?.trim(),
          properties,
        });
      }
    }

    // chunks: one request per few hundred rows, so a big file is a handful of round trips
    const chunkSize = 200;
    let written = 0;
    const failures = [];
    for (let start = 0; start < requests.length; start += chunkSize) {
      const chunk = requests.slice(start, start + chunkSize);
      const payload = kind === 'vertex' ? { vertices: chunk } : { edges: chunk };
      const res = await api('/api/batch', payload);
      if (!res.ok) {
        failures.push(...chunk.map((request) => kind === 'vertex' ? request.id : request.edgeId));
        continue;
      }
      const results = Array.isArray(res.body) ? res.body : [];
      written += results.filter((result) => (result.status ?? 'SUCCESS') === 'SUCCESS').length;
      results.filter((result) => (result.status ?? '') !== 'SUCCESS')
        .forEach((result) => failures.push(`${result.id}:${result.status}`));
      el('dataResult').hidden = false;
      el('dataResult').className = 'result ok';
      el('dataResult').textContent = `导入中… ${Math.min(start + chunkSize, requests.length)}/${requests.length}`;
    }
    showResult(el('dataResult'), failures.length === 0,
      `导入完成：写入 ${written} 条${failures.length ? `，失败 ${failures.length} 条` : ''}`,
      failures.slice(0, 10));
  }

  // ------------------------------------------------------------------ startup

  async function loadStorageTypes() {
    const res = await api('/api/schema-storage-types', undefined, 'GET');
    state.storageTypes = Array.isArray(res.body) ? res.body : [];
    el('storageType').innerHTML = state.storageTypes
      .map((type) => `<option value="${attr(type.name)}">${escapeHtml(type.name)}</option>`).join('');
  }

  async function loadGraphs(select) {
    const res = await api('/api/graphs', undefined, 'GET');
    state.graphs = Array.isArray(res.body) ? res.body : [];
    el('graphPicker').innerHTML = ['', ...state.graphs.map((g) => g.name)]
      .map((name) => `<option value="${attr(name)}">${escapeHtml(name || '打开已有的图…')}</option>`).join('');
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
      refreshDataForm();
    });
    el('addEdge').addEventListener('click', () => {
      state.draft.edges.push({ name: '', description: '', direction: 'DIRECTED',
        source: state.draft.vertices[0]?.name ?? null, target: state.draft.vertices[0]?.name ?? null,
        targetIsNull: false, maxOutDegree: null, maxInDegree: null, properties: [] });
      render();
      refreshDataForm();
    });
    el('validateBtn').addEventListener('click', validateDraft);
    el('saveBtn').addEventListener('click', saveDraft);
    el('exportBtn').addEventListener('click', exportDraft);
    el('dataVertexType').addEventListener('change', renderPropertyInputs);
    el('dataEdgeType').addEventListener('change', renderPropertyInputs);
    el('addVertexData').addEventListener('click', writeVertex);
    el('addEdgeData').addEventListener('click', writeEdge);
    el('deleteVertexData').addEventListener('click', deleteVertexData);
    el('deleteEdgeData').addEventListener('click', deleteEdgeData);
    el('csvVertexFile').addEventListener('change', (e) => {
      state.csv.vertex = e.target.files?.[0] ?? null;
      el('csvVertexHint').textContent = state.csv.vertex
        ? `已选 ${state.csv.vertex.name}（表头需要 id 与各属性名）` : '';
    });
    el('csvEdgeFile').addEventListener('change', (e) => {
      state.csv.edge = e.target.files?.[0] ?? null;
      el('csvEdgeHint').textContent = state.csv.edge
        ? `已选 ${state.csv.edge.name}（表头需要 id、source、destination 与各属性名）` : '';
    });
    el('importVertexCsv').addEventListener('click', () => importCsv('vertex'));
    el('importEdgeCsv').addEventListener('click', () => importCsv('edge'));
    el('csvVertexTemplate').addEventListener('click', () => downloadTemplate('vertex'));
    el('csvEdgeTemplate').addEventListener('click', () => downloadTemplate('edge'));
    el('fuzzyValidate').addEventListener('click', previewFuzzy);
    el('fuzzySave').addEventListener('click', saveFuzzy);
    el('fuzzyRemove').addEventListener('click', removeFuzzy);
    el('fuzzyFunctions').addEventListener('click', showFuzzyFunctions);
    el('fuzzyLink').addEventListener('click', linkFuzzyVertex);
    el('fuzzyGraph').addEventListener('change', () => { loadFuzzyPatterns().catch(() => {}); });
  }

  /** A CSV skeleton for the selected type: its header row, so a spreadsheet starts from the right columns. */
  function downloadTemplate(kind) {
    const typeName = kind === 'vertex' ? el('dataVertexType').value : el('dataEdgeType').value;
    const owner = kind === 'vertex'
      ? state.draft.vertices.find((v) => v.name === typeName)
      : state.draft.edges.find((e) => e.name === typeName);
    const columns = kind === 'vertex'
      ? ['id', ...(owner?.properties ?? []).map((p) => p.name)]
      : ['id', 'source', 'destination', ...(owner?.properties ?? []).map((p) => p.name)];
    const blob = new Blob([columns.join(',') + '\n'], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${state.draft.name || 'graph'}-${typeName || kind}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  init().catch((err) => toast('初始化失败: ' + err.message));
})();
