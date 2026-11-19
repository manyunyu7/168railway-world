// 168Railway: whether the visitor is signed in to 168railway.com, for the start menu's sign-in gate (guests see
// the menu but sign in to play). The site's own session, same origin. Off the published page (dev, a fork) everyone
// plays; a check that fails or is slow lets the player in, so an outage never locks anyone out.
import { PUBLISHED } from "./dataBase";

export type HostAuth = "in" | "out" | "unknown";

/** Sign in, then back to the game: the web keeps /studio/ as where to return (StudioWorldController@login). */
export const HOST_LOGIN = "/api/web/studio/login";

export async function checkHostAuth(waitMs = 4000): Promise<HostAuth> {
  if (!PUBLISHED || typeof fetch === "undefined") return "in";
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), waitMs);
  try {
    const r = await fetch("/api/web/studio/session", { credentials: "same-origin", headers: { Accept: "application/json" }, cache: "no-store", signal: ctl.signal });
    if (!r.ok) return "unknown";
    const s = (await r.json()) as { authenticated?: boolean };
    return s.authenticated ? "in" : "out";
  } catch {
    return "unknown";
  } finally {
    clearTimeout(timer);
  }
}
