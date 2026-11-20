"""Export DATUM's map style for the Android Auto car screen.

The web app builds its MapLibre style in JavaScript from CSS colour tokens, so the
easiest way to get the exact same style for MapLibre Native is to load the page,
let it build the style in light and dark, and serialise what the map is using.

  pip install playwright && python3 -m playwright install chromium
  python3 tools/export_car_style.py android/app/src/main/assets/car

Writes style-light.json and style-dark.json. Changes for the car:
  - no globe projection (MapLibre Native draws Web Mercator);
  - web-only sources and layers (region pack outlines, route links) removed;
  - empty GeoJSON sources the car app fills in: route, route-alt, dest, hits;
  - labels 10% larger, since a car screen is read from further away.
"""
import http.server
import json
import socketserver
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
EMPTY = {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}}
DROP_SOURCES = {"packs", "routeLink"}
DROP_LAYERS = {"pack-edge", "route-link"}
TEXT_SCALE = 1.1


def serve():
    handler = lambda *a, **k: http.server.SimpleHTTPRequestHandler(*a, directory=str(ROOT), **k)
    httpd = socketserver.TCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def fetch(route):
    """Fetch external requests from Python, so the export also works behind an
    HTTPS-intercepting proxy that the bundled Chromium does not trust."""
    rq = route.request
    if rq.url.startswith(("data:", "blob:", "http://127.0.0.1")):
        return route.continue_()
    req = urllib.request.Request(rq.url, data=rq.post_data_buffer, method=rq.method,
                                 headers={"User-Agent": "DATUM-style-export/0.2 (AKA IND)"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            st, ct, body = r.status, r.headers.get("content-type", "application/octet-stream"), r.read()
    except urllib.error.HTTPError as e:
        st, ct, body = e.code, "text/plain", e.read()
    return route.fulfill(status=st, body=body, headers={"content-type": ct, "access-control-allow-origin": "*"})


def scale(v):
    """Multiply every number in a text-size value (a number or an expression) by
    TEXT_SCALE, leaving zoom stops untouched."""
    if isinstance(v, (int, float)):
        return round(v * TEXT_SCALE, 2)
    if isinstance(v, list) and v and v[0] == "interpolate":
        out = v[:3]
        for i in range(3, len(v), 2):
            out += [v[i], scale(v[i + 1])]
        return out
    if isinstance(v, list) and v and v[0] in ("match", "case"):
        out = list(v)
        first = 3 if v[0] == "match" else 2  # match: [op, input, label, out, ..., default]
        for i in range(first, len(v) - 1, 2):
            out[i] = scale(v[i])
        out[-1] = scale(v[-1])
        return out
    return v


def for_car(style):
    paint = {l["id"]: l.get("paint", {}) for l in style["layers"]}
    route_case = paint["route-case"]["line-color"]
    label, halo = paint["road-label"]["text-color"], paint["road-label"]["text-halo-color"]
    road = paint["roads"]["line-color"]
    road = road[-1] if isinstance(road, list) else road

    sources = {k: v for k, v in style["sources"].items() if k not in DROP_SOURCES}
    sources["omt"].pop("attribution", None)
    for k in ("route", "hits"):
        sources[k] = EMPTY
    sources["route-alt"] = EMPTY
    sources["dest"] = EMPTY

    layers = []
    for l in style["layers"]:
        if l["id"] in DROP_LAYERS:
            continue
        if l["id"] == "route-case":
            # Alternative routes sit under the chosen one, drawn in the road colour.
            layers.append({"id": "route-alt-case", "type": "line", "source": "route-alt",
                           "layout": {"line-cap": "round", "line-join": "round"},
                           "paint": {"line-color": route_case, "line-opacity": 0.55,
                                     "line-width": l["paint"]["line-width"]}})
            layers.append({"id": "route-alt", "type": "line", "source": "route-alt",
                           "layout": {"line-cap": "round", "line-join": "round"},
                           "paint": {"line-color": road, "line-width": paint["route"]["line-width"]}})
        lay = l.get("layout", {})
        if "text-size" in lay:
            lay["text-size"] = scale(lay["text-size"])
        layers.append(l)
        if l["id"] == "route":
            layers.append({"id": "dest", "type": "circle", "source": "dest",
                           "paint": {"circle-color": paint["route"]["line-color"], "circle-radius": 9,
                                     "circle-stroke-color": label, "circle-stroke-width": 3}})
            layers.append({"id": "dest-label", "type": "symbol", "source": "dest",
                           "layout": {"text-field": ["get", "n"], "text-font": ["Noto Sans Bold"],
                                      "text-size": 13, "text-anchor": "top", "text-offset": [0, 1.1],
                                      "text-max-width": 10},
                           "paint": {"text-color": label, "text-halo-color": halo, "text-halo-width": 1.8}})

    return {"version": 8, "name": style.get("name", "DATUM"), "glyphs": style["glyphs"],
            "sources": sources, "layers": layers}


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    httpd = serve()
    url = f"http://127.0.0.1:{httpd.server_address[1]}/index.html"
    with sync_playwright() as pw:
        br = pw.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
        for scheme in ("light", "dark"):
            ctx = br.new_context(viewport={"width": 1280, "height": 800}, color_scheme=scheme)
            ctx.route("**/*", fetch)
            pg = ctx.new_page()
            pg.goto(url)
            pg.wait_for_function("window.__datum && window.__datum.map && window.__datum.map.isStyleLoaded()", timeout=120000)
            pg.wait_for_timeout(500)
            style = for_car(json.loads(pg.evaluate("JSON.stringify(window.__datum.map.getStyle())")))
            dst = out / f"style-{scheme}.json"
            dst.write_text(json.dumps(style, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
            print(f"{dst}: {len(style['layers'])} layers, {dst.stat().st_size // 1024} KB")
            ctx.close()
        br.close()
    httpd.shutdown()


if __name__ == "__main__":
    main(sys.argv[1])
