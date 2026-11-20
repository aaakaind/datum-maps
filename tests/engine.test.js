// Engine tests: run with `npm test` (Node 20+, no dependencies).
// Fixtures are two adjacent OpenFreeMap zoom-14 tiles north of downtown Gatineau
// (© OpenStreetMap contributors, ODbL 1.0).
'use strict';
const test = require('node:test'), assert = require('node:assert'), fs = require('fs'), path = require('path'), zlib = require('zlib');
const mvt = require('../js/mvt.js'), GR = require('../js/graph.js'), RT = require('../js/router.js'), SE = require('../js/search.js'), NAV = require('../js/nav.js');
globalThis.DATUM = { idb: {} }; require('../js/offline.js'); const OFF = globalThis.DATUM.offline;

const TILES = [[4747, 5864], [4748, 5864]];
const tile = (x, y) => new Uint8Array(zlib.gunzipSync(fs.readFileSync(path.join(__dirname, 'fixtures', `14-${x}-${y}.pbf.gz`))));
function build(opts) { const b = new GR.GraphBuilder(Object.assign({ z: 14 }, opts)); for (const [x, y] of TILES) b.addTile(x, y, tile(x, y)); return b.finish(); }
const built = build(), R = new RT.Router(built.graph), g = built.graph;
// two points well inside the fixture, on different tiles
const mid = (i) => [GR.wx2lon(g.nx[i]), GR.wy2lat(g.ny[i])];
function farPair(mode) {
  const main = R.main[mode], ids = []; for (let i = 0; i < g.nN; i++) if (main[i]) ids.push(i);
  ids.sort((a, b) => g.nx[a] - g.nx[b]); const a = mid(ids[Math.floor(ids.length * 0.1)]), b = mid(ids[Math.floor(ids.length * 0.9)]);
  return [{ lon: a[0], lat: a[1] }, { lon: b[0], lat: b[1], label: 'Test end' }];
}

test('vector tile decoder reads layers, properties and geometry', () => {
  const d = mvt.decode(tile(4747, 5864));
  assert.ok(d.transportation && d.transportation.features.length > 50);
  const road = d.transportation.features.find(f => f.type === 2 && f.props.class);
  assert.ok(road.geom[0].length >= 4 && Number.isInteger(road.geom[0][0]));
  assert.deepStrictEqual(Object.keys(mvt.decode(tile(4747, 5864), ['place'])), ['place']);
});

test('graph builder nodes the roads and stitches across the tile border', () => {
  const s = built.stats;
  assert.ok(s.nodes > 500 && s.edges > s.nodes * 0.9, JSON.stringify(s));
  assert.ok(s.stitched > 3, 'roads crossing the shared border are joined: ' + s.stitched);
  assert.ok(s.names > 20 && built.search.length > 50);
  // most of the network is one connected piece for walking, and a large part for driving
  assert.ok(R.main.walk.size / g.nN > 0.75, 'walk component ' + R.main.walk.size + '/' + g.nN);
  assert.ok(R.main.car.size / g.nN > 0.25, 'car component ' + R.main.car.size + '/' + g.nN);
  for (let i = 0; i < g.nE; i++) assert.ok(R.len[i] >= 0 && R.len[i] < 20000);
});

test('roads level keeps only drivable roads', () => {
  const r = build({ drivableOnly: true });
  assert.ok(r.stats.edges < built.stats.edges);
  for (let i = 0; i < r.graph.nE; i++) assert.ok(r.graph.ecls[i] <= GR.C.service || r.graph.ecls[i] === GR.C.ferry);
});

