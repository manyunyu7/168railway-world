// Reproduce the global market input with the same compiler, rule, income weights and
// attractions that the original browser worker uses. Supply the app directory after npm ci.
import {mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {gzipSync} from 'node:zlib';
const [appDir,output]=process.argv.slice(2);
if(!output)throw Error('Usage: node build-market.mjs APP_DIR OUTPUT.json.gz');
const app=resolve(appDir), require=createRequire(join(app,'package.json'));
const {rolldown}=await import(pathToFileURL(require.resolve('rolldown')).href);
const tmp=await mkdtemp(join(tmpdir(),'168world-market-'));
const game=join(app,'src/game');
const source=`import {fareWeight,carUsdPerKm,incomeGroups} from ${JSON.stringify(join(game,'income.ts'))};
import registry from ${JSON.stringify(join(game,'registry.json'))};
import attractions from ${JSON.stringify(join(game,'attractions.json'))};
export function generate(rowsText){
 const rows=JSON.parse(rowsText), {frame,rule,places}=registry.market;
 return JSON.stringify({frame,rule,places:places.length,rows:registry.cities.flatMap(({id})=>{
   const r=rows[id]; if(!r) return [];
   return [{...r,fw:fareWeight(r.country),cu:carUsdPerKm(r.country),fg:incomeGroups(r.country,rule.eps).flatMap(g=>[g.share,g.factor])}];
 }),attractions:attractions.rows.map(([, ,lng,lat,visitors])=>[lng,lat,visitors])});
}`;
const bundle=await rolldown({input:'native-market-input',platform:'node',plugins:[{name:'market-source',resolveId(id){if(id==='native-market-input')return '\0native-market-input'},load(id){if(id==='\0native-market-input')return source}}]});
await bundle.write({dir:tmp,format:'esm',entryFileNames:'market.mjs',chunkFileNames:'[name]-[hash].mjs'});
await bundle.close();
const {generate}=await import(pathToFileURL(join(tmp,'market.mjs')).href);
const bytes=await generate(await readFile(join(app,'src/game/market-rows.json'),'utf8'));
await writeFile(output,gzipSync(bytes,{level:9}));
console.log('World rows:',JSON.parse(bytes).rows.length,'bytes:',Buffer.byteLength(bytes));
