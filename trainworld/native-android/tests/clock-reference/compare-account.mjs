// Usage: node compare-account.mjs <reference worker input captures> <native account export>
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {join} from "node:path";
const [web, native] = process.argv.slice(2);
const read = (dir, name) => JSON.parse(readFileSync(join(dir, name+".json"),"utf8"));
let fields=0, maxRiders=0, maxIncome=0;
function arrayDiff(a,b,limit) {
  assert.equal(a.length,b.length);
  let max=0;
  for(let i=0;i<a.length;i++) {
    const delta=Math.abs(a[i]-b[i]);
    assert.ok(delta <= limit,`index ${i}: ${a[i]} vs ${b[i]}`);
    max=Math.max(max,delta);
  }
  fields+=a.length;
  return max;
}
for(let d=0;d<3;d++) {
  const a=read(web,"riders"+d),b=read(native,"riders"+d);
  // Count route-node differences separately from fares. A transfer between parallel
  // services may move a handful of riders to another platform while the total stays equal.
  for(const key of ["packed","arrive"]) maxRiders=Math.max(maxRiders,arrayDiff(a[key],b[key],5));
}
const byLine=read(native,"income"), reference=read(web,"income").lines;
assert.equal(Object.keys(byLine).length,reference.length);
for(const line of reference) {
  for(let d=0;d<3;d++) {
    maxIncome=Math.max(maxIncome,arrayDiff(line.perHour[d],byLine[line.line].fares[d],0.00001));
    maxRiders=Math.max(maxRiders,arrayDiff(line.rides[d],byLine[line.line].rides[d],0.1));
  }
}
function pairs(dir) {
  const v=read(dir,"transfers").packed;
  const out=new Map();
  for(let i=0;i<v.length;i+=7) out.set(v.slice(i,i+6).join(","),v[i+6]);
  return out;
}
const a=pairs(web),b=pairs(native);
assert.equal(a.size,b.size);
maxRiders=Math.max(maxRiders,arrayDiff([...a.values()],[...a.keys()].map(k=>b.get(k)),5));
console.log({fields,maxRiders,maxIncome});
