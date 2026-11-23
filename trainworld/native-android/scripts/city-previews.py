# SPDX-License-Identifier: AGPL-3.0-only
"""Render native new-game previews from Studio's release outlines, registry and city packs."""
import argparse
import gzip
import hashlib
import json
import math
import struct
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android-lib/src/main/assets/world-native"
FEATURED = {"jak", "sur", "bad", "yog", "sem", "med", "maa", "den"}
WIDTH, HEIGHT = 640, 264
SEA = "#c7e0e4"
LAND = "#edeedb"
ZONE = "#e5eccf"
BOUNDARY = "#c4c9ab"


def polygons(records, divisions):
    result = []
    for ring in records:
        x = y = 0
        points = []
        for dx, dy in zip(ring[::2], ring[1::2]):
            x += dx
            y += dy
            points.append((x / divisions, y / divisions))
        if len(points) >= 3:
            result.append(points)
    return result


def terrain_image(packed):
    src = gzip.decompress(packed)
    magic, width, height, col, row, per_degree = struct.unpack_from("<4sIIiiI", src)
    assert magic == b"TWT1"
    pixels = bytearray()
    i = 24
    while i < len(src):
        level = src[i]
        i += 1
        length = shift = 0
        while True:
            byte = src[i]
            i += 1
            length |= (byte & 127) << shift
            if byte < 128:
                break
            shift += 7
        pixels.extend(bytes([level]) * length)
    assert len(pixels) == width * height
    image = Image.frombytes("P", (width, height), bytes(pixels)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    image.putpalette([229,236,207, 208,225,191, 179,204,165, 147,180,148] + [0] * 756)
    return image.convert("RGB"), (col / per_degree - 180, (row + height) / per_degree - 90), per_degree


def checked(path, digest):
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == digest, path
    return data


def intersects(b, v):
    return b[0] < v[2] and b[2] > v[0] and b[1] < v[3] and b[3] > v[1]


def render(city, region, catalog, packs, output, font, bold):
    city_id = city["id"]
    lon, lat = city["center"]
    cosine = math.cos(math.radians(lat))
    # Frame the city itself. A distant island in an administrative zone does not move the camera.
    half_width = min(.85, max(.23, (city["box"][2] - city["box"][0]) * .52))
    half_height = half_width * cosine * HEIGHT / WIDTH
    view = (lon-half_width, lat-half_height, lon+half_width, lat+half_height)
    def xy(point):
        return ((point[0]-view[0])*WIDTH/(2*half_width), (view[3]-point[1])*HEIGHT/(2*half_height))
    nearby = [item for item in region if intersects(item[0]["box"], view)]
    image = Image.new("RGB", (WIDTH, HEIGHT), SEA)
    draw = ImageDraw.Draw(image)
    for meta, land, _ in nearby:
        for ring in land:
            draw.polygon([xy(p) for p in ring], fill=ZONE if meta["id"] == city_id else LAND)
    terrain = city_id in FEATURED
    if terrain:
        data = checked(packs/"terrain"/(city_id+".terrain"), catalog[city_id][9])
        raster, (west, north), per_degree = terrain_image(data)
        # Sampling a floating crop preserves the registry camera exactly, even across a terrain edge.
        scale_x = per_degree*(2*half_width)/WIDTH
        scale_y = per_degree*(2*half_height)/HEIGHT
        affine = (scale_x,0,(view[0]-west)*per_degree,0,scale_y,(north-view[3])*per_degree)
        sample = raster.transform(image.size, Image.Transform.AFFINE, affine, Image.Resampling.BILINEAR)
        mask = Image.new("L", image.size)
        pen = ImageDraw.Draw(mask)
        for meta, land, _ in nearby:
            if meta["id"] == city_id:
                for ring in land:
                    pen.polygon([xy(p) for p in ring],fill=255)
        extent = Image.new("L",raster.size,255).transform(image.size,Image.Transform.AFFINE,affine)
        image.paste(sample, mask=ImageChops.multiply(mask,extent))
    for meta, land, water in nearby:
        for ring in water:
            draw.polygon([xy(p) for p in ring], fill=SEA)
        for ring in land:
            path = [xy(p) for p in ring]
            draw.line(path+path[:1], fill="#809c78" if meta["id"] == city_id else BOUNDARY, width=2)
    places = []
    if terrain:
        places = json.loads(checked(packs/(city_id+".places.json"),catalog[city_id][13]))["places"]
        places = [(p[0],p[2:4]) for p in sorted(places,key=lambda p:(p[1],-p[4]))]
    places.extend((meta["name"],meta["center"]) for meta, _, _ in nearby)
    x, y = xy(city["center"])
    draw.ellipse((x-6,y-6,x+6,y+6), fill="#00695e", outline="white", width=2)
    draw.text((x+12,y),city["name"],font=bold,anchor="lm", fill="#164740",stroke_width=3,stroke_fill="#faf9eb")
    occupied = [(x-12,y-18,x+20+bold.getlength(city["name"]),y+18), (0,HEIGHT-39,116,HEIGHT)]
    used_names = {city["name"].casefold()}
    for name, point in places:
        if name.casefold() in used_names:
            continue
        x,y = xy(point)
        bounds = (x-5, y-12, x+font.getlength(name)+15, y+12)
        if bounds[0]<9 or bounds[1]<9 or bounds[2]>WIDTH-10 or bounds[3]>HEIGHT-10:
            continue
        if any(intersects(bounds, taken) for taken in occupied):
            continue
        draw.ellipse((x-2,y-2,x+2,y+2),fill="#718a7e")
        draw.text((x+6,y),name,font=font,anchor="lm",fill="#365951",stroke_width=2,stroke_fill="#f5f5e8")
        used_names.add(name.casefold())
        occupied.append((bounds[0]-17,bounds[1]-9,bounds[2]+17,bounds[3]+9))
        if len(occupied)>=9:
            break
    km_per_px = 2*half_width*cosine*111.32/WIDTH
    length_km = max(k for k in (1,2,5,10,20,50) if k/km_per_px<105)
    line = length_km/km_per_px
    draw.rounded_rectangle((10,HEIGHT-36,30+line,HEIGHT-6),radius=8,fill="#f8f7ea")
    draw.text((20,HEIGHT-34),f"{length_km} km",font=font,fill="#46635b")
    draw.line((20,HEIGHT-12,20+line,HEIGHT-12),fill="#46635b",width=2)
    credit = "© OpenStreetMap · GEDTM30" if terrain else "Trains World · geoBoundaries"
    draw.text((WIDTH-8,HEIGHT-7),credit,font=font,anchor="rs",fill="#49655a",stroke_width=2,stroke_fill="#f5f5e8")
    image.save(output/(city_id+".webp"),quality=80,method=4)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("registry", type=Path)
    parser.add_argument("packs", type=Path)
    args=parser.parse_args()
    registry=json.loads(args.registry.read_text())["cities"]
    outlines=json.loads(checked(args.packs/"outlines.json","0ac4bb84dd70d662b742e1698a8aec22ce031c35c845a1ca8cc89d04a14d51b9"))
    catalog={line.split("\t")[0]:line.split("\t") for line in (ASSETS/"cities.tsv").read_text().splitlines() if not line.startswith("#")}
    region=[(c,polygons(outlines["zones"].get(c["id"],[]),outlines["q"]),polygons(outlines["water"].get(c["id"],[]),outlines["q"]))
        for c in registry if c.get("box") and intersects(c["box"],[90,-14,145,12])]
    output=ASSETS/"zone-previews"
    output.mkdir(exist_ok=True)
    font=ImageFont.truetype(str(ASSETS/"fonts/Nunito-Regular.ttf"),15)
    bold=ImageFont.truetype(str(ASSETS/"fonts/Nunito-ExtraBold.ttf"),21)
    for city in registry:
        if city.get("country")=="ID":
            render(city,region,catalog,args.packs,output,font,bold)


if __name__=="__main__":
    main()
