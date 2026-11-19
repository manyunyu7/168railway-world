// Top bar: city (or the world, zoomed far out), the mode split (the goal number: the bar and the
// train share), cash, the time and date on one line (T-1853), speed and pause. The clock is its own
// component so a game minute re-renders only it. The sky and demand band are `MapSky`, on the map.

import { clock, DEMAND_CAP, REAL4_SPEED, REAL_SPEED, SLIDER_STOPS, SPEEDS } from "../game/clock";
import { activeCity, cityById, viewedZone } from "../game/cities";
import { demandAskTurn, demandView, modeRides, modeSplit, viewRecomputing, viewUpdating } from "../game/demand";
import { RIDES_PER_PERSON, smallPct, WORLD_PEOPLE, WORLD_RIDES, worldTotals, worldView } from "../game/worldTotals";
import { clockTime, dayOf, exact, money, moneyCash, usingUsd, withFullSymbol } from "../game/format";
import { AVERAGE_DAY, awayNear, dateName, dateOf, dateRange, MONTH_LONG, rushRun, weekdayOf, whenAway, yearOf } from "../game/days";
import { PERIODS, periodAt } from "../game/periods";
import { SkyBanner } from "./SkyBanner";
import { StatusTag } from "./StatusTag";
import { BusyTag } from "./BusyTag";
import { countryName, groupHolidays, holidaysNear, loadHolidays, rushNear, runToday, superDays, supersNear, type HolidaySpan, type RushSpan } from "../game/holidays";
import { useEffect, useRef, useState } from "preact/hooks";
import { display, isBuildTool, setDisplay, shownDay, shownDayOn, tab, tool } from "../game/ui";
import { signal, effect } from "@preact/signals";
import { splitTable, splitTip } from "../game/splitTip";
import { lengthsOf, wantLengths } from "../game/zoneLengths";
import { t, tList, tNumber, tSpaced } from "../i18n";
import { tPlace } from "../i18n/places";
import { world } from "../game/world";
import { liveMoney, moneyState } from "../game/money";
import { pointsScore, pointsShort } from "../game/points";
import { Icon } from "./widgets";
import { perfOff } from "../perfFlags";
import { manageSaveSlot, saveSlotsState } from "../workers/clockClient";
import { START_CASH } from "../workers/saveFile";
import { CACHE_VERSION } from "../workers/demandCache";
import { savesOpen } from "./SavesPanel";

const NO_CLOCK = perfOff("clock");

const hourTime = (h: number) => clockTime(Math.round(h * 60) % 1440);
const WEEKDAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];   // i18n-ignore

/** The demand band's hover (T-902, Anita; T-1242): "Low demand until 06:00", the end being where
 * the level next changes. */
function periodTip(minute: number): string {
  let p = PERIODS[periodAt(minute * 60)];
  const level = p.demand;
  for (let k = 0; k < PERIODS.length; k++) {
    const next = PERIODS.find((q) => q.from === p.to % 24)!;
    if (next.demand !== level) break;
    p = next;
  }
  return t("topBar.demandUntil", { level, time: hourTime(p.to) });
}

/** "Today: x", "Tomorrow: x", or with the hour it starts where its country keeps another local time. */
function dayLine(t0: number, now: number, today: number, what: string): string {
  if (t0 <= now) return t("topBar.today", { what });
  const tomorrow = Math.floor(t0 / 86400) !== today;
  return t0 % 86400 === 0 ? t(tomorrow ? "topBar.tomorrow" : "topBar.today", { what }) : t(tomorrow ? "topBar.tomorrowFrom" : "topBar.todayFrom", { time: clockTime(t0 / 60), what });
}

/** T-862: one line per holiday on the sky, "Today: Thanksgiving Day, United States", "Tomorrow: …",
 * or with the hour it starts where its country keeps another local time. T-1098: one line for a
 * holiday several countries keep that date, "Labor Day, Belgium, China and 4 others", or every
 * country named (`full`, the sky's hover). */
function holidayLines(minute: number, list: HolidaySpan[], full: boolean): { text: string; today: boolean }[] {
  const now = minute * 60, today = Math.floor(now / 86400);
  return groupHolidays(list.filter((h) => h.t1 > now && h.t0 < now + 12 * 3600)).map((h) => {
    const names = h.countries.map(countryName);
    const where = full || names.length <= 3 ? names : [...names.slice(0, 2), t("topBar.others", { count: names.length - 2 })];
    const at = t("topBar.holidayAt", { name: h.name, places: tList(where) });
    // the lines that say plain "Today:" (the clock's hover lists these)
    const isToday = h.t0 <= now || (Math.floor(h.t0 / 86400) === today && h.t0 % 86400 === 0);
    return { text: dayLine(h.t0, now, today, at), today: isToday };
  });
}

