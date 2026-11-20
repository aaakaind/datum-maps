// DATUM · offline areas.
// Downloads the vector tiles for any part of the world into IndexedDB, serves
// them back to MapLibre through the `datum://` protocol (stored first, network
// second), and asks the engine worker to build a road graph and search index
// from them so directions and search work with no connection.
(function (root) {
'use strict';
const D = root.DATUM = root.DATUM || {}, idb = D.idb;

// ------------------------------------------------------------------ levels and budgets
// streets: every road and path at zoom 14 → walk, bike and drive routing, place and street search.
// roads:   drivable roads at zoom 12 (one-ways and ramps included) → driving routes across a region.
// map:     a lower-zoom map only.
const LEVELS = {
  streets: { z: 14, max: 2500, label: 'Streets', blurb: 'Full map, search, and walking, cycling and driving directions' },
  roads: { z: 12, max: 6000, label: 'Roads', blurb: 'Map to town level and driving directions on every through road' },
  map: { z: 10, max: 6000, label: 'Map', blurb: 'Map only' },
};
const EST_KB = [50, 120, 160, 180, 170, 150, 110, 80, 60, 45, 35, 25, 15, 25, 60]; // typical stored size per tile by zoom
const FONTS = ['Noto Sans Regular', 'Noto Sans Bold', 'Noto Sans Italic'];

const O = { areas: [], keys: new Set(), template: null, ready: null, cfg: null, onchange: () => {}, active: null };

// ------------------------------------------------------------------ tile maths
const lon2x = (lon, z) => (lon + 180) / 360 * Math.pow(2, z);
const lat2y = (lat, z) => { const r = Math.max(-85.0511, Math.min(85.0511, lat)) * Math.PI / 180; return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * Math.pow(2, z); };
const x2lon = (x, z) => x / Math.pow(2, z) * 360 - 180;
const y2lat = (y, z) => Math.atan(Math.sinh(Math.PI * (1 - 2 * y / Math.pow(2, z)))) * 180 / Math.PI;

function segHitsRect(x0, y0, x1, y1, rx0, ry0, rx1, ry1) {
  if (Math.max(x0, x1) < rx0 || Math.min(x0, x1) > rx1 || Math.max(y0, y1) < ry0 || Math.min(y0, y1) > ry1) return false;
  if ((x0 >= rx0 && x0 <= rx1 && y0 >= ry0 && y0 <= ry1) || (x1 >= rx0 && x1 <= rx1 && y1 >= ry0 && y1 <= ry1)) return true;
  const dx = x1 - x0, dy = y1 - y0; let t0 = 0, t1 = 1;
  const p = [-dx, dx, -dy, dy], q = [x0 - rx0, rx1 - x0, y0 - ry0, ry1 - y0];
  for (let k = 0; k < 4; k++) { if (p[k] === 0) { if (q[k] < 0) return false; } else { const r = q[k] / p[k]; if (p[k] < 0) { if (r > t1) return false; if (r > t0) t0 = r; } else { if (r < t0) return false; if (r < t1) t1 = r; } } }
  return true;
}
function inRing(px, py, ring) { let c = false; for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) { const a = ring[i], b = ring[j]; if (((a[1] > py) !== (b[1] > py)) && (px < (b[0] - a[0]) * (py - a[1]) / (b[1] - a[1]) + a[0])) c = !c; } return c; }

/** Tiles at zoom z covering a target ({bbox} or {rings: [[lon,lat]…]}). → Int32Array of x,y pairs */
function enumerate(target, z) {
  const n = Math.pow(2, z), out = [], seen = new Set();
  const add = (x, y) => { const k = x * 65536 + y; if (!seen.has(k)) { seen.add(k); out.push(x, y); } };
  if (target.rings) {
    for (const ring of target.rings) {
      const tr = ring.map(([lon, lat]) => [lon2x(lon, z), lat2y(lat, z)]);
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (const [x, y] of tr) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
      for (let x = Math.max(0, Math.floor(x0)); x <= Math.min(n - 1, Math.floor(x1)); x++) for (let y = Math.max(0, Math.floor(y0)); y <= Math.min(n - 1, Math.floor(y1)); y++) {
        let hit = inRing(x + 0.5, y + 0.5, tr);
        for (let i = 0, j = tr.length - 1; !hit && i < tr.length; j = i++) hit = segHitsRect(tr[j][0], tr[j][1], tr[i][0], tr[i][1], x, y, x + 1, y + 1);
        if (hit) add(x, y);
      }
    }
  } else {
    const [w, s, e, nn] = target.bbox;
    const x0 = Math.max(0, Math.floor(lon2x(w, z))), x1 = Math.min(n - 1, Math.floor(lon2x(e, z) - 1e-9)), y0 = Math.max(0, Math.floor(lat2y(nn, z))), y1 = Math.min(n - 1, Math.floor(lat2y(s, z) - 1e-9));
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) add(x, y);
  }
  return Int32Array.from(out);
}
/** Approximate tile count at zoom z without listing them. */
function countAt(target, z) {
  let zc = z;
  const span = (zz) => { const b = targetBBox(target); return (Math.floor(lon2x(b[2], zz)) - Math.floor(lon2x(b[0], zz)) + 1) * (Math.floor(lat2y(b[1], zz)) - Math.floor(lat2y(b[3], zz)) + 1); };
  while (zc > 0 && span(zc) > 20000) zc--;
  const c = enumerate(target, zc).length / 2;
  return zc === z ? c : Math.round(c * Math.pow(4, z - zc) * (target.rings ? 0.8 : 1));
}
function targetBBox(t) {
  if (t.bbox) return t.bbox;
  let w = 180, s = 90, e = -180, n = -90; for (const r of t.rings) for (const [x, y] of r) { if (x < w) w = x; if (x > e) e = x; if (y < s) s = y; if (y > n) n = y; }
  return [w, s, e, n];
}
/** The box covered by a set of tiles at one zoom: what the area can actually serve. */
function tileBBox(xy, z) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (let i = 0; i < xy.length; i += 2) { if (xy[i] < x0) x0 = xy[i]; if (xy[i] > x1) x1 = xy[i]; if (xy[i + 1] < y0) y0 = xy[i + 1]; if (xy[i + 1] > y1) y1 = xy[i + 1]; }
  return [x2lon(x0, z), y2lat(y1 + 1, z), x2lon(x1 + 1, z), y2lat(y0, z)].map(v => +v.toFixed(5));
}
/** All tiles to store for an area: its top-zoom tiles and every ancestor. → [[z,x,y]…] */
function pyramid(xy, zmax) {
  const out = [], seen = new Set();
  for (let i = 0; i < xy.length; i += 2) {
    let x = xy[i], y = xy[i + 1];
    for (let z = zmax; z >= 0; z--) { const k = z + '/' + x + '/' + y; if (seen.has(k)) break; seen.add(k); out.push([z, x, y]); x >>= 1; y >>= 1; }
  }
  out.sort((a, b) => a[0] - b[0]);
  return out;
}
function estimateBytes(xy, zmax) {
  let n = xy.length / 2, bytes = 0;
  for (let z = zmax; z >= 0 && n >= 1; z--) { bytes += n * EST_KB[z] * 1024; n = Math.max(1, n / 4); if (n === 1 && z > 0) { bytes += z * 60 * 1024; break; } }
  return bytes;
}

