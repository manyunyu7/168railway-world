// Compare against the original web worker's final weekday boarding account.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const [reference, ...periods] = process.argv.slice(2);
assert.equal(periods.length, 5, "Supply riders0.json and five native period result files");
const json = path => JSON.parse(readFileSync(path, "utf8"));
const {packed, day} = json(reference);
assert.equal(day, 0);
const web = Array(5).fill(0);
for (let i = 5; i < packed.length;) {
  const stops = packed[i+1];
  assert.ok(Number.isInteger(stops) && stops > 0 && i+2+20*stops <= packed.length);
  i += 2;
  for (let p = 0; p < 5; p++) {
    for (let j = 0; j < 2*stops; j++) web[p] += packed[i+j];
    i += 4*stops;
  }
}
for (let period = 0; period < 5; period++) {
  const native = json(periods[period]);
  assert.equal(native.period,period);
  const difference = native.boardings - web[period];
  console.log(JSON.stringify({period, web: web[period], native: native.boardings, difference}));
  assert.ok(Math.abs(difference) < 1, "Aggregate boardings differ by at least one rider");
}