/** T-264, T-1697: one line per superholiday day on the sky that no holiday line names, with the dates
 * of its part: "Tomorrow: Golden Week, Apr 29-30, China", "Today: Golden Week, May 1-5, China".
 * Several countries on one date share a line, as `holidayLines`. */
function rushLines(minute: number, list: RushSpan[], hols: HolidaySpan[], full: boolean): string[] {
  const now = minute * 60, today = Math.floor(now / 86400);
  const by = new Map<string, { r: RushSpan; countries: string[] }>();
  for (const r of list) {
    if (r.t1 <= now || r.t0 >= now + 12 * 3600) continue;
    if (r.part === 2 && hols.some((h) => h.country === r.country && h.day === r.day)) continue;
    const key = `${r.day} ${r.part} ${r.name}`, e = by.get(key);
    if (e) { if (!e.countries.includes(r.country)) e.countries.push(r.country); } else by.set(key, { r, countries: [r.country] });
  }
  return [...by.values()].map(({ r, countries }) => {
    const names = countries.map(countryName).sort();
    const where = full || names.length <= 3 ? names : [...names.slice(0, 2), t("topBar.others", { count: names.length - 2 })];
    return dayLine(r.t0, now, today, t("topBar.rushAt", { name: r.name, dates: r.dates, places: tList(where) }));
  });
}

/** the Day choices that name a day of the week (topBar.dayOn words them) */
const DAY_KINDS = new Set(["average", "weekday", "saturday", "sunday"]);
const dayOn = (key: string) => t("topBar.dayOn", { day: DAY_KINDS.has(key) ? key : "average" });
/** the Day choice before the calendar icon set it to a day away, to go back to */
let dayBefore = "average";

/** How many days ahead of a superholiday's going-out day the pink dot is a button (T-1476). */
const CAL_AHEAD = 2;

/** T-264, T-1476, T-1373, T-1865: the pink dot by the date. It shows while a holiday is today (`today`),
 * and is a button while a superholiday where the player has built is under way or goes out within
 * `CAL_AHEAD` days (the calendar icon it replaced). Its hover has each run's days within 21 days, and a
 * click shows demand on its next going-out day (the Day choice's own option; once those are past, a
 * coming-back day, then a day away) or back to the day shown before. The dot is yellow until a run
 * is under way (T-1888), then pink. */
function HolidayDot({ today }: { today: boolean }) {
  const runs = supersNear.value;
  void superDays.value; // a day turning, for `runToday` below
  const away = awayNear.value, rush = rushRun.value;
  const d = display.value;
  if (!runs.some((r) => r.out - runToday(r) <= CAL_AHEAD)) return today ? <i class="holiday-dot" /> : null;
  // the day a click shows: the first run's next rush day still to come
  const first = runs.find((r) => runToday(r) < r.end);
  const nowDay = first ? runToday(first) : 0;
  const target: { key: "out" | "back" | "away"; what: string } | null = !first ? null
    : rush && first.out < first.away && nowDay < first.away ? { key: "out", what: t("topBar.goingOut", { dates: dateRange(first.out, first.away) }) }
    : rush && first.back < first.end ? { key: "back", what: t("topBar.comingBack", { dates: dateRange(first.back, first.end) }) }
    : away ? { key: "away", what: t("topBar.dayOf", { what: whenAway.value ?? away }) } : null;
  const showing = (d.dayShown === "out" || d.dayShown === "back") ? rush !== null : d.dayShown === "away" && away !== null;
  const lines: string[] = [];
  for (const r of runs) {
    const n = r.out - runToday(r);
    if (lines.length) lines.push("");
    const dates = r.back > r.away ? dateRange(r.away, r.back) : dateRange(r.out, r.end);
    lines.push(t("topBar.runLine", { name: r.name, dates, country: countryName(r.country), timing: n > 1 ? t("topBar.inDays", { count: n }) : n === 1 ? t("topBar.timingTomorrow") : t("topBar.timingNow") }));
  }
  if (target || showing) lines.push("", showing ? t("topBar.showAgain", { day: dayOn(dayBefore) }) : t("topBar.showOn", { what: target!.what }));
  const click = () => {
    if (showing) setDisplay("dayShown", (DAY_KINDS.has(dayBefore) ? dayBefore : "average") as typeof d.dayShown);
    else if (target) { dayBefore = d.dayShown; setDisplay("dayShown", target.key); }
  };
  // yellow until a run is under way (every hover line says "in N days" or "tomorrow"), pink after
  const coming = !today && runs.every((r) => r.out > runToday(r));
  return <button class={"holiday-dot btn-dot" + (coming ? " coming" : "") + (showing ? " on" : "")} data-tip={lines.join("\n")} aria-label={t("topBar.longHolidays")} onClick={click} />;
}

