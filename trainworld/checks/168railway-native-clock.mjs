// Local end-to-end check for the same virtual HTTPS origin and chunked port used on Android.
// Usage: node checks/168railway-native-clock.mjs <Bogor normal TWG1> <flat pinned pack mirror>
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createServer } from "node:https";
import { createRequire } from "node:module";
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, extname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync, gzipSync } from "node:zlib";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright-core");
const [fixture, packDir, mode] = process.argv.slice(2);
const tutorial = mode === "tutorial";
const layers = mode === "layers";
const editor = mode === "editor" || tutorial || layers;
assert(fixture && packDir, "Pass a normal Bogor .save and a flat local Studio data directory.");
const archive = readFileSync(fixture);
const build = resolve(dirname(fileURLToPath(import.meta.url)), "../app/dist-native-clock");
const mirror = resolve(packDir);
const temporary = mkdtempSync(join(tmpdir(), "studio-clock-test-"));
const origin = "https://studio-clock.invalid";
const mime = { ".js": "text/javascript", ".html": "text/html", ".css":"text/css", ".svg":"image/svg+xml", ".woff2":"font/woff2", ".png":"image/png", ".json": "application/json", ".geojson": "application/json", ".wasm": "application/wasm" };
let browser, server;

function header(bytes) {
  const raw = gunzipSync(bytes);
  assert.equal(raw.toString("ascii", 0, 4), "TWG1");
  return JSON.parse(raw.subarray(8, 8 + raw.readUInt32LE(4)));
}

function serve(request, response) {
  const url = new URL(request.url, origin);
  const path = url.pathname;
  const pack = path.startsWith("/clock/packs/");
  const root = pack ? mirror : build;
  const file = resolve(root, "." + path.slice((pack ? "/clock/packs" : "/clock").length));
  if (!path.startsWith("/clock/") || !file.startsWith(root + sep) || !existsSync(file) || !statSync(file).isFile()) {
    response.writeHead(404).end();
    return;
  }
  const bytes = readFileSync(file);
  response.setHeader("Content-Type", mime[extname(file)] || "application/octet-stream");
  const nativeRange = url.searchParams.get("studioRange");
  if (nativeRange) assert.equal(request.headers.range, undefined);
  const range = /^bytes=(\d+)-(\d*)$/.exec(nativeRange ? "bytes=" + nativeRange : request.headers.range || "");
  if (!range) return response.end(bytes);
  const start = Number(range[1]), end = Math.min(Number(range[2] || bytes.length - 1), bytes.length - 1);
  if (start > end) return response.writeHead(416).end();
  if (!nativeRange) response.setHeader("Content-Range", `bytes ${start}-${end}/${bytes.length}`);
  response.writeHead(nativeRange ? 200 : 206).end(bytes.subarray(start, end + 1));
}

