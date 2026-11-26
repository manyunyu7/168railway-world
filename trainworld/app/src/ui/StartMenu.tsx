import { newerHostWorld, resolveHostWorld, retryLinkedSaves } from "../game/hostWorldSync";
import type { HostWorldSource } from "../workers/saveFile";
import {HostWorlds} from "./HostWorlds";
// T-677: the start menu. Every page load opens on it, unless a URL switch already says which game
// (`choiceFromUrl`): New game (the new game sheet, T-728), then the saved games in this browser, the
// most recent first, each a card as in the saves panel. Nothing heavy has started under it: main.ts
// starts the clock and demand workers with the pick. It covers the page and takes every key until then.
// With no saved game it opens on the new game sheet (T-1441).

import { h, render, type ComponentChildren } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { startMetroBackdrop } from "./metroBackdrop";
import { noteVisit } from "../game/visitNote";
import { hostSignedIn } from "../game/account";
import { CAN_SIGN_IN_THROUGH_APP, checkHostAuth, finishSignInWindow, HOST_REGISTER, IN_RAILWAY_APP, signInHere, signInThroughApp, signInWithGoogle } from "../game/hostSession";
import { cityById, HOME_CITY, isCity } from "../game/cities";
import { choosing } from "../game/loading";
import { display, dropMarket, keepMarket } from "../game/ui";
import { failLists, listSlots, newGameName, StorageReadError, type SaveSlot } from "../workers/saveFile";
import { backupChoice, CHOICE_PARAMS, fileChoice, type GameChoice } from "../workers/clockClient";
import { groupHeads, groupVersions, type GameGroup, type Version } from "../workers/versions";
import { saveRefusal } from "../game/memoryCheck";
import { overBudget } from "../game/memoryEstimate";
import { MemoryWarn } from "./MemoryWarn";
import { blockKeysOutside } from "./keys";
import { JABO_SCENARIOS, NewGameSheet, START_CITIES, type JaboScenario, type NewGameView } from "./NewGame";
import { newGameEnd, setStartAt } from "./locate";


import { AccountButton, startAccountUi } from "./AccountPanel";
import { LanguageSelect } from "./LanguageSelect";
import { DeleteAsk, pickSaveFile, savedWhen, SlotFacts, SlotThumb, SlotTitle, useBackups, VersionsLine, type VersionActions } from "./SavesPanel";
import { Icon } from "./widgets";
import { Money } from "./MoneyText";
import { Changelog } from "./Changelog";
import { RailwayBrand } from "./RailwayBrand";
import { Card, CommunityButton } from "./Community";
import { communityOn, communityOpen, fetchShares, linkedShare, type ShareRow } from "../game/community";
import { accountState, boards, boardsOpen, fetchBoards, playerShown, type Board, type BoardKind } from "../game/account";
import { Avatar, WORLD, netStats, netStatsShort, playingNow, rowSub } from "./Leaderboard";
import { Named } from "./Flag";
import { pointsShort } from "../game/points";
import { dismissUpdate, updateToShow } from "./updates";
import { accountBackupFile, accountList, accountOnly, cardOf, damagedAccount, deleteOwed, deleteVersions, downloadAccountGame, downloadFailure, pinnedFile, settleOnStart, slotsChanged, stopStartSettle } from "../game/accountGames";
import { t, type MessageKey } from "../i18n";
import { tPlace } from "../i18n/places";

declare const __TW_VERSION__: string | undefined;
/** The build's commit count (vite.config.ts), "dev" on a dev server. */
const VERSION = typeof __TW_VERSION__ === "string" ? __TW_VERSION__ : "dev";

/** The game the URL chooses, or null for the menu: `?new=1` a new game that owns no slot (T-320),
 * normal in New York unless `&mode=sandbox` or `&city=<zone id>` say otherwise (T-728), `&side=left|right` its
 * running side (T-3297);
 * `?synth=1`, `?demandTest=1`, `?paused=1` and `?continue=1` the game opened last; `?slot=<key>` and
 * `?load=file|restore|new` the game the saves panel picked (T-1196); `?next=new` the menu, which starts the
 * saves panel's new game (T-3478). */
export function choiceFromUrl(): GameChoice | null {
  const q = new URLSearchParams(location.search);
  const on = (k: string) => q.get(k) === "1";
  if (on("new")) {
    const city = q.get("city");
    const side = q.get("side");
    return { fresh: true, newGame: true, game: { mode: q.get("mode") === "sandbox" ? "sandbox" : "normal", start: isCity(city) ? city! : HOME_CITY, ...(side === "left" || side === "right" ? { rightHand: side === "right" } : {}) } };
  }
  // T-1196: a page reloaded into the game picked in the saves panel; the switch leaves the address
  // bar, so a reload from there goes to the menu (or, `?paused=1`, the game opened last)
  const slot = q.get("slot"), load = q.get("load"), next = q.get("next");
  if (slot || load || next) {
    const u = new URL(location.href);
    for (const k of CHOICE_PARAMS) u.searchParams.delete(k);
    history.replaceState(history.state, "", u.href);
  }
  if (slot) return { slot };
  if (load === "new") {
    const city = q.get("city");
    return { pending: true, newGame: !on("real"), game: { mode: q.get("mode") === "sandbox" ? "sandbox" : "normal", ...(isCity(city) ? { start: city! } : {}) } };
  }
  if (load) return { pending: true };
  if (next === "new") {
    const city = q.get("city");
    const at = (q.get("at") ?? "").split(",").map(Number);
    nextNew = { choice: { pending: true, newGame: !on("real"), game: { mode: q.get("mode") === "sandbox" ? "sandbox" : "normal", ...(isCity(city) ? { start: city! } : {}) } }, at: at.length === 3 && at.every(Number.isFinite) ? { center: [at[0], at[1]], zoom: at[2] } : null };
    return null;
  }
  if (on("synth") || on("demandTest") || on("paused") || on("continue")) return {};
  return null;
}