/**
 * plan(target, wantLevel?) → the levels this target can be downloaded at, and the chosen one.
 * target: { name, bbox } or { name, rings }.
 */
function plan(target, wantLevel) {
  const options = [];
  for (const key of ['streets', 'roads']) { const L = LEVELS[key], c = countAt(target, L.z); if (c <= L.max) options.push({ level: key, z: L.z, count: c }); }
  if (!options.length) { // too large to route: offer the deepest map-only zoom that fits
    let mz = LEVELS.map.z, c = countAt(target, mz); while (mz > 2 && c > LEVELS.map.max) c = countAt(target, --mz);
    options.push({ level: 'map', z: mz, count: c });
  }
  if (target.z != null) { options.length = 0; options.push({ level: 'map', z: target.z, count: countAt(target, target.z) }); } // fixed-zoom world layers
  const chosen = options.find(o => o.level === wantLevel) || options[0];
  const xy = enumerate(target, chosen.z);
  return { target, options, level: chosen.level, z: chosen.z, xy, count: xy.length / 2, total: pyramid(xy, chosen.z).length, bytes: estimateBytes(xy, chosen.z) };
}

// ------------------------------------------------------------------ engine worker RPC
let worker = null, seq = 0; const pending = new Map();
function engine(op, payload, onProgress, transfer) {
  if (!worker) {
    worker = new Worker('js/worker.js');
    worker.onmessage = (ev) => { const m = ev.data, p = pending.get(m.id); if (!p) return; if (m.progress) { p.onProgress && p.onProgress(m.progress); return; } pending.delete(m.id); if (m.ok) p.resolve(m.result); else p.reject(new Error(m.error)); };
    worker.onerror = (e) => { for (const p of pending.values()) p.reject(new Error('The routing engine stopped: ' + (e.message || 'worker error'))); pending.clear(); worker = null; };
  }
  return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject, onProgress }); worker.postMessage(Object.assign({ id, op }, payload), transfer || []); });
}

