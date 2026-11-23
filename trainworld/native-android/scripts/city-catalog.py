# SPDX-License-Identifier: AGPL-3.0-only
"""Build Android's small release catalogue from a verified local mirror manifest."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

native = Path(__file__).resolve().parents[1]
game = native.parent / "app/src/game"
manifest_bytes = Path(sys.argv[1]).read_bytes()
if hashlib.sha256(manifest_bytes).hexdigest() != "e5798fdc0330a3c7809cf1f7e5eff26d4ec8fdc3eeef49211f4f54e457c81601":
    raise ValueError("Unexpected release manifest")
inventory = {entry["path"]: entry for entry in json.loads(manifest_bytes)["files"]}
cities = json.loads((game / "registry.json").read_text())["cities"]
name_counts = Counter(city["name"] for city in cities)
networks = json.loads((game / "realNetworks.json").read_text())
names = {
    city["id"]: city["name"] if name_counts[city["name"]] == 1 or city["id"] in networks
    else f'{city["name"]} · {city["country"]} ({city["id"]})'
    for city in cities
}
rows = ["# Release 2026-11-18. ID, name, then size and SHA256 for header, cells, market, terrain and network."]
for city in sorted(names):
    row = [city, names[city]]
    for path in (f"{city}.json", f"{city}.bin", f"{city}.market.json", f"terrain/{city}.terrain", f"real/{city}.save"):
        if city not in networks and path.startswith("real/"):
            row.extend(("0", "-"))
            continue
        entry = inventory[path]
        row.extend((str(entry["bytes"]), entry["sha256"]))
    if f"{city}.places.json" in inventory:
        places = inventory[f"{city}.places.json"]
        row.extend((str(places["bytes"]), places["sha256"]))
    rows.append("\t".join(row))
target = native / "android-lib/src/main/assets/world-native/cities.tsv"
target.write_text("\n".join(rows) + "\n", encoding="utf-8")
