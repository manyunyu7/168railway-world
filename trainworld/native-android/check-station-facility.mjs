// Read the native paid-upgrade fixture through the web game's generated TrackApi.
import { readFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";
import assert from "node:assert/strict";
const [wasmDirectory, save] = process.argv.slice(2);
const { default: init, TrackApi } = await import(pathToFileURL(resolve(wasmDirectory, "sim.js")).href);
await init({ module_or_path: await readFile(join(wasmDirectory, "sim_bg.wasm")) });
const bytes = gunzipSync(await readFile(save));
assert.equal(bytes.toString("ascii", 0, 4), "TWG1");
const start = 8 + bytes.readUInt32LE(4);
const header = JSON.parse(bytes.toString("utf8", 8, start));
const api = new TrackApi(-74, 40.7, true);
try {
  assert.ok(api.load(bytes.subarray(start)));
  const upgrades = Array.from(api.station_upgrades());
  assert.equal(upgrades.length, 2);
  const [node, bits] = upgrades;
  assert.equal(bits, 4);
  assert.equal(api.station_upgrade_cost(node, 4), 5);
  assert.equal(header.cash, 95);
  assert.equal(header.ledger.at(-1).build, 5);
  assert.equal(header.hours.at(-1).build, 5);
  assert.equal(header.regions[""].ledger.at(-1).build, 5);
  console.log(JSON.stringify({ node, bits, cash: header.cash, build: header.ledger.at(-1).build }));
} finally { api.free(); }
