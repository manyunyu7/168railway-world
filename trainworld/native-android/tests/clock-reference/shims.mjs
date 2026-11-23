// Browser shims for running the native-clock bundle headless (Node baseline; mirrored for QuickJS).
import fs from "node:fs";
import path from "node:path";
import { Worker as NodeWorker } from "node:worker_threads";
export const workers = [];
const boot = new URL("./worker-boot.mjs", import.meta.url);
class SpikeWorker {
  constructor(url) {
    const w = new NodeWorker(boot, { workerData: { url: String(url) } });
    workers.push(w);
    this.w = w; this.onmessage = null; this.onerror = null; this.ls = new Set();
    w.on("message", data => { const e = { data }; this.onmessage?.(e); for (const f of this.ls) f(e) });
    w.on("error", err => { console.error("worker error", String(url).split("/").pop(), err?.stack ?? err); this.onerror?.({ message: String(err), error: err }) });
  }
  postMessage(m, transfer) {
    if (process.env.SPIKE_CLOCK_INPUTS && (m.kind === "init" || m.kind === "open")) {
      const keys = Object.fromEntries(Object.entries(m).map(([k,v]) => [k, Array.isArray(v) ? v.map(x => x?.id ?? (typeof x === "number" ? x : typeof x)) : typeof v]));
      fs.appendFileSync(path.join(process.env.SPIKE_CLOCK_INPUTS, "packs.jsonl"), JSON.stringify(keys) + "\n");
    }
    if (process.env.SPIKE_CLOCK_INPUTS && ["income", "riders", "regionShares", "transfers", "points", "solve"].includes(m.kind)) {
      const key = m.kind === "riders" ? "riders" + m.day : m.kind;
      fs.writeFileSync(path.join(process.env.SPIKE_CLOCK_INPUTS, key + ".json"),
        JSON.stringify(m, (_, v) => ArrayBuffer.isView(v) ? Array.from(v) : v));
    }
    this.w.postMessage(m, Array.isArray(transfer) ? transfer : transfer?.transfer);
  }
  addEventListener(t, f) { if (t === "message") this.ls.add(f); else if (t === "error") this.onerror = f }
  removeEventListener(t, f) { this.ls.delete(f) }
  terminate() { this.w.terminate() }
}
export const ORIGIN = "https://studio-clock.invalid";
export const DIST = process.env.SPIKE_DIST;
export const CACHE = process.env.SPIKE_CACHE;
export const REMOTE = "https://168railway.com/studio-data/2026-11-18/";
export const log = { fetches: [] };

class MemStorage {
  #m = new Map();
  getItem(k) { return this.#m.has(k) ? this.#m.get(k) : null }
  setItem(k, v) { this.#m.set(k, String(v)) }
  removeItem(k) { this.#m.delete(k) }
  clear() { this.#m.clear() }
  key(i) { return [...this.#m.keys()][i] ?? null }
  get length() { return this.#m.size }
}

export function install(g) {
  const def = (k, v) => Object.defineProperty(g, k, { value: v, configurable: true, writable: true });
  def("window", g); def("self", g); def("top", g);
  def("location", new URL(ORIGIN + "/clock/index.html"));
  def("localStorage", new MemStorage()); def("sessionStorage", new MemStorage());
  const listeners = {};
  g.addEventListener = (t, f) => (listeners[t] ??= new Set()).add(f);
  g.removeEventListener = (t, f) => listeners[t]?.delete(f);
  g.dispatchEvent = e => { for (const f of listeners[e.type] ?? []) f(e); return true };
  def("document", {
    visibilityState: "visible", hidden: false, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return true },
    createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, addEventListener() {}, getContext: () => null }),
    querySelectorAll: () => [], querySelector: () => null, documentElement: { lang: "id", dir: "ltr", style: { setProperty() {}, removeProperty() {} }, dataset: {}, classList: { add() {}, remove() {}, toggle() {}, contains: () => false }, setAttribute() {}, getAttribute: () => null, removeAttribute() {} },
    fonts: { ready: Promise.resolve(), add() {} }, body: { appendChild() {}, classList: { add() {}, remove() {} } }, head: { appendChild() {} },
  });
  def("navigator", {
    hardwareConcurrency: 4, deviceMemory: 4, language: "id", languages: ["id"], onLine: true, userAgent: "spike", maxTouchPoints: 0,
    locks: { request: async (n, o, cb) => (typeof o === "function" ? o : cb)({ name: n }) },
    storage: { estimate: async () => ({ quota: 1e9, usage: 0 }), persist: async () => false, persisted: async () => false },
  });
  def("Worker", SpikeWorker);
  def("MutationObserver", class { observe() {} disconnect() {} takeRecords() { return [] } });
  def("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  def("IntersectionObserver", class { observe() {} unobserve() {} disconnect() {} });
  def("matchMedia", () => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }));
  def("requestAnimationFrame", f => setTimeout(() => f(performance.now()), 16));
  def("cancelAnimationFrame", id => clearTimeout(id));
  g.__realFetch ??= g.fetch;
  def("fetch", spikeFetch);
}

function packPath(rel) { return path.join(CACHE, rel.replace(/[^A-Za-z0-9_./-]/g, "_")) }
async function packBytes(rel) {
  const f = packPath(rel);
  if (!fs.existsSync(f)) {
    const r = await globalThis.__realFetch(REMOTE + rel);
    if (!r.ok) return null;
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, Buffer.from(await r.arrayBuffer()));
  }
  return fs.readFileSync(f);
}
const mime = p => p.endsWith(".wasm") ? "application/wasm" : /\.(geo)?json$/.test(p) ? "application/json" : p.endsWith(".js") ? "text/javascript" : "application/octet-stream";

export async function spikeFetch(input, init) {
  const url = new URL(typeof input === "string" ? input : input.url ?? String(input), ORIGIN + "/clock/index.html");
  const t0 = performance.now();
  let body = null, status = 200;
  if (url.protocol === "file:") body = fs.readFileSync(url);
  else if (/^\/(clock\/)?packs\//.test(url.pathname)) {
    const all = await packBytes(url.pathname.replace(/^\/(clock\/)?packs\//, ""));
    if (!all) status = 404;
    else {
      const range = url.searchParams.get("studioRange");
      if (range) { const [a, b] = range.split("-").map(Number); body = all.subarray(a, b + 1) } else body = all;
    }
  } else if (url.pathname.startsWith("/clock/")) {
    const f = path.join(DIST, url.pathname.slice("/clock/".length));
    if (fs.existsSync(f)) body = fs.readFileSync(f); else status = 404;
  } else status = 404;
  log.fetches.push({ url: url.pathname + url.search, status, bytes: body?.length ?? 0, ms: +(performance.now() - t0).toFixed(1) });
  return new Response(status === 200 ? body : null, { status, headers: { "content-type": mime(url.pathname) } });
}
