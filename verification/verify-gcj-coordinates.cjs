// needs: none
// what: the offline WGS84 -> GCJ-02 conversion the map fallback applies
// run:  node verification/run.mjs
// Checks the WGS84 -> GCJ-02 conversion the map applies when it falls back to 高德 tiles: the function
// is lifted straight out of js/map.js (so what is checked is what the page runs), every Chinese offset
// must land in the documented range and shift east, and coordinates outside China must be untouched.
// The 北京 anchor is the published Tiananmen pair (WGS84 116.39740 -> GCJ-02 116.40358, i.e. +0.0062
// degrees of longitude), which is what pins the implementation down.
const fs = require('fs');
const source = fs.readFileSync('graph-web/src/main/resources/static/js/geo-map.js', 'utf8');
const start = source.indexOf('  var GCJ = {');
const end = source.indexOf('  };', source.indexOf('fromWgs84', start)) + 4;
if (start < 0 || end < 4) { throw new Error('cannot find the GCJ block in js/map.js'); }
const sandbox = {};
new Function('exports', source.slice(start, end) + '\nObject.assign(exports, {GCJ});')(sandbox);
const GCJ = sandbox.GCJ;

let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };

const cities = [
  ['北京', 39.90870, 116.39740],
  ['成都', 30.57000, 104.06000],
  ['建业/南京', 32.06000, 118.79000],
  ['长沙', 28.23000, 112.94000],
  ['祁山/礼县', 34.19000, 105.10000],
];
for (const [name, lat, lng] of cities) {
  const [mlat, mlng] = GCJ.fromWgs84(lat, lng);
  const north = (mlat - lat) * 111320;
  const east = (mlng - lng) * 111320 * Math.cos(lat * Math.PI / 180);
  const distance = Math.hypot(north, east);
  check(`${name}：偏移 100–800 m 且向东（纬度方向南区向南、北区向北，算法本身如此）`,
    distance > 100 && distance < 800 && east > 0,
    `${north.toFixed(0)} m N, ${east.toFixed(0)} m E, |offset| ${distance.toFixed(0)} m`);
}

const [, beijingLng] = GCJ.fromWgs84(39.90870, 116.39740);
check('北京经度与原样对照的公开值一致（116.39740 -> ~116.4036）',
  Math.abs(beijingLng - 116.4036) < 0.0005, `got ${beijingLng.toFixed(6)}`);

const [olat, olng] = GCJ.fromWgs84(35.6895, 139.6917); // 东京
check('中国境外不换算（东京原样返回）', olat === 35.6895 && olng === 139.6917, `${olat}, ${olng}`);

console.log(failures ? `${failures} FAILURES` : 'the map coordinate conversion is the standard one');
process.exit(failures ? 1 : 0);
