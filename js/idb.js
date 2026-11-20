// DATUM · tiny IndexedDB wrapper shared by the page and the engine worker.
// Stores: tiles (z/x/y → {b, gz}), areas (id → meta), data (id:graph | id:search), glyphs (font/range → bytes).
(function (root) {
'use strict';
const NAME = 'datum-offline', VERSION = 1, STORES = ['tiles', 'areas', 'data', 'glyphs'];
let dbp = null;
function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') { reject(new Error('This browser has no IndexedDB, so offline areas cannot be stored.')); return; }
    const rq = indexedDB.open(NAME, VERSION);
    rq.onupgradeneeded = () => { for (const s of STORES) if (!rq.result.objectStoreNames.contains(s)) rq.result.createObjectStore(s); };
    rq.onsuccess = () => { rq.result.onversionchange = () => { rq.result.close(); dbp = null; }; resolve(rq.result); };
    rq.onerror = () => { dbp = null; reject(rq.error || new Error('Could not open offline storage.')); };
    rq.onblocked = () => reject(new Error('Offline storage is blocked by another tab.'));
  });
  return dbp;
}
const wrap = (rq) => new Promise((res, rej) => { rq.onsuccess = () => res(rq.result); rq.onerror = () => rej(rq.error); });
const done = (tx) => new Promise((res, rej) => { tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); tx.onabort = () => rej(tx.error || new Error('Storage write was aborted (the device may be out of space).')); });
async function get(store, key) { const db = await open(); return wrap(db.transaction(store).objectStore(store).get(key)); }
async function getMany(store, keys) { const db = await open(), os = db.transaction(store).objectStore(store); return Promise.all(keys.map(k => wrap(os.get(k)))); }
async function put(store, key, val) { const db = await open(), tx = db.transaction(store, 'readwrite'); tx.objectStore(store).put(val, key); return done(tx); }
async function putMany(store, pairs) { const db = await open(), tx = db.transaction(store, 'readwrite'), os = tx.objectStore(store); for (const [k, v] of pairs) os.put(v, k); return done(tx); }
async function del(store, keys) { const db = await open(), tx = db.transaction(store, 'readwrite'), os = tx.objectStore(store); for (const k of [].concat(keys)) os.delete(k); return done(tx); }
async function keys(store) { const db = await open(); return wrap(db.transaction(store).objectStore(store).getAllKeys()); }
async function all(store) { const db = await open(); return wrap(db.transaction(store).objectStore(store).getAll()); }

const canGzip = typeof CompressionStream !== 'undefined' && typeof DecompressionStream !== 'undefined';
async function gzip(buf) { if (!canGzip || buf.byteLength < 200) return { b: buf, gz: 0 }; const b = await new Response(new Blob([buf]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer(); return { b, gz: 1 }; }
async function gunzip(rec) { if (!rec.gz) return rec.b; return new Response(new Blob([rec.b]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer(); }

(root.DATUM = root.DATUM || {}).idb = { open, get, getMany, put, putMany, del, keys, all, gzip, gunzip, canGzip };
})(typeof self !== 'undefined' ? self : globalThis);