/** T-927: the view behind the menu, centred around 10° E, 20° N: one zoom closer (T-1593, Anita:
 * "twice as zoomed in") than the zoom at which the band of the world from 60° S to 75° N covers the
 * page. Mercator y of 75° N is 2.028, of 60° S -1.317, so the band is 3.345 / 2π of the world's
 * 512 px tile height at zoom 0. */
export function worldCover(el: HTMLElement): { center: [number, number]; zoom: number } {
  const w = Math.max(1, el.clientWidth), h = Math.max(1, el.clientHeight);
  return { center: [10, 20], zoom: 1 + Math.max(0, Math.log2(w / 512), Math.log2(h / (512 * 3.345 / (2 * Math.PI)))) };
}

/** T-1567: the logo over the menu, in place of the welcome heading; `?logo=0` leaves it out (the
 * menu as before, heading and all). */
const LOGO = false;
/** the logo is shown: main.ts hides the basemap's names behind it */
export const START_LOGO = LOGO;

/** T-988: the start menu waits this long at most for the account before opening a game anyway. */
const SETTLE_WAIT_MS = 10_000;

function lastPlayedSlot(): string | null {
  try { return localStorage.getItem("anitabuilder-save-slot"); } catch { return null; }
}

/** T-2810: the newest update, with its changelog and a way to hide it (ui/updates.ts). */
function UpdateRow({ onChangelog }: { onChangelog: () => void }) {
  const [update, setUpdate] = useState(updateToShow);
  if (!update) return null;
  const hide = () => { dismissUpdate(update.version); setUpdate(null); };
  return <div class="start-update">
    <span class="start-update-line">{t(update.line)}</span>
    <button class="btn" onClick={onChangelog}>{t("startMenu.changelog")}</button>
    <button class="btn icon mini" aria-label={t("startMenu.hideUpdate")} data-tip={t("startMenu.hideUpdate")} onClick={hide}><Icon.cross /></button>
  </div>;
}

/** 168Railway: a guest's card, the game shown and a sign-in right here (the site's account and session): email or
 * username and password, Google in a small window, or register on the site and come straight back. Signed in, the
 * menu opens in place. */
function GuestGate({ first, onIn }: { first: { current: HTMLButtonElement | null }; onIn: () => void }) {
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState<"" | "form" | "google">("");
  const [error, setError] = useState("");
  // 168Railway app: one button, the app's own sign-in (it has sign-up too) and the game reopens signed in
  const viaApp = CAN_SIGN_IN_THROUGH_APP();
  const signedIn = async () => { if ((await checkHostAuth()) !== "out") { void hostSignedIn(); onIn(); } };
  const submit = async (e: Event) => {
    e.preventDefault();
    if (busy) return;
    if (!login.trim() || !password) { setError(t("startMenu.guestMissing")); return; }
    setBusy("form"); setError("");
    const r = await signInHere(login.trim(), password);
    if (r.ok) { await signedIn(); return; }
    setBusy(""); setError(r.message || t("startMenu.guestFailed"));
  };
  const google = async () => {
    if (busy) return;
    setBusy("google"); setError("");
    await signInWithGoogle();
    await signedIn();
    setBusy("");
  };
  // 168Railway launcher (2026-11 redesign, option A): the sign-in on the left, Jabodetabek's map as the stage
  return <div class="launch launch-guest">
    <section class="launch-side guest-gate">
    <LaunchBrand />
    <div class="launch-intro">
      <h2 id="start-title" class="launch-headline">{t("launch.headline")}</h2>
      <p class="launch-sub">{t("launch.sub")}</p>
    </div>
    {viaApp
      ? <button ref={first} type="button" class="btn start-new guest-gate-login" onClick={signInThroughApp}>
          <Icon.train />{t("startMenu.guestLogin")}
        </button>
      : <>
      <form class="guest-gate-form" onSubmit={(e) => void submit(e)}>
        <label class="launch-field"><span>{t("startMenu.guestLoginField")}</span>
        <input type="text" name="username" autocomplete="username" autocapitalize="none" spellcheck={false}
          value={login} onInput={(e) => setLogin((e.target as HTMLInputElement).value)} disabled={!!busy} /></label>
        <label class="launch-field"><span>{t("startMenu.guestPassword")}</span>
        <input type="password" name="password" autocomplete="current-password"
          value={password} onInput={(e) => setPassword((e.target as HTMLInputElement).value)} disabled={!!busy} /></label>
        {error && <p class="guest-gate-error" role="alert">{error}</p>}
        <button ref={first} type="submit" class="btn start-new guest-gate-login" disabled={!!busy}>
          <Icon.train />{busy === "form" ? t("startMenu.guestSigningIn") : t("startMenu.guestLogin")}
        </button>
      </form>
      {!IN_RAILWAY_APP && <>
        <div class="guest-gate-or muted small">{t("newGame.or")}</div>
        <button type="button" class="btn guest-gate-google" disabled={!!busy} onClick={() => void google()}>
          <span class="guest-gate-g" aria-hidden="true">G</span>{busy === "google" ? t("startMenu.guestGoogleWaiting") : t("startMenu.guestGoogle")}
        </button>
      </>}
      <p class="muted small guest-gate-note">{t("startMenu.guestNoAccount")} <a href={HOST_REGISTER}>{t("startMenu.guestRegister")}</a></p>
      </>}
    <div class="launch-foot">
      <p class="launch-sync muted small"><Icon.save />{t("startMenu.guestPoint3")}</p>
      <div class="launch-foot-row">
        <a href="./about.html" target="_blank" rel="noopener">{t("railway.credits")}</a>
        <span class="muted small">{t("startMenu.build", { version: VERSION })}</span>
        <div class="start-top"><LanguageSelect /></div>
      </div>
    </div>
    </section>
    <div class="launch-stage">
      <StageSnap id="jak" alt={t("startMenu.guestSnapAlt")} eager />
      <ul class="launch-chips">
        <li class="krl">KRL · GAPEKA 2026</li><li class="mrt">MRT</li><li class="lrt">LRT</li><li class="tj">TransJakarta</li>
      </ul>
      <div class="launch-cards">
        <div class="launch-card dark"><span class="launch-kicker">{t("launch.readyKicker")}</span><strong>{t("launch.readyTitle")}</strong><span>{t("launch.readyText")}</span></div>
        <div class="launch-card light"><span class="launch-kicker">{t("launch.blankKicker")}</span><strong>{t("launch.blankTitle")}</strong><span>{t("launch.blankText")}</span></div>
      </div>
    </div>
  </div>;
}

