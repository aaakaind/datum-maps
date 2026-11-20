// DATUM · service worker: keeps the app shell available with no connection.
// Map tiles, fonts and road graphs live in IndexedDB (see js/offline.js); this
// only handles the page, its scripts and the small world/catalog data files.
const VERSION = 'datum-shell-v3';
const SHELL = ['./', 'index.html', 'manifest.webmanifest', 'js/idb.js', 'js/mvt.js', 'js/graph.js', 'js/router.js', 'js/search.js', 'js/nav.js', 'js/offline.js', 'js/worker.js',
  'data/world.json', 'icons/icon-192.png', 'icons/icon-512.png',
  'https://cdnjs.cloudflare.com/ajax/libs/maplibre-gl/5.24.0/maplibre-gl.js', 'https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js'];
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then(c => Promise.all(SHELL.map(u => c.add(new Request(u, { cache: 'reload' })).catch(() => {})))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k.startsWith('datum-shell-') && k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const req = e.request; if (req.method !== 'GET') return;
  const url = new URL(req.url), same = url.origin === self.location.origin;
  const lib = url.hostname === 'cdnjs.cloudflare.com', font = url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com';
  if (!same && !lib && !font) return;                 // tiles, search and routing go straight to the network
  if (same && url.pathname.includes('/packs/')) return; // region packs manage their own cache
  if (lib || font) { // versioned, immutable: cache first
    e.respondWith(caches.open(VERSION).then(async c => (await c.match(req)) || fetch(req).then(r => { if (r.ok || r.type === 'opaque') c.put(req, r.clone()); return r; })));
    return;
  }
  // the app itself: network first so updates land, stored copy when offline
  e.respondWith(caches.open(VERSION).then(async c => {
    try { const r = await fetch(req); if (r.ok) c.put(req, r.clone()); return r; }
    catch (err) { const hit = (await c.match(req, { ignoreSearch: true })) || (req.mode === 'navigate' ? await c.match('index.html') : null); if (hit) return hit; throw err; }
  }));
});
