/*
 * 地图视图：把「带经纬度的点」画在 Leaflet 上，供主界面（index.html 的可视化 panel）内嵌使用。
 *
 * 这一块原来是一个独立页面（map.html + js/map.js），现在抽成组件：app.js 在选中「图里带
 * latitude/longitude 的图实例」时显示工具栏上的地图图标，点开就在画布区域画出地图，再点回到图可视化。
 *
 * 两条经验留在这里（都是上一轮真踩过的坑）：
 *  1. **接口走同源代理** `/api/query`（graph-web -> graph-http）。直连 8081 会跨域：graph-http 没有 CORS 头，
 *     浏览器把响应拦掉，只剩一句「加载失败」。
 *  2. **底图有备用**：OpenStreetMap（WGS84，与数据同坐标系）优先；瓦片报错或超时就换高德（GCJ-02，国内可达），
 *     并把点位 WGS84 -> GCJ-02 再画，点才落在正确的城市上；两家都不行才退化成素色背景（点位照常）。
 */
(function () {
  'use strict';

  // 三国有据可查的活动范围：北至涿郡(39.5)、南至长沙(28)、西至祁山(105)、东至建业(118.8)
  var CENTRE = [33.5, 111.5];

  var PROVIDERS = [
    {
      name: 'OpenStreetMap',
      url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
      subdomains: 'abc',
      gcj02: false
    },
    {
      // 高德栅格瓦片：国内可达；它用 GCJ-02（火星坐标），所以点位要换算后再画
      name: '高德地图',
      url: 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
      subdomains: '1234',
      gcj02: true
    }
  ];

  /** 一张瓦片都没画出来的等待上限：超了就换下一家底图（挂住和报错都算不可用）。 */
  var BASEMAP_TIMEOUT_MS = 6000;

  /** 上次能用的底图：再打开地图时不必把超时重等一遍。 */
  var preferred = 0;

  /** 当前打开的地图（一个容器一张，重复 show 就是更新点位）。 */
  var active = null;

  /**
   * 打开（或更新）一个容器里的地图。
   *
   * @param container 地图容器（调用方保证它有尺寸、可见）
   * @param points    点位：[{name, latitude, longitude, kind, kindLabel, note}]，坐标为 WGS84
   * @param options   {onNote: function({kind, provider})}：底图状态回调（文案由调用方做 i18n）
   * @return 地图是否打开（Leaflet 缺失时为 false，调用方据此把按钮状态收回）
   */
  function show(container, points, options) {
    if (!window.L) {
      if (options && options.onNote) { options.onNote({ kind: 'noleaflet' }); }
      return false;
    }
    if (active && active.container === container) {
      active.points = points || [];
      active.options = options || active.options;
      drawPoints(active);
      return true;
    }
    if (active) { destroy(active); }

    var record = {
      container: container,
      points: points || [],
      options: options || {},
      providerIndex: preferred,
      map: null,
      group: null,
      tiles: null
    };
    active = record;
    container.innerHTML = '';
    record.map = L.map(container, { zoomControl: true, attributionControl: true }).setView(CENTRE, 5);
    record.group = L.featureGroup().addTo(record.map);
    useProvider(record, preferred);
    drawPoints(record);
    // 容器刚由 hidden 变成可见时尺寸才算得出来，等一帧再量
    setTimeout(function () { if (record.map) { record.map.invalidateSize(); } }, 0);
    return true;
  }

  /** 关掉容器里的地图（销毁 Leaflet 实例，容器恢复空白）。 */
  function hide(container) {
    if (active && (!container || active.container === container)) {
      destroy(active);
      active = null;
    }
  }

  function destroy(record) {
    if (record.map) {
      record.map.remove();
      record.map = null;
    }
    record.tiles = null;
    record.group = null;
    record.container.innerHTML = '';
    record.container.classList.remove('nobase');
  }

  /**
   * 用第 index 家底图：先去掉旧的瓦片层，再按 tileload/tileerror/超时 三个信号判断它能不能用。
   * 判断「不可用」的条件偏保守（一张都没成功，且错够 4 张或等了 6 秒），避免网络慢一点就误换。
   */
  function useProvider(record, index) {
    if (record.tiles) { record.map.removeLayer(record.tiles); record.tiles = null; }
    if (index >= PROVIDERS.length) {
      record.container.classList.add('nobase');
      note(record, { kind: 'none' });
      return;
    }
    var provider = PROVIDERS[index];
    record.providerIndex = index;
    var loaded = 0;
    var errors = 0;
    var tiles = L.tileLayer(provider.url, {
      maxZoom: 18,
      subdomains: provider.subdomains,
      attribution: '&copy; ' + provider.name
    }).addTo(record.map);
    record.tiles = tiles;
    tiles.on('tileload', function () { loaded += 1; });
    tiles.on('tileerror', function () {
      errors += 1;
      if (loaded === 0 && errors >= 4 && record.providerIndex === index) {
        useProvider(record, index + 1);
      }
    });
    setTimeout(function () {
      if (loaded === 0 && record.providerIndex === index && record.tiles === tiles) {
        useProvider(record, index + 1);
      }
    }, BASEMAP_TIMEOUT_MS);
    note(record, { kind: index === 0 ? 'provider' : 'fallback', provider: provider.name });
  }

  /** 按当前底图把点位画出来：GCJ-02 底图上先把 WGS84 换算过去，否则点会偏几百米。 */
  function drawPoints(record) {
    if (!record.map) { return; }
    record.group.clearLayers();
    var provider = PROVIDERS[record.providerIndex] || PROVIDERS[0];
    record.points.forEach(function (point) {
      var at = provider.gcj02 ? GCJ.fromWgs84(point.latitude, point.longitude)
        : [point.latitude, point.longitude];
      var radius = point.kind === 'Event' ? 7 : (point.kind === 'Place' ? 9 : 5);
      var colour = point.kind === 'Event' ? '#e11d48' : (point.kind === 'Place' ? '#2563eb' : '#16a34a');
      L.circleMarker(at, {
        radius: radius, color: '#334155', weight: 1, fillColor: colour, fillOpacity: 0.85
      }).addTo(record.group).bindPopup(popupHtml(point));
    });
    if (record.points.length) {
      try { record.map.fitBounds(record.group.getBounds().pad(0.15), { maxZoom: 7 }); } catch (ignored) { /* 单点 */ }
    }
  }

  function popupHtml(point) {
    var head = '<b>' + escapeHtml(point.name || '') + '</b>';
    var kind = point.kindLabel ? escapeHtml(point.kindLabel) : '';
    var noteText = point.note ? escapeHtml(point.note) : '';
    var line = (kind || noteText) ? '<br>' + kind + (kind && noteText ? ' ' : '') + noteText : '';
    // 坐标按存储里的 WGS84 报出来（底图换过坐标系，数据没换）
    var coords = '<br><span style="color:#64748b">' + point.latitude.toFixed(3) + ', '
      + point.longitude.toFixed(3) + ' (WGS84)</span>';
    return head + line + coords;
  }

  function note(record, message) {
    if (record.options && record.options.onNote) { record.options.onNote(message); }
  }

  function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /**
   * WGS84 -> GCJ-02（火星坐标系）：高德/腾讯的瓦片用它。中国境内的偏移算法是公开的固定式，
   * 换算误差在 1e-6 度量级（远小于一个像素），够点位落在正确的城市上。
   */
  var GCJ = {
    a: 6378245.0,
    ee: 0.00669342162296594323,
    outOfChina: function (lat, lng) {
      return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    },
    transformLat: function (x, y) {
      var ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
      ret += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
      ret += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
      ret += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
      return ret;
    },
    transformLng: function (x, y) {
      var ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
      ret += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
      ret += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
      ret += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
      return ret;
    },
    fromWgs84: function (lat, lng) {
      if (this.outOfChina(lat, lng)) { return [lat, lng]; }
      var dLat = this.transformLat(lng - 105.0, lat - 35.0);
      var dLng = this.transformLng(lng - 105.0, lat - 35.0);
      var radLat = lat / 180.0 * Math.PI;
      var magic = Math.sin(radLat);
      magic = 1 - this.ee * magic * magic;
      var sqrtMagic = Math.sqrt(magic);
      dLat = (dLat * 180.0) / ((this.a * (1 - this.ee)) / (magic * sqrtMagic) * Math.PI);
      dLng = (dLng * 180.0) / (this.a / sqrtMagic * Math.cos(radLat) * Math.PI);
      return [lat + dLat, lng + dLng];
    }
  };

  window.PhoenixGeoMap = {
    /** Leaflet 是否加载（就近自带在 vendor/leaflet）。 */
    available: function () { return !!window.L; },
    show: show,
    hide: hide,
    providers: PROVIDERS.map(function (p) { return p.name; }),
    gcj: GCJ
  };
})();
