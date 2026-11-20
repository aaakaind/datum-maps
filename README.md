# DATUM Maps

A free, open map of the world. Search places, get walking, cycling and driving
directions with turn-by-turn navigation, and keep **any part of the world** on your
device to use with no connection. No account, no ads, no analytics.

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

It is a static site: `index.html`, the `js/` engine, `data/`, `packs/` and a service
worker. `vercel.json` sets cache and security headers for Vercel; any static host works.

```sh
npm start          # python3 -m http.server 8080
npm test           # engine tests, Node 20+, no dependencies
```

Offline storage, the service worker and geolocation need `https://` or `localhost`.

## Offline areas

An area is stored at one of three levels, chosen from its size:

| Level | Top zoom | Limit | What works offline |
|---|---|---|---|
| Streets | 14 | 2,500 tiles (a large city) | Map, search, and walk, bike and drive directions |
| Roads | 12 | 6,000 tiles (a region or small country) | Map to town level, driving directions on through roads |
| Map | ≤ 10 | 6,000 tiles | Map only. The largest countries and the whole world |

Measured on Ottawa (catalog entry, 38 km across): 762 tiles, 25 MB stored, graph of
347,000 road segments; download and build took 28 seconds on a fast connection, and a
10 km drive with two alternatives is found in under a second.

**How the road graph is built.** Tiles carry merged, simplified lines clipped at tile
edges, so `js/graph.js` clips each line to its tile, finds the junctions again (shared
vertices, T-junctions whose vertex was simplified away, crossings on the same layer and
bridge/tunnel state), stitches the pieces across tile borders, attaches names from the
separate `transportation_name` layer and contracts degree-2 nodes.

**How good are the routes.** Checked against Valhalla on random trips (device time ÷
Valhalla time, median): Ottawa streets level 0.96 drive, 1.01 walk, 0.95 bike; Ottawa
region at roads level 1.05 drive; Amsterdam 0.90 drive, 0.93 bike. Distances agree to
within 1% at the median.

**Known limits of on-device routing.** The tiles do not carry speed limits, turn
restrictions, roundabout tags, or access rules for one kind of vehicle (bus-only streets,
`motor_vehicle=no`), so speeds are set by road type and a route can occasionally use a
street a car may not enter. Ferries are kept for driving only where they land on roads
at both ends. Roads-level areas have no street names for minor streets.

**Size of the world.** The public tile server is donation-funded, so downloads are
capped per area rather than offering a whole continent at street level. Hosting our own
tiles (OpenFreeMap publishes the full planet) would lift that.

## Emergency use

![SOS tab with the network off](docs/screenshots/sos.png)

The point of the SOS tab is that it keeps working when the network does not, so
**download the area you live or travel in before you need it** (SOS → "Download 25 km
around", or the Offline tab). Then, with no signal at all, the device still has the map,
your GPS position, the nearest emergency places and routes to them.

What it cannot do: it does not contact emergency services for you, and it does not send
your position anywhere. "Call" opens the phone dialler; "Share position" and "Text it"
hand a message to the phone's own apps, which need a mobile signal. Emergency numbers are
built in for about 50 countries; elsewhere it shows 112, which mobile networks in most
countries connect to the local service. Places come from OpenStreetMap and can be
missing or out of date.

## Traffic

There is no free, keyless live traffic feed for the whole world, so DATUM is explicit
about what it shows:

- **Typical traffic** (default for driving on-device): a rush-hour curve by time and
  weekday scaled by road type and by whether the road runs through a built-up area. Pick
  a departure time under Directions → Options. It is an estimate.
- **Live traffic**: paste a TomTom API key under Directions → Options. Driving routes
  then come from the TomTom Routing API with `traffic=true`, and the map gains flow and
  incident tiles. The key is stored in the browser's `localStorage`; restrict it to your
  domain in the TomTom dashboard. **This path was written from TomTom's documentation and
  has not been run against a real key yet.**
- The router also accepts per-road live speed ratios (`Router.live`), ready for a feed
  that is matched to the graph.

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
**Before a public launch with real traffic, point `tiles`, `search` and `route` at our
own instances**; the public servers are not meant to carry another product's load.

## Download catalog

`data/regions.json` (677 KB, 280 KB gzipped, loaded when the Offline tab opens) is built
from Natural Earth with `tools/build_regions.py`; the steps are in the file's header.
Entries follow Natural Earth's list of countries and territories; names and outlines do
not imply endorsement.

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

Pack roads carry posted speed limits, which the router uses in place of the road-type
defaults. Turn restrictions and time-based access rules are not applied.

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

## Android app and Android Auto

`android/` builds DATUM for Android, package `ca.akaind.datum` (Android 8 and up).

- **On the phone** it is this web app, bundled in the APK and served to a WebView from
  `https://appassets.androidplatform.net`, a secure origin, so region packs and "locate
  me" work as in a browser. MapLibre GL JS, d3 and the fonts are bundled too
  (`tools/vendor_web.py`, checked against cdnjs's SRI hashes), so the app opens with no
  network and makes no requests to a CDN or to Google. Tiles, search and routing use the
  same servers as the web app. The build copies `index.html`, `data/` and `packs/` from
  the repository root, so the app always ships the current site.
- **In the car** it is a native Android Auto navigation app built on the Car App
  Library 1.7.0. The car screen draws DATUM's own map style (exported from the web app
  by `tools/export_car_style.py`) with MapLibre Native 13.6.1. It has search and five
  nearby categories (gas, EV charging, parking, food, coffee), read from the map tiles
  already on the phone with Photon as the fallback. Route preview offers up to three
  routes from Valhalla. Guidance is turn by turn, with spoken instructions on the
  navigation audio stream, rerouting when the car leaves the route, and arrival. Trip
  details go to the instrument cluster or head-up display where the car has one.
  "Navigate to…" from Assistant and other apps opens DATUM directly. Location comes
  from Android's own location service, with no Google Play services.

Build (JDK 17 or newer, Android SDK 36):

```sh
cd android
./gradlew testDebugUnitTest      # routing, guidance and car screen tests (Robolectric)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with the upload key named in `android/keystore.properties`
(or the file `DATUM_KEYSTORE_PROPERTIES` points to). Both that file and the key are
git-ignored and must never be committed. Then:

```sh
./gradlew assembleRelease   # APKs to sideload: one per CPU type plus a universal one
./gradlew bundleRelease     # AAB for Google Play (run on its own)
```

Most phones, a Galaxy Z Fold included, take `app-arm64-v8a-release.apk`. Enrol the app in
Play App Signing so the key in `keystore.properties` is only the upload key: Google
holds the app signing key, and a lost upload key can be reset from the Play Console.

Trying it in a car: Android Auto only lists apps installed from Google Play unless you
turn on Android Auto's developer settings (tap *Version* ten times) and enable *Unknown
sources*. On a computer, Google's Desktop Head Unit stands in for the car. Once
guidance has started, `adb shell dumpsys activity service
ca.akaind.datum/.car.DatumCarAppService AUTO_DRIVE` drives the route for you.

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
