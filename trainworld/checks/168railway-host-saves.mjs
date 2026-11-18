/** Test the built game and its save UI. HTTP account responses are isolated fixtures.
 * Set FORK_URL to the checkout's HTTP URL; FORK_DATA may point at a licensed QA mirror.
 * Supply playwright-core through PLAYWRIGHT_MODULE when installed separately. */
import {randomUUID} from 'node:crypto';
import {createRequire} from 'node:module';
import {readFile,stat} from 'node:fs/promises';
import {resolve,sep} from 'node:path';
import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
if(!process.env.FORK_URL)throw Error('Set FORK_URL before running.');
const browser=await chromium.launch({channel:'chrome',headless:process.env.FORK_HEADED!=='1',args:['--enable-unsafe-swiftshader']});
const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,deviceScaleFactor:2});
const page=await context.newPage();page.setDefaultTimeout(90000);
const realHost=process.env.FORK_QA_HOST==='1';
if(realHost&&!['localhost','127.0.0.1'].includes(new URL(process.env.FORK_URL).hostname))throw Error('Use only an isolated local Laravel QA host.');
const host=new URL(process.env.FORK_URL).origin;
async function login(which) {
  await context.clearCookies();
  await context.request.get(host+'/api/web/studio/session');
  const token=(await context.cookies()).find(c=>c.name==='XSRF-TOKEN');
  const result=await context.request.post(host+'/login/proc',{
    form:{uname:'test-'+which+'@example.invalid',password:'local-fixture-only'},
    headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)},maxRedirects:0,
  });
  assert.equal(result.status(),302);
  const session=await (await context.request.get(host+'/api/web/studio/session')).json();
  assert.ok(session.authenticated);return session;
}
if(realHost)await login('a');
const name='Data QA '+randomUUID().slice(0,8);
let user='qa-a' , rival=false, stored=new Map(), submitted=0,opened=0;
if(process.env.FORK_DATA){
  const root=resolve(process.env.FORK_DATA);
  await page.route('**/studio-data/2026-11-18/**',async route=>{
    const pathname=new URL(route.request().url()).pathname.split('/studio-data/2026-11-18/')[1];
    const file=resolve(root,pathname);
    if(!file.startsWith(root+sep))return route.abort();
    const exists=await stat(file).catch(()=>null);
    if(!exists?.isFile())return route.fulfill({status:404,headers:{'Access-Control-Allow-Origin':'*'}});
    return route.fulfill({body:await readFile(file),headers:{'Access-Control-Allow-Origin':'*'}});
  });
}
if(!realHost)await page.route('**/api/web/studio/**',async route=>{
  const req=route.request(),url=new URL(req.url()),id=url.pathname.split('/').at(-1);
  const respond=(json,status=200)=>route.fulfill({json,status});
  if(id==='session')return respond({authenticated:!!user,userId:user,csrfToken:user?'test-csrf':null,loginUrl:'/login'});
  if(!user)return respond({},401);
  if(req.headers()['x-studio-user']!==user)return respond({error:'account_changed'},409);
  if(id==='worlds')return respond({worlds:[...stored.values()].filter(w=>w.owner===user).map(w=>w.meta)});
  const prior=stored.get(id);
  if(req.method()==='GET'){
    if(prior?.owner!==user)return respond({},404);
    opened++;return route.fulfill({body:prior.bytes,headers:{'Content-Type':'application/octet-stream','X-World-Version':String(prior.meta.version)}});
  }
  assert.equal(req.headers()['x-csrf-token'],'test-csrf');
  const expected=Number(req.headers()['x-world-version']);if(rival&&prior)prior.meta.version++,rival=false;
  if(expected!==(prior?.meta.version||0)||prior&&prior.owner!==user)return respond({version:prior?.meta.version},409);
  const bytes=req.postDataBuffer();assert.equal(gunzipSync(bytes).subarray(0,4).toString(),'TWG1');
  const meta={id,name:decodeURIComponent(req.headers()['x-world-name']),size:bytes.length,version:expected+1,updatedAt:new Date().toISOString()};
  stored.set(id,{owner:user,bytes,meta});submitted++;return respond({world:meta},expected?200:201);
});
try{
  await page.goto(process.env.FORK_URL,{waitUntil:'domcontentloaded',timeout:120000});
  if(realHost&&process.env.FORK_IMPORT_SAVE){
    const bytes=await readFile(process.env.FORK_IMPORT_SAVE);
    assert.equal(gunzipSync(bytes).subarray(0,4).toString(),'TWG1');
    const session=await(await context.request.get(host+'/api/web/studio/session')).json();
    const id=randomUUID(), title='Imported QA '+id.slice(0,8);
    const res=await context.request.put(host+'/api/web/studio/worlds/'+id,{data:bytes,headers:{
      'X-Studio-User':session.userId,'X-CSRF-TOKEN':session.csrfToken,
      'Content-Type':'application/octet-stream','X-World-Name':encodeURIComponent(title),'X-World-Version':'0',
    }});assert.equal(res.status(),201);
    await page.locator('.host-worlds:visible summary').first().click();
    await page.locator('.host-worlds:visible li').filter({hasText:title}).getByRole('button',{name:'Buka salinan lokal'}).click();
    await page.locator('.clock-text .time:visible').first().waitFor({timeout:120000});
    console.log('PASS imported file opened on web via account with its simulation clock.',bytes.length,'bytes');
  }else{
  await page.getByRole('button',{name:'New game',exact:true}).tap();
  await page.getByRole('button',{name:'Start in New York',exact:true}).tap();
  }
  await page.locator('.tab.gear.save:visible').first().waitFor({timeout:150000});
  if(process.env.FORK_RAF==="1"){
  const speed=page.locator("button.rate:visible").first();if(await speed.count())await speed.tap();await page.waitForTimeout(4500);
  for(const camera of [false,true]){
    const metrics=await page.evaluate(async (camera)=>{
      const canvas=document.querySelector(".maplibregl-canvas");let last=performance.now(),dt=[];
      const end=last+4500;let frame=0;
      return await new Promise(done=>requestAnimationFrame(function sample(now){
        if(frame>1)dt.push(now-last);last=now;
        if(camera&&frame%15===0&&canvas)canvas.dispatchEvent(new WheelEvent("wheel",{deltaY:frame%30===0?-75:75,clientX:220,clientY:350,bubbles:true}));
        frame++;
        if(now<end)requestAnimationFrame(sample);else{
          const sorted=[...dt].sort((a,b)=>a-b),avg=dt.reduce((a,b)=>a+b,0)/dt.length;
          done({frames:dt.length,fps:1000/avg,medianMs:sorted[Math.floor(dt.length*.5)],p95Ms:sorted[Math.floor(dt.length*.95)],above33:dt.filter(x=>x>33).length});
        }
      }));
    },camera);console.log("RAF",camera?"zoom":"running",metrics);
  }
  }
  if(process.env.FORK_PROOF)await page.screenshot({path:process.env.FORK_PROOF});
  await page.locator('.tab.gear.save:visible').first().tap();
  const panel=page.locator('.host-worlds:visible').first();
  await panel.locator('summary').click();
  await panel.getByLabel('Nama dunia di akun').fill(name);
  await panel.getByRole('button',{name:'Simpan sebagai dunia baru'}).click();
  await panel.getByText(/Tersimpan · versi 1/).waitFor({timeout:100000});
  let world;
  if(realHost){
    const session=await(await context.request.get(host+'/api/web/studio/session')).json();
    const hdr={'X-Studio-User':session.userId};
    const list=await(await context.request.get(host+'/api/web/studio/worlds',{headers:hdr})).json();
    world=list.worlds.find(w=>w.name===name&&w.version===1);assert.ok(world);
    const url=host+'/api/web/studio/worlds/'+world.id;
    const bytes=await(await context.request.get(url,{headers:hdr})).body();
    assert.equal(gunzipSync(bytes).subarray(0,4).toString(),'TWG1');
    const second=await context.request.put(url,{data:bytes,headers:{...hdr,'Content-Type':'application/octet-stream','X-CSRF-TOKEN':session.csrfToken,'X-World-Name':encodeURIComponent(world.name),'X-World-Version':'1'}});
    assert.equal(second.status(),200);
  }else assert.equal(submitted,1);
  const row=panel.locator('li').filter({hasText:name});
  page.on('dialog',d=>d.accept());
  rival=true;await row.getByRole('button',{name:'Perbarui',exact:true}).click();
  await panel.getByText(/versi simpanan berubah/).waitFor();
  if(!realHost)assert.equal(submitted,1);
  user='qa-b';if(realHost)await login('b');await row.getByRole('button',{name:'Perbarui',exact:true}).click();
  await panel.getByText(/Akun di browser berubah/).waitFor();
  await panel.getByRole('button',{name:'Muat ulang daftar'}).click();
  await panel.getByText(/Belum ada simpanan/).waitFor();
  const slotKeys=()=>new Promise((resolve,reject)=>{
    const request=indexedDB.open('anitabuilder',1);
    request.onerror=()=>reject(request.error);
    request.onsuccess=()=>{
      const db=request.result,tx=db.transaction('saves','readonly'),keys=tx.objectStore('saves').getAllKeys();
      keys.onsuccess=()=>resolve(keys.result.filter(k=>k==='autosave'||String(k).startsWith('slot-')));
      tx.oncomplete=()=>db.close();tx.onerror=()=>reject(tx.error);
    };
  });
  const before=await page.evaluate(slotKeys);
  if(process.env.FORK_IMPORT_SAVE)assert.ok(before.length>=1);
  else assert.equal(before.length,0,'a new untouched blank world stays out of local saves');
  user='qa-a';if(realHost)await login('a');await panel.getByRole('button',{name:'Muat ulang daftar'}).click();
  await row.getByRole('button',{name:'Buka salinan lokal'}).click();
  await page.waitForFunction(()=>document.querySelector('.host-worlds[open]')===null,{},{timeout:90000});
  const timeout=Date.now()+60000;let after=[];
  while(Date.now()<timeout){
    try{after=await page.evaluate(slotKeys);}catch{/* game reload tears down the previous document */}
    if(after.length>before.length&&before.every(k=>after.includes(k)))break;
    await page.waitForTimeout(250);
  }
  assert.ok(after.length>before.length&&before.every(k=>after.includes(k)),'opening an account save preserves existing local slots and adds a separate one');
  const copyKey=after.find(k=>!before.includes(k));
  await page.reload({waitUntil:'domcontentloaded'});
  const copy=await page.evaluate(key=>new Promise((resolve,reject)=>{
    const open=indexedDB.open('anitabuilder',1);
    open.onerror=()=>reject(open.error);
    open.onsuccess=()=>{
      const db=open.result,tx=db.transaction('saves','readonly'),read=tx.objectStore('saves').get(key);
      read.onsuccess=()=>resolve({name:read.result?.name,bytes:read.result?.bytes?.byteLength});
      tx.oncomplete=()=>db.close();tx.onerror=()=>reject(tx.error);
    };
  }),copyKey);
  assert.equal(copy.name,name,'the explicit account copy keeps its chosen name after reload');
  assert.ok(copy.bytes>0,'the new local slot contains a game snapshot');
  if(!realHost)assert.equal(opened,1);console.log('PASS actual Rust/WASM gzip TWG1 snapshot, explicit account upload, stale version, switched account, isolation, and open local copy.',realHost?world.size:stored.values().next().value.bytes.length,'bytes', realHost?'Laravel session, storage and CSRF':'isolated HTTP fixtures');
} catch (error) {
  console.error((await page.locator('body').innerText()).slice(-1500));throw error;
} finally{await browser.close();}
