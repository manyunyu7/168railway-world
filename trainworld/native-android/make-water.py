"""Reproduce the offline NYC shoreline from trainsworld's TIGER/Line 2020 water runs."""
from array import array
from bisect import bisect_right
from hashlib import sha256
from math import cos, degrees, radians, ceil
from pathlib import Path
from struct import pack
from urllib.request import urlopen, Request
import json, zlib, sys

base = 'https://data.anita.garden/trainworld/2026-11-18/nyc'
root = Path(__file__).resolve().parent
head = json.load(open(sys.argv[1])) if len(sys.argv)>1 else json.load(urlopen(base+'.json'))
water = head['water']
cols, rows = water['size']
start, runs = (next(a for a in head['arrays'] if a['name']==key) for key in ['water_row','water_x'])
last = runs['offset'] + 2*runs['count'] - 1
blob = Path(sys.argv[2]).read_bytes() if len(sys.argv)>2 else urlopen(Request(base+'.bin', headers={'Range':f"bytes={start['offset']}-{last}"})).read()
assert len(blob)==last-start['offset']+1
cut=runs['offset']-start['offset']
offsets=array('I',blob[:cut]); ends=array('H',blob[cut:])
if sys.byteorder!='little':
    offsets.byteswap();ends.byteswap()
assert len(offsets)==rows+1
stride=5
width,height=ceil(cols/stride),ceil(rows/stride)
pixels=bytearray()
for i in range(height):
    row=min(rows-1, (height-1-i)*stride+stride//2)
    bounds=ends[offsets[row]:offsets[row+1]]
    scan=bytearray((width+7)//8)
    for col in range(width):
        if bisect_right(bounds,min(cols-1,col*stride+stride//2))%2:
            scan[col//8] |= 1 << (7-col%8)
    pixels += bytes([0])+scan
def chunk(kind, data):
    return pack('>I',len(data))+kind+data+pack('>I',zlib.crc32(kind+data))
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',pack('>IIBBBBB',width,height,1,3,0,0,0))+chunk(b'PLTE',bytes.fromhex('ecf2eebabedd')[:3]+bytes.fromhex('accfdc'))+chunk(b'IDAT',zlib.compress(pixels,9))+chunk(b'IEND',b'')
(root/'android-lib/src/main/assets/world-native/nyc-water.png').write_bytes(png)
x0,y0=water['origin_m'];lon,lat=head['origin']['lon'],head['origin']['lat']; meters=water['cell_m']*stride
box=[lon+degrees(x0/(6371008.8*cos(radians(lat)))), lat+degrees(y0/6371008.8),lon+degrees((x0+width*meters)/(6371008.8*cos(radians(lat)))),lat+degrees((y0+height*meters)/6371008.8)]
(root/'assets/nyc-water.json').write_text(json.dumps({'bounds':box,'pixelMeters':meters,'source':base+'.bin','inputRange':[start['offset'],last],'inputSha256':sha256(blob).hexdigest()})+'\n')
