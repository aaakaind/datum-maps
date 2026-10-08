"""DATUM region-pack builder (v0.2).

Reads Overture Maps GeoJSON extracts (release 2026-09-23.1) for one bbox and
writes compact, quantized JSON packs the browser app decodes:

  roads.json      routable network (nodes + edges) that also renders as streets
  areas.json      land use, land cover, water, rail, neighbourhood labels
  buildings.json  footprints (+ height where known)
  places.json     points of interest for search and the place card

Coordinates are integers: round((lon - X0) * Q), round((lat - Y0) * Q).
Lines and rings are [x0, y0, dx1, dy1, dx2, dy2, ...] (first point absolute).
"""
import json, math, sys, collections
from pathlib import Path
from shapely.geometry import shape, box, LineString, Polygon, MultiPolygon, MultiLineString, GeometryCollection, Point
from shapely.validation import make_valid

RAW = Path("raw")    # overturemaps GeoJSON extracts, relative to where you run it
OUT = Path("pack")   # pack JSON is written here
OUT.mkdir(exist_ok=True)

RELEASE = "2026-09-23.1"
BBOX = (-75.74, 45.38, -75.65, 45.445)
X0, Y0 = BBOX[0], BBOX[1]
Q = 100000  # 1e-5 deg ~ 0.8 m E-W, 1.1 m N-S at this latitude
CLIP = box(*BBOX)
CLIP_PAD = box(BBOX[0] - 0.004, BBOX[1] - 0.003, BBOX[2] + 0.004, BBOX[3] + 0.003)

M_PER_DEG_Y = 111_320.0
M_PER_DEG_X = 111_320.0 * math.cos(math.radians(45.41))


def load(name):
    return json.load(open(RAW / f"{name}.geojson"))["features"]


def qx(lon):
    return round((lon - X0) * Q)


def qy(lat):
    return round((lat - Y0) * Q)


def enc_line(coords):
    """coords: list of (lon,lat) -> flat delta-encoded int list, dropping repeated points."""
    out, px, py = [], None, None
    for lon, lat in coords:
        x, y = qx(lon), qy(lat)
        if px is None:
            out += [x, y]
        else:
            if x == px and y == py:
                continue
            out += [x - px, y - py]
        px, py = x, y
    return out


def enc_ring(coords):
    pts = list(coords)
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts = pts[:-1]
    r = enc_line(pts)
    return r if len(r) >= 6 else None


def polys(geom):
    if geom.is_empty:
        return []
    if isinstance(geom, Polygon):
        return [geom]
    if isinstance(geom, MultiPolygon):
        return list(geom.geoms)
    if isinstance(geom, GeometryCollection):
        out = []
        for g in geom.geoms:
            out += polys(g)
        return out
    return []


def lines(geom):
    if geom.is_empty:
        return []
    if isinstance(geom, LineString):
        return [geom]
    if isinstance(geom, MultiLineString):
        return list(geom.geoms)
    if isinstance(geom, GeometryCollection):
        out = []
        for g in geom.geoms:
            out += lines(g)
        return out
    return []


def enc_polygon(p, tol):
    p = p.simplify(tol, preserve_topology=True)
    if p.is_empty or not isinstance(p, Polygon):
        return None
    ext = enc_ring(p.exterior.coords)
    if not ext:
        return None
    rings = [ext]
    for hole in p.interiors:
        h = enc_ring(hole.coords)
        if h:
            rings.append(h)
    return rings


class Strings:
    def __init__(self):
        self.items, self.idx = [], {}

    def __call__(self, s):
        if not s:
            return -1
        if s not in self.idx:
            self.idx[s] = len(self.items)
            self.items.append(s)
        return self.idx[s]


def primary_name(p):
    n = p.get("names") or {}
    return n.get("primary")


# ---------------------------------------------------------------- roads + graph
ROAD_CLASSES = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
                "residential", "living_street", "service", "pedestrian", "footway", "steps",
                "path", "cycleway", "track", "unknown"]
