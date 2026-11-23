// Compare geographical rows exported by the web overlay and WORLD_TRIP_EXPORT.
import assert from "node:assert/strict";
import fs from "node:fs";
const read = path => JSON.parse(fs.readFileSync(path, "utf8"));
const web = read(process.argv[2]).cells;
const native = read(process.argv[3]);
assert.equal(native.length, web.length);
assert.ok(web.length > 0 && web.length % 10 === 0);
let maxTrips = 0;
for (let i = 0; i < web.length; i++) {
  const delta = Math.abs(native[i] - web[i]);
  const coordinate = i % 10 < 2;
  assert.ok(delta <= (coordinate ? 0.000005 : 0.01), `cell ${Math.floor(i / 10)}, column ${i % 10}, difference ${delta}`);
  if (!coordinate) maxTrips = Math.max(maxTrips, delta);
}
console.log(JSON.stringify({ cells: web.length / 10, maxTrips }));
