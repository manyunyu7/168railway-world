// The tutorial card (T-1822, T-2123; notes/T-1822.md): a post-it at the map's upper right, under the
// search button, with the steps of the card in hand ticked off as the game shows them done.
// game/guide.ts checks the steps and keeps the progress in the save; this draws it. On a phone held
// upright it shrinks to its heading and the step in hand until the heading is tapped.

import { signal } from "@preact/signals";
import { h, render } from "preact";
import { editorSandboxGuide, guideShown, guideSteps, guideTicks, isJakartaGuide, startGuide, type GuideStep } from "../game/guide";
import { moneyState } from "../game/money";
import { ask, tab } from "../game/ui";
import { world } from "../game/world";
import { t, tNumber } from "../i18n";
import { saveGuide } from "../workers/clockClient";
import { ripple, startRipple } from "./ripple";
import { savesOpen } from "./SavesPanel";
import { Icon } from "./widgets";
import "./guide.css";

/** the phone layout shows every step after a tap on the heading */
const opened = signal(false);

/** Resume the guide embedded in this local archive, including its last card and checked steps. */
export function openEditorGuide() {
  const money = moneyState.peek();
  if (!money) return;
  const guide = money.guide;
  const reveal = () => {
    opened.value = true;
    if (money.mode === "sandbox") editorSandboxGuide.value = true;
    if (!guide || guide.closed) saveGuide({ done: 0 });
    else if (guide.hidden) {
      const { hidden: _hidden, ...progress } = guide;
      saveGuide(progress);
    }
  };
  if (guide?.closed) ask.value = {
    text: "Tutorial dunia ini sudah selesai. Mulai lagi dari langkah pertama?",
    yes: "Mulai ulang", no: "Kembali ke dunia", run: reveal,
  };
  else reveal();
}

function counter(c: NonNullable<GuideStep["count"]>): string {
  const one = { minimumFractionDigits: 1, maximumFractionDigits: 1 };
  return c.unit === "km" ? t("guide.counterKm", { have: tNumber(c.have, one), goal: tNumber(c.goal, one) }) : c.unit === "%" ? t("guide.counterPercent", { have: tNumber(c.have, one), goal: tNumber(c.goal) }) : `${tNumber(c.have)}/${tNumber(c.goal)}`;
}

function Guide() {
  const m = moneyState.value, w = world.value, g = m?.guide;
  if (!m || !w || !g || !guideShown(g, m.mode === "sandbox") || ask.value || savesOpen.value) return null;
  const steps = guideSteps(w, g);
  const { done, now } = guideTicks(steps, g);
  const finished = now < 0;
  const lesson = g.extension;
  const leaveLesson = () => {
    const { extension, ...previous } = g;
    if (extension) saveGuide({ ...previous, done: extension.previousDone });
  };
  const learnBuild = () => {
    opened.value = true;
    tab.value = "build";
    saveGuide({ ...g, done: 0, extension: {
      stations: w.save.stations.map(s => s.id), lines: w.save.lines.map(l => l.id),
      length: w.edges.reduce((n,e) => n + (e.off || e.crossover ? 0 : e.lengthM), 0), previousDone: g.done,
    } });
  };
  const close = () => (ask.value = { text: t("guide.closeQuestion"), yes: t("guide.closeYes"), no: t("guide.closeNo"), run: () => saveGuide({ ...g, hidden: true }) });
  return (
    <div class={"guide" + (isJakartaGuide() && g.card !== 2 ? " jakarta-guide" : "") + (opened.value ? " open" : "")} data-card={g.card ?? 1}>
      <div class="guide-head">
        <button class="guide-title" aria-expanded={opened.value} onClick={() => (opened.value = !opened.peek())}>{lesson ? "Bangun lintas baru" : t(isJakartaGuide() && g.card !== 2 ? "railway.guide.title" : "guide.title")}</button>
        <button class="btn icon guide-x" aria-label={t("guide.close")} onClick={close}>
          <Icon.cross />
        </button>
      </div>
      {(lesson || w.save.lines.length > 0) && <button class="btn text" data-extension-guide onClick={lesson ? leaveLesson : learnBuild}>
        {lesson ? "Kembali ke panduan utama" : "Belajar bangun lintas baru"}
      </button>}
      <ol class="guide-steps">
        {steps.map((s, i) => (
          <li key={s.id} data-step={s.id} class={done[i] ? "done" : i === now ? "now" : "later"} onClick={() => ripple(s.id)}>
            <span class="guide-box">{done[i] && <Icon.check />}</span>
            <span class="guide-words">{s.words}</span>
            {s.count && <span class="guide-count">{counter(s.count)}</span>}
            {s.note && (i === now || done[i]) && <span class="guide-note">{s.note}</span>}
          </li>
        ))}
      </ol>
      {finished && (
        <div class="guide-end">
          {lesson ? <button class="btn text" onClick={leaveLesson}>Selesai belajar membangun</button> : g.card === 2 || isJakartaGuide()
            ? <button class="btn text" data-guide="end" onClick={() => saveGuide({ ...g, closed: true })}>{t("guide.done")}</button>
            : <button class="btn text" data-guide="end" onClick={() => saveGuide({ ...g, card: 2 })}>{t("guide.continue")}</button>}
        </div>
      )}
    </div>
  );
}

/** Mount the card in the map's container. */
export function mountGuide(container: HTMLElement) {
  const root = document.createElement("div");
  container.appendChild(root);
  render(h(Guide, null), root);
  startGuide();
  startRipple();
}
