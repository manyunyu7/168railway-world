"""168Railway: Indonesia's whole passenger rail timetable from GAPEKA 2026 as `trips` CSVs for realnet (`indo`).

    python pipeline/gapeka_indo.py [out-dir]

Sources (all GAPEKA 2026, in force from 1 December 2026):
  - Java: KP-DJKA 116/2026, data/raw/gapeka2026/gapeka-jawa-2026.json (168Railway's parse of its Daftar Waktu):
    KRL Commuter Line, local and intercity trains; freight is left out.
  - Sumatra: KP-DJKA 117 (Sumatera Bagian Utara), 118 (Barat) and 119 (Selatan)/2026,
    data/raw/gapeka2026/gapeka-sumatra-2026.json (the same parse, as the GAPEKA 2026 pages on 168railway.com use).
Stations' positions: data/raw/gapeka2026/stations.tsv (168Railway's station table, code/name/lon/lat), then
data/raw/gapeka2026/stations-luar-jawa.json for any code missing there. Station codes are national, so a station
is one key in every file and the KRL, local and intercity lines meet at it.

Three files, one realnet source each (they differ in how their trains an hour are counted):
  indo_krl_jabo_trips.csv  Jabodetabek's KRL (the `jak` routes and colours; counted by hour as `jak` is)
  indo_krl_trips.csv       every other KRL service (Bandung Raya, Yogyakarta, Jenggala, ...)
  indo_rail_trips.csv      local and intercity trains of Java and Sumatra. A train along one of the corridor families
                           of pipeline/indo_families.json rides that family's line (route "ka_fam_<id>"), cut to its
                           path and stopping where the family's line stops; any other train has a route per pair of
                           end cities (every
                           train between them, whatever its name, both ways; realnet keeps its main stopping patterns
                           and gives the rest of its trains to them). The cities: the registry's nearest within
                           CITY_KM of the end station, else the station. Route ids start "ka_" (intercity) or "lok_"
                           (local), which the line colours read.

A pass-through is no stop; a stop with no time gets one by distance between the timed stops either side (as
gapeka_jak.py). Direction: "0" if the first station's code sorts before the last's, else "1".
"""
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gapeka_jak import SERVICES as JABO, metres, trip_rows  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw", "gapeka2026")
OUT = os.environ.get("REALNET_OUT", os.path.join(ROOT, "data", "work", "realnet"))

CITY_KM = 30.0
REGISTRY = os.path.join(ROOT, "app", "src", "game", "registry.json")


def city_namer(st):
    """A station code -> the registry city (id, name) its station is in reach of, else the station's own."""
    import math
    cities = [c for c in json.load(open(REGISTRY, encoding="utf-8"))["cities"] if c.get("country") == "ID"]

    def of(code):
        lon, lat = float(st[code]["lon"]), float(st[code]["lat"])
        best = None
        for c in cities:
            d = math.hypot((c["center"][0] - lon) * math.cos(math.radians(lat)), c["center"][1] - lat) * 111.32
            if d <= CITY_KM and (best is None or d < best[0]):
                best = (d, c["id"], c["name"])
        return (best[1], best[2]) if best else (code.lower(), st[code]["name"])
    return of


def slug(s):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")


def load_stations():
    st = {r["code"]: {"name": r["name"], "lon": r["lon"], "lat": r["lat"]}
          for r in csv.DictReader(open(os.path.join(RAW, "stations.tsv"), encoding="utf-8"), delimiter="\t")}
    p = os.path.join(RAW, "stations-luar-jawa.json")
    if os.path.exists(p):
        for r in json.load(open(p, encoding="utf-8")):
            st.setdefault(r["station_code"], {"name": r.get("name") or r["station_code"], "lon": r["longitude"], "lat": r["latitude"]})
    return st


def norm_stops(t):
    """Sumatra's parse names its fields a little differently; both as gapeka_jak.trip_rows reads them."""
    return {**t, "stops": [{**s, "is_pass_through": bool(s.get("is_pass_through"))} for s in t["stops"]]}


def family_paths(fams, trains):
    """Each family's path: the densest run of stations (stops and pass-throughs) any train takes from `from` to `to`
    through `via`."""
    chains = [[x["station_code"] for x in t["stops"]] for t in trains if t.get("train_type") != "freight"]
    out = {}
    for f in fams:
        best = None
        for c in chains:
            for seq in (c, c[::-1]):
                if f["from"] in seq and f["to"] in seq:
                    i, j = seq.index(f["from"]), seq.index(f["to"])
                    if i < j and (not f.get("via") or f["via"] in seq[i:j + 1]) and (best is None or j - i + 1 > len(best)):
                        best = seq[i:j + 1]
        if best:
            out[f["id"]] = best
        else:
            print(f"  family {f['id']}: no train runs {f['from']} - {f['to']}")
    return out