for (const mode of ['car', 'walk', 'bike']) test(`router finds a ${mode} route across the border with steps`, () => {
  const [a, b] = farPair(mode), r = R.route(a, b, { mode, alternatives: 2 });
  assert.ok(!r.error, r.error);
  const crow = RT.distLL([a.lon, a.lat], [b.lon, b.lat]);
  assert.ok(r.dist >= crow * 0.98 && r.dist < crow * 4, `distance ${r.dist} vs crow ${crow}`);
  const kmh = r.dist / r.seconds * 3.6, lim = { car: [8, 110], walk: [3, 5.2], bike: [5, 20] }[mode];
  assert.ok(kmh > lim[0] && kmh < lim[1], `average speed ${kmh.toFixed(1)} km/h`);
  assert.ok(r.coords.length > 5 && r.steps.length >= 2);
  assert.strictEqual(r.steps[0].type, 'depart'); assert.strictEqual(r.steps[r.steps.length - 1].type, 'arrive');
  // the line is continuous: no jump larger than the longest edge
  for (let i = 1; i < r.coords.length; i++) assert.ok(RT.distLL(r.coords[i - 1], r.coords[i]) < 3000);
  for (const alt of r.alts) assert.ok(alt.seconds >= r.seconds * 0.95 && alt.coords.length > 2);
});

test('one-way streets are respected', () => {
  let checked = 0;
  for (let e = 0; e < g.nE && checked < 25; e++) {
    const f = g.eflag[e]; if (!(f & GR.F.CAR_F) || (f & GR.F.CAR_B) || R.len[e] < 60 || !R.main.car[g.ea[e]] || !R.main.car[g.eb[e]]) continue;
    const c = R._arcCoords(2 * e, 0, 1), p = (t) => { const i = Math.min(c.length - 2, Math.floor(t * (c.length - 1))); return { lon: (c[i][0] + c[i + 1][0]) / 2, lat: (c[i][1] + c[i + 1][1]) / 2 }; };
    const A = R.snap(p(0.2).lon, p(0.2).lat, 'car', 50, { raw: true }), B = R.snap(p(0.8).lon, p(0.8).lat, 'car', 50, { raw: true });
    if (!A || !B || A.edge !== e || B.edge !== e || B.t <= A.t + 0.2) continue;
    const fwd = R.route(p(0.2), p(0.8), { mode: 'car' }), back = R.route(p(0.8), p(0.2), { mode: 'car' });
    if (fwd.error || back.error) continue;
    assert.ok(back.dist > fwd.dist + 20, `against the one-way must go around: ${back.dist} vs ${fwd.dist}`); checked++;
  }
  assert.ok(checked > 0, 'fixture has one-way streets to check');
});

test('typical traffic slows a rush-hour drive and never speeds one up', () => {
  const [a, b] = farPair('car'), day = Date.UTC(2026, 10, 18); // a Wednesday
  const lon0 = R.lon0, at = (h) => day + h * 3600000 - (lon0 / 15) * 3600000;
  const night = R.route(a, b, { mode: 'car', traffic: 'typical', depart: at(3) }), rush = R.route(a, b, { mode: 'car', traffic: 'typical', depart: at(17.25) }), none = R.route(a, b, { mode: 'car', traffic: 'none' });
  assert.ok(rush.seconds >= night.seconds && night.seconds >= none.seconds * 0.999);
  assert.ok(rush.free != null && rush.free <= rush.seconds + 1e-6);
  assert.ok(RT.typicalFactor(GR.C.motorway, true, day + 3 * 3600000) < 1.01);
  assert.ok(RT.typicalFactor(GR.C.motorway, true, day + 17.25 * 3600000) > 1.4);
  assert.ok(RT.typicalFactor(GR.C.motorway, true, day + 17.25 * 3600000) > RT.typicalFactor(GR.C.minor, true, day + 17.25 * 3600000));
});

test('live speed ratios override the model', () => {
  const [a, b] = farPair('car'), base = R.route(a, b, { mode: 'car', traffic: 'none' });
  R.live = new Float32Array(g.nE).fill(0.5); const slow = R.route(a, b, { mode: 'car', traffic: 'none' }); R.live = null;
  assert.ok(slow.seconds > base.seconds * 1.5);
});

