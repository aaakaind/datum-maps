# DATUM Maps

A free, open map of the world. Search places, get walking, cycling and driving
directions, and download **region packs** that search and route on your device
without sending your queries anywhere. No account, no ads, no analytics.

`DTM-02 · v0.2 · build 2026-10-07` — an AI² Lab project by AKA IND Technologies Inc.

![DATUM: Louvre to Eiffel Tower walking route](docs/screenshots/paris-route.png)

| Globe | Region pack on a phone | 3D buildings |
|---|---|---|
| ![Opening globe](docs/screenshots/globe.png) | ![Ottawa region pack routing on-device](docs/screenshots/ottawa-phone.png) | ![Midtown Manhattan in 3D](docs/screenshots/nyc-3d.png) |

## What works in v0.2

| Feature | How it works | Where the request goes |
|---|---|---|
| World map (globe at low zoom, 3D buildings) | OpenMapTiles vector tiles rendered with MapLibre GL JS 5.24.0 | OpenFreeMap |
| Search anywhere | Photon geocoder, biased to the map centre | Photon (komoot public server) |
| Directions anywhere (walk, bike, drive) | Valhalla routing with turn-by-turn steps | Valhalla on the FOSSGIS public server |
| Category lists (Coffee, Parks, Transit…) | Read from the map tiles already on screen | Nothing sent |
| Ottawa–Gatineau region pack (3.9 MB) | On-device search of 11,487 places and every named street; on-device A* routing over 64,272 road and path segments | Nothing sent after download; stored in the browser's Cache Storage |

## Run it

It is a static site: one `index.html` plus the `packs/` folder. `vercel.json` sets cache and security headers for Vercel; any static host works.

```sh
python3 -m http.server 8080
# open http://localhost:8080
```

Region packs persist through the Cache Storage API, which browsers only expose on
`https://` or `localhost`.

## Services and swapping them

Every external service is in the `CONFIG` block at the top of the script in
`index.html`:

```js
const CONFIG = {
  tiles:  'https://tiles.openfreemap.org/planet',
  glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
  search: 'https://photon.komoot.io',
  route:  'https://valhalla1.openstreetmap.de/route',
  packs:  [ /* region packs */ ],
};
```

- **OpenFreeMap** states it is free for any use, with no API keys and no limits on
  map views; attribution is required.
- **Photon** (komoot) public server: free within reasonable use; heavy use is
  throttled or banned; no availability guarantee.
- **Valhalla** on the FOSSGIS server: a community-run public instance. Read the
  FOSSGIS terms at <https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/>
  before sending real traffic.

All three are open source (OpenFreeMap, Photon, Valhalla) and can be self-hosted.
**Before a public launch with real traffic, point `search` and `route` at our own
instances**; the public servers are not meant to carry another product's load.

## Region packs

A pack is a quantized, delta-encoded JSON extract of Overture Maps data for one
bounding box: a routable road graph (`roads.json`), land, water and neighbourhood
names (`areas.json`) and places (`places.json`).

Build one with `tools/build_pack.py`:

```sh
pip install overturemaps shapely
B=-75.74,45.38,-75.65,45.445
for t in segment place water land_use land division; do
  overturemaps download --bbox=$B -f geojson --type=$t -o raw/$t.geojson
done
python3 tools/build_pack.py        # edit BBOX / RELEASE at the top first; writes pack/*.json
```

Sidewalks are mapped as unnamed footways; the builder gives each one the name of
the parallel street within 30 m (flag 256), so walking directions read
"Bank Street" instead of "footpath". On-device routing snaps endpoints to the
largest strongly connected component for each travel mode, so a route always
exists inside a pack.

Known limits of on-device routing in v0.2: no turn restrictions, no time-based or
partial-length access rules, constant speeds (walk 4.9 km/h, bike 15 km/h, car at
the posted limit or a class default).

## Attribution (required wherever the map is shown)

- Map: OpenFreeMap © OpenMapTiles. Data from © OpenStreetMap contributors (ODbL 1.0).
- Search: Photon by komoot, OpenStreetMap data.
- Routing: Valhalla, served by FOSSGIS e.V., OpenStreetMap data.
- Region packs: Overture Maps Foundation (release 2026-09-23.1). Roads, buildings,
  land and water © OpenStreetMap contributors (ODbL 1.0). Places under
  CDLA-Permissive-2.0, including Foursquare Open Source Places (Apache-2.0) and
  AllThePlaces (CC0).

## Licences

- Code: GNU AGPL-3.0 (see `LICENSE`).
- Region pack files: a derivative database of ODbL data, available under ODbL 1.0.
- The Canada address points in Overture (source `ca/countrywide`) are tagged
  `LicenseRef-Proprietary` in the row data, while Overture's documentation lists the
  source as openly licensed. They are left out of packs until that is resolved.