try {
  const key = join(temporary, "key.pem"), cert = join(temporary, "cert.pem");
  execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-keyout", key, "-out", cert, "-subj", "/CN=studio-clock.invalid"], { stdio: "ignore" });
  server = createServer({ key: readFileSync(key), cert: readFileSync(cert) }, serve);
  await new Promise(done => server.listen(0, "127.0.0.1", done));
  // All other domains fail DNS. A missing city pack never escapes to a public server.
  browser = await chromium.launch({ channel: process.env.CHROME_CHANNEL || "chrome", args: ["--enable-unsafe-swiftshader", "--no-proxy-server", "--ignore-certificate-errors",
    `--host-resolver-rules=MAP studio-clock.invalid 127.0.0.1:${server.address().port}, MAP * ~NOTFOUND`] });
  const page = await browser.newPage({ ignoreHTTPSErrors: true });
  page.setDefaultTimeout(180_000);

  async function wait(type) {
    await page.waitForFunction(type => window.messages.some(m => m.type === type || m.type === "error"), type);
    const received = await page.evaluate(type => window.messages.find(m => m.type === type || m.type === "error"), type);
    assert.equal(received.type, type, received.message);
    return received;
  }
  const send = message => page.evaluate(message => window.channel.postMessage(JSON.stringify(message)), message);
  async function connect() {
    await page.goto(origin + (editor ? "/clock/editor.html?debug=1" : "/clock/index.html"));
    await page.waitForFunction(() => window.__studioClockReady === true);
    await page.evaluate(() => {
      window.messages = [];
      const ports = new MessageChannel();
      window.channel = ports.port1;
      ports.port1.onmessage = e => window.messages.push(JSON.parse(e.data));
      window.postMessage(JSON.stringify({ type: "connect" }), location.origin, [ports.port2]);
    });
    await wait("ready");
  }
  async function open(bytes, id, showTutorial = false, mapLayer) {
    await send({ type: "open", id, size: bytes.length, showTutorial, mapLayer, trainLoads: !editor });
    for (let offset = 0; offset < bytes.length; offset += 49152) {
      await send({ type: "input", id, offset, base64: bytes.subarray(offset, offset + 49152).toString("base64") });
    }
    await send({ type: "finish", id });
    return wait("opened");
  }

  if (layers) {
    const groups = {
      "Permintaan di peta": { home: "Rumah", work: "Destinasi", both: "Total" },
      "Penumpang stasiun": { origins: "Asal", transfers: "Pindah", total: "Total" },
      "Tampilan rel": { traffic: "Trafik", speed: "Laju", height: "Tingkat", profit: "Laba" },
    };
    for (const [group, choices] of Object.entries(groups)) {
      for (const [mode, label] of Object.entries(choices)) {
        await connect();
        await open(archive, mode, false, mode);
        const row = page.getByRole("group", {name:group, exact:true});
        const active = row.getByRole("button", {name:label, exact:true, pressed:true});
        await active.waitFor();
        assert.equal(await row.locator("button[aria-pressed=true]").count(), 1);
        console.log("OK lapisan", group, "→", label);
      }
    }
  } else if (tutorial) {
    // These are disposable copies of the fixture with deliberately different saved guide states.
    const original = gunzipSync(archive);
    const headerBytes = original.readUInt32LE(4);
    const scene = (guide,gameMode="normal") => {
      const json = Buffer.from(JSON.stringify({...header(archive),mode:gameMode,guide}));
      const prefix = Buffer.from(original.subarray(0,8));
      prefix.writeUInt32LE(json.length,4);
      return gzipSync(Buffer.concat([prefix,json,original.subarray(8+headerBytes)]));
    };
    const mask = 1 << 14;
    async function checkpointGuide() {
      await send({type:"snapshot",id:5});
      await wait("snapshotEnd");
      const chunks = await page.evaluate(() => window.messages.filter(m=>m.type==="output").map(m=>m.base64));
      return header(Buffer.concat(chunks.map(b=>Buffer.from(b,"base64")))).guide;
    }
    await connect();
    await open(scene({done:mask,card:2,hidden:true}),1,true);
    await page.locator('.guide.open[data-card="2"]').waitFor();
    const resumed = await checkpointGuide();
    console.log("OK Kartu tersembunyi dipulihkan");
    assert.equal(resumed.card,2);
    assert.equal(resumed.done & mask,mask);
    assert.equal(!!resumed.hidden,false);
    await connect();
    await open(scene({done:mask,card:2,closed:true}),2,true);
    await page.getByRole("button",{name:"Kembali ke dunia"}).click({noWaitAfter:true});
    assert.equal((await checkpointGuide()).closed,true);
    await connect();
    console.log("OK Menolak mulai ulang mempertahankan penyelesaian");
    await open(scene({done:mask,card:2,closed:true}),3,true);
    await page.getByRole("button",{name:"Mulai ulang",exact:true}).click({noWaitAfter:true});
    await page.locator('.guide.open[data-card="1"]').waitFor();
    assert.equal(!!(await checkpointGuide()).closed,false);
    await connect();
    console.log("OK Mulai ulang kartu selesai");
    await open(scene(undefined,"sandbox"),4,true);
    await page.locator(".guide.open").waitFor();
    assert((await checkpointGuide()) != null);
    console.log("PASS tutorial: resume hidden card and checks, decline/accept completed restart, explicit sandbox guide");
  } else if (editor) {
    await connect();
    await open(archive, 1);
    await page.getByText(/penumpang hari ini/).first().waitFor();
    const line = page.getByText("Commuter Line Bogor", {exact:true}).first();
    await line.click({noWaitAfter:true});
    await page.locator(".marey-open").click({noWaitAfter:true});
    await page.locator(".marey .marey-time").first().waitFor();
    console.log("OK Marey satu jalur");
    await page.locator(".marey-close").click({noWaitAfter:true});
    await page.getByRole("button",{name:"Ganti nama",exact:true}).click({noWaitAfter:true});
    const name = page.locator("input.name-edit");
    const editedName = "Bogor · slot Android";
    await page.waitForFunction(() => document.activeElement?.matches("input.name-edit"));
    await name.fill(editedName);
    await name.press("Enter");
    await page.getByText(editedName,{exact:true}).first().waitFor();
    await page.getByRole("button",{name:"Kelola grup lini"}).click({noWaitAfter:true});
    const picks = page.locator(".native-line-groups input[type=checkbox]");
    await picks.nth(0).check();
    await picks.nth(1).check();
    await page.getByRole("button",{name:"Terapkan grup"}).click({noWaitAfter:true});
    await page.locator("li.group-row").first().click({noWaitAfter:true});
    await page.getByRole("button",{name:"Grafik jadwal",exact:true}).click({noWaitAfter:true});
    await page.locator("dialog.sched-panel .marey-time").first().waitFor();
    console.log("OK Marey grup dari dua jalur");
    await page.locator(".sp-close").click({noWaitAfter:true});
    await page.getByRole("button",{name:"Kota",exact:true}).click({noWaitAfter:true});
    await page.locator("button.map-diagram").click({noWaitAfter:true});
    await page.locator("button.diagram-save").waitFor({timeout:90000});
    await page.locator("button.diagram-save").click({noWaitAfter:true});
    await wait("pictureEnd");
    const png = await page.evaluate(() => window.messages.filter(m=>m.type==="picturePart").map(m=>m.base64));
    const picture = Buffer.concat(png.map(b=>Buffer.from(b,"base64")));
    assert.equal(picture.readUInt32BE(0),0x89504e47);
    assert(picture.readUInt32BE(16) > 100 && picture.readUInt32BE(20) > 100);
    console.log("OK Diagram PNG dari worker");
    await page.locator("button.diagram-exit").click({noWaitAfter:true});
    await page.getByRole("button",{name:"Bangun",exact:true}).first().click({noWaitAfter:true});
    await page.locator(".tool-pick").first().waitFor();
    const travel = page.getByRole("button",{name:"Lihat perjalanan",exact:true});
    await travel.click({noWaitAfter:true});
    // Project two locations on the real commuter line. Only the QA browser enables the game's debug camera.
    await page.evaluate(() => window.tw.map.jumpTo({center:[106.827,-6.152],zoom:14}));
    const stops = await page.evaluate(() => {
      const box = window.tw.map.getCanvas().getBoundingClientRect();
      return [[106.8260,-6.1497],[106.8275,-6.1605]].map(ll => {
        const p = window.tw.map.project(ll);
        return [box.left+p.x,box.top+p.y];
      });
    });
    await page.mouse.click(...stops[0]);
    await page.waitForFunction(() => window.tw.selection.peek()?.kind === "trip");
    await page.mouse.click(...stops[1]);
    await page.waitForFunction(() => !!window.tw.selection.peek()?.to);
    await page.locator(".h").filter({hasText:/^Mobil$/}).first().waitFor();
    await page.locator(".h").filter({hasText:/^Angkutan umum/}).first().waitFor();
    console.log("OK Perjalanan kereta dan mobil");
    await travel.click({noWaitAfter:true});
    const rail = await page.evaluate(() => {
      const {map,overlay,renderer,pick} = window.tw;
      const r = map.getCanvas().getBoundingClientRect(), track = renderer.net.track;
      for (let i = 0; i < track.count; i++) {
        const [ax,ay,bx,by] = track.seg.subarray(i*4,i*4+4);
        const [x,y] = overlay.toScreen((ax+bx)/2,(ay+by)/2);
        if (x < r.width*0.30 || x > r.width*0.70 || y < r.height*0.35 || y > r.height*0.75) continue;
        const hit = pick(overlay,x,y,true);
        if (hit?.kind === "track" || hit?.kind === "line") return [r.left+x,r.top+y];
      }
      throw Error("Expected visible Bogor track in the QA view.");
    });
    // Repeated taps cycle through overlapping lines and the underlying piece.
    for (let i=0; i<4; i++) {
      await page.mouse.click(...rail);
      if (await page.locator(".piece-rebuild").isVisible()) break;
    }
    await page.locator(".piece-rebuild").waitFor();
    await page.locator(".piece-rebuild").getByRole("button",{name:"Rel tunggal",exact:true}).click({noWaitAfter:true});
    await page.waitForFunction(() => document.querySelector(".piece-rebuild .tb-count button")?.getAttribute("aria-pressed") === "true");
    console.log("OK Pemeriksa potongan rel");
    await send({ type:"snapshot", id:2 });
    await wait("snapshotEnd");
    const chunks = await page.evaluate(() => window.messages.filter(m=>m.type==="output"));
    const bytes = Buffer.concat(chunks.map(m=>Buffer.from(m.base64,"base64")));
    assert.equal(header(bytes).clock,header(archive).clock);
    await page.goto("about:blank");
    await connect();
    await open(bytes,3);
    await page.getByRole("button",{name:"Lini",exact:true}).first().click({noWaitAfter:true});
    await page.locator("li.group-row").first().waitFor();
    // Editing the line and making its group are persistent changes to the underlying TWG1.
    await page.locator("li.group-row").first().click({noWaitAfter:true});
    const unfold = page.locator('li.group-row button.chevron[aria-expanded="false"]').first();
    if (await unfold.count()) await unfold.click({noWaitAfter:true});
    await page.getByText(editedName,{exact:true}).first().waitFor();
    console.log("PASS offline full editor: line rename, line and group Marey, network diagram and PNG, track-piece inspector, passenger trip, same TWG1 reopened");
  } else {
    await connect();
    const before = await open(archive, 1);
    assert.equal(before.clock, header(archive).clock);
    await page.waitForFunction(() => window.messages.some(m=>m.type==="trainLoads" && m.rows.some((v,i)=>i%3===2 && v>0)));
    assert(await page.evaluate(t=>window.messages.filter(m=>m.type==="state").every(m=>m.clock===t),before.clock));
    await send({ type: "play", rate: 3600 });
    await page.waitForFunction(start => window.messages.some(m => m.type === "state" && m.clock > start + 3600), before.clock);
    await page.waitForFunction(() => window.messages.some(m => m.type==="trainLoads" && m.rows.some((value,i)=>i%3===2 && value>0)));
    const display = await page.evaluate(() => window.messages.filter(m=>m.type==="trainLoads").flatMap(m=>m.rows));
    assert(display.every((v,i)=>Number.isFinite(v) && (i%3!==2 || v>=0 && v<=200)));
    await send({ type: "pause" });
    await send({ type: "snapshot", id: 2 });
    const end = await wait("snapshotEnd");
    const parts = await page.evaluate(() => window.messages.filter(m => m.type === "output"));
    let offset = 0;
    const saved = Buffer.concat(parts.map(part => {
      assert.equal(part.id, 2);
      assert.equal(part.offset, offset);
      const bytes = Buffer.from(part.base64, "base64");
      offset += bytes.length;
      return bytes;
    }));
    assert.equal((await wait("snapshotBegin")).size, saved.length);
    const h = header(saved);
    assert.equal(h.clock, end.clock);
    assert(h.clock > before.clock + 3600);
    const day = h.ledger.at(-1);
    assert(day.rides > 0 && day.fares > 0 && day.upkeep > 0 && day.running > 0);
    assert.notEqual(h.cash, before.cash);
    await page.waitForFunction(t => window.messages.filter(m => m.type === "state" && m.clock === t).length >= 2, h.clock);
    await page.goto("about:blank");
    await connect();
    const reopened = await open(saved, 3);
    assert.equal(reopened.clock, h.clock);
    assert.equal(reopened.cash, h.cash);
    assert.equal(reopened.boardings, day.rides);
    console.log("PASS normal Studio clock, demand, income, upkeep, pause, chunked TWG1 and reopen", JSON.stringify({ clock: h.clock, cash: h.cash, rides: day.rides }));
  }
} finally {
  await browser?.close();
  if (server) await new Promise(done => server.close(done));
  rmSync(temporary, { recursive: true, force: true });
}
