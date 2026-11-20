// DATUM · Mapbox Vector Tile decoder (protobuf), dependency-free.
// Decodes just what the on-device engine needs: layers → features with
// integer tile coordinates, a type and a plain properties object.
(function (root) {
'use strict';
function Pbf(buf) { this.b = buf; this.p = 0; this.end = buf.length; }
Pbf.prototype.varint = function () {
  const b = this.b; let v = 0, s = 1, x;
  // up to 2^53; multiplication instead of shifts so large ids survive
  do { x = b[this.p++]; v += (x & 0x7f) * s; s *= 128; } while (x >= 0x80);
  return v;
};
Pbf.prototype.skip = function (wire) {
  if (wire === 0) { while (this.b[this.p++] >= 0x80); }
  else if (wire === 2) { const l = this.varint(); this.p += l; }
  else if (wire === 5) this.p += 4;
  else if (wire === 1) this.p += 8;
  else throw new Error('Unsupported wire type ' + wire);
};
const td = typeof TextDecoder !== 'undefined' ? new TextDecoder('utf-8') : null;
function str(b, s, e) { return td.decode(b.subarray(s, e)); }
const f32 = new Float32Array(1), u8f = new Uint8Array(f32.buffer), f64 = new Float64Array(1), u8d = new Uint8Array(f64.buffer);
function readValue(pbf, end) {
  let v = null; const b = pbf.b;
  while (pbf.p < end) {
    const t = pbf.varint(), tag = t >> 3, wire = t & 7;
    if (tag === 1) { const l = pbf.varint(); v = str(b, pbf.p, pbf.p + l); pbf.p += l; }
    else if (tag === 2) { u8f.set(b.subarray(pbf.p, pbf.p + 4)); v = f32[0]; pbf.p += 4; }
    else if (tag === 3) { u8d.set(b.subarray(pbf.p, pbf.p + 8)); v = f64[0]; pbf.p += 8; }
    else if (tag === 4 || tag === 5) { v = pbf.varint(); if (v > 9223372036854775807) v -= 18446744073709551616; }
    else if (tag === 6) { const n = pbf.varint(); v = (n % 2 === 1) ? -(n + 1) / 2 : n / 2; }
    else if (tag === 7) v = pbf.varint() !== 0;
    else pbf.skip(wire);
  }
  return v;
}
// geometry → array of rings/lines/points, each a flat Int32 [x0,y0,x1,y1…]
function readGeom(b, s, e, type) {
  const pbf = new Pbf(b); pbf.p = s; const parts = []; let cur = null, x = 0, y = 0;
  while (pbf.p < e) {
    const cmd = pbf.varint(), id = cmd & 7; let n = cmd >> 3;
    if (id === 1) { // MoveTo
      while (n-- > 0) { const dx = pbf.varint(), dy = pbf.varint(); x += (dx >> 1) ^ -(dx & 1); y += (dy >> 1) ^ -(dy & 1);
        if (type === 1) parts.push([x, y]); else { cur = [x, y]; parts.push(cur); } }
    } else if (id === 2) { while (n-- > 0) { const dx = pbf.varint(), dy = pbf.varint(); x += (dx >> 1) ^ -(dx & 1); y += (dy >> 1) ^ -(dy & 1); cur.push(x, y); } }
    else if (id === 7) { if (cur) cur.push(cur[0], cur[1]); }
    else break;
  }
  return parts;
}
/** decode(buf: Uint8Array, want?: Set<string>|string[]) → { layerName: { extent, features:[{type, props, geom}] } } */
function decode(buf, want) {
  if (!(buf instanceof Uint8Array)) buf = new Uint8Array(buf);
  const wantSet = want ? (want instanceof Set ? want : new Set(want)) : null;
  const pbf = new Pbf(buf), out = {};
  while (pbf.p < pbf.end) {
    const t = pbf.varint();
    if ((t >> 3) !== 3) { pbf.skip(t & 7); continue; }
    const len = pbf.varint(), end = pbf.p + len, start = pbf.p;
    // first pass: name, keys, values, extent
    let name = '', extent = 4096; const keys = [], vals = [], feats = [];
    while (pbf.p < end) {
      const tt = pbf.varint(), tag = tt >> 3;
      if (tag === 1) { const l = pbf.varint(); name = str(buf, pbf.p, pbf.p + l); pbf.p += l; }
      else if (tag === 2) { const l = pbf.varint(); feats.push(pbf.p, pbf.p + l); pbf.p += l; }
      else if (tag === 3) { const l = pbf.varint(); keys.push(str(buf, pbf.p, pbf.p + l)); pbf.p += l; }
      else if (tag === 4) { const l = pbf.varint(); vals.push(readValue(pbf, pbf.p + l)); }
      else if (tag === 5) extent = pbf.varint();
      else pbf.skip(tt & 7);
    }
    pbf.p = end;
    if (wantSet && !wantSet.has(name)) continue;
    const features = [];
    for (let i = 0; i < feats.length; i += 2) {
      const fp = new Pbf(buf); fp.p = feats[i]; const fe = feats[i + 1];
      let type = 0, gs = 0, ge = 0; const props = {};
      while (fp.p < fe) {
        const tt = fp.varint(), tag = tt >> 3;
        if (tag === 2) { const e2 = fp.varint() + fp.p; while (fp.p < e2) { const k = fp.varint(), v = fp.varint(); props[keys[k]] = vals[v]; } }
        else if (tag === 3) type = fp.varint();
        else if (tag === 4) { const l = fp.varint(); gs = fp.p; ge = fp.p + l; fp.p = ge; }
        else fp.skip(tt & 7);
      }
      features.push({ type, props, geom: readGeom(buf, gs, ge, type) });
    }
    out[name] = { extent, features };
    void start;
  }
  return out;
}
const api = { decode };
if (typeof module !== 'undefined' && module.exports) module.exports = api; else (root.DATUM = root.DATUM || {}).mvt = api;
})(typeof self !== 'undefined' ? self : globalThis);
