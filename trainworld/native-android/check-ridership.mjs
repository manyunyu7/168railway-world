// Compare the native city assignment to the fork's browser WASM on identical packed services.
// Arguments: sim.js, city.json, city.bin, exported native service.json, native result.json.
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
const [modulePath,headerPath,cellsPath,servicePath,resultPath]=process.argv.slice(2);
const js=pathToFileURL(resolve(modulePath));
const {initSync,DemandApi,EverydayApi,MarketPacks}=await import(js.href);
initSync({module:readFileSync(new URL('sim_bg.wasm',js))});
const json=path=>JSON.parse(readFileSync(path,'utf8'));
const n=json(servicePath), expected=json(resultPath),q=expected.period;
const h=readFileSync(headerPath,'utf8'), c=readFileSync(cellsPath);
const work=new DemandApi(h,c), outings=EverydayApi.beside(work,h,c);
let market,packs;
const regionalInput=process.argv.slice(7); // index.json.gz, market-drives.bin, one or more zone.market.json
try {
  const access=(p)=>{p.set_upgrades(Uint8Array.from(n.upgrades));p.set_passages(Uint32Array.from(n.passages));p.set_span(Float32Array.from(n.span));};
  if(regionalInput.length){
    assert.ok(regionalInput.length>=3);const [index,drives,...zones]=regionalInput;
    packs=new MarketPacks(gunzipSync(readFileSync(index)).toString());
    packs.set_drives(readFileSync(drives));
    for(const z of zones)packs.add(readFileSync(z,'utf8'));
    market=packs.market();access(market);market.set_bus_ll(Uint32Array.from(n.catchN),Float64Array.from(n.catchLL));market.set_sampled(true);market.set_back_stops(Uint32Array.from(n.back));
    market.set_network_ll(Float64Array.from(n.ll),Uint32Array.from(n.counts),Uint32Array.from(n.stops),Float32Array.from(n.times),Float32Array.from(n.tph),Float32Array.from(n.cars));
    assert.ok(market.solve(q));
  }
  for(const p of [work,outings]){
    access(p);p.set_bus(Uint32Array.from(n.catchN),Float32Array.from(n.catchXY));if(p===work)p.set_tram_lines(Uint8Array.from(n.tram));
    p.set_back_stops(Uint32Array.from(n.back));
    p.set_network(Float32Array.from(n.xy),Uint32Array.from(n.counts),Uint32Array.from(n.stops),Float32Array.from(n.times),Float32Array.from(n.tph),Float32Array.from(n.cars));
    if(p===outings)p.set_ferry(Uint8Array.from(n.ferry));
    assert.ok(p.solve(q));
  }
  for(let round=0;round<3;round++){
    const parts=['seg','board','alight'].map(key=>{
      const a=work[key](q),b=outings[key](q);
      const m=market?.[key](q); return a.map((v,i)=>Math.fround(v+b[i])+(m?.[i]??0));
    });
    const sameLoad=Float32Array.from(parts.flatMap(v=>Array.from(v)));
    if(market)assert.ok(market.crowd_with_external(q,sameLoad));
    assert.ok(work.crowd_with_external(q,sameLoad));
    assert.ok(outings.crowd_with_external(q,sameLoad));
  }
  const a=work.summary(q),b=outings.summary(q);
  const actual={trips:a[1]+b[1],rail:a[2]+b[2],commuteRail:a[2],outingsRail:b[2],walk:a[3]+b[3],unserved:a[13]+b[13],boardings:[...work.board(q),...outings.board(q)].reduce((x,y)=>x+y,0)};
  if(market){const s=market.summary(q);actual.regionalTrips=s[1];actual.regionalRail=s[2];actual.trips+=s[1];actual.rail+=s[2];actual.walk+=s[3];actual.boardings+=market.board(q).reduce((x,y)=>x+y,0);}
  for(const [key,val] of Object.entries(actual)){
    const relative=Math.abs(val-expected[key])/Math.max(1,expected[key]);
    console.log(key,JSON.stringify({wasm:val,native:expected[key],relative}));
    assert.ok(relative<0.001,key+' deviates by more than 0.1%');
  }
}finally{market?.free();packs?.free();outings.free();work.free()}
