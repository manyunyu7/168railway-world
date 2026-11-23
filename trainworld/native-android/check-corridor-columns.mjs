// Compare Rust's compact table with the web worker's original column format and FNV key.
// Usage: node check-corridor-columns.mjs sim.js market.json.gz drives.bin zone.market.json
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";

const [modulePath, index, drives, zone] = process.argv.slice(2);
const js = pathToFileURL(resolve(modulePath));
const { initSync, MarketPacks } = await import(js.href);
initSync({ module: readFileSync(new URL("sim_bg.wasm", js)) });
const packs = new MarketPacks(gunzipSync(readFileSync(index)).toString());
packs.set_drives(readFileSync(drives));
packs.add(readFileSync(zone, "utf8"));
const market = packs.market();
const compact = market.corridor_columns(true);
let hash = 0x811c9dc5;
function word(value) { hash = Math.imul(hash ^ value, 0x01000193); }
function words(array) {
  const bits = new Uint32Array(array.buffer, array.byteOffset, array.byteLength / 4);
  for (const value of bits) word(value);
}
function equal(actual, expected) {
  assert.equal(actual.constructor, expected.constructor);
  assert.ok(Buffer.from(actual.buffer, actual.byteOffset, actual.byteLength)
    .equals(Buffer.from(expected.buffer, expected.byteOffset, expected.byteLength)));
}
const pairs = market.corridor_rows_pairs(), n = pairs.length / 2;
assert.equal(compact.rows(), n);
const wide = pairs.some(v => v > 65535);
assert.equal(compact.wide(), wide);
for (const offset of [0, 1]) {
  const expected = new (wide ? Uint32Array : Uint16Array)(n);
  for (let j = 0; j < n; j++) { expected[j] = pairs[2*j + offset]; word(expected[j]); }
  equal(wide ? (offset ? compact.take_to32() : compact.take_from32())
    : (offset ? compact.take_to16() : compact.take_from16()), expected);
}
const trips = market.corridor_rows_trips();
words(trips);
equal(compact.take_longer(), Float32Array.from(trips));
const legs = market.corridor_rows_commutes();
if (legs.length === n) words(legs);
else for (let i = 0; i < 2*n; i++) word(0);
const at = [];
for (let i = 0; i < legs.length; i++) if (!Object.is(legs[i], 0)) at.push(i);
equal(compact.take_commute_at(), Uint32Array.from(at));
equal(compact.take_commute_legs(), Float64Array.from(at, i => legs[i]));
const modes = market.corridor_rows_modes();
const modeBits = new Uint32Array(modes.buffer, modes.byteOffset, modes.length);
for (const offset of [0, 1]) {
  const expected = new Uint16Array(n);
  for (let j = 0; j < n; j++) {
    const k = 2*j + offset, share = modes[k];
    word(modeBits[k]);
    expected[j] = Number.isNaN(share) ? 65535 : Math.round(Math.min(1, Math.max(0, share)) * 65534);
  }
  equal(offset ? compact.take_car() : compact.take_air(), expected);
}
assert.equal(compact.hash(), hash >>> 0);
compact.free();
const keyOnly = market.corridor_columns(false);
assert.equal(keyOnly.hash(), hash >>> 0);
assert.equal(keyOnly.rows(), n);
keyOnly.free();
market.free();
packs.free();
console.log(`Verified ${n} corridor rows, key ${(hash >>> 0).toString(36)}`);