/** T-861, T-1853: the time and date on one line. */
function Clock() {
  // ?perfOff=clock (T-045, measurement only): read without subscribing, so it never re-renders.
  // T-309: the local time and day where the player is looking
  const minute = NO_CLOCK ? clock.local.peek() : clock.local.value;
  // T-172: a loaded game's clock waits for its first demand estimate; building does not
  const held = clock.held.value;
  const day = dayOf(minute), { month, date } = dateOf(day);
  useEffect(loadHolidays, []);
  const near = holidaysNear.value;
  const todayHols = holidayLines(minute, near, false).filter((l) => l.today).map((l) => l.text);
  const dateTip = t("topBar.dateTip", { weekday: t("topBar.weekday", { day: WEEKDAY_KEYS[weekdayOf(day)] }), month: MONTH_LONG[month], date, year: yearOf(day), day });
  return (
    // T-3635: a page translator rewriting the date's text can break the holiday dot's diff and leave stale clocks behind
    <div class="clock" translate={false}>
      <div class="clock-text" data-tip={held ? t("topBar.clockHeld") : [dateTip, ...todayHols].join("\n")}>
        <svg class="hud-symbol clock-symbol" aria-hidden="true" viewBox="0 0 20 20"><circle cx="10" cy="10" r="7.5"/><path d="M10 5.5V10l3 2"/></svg>
        <b class={"time lg" + (held ? " held" : "")}>{clockTime(minute)}</b>
        <span class="date"><HolidayDot today={todayHols.length > 0} />{dateName(day)}</span>
      </div>
    </div>
  );
}

/** T-1868: the day and night picture with its demand band, at the map's top right under the bar (it was
 * in the bar). Its hover lists the holidays in view; the band's gives the demand level. Mounted in the
 * map's container by main.ts. T-2202: under it, on a narrow map, the status tags (shown beside the demand
 * buttons otherwise, style.css). */
export function MapSky() {
  const minute = NO_CLOCK ? clock.local.peek() : clock.local.value;
  const near = holidaysNear.value, rush = rushNear.value;
  const hols = [...holidayLines(minute, near, true).map((l) => l.text), ...rushLines(minute, rush, near, true)];
  return <div class="map-sky">
    <SkyBanner skyTip={hols.length ? hols.join("\n") : undefined} bandTip={periodTip(minute)} />
    <div class="sky-status"><StatusTag /><BusyTag /></div>
  </div>;
}

/** T-284: its own component, re-rendered as fares land (several times a wall second while the clock
 * runs) without the rest of the top bar. */
function Cash() {
  const live = liveMoney.value, m = moneyState.value;
  const today = live.today;
  const tip = today ? t("topBar.cashTip", { fares: withFullSymbol(money(today.fares * 1e6)), running: withFullSymbol(money(-today.running * 1e6)), upkeep: withFullSymbol(money(-(today.upkeep ?? 0) * 1e6)) }) : undefined;
  // T-728: a sandbox shows no cash; the hover has the money since the start
  if (m?.mode === "sandbox") return <div class="cash sandbox" data-tip={[t("topBar.infiniteMoney"), t("topBar.sinceDay1", { amount: money((live.cash - START_CASH) * 1e6) }), ...(tip ? [tip] : []), t("moneyTab.scaleTip")].join("\n")}><span class="hud-symbol" aria-hidden="true"><Icon.money/></span>{t("topBar.sandbox")}</div>;
  // T-1865: after the split, in a box of fixed width (style.css .cash), so nothing beside it moves
  const shown = m ? moneyCash(live.cash * 1e6) : "";
  return <div class={"cash num" + (usingUsd() ? "" : " cash-wide") + (shown.startsWith("−") ? " money-neg" : "")} data-tip={m ? [...(tip ? [tip] : []), t("moneyTab.scaleTip")].join("\n") : undefined}><span class="hud-symbol" aria-hidden="true"><Icon.money/></span>{shown}</div>;
}