/** 168Railway launcher card (option D): the commuter lines and the loco across the card's top, the name on them */
function LaunchStrip() {
  return <div class="launch-strip" aria-hidden="true">
    <RailwayBrand title={false} />
    <span class="launch-strip-name"><span class="railway-mark"><Icon.train /></span>168Railway <strong>World</strong></span>
  </div>;
}

/** 168Railway launcher: the mark and the name, with room on the right (the account button) */
function LaunchBrand({ children }: { children?: ComponentChildren }) {
  return <div class="launch-brand">
    <span class="railway-mark" aria-hidden="true"><Icon.train /></span>
    <span class="launch-brand-name">168Railway <strong>World</strong></span>
    {children}
  </div>;
}

/** the stage's pictures in two sizes (`snapshots/hd/<id>-1200.webp`, `-2100.webp`); the 600 px card picture
 * (`snapshots/<id>.jpg`, cached already for the cards) is the phone's and the fallback */
const HD_SNAPS = new Set(["jak", "jak_mulai", "jak_2019", "jak_krl", "jak_tj", "jak_bogor", "base-jak"]);
const STAGE_SIZES = "(max-width: 860px) 100vw, calc(100vw - 440px)";
function StageSnap({ id, alt = "", eager = false, class: cls = "launch-stage-img" }: { id: string; alt?: string; eager?: boolean; class?: string }) {
  const card = id.startsWith("base-") ? null : `./snapshots/${id}.jpg`;
  const hd = HD_SNAPS.has(id);
  const srcSet = hd ? [card && `${card} 600w`, `./snapshots/hd/${id}-1200.webp 1200w`, `./snapshots/hd/${id}-2100.webp 2100w`].filter(Boolean).join(", ") : undefined;
  return <img class={cls} src={card ?? `./snapshots/hd/${id}-1200.webp`} srcset={srcSet} sizes={hd ? STAGE_SIZES : undefined}
    width={2100} height={1500} alt={alt} decoding="async" {...{ fetchpriority: eager ? "high" : "auto" }} />;
}

/** 168Railway launcher card: the strongest line colour in a saved game's picture (the network's own colours), for
 * the card's accent; null for no picture or a picture with no coloured lines */
function useAccent(thumb?: string): string | null {
  const [accent, setAccent] = useState<string | null>(null);
  useEffect(() => {
    setAccent(null);
    if (!thumb) return;
    let gone = false;
    const img = new Image();
    img.onload = () => {
      if (gone) return;
      const cv = document.createElement("canvas"); cv.width = 96; cv.height = 64;
      const cx = cv.getContext("2d", { willReadFrequently: true });
      if (!cx) return;
      cx.drawImage(img, 0, 0, 96, 64);
      const d = cx.getImageData(0, 0, 96, 64).data;
      const bins = new Map<number, { n: number; r: number; g: number; b: number }>();
      for (let i = 0; i < d.length; i += 4) {
        const r = d[i], g = d[i + 1], b = d[i + 2];
        if (d[i + 3] < 200 || Math.max(r, g, b) - Math.min(r, g, b) < 90) continue;
        const k = (r >> 5) << 6 | (g >> 5) << 3 | (b >> 5);
        const e = bins.get(k) ?? { n: 0, r: 0, g: 0, b: 0 };
        e.n++; e.r += r; e.g += g; e.b += b; bins.set(k, e);
      }
      let best: { n: number; r: number; g: number; b: number } | null = null;
      for (const e of bins.values()) if (!best || e.n > best.n) best = e;
      if (best && best.n >= 6) setAccent(`rgb(${Math.round(best.r / best.n)} ${Math.round(best.g / best.n)} ${Math.round(best.b / best.n)})`);
    };
    img.src = thumb;
    return () => { gone = true; };
  }, [thumb]);
  return accent;
}

/** One saved game's card on the launcher's row (2026-11-26 option 14 "Biru MRT"): a stripe in its network's own
 * colour across the top, then a badge with its initial, its picture, its figures and the open button */
function GameCard({ origin, accentOf, children, last }: { origin: string; accentOf?: string; children: ComponentChildren; last: boolean }) {
  const accent = useAccent(accentOf);
  return <li data-origin={origin} class={"row-card sc" + (last ? " is-last" : "")} style={accent ? { "--acc": accent } : undefined}>
    <div class="sc-stripe" aria-hidden="true" />
    {children}
  </li>;
}

/** Mulai baru (2026-11-26 option D): the two lead starts as big pictures (one line for a first game, the whole 2026
 * network), the empty map beside them, then the other scenarios and the start cities as chips, each opening the new
 * game sheet on its pick */
