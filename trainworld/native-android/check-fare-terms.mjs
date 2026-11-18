// Compare native input terms to the original clock worker's TWG1 fare migration and
// demand worker's local/market fare setup. Run with APP_DIR and Rust's exported service.json.
import {mkdtemp,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const [appDir,input]=process.argv.slice(2),app=resolve(appDir);
const require=createRequire(join(app,'package.json'));
const {rolldown}=await import(pathToFileURL(require.resolve('rolldown')).href);
const worker=await readFile(join(app,'src/workers/clock.worker.ts'),'utf8');
const imports=worker.split(/\r?\n/).filter(s=>s.startsWith('import {')&&['../game/fareCurve','../game/income'].some(m=>s.includes('"'+m+'"'))).map(s=>s.replace('../game/',join(app,'src/game/')));
const start=worker.slice(worker.indexOf('const START_FARES:'),worker.indexOf('\n',worker.indexOf('const START_FARES:')));
const migration=worker.slice(worker.indexOf('function loadFares('),worker.indexOf('/** T-3567: line',worker.indexOf('function loadFares(')));
const absFile=m=>JSON.stringify(join(app,'src/game/'+m));
const source=imports.join('\n')+`
import {fareWeight,fareScale,marketFareWeight,fareFactors} from ${absFile('income')};
import {curveKnots,scaledKnots} from ${absFile('fareCurve')};import {roundedDefault} from ${absFile('fareDefault')};
const HOME_CITY='nyc',cityById=()=>({country:'US'});${start}
${migration}
export function check(raw,country){
  const f=loadFares(raw),s=fareScale(country,f.scales),r=roundedDefault(f,country);
  return {local:{base:r?r.base:f.base*s,rate:r?r.perKm:f.perKm*s,weight:fareWeight(country),transfer:(f.transfer||0)*s,curve:Array.from(r?curveKnots(r):scaledKnots(f,s))},
  market:{base:f.base,rate:f.perKm,weight:marketFareWeight(f.scales),factors:fareFactors(f.scales),curve:Array.from(curveKnots(f))}};
}`;
const tmp=await mkdtemp(join(tmpdir(),'168world-fare-check-'));
const bundle=await rolldown({input:'native-compare.ts',platform:'node',plugins:[{name:'fork-worker',resolveId(id){if(id==='native-compare.ts')return '\0native-compare.ts'},load(id){if(id==='\0native-compare.ts')return source}}]});
await bundle.write({dir:tmp,format:'esm',entryFileNames:'check.mjs'});await bundle.close();
const {check}=await import(pathToFileURL(join(tmp,'check.mjs')).href);
const data=JSON.parse(await readFile(input,'utf8')),rows=Array.isArray(data)?data:[data];
function compare(a,b,key){
 if(typeof a==='number'){assert.ok(Math.abs(a-b)<1e-5*Math.max(1,Math.abs(a)),key+' expected '+a+' got '+b);return;}
 assert.deepEqual(Object.keys(a).sort(),Object.keys(b).sort(),key);for(const k in a)compare(a[k],b[k],key+'.'+k);
}
for(const row of rows){const r=check(row.rawFare,row.country);compare(r.local,row.localFare,'local');compare(r.market,row.marketFare,'market');console.log(row.country,r.local.base,r.local.rate,r.local.weight);}
