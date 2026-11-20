// DATUM · on-device router.
// Edge-based A* (the search state is a directed road segment, so turn and
// junction costs are exact), partial-edge snapping at both ends, a typical
// traffic model, optional live speed ratios, avoid options, alternative
// routes by the penalty method, and turn-by-turn step generation.
(function (root) {
'use strict';
const GR = (typeof module !== 'undefined' && module.exports) ? require('./graph.js') : root.DATUM.graph;
const { C, F, F2, WORLD, wx2lon, wy2lat, lon2wx, lat2wy } = GR;
const RAD = Math.PI / 180, R_EARTH = 6371008.8;
const MODE_BIT = { walk: 1, bike: 4, car: 16 };

// ------------------------------------------------------------------ typical traffic (a model, not a feed)
// Share of the worst-hour slowdown by time of week, in 15-minute bins.
const PROFILE = (() => {
  const p = new Float32Array(7 * 96), bump = (h, c, s) => Math.exp(-0.5 * ((h - c) / s) ** 2);
  for (let d = 0; d < 7; d++) for (let q = 0; q < 96; q++) {
    const h = q / 4; let v;
    if (d >= 1 && d <= 5) v = Math.max(bump(h, 8, 0.9), (d === 5 ? 1.05 : 1) * bump(h, 17.2, 1.25), (h > 9.5 && h < 15.5) ? 0.34 : 0, 0.3 * bump(h, 12.5, 2.5));
    else v = (d === 6 ? 0.42 : 0.3) * bump(h, 14, 3);
    p[d * 96 + q] = Math.min(1, v);
  }
  return p;
})();
// Worst-hour extra travel time by road class, urban and rural.
const PEAK_URBAN = [0.6, 0.55, 0.5, 0.42, 0.3, 0.12, 0.05, 0, 0, 0, 0, 0, 0, 0, 0.08];
const PEAK_RURAL = [0.1, 0.08, 0.06, 0.04, 0.02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
/** Typical traffic factor (≥1) for a class at a local time. `local` = ms since epoch shifted to local solar time. */
function typicalFactor(cls, urban, localMs) {
  const d = new Date(localMs), bin = d.getUTCDay() * 96 + d.getUTCHours() * 4 + (d.getUTCMinutes() / 15 | 0);
  return 1 + (urban ? PEAK_URBAN[cls] : PEAK_RURAL[cls]) * PROFILE[bin];
}
function congestionLevel(localMs) { const d = new Date(localMs); return PROFILE[d.getUTCDay() * 96 + d.getUTCHours() * 4 + (d.getUTCMinutes() / 15 | 0)]; }

// free-flow car speeds (km/h) for graphs built from tiles, which carry no speed limits
const URBAN_KMH = [88, 60, 43, 39, 35, 26, 13, 12, 0, 0, 0, 0, 0, 18, 12];
const RURAL_KMH = [108, 88, 72, 64, 54, 40, 15, 14, 0, 0, 0, 0, 0, 20, 12];
const TUNE = { ramp: 5, yieldU: 9, yieldR: 6, signalU: 7, signalR: 3, minor: 1.5, throughU: 1.2, sharp: 6, turn: 3.5 };
const WALK_MULT = [9, 2.2, 1.45, 1.3, 1.15, 1.05, 1.05, 1.05, 1, 1, 1.05, 1, 1.15, 1, 1];
const BIKE_MS = [0, 4.6, 4.6, 4.8, 5.0, 5.0, 4.2, 3.4, 4.3, 1.7, 5.4, 1.7, 0, 4.2, 4.4];
const BIKE_MULT = [9, 2.6, 1.7, 1.4, 1.2, 1.04, 1.1, 1.15, 1.02, 1.25, 1, 1.25, 9, 1, 1];

function Router(g) {
  this.g = g;
  const nN = g.nN, nE = g.nE;
  // node positions
  const lat = new Float64Array(nN), lon = new Float64Array(nN), cosl = new Float64Array(nN);
  for (let i = 0; i < nN; i++) { lon[i] = wx2lon(g.nx[i]) * RAD; lat[i] = wy2lat(g.ny[i]) * RAD; cosl[i] = Math.cos(lat[i]); }
  this.lat = lat; this.lon = lon; this.cosl = cosl;
  // edge lengths in metres
  const len = new Float32Array(nE);
  for (let i = 0; i < nE; i++) {
    let px = g.nx[g.ea[i]], py = g.ny[g.ea[i]], L = 0;
    const seg = (x, y) => { const k = Math.cos(wy2lat((py + y) / 2) * RAD) * 40075016.686 / WORLD; L += Math.hypot(x - px, y - py) * k; px = x; py = y; };
    for (let o = g.egeo[i]; o < g.egeo[i + 1]; o += 2) seg(g.geo[o], g.geo[o + 1]);
    seg(g.nx[g.eb[i]], g.ny[g.eb[i]]); len[i] = L;
  }
  this.len = len;
  // CSR of outgoing arcs per node; arc = 2*edge + dir (0: a→b, 1: b→a)
  const off = new Uint32Array(nN + 1);
  for (let i = 0; i < nE; i++) { off[g.ea[i] + 1]++; off[g.eb[i] + 1]++; }
  for (let i = 0; i < nN; i++) off[i + 1] += off[i];
  const fill = off.slice(0, nN), arcs = new Uint32Array(off[nN]);
  for (let i = 0; i < nE; i++) { arcs[fill[g.ea[i]]++] = 2 * i; arcs[fill[g.eb[i]]++] = 2 * i + 1; }
  this.off = off; this.arcs = arcs;
  // car free-flow m/s per edge
  const fromPack = g.src === 'pack', car = new Float32Array(nE);
  let vmax = 8;
  for (let i = 0; i < nE; i++) {
    const c = g.ecls[i], f2 = g.eflag2[i], f = g.eflag[i]; let k;
    if (fromPack) k = Math.min(g.ekmh[i] || 30, 130) * (c <= C.trunk ? 0.97 : 0.86);
    else { k = (f2 & F2.URBAN) ? URBAN_KMH[c] : RURAL_KMH[c]; if ((f & F.LINK) || (f2 & F2.PRIVATE) || (f & F.UNPAVED)) k = Math.min(k, g.ekmh[i]); if (c === C.trunk && (f2 & F2.EXPRESSWAY)) k = Math.max(k, (f2 & F2.URBAN) ? 80 : 95); }
    car[i] = Math.max(1.5, k / 3.6); if ((f & (F.CAR_F | F.CAR_B)) && car[i] > vmax) vmax = car[i];
  }
  this.carMs = car; this.vmax = { walk: 1.36, bike: 5.4, car: vmax };
  // junction rank per node: number of drivable through-edges and their best class
  const jdeg = new Uint8Array(nN), jbest = new Uint8Array(nN).fill(99), jcnt = new Uint8Array(nN), jsec = new Uint8Array(nN).fill(99);
  for (let i = 0; i < nE; i++) {
    if (!(g.eflag[i] & (F.CAR_F | F.CAR_B)) || (g.eflag2[i] & F2.PRIVATE)) continue;
    const c = g.ecls[i];
    for (const v of [g.ea[i], g.eb[i]]) {
      if (jdeg[v] < 250) jdeg[v]++;
      if (c < jbest[v]) { jsec[v] = jbest[v]; jbest[v] = c; jcnt[v] = 1; } else if (c === jbest[v]) jcnt[v]++; else if (c < jsec[v]) jsec[v] = c;
    }
  }
  this.jcnt = jcnt; this.jsec = jsec;
  this.jdeg = jdeg; this.jbest = jbest;
  this.main = { walk: this._scc(1), bike: this._scc(4), car: this._scc(16) };
  this._grid();
  // per-query scratch, stamped so nothing is cleared between queries
  const nA = 2 * nE;
  this.gc = new Float64Array(nA); this.prev = new Int32Array(nA); this.stamp = new Uint32Array(nA); this.closed = new Uint32Array(nA); this.q = 0;
  this.hk = new Float64Array(1024); this.hv = new Uint32Array(1024); this.hn = 0;
  this.pen = null; this.live = null;
  this.lon0 = wx2lon(g.nN ? g.nx[0] : 0);
}

// Largest strongly connected component for a mode (iterative Tarjan over nodes).
Router.prototype._scc = function (fbit) {
  const g = this.g, n = g.nN, off = this.off, arcs = this.arcs;
  const index = new Int32Array(n).fill(-1), low = new Int32Array(n), on = new Uint8Array(n), stack = new Int32Array(n), comp = new Int32Array(n).fill(-1), cn = new Int32Array(n), ck = new Int32Array(n);
  let sp = 0, idx = 0, nComp = 0, best = -1, bestSize = 0;
  const sizes = [];
  for (let s = 0; s < n; s++) {
    if (index[s] !== -1) continue;
    let csp = 0; cn[0] = s; ck[0] = off[s]; index[s] = low[s] = idx++; stack[sp++] = s; on[s] = 1;
    while (csp >= 0) {
      const v = cn[csp]; let k = ck[csp], pushed = false;
      for (; k < off[v + 1]; k++) {
        const a = arcs[k], e = a >> 1, d = a & 1; if (!(g.eflag[e] & (fbit << d))) continue;
        const w = d ? g.ea[e] : g.eb[e];
        if (index[w] === -1) { ck[csp] = k + 1; csp++; cn[csp] = w; ck[csp] = off[w]; index[w] = low[w] = idx++; stack[sp++] = w; on[w] = 1; pushed = true; break; }
        else if (on[w] && index[w] < low[v]) low[v] = index[w];
      }
      if (pushed) continue;
      if (low[v] === index[v]) { let size = 0, w; do { w = stack[--sp]; on[w] = 0; comp[w] = nComp; size++; } while (w !== v); sizes.push(size); if (size > bestSize) { bestSize = size; best = nComp; } nComp++; }
      csp--; if (csp >= 0) { const u = cn[csp]; if (low[v] < low[u]) low[u] = low[v]; }
    }
  }
  const inMain = new Uint8Array(n); for (let i = 0; i < n; i++) if (comp[i] === best) inMain[i] = 1;
  inMain.size = bestSize;
  return inMain;
};

// Uniform grid over edge segments for snapping.
Router.prototype._grid = function () {
  const g = this.g; if (!g.nN) { this.cells = new Map(); this.cell = 1; return; }
  const latMid = (g.bbox[1] + g.bbox[3]) / 2;
  const cell = Math.max(64, Math.round(250 / (40075016.686 * Math.cos(latMid * RAD) / WORLD)));
  const cells = new Map();
  const add = (cx, cy, e) => { const k = cx * 4194304 + cy; let a = cells.get(k); if (!a) cells.set(k, a = []); if (a[a.length - 1] !== e) a.push(e); };
  for (let i = 0; i < g.nE; i++) {
    let px = g.nx[g.ea[i]], py = g.ny[g.ea[i]];
    const seg = (x, y) => {
      const cx0 = Math.floor(Math.min(px, x) / cell), cx1 = Math.floor(Math.max(px, x) / cell), cy0 = Math.floor(Math.min(py, y) / cell), cy1 = Math.floor(Math.max(py, y) / cell);
      if ((cx1 - cx0 + 1) * (cy1 - cy0 + 1) <= 64) for (let cx = cx0; cx <= cx1; cx++) for (let cy = cy0; cy <= cy1; cy++) add(cx, cy, i);
      else { const n = Math.ceil(Math.max(Math.abs(x - px), Math.abs(y - py)) / cell) * 2; for (let s = 0; s <= n; s++) add(Math.floor((px + (x - px) * s / n) / cell), Math.floor((py + (y - py) * s / n) / cell), i); }
      px = x; py = y;
    };
    for (let o = g.egeo[i]; o < g.egeo[i + 1]; o += 2) seg(g.geo[o], g.geo[o + 1]);
    seg(g.nx[g.eb[i]], g.ny[g.eb[i]]);
  }
  this.cells = cells; this.cell = cell;
};

Router.prototype.contains = function (lon, lat) { const b = this.g.bbox; return lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3]; };

/** Nearest usable edge. → { edge, t, lon, lat, dist } or null */
Router.prototype.snap = function (lon, lat, mode, maxM, opts) {
  const g = this.g, cell = this.cell, wx = lon2wx(lon), wy = lat2wy(lat), mpu = 40075016.686 * Math.cos(lat * RAD) / WORLD;
  const fb = MODE_BIT[mode] * 3, main = opts && opts.any ? null : this.main[mode];
  const cx = Math.floor(wx / cell), cy = Math.floor(wy / cell), maxRing = Math.ceil((maxM || 3000) / (cell * mpu)) + 1;
  let best = null, bd = Infinity;
  for (let r = 0; r <= maxRing; r++) {
    if (best && bd <= ((r - 1) * cell * mpu)) break;
    for (let dx = -r; dx <= r; dx++) for (let dy = -r; dy <= r; dy++) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
      const list = this.cells.get((cx + dx) * 4194304 + (cy + dy)); if (!list) continue;
      for (const e of list) {
        const f = g.eflag[e]; if (!(f & fb)) continue;
        if (main && !(main[g.ea[e]] && main[g.eb[e]])) continue;
        if (opts && opts.named && g.ename[e] < 0) continue;
        // prefer ordinary streets over motorways, ramps and tunnels when choosing where a trip starts or ends
        let w = 1; const c = g.ecls[e];
        if (mode === 'car' && !(opts && opts.raw)) { if (c === C.motorway || (f & F.LINK) || (f & F.TUNNEL)) w = 3; else if (c === C.trunk) w = 1.6; else if (g.eflag2[e] & F2.PRIVATE) w = 1.4; }
        else if (mode === 'walk' && (f & F.TUNNEL)) w = 2;
        let px = g.nx[g.ea[e]], py = g.ny[g.ea[e]], acc = 0;
        const tot = this.len[e] / mpu || 1;
        const seg = (x, y) => {
          const sx = x - px, sy = y - py, l2 = sx * sx + sy * sy, sl = Math.sqrt(l2);
          let t = l2 ? ((wx - px) * sx + (wy - py) * sy) / l2 : 0; t = t < 0 ? 0 : t > 1 ? 1 : t;
          const qx = px + t * sx, qy = py + t * sy, d = Math.hypot(qx - wx, qy - wy) * mpu * w;
          if (d < bd) { bd = d; best = { edge: e, acc: acc + t * sl, qx, qy, dist: d / w }; }
          acc += sl; px = x; py = y;
        };
        for (let o = g.egeo[e]; o < g.egeo[e + 1]; o += 2) seg(g.geo[o], g.geo[o + 1]);
        seg(g.nx[g.eb[e]], g.ny[g.eb[e]]);
        if (best && best.edge === e && best.tot === undefined) best.tot = acc;
        else if (best && best.edge === e) best.tot = acc;
      }
    }
  }
  if (!best || best.dist > (maxM || 3000)) return null;
  const t = best.tot ? Math.min(1, Math.max(0, best.acc / best.tot)) : 0;
  return { edge: best.edge, t, lon: wx2lon(best.qx), lat: wy2lat(best.qy), dist: best.dist };
};

Router.prototype.nearestName = function (lon, lat, maxM) {
  const s = this.snap(lon, lat, 'walk', maxM || 250, { any: true, named: true }) || this.snap(lon, lat, 'car', maxM || 250, { any: true, named: true });
  return s ? { name: this.g.names[this.g.ename[s.edge]], dist: s.dist } : null;
};

// ---- heap
Router.prototype._push = function (key, v) {
  if (this.hn === this.hk.length) { const k = new Float64Array(this.hn * 2), w = new Uint32Array(this.hn * 2); k.set(this.hk); w.set(this.hv); this.hk = k; this.hv = w; }
  const hk = this.hk, hv = this.hv; let i = this.hn++;
  while (i > 0) { const p = (i - 1) >> 1; if (hk[p] <= key) break; hk[i] = hk[p]; hv[i] = hv[p]; i = p; }
  hk[i] = key; hv[i] = v;
};
Router.prototype._pop = function () {
  const hk = this.hk, hv = this.hv, top = hv[0], n = --this.hn;
  if (n > 0) { const key = hk[n], v = hv[n]; let i = 0;
    for (;;) { let l = 2 * i + 1; if (l >= n) break; if (l + 1 < n && hk[l + 1] < hk[l]) l++; if (hk[l] >= key) break; hk[i] = hk[l]; hv[i] = hv[l]; i = l; }
    hk[i] = key; hv[i] = v; }
  return top;
};

// bearing (degrees) of an arc leaving (`out`) or arriving at (`!out`) its node, measured ~12 m along the geometry
Router.prototype._bearing = function (arc, out) {
  const g = this.g, e = arc >> 1, d = arc & 1, o0 = g.egeo[e], o1 = g.egeo[e + 1];
  // points in travel order
  const n = (o1 - o0) / 2 + 2;
  const pt = (i) => { const j = d ? n - 1 - i : i; if (j === 0) return [g.nx[g.ea[e]], g.ny[g.ea[e]]]; if (j === n - 1) return [g.nx[g.eb[e]], g.ny[g.eb[e]]]; return [g.geo[o0 + 2 * (j - 1)], g.geo[o0 + 2 * (j - 1) + 1]]; };
  const mpu = 40075016.686 * this.cosl[g.ea[e]] / WORLD, need = 12 / mpu;
  let a, b;
  if (out) { a = pt(0); let i = 1; b = pt(1); while (i < n - 1 && Math.hypot(b[0] - a[0], b[1] - a[1]) < need) b = pt(++i); }
  else { b = pt(n - 1); let i = n - 2; a = pt(i); while (i > 0 && Math.hypot(b[0] - a[0], b[1] - a[1]) < need) a = pt(--i); }
  return (Math.atan2(b[0] - a[0], -(b[1] - a[1])) / RAD + 360) % 360;
};

/** Profile: per-metre cost and time for an edge, junction cost between arcs. */
Router.prototype._profile = function (mode, o) {
  const g = this.g, self = this, avoid = o.avoid || {}, carMs = this.carMs, len = this.len;
  const traffic = mode === 'car' && o.traffic !== 'none' && o.depart != null;
  // local solar time keeps the model honest for areas far from the device's time zone
  const localBase = traffic ? o.depart + (this.lon0 / 15) * 3600000 : 0;
  const live = mode === 'car' ? this.live : null, pen = this.pen;
  const cacheF = traffic ? new Float32Array(15 * 2) : null; let cacheBin = -1;
  const tfac = (e, elapsed) => {
    if (live && live[e] > 0) return 1 / live[e];
    if (!traffic) return 1;
    const bin = Math.floor((localBase + elapsed * 1000) / 900000);
    if (bin !== cacheBin) { cacheBin = bin; for (let c = 0; c < 15; c++) { cacheF[c * 2] = typicalFactor(c, false, bin * 900000); cacheF[c * 2 + 1] = typicalFactor(c, true, bin * 900000); } }
    return cacheF[g.ecls[e] * 2 + ((g.eflag2[e] & F2.URBAN) ? 1 : 0)];
  };
  let secPerM, costMult;
  if (mode === 'car') {
    secPerM = (e, el) => tfac(e, el) / carMs[e];
    costMult = (e) => { const f = g.eflag[e], f2 = g.eflag2[e], c = g.ecls[e]; let m = 1;
      if (f2 & F2.PRIVATE) m *= 2.5; if (c === C.track) m *= 2.2; if (c === C.living) m *= 1.4; if (f & F.UNPAVED) m *= avoid.unpaved ? 6 : 1.25;
      if (avoid.highways && (c === C.motorway || (c === C.trunk && (f2 & F2.EXPRESSWAY)))) m *= 8; if (avoid.tolls && (f & F.TOLL)) m *= 12; if (f & F.FERRY) m *= avoid.ferries ? 20 : 1.5;
      return m; };
  } else if (mode === 'bike') {
    secPerM = (e) => 1 / (((g.eflag[e] & F.UNPAVED) ? 0.8 : 1) * (BIKE_MS[g.ecls[e]] || 1.7));
    costMult = (e) => { const f = g.eflag[e]; let m = BIKE_MULT[g.ecls[e]]; if (f & F.UNPAVED) m *= avoid.unpaved ? 4 : 1.15; if (f & F.FERRY) m *= avoid.ferries ? 20 : 1.5; return m; };
  } else {
    secPerM = (e) => (g.ecls[e] === C.steps ? 1 / 0.7 : g.ecls[e] === C.ferry ? 1 / 4 : 1 / 1.36);
    costMult = (e) => { const f = g.eflag[e]; let m = WALK_MULT[g.ecls[e]]; if (f & F.SIDEPATH) m *= 1.5; if (f & F.FERRY) m *= avoid.ferries ? 20 : 1.5; return m; };
  }
  const edgeCost = (e, el) => { let c = secPerM(e, el) * costMult(e); if (pen && pen[e]) c *= 1 + pen[e]; return c; };
  // seconds lost at the node between two arcs
  const turn = (inArc, outArc, v) => {
    if ((g.eflag[outArc >> 1] & F.FERRY) && !(g.eflag[inArc >> 1] & F.FERRY)) return 300; // waiting to board
    if ((inArc >> 1) === (outArc >> 1)) return mode === 'walk' ? 2 : (self.off[v + 1] - self.off[v] <= 1 ? 12 : 90); // U-turn
    if (mode !== 'car') {
      if (mode === 'walk') { const c = g.ecls[outArc >> 1], ci = g.ecls[inArc >> 1]; return (g.eflag[outArc >> 1] & F.CROSSING) ? 6 : 0; }
      return self.jdeg[v] >= 3 ? 2 : 0;
    }
    const jd = self.jdeg[v]; if (jd < 3) return 0;
    const ei = inArc >> 1, eo = outArc >> 1, ci = g.ecls[ei], co = g.ecls[eo];
    let d = Math.abs(((self._bearing(outArc, true) - self._bearing(inArc, false) + 540) % 360) - 180);
    const urban = (g.eflag2[ei] & F2.URBAN) ? 1 : 0;
    let s;
    if (ci <= C.trunk && co <= C.trunk) s = 0;                               // grade-separated flow
    else if ((g.eflag[ei] & F.LINK) || (g.eflag[eo] & F.LINK)) s = d > 50 ? TUNE.ramp : 1; // ramps: merge or a signal at the foot
    else if (self.jbest[v] < ci) s = urban ? TUNE.yieldU : TUNE.yieldR;       // giving way to a bigger road
    else {
      const cross = self.jcnt[v] >= 3 ? ci : self.jsec[v];                   // the road being crossed
      if (cross <= C.tertiary && cross - ci <= 2) s = urban ? TUNE.signalU : TUNE.signalR; // two real roads meet: usually signalled
      else s = ci >= C.minor ? TUNE.minor : (urban ? TUNE.throughU : 0.5);
    }
    if (d > 135) s += TUNE.sharp; else if (d > 50) s += TUNE.turn;
    return s;
  };
  return { secPerM, edgeCost, turn };
};

/** Core search between two snaps. → { arcs:[…], cost } or null */
Router.prototype._search = function (A, B, mode, P, limitSec) {
  const g = this.g, len = this.len, off = this.off, arcs = this.arcs, fbit = MODE_BIT[mode];
  const gc = this.gc, prev = this.prev, stamp = this.stamp, closed = this.closed, q = ++this.q;
  const tlon = B.lon * RAD, tlat = B.lat * RAD, tcos = Math.cos(tlat), vmax = this.vmax[mode], lat = this.lat, lon = this.lon, cosl = this.cosl;
  const h = (v) => { const dl = lat[v] - tlat, dn = lon[v] - tlon, a = Math.sin(dl / 2) ** 2 + cosl[v] * tcos * Math.sin(dn / 2) ** 2; return 2 * R_EARTH * Math.asin(Math.sqrt(a)) / vmax; };
  this.hn = 0;
  let best = Infinity, bestArc = -1, bestDirect = false, visited = 0;
  // same edge, no node needed
  if (A.edge === B.edge) {
    const e = A.edge, f = g.eflag[e];
    if (B.t >= A.t && (f & fbit)) { best = (B.t - A.t) * len[e] * P.edgeCost(e, 0); bestDirect = true; bestArc = 2 * e; }
    else if (B.t <= A.t && (f & (fbit << 1))) { best = (A.t - B.t) * len[e] * P.edgeCost(e, 0); bestDirect = true; bestArc = 2 * e + 1; }
  }
  for (let d = 0; d < 2; d++) {
    const e = A.edge; if (!(g.eflag[e] & (fbit << d))) continue;
    const a = 2 * e + d, c = (d ? A.t : 1 - A.t) * len[e] * P.edgeCost(e, 0);
    gc[a] = c; prev[a] = -1; stamp[a] = q; this._push(c + h(d ? g.ea[e] : g.eb[e]), a);
  }
  const tEdge = B.edge; let bestPrev = -1;
  while (this.hn) {
    if (this.hk[0] >= best) break;
    const u = this._pop(); if (closed[u] === q) continue; closed[u] = q; visited++;
    const ue = u >> 1, v = (u & 1) ? g.ea[ue] : g.eb[ue], gu = gc[u];
    if (limitSec && gu > limitSec) break;
    for (let k = off[v]; k < off[v + 1]; k++) {
      const a = arcs[k], e = a >> 1, d = a & 1;
      if (!(g.eflag[e] & (fbit << d))) continue;
      const base = gu + P.turn(u, a, v), per = P.edgeCost(e, base);
      // arriving: only part of the target edge is driven. Kept apart from the through record below.
      if (e === tEdge) { const c = base + (d ? 1 - B.t : B.t) * len[e] * per; if (c < best) { best = c; bestArc = a; bestPrev = u; bestDirect = false; } }
      if (closed[a] === q) continue;
      const c = base + len[e] * per;
      if (stamp[a] !== q || c < gc[a]) { gc[a] = c; prev[a] = u; stamp[a] = q; this._push(c + h(d ? g.ea[e] : g.eb[e]), a); }
    }
  }
  this.visited = visited;
  if (bestArc < 0) return null;
  if (bestDirect) return { arcs: [bestArc], cost: best, direct: true };
  const out = [bestArc]; let cur = bestPrev;
  while (cur !== -1) { out.push(cur); cur = prev[cur]; }
  out.reverse();
  return { arcs: out, cost: best, direct: false };
};

// ---- geometry helpers
Router.prototype._arcCoords = function (arc, t0, t1) { // world-unit points along the arc between fractions (of the edge, a→b)
  const g = this.g, e = arc >> 1, pts = [[g.nx[g.ea[e]], g.ny[g.ea[e]]]];
  for (let o = g.egeo[e]; o < g.egeo[e + 1]; o += 2) pts.push([g.geo[o], g.geo[o + 1]]);
  pts.push([g.nx[g.eb[e]], g.ny[g.eb[e]]]);
  let out = pts;
  if (t0 > 0 || t1 < 1) {
    let tot = 0; const sl = []; for (let i = 1; i < pts.length; i++) { const l = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); sl.push(l); tot += l; }
    const d0 = t0 * tot, d1 = t1 * tot; out = []; let acc = 0;
    for (let i = 1; i < pts.length; i++) {
      const l = sl[i - 1], s = acc, en = acc + l; acc = en;
      if (en < d0 || s > d1) continue;
      const lerp = (d) => { const t = l ? (d - s) / l : 0; return [pts[i - 1][0] + t * (pts[i][0] - pts[i - 1][0]), pts[i - 1][1] + t * (pts[i][1] - pts[i - 1][1])]; };
      if (!out.length) out.push(s >= d0 ? pts[i - 1] : lerp(d0));
      out.push(en <= d1 ? pts[i] : lerp(d1));
    }
    if (out.length < 2) { const p = out[0] || pts[0]; out = [p, p]; }
  }
  if (arc & 1) out = out.slice().reverse();
  return out.map(p => [wx2lon(p[0]), wy2lat(p[1])]);
};
function bearingLL(a, b) { const dx = (b[0] - a[0]) * Math.cos((a[1] + b[1]) / 2 * RAD), dy = b[1] - a[1]; return (Math.atan2(dx, dy) / RAD + 360) % 360; }
function distLL(a, b) { const t1 = a[1] * RAD, t2 = b[1] * RAD, dt = t2 - t1, dl = (b[0] - a[0]) * RAD, h = Math.sin(dt / 2) ** 2 + Math.cos(t1) * Math.cos(t2) * Math.sin(dl / 2) ** 2; return 2 * R_EARTH * Math.asin(Math.min(1, Math.sqrt(h))); }
const COMPASS = ['north', 'northeast', 'east', 'southeast', 'south', 'southwest', 'west', 'northwest'];
const UNNAMED = ['road', 'road', 'road', 'road', 'road', 'road', 'service road', 'track', 'path', 'footpath', 'cycle path', 'pedestrian street', 'steps', 'ferry', 'street'];