/** The city's name, the whole of it and its points (T-684, T-3465: its forecast weekday's) on hover. */
function CityName({ id, name, tip }: { id: string | null; name: string; tip?: string }) {
  const score = id ? pointsScore.value.get(id) : undefined;
  const lines = [name];
  if (id && score) lines.push(t("topBar.pointsPast", { count: exact(score) }));
  return <div class="city" data-tip={tip ?? (id ? lines.join("\n") : undefined)}>{name && <span class="hud-symbol" aria-hidden="true"><Icon.pin/></span>}{name}</div>;
}

/** A speed in times normal speed: "real" and "4real" for the two slowest (T-3327), else "16x". */
const times = (x: number) =>
  Math.abs(x - REAL_SPEED) < 1e-9 ? t("topBar.speedReal") : Math.abs(x - REAL4_SPEED) < 1e-9 ? t("topBar.speedReal4") : `${x}x`;

/** T-1664: double-click the fastest button for a slider of speeds, real to 100x (the third button always
 * shows its speed as text); T-1665: while demand updates the clock is held at DEMAND_CAP, the chosen speed dimmed. */
/** 168Railway (after Railway Studio): the map's layer rows (demand, track display, ridership) folded behind the
 * action bar's Layers button; the find button and the edit notes stay. Open for the player who asks. */
const LAYERS_KEY = "railway-layers-open";
export const layersOpen = signal<boolean>((() => { try { return localStorage.getItem(LAYERS_KEY) === "1"; } catch { return false; } })());
effect(() => {
  const open = layersOpen.value;
  if (typeof document !== "undefined") document.documentElement.dataset.layers = open ? "open" : "closed";
  try { localStorage.setItem(LAYERS_KEY, open ? "1" : "0"); } catch { /* none */ }
});
/** the city card folded to its first row (phones keep the map) */
const CARD_KEY = "railway-card-folded";
const cardFolded = signal<boolean>((() => { try { return localStorage.getItem(CARD_KEY) === "1"; } catch { return false; } })());

/** The action bar at the map's foot (Railway Studio's): run or pause, build, the speed, the layers. The speed chip
 * steps through the three speeds without starting a paused clock; a double click opens the slider of speeds
 * (T-1664). Run carries the tutorial's "play" mark. */
export function StudioActions() {
  const running = clock.running.value;
  const speed = clock.speed.value;
  const custom = clock.custom.value;
  const capped = clock.capped.value;
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("pointerdown", away); document.removeEventListener("keydown", esc); };
  }, [open]);
  const w = world.value;
  // while a build tool is out its own bars take the map's foot
  if (!w || isBuildTool(tool.value)) return null;
  const chosen = clock.chosenRate.value / SPEEDS[0];
  const held = capped && chosen > DEMAND_CAP;
  const cur = custom !== null ? 2 : speed;
  const step = () => {
    const was = clock.running.peek();
    const next = (cur + 1) % SPEEDS.length;
    if (next === 2 && custom !== null) clock.setMultiple(custom); else clock.setSpeed(next);
    if (!was) clock.setRunning(false);
  };
  const stop = Math.max(0, SLIDER_STOPS.findIndex((x) => x >= chosen - 1e-9));
  const layers = layersOpen.value;
  return (
    <div class="studio-actions" ref={box}>
      <button class={"btn studio-run" + (running ? " running" : "")} data-guide="play" data-tip={t("topBar.pauseTip")}
        aria-label={running ? t("topBar.pause") : t("topBar.normalSpeed")} onClick={() => clock.setRunning(!running)}>
        {running ? <Icon.pause /> : <Icon.play1 />}<span>{running ? t("studio.pause") : t("studio.run")}</span>
      </button>
      <button class={"btn studio-build" + (tab.value === "build" ? " on" : "")} aria-label={t("studio.build")} onClick={() => { tab.value = "build"; }}>
        <Icon.wrench /><span>{t("studio.build")}</span>
      </button>
      <button class={"btn studio-speed" + (held ? " held" : "")} aria-label={t("topBar.clockSpeed")}
        data-tip={held ? t("topBar.speedCapped", { speed: times(chosen), cap: times(DEMAND_CAP) }) : t("studio.speedTip")}
        onClick={step} onDblClick={() => setOpen((o) => !o)}>{times(chosen)}</button>
      <button class={"btn icon studio-layers" + (layers ? " on" : "")} aria-pressed={layers} aria-label={t("studio.layers")} data-tip={t("studio.layers")}
        onClick={() => { layersOpen.value = !layers; }}>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path class="stroke2" d="M8 2.5l6 3-6 3-6-3z M2 8.5l6 3 6-3 M2 11.5l6 3 6-3" fill="none" /></svg>
      </button>
      {open && (
        <div class="slider speed-pop">
          <input type="range" min={0} max={SLIDER_STOPS.length - 1} step={1} value={stop} aria-label={t("topBar.clockSpeed")}
            onInput={(e) => clock.setMultiple(SLIDER_STOPS[Number((e.target as HTMLInputElement).value)])} />
          <span class="num">{times(chosen)}</span>
        </div>
      )}
    </div>
  );
}

