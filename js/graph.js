// DATUM · road graph builder.
// Turns OpenMapTiles vector tiles (the same tiles the map draws) into a routable
// graph and a search index, entirely on the device. Runs in a Web Worker and in
// Node (tests). Also converts the Overture region-pack format to the same graph.
//
// Tiles carry merged, simplified, clipped lines, so the builder has to:
//   1. clip every line to its tile and re-node it: shared vertices, T-junctions
//      whose vertex was simplified away, and X-crossings on the same layer;
//   2. stitch the pieces across tile borders;
//   3. attach street names from the separate transportation_name layer;
//   4. contract degree-2 nodes so the graph stays small.
(function (root) {
'use strict';
const mvt = (typeof module !== 'undefined' && module.exports) ? require('./mvt.js') : root.DATUM.mvt;

const WORLD = 1073741824; // 2^30 world units across the Web Mercator square
const E = 4096;
const CLS = ['motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'minor', 'service', 'track', 'path', 'footway', 'cycleway', 'pedestrian', 'steps', 'ferry', 'living'];
const C = {}; CLS.forEach((n, i) => { C[n] = i; });
// edge flags
const F = { WALK_F: 1, WALK_B: 2, BIKE_F: 4, BIKE_B: 8, CAR_F: 16, CAR_B: 32, LINK: 64, ROUNDABOUT: 128, TUNNEL: 256, BRIDGE: 512, TOLL: 1024, FERRY: 2048, UNPAVED: 4096, SIDEPATH: 8192, NAME_INFERRED: 16384, CROSSING: 32768 };
const F2 = { URBAN: 1, PRIVATE: 2, EXPRESSWAY: 4 };
const KMH = [105, 85, 65, 55, 45, 35, 15, 12, 0, 0, 0, 0, 0, 20, 15];

function wx2lon(wx) { return wx / WORLD * 360 - 180; }
function wy2lat(wy) { return Math.atan(Math.sinh(Math.PI * (1 - 2 * wy / WORLD))) * 180 / Math.PI; }
function lon2wx(lon) { return (lon + 180) / 360 * WORLD; }
function lat2wy(lat) { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * WORLD; }

// ------------------------------------------------------------------ classify one tile feature
function classify(p, drivableOnly) {
  let cls = p.class; if (!cls) return null;
  if (cls.endsWith('_construction')) return null;
  if (p.indoor === 1) return null;
  let c, sub = p.subclass;
  switch (cls) {
    case 'motorway': case 'trunk': case 'primary': case 'secondary': case 'tertiary': case 'minor': case 'service': case 'track': c = C[cls]; break;
    case 'ferry': c = C.ferry; break;
    case 'pier': c = C.footway; break;
    case 'path':
      if (sub === 'platform' || sub === 'corridor') return null;
      c = sub === 'footway' ? C.footway : sub === 'cycleway' ? C.cycleway : sub === 'pedestrian' ? C.pedestrian : sub === 'steps' ? C.steps : C.path; break;
    default: return null; // rail, transit, busway, raceway, bridge polygons…
  }
  if (drivableOnly && (c > C.service && c !== C.ferry)) return null;
  const noAccess = p.access === 'no', foot = p.foot, bic = p.bicycle, exp = p.expressway === 1;
  let walk = true, bike = true, car = c <= C.track;
  if (c === C.motorway) { walk = false; bike = false; }
  if (c === C.trunk && exp) { walk = false; bike = false; }
  if (foot === 'no') walk = false;
  if (bic === 'no') bike = false;
  if (c === C.steps) { bike = false; }
  if (c === C.ferry) { car = true; walk = foot !== 'no'; bike = bic !== 'no'; }
  if (noAccess) { car = false; if (!(foot === 'yes' || foot === 'designated' || foot === 'permissive')) walk = false; if (!(bic === 'yes' || bic === 'designated' || bic === 'permissive')) bike = false; }
  if (c === C.path || c === C.footway || c === C.cycleway || c === C.pedestrian || c === C.steps) car = false;
  if (c === C.cycleway && foot === undefined && false) walk = false;
  if (!walk && !bike && !car) return null;
  const ow = p.oneway === 1 ? 1 : p.oneway === -1 ? -1 : 0;
  let f = 0;
  if (walk) f |= F.WALK_F | F.WALK_B;
  if (bike) { if (ow >= 0) f |= F.BIKE_F; if (ow <= 0) f |= F.BIKE_B; }
  if (car) { if (ow >= 0) f |= F.CAR_F; if (ow <= 0) f |= F.CAR_B; }
  if (p.ramp === 1) f |= F.LINK;
  if (p.brunnel === 'tunnel') f |= F.TUNNEL; else if (p.brunnel === 'bridge') f |= F.BRIDGE;
  if (p.toll === 1) f |= F.TOLL;
  if (c === C.ferry) f |= F.FERRY;
  if (p.surface === 'unpaved') f |= F.UNPAVED;
  if (foot === 'use_sidepath') f |= F.SIDEPATH;
  let f2 = 0, kmh = KMH[c];
  if (exp) f2 |= F2.EXPRESSWAY;
  if (c === C.service && (p.service === 'driveway' || p.service === 'parking_aisle')) { f2 |= F2.PRIVATE; kmh = 10; }
  if (c === C.service && p.service === 'alley') kmh = 12;
  if (f & F.LINK) kmh = c <= C.trunk ? 50 : c <= C.secondary ? 40 : 30;
  if (c === C.trunk && exp) kmh = 95;
  if ((f & F.UNPAVED) && c <= C.service) kmh = Math.min(kmh, 40);
  return { c, f, f2, kmh, layer: p.layer | 0, brunnel: p.brunnel === 'bridge' ? 1 : p.brunnel === 'tunnel' ? 2 : 0 };
}

// ------------------------------------------------------------------ clip a polyline (flat ints) to [0,E]²
function clipLine(g, out) {
  let cur = null;
  const n = g.length;
  for (let i = 0; i + 3 < n; i += 2) {
    let x0 = g[i], y0 = g[i + 1], x1 = g[i + 2], y1 = g[i + 3];
    const dx = x1 - x0, dy = y1 - y0; let t0 = 0, t1 = 1, ok = true;
    // Liang–Barsky
    const p = [-dx, dx, -dy, dy], q = [x0, E - x0, y0, E - y0];
    for (let k = 0; k < 4; k++) {
      if (p[k] === 0) { if (q[k] < 0) { ok = false; break; } }
      else { const r = q[k] / p[k]; if (p[k] < 0) { if (r > t1) { ok = false; break; } if (r > t0) t0 = r; } else { if (r < t0) { ok = false; break; } if (r < t1) t1 = r; } }
    }
    if (!ok) { cur = null; continue; }
    const ax = t0 > 0 ? Math.round(x0 + t0 * dx) : x0, ay = t0 > 0 ? Math.round(y0 + t0 * dy) : y0;
    const bx = t1 < 1 ? Math.round(x0 + t1 * dx) : x1, by = t1 < 1 ? Math.round(y0 + t1 * dy) : y1;
    if (!cur || t0 > 0) { cur = [ax, ay]; out.push(cur); }
    if (bx !== cur[cur.length - 2] || by !== cur[cur.length - 1]) cur.push(bx, by);
    if (t1 < 1) cur = null;
  }
}

// ------------------------------------------------------------------ builder
function GraphBuilder(opts) {
  opts = opts || {};
  this.z = opts.z || 14;
  this.drivableOnly = !!opts.drivableOnly;
  this.scale = WORLD / (Math.pow(2, this.z) * E); // world units per tile unit
  this.nodeId = new Map();     // key(tx,ty) → id
  this.ntx = []; this.nty = []; // node coords in tile-global units
  this.edges = [];             // {a,b,c,f,f2,kmh,layer,name,ref,pts(Int32 flat tile-global interior), tile}
  this.names = []; this.nameId = new Map();
  this.search = [];            // [name, alt, kind, cat, lon, lat, rank]
  this.seenSearch = new Set();
  this.ranges = new Set();
  this.tiles = 0; this.bbox = [Infinity, Infinity, -Infinity, -Infinity];
  this.collectSearch = opts.search !== false;
}
GraphBuilder.prototype._node = function (tx, ty) {
  const key = tx * 67108864 + ty; let id = this.nodeId.get(key);
  if (id === undefined) { id = this.ntx.length; this.nodeId.set(key, id); this.ntx.push(tx); this.nty.push(ty); }
  return id;
};
GraphBuilder.prototype._name = function (s) {
  let id = this.nameId.get(s); if (id === undefined) { id = this.names.length; this.names.push(s); this.nameId.set(s, id); } return id;
};
GraphBuilder.prototype._chars = function (s) { for (let i = 0; i < s.length; i++) this.ranges.add(s.charCodeAt(i) >> 8); };

const WANT = new Set(['transportation', 'transportation_name', 'poi', 'place', 'park', 'water_name', 'aerodrome_label', 'mountain_peak']);
const TOL = 1.5, CELL = 128, NC = E / CELL;

GraphBuilder.prototype.addTile = function (x, y, buf) {
  const layers = mvt.decode(buf, WANT), tr = layers.transportation;
  this.tiles++;
  const ox = x * E, oy = y * E, S = this.scale, self = this, tileIdx = this.tiles;
  // tile bbox → area bbox
  const w = wx2lon(ox * S), e = wx2lon((ox + E) * S), n = wy2lat(oy * S), s = wy2lat((oy + E) * S);
  if (w < this.bbox[0]) this.bbox[0] = w; if (s < this.bbox[1]) this.bbox[1] = s; if (e > this.bbox[2]) this.bbox[2] = e; if (n > this.bbox[3]) this.bbox[3] = n;
  if (this.collectSearch) this._search(layers, ox, oy);
  if (!tr) return;

  // ---- 1. classify + clip
  const lines = []; // {p: number[] flat, a: attr}
  for (const ft of tr.features) {
    if (ft.type !== 2) continue;
    const a = classify(ft.props, this.drivableOnly); if (!a) continue;
    for (const g of ft.geom) { const pieces = []; clipLine(g, pieces); for (const p of pieces) if (p.length >= 4) lines.push({ p, a, splits: null }); }
  }
  if (!lines.length) return;

  // ---- 2. node detection
  const key = (px, py) => px * 8192 + py;
  const vcount = new Map();   // key → first layer, or -100 once it is a node
  const isNode = new Set();
  const alias = new Map();    // key → key (T-junction endpoints snapped onto an existing vertex)
  for (const L of lines) {
    const p = L.p, last = p.length - 2;
    for (let i = 0; i <= last; i += 2) {
      const k = key(p[i], p[i + 1]);
      if (i === 0 || i === last) { isNode.add(k); continue; }
      const prev = vcount.get(k);
      if (prev === undefined) vcount.set(k, L.a.layer);
      else if (prev === L.a.layer) isNode.add(k);
    }
  }
  // interior vertices that coincide with an endpoint are nodes via isNode already.
  // segment grid
  const grid = new Array(NC * NC);
  const segL = [], segI = []; // line index, point offset
  for (let li = 0; li < lines.length; li++) {
    const p = lines[li].p;
    for (let i = 0; i + 3 < p.length; i += 2) {
      const sid = segL.length; segL.push(li); segI.push(i);
      const cx0 = Math.max(0, Math.min(NC - 1, Math.floor(Math.min(p[i], p[i + 2]) / CELL))), cx1 = Math.max(0, Math.min(NC - 1, Math.floor(Math.max(p[i], p[i + 2]) / CELL)));
      const cy0 = Math.max(0, Math.min(NC - 1, Math.floor(Math.min(p[i + 1], p[i + 3]) / CELL))), cy1 = Math.max(0, Math.min(NC - 1, Math.floor(Math.max(p[i + 1], p[i + 3]) / CELL)));
      for (let cy = cy0; cy <= cy1; cy++) for (let cx = cx0; cx <= cx1; cx++) { const ci = cy * NC + cx; (grid[ci] || (grid[ci] = [])).push(sid); }
    }
  }
  const addSplit = (li, i, t, px, py) => { const L = lines[li]; (L.splits || (L.splits = [])).push(i, t, px, py); };
  // T-junctions: a line end that lies on another line's segment
  for (let li = 0; li < lines.length; li++) {
    const L = lines[li], p = L.p;
    for (const ei of [0, p.length - 2]) {
      const px = p[ei], py = p[ei + 1];
      const cx0 = Math.max(0, Math.floor((px - TOL) / CELL)), cx1 = Math.min(NC - 1, Math.floor((px + TOL) / CELL));
      const cy0 = Math.max(0, Math.floor((py - TOL) / CELL)), cy1 = Math.min(NC - 1, Math.floor((py + TOL) / CELL));
      let best = -1, bd = TOL * TOL + 1e-9, bt = 0;
      for (let cy = cy0; cy <= cy1; cy++) for (let cx = cx0; cx <= cx1; cx++) {
        const cell = grid[cy * NC + cx]; if (!cell) continue;
        for (const sid of cell) {
          const lj = segL[sid], i = segI[sid]; if (lj === li && Math.abs(i - ei) <= 2) continue;
          const q = lines[lj].p, x0 = q[i], y0 = q[i + 1], dx = q[i + 2] - x0, dy = q[i + 3] - y0, len2 = dx * dx + dy * dy; if (!len2) continue;
          let t = ((px - x0) * dx + (py - y0) * dy) / len2; t = t < 0 ? 0 : t > 1 ? 1 : t;
          const ex = x0 + t * dx - px, ey = y0 + t * dy - py, d = ex * ex + ey * ey;
          if (d < bd) { bd = d; best = sid; bt = t; }
        }
      }
      if (best < 0) continue;
      const lj = segL[best], i = segI[best], q = lines[lj].p, len = Math.hypot(q[i + 2] - q[i], q[i + 3] - q[i + 1]);
      const kp = key(px, py);
      if (bt * len <= TOL) { const kv = key(q[i], q[i + 1]); if (kv !== kp) { alias.set(kp, kv); } isNode.add(kv); }
      else if ((1 - bt) * len <= TOL) { const kv = key(q[i + 2], q[i + 3]); if (kv !== kp) { alias.set(kp, kv); } isNode.add(kv); }
      else if (lines[lj].a.layer === L.a.layer || bd === 0) addSplit(lj, i, bt, px, py);
    }
  }
  // X-crossings on the same layer where both lines lost their shared vertex
  for (let ci = 0; ci < grid.length; ci++) {
    const cell = grid[ci]; if (!cell || cell.length < 2) continue;
    const cxl = (ci % NC) * CELL, cyl = Math.floor(ci / NC) * CELL;
    for (let a = 0; a < cell.length; a++) {
      const sa = cell[a], la = segL[sa], ia = segI[sa], A = lines[la], pa = A.p;
      const ax = pa[ia], ay = pa[ia + 1], adx = pa[ia + 2] - ax, ady = pa[ia + 3] - ay;
      for (let b = a + 1; b < cell.length; b++) {
        const sb = cell[b], lb = segL[sb], ib = segI[sb];
        if (la === lb && Math.abs(ia - ib) <= 2) continue;
        const B = lines[lb]; if (A.a.layer !== B.a.layer || A.a.brunnel !== B.a.brunnel) continue;
        const pb = B.p, bx = pb[ib], by = pb[ib + 1], bdx = pb[ib + 2] - bx, bdy = pb[ib + 3] - by;
        const den = adx * bdy - ady * bdx; if (den === 0) continue;
        const t = ((bx - ax) * bdy - (by - ay) * bdx) / den, u = ((bx - ax) * ady - (by - ay) * adx) / den;
        if (t <= 0 || t >= 1 || u <= 0 || u >= 1) continue;
        const ix = ax + t * adx, iy = ay + t * ady;
        // handle each pair once: in the cell that owns the intersection point
        const ocx = Math.min(NC - 1, Math.floor(ix / CELL)) * CELL, ocy = Math.min(NC - 1, Math.floor(iy / CELL)) * CELL;
        if (ocx !== cxl || ocy !== cyl) continue;
        const rx = Math.round(ix), ry = Math.round(iy), k = key(rx, ry);
        isNode.add(k);
        const onA0 = rx === ax && ry === ay, onA1 = rx === pa[ia + 2] && ry === pa[ia + 3];
        const onB0 = rx === bx && ry === by, onB1 = rx === pb[ib + 2] && ry === pb[ib + 3];
        if (!onA0 && !onA1) addSplit(la, ia, t, rx, ry);
        if (!onB0 && !onB1) addSplit(lb, ib, u, rx, ry);
      }
    }
  }

  // ---- 3. names: grid of transportation_name segments
  const tn = layers.transportation_name, nseg = [], ngrid = new Array(NC * NC);
  if (tn) for (const ft of tn.features) {
    if (ft.type !== 2) continue;
    const pr = ft.props, nm = pr.name || '', ref = pr.ref || '';
    if (!nm && !ref) continue;
    const road = pr.class !== 'path' && pr.class !== 'ferry' && pr.class !== 'rail' && pr.class !== 'transit';
    if (pr.class === 'rail' || pr.class === 'transit') continue;
    const ni = nm ? this._name(nm) : -1, ri = ref ? this._name(ref) : -1;
    if (nm) this._chars(nm);
    for (const g of ft.geom) for (let i = 0; i + 3 < g.length; i += 2) {
      const sid = nseg.length / 7; nseg.push(g[i], g[i + 1], g[i + 2], g[i + 3], ni, ri, road ? 1 : 0);
      const cx0 = Math.max(0, Math.min(NC - 1, Math.floor(Math.min(g[i], g[i + 2]) / CELL))), cx1 = Math.max(0, Math.min(NC - 1, Math.floor(Math.max(g[i], g[i + 2]) / CELL)));
      const cy0 = Math.max(0, Math.min(NC - 1, Math.floor(Math.min(g[i + 1], g[i + 3]) / CELL))), cy1 = Math.max(0, Math.min(NC - 1, Math.floor(Math.max(g[i + 1], g[i + 3]) / CELL)));
      if (Math.max(g[i], g[i + 2]) < 0 || Math.min(g[i], g[i + 2]) > E || Math.max(g[i + 1], g[i + 3]) < 0 || Math.min(g[i + 1], g[i + 3]) > E) continue;
      for (let cy = cy0; cy <= cy1; cy++) for (let cx = cx0; cx <= cx1; cx++) { const ci = cy * NC + cx; (ngrid[ci] || (ngrid[ci] = [])).push(sid); }
    }
  }
  const SIDE = Math.max(6, Math.round(30 / (40075016.7 * Math.cos(((n + s) / 2) * Math.PI / 180) / (Math.pow(2, this.z) * E)))); // ≈30 m in tile units
  const nameFor = (mx, my, ux, uy, radius, roadOnly) => {
    const cx0 = Math.max(0, Math.floor((mx - radius) / CELL)), cx1 = Math.min(NC - 1, Math.floor((mx + radius) / CELL));
    const cy0 = Math.max(0, Math.floor((my - radius) / CELL)), cy1 = Math.min(NC - 1, Math.floor((my + radius) / CELL));
    let best = -1, bd = radius * radius;
    for (let cy = cy0; cy <= cy1; cy++) for (let cx = cx0; cx <= cx1; cx++) {
      const cell = ngrid[cy * NC + cx]; if (!cell) continue;
      for (const sid of cell) {
        const o = sid * 7; if (roadOnly && !nseg[o + 6]) continue;
        const x0 = nseg[o], y0 = nseg[o + 1], dx = nseg[o + 2] - x0, dy = nseg[o + 3] - y0, len2 = dx * dx + dy * dy; if (!len2) continue;
        let t = ((mx - x0) * dx + (my - y0) * dy) / len2; t = t < 0 ? 0 : t > 1 ? 1 : t;
        const ex = x0 + t * dx - mx, ey = y0 + t * dy - my, d = ex * ex + ey * ey;
        if (d > bd) continue;
        const cos = Math.abs(dx * ux + dy * uy) / Math.sqrt(len2);
        if (cos < (roadOnly ? 0.93 : 0.8)) continue;
        bd = d; best = o;
      }
    }
    return best;
  };

  // ---- 4. cut lines at nodes → edges
  const res = (k) => { const a = alias.get(k); return a === undefined ? k : a; };
  for (const L of lines) {
    let p = L.p;
    if (L.splits) { // insert split vertices
      const sp = L.splits, idx = []; for (let i = 0; i < sp.length; i += 4) idx.push(i);
      idx.sort((u, v) => (sp[u] - sp[v]) || (sp[u + 1] - sp[v + 1]));
      const np = []; let si = 0;
      for (let i = 0; i < p.length; i += 2) {
        np.push(p[i], p[i + 1]);
        while (si < idx.length && sp[idx[si]] === i) { const o = idx[si++]; const px = sp[o + 2], py = sp[o + 3]; if (px !== np[np.length - 2] || py !== np[np.length - 1]) { np.push(px, py); isNode.add(key(px, py)); } }
      }
      p = np;
    }
    let start = 0;
    for (let i = 2; i < p.length; i += 2) {
      const k = res(key(p[i], p[i + 1]));
      if (i !== p.length - 2 && !isNode.has(k)) continue;
      // edge from start..i
      const k0 = res(key(p[start], p[start + 1]));
      const ax = Math.floor(k0 / 8192), ay = k0 % 8192, bx = Math.floor(k / 8192), by = k % 8192;
      const a = this._node(ox + ax, oy + ay), b = this._node(ox + bx, oy + by);
      const cnt = (i - start) / 2 - 1;
      if (a === b && cnt < 2) { start = i; continue; }
      const pts = cnt > 0 ? new Int32Array(cnt * 2) : null;
      // midpoint along the piece, for name lookup
      let tot = 0; for (let j = start; j < i; j += 2) tot += Math.hypot(p[j + 2] - p[j], p[j + 3] - p[j + 1]);
      let acc = 0, mx = p[start], my = p[start + 1], ux = 1, uy = 0;
      for (let j = start; j < i; j += 2) {
        const sl = Math.hypot(p[j + 2] - p[j], p[j + 3] - p[j + 1]);
        if (acc + sl >= tot / 2 && sl > 0) { const t = (tot / 2 - acc) / sl; mx = p[j] + t * (p[j + 2] - p[j]); my = p[j + 1] + t * (p[j + 3] - p[j + 1]); ux = (p[j + 2] - p[j]) / sl; uy = (p[j + 3] - p[j + 1]) / sl; break; }
        acc += sl;
      }
      for (let j = start + 2, o = 0; j < i; j += 2) { pts[o++] = ox + p[j]; pts[o++] = oy + p[j + 1]; }
      let name = -1, ref = -1, f = L.a.f;
      if (nseg.length) {
        let o = nameFor(mx, my, ux, uy, 4, false);
        if (o >= 0) { name = nseg[o + 4]; ref = nseg[o + 5]; }
        else if (L.a.c === C.footway || L.a.c === C.path || L.a.c === C.cycleway) { o = nameFor(mx, my, ux, uy, SIDE, true); if (o >= 0 && nseg[o + 4] >= 0) { name = nseg[o + 4]; f |= F.NAME_INFERRED; } }
      }
      this.edges.push({ a, b, c: L.a.c, f, f2: L.a.f2, kmh: L.a.kmh, layer: L.a.layer, name, ref, pts, tile: tileIdx, dead: false });
      start = i;
    }
  }
};

// ------------------------------------------------------------------ search items from a tile
const PLACE_W = { city: 60, town: 44, village: 34, suburb: 30, quarter: 26, neighbourhood: 24, hamlet: 20, island: 26, isolated_dwelling: 8 };
GraphBuilder.prototype._search = function (layers, ox, oy) {
  const S = this.scale, self = this;
  const put = (name, alt, kind, cat, px, py, w) => {
    const lon = wx2lon((ox + px) * S), lat = wy2lat((oy + py) * S);
    const k = kind + '|' + name + '|' + Math.round(lon * 500) + '|' + Math.round(lat * 500);
    if (self.seenSearch.has(k)) return; self.seenSearch.add(k);
    self._chars(name);
    self.search.push([name, alt && alt !== name ? alt : '', kind, cat, +lon.toFixed(6), +lat.toFixed(6), w]);
  };
  const inside = (g) => g[0] >= 0 && g[0] < E && g[1] >= 0 && g[1] < E;
  const pts = (layer, fn) => { const L = layers[layer]; if (!L) return; for (const ft of L.features) { if (ft.type !== 1 || !ft.props.name) continue; for (const g of ft.geom) if (inside(g)) fn(ft.props, g); } };
  pts('poi', (p, g) => put(p.name, p['name:en'] || p['name:latin'], 'poi', p.subclass && p.subclass !== p.class ? p.class + '/' + p.subclass : p.class, g[0], g[1], Math.max(2, 14 - (p.rank || 30) / 3)));
  pts('place', (p, g) => put(p.name, p['name:en'] || p['name:latin'], 'place', p.class, g[0], g[1], PLACE_W[p.class] || 16));
  pts('aerodrome_label', (p, g) => put(p.name, p.iata || p['name:en'], 'poi', 'airport', g[0], g[1], 30));
  pts('mountain_peak', (p, g) => put(p.name, p['name:en'], 'nat', 'peak', g[0], g[1], 12));
  pts('water_name', (p, g) => put(p.name, p['name:en'], 'nat', p.class || 'water', g[0], g[1], 16));
  pts('park', (p, g) => put(p.name, p['name:en'], 'nat', 'park', g[0], g[1], 18));
  // streets: one entry per name per ~2.5 km cell, at the midpoint of its longest piece
  const tn = layers.transportation_name; if (!tn) return;
  const best = new Map();
  for (const ft of tn.features) {
    const p = ft.props; if (ft.type !== 2 || !p.name || p.class === 'rail' || p.class === 'transit') continue;
    for (const g of ft.geom) {
      let len = 0; for (let i = 0; i + 3 < g.length; i += 2) len += Math.hypot(g[i + 2] - g[i], g[i + 3] - g[i + 1]);
      const cur = best.get(p.name); if (cur && cur.len >= len) continue;
      let acc = 0, mx = g[0], my = g[1];
      for (let i = 0; i + 3 < g.length; i += 2) { const sl = Math.hypot(g[i + 2] - g[i], g[i + 3] - g[i + 1]); if (acc + sl >= len / 2 && sl > 0) { const t = (len / 2 - acc) / sl; mx = g[i] + t * (g[i + 2] - g[i]); my = g[i + 1] + t * (g[i + 3] - g[i + 1]); break; } acc += sl; }
      if (mx < 0 || mx >= E || my < 0 || my >= E) continue;
      best.set(p.name, { len, mx, my, alt: p['name:en'] || p['name:latin'], cls: p.class });
    }
  }
  for (const [name, b] of best) {
    const lon = wx2lon((ox + b.mx) * S), lat = wy2lat((oy + b.my) * S);
    const k = 'street|' + name + '|' + Math.round(lon * 40) + '|' + Math.round(lat * 40);
    if (this.seenSearch.has(k)) continue; this.seenSearch.add(k);
    this.search.push([name, b.alt && b.alt !== name ? b.alt : '', 'street', b.cls === 'path' ? 'path' : 'street', +lon.toFixed(6), +lat.toFixed(6), 18]);
  }
};

// ------------------------------------------------------------------ finish: stitch borders, contract, pack
GraphBuilder.prototype.finish = function () {
  const edges = this.edges, ntx = this.ntx, nty = this.nty, nN0 = ntx.length;
  // union-find over nodes for border stitching
  const parent = new Int32Array(nN0); for (let i = 0; i < nN0; i++) parent[i] = i;
  const find = (i) => { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; };
  // degree + single incident edge
  const deg = new Uint16Array(nN0), one = new Int32Array(nN0).fill(-1);
  for (let i = 0; i < edges.length; i++) { const e = edges[i]; deg[e.a]++; one[e.a] = i; deg[e.b]++; one[e.b] = i; }
  const vert = new Map(), horiz = new Map(); // border line coordinate → dangling node ids
  for (let i = 0; i < nN0; i++) {
    if (deg[i] !== 1) continue;
    if (ntx[i] % E === 0) { let a = vert.get(ntx[i]); if (!a) vert.set(ntx[i], a = []); a.push(i); }
    else if (nty[i] % E === 0) { let a = horiz.get(nty[i]); if (!a) horiz.set(nty[i], a = []); a.push(i); }
  }
  const TOLB = 6; let stitched = 0;
  const stitch = (groups, along) => {
    for (const list of groups.values()) {
      if (list.length < 2) continue;
      list.sort((u, v) => along[u] - along[v]);
      const pairs = [];
      for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length && along[list[j]] - along[list[i]] <= TOLB; j++) {
        const eu = edges[one[list[i]]], ev = edges[one[list[j]]];
        if (eu.tile === ev.tile || eu.c !== ev.c || eu.layer !== ev.layer) continue;
        pairs.push([along[list[j]] - along[list[i]], list[i], list[j]]);
      }
      pairs.sort((u, v) => u[0] - v[0]);
      const used = new Set();
      for (const [, u, v] of pairs) { if (used.has(u) || used.has(v)) continue; used.add(u); used.add(v); parent[find(v)] = find(u); stitched++; }
    }
  };
  stitch(vert, nty); stitch(horiz, ntx);
  for (const e of edges) { e.a = find(e.a); e.b = find(e.b); }

  // ---- contract degree-2 nodes
  const adj = new Array(nN0);
  for (let i = 0; i < edges.length; i++) { const e = edges[i]; (adj[e.a] || (adj[e.a] = [])).push(i); if (e.b !== e.a) (adj[e.b] || (adj[e.b] = [])).push(i); else adj[e.a].push(i); }
  const flip = (f) => (f & ~63) | ((f & 21) << 1) | ((f & 42) >> 1);
  const rev = (e) => { const t = e.a; e.a = e.b; e.b = t; e.f = flip(e.f); if (e.pts) { const p = e.pts, n = p.length; for (let i = 0, j = n - 2; i < j; i += 2, j -= 2) { const x = p[i], y = p[i + 1]; p[i] = p[j]; p[i + 1] = p[j + 1]; p[j] = x; p[j + 1] = y; } } };
  let contracted = 0;
  for (let v = 0; v < nN0; v++) {
    const a = adj[v]; if (!a || a.length !== 2 || a[0] === a[1]) continue;
    const e1 = edges[a[0]], e2 = edges[a[1]];
    if (e1.dead || e2.dead || e1.a === e1.b || e2.a === e2.b) continue;
    if (e1.c !== e2.c || e1.name !== e2.name || e1.ref !== e2.ref || e1.kmh !== e2.kmh || e1.f2 !== e2.f2) continue;
    if (e1.b !== v) rev(e1); if (e2.a !== v) rev(e2);
    if (e1.f !== e2.f) continue;
    if (e1.a === e2.b) continue; // would make a loop
    const n1 = e1.pts ? e1.pts.length : 0, n2 = e2.pts ? e2.pts.length : 0, pts = new Int32Array(n1 + 2 + n2);
    if (n1) pts.set(e1.pts, 0); pts[n1] = ntx[v]; pts[n1 + 1] = nty[v]; if (n2) pts.set(e2.pts, n1 + 2);
    e1.pts = pts; e1.b = e2.b; e2.dead = true; contracted++;
    const fb = adj[e2.b]; for (let i = 0; i < fb.length; i++) if (fb[i] === a[1]) { fb[i] = a[0]; break; }
    adj[v] = null;
  }

  // ---- ferries: the tiles do not say which ones carry cars. Keep driving only on ferry
  // lines that land on a drivable road at two or more places; passenger boats land on paths.
  {
    const fp = new Map(), ff = (n) => { let r = n; while (fp.get(r) !== r) r = fp.get(r); fp.set(n, r); return r; };
    const carNode = new Set();
    for (const e of edges) { if (e.dead) continue; if (e.c === C.ferry) { for (const n of [e.a, e.b]) if (!fp.has(n)) fp.set(n, n); fp.set(ff(e.a), ff(e.b)); } else if ((e.f & (F.CAR_F | F.CAR_B)) && e.c <= C.service) { carNode.add(e.a); carNode.add(e.b); } }
    const landings = new Map();
    for (const n of fp.keys()) if (carNode.has(n)) { const r = ff(n); landings.set(r, (landings.get(r) || 0) + 1); }
    for (const e of edges) if (!e.dead && e.c === C.ferry && (landings.get(ff(e.a)) || 0) < 2) e.f &= ~(F.CAR_F | F.CAR_B);
  }

  // ---- pack into typed arrays
  const live = edges.filter(e => !e.dead);
  const remap = new Int32Array(nN0).fill(-1); let nN = 0;
  for (const e of live) { if (remap[e.a] < 0) remap[e.a] = nN++; if (remap[e.b] < 0) remap[e.b] = nN++; }
  const S = this.scale, nE = live.length;
  const nx = new Int32Array(nN), ny = new Int32Array(nN);
  for (let i = 0; i < nN0; i++) if (remap[i] >= 0) { nx[remap[i]] = Math.round(ntx[i] * S); ny[remap[i]] = Math.round(nty[i] * S); }
  let gp = 0; for (const e of live) gp += e.pts ? e.pts.length : 0;
  const ea = new Uint32Array(nE), eb = new Uint32Array(nE), ecls = new Uint8Array(nE), eflag = new Uint16Array(nE), eflag2 = new Uint8Array(nE), ekmh = new Uint8Array(nE);
  const ename = new Int32Array(nE), eref = new Int32Array(nE), egeo = new Uint32Array(nE + 1), geo = new Int32Array(gp);
  let o = 0;
  for (let i = 0; i < nE; i++) {
    const e = live[i]; ea[i] = remap[e.a]; eb[i] = remap[e.b]; ecls[i] = e.c; eflag[i] = e.f; eflag2[i] = e.f2; ekmh[i] = e.kmh; ename[i] = e.name; eref[i] = e.ref; egeo[i] = o;
    if (e.pts) for (let j = 0; j < e.pts.length; j++) geo[o++] = Math.round(e.pts[j] * S);
  }
  egeo[nE] = o;
  const graph = { v: 1, nN, nE, nx, ny, ea, eb, ecls, eflag, eflag2, ekmh, ename, eref, egeo, geo, names: this.names, bbox: this.bbox.slice(), src: 'tiles', z: this.z };
  markUrban(graph);
  return { graph, search: this.search, ranges: Array.from(this.ranges).sort((a, b) => a - b), stats: { tiles: this.tiles, nodes: nN, edges: nE, stitched, contracted, names: this.names.length, search: this.search.length } };
};

// Urban flag from junction density (≈ per 600 m cell): used for speeds and typical traffic.
function markUrban(g) {
  const deg = new Uint8Array(g.nN);
  for (let i = 0; i < g.nE; i++) { if (g.ecls[i] > C.service) continue; if (deg[g.ea[i]] < 255) deg[g.ea[i]]++; if (deg[g.eb[i]] < 255) deg[g.eb[i]]++; }
  let lat = 0; if (g.nN) lat = wy2lat(g.ny[0]);
  const cell = Math.max(1, Math.round(600 / (40075016.7 * Math.cos(lat * Math.PI / 180) / WORLD)));
  const cnt = new Map();
  for (let i = 0; i < g.nN; i++) { if (deg[i] < 3) continue; const k = Math.floor(g.nx[i] / cell) * 65536 + Math.floor(g.ny[i] / cell); cnt.set(k, (cnt.get(k) || 0) + 1); }
  for (let i = 0; i < g.nE; i++) {
    const a = g.ea[i], k = Math.floor(g.nx[a] / cell) * 65536 + Math.floor(g.ny[a] / cell);
    if ((cnt.get(k) || 0) >= 10) g.eflag2[i] |= F2.URBAN;
  }
}

// ------------------------------------------------------------------ Overture region pack → same graph
function fromPack(R) {
  const nN = R.nodes.length / 2, E0 = R.edges, nE = E0.length, Q = R.q, O = R.origin;
  const map = R.classes.map(n => ({ motorway: 0, trunk: 1, primary: 2, secondary: 3, tertiary: 4, residential: 5, unclassified: 5, living_street: 14, service: 6, track: 7, path: 8, bridleway: 8, footway: 9, cycleway: 10, pedestrian: 11, steps: 12 })[n] ?? 5);
  const nx = new Int32Array(nN), ny = new Int32Array(nN);
  for (let i = 0; i < nN; i++) { nx[i] = Math.round(lon2wx(O[0] + R.nodes[2 * i] / Q)); ny[i] = Math.round(lat2wy(O[1] + R.nodes[2 * i + 1] / Q)); }
  let gp = 0; for (const e of E0) gp += e.length - 6;
  const ea = new Uint32Array(nE), eb = new Uint32Array(nE), ecls = new Uint8Array(nE), eflag = new Uint16Array(nE), eflag2 = new Uint8Array(nE), ekmh = new Uint8Array(nE);
  const ename = new Int32Array(nE), eref = new Int32Array(nE).fill(-1), egeo = new Uint32Array(nE + 1), geo = new Int32Array(gp);
  let o = 0;
  for (let i = 0; i < nE; i++) {
    const e = E0[i], f = e[4]; ea[i] = e[0]; eb[i] = e[1]; ecls[i] = map[e[2]]; ename[i] = e[3]; ekmh[i] = e[5] || KMH[ecls[i]] || 30; egeo[i] = o;
    let fl = 0;
    if (f & 16) fl |= F.WALK_F | F.WALK_B; if (f & 4) fl |= F.BIKE_F; if (f & 8) fl |= F.BIKE_B; if (f & 1) fl |= F.CAR_F; if (f & 2) fl |= F.CAR_B;
    if (f & 32) fl |= F.BRIDGE; if (f & 64) fl |= F.TUNNEL; if (f & 128) fl |= F.LINK; if (f & 256) fl |= F.NAME_INFERRED; if (f & 512) fl |= F.CROSSING;
    eflag[i] = fl;
    let x = R.nodes[2 * e[0]], y = R.nodes[2 * e[0] + 1];
    for (let j = 6; j < e.length; j += 2) { x += e[j]; y += e[j + 1]; geo[o++] = Math.round(lon2wx(O[0] + x / Q)); geo[o++] = Math.round(lat2wy(O[1] + y / Q)); }
  }
  egeo[nE] = o;
  const g = { v: 1, nN, nE, nx, ny, ea, eb, ecls, eflag, eflag2, ekmh, ename, eref, egeo, geo, names: R.names, bbox: R.bbox.slice(), src: 'pack', z: 14 };
  markUrban(g);
  return g;
}

const api = { GraphBuilder, fromPack, CLS, C, F, F2, KMH, WORLD, wx2lon, wy2lat, lon2wx, lat2wy, classify };
if (typeof module !== 'undefined' && module.exports) module.exports = api; else (root.DATUM = root.DATUM || {}).graph = api;
})(typeof self !== 'undefined' ? self : globalThis);