const LEAD_STARTS: { id: JaboScenario; level: MessageKey }[] = [{ id: "jak_bogor", level: "launch.levelEasy" }, { id: "jak", level: "launch.levelFull" }];
function StartPicks({ onPick }: { onPick: (tab: NewGameView["tab"], jabo?: JaboScenario, city?: string) => void }) {
  const lead = new Set(LEAD_STARTS.map((l) => l.id));
  return <div class="sp">
    <div class="sp-top">
      {LEAD_STARTS.map(({ id, level }) => <button type="button" class="sp-hero" data-scenario={id} onClick={() => onPick("real", id)}>
        <StageSnap id={id} class="sp-hero-img" />
        <span class="sp-hero-text">
          <span class="sp-level">{t(level)}</span>
          <b>{t(`newGame.jabo.${id}` as MessageKey)}</b>
          <span class="sp-tip">{t(`newGame.jaboTip.${id}` as MessageKey)}</span>
        </span>
      </button>)}
      <button type="button" class="sp-blank" onClick={() => onPick("blank")}>
        <span class="sp-plus" aria-hidden="true"><Icon.plus /></span>
        <span><b>{t("launch.welcomeBlank")}</b><span class="sp-blank-sub">{t("launch.blankAnyCity")}</span></span>
      </button>
    </div>
    <div class="sp-chips">
      <span class="sp-label">{t("launch.otherScenarios")}</span>
      {JABO_SCENARIOS.filter((id) => !lead.has(id)).map((id) => <button type="button" class="sp-chip" data-scenario={id} onClick={() => onPick("real", id)}>
        <img src={`./snapshots/${id}.jpg`} alt="" width={36} height={36} loading="lazy" /><span>{t(`newGame.jabo.${id}` as MessageKey)}</span>
      </button>)}
    </div>
    <div class="sp-chips">
      <span class="sp-label">{t("launch.fromScratchIn")}</span>
      {START_CITIES.filter((id) => id !== HOME_CITY && isCity(id)).map((id) => <button type="button" class="sp-chip sp-city" data-city={id} onClick={() => onPick("blank", undefined, id)}>
        <Icon.pin /><span>{tPlace(id, cityById(id).name)}</span>
      </button>)}
      <button type="button" class="sp-chip sp-city sp-more" onClick={() => onPick("blank")}><Icon.globe /><span>{t("launch.otherCity")}</span></button>
    </div>
  </div>;
}

/** dev server only: `?mock=launch` fills Komunitas and Papan peringkat with made-up rows, to see their layout */
const MOCK = import.meta.env.DEV && new URLSearchParams(location.search).get("mock") === "launch";
const MOCK_ROWS: ShareRow[] = MOCK ? ["Jabodetabek Loop Ekspres", "KRL Lingkar Dalam", "Busway 2030", "Red Line ke Puncak"].map((title, i) =>
  ({ id: "mock000" + i, rev: 1, name: ["masinis_bdg", "rara.rail", "andi_tj", "sinta"][i], title, city: "jak", points: [1240, 890, 760, 420][i] * 1000, lines: [31, 12, 22, 6][i], stations: [412, 186, 264, 98][i], day: 10, likes: [24, 12, 9, 3][i], at: 0 })) : [];

/** Komunitas: the biggest shared networks, two by two, each opening in Community; nothing while Community is off */
function CommunityPeek() {
  const [rows, setRows] = useState<ShareRow[] | null>(null);
  const on = communityOn() || MOCK;
  useEffect(() => {
    if (MOCK) { setRows(MOCK_ROWS); return; }
    if (!on) return;
    let live = true;
    fetchShares("top", "", "").then((l) => { if (live) setRows(l.rows.slice(0, 4)); }, () => { if (live) setRows([]); });
    return () => { live = false; };
  }, [on]);
  if (!on || (rows && !rows.length)) return null;
  return <section class="lsec">
    <div class="lsec-head"><h2>{t("community.title")}</h2><span class="muted">{t("community.biggest")}</span>
      <button type="button" class="lsec-more" onClick={() => (communityOpen.value = true)}>{t("launch.seeAll")} →</button></div>
    <ul class="cp-grid">{rows ? rows.map((r) => <Card row={r} onOpen={() => { linkedShare.value = r.id; communityOpen.value = true; }} />)
      : [0, 1, 2, 3].map(() => <li class="cp-wait" />)}</ul>
  </section>;
}