/** The card's status line: the period of the day and whether the clock runs. */
function PeriodLine() {
  const minute = NO_CLOCK ? clock.local.peek() : clock.local.value;
  const p = PERIODS[periodAt(minute * 60)];
  return <div class="studio-status" data-tip={periodTip(minute)}>
    <span>{p?.name}</span><span class="studio-status-run">{clock.running.value ? t("studio.running") : t("studio.paused")}</span>
  </div>;
}

/** The world's bar hover: the train trips/day in the cities (T-1440), the share of the world's
 * fixed trips a day and how that is reached (T-1301), then the cities' own split. */
function worldShareTip(tot: ReturnType<typeof worldTotals>, day: string, note: string): string {
  const big = (n: number) => tNumber(n, { notation: "compact", compactDisplay: "long" });
  const world = t("topBar.worldTrips", { rides: big(WORLD_RIDES), people: big(WORLD_PEOPLE), each: tNumber(RIDES_PER_PERSON) });
  if (!tot.split || !tot.rides) return t("topBar.trainShare", { world, note });
  const cities = t("topBar.cities", { count: tot.cities });
  return [
    t("topBar.tripsInCities", { count: exact(tot.rides.train), cities, note }),
    t("topBar.trainsCarry", { share: smallPct(tot.split.train), world }),
    splitTip(joinWhere(t("topBar.inCitiesWithStations", { cities }), day), tot.citySplit, tot.rides, ""),
  ].join("\n");
}

/** The phrase after "Trips per day" in the split's hovers, or empty. */
const joinWhere = (place: string, day: string) => tSpaced(place && day ? t("topBar.placeDay", { place, day }) : place || day);

function SaveNotice() {
  const state = saveSlotsState.value;
  if (state.writable) return null;
  // T-3257: a network viewed from Community is kept nowhere until saved
  if (state.viewing) return <div class="save-notice" role="status">
    <span>{t("topBar.viewing", { name: state.viewing.name })}</span>
    <button class="btn" onClick={() => manageSaveSlot("save")}>{t("topBar.viewSave")}</button>
  </div>;
  return <div class="save-notice" role="status">
    <span>{state.reason ?? t("topBar.autosaveOff")}</span>
    <button class="btn" onClick={() => { savesOpen.value = true; }}>{t("topBar.saves")}</button>
  </div>;
}

