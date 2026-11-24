"""Encode the release's outlines.json and web registry for offline viewport lookup."""
import gzip
import json
import struct
import sys
from pathlib import Path

registry, outlines, dest = map(Path, sys.argv[1:])
data = json.loads(outlines.read_text())
cities = json.loads(registry.read_text())["cities"]
with dest.open("wb") as file, gzip.GzipFile(fileobj=file, filename="", mode="wb", mtime=0) as out:
    out.write(struct.pack(">ii", data["q"], len(cities)))
    for city in cities:
        key = city["id"].encode("ascii")
        rings = data["zones"].get(city["id"], [])
        out.write(struct.pack(">H", len(key)) + key)
        out.write(struct.pack(">6di", *city["center"], *city["box"], len(rings)))
        for ring in rings:
            out.write(struct.pack(">i", len(ring)))
            out.write(struct.pack(f">{len(ring)}i", *ring))
