// Compare network rows from the web overlay with the Rust fixture export.
import assert from "node:assert/strict";
import fs from "node:fs";

const read = path => JSON.parse(fs.readFileSync(path, "utf8"));
const web = read(process.argv[2]);
const native = read(process.argv[3]);
const tolerances = {
  stops: [1e-7, 1e-7, 5, 5],
  rail: [1e-7, 1e-7, 1e-7, 1e-7, 1e-5, 0.003],
  edges: [0, 1e-5],
  spans: [0, 1e-7, 1e-7, 3, 1e-5],
  road: [0, 1e-7, 1e-7, 0.001],
};
for (const [layer, limits] of Object.entries(tolerances)) {
  const expected = layer === "road" ? web.road ?? [] : web[layer];
  const actual = native[layer];
  assert.equal(actual.length, expected.length, layer);
  assert.equal(actual.length % limits.length, 0, layer);
  const maxima = limits.map(() => 0);
  for (let i = 0; i < actual.length; i++) {
    const column = i % limits.length;
    const difference = Math.abs(actual[i] - expected[i]);
    assert.ok(difference <= limits[column], `${layer}[${i}] differs by ${difference}`);
    maxima[column] = Math.max(maxima[column], difference);
  }
  console.log(JSON.stringify({ layer, rows: actual.length / limits.length, maxima }));
}
