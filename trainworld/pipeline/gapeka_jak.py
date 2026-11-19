"""168Railway: Jabodetabek's KRL Commuter Line timetable from GAPEKA 2026 as a `trips` CSV for realnet.

    python pipeline/gapeka_jak.py [gapeka.json] [stations.tsv] [out.csv]

GAPEKA 2026 (KP-DJKA 116 Tahun 2026, Jawa; in force from 1 December 2026), its Daftar Waktu as
168Railway's parser wrote it: per train its stops with arrival/departure times, pass-throughs
flagged. Stations' positions from 168Railway's station table (code, name, lon, lat), exported to a
TSV. Defaults: data/raw/gapeka2026/{gapeka-jawa-2026.json,stations.tsv} -> data/work/realnet/jak_krl_trips.csv.

Each KRL train of the Jabodetabek services below is one trip. Walahar (Cikarang-Purwakarta) is left
out: three of its stations are in the pack. GAPEKA numbers the airport service as two trains that
meet at Duri (Manggarai-Duri, Duri-Bandara), where it reverses; the game's lines do not turn back
partway, so they stay two lines. A pass-through is no stop; a stop
with no time (GAPEKA times only timing points) gets one by distance between the timed stops either
side. A trip crossing midnight keeps counting up past 24:00. Direction: "0" if the first station's
code sorts before the last's, else "1" (the line builder splits patterns by their stops anyway).
"""
import csv
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw", "gapeka2026")
OUT = os.environ.get("REALNET_OUT", os.path.join(ROOT, "data", "work", "realnet"))

# GAPEKA's train name -> (route id, the line's name in the game)
SERVICES = {
    "Commuter Line Bogor": ("B", "Commuter Line Bogor"),
    "Commuter Line Cikarang": ("C", "Commuter Line Cikarang"),
    "Commuter Line Rangkasbitung": ("R", "Commuter Line Rangkasbitung"),
    "Commuter Line Tangerang": ("T", "Commuter Line Tangerang"),
    "Commuter Line Tanjungpriuk": ("TP", "Commuter Line Tanjung Priok"),
    "Commuter Line BST": ("A", "Commuter Line Bandara Soekarno-Hatta"),
}


def secs(t):
    h, m, s = (int(x) for x in t.split(":"))
    return h * 3600 + m * 60 + s


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * math.cos(lat), b[1] - a[1]) * 111_320.0


def trip_rows(train, st):
    stops = [s for s in train["stops"] if not s["is_pass_through"]]
    pts = [(float(st[s["station_code"]]["lon"]), float(st[s["station_code"]]["lat"])) for s in stops]
    times = [s["departure_time"] or s["arrival_time"] for s in stops]
    known = []  # (index, seconds), counting up past midnight
    last = None
    for i, t in enumerate(times):
        if not t:
            continue
        v = secs(t)
        while last is not None and v < last:
            v += 86400
        known.append((i, v))
        last = v
    if len(known) < 2:
        return []
    cum = [0.0]
    for i in range(1, len(pts)):
        cum.append(cum[-1] + metres(pts[i - 1], pts[i]))
    out = [None] * len(stops)
    for (i0, t0), (i1, t1) in zip(known, known[1:]):
        for i in range(i0, i1 + 1):
            span = cum[i1] - cum[i0]
            out[i] = t0 + (t1 - t0) * ((cum[i] - cum[i0]) / span if span > 0 else (i - i0) / (i1 - i0))
    # before the first or after the last timed stop: no time to place it, the trip starts/ends there
    keep = [i for i in range(len(stops)) if out[i] is not None]
    return [(stops[i], pts[i], int(round(out[i]))) for i in keep]


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(RAW, "gapeka-jawa-2026.json")
    stp = sys.argv[2] if len(sys.argv) > 2 else os.path.join(RAW, "stations.tsv")
    dst = sys.argv[3] if len(sys.argv) > 3 else os.path.join(OUT, "jak_krl_trips.csv")
    st = {r["code"]: r for r in csv.DictReader(open(stp, encoding="utf-8"), delimiter="\t")}
    d = json.load(open(src, encoding="utf-8"))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    trips = []  # (route, name, train number, rows)
    for t in d["trains"]:
        if t.get("train_type") != "krl" or t["train_name"] not in SERVICES:
            continue
        route, name = SERVICES[t["train_name"]]
        rows = trip_rows(t, st)
        if len(rows) >= 2:
            trips.append([route, name, t["train_number"], rows])
    n_trips, per = 0, {}
    with open(dst, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["route", "direction", "trip", "stop", "name", "lon", "lat", "time", "route_name"])
        for route, name, number, rows in trips:
            first, last = rows[0][0]["station_code"], rows[-1][0]["station_code"]
            direction = "0" if first <= last else "1"
            for s, (lon, lat), v in rows:
                w.writerow([route, direction, number, s["station_code"], st[s["station_code"]]["name"],
                            f"{lon:.6f}", f"{lat:.6f}", v, name])
            n_trips += 1
            per[route] = per.get(route, 0) + 1
    print(f"GAPEKA 2026: {n_trips} KRL trips {per} -> {dst}")


if __name__ == "__main__":
    main()