/** Turn a found path into coordinates, totals and steps. */
Router.prototype._assemble = function (res, A, B, mode, P, o) {
  const g = this.g, len = this.len, legs = [], n = res.arcs.length;
  let elapsed = 0, dist = 0;
  for (let i = 0; i < n; i++) {
    const a = res.arcs[i], e = a >> 1, d = a & 1;
    let t0 = 0, t1 = 1;
    if (res.direct) { t0 = Math.min(A.t, B.t); t1 = Math.max(A.t, B.t); }
    else { if (i === 0) { if (d) t1 = A.t; else t0 = A.t; } if (i === n - 1) { if (d) t0 = B.t; else t1 = B.t; } }
    const L = (t1 - t0) * len[e], tsec = i > 0 ? P.turn(res.arcs[i - 1], a, d ? g.eb[e] : g.ea[e]) : 0;
    const sec = L * P.secPerM(e, elapsed) + tsec;
    legs.push({ arc: a, e, len: L, sec, coords: this._arcCoords(a, t0, t1), node: d ? g.eb[e] : g.ea[e] });
    elapsed += sec; dist += L;
  }
  const coords = []; for (const l of legs) { l.ci = Math.max(0, coords.length - 1); for (let j = coords.length ? 1 : 0; j < l.coords.length; j++) coords.push(l.coords[j]); }
  return { coords, dist, seconds: elapsed, legs };
};

