// Touch-driven regression from an empty Jakarta world, plus the C-key guard. No edit API is used.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir, writeFile} from 'node:fs/promises';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
const out=process.env.QA_OUT || '/tmp/jakarta-first-train';
await mkdir(out,{recursive:true});
const b=await chromium.launch({channel:'chrome',headless:true});
const ctx=await b.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,deviceScaleFactor:1});
const p=await ctx.newPage(),errors=[],checks=[];
p.setDefaultTimeout(30000);p.on('pageerror',e=>errors.push(e.message));
const check=(value,label)=>{assert.ok(value,label);checks.push(label);console.log('PASS',label)};
const snap=name=>p.screenshot({path:`${out}/${name}.png`});
const state=()=>p.evaluate(async()=>{const tw=window.tw,w=tw.world.value;return {tool:tw.tool.value,tab:tw.tab.value,start:!!tw.tools.start,touchAt:tw.tools.touchAt,edges:w.edges.map(e=>({id:e.id,a:e.a,b:e.b,built:e.built,length:e.lengthM})),stations:w.save.stations,lines:w.save.lines,stats:w.lineStats,cost:w.money.blueprintCost,step:document.querySelector('.guide li.now')?.dataset.step};});
try {
 await p.goto((process.env.FORK_URL || 'http://127.0.0.1:6137/')+'?lang=id&debug=1&demandWorkers=1',{waitUntil:'domcontentloaded'});
 await p.getByRole('button',{name:'Game baru',exact:true}).tap();
 await p.getByRole('button',{name:'Jakarta',exact:true}).tap();
 await p.getByRole('button',{name:'Mulai di Jakarta',exact:true}).tap();
 await p.locator('.jakarta-guide').waitFor({timeout:180000});
 await p.waitForFunction(()=>window.tw&&!window.tw.clock.held.value,{timeout:180000});
 await p.locator(".loading-card-wrap").waitFor({state:"hidden",timeout:30000});
 await p.addStyleTag({content:'#debug-root{display:none!important}'});
 check((await state()).stations.length===0 && (await state()).edges.length===0,'starts in genuinely empty Jakarta');
 check((await state()).step==='track','current step is visible while the checklist is folded');
 await snap('01-empty-jakarta');
 await p.evaluate(()=>window.tw.map.jumpTo({center:[106.823,-6.19],zoom:14}));
 await p.waitForFunction(()=>window.tw.map.getZoom()===14&&!window.tw.map.isMoving());
 await p.locator('[data-guide="track"]').first().tap();
 await p.waitForFunction(()=>window.tw.tool.value==='track');
 await p.touchscreen.tap(80,280);
 await p.waitForFunction(()=>!!window.tw.tools.start,{},{timeout:5000});
 await p.touchscreen.tap(280,330);
 await p.locator('.touch-bar .ok:enabled').waitFor({timeout:8000});
 await p.waitForFunction(()=>window.tw.tools.lastPreview?.ok && window.tw.tools.previewKey===window.tw.tools.wantKey);
 const preview=await p.locator('.tool-tag.build-tag').innerText();await snap('02-track-preview');
 await p.locator('[data-guide-tab="money"]').tap();
 check((await state()).tool==='select','Money disables construction input');
 check(await p.locator('.tool-bar:visible,.touch-bar:visible').count()===0,'Money hides construction controls');
 await p.touchscreen.tap(190,340);check((await state()).edges.length===0,'map tap outside Build does not create track');
 await snap('03-preview-paused-finances');
 await p.locator('[data-guide-tab="build"]').tap();
 await p.locator('.touch-bar .ok:enabled').waitFor();
 check(await p.locator('.tool-tag.build-tag').innerText()===preview,'unconfirmed track preview and quote return unchanged');
 await p.locator('.touch-bar .ok').tap();
 await p.waitForFunction(()=>window.tw.world.value.edges.length>0&&window.tw.world.value.money.blueprintCost>0);
 const before=await state();await snap('04-blueprint');
 await p.locator('[data-guide-tab="lines"]').tap();await p.touchscreen.tap(190,340);
 await p.keyboard.press('c');
 assert.deepEqual((await state()).edges,before.edges);check((await state()).cost===before.cost,'Lines preserves blueprint topology and cost, including after C');
 await p.locator('[data-guide-tab="build"]').tap();check((await state()).cost===before.cost,'returning to Build restores blueprint cost');
 await p.locator('[data-guide="stations"]').first().tap();
 for(const [x,y,count] of [[105,286.25,1],[255,323.75,2]]) {
  await p.touchscreen.tap(x,y);await p.locator('.touch-bar .ok:enabled').waitFor();await p.locator('.touch-bar .ok').tap();
  await p.waitForFunction(count=>window.tw.world.value.save.stations.length===count,count);
 }
 check((await state()).step==='construct','connected station blueprints advance to construction');await snap('05-station-blueprints');
 await p.locator('[data-guide="construct"]').tap();
 await p.waitForFunction(()=>window.tw.world.value.save.stations.length===2&&window.tw.world.value.save.stations.every(s=>s.built));
 check((await state()).step==='line','built connected stations advance to line creation');await snap('06-built-stations');
 await p.locator('[data-guide-tab="lines"]').tap();await p.locator('[data-guide="line"]').tap();
 await p.waitForFunction(()=>window.tw.world.value.save.lines.length===1);
 check((await state()).step==='stops','new line advances to choosing connected stops');
 await snap('07-new-line');
 for(let i=0;i<2;i++) {
  const pos=await p.evaluate(i=>{const tw=window.tw,s=tw.world.value.save.stations[i],v=tw.map.project([s.lng,s.lat]),r=tw.map.getContainer().getBoundingClientRect();return {x:v.x+r.x,y:v.y+r.y};},i);
  await p.touchscreen.tap(pos.x,pos.y);
  await p.waitForFunction(n=>window.tw.world.value.save.lines[0].stops.length===n,i+1);
 }
 await p.locator('#tool-notes button').filter({hasText:'Selesai'}).tap();
 check((await state()).step==='trains','connected line with zero trains still asks for a train');
 await snap('08-stops');
 for(const plus of await p.getByRole('button',{name:'Tambah kereta di lini',exact:true}).all()) {
  await plus.tap();
 }
 await p.waitForFunction(()=>window.tw.world.value.lineStats['0'].trainsNeeded.every(n=>n>=1));
 await p.locator('.guide li.now[data-step="play"]').waitFor();
 check((await state()).step==='play','allocated trains advance to Play, not directly to completion');
 await snap('09-train-assigned');
 await p.locator('[data-guide="play"]').tap();
 await p.waitForFunction(()=>window.tw.clock.running.value);
 await p.waitForFunction(()=>{
  const tw=window.tw,r=tw.renderer,now=tw.clock.now();
  for(let i=0;i<r.trips.length;i+=2)if(tw.tripAt(r.net,r.trips[i],r.trips[i+1]+r.tripsEpoch,now))return true;
  return false;
 },undefined,{timeout:90000});
 const motion=await p.evaluate(()=>{
  const tw=window.tw,r=tw.renderer,now=tw.clock.now(),points=[];
  for(let i=0;i<r.trips.length;i+=2){const profile=r.trips[i],departure=r.trips[i+1]+r.tripsEpoch,at=tw.tripAt(r.net,profile,departure,now);if(at)points.push({profile,departure,s:at.s,x:at.x,y:at.y});}
  return {time:now,points};
 });
 check(motion.points.length>0,'sample contains an active scheduled trip');
 await p.waitForFunction(before=>{
  const tw=window.tw,r=tw.renderer,now=tw.clock.now();
  return before.points.some(old=>{const at=tw.tripAt(r.net,old.profile,old.departure,now);return at&&Math.abs(at.s-old.s)>1;});
 },motion,{timeout:90000});
 check(true,'independent trip sample moved more than one metre');
 await p.waitForFunction(()=>document.querySelector('.guide [data-step="departure"]')?.classList.contains('done'),undefined,{timeout:90000});
 check(!((await state()).step),'guide finishes after an actual scheduled train moves');
 check(await p.locator('.guide-end button').isVisible(),'completion action is visible on phone');
 await snap('10-first-departure');
 const movement=await p.evaluate(before=>({before,afterTime:window.tw.clock.now(),afterPoints:before.points.map(old=>{const tw=window.tw,r=tw.renderer,at=tw.tripAt(r.net,old.profile,old.departure,tw.clock.now());return {...old,afterS:at?.s,afterX:at?.x,afterY:at?.y};}),tripCount:window.tw.renderer.tripCount,lines:window.tw.world.value.save.lines,stats:window.tw.world.value.lineStats}),motion);
 check(errors.length===0,'no JavaScript page errors');
 await writeFile(`${out}/motion.json`,JSON.stringify(movement,null,2));
 await writeFile(`${out}/result.json`,JSON.stringify({checks,errors,state:await state()},null,2));
} catch(e) {await snap('failure');console.error('FAILED_STATE',await state().catch(()=>null));throw e;}
finally {await b.close();}
