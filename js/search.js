// DATUM · on-device search over the places and streets collected from an offline area.
// Items are [name, alt, kind, cat, lon, lat, weight]. A linear scan over pre-normalised
// names is fast enough for a few hundred thousand entries and needs no index on disk.
(function (root) {
'use strict';
const norm = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[’`]/g, "'");
const ABBR = { st: 'street', ave: 'avenue', av: 'avenue', rd: 'road', dr: 'drive', blvd: 'boulevard', boul: 'boulevard', cres: 'crescent', pl: 'place', ln: 'lane', pkwy: 'parkway', ct: 'court', crt: 'court', terr: 'terrace', hwy: 'highway', sq: 'square', str: 'strasse' };
const isB = (c) => c === undefined || c === ' ' || c === '-' || c === '/' || c === ',' || c === '.' || c === '(' || c === ')' || c === "'";
function Index(items) {
  this.items = items; const n = items.length; this.nn = new Array(n);
  for (let i = 0; i < n; i++) { const it = items[i]; this.nn[i] = it[1] ? norm(it[0]) + ' | ' + norm(it[1]) : norm(it[0]); }
}
Index.prototype.query = function (q, center, limit) {
  const qn = norm(q).trim(); if (!qn) return [];
  const toks = qn.split(/\s+/).map(t => [t, ABBR[t] || null]), items = this.items, nn = this.nn, out = [];
  const cl = Math.cos((center ? center[1] : 0) * Math.PI / 180);
  let floor = -Infinity;
  for (let i = 0; i < nn.length; i++) {
    const s = nn[i]; let sc = 0, ok = true;
    for (const [t, full] of toks) {
      let p = s.indexOf(t), best = 0;
      while (p >= 0 && best < 36) { const pre = isB(s[p - 1]), post = isB(s[p + t.length]); best = Math.max(best, pre && post ? 36 : pre ? 30 : 12); p = s.indexOf(t, p + 1); }
      if (!best && full) { const p2 = s.indexOf(full); if (p2 >= 0) best = 30; }
      if (!best) { const cat = items[i][3]; if (cat && t.length >= 3 && cat.replace(/_/g, ' ').split(/[ /]/).some(w => w.startsWith(t))) best = 14; }
      if (!best) { ok = false; break; }
      sc += best;
    }
    if (!ok) continue;
    if (s.startsWith(qn)) sc += 40; if (s === qn || s.startsWith(qn + ' |')) sc += 30;
    const it = items[i];
    let m = null;
    if (center) { const dx = (it[4] - center[0]) * cl * 111320, dy = (it[5] - center[1]) * 110540; m = Math.sqrt(dx * dx + dy * dy); sc -= Math.min(16, m / 1000 * 1.6); }
    sc += it[6];
    if (out.length >= limit * 4 && sc < floor) continue;
    out.push({ i, sc, m });
    if (out.length > limit * 8) { out.sort((a, b) => b.sc - a.sc); out.length = limit * 4; floor = out[out.length - 1].sc; }
  }
  out.sort((a, b) => b.sc - a.sc);
  const res = [], seen = [];
  for (const o of out) {
    const it = items[o.i];
    // the same street shows up once per map cell: keep one per ~3 km
    if (seen.some(s => s[0] === it[0] && s[2] === it[2] && Math.abs(s[4] - it[4]) < 0.04 && Math.abs(s[5] - it[5]) < 0.03)) continue;
    seen.push(it); res.push({ name: it[0], alt: it[1], kind: it[2], cat: it[3], ll: [it[4], it[5]], sc: o.sc, m: o.m });
    if (res.length >= limit) break;
  }
  return res;
};
/** Named places of the given POI classes near a point, nearest first. */
Index.prototype.nearby = function (classes, center, radiusM, limit) {
  const set = new Set(classes), items = this.items, out = [], cl = Math.cos(center[1] * Math.PI / 180);
  for (let i = 0; i < items.length; i++) {
    const it = items[i]; if (it[2] !== 'poi') continue;
    const cls = it[3].split('/')[0]; if (!set.has(cls)) continue;
    const dx = (it[4] - center[0]) * cl * 111320, dy = (it[5] - center[1]) * 110540, m = Math.sqrt(dx * dx + dy * dy);
    if (m <= radiusM) out.push({ name: it[0], alt: it[1], kind: it[2], cat: it[3], ll: [it[4], it[5]], m });
  }
  out.sort((a, b) => a.m - b.m); return out.slice(0, limit);
};
const api = { Index, norm };
if (typeof module !== 'undefined' && module.exports) module.exports = api; else (root.DATUM = root.DATUM || {}).search = api;
})(typeof self !== 'undefined' ? self : globalThis);