/** Papan peringkat: the world board's top three on a podium, the next two, and the player's own place */
function BoardPeek() {
  const [kind, setKind] = useState<BoardKind>("tpl");
  // 168Railway (26 Nov): the world board or one city's, the cities with the most players as chips
  const [key, setKey] = useState(WORLD);
  const on = accountState.value === "on" || MOCK;
  useEffect(() => { if (on) void fetchBoards(key, kind); }, [on, kind, key]);
  if (!on) return null;
  const b = boards.value, d: Board | null = MOCK ? { board: WORLD, count: 40, rows: [["masinis_bdg", 1240], ["rara.rail", 890], ["andi_tj", 760], ["sinta", 420], ["kereta_kita", 395], ["dipo_bukit", 380], ["loko_cc206", 344], ["krl_mania", 300], ["rel_ganda", 287], ["nusantara_rail", 260]].map(([name, v], i) => ({ rank: i + 1, name: name as string, value: (v as number) * 1000, id: "a0000000000" + i, ...(i % 3 === 1 ? {} : { lines: 30 - i * 2, stations: 320 - i * 25, riders: (12 - i) * 180_000 }), cities: [["jak", "bad"], ["sur"], ["jak", "lek", "bad"], ["sem"], ["yog", "sol"]][i % 5], day: 40 + i * 37, at: Date.now() / 1000 - i * i * 2400 })), me: { rank: 27, name: "kamu", value: 120000 } }
    : b.key === key && b.kind === kind && b.data?.board === key ? b.data : null;
  const cityKeys = Object.entries(b.list).filter(([k]) => k.startsWith(WORLD + "_")).sort((x, y) => y[1] - x[1]).slice(0, 6).map(([k]) => k);
  if (key !== WORLD && !cityKeys.includes(key)) cityKeys.unshift(key);
  const rows = d?.rows ?? [];
  const top = [rows[1], rows[0], rows[2]];
  const me = d?.me;
  const meAbove = me ? rows.find((r) => r.rank === me.rank - 1) : undefined;
  return <section class="lsec">
    <div class="lsec-head"><h2>{t("leaderboard.title")}</h2>
      <div class="lb-kinds" role="tablist">
        <button role="tab" aria-selected={kind === "tpl"} class={kind === "tpl" ? "on" : ""} onClick={() => setKind("tpl")}>{t("newGame.tabReal")}</button>
        <button role="tab" aria-selected={kind === "nol"} class={kind === "nol" ? "on" : ""} onClick={() => setKind("nol")}>{t("launch.fromScratch")}</button>
      </div>
      <button type="button" class="lsec-more" onClick={() => { playerShown.value = null; boardsOpen.value = true; }}>{t("launch.full")} →</button></div>
    {cityKeys.length > 0 && <div class="bp-cities" role="tablist" aria-label={t("launch.boardCity")}>
      {[WORLD, ...cityKeys].map((k) => <button type="button" role="tab" aria-selected={key === k} class={key === k ? "on" : ""} onClick={() => setKey(k)}>
        {k === WORLD ? t("leaderboard.world") : tPlace(k.slice(WORLD.length + 1), cityById(k.slice(WORLD.length + 1))?.name ?? k.slice(WORLD.length + 1))}</button>)}
    </div>}
    <div class="bp">
      {!d ? <p class="muted small">{b.error || t("leaderboard.loading")}</p> : !rows.length ? <p class="muted small">{t("leaderboard.nobody")}</p> : <>
        <div class="bp-pod">{top.map((r, i) => r ? <div class={"bp-p p" + r.rank} key={i}>
          <Avatar name={r.name} photo={r.photo} size={r.rank === 1 ? 54 : 42} cls="bp-av" />
          <button type="button" class="bp-nm" disabled={!r.id} onClick={() => { if (r.id) { playerShown.value = r.id; boardsOpen.value = true; } }}><Named name={r.name} flag={r.flag} small /></button>
          <span class="bp-pts num">{pointsShort(r.value)}</span>
          {rowSub(r, 1, false) && <small class="bp-sub">{playingNow(r) && <i class="live-dot" />}{rowSub(r, 1, false)}</small>}
          {netStats(r) && <small class="bp-st num">{netStatsShort(r).filter(Boolean).map((l) => <span>{l}</span>)}</small>}
          <span class="bp-blk">{r.rank}</span>
        </div> : <div key={i} />)}</div>
        {rows.slice(3, 10).map((r) => <button type="button" class="bp-row" disabled={!r.id} onClick={() => { if (r.id) { playerShown.value = r.id; boardsOpen.value = true; } }}><span class="num">{r.rank}</span><Avatar name={r.name} photo={r.photo} size={26} /><span class="bp-nmw"><Named name={r.name} flag={r.flag} small />{rowSub(r, 1) && <small class="bp-sub">{playingNow(r) && <i class="live-dot" />}{rowSub(r, 1)}</small>}{netStats(r) && <small class="bp-net num">{netStats(r)}</small>}</span>{r.thumb ? <img class="bp-th" src={r.thumb} alt="" loading="lazy" width={60} height={40} /> : <span />}<span class="bp-val"><b class="num">{pointsShort(r.value)}</b><i class="bp-bar"><i style={{ width: `${Math.max(4, Math.round(100 * r.value / (rows[0]?.value || 1)))}%` }} /></i></span></button>)}
      </>}
      {me ? <div class="bp-me"><div><span class="bp-rk num">{me.rank}</span><span>{t("launch.you")}</span><b class="num">{pointsShort(me.value)}</b></div>
        {meAbove && <small class="muted">{t("launch.toNext", { points: pointsShort(meAbove.value - me.value), rank: meAbove.rank })}</small>}</div>
        : d && <p class="muted small bp-join">{t("launch.joinBoard")}</p>}
    </div>
  </section>;
}