Router.prototype._label = function (e) {
  const g = this.g, nm = g.ename[e] >= 0 ? g.names[g.ename[e]] : '', ref = g.eref[e] >= 0 ? g.names[g.eref[e]] : '';
  if (nm && ref && g.ecls[e] <= C.trunk) return ref + ' (' + nm + ')';
  if (nm) return nm; if (ref) return ref;
  if (g.eflag[e] & F.CROSSING) return '';
  return '';
};

Router.prototype._steps = function (legs, mode, destLabel) {
  const g = this.g, fbit = MODE_BIT[mode], steps = [];
  const kind = (e) => (g.eflag[e] & F.FERRY) ? 'ferry' : (g.eflag[e] & F.LINK) ? 'link' : 'road';
  const head = (l) => { const c = l.coords; let j = 1; while (j < c.length - 1 && distLL(c[0], c[j]) < 12) j++; return bearingLL(c[0], c[j]); };
  const tail = (l) => { const c = l.coords, m = c.length - 1; let j = m - 1; while (j > 0 && distLL(c[j], c[m]) < 12) j--; return bearingLL(c[Math.max(0, j)], c[m]); };
  let cur = null;
  for (let i = 0; i < legs.length; i++) {
    const l = legs[i], name = this._label(l.e), k = kind(l.e);
    if (!cur) { cur = { type: 'depart', mod: '', name, kind: k, len: 0, sec: 0, ci: l.ci, at: l.coords[0], bear: head(l), cls: g.ecls[l.e], e: l.e }; steps.push(cur); }
    else {
      const p = legs[i - 1], v = l.node;
      let d = ((head(l) - tail(p) + 540) % 360) - 180;
      // other ways out of this node, and how close the straightest one is to dead ahead
      let choices = 0, rival = 180, rivalSide = 0;
      for (let q = this.off[v]; q < this.off[v + 1]; q++) {
        const a = this.arcs[q], e = a >> 1; if (e === l.e || e === p.e) continue;
        if (!(g.eflag[e] & (fbit << (a & 1)))) continue;
        if (mode === 'car' && (g.eflag2[e] & F2.PRIVATE)) continue;
        choices++;
        const od = ((this._bearing(a, true) - tail(p) + 540) % 360) - 180;
        if (Math.abs(od) < Math.abs(rival)) { rival = od; rivalSide = od < d ? 1 : -1; }
      }
      const ad = Math.abs(d), sameName = name === cur.name || (!name && !cur.name && g.ecls[l.e] === cur.cls);
      const pk = kind(p.e);
      let type = null, mod = '';
      const side = d < 0 ? 'left' : 'right';
      if (p.e === l.e) { type = 'uturn'; }
      else if (k === 'ferry' && pk !== 'ferry') type = 'ferry';
      else if (pk === 'ferry' && k !== 'ferry') type = 'leave-ferry';
      else if (k === 'link' && pk !== 'link' && mode === 'car') { type = g.ecls[p.e] <= C.trunk ? 'exit' : 'ramp'; mod = ad < 12 && Math.abs(rival) > 12 ? (rivalSide > 0 ? 'right' : 'left') : side; }
      else if (pk === 'link' && k !== 'link' && mode === 'car' && ad < 40 && g.ecls[l.e] <= C.primary) { type = 'merge'; }
      else if (choices > 0 && ad >= 28) { type = 'turn'; mod = ad < 55 ? 'slight ' + side : ad < 140 ? side : 'sharp ' + side; }
      else if (choices > 0 && ad < 28 && Math.abs(rival) < 32 && mode === 'car' && (g.ecls[l.e] <= C.trunk || k === 'link' || !sameName)) { type = 'fork'; mod = rivalSide > 0 ? 'right' : 'left'; }
      else if (!sameName && (name || cur.name) && !(g.eflag[l.e] & F.CROSSING) && !(g.eflag[p.e] & F.CROSSING)) { if (name) { type = 'continue'; } }
      if (type) { const stay = type === 'fork' && name && name === cur.name; cur = { stay, type, mod, name, kind: k, len: 0, sec: 0, ci: l.ci, at: l.coords[0], bear: head(l), cls: g.ecls[l.e], e: l.e, angle: d }; steps.push(cur); }
      else if (!cur.name && name) cur.name = name;
    }
    cur.len += l.len; cur.sec += l.sec;
  }
  // fold very short steps into their neighbours, carrying the net turn forward
  for (let i = 1; i < steps.length - 0; i++) {
    const s = steps[i], nx = steps[i + 1];
    if (s.len >= 16 || !nx || s.type === 'ferry' || s.type === 'leave-ferry' || s.type === 'exit' || s.type === 'ramp') continue;
    if (nx.type === 'turn' || nx.type === 'continue' || nx.type === 'fork') {
      const net = (s.angle || 0) + (nx.angle || 0), ad = Math.abs(net), side = net < 0 ? 'left' : 'right';
      if (s.type === 'turn' || nx.type === 'turn') { nx.type = ad < 24 ? 'continue' : 'turn'; nx.mod = ad < 24 ? '' : ad < 55 ? 'slight ' + side : ad < 140 ? side : 'sharp ' + side; nx.angle = net; }
      nx.len += s.len; nx.sec += s.sec; nx.ci = s.ci; nx.at = s.at; steps.splice(i, 1); i--;
    }
  }
  // drop "continue" steps that go nowhere new
  for (let i = steps.length - 1; i > 0; i--) { const s = steps[i], p = steps[i - 1]; if (s.type === 'continue' && (!s.name || s.name === p.name)) { p.len += s.len; p.sec += s.sec; steps.splice(i, 1); } }
  const on = (s) => s.name ? s.name : 'the ' + UNNAMED[s.cls];
  const out = steps.map((s) => {
    let turn, text;
    switch (s.type) {
      case 'depart': turn = 'Head ' + COMPASS[Math.round(s.bear / 45) % 8]; text = 'on ' + on(s); break;
      case 'turn': turn = s.mod.startsWith('slight') ? 'Slight ' + s.mod.slice(7) : s.mod.startsWith('sharp') ? 'Sharp ' + s.mod.slice(6) : 'Turn ' + s.mod; text = 'onto ' + on(s); break;
      case 'fork': turn = 'Keep ' + s.mod; text = s.name ? (s.stay ? 'to stay on ' : 'onto ') + s.name : 'at the fork'; break;
      case 'exit': turn = 'Take the exit'; text = s.name ? 'toward ' + s.name : (s.mod ? 'on the ' + s.mod : ''); break;
      case 'ramp': turn = 'Take the ramp'; text = s.name ? 'toward ' + s.name : (s.mod ? 'on the ' + s.mod : ''); break;
      case 'merge': turn = 'Merge'; text = 'onto ' + on(s); break;
      case 'uturn': turn = 'Make a U-turn'; text = s.name ? 'on ' + s.name : ''; break;
      case 'ferry': turn = 'Board the ferry'; text = s.name || ''; break;
      case 'leave-ferry': turn = 'Leave the ferry'; text = s.name ? 'onto ' + s.name : ''; break;
      default: turn = 'Continue'; text = 'onto ' + on(s);
    }
    return { turn, text, type: s.type, mod: s.mod || '', name: s.name || '', len: s.len, sec: s.sec, at: s.at, ci: s.ci };
  });
  return out;
};