// ------------------------------------------------------------------ boot
function init(cfg) {
  O.cfg = cfg;
  try { O.template = localStorage.getItem('datum-tile-template'); } catch {}
  // the tile server publishes a versioned URL template; remember the last one for offline starts
  O.templateP = fetch(cfg.tiles).then(r => { if (!r.ok) throw new Error('TileJSON ' + r.status); return r.json(); })
    .then(j => { O.template = j.tiles[0]; try { localStorage.setItem('datum-tile-template', O.template); } catch {} return O.template; })
    .catch(() => O.template);
  O.ready = (async () => {
    try {
      O.areas = (await idb.all('areas')).sort((a, b) => b.created - a.created);
      for (const k of await idb.keys('tiles')) O.keys.add(k);
      for (const a of O.areas) if (a.status === 'ready' && a.level !== 'map') engine('load', { area: slim(a) }).then(r => { a.loaded = r.loaded; O.onchange(); }).catch(() => {});
    } catch (e) { O.error = e.message; }
    O.onchange();
  })();
  return O.ready;
}
const slim = (a) => ({ id: a.id, name: a.name, level: a.level, zmax: a.zmax, bbox: a.bbox });

// ------------------------------------------------------------------ MapLibre protocol: stored first, then network
async function protocol(params, abort) {
  const u = params.url, path = u.slice(u.indexOf('://') + 3), signal = abort && abort.signal;
  if (path.startsWith('tiles/')) {
    const key = path.slice(6);
    if (O.keys.has(key)) { const rec = await idb.get('tiles', key); if (rec) return { data: await idb.gunzip(rec) }; }
    const tpl = O.template || await O.templateP;
    if (!tpl) throw new Error('Offline and this part of the map is not downloaded.');
    const [z, x, y] = key.split('/');
    const res = await fetch(tpl.replace('{z}', z).replace('{x}', x).replace('{y}', y), { signal });
    if (!res.ok) throw new Error('Tile ' + key + ' returned ' + res.status);
    return { data: await res.arrayBuffer() };
  }
  if (path.startsWith('glyphs/')) {
    const key = decodeURIComponent(path.slice(7));
    try { const hit = await idb.get('glyphs', key); if (hit) return { data: hit }; } catch {}
    const [font, range] = key.split('/');
    const res = await fetch(O.cfg.glyphs.replace('{fontstack}', encodeURIComponent(font)).replace('{range}', range), { signal });
    if (!res.ok) throw new Error('Glyphs ' + key + ' returned ' + res.status);
    const data = await res.arrayBuffer();
    idb.put('glyphs', key, data).catch(() => {});
    return { data };
  }
  throw new Error('Unknown resource ' + u);
}

