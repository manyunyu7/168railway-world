// A piece of track's signalling (sim/src/track/signal.rs): four levels, from open block to CBTC. A pick
// applies at once, to the piece or its whole block (station to station or junction); dearer signalling on
// built track is paid then, on a blueprint with its Construct. The rows say what the pick does.

import { useState } from "preact/hooks";
import { money } from "../../game/format";
import { t, tNumber } from "../../i18n";
import { edit } from "../../game/world";
import { readSignalPanel, SIGNAL_KEYS, SIGNAL_LEVELS, SIGNAL_OPEN, signalOf } from "../../game/signalling";
import type { TrackEdge } from "../../game/types";
import { Check, Row, Segmented } from "../widgets";
import { useClockQuery } from "../useClockQuery";

export function PieceSignal({ e, version }: { e: TrackEdge; version: number }) {
  const p = useClockQuery({ what: "signal", edge: e.id }, [e.id, version], readSignalPanel);
  const [whole, setWhole] = useState(true);
  if (!p) return null;
  const have = signalOf(e);
  const block = whole && p.block.length > 1;
  const quoteOf = (level: number) => (block ? p.quote[level].block : p.quote[level].piece);
  // What each level would give and cost, shown under the chips (a hover tip covered the inspector on phones).
  const offer = (level: number) => {
    const q = quoteOf(level), parts = [t("signal.most", { n: tNumber(Math.floor(p.tph[level])) })];
    if (q[0] > 0) parts.push(t("signal.payNow", { price: money(q[0] * 1e6) }));
    if (q[1] > 0) parts.push(t("signal.payLater", { price: money(q[1] * 1e6) }));
    return parts.join(" · ");
  };
  const pick = (level: number) => {
    if (level === have && !block) return;
    void edit({ op: "pieceSignal", edges: block ? p.block : [e.id], level });
  };
  return (
    <>
      <div class="h">{t("signal.title")}</div>
      <Segmented options={SIGNAL_LEVELS.map((l): [number, string] => [l, t(`signal.${SIGNAL_KEYS[l]}`)])} value={have} onChange={pick} />
      <p class="muted small">{t(`signal.${SIGNAL_KEYS[have]}Tip`)}</p>
      {p.block.length > 1 && (
        <Check checked={whole} onChange={setWhole}>{t("signal.wholeBlock", { n: p.block.length })}</Check>
      )}
      <p><b>{t("signal.capacity")}:</b> {t("signal.most", { n: tNumber(Math.floor(p.tph[have])) })}</p>
      <ul class="rows">
        {have === SIGNAL_OPEN && <Row label={t("signal.topSpeed")}>{t("signal.openTop")}</Row>}
        {p.price[have] > 0 && <Row label={t("signal.cost")}>{money(p.price[have] * 1e6)}</Row>}
        {e.built && p.upkeep[have] > 0 && <Row label={t("signal.upkeep")}><span class="money-neg">{t("inspector.perDay", { value: money(p.upkeep[have] * 1e6) })}</span></Row>}
      </ul>
      <ul class="rows">
        {SIGNAL_LEVELS.filter((l) => l !== have).map((l) => (
          <li class="muted small" key={l}>→ {t(`signal.${SIGNAL_KEYS[l]}`)}: {offer(l)}</li>
        ))}
      </ul>
    </>
  );
}
