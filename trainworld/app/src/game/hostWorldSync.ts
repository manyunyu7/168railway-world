// Synchronize a committed browser save with the same private archive used by the phone.
import { signal } from "@preact/signals";
import { listSlots, readHostLink, readStored, slotName, writeHostLink, type HostWorldSource, type SaveSlot } from "../workers/saveFile";

const api = "/api/web/studio";
type Session = { authenticated: boolean; userId: string | null; csrfToken: string | null };
export const hostSyncStatus = signal("");
let session: Promise<Session | null> | undefined;
let owner: string | undefined;
let watching = false;
const timers = new Map<string, ReturnType<typeof setTimeout>>();
const lastTry = new Map<string, number>();
const offline = "Progres tersimpan di browser. Sinkronisasi akun akan dicoba kembali.";

async function fetchSession(): Promise<Session | null> {
  const response = await fetch(api + "/session", {
    headers: { Accept: "application/json" }, credentials: "same-origin", cache: "no-store",
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw Error(offline);
  const fresh: Session = await response.json();
  if (!fresh.authenticated || !fresh.userId || !fresh.csrfToken) return null;
  // A change of account in another tab never retargets an automatic upload.
  if (owner && owner !== fresh.userId) return null;
  owner = fresh.userId;
  return fresh;
}

export function startHostSession() {
  if (!watching) {
    watching = true;
    const flush = () => { for (const key of Array.from(timers.keys())) queueHostSave(key, true); };
    addEventListener("online", flush);
    document.addEventListener("visibilitychange", () => { if (document.hidden) flush(); });
  }
  session ??= fetchSession().catch(() => { session = undefined; return null; });
}

/** Retry previously linked saves if the tab closed before their last upload finished. */
export async function retryLinkedSaves(slots: SaveSlot[]) {
  startHostSession();
  const current = await session;
  if (!current?.userId) return;
  for (const slot of slots) {
    const link = await readHostLink(slot.key);
    if (link && link.user === current.userId && !link.conflict && !slot.damaged && link.at !== slot.at) {
      queueHostSave(slot.key);
    }
  }
}

function later(key: string, delay: number) {
  if (timers.has(key)) return;
  timers.set(key, setTimeout(() => { timers.delete(key); queueHostSave(key, true); }, delay));
}

export function queueHostSave(key: string, force = false) {
  if (!key || !navigator.locks) return;
  const wait = 15_000 - (Date.now() - (lastTry.get(key) ?? 0));
  if (!force && wait > 0) { later(key, wait); return; }
  lastTry.set(key, Date.now());
  clearTimeout(timers.get(key));
  timers.delete(key);
  void navigator.locks.request("168-world:" + key, async () => {
    startHostSession();
    const current = await session;
    if (!current?.userId || !current.csrfToken) {
      if (!session) throw Error(offline);
      session = undefined;
      hostSyncStatus.value = "Progres tersimpan di browser. Masuk kembali dengan akun pemilik dunia untuk menyinkronkan.";
      return;
    }
    let link = await readHostLink(key);
    if (link && link.user !== current.userId) {
      hostSyncStatus.value = "Dunia lokal ini terhubung ke akun lain.";
      return;
    }
    if (link?.conflict) {
      hostSyncStatus.value = "Perangkat lain memperbarui dunia ini. Progres browser tersimpan. Buka versi akun atau simpan progres ini sebagai dunia baru.";
      return;
    }
    const save = await readStored(key);
    if (!save || save.damaged || (link && link.at === save.at)) return;
    if (save.bytes.byteLength > 8 * 1024 * 1024) {
      hostSyncStatus.value = "Dunia tersimpan di browser. Ukurannya melebihi batas 8 MiB untuk akun.";
      return;
    }
    if (!link) {
      const name = slotName(key, save).replace(/[\u0000-\u001f\u007f]/g, " ").trim() || "Dunia";
      link = { user: current.userId, id: crypto.randomUUID(), version: 0, at: 0, name: Array.from(name).slice(0, 100).join("") };
      await writeHostLink(key, link); // retain the UUID even if the response is lost
    }
    const url = api + "/worlds/" + link.id;
    const headers = { "X-Studio-User": link.user, Accept: "application/json" };
    const response = await fetch(url, {
      method: "PUT", cache: "no-store", credentials: "same-origin", signal: AbortSignal.timeout(15_000),
      headers: { ...headers, "X-CSRF-TOKEN": current.csrfToken, "X-World-Name": encodeURIComponent(link.name),
        "X-World-Version": String(link.version), "Content-Type": "application/octet-stream" },
      body: new Blob([save.bytes as BlobPart]),
    });
    if (response.status === 409) {
      const conflict = await response.json();
      if (conflict.error === "account_changed") {
        session = undefined;
        hostSyncStatus.value = "Akun browser telah berganti. Progres tetap tersimpan di perangkat ini.";
        return;
      }
      // An interrupted reply may have committed these exact bytes. Confirm before flagging a conflict.
      const remote = await fetch(url, { headers, credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(8000) });
      if (!remote.ok) throw Error(offline);
      const theirs = new Uint8Array(await remote.arrayBuffer());
      const version = Number(remote.headers.get("X-World-Version"));
      if (version > 0 && theirs.length === save.bytes.length && theirs.every((v, i) => v === save.bytes[i])) {
        await writeHostLink(key, { ...link, at: save.at, version });
      } else {
        await writeHostLink(key, { ...link, conflict: true });
        hostSyncStatus.value = "Versi akun berubah. Kedua progres tersimpan. Buka versi akun atau simpan progres browser sebagai dunia baru.";
        return;
      }
    } else if (response.status === 401 || response.status === 419) {
      session = undefined;
      throw Error(offline);
    } else if (response.status === 413 || response.status === 422) {
      const detail = await response.json().catch(() => ({}));
      hostSyncStatus.value = detail.message || "Layanan akun menolak simpanan ini. Salinan browser tersedia.";
      return;
    } else {
      if (!response.ok) throw Error(offline);
      const { world } = await response.json();
      if (world.id !== link.id || world.version !== link.version + 1) throw Error(offline);
      await writeHostLink(key, { ...link, at: save.at, version: world.version });
    }
    hostSyncStatus.value = "Progres tersimpan di akun dan bisa dilanjutkan di HP.";
  }).catch(() => { hostSyncStatus.value = offline; later(key, 30_000); });
}

// Explicit saves are also a way to resolve a version conflict with the player's choice.
export async function attachManualSave(key: string, source: HostWorldSource) {
  if (!navigator.locks) return;
  await navigator.locks.request("168-world:" + key, async () => {
    const link = await readHostLink(key);
    if (link && (link.user !== source.user || (link.id === source.id && link.version > source.version))) return;
    await writeHostLink(key, { ...source, at: (await readStored(key))?.at ?? 0 });
    hostSyncStatus.value = "Dunia ini tersinkron dengan akun.";
  });
}

/** When the browser has no unsent edits, resume a newer phone revision in a fresh local slot.
 * The previous slot and its backup history stay available. Offline, open that slot as usual. */
export async function newerHostWorld(key: string): Promise<{ file: File; source: HostWorldSource } | null> {
  try {
    const link = await readHostLink(key);
    if (!link || !link.version || link.conflict) return null;
    const saved = await readStored(key);
    if (!saved || link.at !== saved.at) return null;
    return await fetchHostWorld(link.user, link.id, link.version);
  } catch { return null; }
}

async function fetchHostWorld(user: string, id: string, afterVersion = 0) {
  const options: RequestInit = { headers: { "X-Studio-User": user, Accept: "application/json" }, credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(8000) };
  const list = await fetch(api + "/worlds", options);
  if (!list.ok) throw Error("Daftar dunia akun belum dapat dimuat.");
  const { worlds } = await list.json() as { worlds: HostWorldSource[] };
  const world = worlds.find(w => w.id === id);
  if (!world || world.version <= afterVersion) return null;
  const response = await fetch(api + "/worlds/" + id, options);
  const version = Number(response.headers.get("X-World-Version"));
  if (!response.ok || !Number.isSafeInteger(version) || version <= afterVersion) throw Error("Versi dunia akun belum dapat dimuat.");
  return { file: new File([await response.blob()], world.name + ".save"), source: { user, id, name: world.name, version } };
}

export async function resolveHostWorld(id: string) {
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) throw Error("Tautan dunia tidak valid.");
  const account = await fetchSession();
  if (!account?.userId) throw Error("Masuk dengan akun pemilik dunia untuk melanjutkan.");
  const remote = await fetchHostWorld(account.userId, id.toLowerCase());
  if (!remote) throw Error("Dunia ini tidak ditemukan dalam akun yang sedang digunakan.");
  for (const slot of await listSlots()) {
    const link = await readHostLink(slot.key);
    if (link?.id === remote.source.id && link.user === account.userId && link.version === remote.source.version
      && !link.conflict && !slot.damaged && slot.at === link.at) return { slot: slot.key } as const;
  }
  return remote;
}