CAR_SPEED = {"motorway": 90, "trunk": 70, "primary": 50, "secondary": 50, "tertiary": 40,
             "unclassified": 40, "residential": 30, "living_street": 10, "service": 15}
CAR_OK = set(CAR_SPEED)
FOOT_NO = {"motorway", "trunk"}
BIKE_NO = {"motorway", "trunk", "steps"}


def seg_len_m(a, b):
    return math.hypot((b[0] - a[0]) * M_PER_DEG_X, (b[1] - a[1]) * M_PER_DEG_Y)


def access(props, cls):
    """Return (car_fwd, car_back, bike_fwd, bike_back, foot) for the whole segment.
    Rules with partial `between` ranges or conditional `during/using/recognized/vehicle`
    clauses are not applied in v0 (documented limitation)."""
    car = cls in CAR_OK
    bike = cls not in BIKE_NO
    foot = cls not in FOOT_NO
    car_back = bike_back = True
    rules = props.get("access_restrictions") or []
    # denials first, then allowances override
    for phase in ("denied", "allowed"):
        for r in rules:
            t = r.get("access_type")
            if (phase == "denied") != (t == "denied"):
                continue
            if r.get("between"):
                continue
            w = r.get("when") or {}
            if w.get("during") or w.get("using") or w.get("recognized") or w.get("vehicle"):
                continue
            modes = set(w.get("mode") or [])
            heading = w.get("heading")
            hits_car = not modes or modes & {"motor_vehicle", "car", "vehicle"}
            hits_bike = not modes or modes & {"bicycle", "vehicle"}
            hits_foot = not modes or "foot" in modes
            val = (t != "denied")
            if heading == "backward":
                if t == "denied":
                    # OSM oneway: applies to vehicles, never pedestrians
                    if hits_car:
                        car_back = False
                    if hits_bike:
                        bike_back = False
                else:
                    if modes and "bicycle" in modes:
                        bike_back = True
                continue
            if heading == "forward":
                continue  # rare; skip in v0
            if t != "denied" and not modes:
                continue  # unconditional "allowed" with no mode: keep class defaults
            if hits_car:
                car = val
            if hits_bike:
                bike = val
            if hits_foot:
                foot = val
    car_back = car and car_back
    bike_back = bike and bike_back
    return car, car_back, bike, bike_back, foot


def car_speed(props, cls):
    for sl in props.get("speed_limits") or []:
        if sl.get("between"):
            continue
        mx = sl.get("max_speed") or {}
        v, unit = mx.get("value"), mx.get("unit")
        if v:
            return round(v * 1.609) if unit == "mph" else int(v)
    return CAR_SPEED.get(cls, 0)