function Menu({ slots: first0, unread: unread0, pick: pickGame, settling, guest: guest0 = false }: { slots: SaveSlot[]; unread: boolean; pick: (c: GameChoice) => void; settling: Promise<void>; guest?: boolean }) {
  // 168Railway: a guest signs in on the card, and the menu opens in place
  const [guest, setGuest] = useState(guest0);
  useEffect(() => { if (!guest && !new URLSearchParams(location.search).has("world")) void retryLinkedSaves(first0).catch(() => {}); }, [guest]);
  // T-3073: every game opened here has inter-city trips unless the warning's lite button asked otherwise
  const pick = (c: GameChoice, lite = false) => { if (lite) dropMarket(); else keepMarket(); pickGame(c); };
  const first = useRef<HTMLButtonElement>(null);
  // T-2810: out in the menu's own element, away from the card's zoom
  const [changelog, setChangelog] = useState(false);
  // T-1441: a first visit, with no saved game to pick, opens on the new game sheet
  const [sheet, setSheet] = useState(false);
  useEffect(() => { if (!sheet) first.current?.focus(); }, [sheet]);
  // 168Railway launcher: the new game sheet's tab and pick
  const [sheetTab, setSheetTab] = useState<NewGameView["tab"]>("real");
  const [view, setView] = useState<NewGameView>({ tab: "real", jabo: "jak_mulai" });
  // 168Railway: behind the sign-in and the signed-in home, a metro map drawing itself (as PPKA Simulator's opening screen)
  const metro = useRef<HTMLCanvasElement>(null);
  useEffect(() => (metro.current ? startMetroBackdrop(metro.current) : undefined), [guest, sheet]);
  const [sheetJabo, setSheetJabo] = useState<JaboScenario>("jak_mulai");
  const [sheetCity, setSheetCity] = useState<string | undefined>(undefined);
  const openSheet = (tab: NewGameView["tab"], jabo?: JaboScenario, city?: string) => {
    setSheetTab(tab); if (jabo) setSheetJabo(jabo); setSheetCity(city);
    setView((v) => ({ tab, jabo: jabo ?? v.jabo })); setSheet(true);
  };
  // T-988: the slots again once the account has settled them (downloads, deletions, forks)
  const [slots, setSlotsOnly] = useState(first0);
  // T-2705: the saved games could not be read (storage kept failing), as against there being none
  const [unread, setUnread] = useState(unread0);
  const setSlots = (s: SaveSlot[]) => { setSlotsOnly(s); setUnread(false); };
  const changed = slotsChanged.value;
  useEffect(() => { if (changed) void listSlots().then(setSlots, () => {}); }, [changed]);
  // T-1197: a saved game too big for this device is refused with the reason
  const [refused, setRefused] = useState<string | null>(null);
  // T-3061: the device check is a guess (browsers round a device's memory down), so it warns and offers to load anyway
  // T-3073: `go(true)` loads it without inter-city trips; any other pick here has them again
  const [warned, setWarned] = useState<{ text: string; go: (lite?: boolean) => void } | null>(null);
  // T-3329: a game bigger than the device can give asks before it opens
  const [memWarn, setMemWarn] = useState<{ key: string; stations: number } | null>(null);
  const openHostFile = async (file: File, host: HostWorldSource) => {
    await Promise.race([settling, new Promise(r => setTimeout(r, SETTLE_WAIT_MS))]);
    const result = await fileChoice(file, { host }, true);
    if (typeof result === "string") { setRefused(result); return; }
    if ("warn" in result) {
      setWarned({ text: result.warn, go: async lite => {
        const choice = await fileChoice(file, { host });
        if (typeof choice === "string") setRefused(choice); else if (!("warn" in choice)) pick(choice, lite);
      } });
    } else pick(result);
  };
  useEffect(() => {
    const url = new URL(location.href);
    const world = url.searchParams.get("world");
    if (guest || world === null) return;
    let cancelled = false;
    void resolveHostWorld(world).then(async result => {
      if (cancelled) return;
      url.searchParams.delete("world");
      history.replaceState(history.state, "", url.href);
      if ("slot" in result) {
        const why = await saveRefusal({ key: result.slot }, true);
        if (cancelled) return;
        if (why?.soft) setWarned({ text: why.text, go: lite => pick({ slot: result.slot }, lite) });
        else if (why) setRefused(why.text);
        else pick({ slot: result.slot });
      } else await openHostFile(result.file, result.source);
    }).catch(() => { if (!cancelled) setRefused("Dunia pada tautan belum bisa dibuka. Periksa koneksi dan akun yang aktif."); });
    return () => { cancelled = true; };
  }, [guest]);
  const open = async (key: string) => {
    await Promise.race([settling, new Promise((r) => setTimeout(r, SETTLE_WAIT_MS))]);
    // T-988: an account game is downloaded into a new slot first
    const g = key.startsWith("account:") ? accountList.peek()?.games.find((x) => "account:" + x.id === key) : null;
    if (g) {
      const k = await downloadAccountGame(g);
      if (!k) { setRefused(downloadFailure(g.id, t("startMenu.downloadFailed"))); return; }
      key = k;
    }
    const remote = await newerHostWorld(key);
    if (remote) return openHostFile(remote.file, remote.source);
    const why = await saveRefusal({ key }, true);
    setRefused(why && !why.soft ? why.text : null);
    setWarned(why?.soft ? { text: why.text, go: (lite) => pick({ slot: key }, lite) } : null);
    if (!why) pick({ slot: key });
  };
  // T-1612: the card whose delete is being asked about
  const [removing, setRemoving] = useState<string | null>(null);
  // T-2738: a card's every version, or one, here and on the account (`deleteVersions`)
  const remove = async (versions: Version[]) => {
    setRemoving(null);
    await Promise.race([settling, new Promise((r) => setTimeout(r, SETTLE_WAIT_MS))]);
    setRefused(await deleteVersions(versions) || null);
    await listSlots().then(setSlots, (err) => { if (err instanceof StorageReadError) setUnread(true); else setSlots([]); });
  };
  // T-1612: a save file picked here opens as it does from the saves panel (its game waits in storage)
  const load = () => pickSaveFile(async (file) => {
    await Promise.race([settling, new Promise((r) => setTimeout(r, SETTLE_WAIT_MS))]);
    const c = await fileChoice(file, undefined, true);
    setWarned(null);
    if (typeof c === "string") setRefused(c);
    else if ("warn" in c) setWarned({ text: c.warn, go: async (lite) => { const d = await fileChoice(file); if (typeof d === "string") setRefused(d); else if (!("warn" in d)) pick(d, lite); } });
    else pick(c);
  });
  const now = Date.now();
  // T-2037: one card per game, its version with the latest player change on top
  const backups = useBackups(slots.map((s) => s.key), slots.length);
  const groups = groupVersions(slots, accountOnly(slots), backups, "", accountList.value?.games ?? [], damagedAccount.value);
  const last = lastPlayedSlot();
  const resume = groups.find(g => !g.damaged && g.top.kind === "slot" && g.top.slot.key === last)
    ?? groups.find(g => !g.damaged);
  const openCard = (s: ReturnType<typeof cardOf>) => {
    if (overBudget(s.stations)) setMemWarn({ key: s.key, stations: s.stations! });
    else void open(s.key);
  };
  const wait = () => Promise.race([settling, new Promise((r) => setTimeout(r, SETTLE_WAIT_MS))]);
  const chose = (c: GameChoice | string | { warn: string }) => { if (typeof c === "string") return c; if ("warn" in c) return c.warn; pick(c); return ""; };
  const actions = (g: GameGroup): VersionActions => ({
    open: async (v) => {
      if (v.kind === "slot") { await open(v.slot.key); return ""; }
      if (v.kind === "account") { await open("account:" + v.game.id); return ""; }
      await wait();
      if (v.kind === "backup") return chose(await backupChoice(v.backup.key, v.slot.key));
      const name = g.top.kind === "slot" ? g.top.slot.name : g.top.game.name;
      if (v.kind === "pinned") {
        const pinned = await pinnedFile(v.game.id, name);
        return pinned ? chose(await fileChoice(pinned, { origin: g.origin, edit: v.game.pinned!.savedAt * 1000 })) : t("startMenu.backupDownloadFailed");
      }
      const file = await accountBackupFile(v.game.id, name, v.backup);
      return file ? chose(await fileChoice(file, { origin: g.origin, edit: v.backup.savedAt * 1000 })) : t("startMenu.backupDownloadFailed");
    },
    remove: async (v) => { await remove([v]); return ""; },
  });
  // T-728: the new game sheet; its game gets a slot of its own named after its start, so no saved game is replaced
  // T-1567: the logo stays mounted over both, so its trains run on when the sheet opens
  // with the logo the welcome heading goes (Anita), the logo naming the menu
  // 168Railway launcher (2026-11-26, Stitch option 8, calm colours): the saved games as a row of cards, the one
  // played last first with Continue; each card its picture, four figures, open, versions and delete
  const ordered = resume ? [resume, ...groups.filter((g) => g !== resume)] : groups;
  const scenTip = view.tab === "real" ? t(`newGame.jaboTip.${view.jabo}` as MessageKey) : t("newGame.blankStageTip");
  return <div class="start-menu launch-menu" role="dialog" aria-modal="true" aria-labelledby="start-title">
    {!sheet && <canvas ref={metro} class="start-metro" aria-hidden="true" />}
    {guest ? <GuestGate first={first} onIn={() => { setGuest(false); void listSlots().then(setSlots, () => {}); }} /> : sheet ? <div class="launch launch-new is-card">
      <LaunchStrip />
      <section class="launch-side new-game-box">
        <div class="launch-titlebar">
          <button class="btn icon launch-back" aria-label={t("newGame.back")} data-tip={t("newGame.back")} onClick={() => setSheet(false)}><Icon.goBack /></button>
          <h1 id="start-title">{t("startMenu.newGame")}</h1>
          {/* T-2753: the language on the first row, so a new player (who lands on the sheet) sets it first */}
          <div class="start-top"><LanguageSelect /><AccountButton kind="start" /></div>
        </div>
        <NewGameSheet heading={false} back={false} tab={sheetTab} jabo={sheetJabo} city={sheetCity} onView={setView} onBack={() => setSheet(false)} onStart={(game) => pick({ newGame: true, newSlot: newGameName(slots, game), game })} />
      </section>
      <div class="launch-stage">
        <StageSnap id={view.tab === "real" ? view.jabo : "base-jak"} />
        <div class="launch-panel launch-scenario">
          <span class="launch-kicker">{view.tab === "real" ? t("newGame.tabReal") : t("newGame.tabBlank")}</span>
          <strong class="launch-panel-title">{view.tab === "real" ? t(`newGame.jabo.${view.jabo}` as MessageKey) : t("newGame.tabBlank")}</strong>
          <p>{scenTip}</p>
        </div>
      </div>
    </div> : <div class="launch launch-row">
      <header class="row-head">
        <span class="row-brand"><span class="railway-mark" aria-hidden="true"><Icon.train /></span>168Railway <strong>World</strong></span>
        <div class="start-top"><AccountButton kind="start" /></div>
      </header>
      <main class="row-main">
        <UpdateRow onChangelog={() => setChangelog(true)} />
        <div class="row-title">
          <h1 id="start-title">{groups.length ? t("launch.myGames") : t("launch.welcomeTitle")}</h1>
          <button ref={first} class="btn row-new" onClick={() => openSheet("real")}><Icon.plus />{t("startMenu.newGame")}</button>
        </div>
        {(refused || deleteOwed.value) && <p class="muted small start-refused" role="alert">{refused || t("cloud.deleteOwed")}</p>}
        {warned && !refused && <p class="muted small start-refused" role="alert">{warned.text} <button class="btn" onClick={() => { setWarned(null); warned.go(); }}>{t("startMenu.loadAnyway")}</button> <button class="btn" onClick={() => { setWarned(null); warned.go(true); }}>{t("startMenu.loadWithoutIntercity")}</button></p>}
        {unread && <p class="muted small start-refused start-unread" role="alert">{t("startMenu.savesUnreadable")}</p>}
        {ordered.length > 0 ? <ul class="row-cards start-saves" aria-label={t("startMenu.savedGames")}>
          {ordered.map((g) => {
            const s = g.top.kind === "slot" ? g.top.slot : cardOf(g.top.game);
            const last = g === resume;
            const tag = last ? t("launch.lastPlayed") : s.mode === "sandbox" ? t("newGame.sandbox") : t("newGame.normal");
            return <GameCard key={g.origin} origin={g.origin} accentOf={s.thumb} last={last}>
              <div class="sc-head">
                <span class="sc-badge" aria-hidden="true">{(s.name || "?").slice(0, 1).toUpperCase()}</span>
                <div class="sc-name"><h2 class="row-card-title"><SlotTitle slot={s} /></h2><span class="muted small">{tag} · {savedWhen(s.at, now)}</span></div>
              </div>
              <button class="sc-pic start-save" data-slot={s.key} tabIndex={-1} aria-hidden="true" onClick={() => openCard(s)}><SlotThumb slot={s} /></button>
              {!s.unreadable && <div class="sc-pills num">
                <span><i>{t("launch.statDay")}</i> {s.day}</span>
                {s.lines !== undefined && <span><i>{t("launch.statLines")}</i> {s.lines}</span>}
                {s.stations !== undefined && <span><i>{t("launch.statStationsShort")}</i> {s.stations}</span>}
                {s.mode !== "sandbox" && s.cash !== undefined && <span><i>{t("launch.statCash")}</i> <Money dollars={s.cash * 1e6} /></span>}
              </div>}
              {/* a damaged or refused copy says so, as the saves panel does */}
              {(s.damaged || s.unreadable || s.cloud?.error) && <div class="row-facts"><SlotFacts slot={s} now={now} /></div>}
              {memWarn?.key === s.key && <MemoryWarn stations={memWarn.stations} onGo={() => { setMemWarn(null); void open(s.key); }} onCancel={() => setMemWarn(null)} />}
              <button class={"btn sc-go" + (last ? " railway-resume" : "")} onClick={() => openCard(s)}><Icon.play1 />{last ? t("railway.continue") : t("launch.open")}</button>
              <div class="row-card-actions">
                <VersionsLine group={g} now={now} act={actions(g)} />
                <button class="btn icon row-delete start-delete" data-tip={t("startMenu.delete")} aria-label={t("startMenu.deleteGame", { name: s.name })} onClick={() => setRemoving(g.origin)}><Icon.bin /></button>
              </div>
              {removing === g.origin && <div class="callout bad">
                <DeleteAsk group={g} yesLabel={t("startMenu.deleteSave")} keepLabel={t("startMenu.keepIt")} yesClass="start-delete-yes" onYes={() => void remove(groupHeads(g))} onKeep={() => setRemoving(null)} />
              </div>}
            </GameCard>;
          })}
        </ul> : <div class="row-cards row-welcome">
          <button class="row-card row-pick" onClick={() => openSheet("real")}>
            <StageSnap id="jak" class="row-pick-img" />
            <strong>{t("launch.welcomeJabo")}</strong><span class="muted">{t("launch.welcomeJaboText")}</span>
          </button>
          <button class="row-card row-pick" onClick={() => openSheet("blank")}>
            <StageSnap id="base-jak" class="row-pick-img" />
            <strong>{t("launch.welcomeBlank")}</strong><span class="muted">{t("launch.welcomeBlankText")}</span>
          </button>
        </div>}
        <section class="lsec">
          <div class="lsec-head"><h2>{t("launch.startNew")}</h2><span class="muted">{t("launch.startNewSub2")}</span></div>
          <StartPicks onPick={openSheet} />
        </section>
        <div class="lsec-two"><CommunityPeek /><BoardPeek /></div>
        <HostWorlds openFile={openHostFile} />
        <footer class="row-foot">
          <button class="btn start-file" onClick={load}><Icon.download />{t("startMenu.loadFile")}</button>
          <CommunityButton start />
          <LanguageSelect />
          <span class="row-foot-gap" />
          <a href="./about.html" target="_blank" rel="noopener">{t("railway.credits")}</a>
          <span class="start-version muted small">{t("startMenu.build", { version: VERSION })}</span>
        </footer>
      </main>
    </div>}
    <Changelog open={changelog} onClose={() => setChangelog(false)} />
  </div>;
}