export function TopBar() {
  // T-2365: the zone's km by mode for the split's hover, asked for while the pointer is on it
  const [over, setOver] = useState(false);
  const dv = demandView.value, zone = !worldView.value && viewedZone.value ? activeCity.value : null;
  useEffect(() => { if (over && zone) wantLengths(zone); }, [over, zone, dv?.version, dv?.round, shownDay.value, demandAskTurn.value]);
  const w = world.value;
  // 168Railway: the card's height for what sits under it on the map (the layer rows, the sky on a phone)
  const card = useRef<HTMLElement>(null);
  useEffect(() => {
    const el = card.current;
    if (!el) return;
    const ro = new ResizeObserver(() => document.documentElement.style.setProperty("--studio-card-h", el.offsetHeight + "px"));
    ro.observe(el);
    return () => ro.disconnect();
  }, [!!w]);
  if (!w) return <header id="topbar" />;
  // T-1110: zoomed far out the bar names the world and sums every city, as the Cities tab's first row does
  const far = worldView.value;
  const total = far ? worldTotals() : null;
  const known = total ? total.split : modeSplit.value; // T-026: from the demand workers
  const s = known ?? { train: 0, walk: 0, drive: 0 };
  // T-318: no name over dead space (the focus stays on the last zone, which is not where the view is)
  const city = !far && viewedZone.value ? cityById(activeCity.value) : null;
  const cityName = city ? tPlace(city.id, city.name) : "";
  // T-172: no figures until the city has opened (the game is playable while it loads)
  // T-1440: zoomed out, the train trips/day in place of the world's share
  const pct = (v: number) => (!known ? "–" : far ? t("topBar.tripsPerDay", { count: pointsShort(total?.rides?.train ?? 0) }) : tNumber(v, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + "%");
  // T-342: the shares are of rides (trip legs) a day; the train's count beside its share, all in the hover
  const rides = total ? total.rides : modeRides.value;
  const dayPhrase = shownDay.value === AVERAGE_DAY ? "" : shownDayOn.value;
  const where = joinWhere(far ? t("topBar.inWorld") : city ? t("topBar.inCity", { name: cityName }) : "", dayPhrase);
  // T-1223: the zone in view shown rough while it is worked out in detail
  // T-1851: one note for every case where the trips are being recomputed
  const note = demandView.value && (viewRecomputing.value || demandView.value.version === CACHE_VERSION) ? tSpaced(t("topBar.recomputing")) : "";
  const worldTip = total ? t("topBar.worldTip", { cities: total.cities, stations: total.stations }) : undefined;
  const folded = cardFolded.value;
  const fold = () => { cardFolded.value = !folded; try { localStorage.setItem(CARD_KEY, folded ? "0" : "1"); } catch { /* none */ } };
  return (
    <><header id="topbar" ref={card} class={"studio-card" + (folded ? " folded" : "")}>
      {/* 168Railway (after Railway Studio): a floating card, the city and three figures, the period under them */}
      <div class="studio-card-head">
        {/* T-1108: the name takes what it needs up to a cap, on two lines at most, the whole name on hover */}
        {far ? <CityName id={null} name={t("topBar.world")} tip={worldTip} /> : <CityName id={city?.id ?? null} name={cityName} />}
        <button class="btn icon studio-card-fold" aria-expanded={!folded} aria-label={folded ? t("studio.cardOpen") : t("studio.cardFold")} onClick={fold}>
          <svg viewBox="0 0 16 16" aria-hidden="true"><path class="stroke2" d={folded ? "M4 6l4 4 4-4" : "M4 10l4-4 4 4"} fill="none" /></svg>
        </button>
      </div>
      <div class="studio-stats">
        <div class="studio-stat"><Clock /></div>
        {/* T-2365: the zone's km by mode for the split's hover; `split-row` is the tutorial's mark (GUIDE_SPLIT) */}
        <div data-tip={total ? worldShareTip(total, dayPhrase, note) : splitTable(where, known, rides, city ? lengthsOf(city.id) : null, note)}
          class={"studio-stat split-row" + (viewUpdating.value ? " updating" : "")} onPointerEnter={() => setOver(true)} onPointerLeave={() => setOver(false)}>
          <details class="hud-travel" onKeyDown={e=>{if(e.key==="Escape"){e.stopPropagation();e.preventDefault();e.currentTarget.open=false;e.currentTarget.querySelector("summary")?.focus();}}}>
            <summary aria-label={t("tripText.modeShare",{mode:t("splitTip.train"),share:pct(s.train)})}>
              <span class="hud-symbol" aria-hidden="true"><Icon.train/></span><b class={"pct" + (far ? " trips" : "")}>{pct(s.train)}</b>
            </summary>
            <div class="hud-travel-detail">
              <b>{t("splitTip.perDay",{where,note})}</b>
              {([["train","splitTip.train"],["walk","splitTip.walking"],["drive","splitTip.driving"],["none","splitTip.noRoute"]] as const)
                .filter(([mode])=>mode!=="none" || (s.none??0)>0)
                .map(([mode,key])=><div key={mode}><span>{t(key)}</span><b>{known?tNumber(s[mode]??0,{maximumFractionDigits:1})+"%":"–"}</b></div>)}
            </div>
          </details>
          <span class="studio-label">{far ? t("studio.trainTrips") : t("studio.trainShare")}</span>
        </div>
        <div class="studio-stat"><Cash /><span class="studio-label">{t("studio.cash")}</span></div>
      </div>
      <PeriodLine />
    </header><SaveNotice /></>
  );
}
