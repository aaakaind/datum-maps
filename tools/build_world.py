"""DATUM world-view builder.

Turns Natural Earth (public domain) GeoJSON into data/world.json for the
Equal Earth world view in index.html.

  python3 tools/build_world.py <dir with Natural Earth .geojson files> data/world.json

Inputs (from https://github.com/nvkelso/natural-earth-vector/tree/master/geojson):
  ne_50m_land.geojson
  ne_50m_admin_0_boundary_lines_land.geojson
  ne_50m_lakes.geojson
  ne_110m_admin_0_countries.geojson

Output: coordinates quantized to 1/Q degree, each ring or line delta-encoded as
[x0, y0, dx1, dy1, ...]. Rings are oriented clockwise, the winding d3-geo expects
for an exterior ring. Borders carry a kind: 0 international, 1 disputed or
otherwise unsettled (drawn dashed).
"""
import json
import sys
from pathlib import Path

from shapely.geometry import shape, Polygon, MultiPolygon
from shapely.geometry.polygon import orient

Q = 20                 # 1/20 degree = 0.05 degree, about 5.5 km at the equator
LAND_TOL = 0.04        # simplification tolerance in degrees
MIN_ISLAND = 0.05      # drop land polygons smaller than this many square degrees
MIN_LAKE = 0.6         # keep lakes larger than this many square degrees
LABEL_MAX_RANK = 6
# Natural Earth labels some de facto entities that OpenStreetMap, the source of the
# rest of DATUM, does not map as countries. Leave their names out for consistency;
# their boundary lines stay, drawn dashed.
LABEL_SKIP = {"Somaliland", "N. Cyprus"}


def enc(coords):
    out, px, py = [], None, None
    for lon, lat in coords:
        x, y = round(lon * Q), round(lat * Q)
        if px is None:
            out += [x, y]
        elif (x, y) != (px, py):
            out += [x - px, y - py]
        else:
            continue
        px, py = x, y
    return out


def polys(g):
    if isinstance(g, Polygon):
        return [g]
    if isinstance(g, MultiPolygon):
        return list(g.geoms)
    return []


def rings(p):
    p = orient(p, sign=-1.0)  # exterior clockwise, holes counter-clockwise
    out = []
    for r in [p.exterior, *p.interiors]:
        pts = list(r.coords)[:-1]
        e = enc(pts)
        if len(e) >= 6:
            out.append(e)
    return out


def main(src, dst):
    src = Path(src)
    load = lambda n: json.load(open(src / f"{n}.geojson", encoding="utf-8"))["features"]

    land = []
    for f in load("ne_50m_land"):
        for p in polys(shape(f["geometry"])):
            if p.area < MIN_ISLAND:
                continue
            s = p.simplify(LAND_TOL, preserve_topology=True)
            for q in polys(s):
                r = rings(q)
                if r:
                    land.append(r)

    lakes = []
    for f in load("ne_50m_lakes"):
        for p in polys(shape(f["geometry"])):
            if p.area < MIN_LAKE:
                continue
            r = rings(p.simplify(LAND_TOL, preserve_topology=True))
            if r:
                lakes.append(r)

    borders = []
    for f in load("ne_50m_admin_0_boundary_lines_land"):
        cls = f["properties"].get("FEATURECLA", "")
        kind = 0 if cls.startswith("International") else 1
        g = shape(f["geometry"]).simplify(LAND_TOL)
        lines = [g] if g.geom_type == "LineString" else list(getattr(g, "geoms", []))
        for ln in lines:
            e = enc(list(ln.coords))
            if len(e) >= 4:
                borders.append([kind, e])

    labels = []
    for f in load("ne_110m_admin_0_countries"):
        p = f["properties"]
        rank = p.get("LABELRANK") or 9
        name = p.get("NAME") or p.get("NAME_EN")
        if rank > LABEL_MAX_RANK or name in LABEL_SKIP:
            continue
        if name == "United States of America":
            name = "United States"
        labels.append([name, round(p["LABEL_X"], 2), round(p["LABEL_Y"], 2), rank])
    labels.sort(key=lambda r: r[3])

    out = {
        "source": "Natural Earth (public domain), naturalearthdata.com",
        "note": "De facto boundaries as published by Natural Earth; kind 1 = disputed or unsettled. "
                "Names and lines do not imply endorsement.",
        "q": Q, "land": land, "lakes": lakes, "borders": borders, "labels": labels,
    }
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    Path(dst).write_text(json.dumps(out, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    print(f"land {len(land)} polygons, lakes {len(lakes)}, borders {len(borders)} "
          f"({sum(1 for b in borders if b[0])} disputed), labels {len(labels)} -> "
          f"{Path(dst).stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
