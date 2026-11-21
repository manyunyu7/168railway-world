"""168Railway: single or double track per lintas for the `indo` template, from pipeline/indo_tracks.json.

    python pipeline/indo_tracks.py [city]      # after realcorridors.py, before realsave

Each lintas in the table runs between two GAPEKA station codes (and through `via` when given) along the stations a
GAPEKA 2026 train passes between them, stops and pass-throughs alike: that chain of stations, buffered BUFFER_M, is
the lintas. A model edge of `<city>_real_corridors.json` whose track lies at least INSIDE of its length in a
lintas takes that lintas' track count; Jakarta's MRT and LRT (the `osm` feed) are always double; every other edge
gets `default_tracks` (single). The count is written into the model edge as `tracks` (realsave reads it; 2 when
absent), so one lintas is the same along its whole length.

OSM is a cross-check only: each corridor piece's parallel OSM chains (`tracks`, realcorridors.py) against the table;
the km where they disagree are printed per lintas.
"""
import csv
import json
import os
import sys
from collections import defaultdict

import numpy as np
from shapely.geometry import LineString, Point
from shapely.prepared import prep

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import realnet  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(realnet.ROOT, "data", "raw", "gapeka2026")
BUFFER_M = 2000.0
INSIDE = 0.7
STEP_M = 100.0


def stations():
    st = {r["code"]: (float(r["lon"]), float(r["lat"])) for r in csv.DictReader(open(os.path.join(RAW, "stations.tsv"), encoding="utf-8"), delimiter="\t")}
    p = os.path.join(RAW, "stations-luar-jawa.json")
    if os.path.exists(p):
        for r in json.load(open(p, encoding="utf-8")):
            st.setdefault(r["station_code"], (float(r["longitude"]), float(r["latitude"])))
    return st


def chains():
    """Every passenger train's full station sequence (stops and pass-throughs)."""
    out = []
    for f in ("gapeka-jawa-2026.json", "gapeka-sumatra-2026.json"):
        for t in json.load(open(os.path.join(RAW, f), encoding="utf-8"))["trains"]:
            if t.get("train_type") != "freight":
                out.append([s["station_code"] for s in t["stops"]])
    return out


def lintas_codes(row, trains):
    """The densest run of stations from `from` to `to` (through `via`) any train takes, in that order."""
    a, b, via = row["from"], row["to"], row.get("via")
    best = None
    for c in trains:
        for seq in (c, c[::-1]):
            if a in seq and b in seq:
                i, j = seq.index(a), seq.index(b)
                if i < j:
                    run = seq[i:j + 1]
                    if via and via not in run:
                        continue
                    if best is None or len(run) > len(best):
                        best = run
    return best


def edge_poly(m, e):
    pts = [m["nodes"][e["a"]]["xy"]]
    if e.get("lead_a") is not None:
        pts.append(e["lead_a"])
        pts += e["line"]
        pts.append(e["lead_b"])
    pts.append(m["nodes"][e["b"]]["xy"])
    return np.array(pts, float)


def samples(poly):
    seg = np.hypot(*np.diff(poly, axis=0).T)
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    L = cum[-1]
    if L <= 0:
        return [poly[0]], 0.0
    ds = np.arange(STEP_M / 2, L, STEP_M) if L > STEP_M else np.array([L / 2])
    return np.stack([np.interp(ds, cum, poly[:, 0]), np.interp(ds, cum, poly[:, 1])], 1), L


def main():
    city = sys.argv[1] if len(sys.argv) > 1 else "indo"
    cfg = realnet.CITIES[city]
    o = realnet.origin(cfg["pack"])
    table = json.load(open(os.path.join(HERE, "indo_tracks.json"), encoding="utf-8"))
    st, trains = stations(), chains()
    zones = []  # (row, prepared buffer)
    for row in table["lintas"]:
        codes = lintas_codes(row, trains)
        if not codes:
            print(f"  no train runs {row['from']} - {row['to']} ({row['name']}): left out")
            continue
        xy = [realnet.to_xy(*st[c], o) for c in codes if c in st]
        line = LineString([(float(x), float(y)) for x, y in xy])
        zones.append((row, prep(line.buffer(BUFFER_M)), line.length / 1000.0))
    p = realnet.out_or_work(f"{city}_real_corridors.json")
    corr = json.load(open(p, encoding="utf-8"))
    m = corr["model"]
    net = json.load(open(cfg["out"], encoding="utf-8"))
    feed_of = [l["feed"] for l in net["lines"]]
    lines_on = defaultdict(set)
    for li, ml in enumerate(m["lines"]):
        if ml is None:
            continue
        for v in ml["path"]:
            lines_on[v >> 1].add(li)

    def classify(pts):
        best = None
        for row, z, _ in zones:
            share = np.mean([z.contains(Point(float(x), float(y))) for x, y in pts])
            if share >= INSIDE and (best is None or share > best[1]):
                best = (row, share)
        return best[0] if best else None

    km = defaultdict(float)
    by_lintas = defaultdict(float)
    for ei, e in enumerate(m["edges"]):
        pts, L = samples(edge_poly(m, e))
        if lines_on[ei] and all(feed_of[li] == "osm" for li in lines_on[ei]):
            e["tracks"] = 2
            km["metro/LRT (double)"] += L / 1000
            continue
        row = classify(pts)
        e["tracks"] = row["tracks"] if row else table["default_tracks"]
        km["double" if e["tracks"] == 2 else "single"] += L / 1000
        if row:
            by_lintas[row["name"]] += L / 1000
    json.dump(corr, open(p, "w"))
    print(f"track: " + ", ".join(f"{k} {v:.0f} km" for k, v in km.items()) + f" -> {p}")
    for row, _, length in zones:
        print(f"  {by_lintas[row['name']]:6.0f} km of {length:5.0f} km  {row['name']}{'  (check)' if row.get('check') else ''}")
    # cross-check against OSM's parallel chains, by piece
    dis = defaultdict(lambda: [0.0, 0.0])  # lintas or "rest" -> [we double / OSM one chain, we single / OSM two+]
    for P in corr["pieces"]:
        if P.get("off_graph") or all(feed_of[li] == "osm" for pair in P.get("lines", []) for li in pair):
            continue
        poly = np.array(P["line"], float)
        if len(poly) < 2:
            continue
        pts, L = samples(poly)
        row = classify(pts)
        ours = row["tracks"] if row else table["default_tracks"]
        osm = P.get("tracks", 1)
        key = row["name"] if row else "(not in the table: single)"
        if ours == 2 and osm < 2:
            dis[key][0] += L / 1000
        elif ours == 1 and osm >= 2:
            dis[key][1] += L / 1000
    print("OSM disagrees (km; table double but OSM one chain | table single but OSM two or more):")
    for k, (a, b) in sorted(dis.items(), key=lambda kv: -sum(kv[1])):
        if a + b >= 1:
            print(f"  {a:6.0f} | {b:6.0f}  {k}")


if __name__ == "__main__":
    main()