test('on-device search finds streets and places, nearest first for categories', () => {
  const ix = new SE.Index(built.search), street = built.search.find(s => s[2] === 'street');
  const hits = ix.query(street[0], [street[4], street[5]], 5);
  assert.strictEqual(hits[0].name, street[0]);
  assert.deepStrictEqual(ix.query('zzzzqqq', null, 5), []);
  const poi = built.search.find(s => s[2] === 'poi'), near = ix.nearby([poi[3].split('/')[0]], [poi[4], poi[5]], 500, 5);
  assert.ok(near.length && near[0].m < 1);
});

test('navigation tracker follows the route and counts down', () => {
  const [a, b] = farPair('car'), r = R.route(a, b, { mode: 'car' }), tr = new NAV.Tracker(r);
  assert.ok(Math.abs(tr.total - (r.dist - 0)) < r.dist * 0.2);
  let lastRem = Infinity, lastStep = 0;
  for (let i = 0; i <= 40; i++) { const m = tr.total * i / 40;
    const p = tr.at(m), st = tr.update(p.pt[0], p.pt[1]);
    assert.ok(st.off < 3, 'on the line: ' + st.off); assert.ok(st.remainingM <= lastRem + 1); assert.ok(st.step >= lastStep);
    lastRem = st.remainingM; lastStep = st.step;
  }
  assert.ok(lastRem < 5 && lastStep >= r.steps.length - 2);
  const off = tr.update(r.coords[0][0] + 0.01, r.coords[0][1] + 0.01); assert.ok(off.off > 500);
  assert.match(NAV.say({ turn: 'Turn left', text: 'onto Main Street', type: 'turn' }, 400), /^In 400 metres, turn left onto Main Street$/);
  assert.strictEqual(NAV.arrow({ type: 'turn', mod: 'left' }), -90); assert.strictEqual(NAV.arrow({ type: 'arrive' }), null);
});

test('download planner picks a level that fits and lists every tile once', () => {
  const city = OFF.plan({ name: 'c', bbox: [-75.75, 45.38, -75.65, 45.45] });
  assert.strictEqual(city.level, 'streets'); assert.strictEqual(city.z, 14);
  const pyr = OFF.pyramid(city.xy, 14), keys = new Set(pyr.map(t => t.join('/')));
  assert.strictEqual(keys.size, pyr.length); assert.ok(keys.has('0/0/0')); assert.strictEqual(city.total, pyr.length);
  const region = OFF.plan({ name: 'r', bbox: [3.3, 50.7, 7.3, 53.6] }); assert.strictEqual(region.level, 'roads');
  const huge = OFF.plan({ name: 'h', bbox: [-140, 42, -52, 83] }); assert.strictEqual(huge.level, 'map'); assert.ok(huge.count <= 6000);
  const world = OFF.plan({ name: 'w', bbox: [-180, -85.05, 180, 85.05], z: 4 }); assert.strictEqual(world.total, 341);
  // a triangle needs fewer tiles than its bounding box
  const tri = OFF.enumerate({ rings: [[[0, 0], [4, 0], [0, 4]]] }, 9).length, box = OFF.enumerate({ bbox: [0, 0, 4, 4] }, 9).length;
  assert.ok(tri < box * 0.85 && tri > box * 0.4, `${tri} vs ${box}`);
});

test('region pack converts to the same graph and routes', () => {
  const pack = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'packs', 'ottawa-gatineau', 'roads.json'))), pr = new RT.Router(GR.fromPack(pack));
  const r = pr.route({ lon: -75.7009, lat: 45.4236 }, { lon: -75.6903, lat: 45.3995, label: 'Lansdowne' }, { mode: 'car', alternatives: 1 });
  assert.ok(!r.error, r.error); assert.ok(r.dist > 2800 && r.dist < 4500, 'distance ' + r.dist); assert.ok(r.seconds > 300 && r.seconds < 900, 'time ' + r.seconds);
  const w = pr.route({ lon: -75.7009, lat: 45.4236 }, { lon: -75.6903, lat: 45.3995 }, { mode: 'walk' }); assert.ok(w.dist > 2800 && w.dist < 4200);
});