/**
 * route(from, to, opts) — from/to: {lon, lat, label?}
 * opts: { mode:'walk'|'bike'|'car', depart: ms|null, traffic:'typical'|'none', avoid:{tolls,highways,ferries,unpaved}, alternatives: 0..2 }
 */
Router.prototype.route = function (from, to, opts) {
  const o = opts || {}, mode = o.mode || 'car', maxSnap = o.maxSnap || (this.g.z < 14 ? 8000 : 2500);
  const A = this.snap(from.lon, from.lat, mode, maxSnap), B = this.snap(to.lon, to.lat, mode, maxSnap);
  const what = { walk: 'walkable path', bike: 'cyclable road', car: 'drivable road' }[mode];
  if (!A) return { error: `No ${what} near the start in this offline area.` };
  if (!B) return { error: `No ${what} near the destination in this offline area.` };
  const t0 = Date.now();
  this.pen = null;
  const P = this._profile(mode, o), res = this._search(A, B, mode, P);
  if (!res) return { error: `No ${mode === 'car' ? 'driving' : mode === 'bike' ? 'cycling' : 'walking'} route found between these points in this offline area.` };
  const visited = this.visited;
  const build = (r) => {
    this.pen = null; const Pn = this._profile(mode, o);
    const asm = this._assemble(r, A, B, mode, Pn, o), steps = this._steps(asm.legs, mode, to.label);
    const access = (A.dist + B.dist) / (mode === 'car' ? 4 : this.vmax[mode] * 0.9);
    steps.push({ turn: 'Arrive', text: to.label || 'at your destination', type: 'arrive', mod: '', name: '', len: 0, sec: 0, at: asm.coords[asm.coords.length - 1], ci: asm.coords.length - 1 });
    // free-flow comparison for the traffic line
    let free = null;
    if (mode === 'car' && (o.depart != null && o.traffic !== 'none' || this.live)) { const saveLive = this.live; this.live = null; const Pf = this._profile(mode, Object.assign({}, o, { traffic: 'none' })); free = this._assemble(r, A, B, mode, Pf, o).seconds + access; this.live = saveLive; }
    const tally = new Map(); for (const l of asm.legs) { const nm = this._label(l.e); if (nm) tally.set(nm, (tally.get(nm) || 0) + l.len); }
    let via = '', vl = 0; for (const [nm, v] of tally) if (v > vl) { vl = v; via = nm; }
    const edges = new Set(asm.legs.map(l => l.e));
    return { coords: asm.coords, dist: asm.dist + A.dist + B.dist, seconds: asm.seconds + access, free, steps, via, edges,
      link: [[[from.lon, from.lat], [A.lon, A.lat]], [[B.lon, B.lat], [to.lon, to.lat]]] };
  };
  const main = build(res); main.visited = visited;
  const alts = [];
  const want = Math.min(2, o.alternatives || 0), ms0 = Date.now() - t0;
  if (want && !res.direct && ms0 < 900 && main.dist > 400) {
    const pen = new Float32Array(this.g.nE); let usedSets = [main.edges];
    for (let k = 0; k < want + 1 && alts.length < want; k++) {
      for (const s of usedSets) for (const e of s) pen[e] = Math.min(3, pen[e] + 0.7);
      this.pen = pen; const Pp = this._profile(mode, o), r2 = this._search(A, B, mode, Pp);
      if (!r2) break;
      const alt = build(r2);
      let shared = 0, tot = 0; const legsLen = (e) => this.len[e];
      for (const e of alt.edges) { const L = legsLen(e); tot += L; if (usedSets.some(s => s.has(e))) shared += L; }
      usedSets = [alt.edges];
      if (tot && shared / tot <= 0.72 && alt.seconds <= main.seconds * 1.4 + 90 && !alts.some(a => Math.abs(a.seconds - alt.seconds) < 2 && Math.abs(a.dist - alt.dist) < 5)) alts.push(alt);
      usedSets = [main.edges].concat(alts.map(a => a.edges));
    }
    this.pen = null;
  }
  const strip = (r) => { delete r.edges; return r; };
  strip(main); alts.forEach(strip);
  main.alts = alts; main.ms = Date.now() - t0; main.engine = 'device';
  return main;
};

const api = { Router, TUNE, URBAN_KMH, RURAL_KMH, typicalFactor, congestionLevel, distLL, bearingLL, MODE_BIT };
if (typeof module !== 'undefined' && module.exports) module.exports = api; else (root.DATUM = root.DATUM || {}).router = api;
})(typeof self !== 'undefined' ? self : globalThis);
