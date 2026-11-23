// Compare all live train keys and their displayed integer load percentages at the same instant.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const [a,b] = process.argv.slice(2).map(path => JSON.parse(readFileSync(path,"utf8")));
assert.equal(a.clock,b.clock);
function gauges({rows}) {
  assert.equal(rows.length%3,0);
  const keys=new Map();
  for(let i=0;i<rows.length;i+=3) keys.set(rows[i]+"/"+rows[i+1],rows[i+2]);
  assert.equal(keys.size,rows.length/3);
  return keys;
}
assert.deepEqual(gauges(a),gauges(b));
console.log(a.rows.length/3,"train gauges match");
