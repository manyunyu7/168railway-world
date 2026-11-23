// Capture the web runtime's settled checkpoint. Replay the native ledger to the clock
// in its header, since time spent loading demand and saving is machine-dependent.
// SPIKE_DIST=<unmodified clock bundle> SPIKE_CACHE=<packs> node checkpoint.mjs in.save out.save
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { install, workers } from "./shims.mjs";

const [input, output] = process.argv.slice(2);
if (!input || !output) throw Error("Supply an input save and a new checkpoint path");
const bytes = fs.readFileSync(input);
const decode = bytes => {
  const raw = gunzipSync(bytes);
  assert.equal(raw.toString("ascii", 0, 4), "TWG1");
  return JSON.parse(raw.toString("utf8", 8, 8 + raw.readUInt32LE(4)));
};
const original = decode(bytes);
install(globalThis);
const assets = path.join(process.env.SPIKE_DIST, "assets");
const entry = fs.readdirSync(assets).find(name => /^clock-.*\.js$/.test(name));
await import(path.join(assets, entry));
const { port1, port2 } = new MessageChannel();
const send = data => port1.postMessage(JSON.stringify(data));
const preview = process.env.SPIKE_TRAIN_PREVIEW;
let previewRows = [];
let chunks, size, offset = 0, playing = false, saving = false;
try {
  const checkpoint = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error("Clock reference timed out")), 180000);
    timeout.unref();
    port1.onmessage = event => {
      try {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
          case "ready":
            send({ type: "open", id: 1, size: bytes.length, trainLoads: !!preview });
            for (let k = 0; k < bytes.length; k += 49152) {
              send({ type: "input", id: 1, offset: k, base64: bytes.subarray(k, k + 49152).toString("base64") });
            }
            send({ type: "finish", id: 1 });
            break;
          case "trainLoads":
            if (preview && !playing) previewRows.push(...msg.rows);
            break;
          case "opened":
            if (preview) { send({type:"pause"}); break; }
            playing = true;
            send({ type: "play", rate: 240 });
            break;
          case "state":
            if (preview && !playing && previewRows.length) {
              fs.writeFileSync(preview,JSON.stringify({clock:msg.clock,rows:previewRows}));
              previewRows = [];
              playing = true;
              send({type:"play",rate:240});
            }
            if (playing && !saving && msg.clock >= original.clock + 3600) {
              saving = true;
              send({ type: "pause" });
              send({ type: "snapshot", id: 2 });
            }
            break;
          case "snapshotBegin":
            assert.equal(msg.id, 2);
            size = msg.size;
            assert.ok(size > 0 && size <= 8 * 1024 * 1024);
            chunks = [];
            break;
          case "output": {
            assert.equal(msg.id, 2);
            assert.equal(msg.offset, offset);
            const chunk = Buffer.from(msg.base64, "base64");
            offset += chunk.length;
            assert.ok(offset <= size);
            chunks.push(chunk);
            break;
          }
          case "snapshotEnd": {
            assert.equal(msg.id, 2);
            assert.equal(size, offset);
            const result = Buffer.concat(chunks);
            const header = decode(result);
            assert.equal(header.clock, msg.clock);
            assert.ok(header.clock > original.clock);
            assert.ok(Number.isFinite(header.cash));
            clearTimeout(timeout);
            resolve(result);
            break;
          }
          case "error":
            throw Error(msg.message);
        }
      } catch (error) { reject(error); }
    };
    globalThis.dispatchEvent({ type: "message", data: JSON.stringify({ type: "connect" }), ports: [port2], source: null, origin: "" });
  });
  fs.writeFileSync(output, checkpoint, { flag: "wx" });
  const end = decode(checkpoint);
  console.log(JSON.stringify({ clock: end.clock, cash: end.cash, ledger: end.ledger?.at(-1) }));
} finally {
  port1.close();
  await Promise.all(workers.map(w => w.terminate()));
}
// The bundled UI owns repeating browser timers.
process.exit(0);
