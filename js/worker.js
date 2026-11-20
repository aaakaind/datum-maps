// DATUM · engine worker. Builds road graphs from stored tiles, keeps them in
// memory, and answers route and search requests off the main thread.
'use strict';
importScripts('idb.js', 'mvt.js', 'graph.js', 'router.js', 'search.js');
const { idb, graph: GR, router: RT, search: SE } = self.DATUM;
const areas = new Map(); // id → { meta, router, index }

function attach(id, meta, graph, search) {
  areas.set(id, { meta, router: graph ? new RT.Router(graph) : null, index: search ? new SE.Index(search) : null });
}
async function build(msg, progress) {
  const { area } = msg, t0 = Date.now();
  const b = new GR.GraphBuilder({ z: area.zmax, drivableOnly: area.level !== 'streets' });
  const xy = area.xy, n = xy.length / 2;
  for (let i = 0; i < n; i += 24) {
    const keys = []; for (let j = i; j < Math.min(n, i + 24); j++) keys.push(area.zmax + '/' + xy[2 * j] + '/' + xy[2 * j + 1]);
    const recs = await idb.getMany('tiles', keys);
    for (let j = 0; j < recs.length; j++) {
      const rec = recs[j]; if (!rec || !rec.b || !rec.b.byteLength) continue;
      const buf = new Uint8Array(await idb.gunzip(rec));
      try { b.addTile(xy[2 * (i + j)], xy[2 * (i + j) + 1], buf); } catch (e) { /* a damaged tile only costs its own roads */ }
    }
    progress({ phase: 'build', done: Math.min(n, i + 24), total: n });
  }
  const r = b.finish();
  await idb.putMany('data', [[area.id + ':graph', r.graph], [area.id + ':search', r.search]]);
  attach(area.id, area, r.graph, r.search);
  const a = areas.get(area.id);
  return { stats: r.stats, ranges: r.ranges, ms: Date.now() - t0, main: { walk: a.router.main.walk.size, bike: a.router.main.bike.size, car: a.router.main.car.size } };
}
async function load(msg) {
  const { area } = msg; if (areas.has(area.id)) return { loaded: true };
  const [graph, search] = await idb.getMany('data', [area.id + ':graph', area.id + ':search']);
  if (!graph) return { loaded: false };
  attach(area.id, area, graph, search); return { loaded: true, nodes: graph.nN, edges: graph.nE };
}
function pick(from, to, mode) {
  let best = null;
  for (const a of areas.values()) {
    if (!a.router) continue;
    if (a.meta.level !== 'streets' && a.meta.level !== 'pack' && mode !== 'car') continue;
    if (!a.router.contains(from.lon, from.lat) || !a.router.contains(to.lon, to.lat)) continue;
    const b = a.router.g.bbox, size = (b[2] - b[0]) * (b[3] - b[1]), rank = (a.meta.level === 'roads' ? 0 : 1);
    if (!best || rank > best.rank || (rank === best.rank && size < best.size)) best = { a, rank, size };
  }
  return best && best.a;
}
const ops = {
  build,
  load,
  pack(msg) { attach(msg.area.id, msg.area, GR.fromPack(msg.roads), null); return { loaded: true }; },
  unload(msg) { areas.delete(msg.id); return {}; },
  route(msg) {
    const a = pick(msg.from, msg.to, msg.opts.mode); if (!a) return { none: true };
    const r = a.router.route(msg.from, msg.to, msg.opts); r.area = a.meta.id; r.areaName = a.meta.name; r.level = a.meta.level; return r;
  },
  search(msg) {
    let out = [];
    for (const a of areas.values()) { if (!a.index) continue; for (const h of a.index.query(msg.q, msg.center, msg.limit)) { h.area = a.meta.id; out.push(h); } }
    out.sort((x, y) => y.sc - x.sc);
    const res = []; for (const h of out) { if (res.some(r => r.name === h.name && r.kind === h.kind && Math.abs(r.ll[0] - h.ll[0]) < 0.002 && Math.abs(r.ll[1] - h.ll[1]) < 0.002)) continue; res.push(h); if (res.length >= msg.limit) break; }
    return res;
  },
  nearby(msg) {
    let out = []; for (const a of areas.values()) if (a.index) out = out.concat(a.index.nearby(msg.classes, msg.center, msg.radius, msg.limit));
    out.sort((x, y) => x.m - y.m); return out.slice(0, msg.limit);
  },
  nearestName(msg) {
    let best = null; for (const a of areas.values()) { if (!a.router || !a.router.contains(msg.lon, msg.lat)) continue; const r = a.router.nearestName(msg.lon, msg.lat, 250); if (r && (!best || r.dist < best.dist)) best = r; }
    return best;
  },
  live(msg) { const a = areas.get(msg.id); if (a && a.router) a.router.live = msg.ratios; return {}; },
};
self.onmessage = async (ev) => {
  const msg = ev.data, op = ops[msg.op];
  try {
    if (!op) throw new Error('Unknown engine request: ' + msg.op);
    const result = await op(msg, (p) => self.postMessage({ id: msg.id, progress: p }));
    self.postMessage({ id: msg.id, ok: true, result });
  } catch (e) { self.postMessage({ id: msg.id, ok: false, error: e && e.message ? e.message : String(e) }); }
};
