#!/usr/bin/env python3
"""Build data/regions.json: the offline download catalog.

Every country and territory (simplified outline, so tiles over open sea are
skipped), every state or province (bounding box) and 7,000+ cities (point and
population), from Natural Earth, which is in the public domain.

    pip install shapely
    B=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
    mkdir -p ne && for f in ne_50m_admin_0_countries ne_10m_admin_1_states_provinces ne_10m_populated_places_simple; do
      curl -sL -o ne/$f.geojson $B/$f.geojson
    done
    python3 tools/build_regions.py ne data/regions.json
"""
import json, sys
from shapely.geometry import shape

Q = 100  # 0.01 degree grid


def load(d, name):
    with open(f"{d}/{name}.geojson", encoding="utf-8") as f:
        return json.load(f)["features"]


def rings(geom, tol=0.04, min_area=0.02):
    """Outer rings only, simplified and delta-encoded; tiny islands dropped unless they are all there is."""
    g = shape(geom)
    polys = list(g.geoms) if g.geom_type == "MultiPolygon" else [g]
    polys.sort(key=lambda p: -p.area)
    out = []
    for i, p in enumerate(polys):
        if i > 0 and p.area < min_area:
            continue
        s = p.simplify(tol, preserve_topology=True)
        if s.is_empty or s.geom_type != "Polygon":
            s = p.envelope
        pts = [(round(x * Q), round(y * Q)) for x, y in s.exterior.coords[:-1]]
        ded = [pts[0]] + [b for a, b in zip(pts, pts[1:]) if b != a]
        if len(ded) < 3:
            w, s_, e, n = (round(v * Q) for v in p.bounds)
            ded = [(w, s_), (e + 1, s_), (e + 1, n + 1), (w, n + 1)]
        flat = [ded[0][0], ded[0][1]]
        for a, b in zip(ded, ded[1:]):
            flat += [b[0] - a[0], b[1] - a[1]]
        out.append(flat)
    return out


def main(src, dst):
    conts = ["Africa", "Asia", "Europe", "North America", "Oceania", "South America", "Antarctica"]
    countries, index = [], {}
    feats = sorted(load(src, "ne_50m_admin_0_countries"), key=lambda f: f["properties"]["NAME_LONG"])
    for f in feats:
        p = f["properties"]
        cont = p["CONTINENT"]
        if cont == "Seven seas (open ocean)":
            cont = "Africa" if p["ADM0_A3"] in ("SYC", "MUS", "SHN", "ATF", "IOT") else "Asia" if p["ADM0_A3"] == "MDV" else "Oceania"
        if cont not in conts:
            continue
        index[p["ADM0_A3"]] = len(countries)
        countries.append([p["NAME_LONG"], p["ADM0_A3"], conts.index(cont), int(p.get("POP_EST") or 0), rings(f["geometry"])])

    regions = []
    for f in load(src, "ne_10m_admin_1_states_provinces"):
        p = f["properties"]
        name = p.get("name_en") or p.get("name")
        ci = index.get(p.get("adm0_a3"))
        if not name or ci is None or not f.get("geometry"):
            continue
        g = shape(f["geometry"])
        w, s, e, n = g.bounds
        if e - w > 180:  # crosses the antimeridian: keep the largest part
            w, s, e, n = max(g.geoms, key=lambda q: q.area).bounds
        if (e - w) * (n - s) < 1e-4:
            continue
        regions.append([name, ci, round(w, 2), round(s, 2), round(e, 2), round(n, 2)])
    # a country with a single region gains nothing from listing it
    per = {}
    for r in regions:
        per[r[1]] = per.get(r[1], 0) + 1
    regions = sorted((r for r in regions if per[r[1]] > 1), key=lambda r: (r[1], r[0]))

    cities = []
    for f in load(src, "ne_10m_populated_places_simple"):
        p = f["properties"]
        ci = index.get(p.get("adm0_a3"))
        if ci is None or not p.get("name"):
            continue
        cities.append([p["name"], ci, round(p["longitude"], 3), round(p["latitude"], 3), int(max(p.get("pop_max") or 0, 0))])
    cities.sort(key=lambda c: (c[1], -c[4]))

    out = {"q": Q, "source": "Natural Earth (public domain)", "continents": conts, "countries": countries, "regions": regions, "cities": cities}
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(countries)} countries, {len(regions)} regions, {len(cities)} cities")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
