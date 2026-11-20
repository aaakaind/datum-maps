// DATUM · turn-by-turn progress along a route. Pure logic: positions in,
// snapped position, current step, distance and time remaining out.
(function (root) {
'use strict';
const RAD = Math.PI / 180, R = 6371008.8;
function dist(a, b) { const t1 = a[1] * RAD, t2 = b[1] * RAD, dt = t2 - t1, dl = (b[0] - a[0]) * RAD, h = Math.sin(dt / 2) ** 2 + Math.cos(t1) * Math.cos(t2) * Math.sin(dl / 2) ** 2; return 2 * R * Math.asin(Math.min(1, Math.sqrt(h))); }
function bearing(a, b) { const dx = (b[0] - a[0]) * Math.cos((a[1] + b[1]) / 2 * RAD), dy = b[1] - a[1]; return (Math.atan2(dx, dy) / RAD + 360) % 360; }

function Tracker(route) {
  const c = route.coords, n = c.length;
  this.coords = c; this.cum = new Float64Array(n);
  for (let i = 1; i < n; i++) this.cum[i] = this.cum[i - 1] + dist(c[i - 1], c[i]);
  this.total = this.cum[n - 1] || 0; this.seconds = route.seconds || 0; this.seg = 0;
  // where each step starts, in metres and seconds from the start
  const steps = route.steps || []; this.steps = steps;
  this.sAt = steps.map(s => this.cum[Math.min(n - 1, Math.max(0, s.ci || 0))]);
  const known = steps.every(s => typeof s.sec === 'number'), sum = steps.reduce((a, s) => a + (s.sec || 0), 0);
  let acc = 0; this.tAt = steps.map((s, i) => { const t = acc; acc += known && sum > 0 ? s.sec * (this.seconds / sum) : 0; return known && sum > 0 ? t : (this.total ? this.sAt[i] / this.total * this.seconds : 0); });
}
/** Position at a distance along the route. */
Tracker.prototype.at = function (m) {
  const c = this.coords, cum = this.cum; m = Math.max(0, Math.min(this.total, m));
  let lo = 0, hi = cum.length - 1; while (lo < hi - 1) { const mid = (lo + hi) >> 1; if (cum[mid] <= m) lo = mid; else hi = mid; }
  const l = cum[hi] - cum[lo], t = l ? (m - cum[lo]) / l : 0;
  return { pt: [c[lo][0] + t * (c[hi][0] - c[lo][0]), c[lo][1] + t * (c[hi][1] - c[lo][1])], seg: lo, bearing: bearing(c[lo], c[hi]) };
};
Tracker.prototype.update = function (lon, lat) {
  const c = this.coords, n = c.length, cl = Math.cos(lat * RAD);
  const scan = (i0, i1) => {
    let best = null;
    for (let i = Math.max(0, i0); i < Math.min(n - 1, i1); i++) {
      const ax = (c[i][0] - lon) * cl, ay = c[i][1] - lat, bx = (c[i + 1][0] - lon) * cl, by = c[i + 1][1] - lat, dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
      let t = l2 ? -(ax * dx + ay * dy) / l2 : 0; t = t < 0 ? 0 : t > 1 ? 1 : t;
      const px = ax + t * dx, py = ay + t * dy, d = Math.hypot(px, py) * 111320;
      if (!best || d < best.off - 0.01) best = { seg: i, t, off: d };
    }
    return best;
  };
  // look just ahead of the last fix first, so a route that doubles back is followed in order
  let b = scan(this.seg - 2, this.seg + 40);
  if (!b || b.off > 60) { const g = scan(0, n - 1); if (g && (!b || g.off < b.off - 25)) b = g; }
  if (!b) b = { seg: 0, t: 0, off: Infinity };
  this.seg = b.seg;
  const i = b.seg, along = this.cum[i] + b.t * (this.cum[i + 1] - this.cum[i]);
  const pt = [c[i][0] + b.t * (c[i + 1][0] - c[i][0]), c[i][1] + b.t * (c[i + 1][1] - c[i][1])];
  let step = 0; for (let k = 0; k < this.sAt.length; k++) if (this.sAt[k] <= along + 2) step = k;
  const last = this.steps.length - 1, next = Math.min(last, step + 1);
  const toNext = Math.max(0, this.sAt[next] - along);
  // time left: rest of this step by distance share, plus the later steps
  const s0 = this.sAt[step], s1 = step < last ? this.sAt[step + 1] : this.total, t0 = this.tAt[step], t1 = step < last ? this.tAt[step + 1] : this.seconds;
  const frac = s1 > s0 ? Math.min(1, Math.max(0, (along - s0) / (s1 - s0))) : 1;
  const elapsed = t0 + frac * (t1 - t0);
  return { pt, seg: i, along, off: b.off, bearing: bearing(c[i], c[i + 1]), step, next, toNext, remainingM: Math.max(0, this.total - along), remainingS: Math.max(0, this.seconds - elapsed) };
};

function sayDist(m) { if (m >= 950) { const km = m / 1000; return (km < 10 ? km.toFixed(1).replace(/\.0$/, '') : Math.round(km)) + ' kilometres'; } return (m >= 100 ? Math.round(m / 50) * 50 : Math.max(10, Math.round(m / 10) * 10)) + ' metres'; }
/** Spoken instruction for a step, optionally with a lead distance. */
function say(step, m) {
  const body = step.type === 'arrive' ? 'you will arrive' + (step.text && !/^at your/.test(step.text) ? ' at ' + step.text : ' at your destination') : (step.turn + (step.text ? ' ' + step.text : '')).replace(/\s+/g, ' ');
  if (m == null) return step.type === 'arrive' ? 'You have arrived' : body.charAt(0).toUpperCase() + body.slice(1);
  return 'In ' + sayDist(m) + ', ' + body.charAt(0).toLowerCase() + body.slice(1);
}
/** Arrow rotation (degrees) for a step, for the banner glyph. null = not an arrow. */
function arrow(step) {
  const t = step.type, m = step.mod || '';
  if (t === 'arrive') return null; if (t === 'uturn') return 180;
  const side = m.includes('left') ? -1 : m.includes('right') ? 1 : 0;
  if (t === 'fork' || t === 'exit' || t === 'ramp') return side * 30;
  if (m.startsWith('slight')) return side * 40; if (m.startsWith('sharp')) return side * 135;
  return side * 90;
}
const api = { Tracker, say, sayDist, arrow, dist, bearing };
if (typeof module !== 'undefined' && module.exports) module.exports = api; else (root.DATUM = root.DATUM || {}).nav = api;
})(typeof self !== 'undefined' ? self : globalThis);
