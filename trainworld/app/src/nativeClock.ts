// Local WebView entry. The only bridge is a single transferred MessagePort on the APK's virtual origin.
import { effect } from "@preact/signals";
import { activeCity, cityById, cityWithin, isCity, viewedZone } from "./game/cities";
import { clock, startClock } from "./game/clock";
import { makeFrame } from "./game/frame";
import { viewCentre } from "./game/listScope";
import { demandStopped, gameFailed, gameReady, loadFailure, startGameWait, zoneFailures } from "./game/loading";
import { nativeTrainLoads } from "./game/nativeTrainLoads";
import { liveMoney } from "./game/money";
import { clockWorkerStatus, ClockClient, exportForShare, gamesLoaded, onNetAhead, onNetworkSnapshot, onStationsAhead } from "./workers/clockClient";
import { netAhead, stationsAhead, startDemand, submitNetwork } from "./workers/demandClient";
import { decodeSave } from "./workers/saveFile";

const ORIGIN = "https://studio-clock.invalid";
const MAX_BYTES = 8 * 1024 * 1024;
const CHUNK = 48 * 1024;
type Id = string | number;
type Input = { id: Id; bytes: Uint8Array<ArrayBuffer>; offset: number };
type Command = { type?: string; id?: Id; size?: number; offset?: number; base64?: string; rate?: number; trainLoads?: boolean; overlay?: string; overlayCity?: string };

