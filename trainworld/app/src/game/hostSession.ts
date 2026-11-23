// 168Railway: whether the visitor is signed in to 168railway.com, for the start menu's sign-in gate (guests see
// the menu but sign in to play). The site's own session, same origin. Off the published page (dev, a fork) everyone
// plays; a check that fails or is slow lets the player in, so an outage never locks anyone out.
import { PUBLISHED } from "./dataBase";

export type HostAuth = "in" | "out" | "unknown";

/** Sign in, then back to the game: the web keeps /studio/ as where to return (StudioWorldController@login). */
const requested = typeof location !== "undefined" ? new URLSearchParams(location.search).get("world") : null;
const worldQuery = requested && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(requested) ? "world=" + requested : "";
export const HOST_LOGIN = "/api/web/studio/login" + (worldQuery ? "?" + worldQuery : "");

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

/** Register on the site, then back to the game (the web keeps /studio/ as where to return). */
export const HOST_REGISTER = "/api/web/studio/login?to=register" + (worldQuery ? "&" + worldQuery : "");

/** Sign in from inside the game, with the site's own JSON sign-in (POST /api/login) and the session's CSRF token. */
export async function signInHere(login: string, password: string): Promise<{ ok: true } | { ok: false; message: string }> {
  try {
    const s = await fetch("/api/web/studio/session", { credentials: "same-origin", headers: { Accept: "application/json" }, cache: "no-store" });
    const token = ((await s.json()) as { csrfToken?: string | null }).csrfToken;
    if (!token) return { ok: false, message: "" };
    const r = await fetch("/api/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json", "X-CSRF-TOKEN": token, "X-Requested-With": "XMLHttpRequest" },
      body: JSON.stringify({ email_or_username: login, password }),
    });
    const body = (await r.json().catch(() => ({}))) as { success?: boolean; message?: string };
    return r.ok && body.success ? { ok: true } : { ok: false, message: body.message ?? "" };
  } catch {
    return { ok: false, message: "" };
  }
}

/** Opened from the 168Railway Android app (its WebView adds "168RailwayApp" to the user agent): the app already
 * signs the player in, and Google's sign-in would leave for the phone's browser and never come back signed in. */
export const IN_RAILWAY_APP = typeof navigator !== "undefined" && /\b168RailwayApp\b/.test(navigator.userAgent);

type AppBridge = { callHandler: (name: string, ...args: unknown[]) => Promise<unknown> };
const appBridge = (): AppBridge | null =>
  (IN_RAILWAY_APP && (globalThis as { flutter_inappwebview?: AppBridge }).flutter_inappwebview) || null;

/** In the app, signing in is the app's own sign-in (or sign-up): the app and the game end up signed in together, and
 * the app reopens the game signed in. False outside the app or when the app cannot be reached. */
export const CAN_SIGN_IN_THROUGH_APP = (): boolean => typeof appBridge()?.callHandler === "function";
export function signInThroughApp(): void { void appBridge()?.callHandler("railwayLogin").catch(() => {}); }

// In the app the game fills the screen under the camera cutout; `in-app` pads what sits at the top edge.
if (IN_RAILWAY_APP && typeof document !== "undefined") {
  const vp = document.querySelector<HTMLMetaElement>('meta[name="viewport"]');
  if (vp && !vp.content.includes("viewport-fit")) vp.content += ", viewport-fit=cover";
  document.documentElement.classList.add("in-app");
}

const POPUP_DONE = "login=done";

/** Google's sign-in in a small window (its page cannot sit inside ours); resolves once that window is done or
 * closed, and the caller checks the session again. A blocked window goes the same way in this tab. */
export function signInWithGoogle(): Promise<void> {
  const url = `/auth/google?next=${encodeURIComponent(`/studio/?${POPUP_DONE}`)}`;
  const w = window.open(url, "168railway-login", "popup,width=480,height=640");
  if (!w) {
    location.href = `/auth/google?next=${encodeURIComponent("/studio/" + (worldQuery ? "?" + worldQuery : ""))}`;
    return new Promise(() => {});
  }
  return new Promise((done) => {
    const finish = () => { removeEventListener("message", onMessage); clearInterval(timer); done(); };
    const onMessage = (e: MessageEvent) => { if (e.origin === location.origin && e.data === "168railway-login") finish(); };
    addEventListener("message", onMessage);
    const timer = setInterval(() => { if (w.closed) finish(); }, 500);
  });
}

/** In Google's sign-in window, back on the game: tell the game that opened it and close; else drop the mark. */
export function finishSignInWindow(): boolean {
  const q = new URLSearchParams(location.search);
  if (q.get("login") !== "done") return false;
  if (window.opener && window.opener !== window) {
    try { (window.opener as Window).postMessage("168railway-login", location.origin); } catch { /* none */ }
    window.close();
    return true;
  }
  const u = new URL(location.href);
  u.searchParams.delete("login");
  history.replaceState(history.state, "", u.href);
  return false;
}