def all_rows(t, st):
    """Every station of a train with a time, pass-throughs too (GAPEKA times them), in its order."""
    return trip_rows({**t, "stops": [{**x, "is_pass_through": False} for x in t["stops"]]}, st)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    os.makedirs(out, exist_ok=True)
    st = load_stations()
    city = city_namer(st)
    java = json.load(open(os.path.join(RAW, "gapeka-jawa-2026.json"), encoding="utf-8"))["trains"]
    sumatra = json.load(open(os.path.join(RAW, "gapeka-sumatra-2026.json"), encoding="utf-8"))["trains"]
    fam_cfg = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "indo_families.json"), encoding="utf-8"))
    paths = family_paths(fam_cfg["families"], java + sumatra)
    fam_name = {f["id"]: f["name"] for f in fam_cfg["families"]}
    fam_trips = {k: [] for k in paths}  # id -> [(train, rows of every station on the path)]
    files = {k: [] for k in ("krl_jabo", "krl", "rail")}
    skipped = []
    for island, trains in (("jawa", java), ("sumatera", sumatra)):
        for t in trains:
            ty = t.get("train_type")
            if ty == "freight":
                continue
            t = norm_stops(t)
            missing = [s["station_code"] for s in t["stops"] if not s["is_pass_through"] and s["station_code"] not in st]
            if missing:
                skipped.append((t["train_number"], missing))
                continue
            rows = trip_rows(t, st)
            if len(rows) < 2:
                skipped.append((t["train_number"], "fewer than two timed stops"))
                continue
            name = t["train_name"]
            if ty == "krl" and name in JABO:
                route, shown = JABO[name]
                files["krl_jabo"].append((route, shown, t["train_number"], rows))
            elif ty == "krl":
                files["krl"].append((slug(name), name, t["train_number"], rows))
            else:
                codes = [x["station_code"] for x in t["stops"]]
                fit = max(((len(set(codes) & set(pth)) / len(set(codes)), fid) for fid, pth in paths.items()), default=(0.0, None))
                # local trains (Feeder Whoosh, Batara Kresna, ...) keep lines of their own
                if ty == "intercity" and fit[0] >= fam_cfg["share"]:
                    on = set(paths[fit[1]])
                    full = [r for r in all_rows(t, st) if r[0]["station_code"] in on]
                    if len(full) >= 2:
                        stops = {x["station_code"] for x in t["stops"] if not x["is_pass_through"]}
                        fam_trips[fit[1]].append((t["train_number"], full, stops))
                        continue
                a, b = city(rows[0][0]["station_code"]), city(rows[-1][0]["station_code"])
                if a[0] > b[0]:
                    a, b = b, a
                kind = "lok" if ty == "local" else "ka"
                shown = f"KA {a[1]} - {b[1]}" if a[0] != b[0] else f"KA {a[1]}"
                files["rail"].append((f"{kind}_{a[0]}_{b[0]}", shown, t["train_number"], rows))
    # a family's line stops where a share of the trains that run past the station stop there, and at its two ends
    for fid, trips in fam_trips.items():
        if not trips:
            continue
        pth = paths[fid]
        ran, stopped = {}, {}
        for _, full, stops in trips:
            for s_, _, _ in full:
                c = s_["station_code"]
                ran[c] = ran.get(c, 0) + 1
                stopped[c] = stopped.get(c, 0) + (c in stops)
        keep = {c for c in ran if stopped[c] / ran[c] >= fam_cfg["stop_share"]} | {pth[0], pth[-1]}
        n = 0
        for number, full, _ in trips:
            rows = [r for r in full if r[0]["station_code"] in keep]
            # a train starting or ending off the line's stops starts or ends at the nearest one inside its run
            if len(rows) >= 2:
                # direction along the family's path, so every train's run is a piece of the one line either way
                d = "0" if pth.index(rows[0][0]["station_code"]) < pth.index(rows[-1][0]["station_code"]) else "1"
                files["rail"].append((f"ka_fam_{fid}", fam_name[fid], number, rows, d))
                n += 1
        # the line itself: one run over every stop of the path, so each train's run is a piece of it whatever its ends
        # (no train runs the whole of some families, and two pieces from either end would be two lines). Its hop times are
        # the trains' median between the stops, else 60 km/h; it adds one train a day to the family's count.
        order = [c for c in pth if c in keep]
        hop = {}
        for _, full, _ in trips:
            at = {r[0]["station_code"]: r[2] for r in full}
            for a, b in zip(order, order[1:]):
                if a in at and b in at:
                    hop.setdefault((a, b), []).append(abs(at[b] - at[a]))
        t, rows = 10 * 3600, []  # in the day window realnet counts patterns in
        for i, c in enumerate(order):
            if i:
                a = order[i - 1]
                dt = sorted(hop[(a, order[i])])[len(hop[(a, order[i])]) // 2] if hop.get((a, order[i])) else \
                    metres((float(st[a]["lon"]), float(st[a]["lat"])), (float(st[c]["lon"]), float(st[c]["lat"]))) / (60 / 3.6)
                t += max(60, int(dt))
            rows.append(({"station_code": c}, (float(st[c]["lon"]), float(st[c]["lat"])), t))
        files["rail"].append((f"ka_fam_{fid}", fam_name[fid], f"line-{fid}", rows, "0"))
        print(f"family {fid}: {n} trains, {len(order)} stops on a path of {len(pth)} stations")
    for k, trips in files.items():
        p = os.path.join(out, f"indo_{k}_trips.csv")
        with open(p, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["route", "direction", "trip", "stop", "name", "lon", "lat", "time", "route_name"])
            for route, shown, number, rows, *dirn in trips:
                first, last = rows[0][0]["station_code"], rows[-1][0]["station_code"]
                direction = dirn[0] if dirn else ("0" if first <= last else "1")
                for s, (lon, lat), v in rows:
                    w.writerow([route, direction, number, s["station_code"], st[s["station_code"]]["name"],
                                f"{lon:.6f}", f"{lat:.6f}", v, shown])
        print(f"{k}: {len(trips)} trips, {len(set(r for r, *_ in trips))} routes -> {p}")
    if skipped:
        print(f"skipped {len(skipped)} trains: {skipped[:10]}")


if __name__ == "__main__":
    main()