function attach(port: MessagePort) {
  let input: Input | null = null;
  let opening = false;
  let opened = false;
  let failed = false;
  let openedId: Id = "";
  let wantLoads = false;
  let overlay: string | undefined, overlayCity: string | undefined;
  let exporting = false;
  const send = (message: object) => port.postMessage(JSON.stringify(message));
  const error = (message: string, id?: Id) => {
    if (failed) return;
    failed = true;
    if (clock) clock.setRunning(false);
    send({ type: "error", message, id });
  };
  function state() {
    const { cash, today } = liveMoney.peek();
    return { clock: clock.now(), cash, boardings: today?.rides ?? 0, fares: today?.fares ?? 0, upkeep: today?.upkeep ?? 0 };
  }

  async function open(bytes: Uint8Array<ArrayBuffer>, id: Id) {
    const { header } = await decodeSave(bytes);
    const city = [header.home, header.start, ...(header.opened ?? []), header.city].find(c => !!c && isCity(c));
    if (!city || !Number.isFinite(header.clock)) throw Error("Kota atau waktu arsip tidak dikenali.");
    openedId = id;
    startClock(header.clock);
    clock.setRunning(false);
    clock.setBackground(true); // Android pauses explicitly before saving or leaving.
    clock.setHeld(true);
    startGameWait("android");
    // Legacy packs share NYC's world frame even for Jakarta. The first real station locates
    // their demand before the pool starts. Empty networks use the saved start or home zone.
    let started = false;
    const startPool = (inferred?: string | null) => {
      if (started) return;
      started = true;
      const focus = cityById([overlay && overlayCity, header.home, header.start, inferred, city].find(c => c && isCity(c))!);
      activeCity.value = viewedZone.value = focus.id;
      viewCentre.value = focus.center;
      startDemand(focus.id, false, true);
    };
    onNetworkSnapshot(snap => { startPool(); submitNetwork(snap); });
    onStationsAhead((frame, xy, fine, built) => {
      const coordinate = makeFrame(frame).toLonLat(xy[0], xy[1]);
      startPool(cityWithin(...coordinate));
      stationsAhead(frame, xy, fine, built);
    });
    onNetAhead(netAhead);
    const client = new ClockClient(city);
    let mapNet: import("./map/network").NetworkBuffers | undefined;
    if (overlay === "network") client.onState = s => {
      if (s.net) mapNet = s.net;
      if (mapNet && s.service) { mapNet.phases = s.render.phases; mapNet.meta = s.render.meta; }
    };
    const publishLoads = wantLoads ? nativeTrainLoads(client,send) : null;
    openedId = id;
    effect(() => {
      const status = clockWorkerStatus.value;
      const why = loadFailure.value || zoneFailures.value.values().next().value;
      if (why || demandStopped.value || gameFailed.value || status.startsWith("failed") || status.includes("panic")) {
        error(why || "Studio gagal memuat mesin atau data penumpang.", id);
      } else if (!failed && gamesLoaded.value && gameReady.value && !opened) {
        opened = true;
        send({ type: "opened", id, ...state() });
      }
    });
    if (overlay) {
      const kind = overlay;
      let busy = false;
      const timer = setInterval(async () => {
        if (failed) { clearInterval(timer); return; }
        if (!opened || busy) return;
        busy = true;
        try {
          const {publishMapLayers} = await import("./game/nativeMapLayers");
          if (await publishMapLayers(kind,overlayCity,send,mapNet)) clearInterval(timer);
        } catch (e) { clearInterval(timer); error(String(e),id); }
        finally { busy = false; }
      }, 750);
    }
    client.start({ nativeArchive: bytes.buffer });
    setInterval(() => {
      if (!opened || failed) return;
      clock.tick();
      publishLoads?.(clock.now());
      send({ type: "state", ...state() });
    }, 500);
  }

  async function snapshot(id: Id) {
    if (exporting) throw Error("Ekspor sebelumnya masih berjalan.");
    exporting = true;
    try {
      const bytes = await exportForShare();
      if (!bytes || !bytes.length || bytes.length > MAX_BYTES) throw Error("Arsip hasil simulasi tidak bisa disimpan.");
      // Saving settles the worker's ledger. The archive clock is the exact cut used for the native checkpoint.
      const { header } = await decodeSave(bytes);
      send({ type: "snapshotBegin", id, size: bytes.length });
      for (let offset = 0; offset < bytes.length; offset += CHUNK) {
        const chunk = bytes.subarray(offset, offset + CHUNK);
        send({ type: "output", id, offset, base64: btoa(String.fromCharCode(...chunk)) });
      }
      send({ type: "snapshotEnd", id, clock: header.clock });
    } finally { exporting = false; }
  }

  async function handle(m: Command) {
    if (failed) return;
    if (m.type === "open" && !opening && !input && m.id != null && Number.isInteger(m.size) && m.size! > 0 && m.size! <= MAX_BYTES) {
      overlay = (m.overlay === "trips" || m.overlay === "network") ? m.overlay : undefined;
      overlayCity = m.overlayCity;
      wantLoads = m.trainLoads === true;
      input = { id: m.id, offset: 0, bytes: new Uint8Array(m.size!) };
    } else if (m.type === "input" && input && m.id === input.id && m.offset === input.offset && typeof m.base64 === "string" && m.base64.length <= 4 * CHUNK / 3) {
      const data = atob(m.base64);
      if (!data.length || input.offset + data.length > input.bytes.length) throw Error("Potongan arsip tidak sesuai.");
      for (let i = 0; i < data.length; i++) input.bytes[input.offset++] = data.charCodeAt(i);
    } else if (m.type === "finish" && input && m.id === input.id && input.offset === input.bytes.length) {
      const received = input;
      input = null;
      opening = true;
      await open(received.bytes, received.id);
    } else if (m.type === "pause" && opening) {
      if (clock) clock.setRunning(false);
      if (opened) send({ type: "state", ...state() });
    } else if (opened && m.type === "play" && typeof m.rate === "number" && Number.isFinite(m.rate) && m.rate >= 1 && m.rate <= 60000) {
      clock.setMultiple(m.rate / 60);
    } else if (opened && m.type === "snapshot" && m.id != null) await snapshot(m.id);
    else throw Error("Perintah arsip atau simulasi tidak sesuai.");
  }
  port.onmessage = e => {
    try {
      if (typeof e.data !== "string" || e.data.length > CHUNK * 2) throw Error("Pesan terlalu besar.");
      const msg = JSON.parse(e.data) as Command;
      void handle(msg).catch(err => error(String(err), msg?.id ?? openedId));
    } catch (err) { error(String(err), openedId); }
  };
  port.start();
  send({ type: "ready" });
}

if (location.origin === ORIGIN && location.pathname === "/clock/index.html" && window.top === window) {
  const connect = (e: MessageEvent) => {
    if ((e.source !== null && e.source !== window) || (e.origin && e.origin !== "null" && e.origin !== ORIGIN) || e.ports.length !== 1) return;
    try { if (JSON.parse(e.data)?.type !== "connect") return; } catch { return; }
    removeEventListener("message", connect);
    attach(e.ports[0]);
  };
  addEventListener("message", connect);
  // WebView.onPageFinished can race module evaluation. Native probes this single flag
  // before transferring its port, which Android permits only once.
  Object.defineProperty(window, "__studioClockReady", { value: true });
}
