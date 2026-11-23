// Node worker bootstrap: browser-worker globals over worker_threads, then the bundled worker module.
import { parentPort, workerData } from "node:worker_threads";
import { install } from "./shims.mjs";
install(globalThis);
const queue = [];
let handler = null;
Object.defineProperty(globalThis, "onmessage", {
  configurable: true,
  get: () => handler,
  set: f => { handler = f; while (handler && queue.length) handler({ data: queue.shift() }) },
});
const typed = new Set();
globalThis.addEventListener = (t, f) => { if (t === "message") typed.add(f) };
globalThis.removeEventListener = (t, f) => { if (t === "message") typed.delete(f) };
globalThis.postMessage = (m, transfer) => parentPort.postMessage(m, Array.isArray(transfer) ? transfer : transfer?.transfer);
globalThis.close = () => process.exit(0);
parentPort.on("message", data => {
  const e = { data, ports: data?.__ports ?? [] };
  if (handler) handler(e); else if (!typed.size) queue.push(data);
  for (const f of typed) f(e);
});
await import(workerData.url);