// ------------------------------------------------------------------ download
async function fetchTile(z, x, y, signal) {
  const tpl = O.template || await O.templateP;
  if (!tpl) throw new Error('The tile server is unreachable.');
  const url = tpl.replace('{z}', z).replace('{x}', x).replace('{y}', y);
  let lastErr;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const res = await fetch(url, { signal });
      if (res.status === 204 || res.status === 404) return new ArrayBuffer(0);
      if (!res.ok) throw new Error('Tile server returned ' + res.status);
      return await res.arrayBuffer();
    } catch (e) { if (e.name === 'AbortError') throw e; lastErr = e; await new Promise(r => setTimeout(r, 400 * Math.pow(2, attempt))); }
  }
  throw lastErr;
}
/**
 * download(planned, onProgress) → area meta. Resumable: tiles already stored are skipped.
 * Progress: { phase: 'tiles'|'build'|'fonts'|'done', done, total, bytes }
 */
async function download(p, onProgress, resume) {
  if (O.active) throw new Error('Another download is running.');
  const ctl = new AbortController(); O.active = { ctl, name: p.target.name };
  const id = resume ? resume.id : (p.target.name.toLowerCase().normalize('NFD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'area') + '-' + Date.now().toString(36);
  const meta = resume || { id, name: p.target.name, level: p.level, zmax: p.z, xy: p.xy, bbox: tileBBox(p.xy, p.z), created: Date.now(), bytes: 0, tiles: 0, status: 'partial', version: (O.template || '').split('/planet/')[1] ? (O.template.split('/planet/')[1] || '').split('/')[0] : '' };
  try {
    if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
    await idb.put('areas', meta.id, meta);
    if (!O.areas.includes(meta)) O.areas.unshift(meta);
    O.onchange();
    const all = pyramid(meta.xy, meta.zmax), todo = all.filter(t => !O.keys.has(t.join('/')));
    let done = all.length - todo.length, bytes = meta.bytes || 0, next = 0, batch = [], failed = 0;
    const flush = async () => { if (!batch.length) return; const b = batch; batch = []; await idb.putMany('tiles', b); for (const [k] of b) O.keys.add(k); };
    onProgress({ phase: 'tiles', done, total: all.length, bytes });
    const run = async () => {
      while (next < todo.length) {
        const [z, x, y] = todo[next++];
        let buf; try { buf = await fetchTile(z, x, y, ctl.signal); } catch (e) { if (e.name === 'AbortError') throw e; failed++; if (failed > Math.max(8, todo.length * 0.02)) throw new Error('Too many tiles failed to download (' + e.message + ').'); continue; }
        const rec = await idb.gzip(buf); bytes += rec.b.byteLength; batch.push([z + '/' + x + '/' + y, rec]);
        if (batch.length >= 12) await flush();
        done++; onProgress({ phase: 'tiles', done, total: all.length, bytes });
      }
    };
    await Promise.all(Array.from({ length: 6 }, run));
    await flush();
    meta.bytes = bytes; meta.tiles = all.length; meta.failed = failed;
    if (meta.level !== 'map') {
      const r = await engine('build', { area: Object.assign(slim(meta), { xy: meta.xy }) }, (pr) => onProgress(Object.assign({ bytes }, pr)));
      meta.stats = r.stats; meta.main = r.main; meta.loaded = true;
      // fonts for the scripts the area's names use, so labels render offline
      const jobs = []; for (const f of FONTS) for (const rg of r.ranges) { if (rg >= 0x30 && rg <= 0xD7) continue; jobs.push(f + '/' + (rg * 256) + '-' + (rg * 256 + 255)); }
      let fd = 0; onProgress({ phase: 'fonts', done: 0, total: jobs.length, bytes });
      const have = new Set(await idb.keys('glyphs'));
      const frun = async () => { while (jobs.length) { const key = jobs.pop(); if (!have.has(key)) { try { const [font, range] = key.split('/'); const res = await fetch(O.cfg.glyphs.replace('{fontstack}', encodeURIComponent(font)).replace('{range}', range), { signal: ctl.signal }); if (res.ok) await idb.put('glyphs', key, await res.arrayBuffer()); } catch (e) { if (e.name === 'AbortError') throw e; } } onProgress({ phase: 'fonts', done: ++fd, total: fd + jobs.length, bytes }); } };
      await Promise.all(Array.from({ length: 4 }, frun));
    }
    meta.status = 'ready'; meta.finished = Date.now();
    await idb.put('areas', meta.id, strip(meta));
    onProgress({ phase: 'done', done: all.length, total: all.length, bytes });
    return meta;
  } catch (e) {
    try { await idb.put('areas', meta.id, strip(meta)); } catch {}
    if (e.name === 'AbortError') { const err = new Error('Download paused.'); err.paused = true; throw err; }
    throw e;
  } finally { O.active = null; O.onchange(); }
}
const strip = (m) => { const c = Object.assign({}, m); delete c.loaded; return c; };
function cancel() { if (O.active) O.active.ctl.abort(); }

async function remove(id) {
  const area = O.areas.find(a => a.id === id); if (!area) return;
  const keep = new Set();
  for (const a of O.areas) if (a.id !== id) for (const t of pyramid(a.xy, a.zmax)) keep.add(t.join('/'));
  const gone = pyramid(area.xy, area.zmax).map(t => t.join('/')).filter(k => !keep.has(k));
  for (let i = 0; i < gone.length; i += 500) await idb.del('tiles', gone.slice(i, i + 500));
  for (const k of gone) O.keys.delete(k);
  await idb.del('data', [id + ':graph', id + ':search']); await idb.del('areas', id);
  O.areas = O.areas.filter(a => a.id !== id);
  engine('unload', { id }).catch(() => {});
  O.onchange();
}
async function usage() { try { const e = await navigator.storage.estimate(); return { used: e.usage || 0, quota: e.quota || 0, persisted: navigator.storage.persisted ? await navigator.storage.persisted() : false }; } catch { return null; } }

/** Is this point inside a ready, routable offline area? */
function covering(lon, lat, mode) {
  return O.areas.filter(a => a.status === 'ready' && a.level !== 'map' && (mode !== 'walk' && mode !== 'bike' || a.level === 'streets') && lon >= a.bbox[0] && lon <= a.bbox[2] && lat >= a.bbox[1] && lat <= a.bbox[3]);
}

// ------------------------------------------------------------------ world catalog
let catalogP = null;
function catalog() {
  if (!catalogP) catalogP = fetch('data/regions.json').then(r => { if (!r.ok) throw new Error('regions.json returned ' + r.status); return r.json(); }).then(j => {
    const q = j.q;
    j.countries = j.countries.map(([name, a3, cont, pop, enc], i) => ({ i, name, a3, cont, pop, enc, get rings() { return this._r || (this._r = this.enc.map(a => { const out = [[a[0] / q, a[1] / q]]; let x = a[0], y = a[1]; for (let k = 2; k < a.length; k += 2) { x += a[k]; y += a[k + 1]; out.push([x / q, y / q]); } return out; })); } }));
    j.regions = j.regions.map(([name, c, w, s, e, n]) => ({ name, c, bbox: [w, s, e, n] }));
    j.cities = j.cities.map(([name, c, lon, lat, pop]) => ({ name, c, lon, lat, pop }));
    return j;
  }).catch(e => { catalogP = null; throw e; });
  return catalogP;
}
/** A city's download box grows with its population: about 10 km out for 100,000 people, 18 km for a million. */
function cityBBox(c) {
  const r = Math.min(45, 3 + 0.16 * Math.pow(Math.max(c.pop, 1000), 0.33)), dy = r / 110.54, dx = r / (111.32 * Math.cos(c.lat * Math.PI / 180));
  return [+(c.lon - dx).toFixed(4), +(c.lat - dy).toFixed(4), +(c.lon + dx).toFixed(4), +(c.lat + dy).toFixed(4)];
}

D.offline = { O, LEVELS, init, protocol, plan, download, cancel, remove, usage, covering, engine, catalog, cityBBox, enumerate, pyramid, countAt, targetBBox };
})(typeof self !== 'undefined' ? self : globalThis);
