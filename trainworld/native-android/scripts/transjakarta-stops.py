"""Build the Studio corridor stop labels from the OSM relations used to create its Jakarta scenarios
and the TransJakarta GTFS feed. Usage: python3 transjakarta-stops.py osm_jak_tj.json file_gtfs.zip.
The simulation supplies its own stop positions and timetable; this table supplies names only.
"""
import csv
import io
import json
import math
from pathlib import Path
import sys
from zipfile import ZipFile

relations = json.loads(Path(sys.argv[1]).read_text())["elements"]
route_stops = {}
with ZipFile(sys.argv[2]) as feed:
    def records(name):
        with feed.open(name + ".txt") as stream:
            yield from csv.DictReader(io.TextIOWrapper(stream, encoding="utf-8-sig"))

    trips = {row["trip_id"]: row["route_id"] for row in records("trips")}
    for row in records("stop_times"):
        route_stops.setdefault(trips[row["trip_id"]], set()).add(row["stop_id"])
    stops = {row["stop_id"]: (float(row["stop_lon"]), float(row["stop_lat"]), row["stop_name"].strip())
             for row in records("stops")}

print("# Studio scenario geometry © OpenStreetMap contributors, ODbL; timetable stop names: TransJakarta GTFS.")
print("# Corridor, longitude, latitude, stop name. Empty names have no close GTFS match.")
for ref in map(str, range(1, 15)):
    # Same relation selection as trainworld/pipeline/realnet.py osm_bus_routes.
    route = min((r for r in relations if r.get("tags", {}).get("type") == "route"
                 and r["tags"].get("ref") == ref), key=lambda r: r["id"])
    for member in route["members"]:
        if not member["role"].startswith("stop") or "lat" not in member:
            continue
        lon, lat = member["lon"], member["lat"]
        def distance(stop):
            x, y, _ = stop
            return 111200 * math.hypot((x - lon) * math.cos(math.radians(lat)), y - lat)
        candidates = sorted((distance(stops[s]), stops[s][2]) for s in route_stops[ref])
        d, name = candidates[0]
        # Keep ambiguous stops unnamed. Don't infer a name from a different corridor.
        if d > 85 or any(other != name and metres <= d + 8 for metres, other in candidates):
            name = ""
        print(f"{ref}\t{lon:.7f}\t{lat:.7f}" + (f"\t{name}" if name else ""))