def build_roads():
    segs = load("segment")
    names = Strings()
    node_xy = {}     # connector_id -> (x, y) quantized
    node_index = {}  # connector_id -> int
    nodes = []
    edges = []
    rail = []
    meta = []  # (edge index, LineString lon/lat, class, subclass, name)
    stats = collections.Counter()

    def node(cid, lon, lat):
        if cid not in node_index:
            node_index[cid] = len(nodes) // 2
            nodes.extend([qx(lon), qy(lat)])
            node_xy[cid] = (lon, lat)
        return node_index[cid]

    for f in segs:
        p = f["properties"]
        g = f["geometry"]
        if g["type"] != "LineString":
            continue
        coords = [tuple(c[:2]) for c in g["coordinates"]]
        geom = LineString(coords)
        if not geom.intersects(CLIP_PAD):
            continue
        st = p.get("subtype")
        cls = p.get("class") or "unknown"
        nm = primary_name(p)
        if st == "rail":
            rail.append([1 if cls == "light_rail" else 0, names(nm), enc_line(coords)])
            continue
        if st != "road":
            continue
        if cls not in ROAD_CLASSES:
            cls = "unknown"
        flags_list = set()
        for rf in p.get("road_flags") or []:
            if not rf.get("between"):
                flags_list |= set(rf.get("values") or [])
        if "is_under_construction" in flags_list:
            stats["skipped_construction"] += 1
            continue
        cf, cb, bf, bb, ft = access(p, cls)
        flags = (cf << 0) | (cb << 1) | (bf << 2) | (bb << 3) | (ft << 4) \
            | (("is_bridge" in flags_list) << 5) | (("is_tunnel" in flags_list) << 6) \
            | (("is_link" in flags_list) << 7)
        spd = car_speed(p, cls)

        # cumulative length along the line for placing connectors
        cum = [0.0]
        for i in range(1, len(coords)):
            cum.append(cum[-1] + seg_len_m(coords[i - 1], coords[i]))
        total = cum[-1] or 1e-9
        conns = sorted(p.get("connectors") or [], key=lambda c: c["at"])
        cut = []  # (vertex_index, connector_id)
        pts = list(coords)
        for c in conns:
            target = c["at"] * total
            # nearest existing vertex
            best = min(range(len(cum)), key=lambda i: abs(cum[i] - target))
            if abs(cum[best] - target) > 2.0:
                # insert an interpolated vertex
                j = next((i for i in range(1, len(cum)) if cum[i] >= target), len(cum) - 1)
                a, b = pts[j - 1], pts[j]
                span = (cum[j] - cum[j - 1]) or 1e-9
                t = (target - cum[j - 1]) / span
                np_ = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                pts.insert(j, np_)
                cum.insert(j, target)
                cut = [(vi + 1 if vi >= j else vi, cid) for vi, cid in cut]
                best = j
            cut.append((best, c["connector_id"]))
        cut.sort()
        # dedupe identical vertex indices
        dedup = []
        for vi, cid in cut:
            if dedup and dedup[-1][0] == vi:
                continue
            dedup.append((vi, cid))
        if len(dedup) < 2:
            stats["no_connectors"] += 1
            continue
        for (ia, ca), (ib, cb_) in zip(dedup, dedup[1:]):
            piece = pts[ia:ib + 1]
            na = node(ca, *piece[0])
            nb = node(cb_, *piece[-1])
            ax, ay = node_xy[ca]
            bx, by = node_xy[cb_]
            piece = [(ax, ay)] + piece[1:-1] + [(bx, by)]
            if len(piece) > 2:
                piece = list(LineString(piece).simplify(0.000004).coords)
            enc = enc_line(piece)
            # interior deltas only: drop the absolute first point and the final delta
            interior = enc[2:-2] if len(enc) > 4 else []
            sub = p.get("subclass")
            eflags = flags | ((sub == "crosswalk") << 9)
            edges.append([na, nb, ROAD_CLASSES.index(cls), names(nm), eflags, spd] + interior)
            meta.append((len(edges) - 1, LineString(piece), cls, sub, nm))
            stats["edges"] += 1

    # Sidewalks are mapped as unnamed footways. Give each one the name of the
    # parallel street it runs beside (within 30 m, bearing within 30 deg) so
    # walking directions read "Bank Street" instead of "footpath". Flag 256 marks
    # an inferred name so the map does not draw it as a label.
    from shapely.strtree import STRtree
    from shapely import affinity
    sx, sy = M_PER_DEG_X, M_PER_DEG_Y
    proj = lambda g: affinity.scale(g, xfact=sx, yfact=sy, origin=(X0, Y0))
    named = [(i, proj(g), nm) for i, g, cls, sub, nm in meta if nm and cls in CAR_SPEED and cls != "service"]
    tree = STRtree([g for _, g, _ in named])

    def brg(g):
        (x1, y1), (x2, y2) = g.coords[0], g.coords[-1]
        return math.degrees(math.atan2(x2 - x1, y2 - y1)) % 180

    for i, g, cls, sub, nm in meta:
        if nm or sub != "sidewalk":
            continue
        pg = proj(g)
        mid = pg.interpolate(0.5, normalized=True)
        best, bd = None, 30.0
        b0 = brg(pg)
        for j in tree.query(mid.buffer(30)):
            _, ng, nname = named[j]
            d = ng.distance(mid)
            if d >= bd:
                continue
            # local bearing of the street near this point
            t = ng.project(mid)
            a = ng.interpolate(max(0, t - 8)); b = ng.interpolate(min(ng.length, t + 8))
            b1 = math.degrees(math.atan2(b.x - a.x, b.y - a.y)) % 180
            diff = abs(b0 - b1); diff = min(diff, 180 - diff)
            if pg.length > 6 and diff > 30:
                continue
            best, bd = nname, d
        if best:
            edges[i][3] = names(best)
            edges[i][4] |= 256
            stats["sidewalk_named"] += 1
        else:
            stats["sidewalk_unmatched"] += 1

    print("roads:", dict(stats), "nodes:", len(nodes) // 2, "rail:", len(rail))
    return {
        "release": RELEASE, "bbox": BBOX, "q": Q, "origin": [X0, Y0],
        "classes": ROAD_CLASSES, "names": names.items,
        "nodes": nodes, "edges": edges, "rail": rail,
        "edge_fields": "a,b,class,name,flags(1 carF,2 carB,4 bikeF,8 bikeB,16 foot,32 bridge,64 tunnel,128 link,256 sidewalk-name-inferred,512 crosswalk),carKmh,...interior deltas",
    }


# ---------------------------------------------------------------- areas
AREA_KINDS = ["green", "wood", "pitch", "plaza", "campus", "water", "wetland", "cemetery"]


def area_kind(theme, p):
    st, cl = p.get("subtype"), p.get("class")
    if theme == "water":
        if cl == "swimming_pool":
            return "water"
        return "water"
    if theme == "land":
        if st == "forest" or cl in ("wood", "forest"):
            return "wood"
        if st in ("shrub", "grass") or cl in ("scrub", "grass", "meadow"):
            return "green"
        if st == "wetland":
            return "wetland"
        return None
    # land_use
    if cl in ("pitch", "track", "stadium", "sports_centre"):
        return "pitch"
    if st == "pedestrian" or cl in ("pedestrian", "plaza"):
        return "plaza"
    if st == "cemetery" or cl in ("cemetery", "grave_yard"):
        return "cemetery"
    if st in ("education", "medical") or cl in ("university", "college", "school", "hospital", "clinic"):
        return "campus"
    if st in ("park", "managed", "horticulture", "recreation", "protected", "golf") or cl in (
            "park", "grass", "garden", "playground", "recreation_ground", "village_green", "flowerbed",
            "golf_course", "meadow", "dog_park", "nature_reserve"):
        return "green"
    return None


def build_areas():
    names = Strings()
    areas, waterlines, labels = [], [], []
    stats = collections.Counter()
    for theme in ("land", "land_use", "water"):
        for f in load(theme):
            p = f["properties"]
            k = area_kind(theme, p)
            if not k:
                continue
            g = shape(f["geometry"])
            if g.geom_type == "Point":
                continue
            if not g.is_valid:
                g = make_valid(g)
            g = g.intersection(CLIP)
            nm = primary_name(p)
            if g.geom_type in ("LineString", "MultiLineString") or (g.geom_type == "GeometryCollection" and lines(g)):
                if theme == "water":
                    for ln in lines(g):
                        e = enc_line(list(ln.simplify(0.00001).coords))
                        if len(e) >= 4:
                            waterlines.append([names(nm), e])
                            stats["waterline"] += 1
            for poly in polys(g):
                if poly.area * M_PER_DEG_X * M_PER_DEG_Y < 30:
                    continue
                r = enc_polygon(poly, 0.000008)
                if r:
                    areas.append([AREA_KINDS.index(k), names(nm), r])
                    stats[k] += 1
                    if nm and k in ("water", "green", "campus") and poly.area * M_PER_DEG_X * M_PER_DEG_Y > 20000:
                        c = poly.representative_point()
                        labels.append([AREA_KINDS.index(k), names(nm), qx(c.x), qy(c.y)])
    hoods = []
    for f in load("division"):
        p = f["properties"]
        if p.get("subtype") not in ("neighborhood", "macrohood", "locality"):
            continue
        lon, lat = f["geometry"]["coordinates"][:2]
        if not CLIP.contains(Point(lon, lat)):
            continue
        rank = {"locality": 0, "macrohood": 1, "neighborhood": 2}[p["subtype"]]
        hoods.append([rank, names(primary_name(p)), qx(lon), qy(lat)])
    # largest water/green areas first so smaller sit on top
    areas.sort(key=lambda a: -len(a[2][0]))
    print("areas:", dict(stats), "labels:", len(labels), "hoods:", len(hoods))
    return {"release": RELEASE, "q": Q, "origin": [X0, Y0], "kinds": AREA_KINDS,
            "names": names.items, "areas": areas, "waterlines": waterlines,
            "labels": labels, "hoods": hoods}


# ---------------------------------------------------------------- buildings
def build_buildings():
    out = []
    stats = collections.Counter()
    for f in load("building"):
        p = f["properties"]
        g = shape(f["geometry"])
        if not g.intersects(CLIP):
            continue
        if not g.is_valid:
            g = make_valid(g)
        h = p.get("height")
        if not h and p.get("num_floors"):
            h = p["num_floors"] * 3.2
        h = int(round(h)) if h else 0
        for poly in polys(g):
            r = enc_polygon(poly, 0.0000035)
            if r:
                out.append([h] + r)
                stats["with_height" if h else "no_height"] += 1
    print("buildings:", len(out), dict(stats))
    return {"release": RELEASE, "q": Q, "origin": [X0, Y0], "buildings": out,
            "fields": "height_m(0=unknown), ring0, ring1..."}


# ---------------------------------------------------------------- places
def nice(cat):
    return cat.replace("_", " ").capitalize() if cat else ""


def build_places():
    cats, groups = Strings(), Strings()
    out = []
    stats = collections.Counter()
    for f in load("place"):
        p = f["properties"]
        nm = primary_name(p)
        if not nm:
            continue
        if p.get("operating_status") == "permanently_closed":
            stats["closed"] += 1
            continue
        conf = p.get("confidence") or 0
        if conf < 0.5:
            stats["low_conf"] += 1
            continue
        lon, lat = f["geometry"]["coordinates"][:2]
        if not CLIP.contains(Point(lon, lat)):
            continue
        tax = p.get("taxonomy") or {}
        hier = tax.get("hierarchy") or []
        grp = hier[0] if hier else "other"
        cat = p.get("basic_category") or (hier[-1] if hier else "")
        addr = ""
        a = (p.get("addresses") or [None])[0]
        if a:
            addr = ", ".join(x for x in [a.get("freeform"), a.get("locality")] if x)
        phone = (p.get("phones") or [""])[0] or ""
        web = (p.get("websites") or [""])[0] or ""
        out.append([qx(lon), qy(lat), nm, cats(nice(cat)), groups(grp), addr, phone, web, round(conf * 100)])
        stats["kept"] += 1
    print("places:", dict(stats))
    return {"release": RELEASE, "q": Q, "origin": [X0, Y0], "cats": cats.items, "groups": groups.items,
            "places": out, "fields": "x,y,name,cat,group,address,phone,website,confidence%"}


if __name__ == "__main__":
    for fname, fn in (("roads", build_roads), ("areas", build_areas),
                      ("buildings", build_buildings), ("places", build_places)):
        data = fn()
        path = OUT / f"{fname}.json"
        path.write_text(json.dumps(data, separators=(",", ":"), ensure_ascii=False))
        print(f"  -> {path.name}: {path.stat().st_size / 1e6:.2f} MB")