/** Put up the menu; `onPick` gets the player's choice once, after the menu has gone. */
/** T-3478: the new game the saves panel picked, which the menu starts as soon as it is up (`?next=new`) */
let nextNew: { choice: GameChoice; at: { center: [number, number]; zoom: number } | null } | null = null;

export function showStartMenu(onPick: (c: GameChoice) => void) {
  // 168Railway: Google's sign-in window back on the game tells the game that opened it and closes
  if (finishSignInWindow()) return;
  choosing.value = true;
  document.documentElement.classList.add("choosing");
  startAccountUi();
  const root = document.createElement("div");
  document.body.appendChild(root);
  // the account panel (T-972) and Community (a share link, T-3115) open over the menu and take the keys while open
  const unblock = blockKeysOutside(root, () => !document.querySelector("dialog.acct-panel[open], dialog.community-panel[open]"));
  let done = false;
  const pick = (c: GameChoice) => {
    if (done) return;
    done = true;
    if (!c.newGame) noteVisit("continue");
    stopStartSettle();
    unblock();
    render(null, root);
    root.remove();
    document.documentElement.classList.remove("choosing");
    choosing.value = false;
    document.getElementById("boot-cover")?.remove();
    onPick(c);
  };
  // T-3478: as the new game sheet's Start does (NewGame.tsx), on this side of the reload
  if (nextNew) {
    const { choice, at } = nextNew;
    nextNew = null;
    setStartAt(at);
    display.value = { ...display.peek(), commuters: true, commuterEnd: newGameEnd(choice.game?.mode) };
    return pick(choice);
  }
  // T-988: the slots settled against the account (signed in) while the menu is up
  const settling = settleOnStart().catch(() => {});
  // storage blocked (a private window): no saved games, New game alone; storage that kept failing to
  // read says so (T-2705)
  const q = new URLSearchParams(location.search);
  if (q.get("debug") === "1") failLists(Number(q.get("listFails")) || 0);
  // 168Railway: guests see the menu but sign in to play; the check runs beside the slots' listing, so the menu
  // shows once, already right (a failed or slow check lets the player in)
  const slotsRead = listSlots().then((slots) => ({ slots, unread: false }), (err) => ({ slots: [] as SaveSlot[], unread: err instanceof StorageReadError }));
  void Promise.all([slotsRead, checkHostAuth()])
    .then(([{ slots, unread }, auth]) => { if (!done) { noteVisit("open"); render(h(Menu, { slots, unread, pick, settling, guest: auth === "out" }), root); } document.getElementById("boot-cover")?.remove(); });
}
