// Read-only mobile guest verification. Start a fresh game in a disposable browser profile.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const url=process.env.FORK_URL || 'https://168railway.com/studio/';
const browser=await chromium.launch({channel:'chrome'});
try {
  const page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true,deviceScaleFactor:2});
  page.setDefaultTimeout(180000);
  const ranges=[],pending=[],overlay=[];
  page.on('response',r=>{
    const u=new URL(r.url());
    if(u.pathname.includes('/noritetsu/'))overlay.push(u.pathname);
    if(u.pathname.endsWith('/nyc.bin'))pending.push(r.allHeaders().then(h=>ranges.push({status:r.status(),range:h['content-range'],length:h['content-length']})));
  });
  const start=Date.now();
  await page.goto(url,{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'New game',exact:true}).tap();
  await page.getByRole('button',{name:'Start in New York',exact:true}).tap();
  const clock=page.locator('.clock-text .time');
  await clock.waitFor();
  const initial=(await clock.innerText()).trim(),readyMs=Date.now()-start;
  await page.locator('button.rate:visible').first().tap();
  await page.waitForFunction(old=>{
    const t=document.querySelector('.clock-text .time')?.textContent?.trim();
    return !!t && t!==old;
  },initial,{timeout:300000});
  await Promise.all(pending);
  assert.ok(ranges.length);
  const totals=new Set();
  for(const r of ranges) {
    if(r.status===200)continue;
    assert.equal(r.status,206);
    const m=/^bytes (\d+)-(\d+)\/(\d+)$/.exec(r.range);
    assert.ok(m);
    const [,lo,hi,total]=m.map(Number);
    assert.ok(lo>=0 && hi>=lo && hi<total);
    if(r.length)assert.equal(+r.length,hi-lo+1);
    totals.add(total);
  }
  assert.ok(totals.size<=1);
  if(process.env.EXPECT_NO_OVERLAY==='1')assert.deepEqual(overlay,[]);
  console.log(JSON.stringify({initial,current:await clock.innerText(),readyMs,elapsedMs:Date.now()-start,ranges,overlay}));
  if(process.env.SCREENSHOT) {
    const c=await page.context().newCDPSession(page);
    const {data}=await c.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    const {writeFile}=await import('node:fs/promises');
    await writeFile(process.env.SCREENSHOT,Buffer.from(data,'base64'));
  }
} finally {await browser.close()}
