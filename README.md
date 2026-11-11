# DATUM Maps

A free, open map of the world. Search places, get walking, cycling and driving
directions, and download **region packs** that search and route on your device
without sending your queries anywhere. No account, no ads, no analytics.

`DTM-03 · v0.3 · build 2026-11-11` — an AI² Lab project by AKA IND Technologies Inc.

![DATUM: Louvre to Eiffel Tower walking route](docs/screenshots/paris-route.png)

| Globe | Region pack on a phone | 3D buildings |
|---|---|---|
| ![Opening globe](docs/screenshots/globe.png) | ![Ottawa region pack routing on-device](docs/screenshots/ottawa-phone.png) | ![Midtown Manhattan in 3D](docs/screenshots/nyc-3d.png) |

## What works in v0.3

| Feature | How it works | Where the request goes |
|---|---|---|
| World map (globe at low zoom, 3D buildings) | OpenMapTiles vector tiles rendered with MapLibre GL JS 5.24.0 | OpenFreeMap |
| Search anywhere | Photon geocoder, biased to the map centre | Photon (komoot public server) |
| Directions anywhere (walk, bike, drive) | Valhalla routing with turn-by-turn steps | Valhalla on the FOSSGIS public server |
| World view in the Equal Earth projection | Zoomed out, the world is drawn with d3-geo in Equal Earth, the area-true projection the UN General Assembly backed on 4 September 2026; Natural Earth land, lakes and borders, disputed borders dashed | DATUM's own site (`data/world.json`); d3 loads from cdnjs like MapLibre |
| Category lists (Coffee, Parks, Transit…) | Read from the map tiles already on screen | Nothing sent |
| Locate me | The browser's geolocation, shown as a dot with its accuracy circle; also offered as **Your location** in directions | Nothing sent (until you route from it) |
| Shareable links | The address bar holds the view, the open place, a route (with stops, mode and avoid options) or a search; **Share** copies it or opens the phone's share sheet | Nowhere, unless you send the link |
| Map point menu (right-click, or long-press on touch) | What's here, directions from or to here, add as a stop, reachable from here, measure, share, copy coordinates | Photon, to name the point |
| Multi-stop routes (up to 5 stops) | Valhalla with several locations, or chained on-device A* inside a region pack | Valhalla, or nothing inside a pack |
| Alternative routes | Up to 3 routes from Valhalla (`alternates: 2`), listed with time and distance; tap a list item or a grey line | Valhalla |
| Avoid highways, tolls, ferries | Valhalla costing preferences (`use_highways`, `use_tolls`, `use_ferry` = 0): avoided when a reasonable alternative exists | Valhalla |
| Reachable from here | Valhalla isochrones: 5/10/15 min on foot, 10/20/30 min by bike or car | Valhalla (`/isochrone`) |
| Measure distance | Great-circle distance along points you tap | Nothing sent |
| Saved places and recent searches | `localStorage` in this browser; listed on the search home and offered as quick picks in directions | Nothing sent |
| Terrain | Hillshade and 3D relief from Terrain Tiles (Terrarium encoding) | AWS Open Data (`elevation-tiles-prod`) |
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
  isochrone: 'https://valhalla1.openstreetmap.de/isochrone',
  terrain: 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png',
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

- **Terrain Tiles** on AWS Open Data: free to use with attribution
  (<https://github.com/tilezen/joerd/blob/master/docs/attribution.md>); requests go to Amazon S3.

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

## World view

`data/world.json` (152 KB, 50 KB gzipped) is built from Natural Earth, which is in the
public domain, with `tools/build_world.py`:

```sh
pip install shapely
B=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
mkdir -p ne && for f in ne_50m_land ne_50m_admin_0_boundary_lines_land ne_50m_lakes ne_110m_admin_0_countries; do
  curl -sL -o ne/$f.geojson $B/$f.geojson
done
python3 tools/build_world.py ne data/world.json
```

Borders are Natural Earth's de facto lines. Disputed, indefinite and line-of-control
segments are drawn dashed, and countries are not coloured individually. Two de facto
entities that OpenStreetMap does not map as countries (Somaliland, Northern Cyprus) are
left unlabelled for consistency with the rest of the map. The UN's own map images are
not used: the UN website licenses them for personal, non-commercial use only.

## Attribution (required wherever the map is shown)

- Map: OpenFreeMap © OpenMapTiles. Data from © OpenStreetMap contributors (ODbL 1.0).
- Search: Photon by komoot, OpenStreetMap data.
- Routing: Valhalla, served by FOSSGIS e.V., OpenStreetMap data.
- World view: Natural Earth (public domain), drawn with d3-geo (ISC). Equal Earth projection by Šavrič, Patterson and Jenny (2018).
- Terrain: Terrain Tiles on AWS Open Data, made by Mapzen from SRTM, GMTED2010, ETOPO1 and other sources
  (shown in the footer while terrain is on).
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

## Link format

Everything after `#` is the state of the page, so any view can be bookmarked or shared:

| Key | Example | Meaning |
|---|---|---|
| `map` | `15.00/48.85840/2.29450[/bearing/pitch]` | zoom / latitude / longitude; bearing and pitch when tilted |
| `pin`, `name` | `pin=48.858400,2.294500&name=Eiffel+Tower` | open place card (no `name` for a dropped pin) |
| `reach` | `reach=walk` | show the reachable areas from that place |
| `r`, `mode`, `avoid`, `alt` | `r=LAT,LON,Label;LAT,LON,Label&mode=car&avoid=tolls&alt=1` | a route: start, stops and destination, travel mode, avoid options, chosen alternative |
| `q` | `q=coffee+ottawa` | a search |
| `layers` | `layers=terrain` | terrain relief on |

Without `map`, DATUM fits the map to the shared place or route.
