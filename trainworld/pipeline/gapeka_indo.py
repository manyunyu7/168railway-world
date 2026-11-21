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
  indo_rail_trips.csv      local and intercity trains of Java and Sumatra, a route per train name

A pass-through is no stop; a stop with no time gets one by distance between the timed stops either side (as
gapeka_jak.py). Direction: "0" if the first station's code sorts before the last's, else "1".
"""
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gapeka_jak import SERVICES as JABO, trip_rows  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw", "gapeka2026")
OUT = os.environ.get("REALNET_OUT", os.path.join(ROOT, "data", "work", "realnet"))

# names GAPEKA shortens, as players know the trains
NAMES = {"Anggrek": "Argo Bromo Anggrek", "Ka Feeder": "Feeder Whoosh", "Exspres Rajabasa": "Rajabasa"}


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


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    os.makedirs(out, exist_ok=True)
    st = load_stations()
    java = json.load(open(os.path.join(RAW, "gapeka-jawa-2026.json"), encoding="utf-8"))["trains"]
    sumatra = json.load(open(os.path.join(RAW, "gapeka-sumatra-2026.json"), encoding="utf-8"))["trains"]
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
                shown = "KA " + NAMES.get(name, name)
                files["rail"].append((slug(island + "_" + name), shown, t["train_number"], rows))
    for k, trips in files.items():
        p = os.path.join(out, f"indo_{k}_trips.csv")
        with open(p, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["route", "direction", "trip", "stop", "name", "lon", "lat", "time", "route_name"])
            for route, shown, number, rows in trips:
                first, last = rows[0][0]["station_code"], rows[-1][0]["station_code"]
                direction = "0" if first <= last else "1"
                for s, (lon, lat), v in rows:
                    w.writerow([route, direction, number, s["station_code"], st[s["station_code"]]["name"],
                                f"{lon:.6f}", f"{lat:.6f}", v, shown])
        print(f"{k}: {len(trips)} trips, {len(set(r for r, *_ in trips))} routes -> {p}")
    if skipped:
        print(f"skipped {len(skipped)} trains: {skipped[:10]}")


if __name__ == "__main__":
    main()
