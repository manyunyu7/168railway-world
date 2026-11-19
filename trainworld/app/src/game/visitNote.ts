// 168Railway: a note of who opens 168Railway World (the web's /admin/world-visits): the start menu opened, a
// new game started (mode, city, Jabodetabek scenario), a saved game carried on. One small GET each, not awaited,
// on the published page only; the account rides on the site's own cookie, a guest is a random id kept in this
// browser. Nothing else is sent.
import { PUBLISHED } from "./dataBase";

const KEY = "tw-visit-id";

function anonId(): string {
  try {
    let id = localStorage.getItem(KEY);
    if (!id) {
      id = Math.random().toString(36).slice(2, 12);
      localStorage.setItem(KEY, id);
    }
    return id;
  } catch {
    return "";
  }
}

export function noteVisit(ev: "open" | "start" | "continue", fields: { mode?: string; city?: string | null; scen?: string | null } = {}): void {
  if (!PUBLISHED || typeof fetch === "undefined") return;
  const q = new URLSearchParams({ ev });
  const anon = anonId();
  if (anon) q.set("anon", anon);
  if (ev === "open" && document.referrer) {
    try { const r = new URL(document.referrer); q.set("ref", (r.host + r.pathname).slice(0, 120)); } catch { /* none */ }
  }
  for (const [k, v] of Object.entries(fields)) if (v) q.set(k, v);
  fetch(`/api/web/studio/visit?${q}`, { credentials: "same-origin", keepalive: true }).catch(() => {});
}
