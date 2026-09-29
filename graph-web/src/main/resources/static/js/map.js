/*
 * 《三国演义》地图 — 方案 ⑩
 * 从 sanguo-kg-text 拉取带坐标的实体（都城 + 战场/事件），用 Leaflet 画在地图上。
 *
 * 坐标是策展表（SanguoTextGraph.CURATED_GEO，WGS84）：只有有据可查的都城与战场才有点，
 * 不猜籍贯州郡的坐标。地图底图用 OpenStreetMap（WGS84，与坐标一致）；
 * 若需要国内更快的底图，可换高德瓦片（GCJ-02，坐标会有几百米偏移，需自行纠偏）。
 */
(function () {
  'use strict';

  var API = 'http://localhost:8081/api/v1/query';
  var GRAPH = 'sanguo-kg-text';

  // 三国有据可查的活动范围：北至涿郡(39.5)、南至长沙(28)、西至祁山(105)、东至建业(118.8)
  var map = L.map('map').setView([33.5, 111.5], 5);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 18,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);

  function hint(text) {
    document.getElementById('hint').textContent = text;
  }

  // elementMap 会把 history/novel 长文本也带回来，valueMap 只取需要的字段，轻得多
  var query = "g.V('Entity').has('latitude').valueMap('name','latitude','longitude','entityType','eventYear','eventYearEnd')";

  fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      graphName: GRAPH,
      language: 'gremlin',
      query: query,
      includeResult: true,
      returnSubgraph: true
    })
  })
    .then(function (r) { return r.json(); })
    .then(function (body) {
      if (body.error) { throw new Error(body.error); }
      var rows = body.result || [];
      if (!rows.length) { hint('图上没有带坐标的实体（请确认已重播 sanguo-kg-text）。'); return; }

      var events = 0, places = 0;
      rows.forEach(function (e) {
        var lat = Number(e.latitude), lng = Number(e.longitude);
        if (!isFinite(lat) || !isFinite(lng)) return;
        var isEvent = e.entityType === 'Event';
        var isPlace = e.entityType === 'Place';
        if (isEvent) events++; else if (isPlace) places++;

        L.circleMarker([lat, lng], {
          radius: isEvent ? 7 : (isPlace ? 9 : 5),
          color: '#334155',
          weight: 1,
          fillColor: isEvent ? '#e11d48' : (isPlace ? '#2563eb' : '#16a34a'),
          fillOpacity: 0.85
        }).addTo(map).bindPopup(popupHtml(e));
      });

      hint('已加载 ' + rows.length + ' 个带坐标的实体：都城/城市 ' + places + ' 个，战役/事件 ' + events + ' 个。点击标记看详情。');
    })
    .catch(function (err) {
      hint('加载失败：请确认 graph-http 服务在 8081 端口运行。' + err);
    });

  function popupHtml(e) {
    var year = '';
    if (e.eventYear != null) {
      year = '（' + e.eventYear + (e.eventYearEnd != null ? '–' + e.eventYearEnd : '') + '年）';
    }
    var type = { Place: '都城/城市', Event: '战役/事件', Artifact: '器物', Idiom: '成语' }[e.entityType] || e.entityType;
    return '<b>' + e.name + '</b><br>' + type + year;
  }
})();
