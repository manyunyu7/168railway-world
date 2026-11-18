# SPDX-License-Identifier: AGPL-3.0-only
"""Build Android's small release catalogue from a verified local mirror manifest."""
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
names = {city["id"]: city["name"] for city in json.loads((game / "registry.json").read_text())["cities"]}
networks = json.loads((game / "realNetworks.json").read_text())
rows = ["# Release 2026-11-18. ID, name, then size and SHA256 for header, cells, market, terrain and network."]
for city in sorted(networks):
    row = [city, names[city]]
    for path in (f"{city}.json", f"{city}.bin", f"{city}.market.json", f"terrain/{city}.terrain", f"real/{city}.save"):
        entry = inventory[path]
        row.extend((str(entry["bytes"]), entry["sha256"]))
    rows.append("\t".join(row))
target = native / "android-lib/src/main/assets/world-native/cities.tsv"
target.write_text("\n".join(rows) + "\n", encoding="utf-8")
