// Signalling per piece of track (sim/src/track/signal.rs): 0 open block (mechanical signals), 1 closed block
// (electric colour lights, every piece's unless the player chose another), 2 ATP, 3 CBTC.

import type { TrackEdge, WorldView } from "./types";

export const SIGNAL_OPEN = 0, SIGNAL_CLOSED = 1, SIGNAL_ATP = 2, SIGNAL_CBTC = 3;
export const SIGNAL_LEVELS = [SIGNAL_OPEN, SIGNAL_CLOSED, SIGNAL_ATP, SIGNAL_CBTC] as const;
/** each level's key in the messages (`signal.<key>`, `signal.<key>Tip`) */
export const SIGNAL_KEYS = ["open", "closed", "atp", "cbtc"] as const;
/** upkeep a day per km over ground track's, by level: `SIGNAL_UPKEEP_SHARE` in sim/src/track/params.rs */
export const SIGNAL_UPKEEP_SHARE = [0, 0, 0.15, 0.4];

export const signalOf = (e: Pick<TrackEdge, "signal">): number => e.signal ?? SIGNAL_CLOSED;

/** The Signalling view's figure per edge id, read by the Profit view's colour ramp (`PROFIT_STOPS`): open
 * block red, ATP light blue, CBTC deep blue; closed block, every piece's by default, the plain track
 * colour (no figure). */
const LENS_FIGURE = [0, 1e30, 1.6, 3];
export function signalLens(w: WorldView | null): Float32Array | null {
  if (!w) return null;
  const n = w.edges.reduce((m, e) => Math.max(m, e.id + 1), 0);
  const out = new Float32Array(n).fill(1e30);
  for (const e of w.edges) if (e.signal !== undefined && !e.crossover) out[e.id] = LENS_FIGURE[e.signal] ?? 1e30;
  return out;
}

/** The inspector's answer (`TrackApi::signal_panel`): per level the most trains an hour each way, the
 * signalling's price on the piece (US$M) and its upkeep a day (US$M); the block's pieces; per level the
 * quote [paid now, added to the blueprint, upkeep change a day] for the piece and for its block. */
export interface SignalPanel {
  level: number;
  tph: number[];
  price: number[];
  upkeep: number[];
  block: number[];
  quote: { piece: number[]; block: number[] }[];
}

export function readSignalPanel(d: Float64Array): SignalPanel | null {
  if (d.length < 14) return null;
  const tph: number[] = [], price: number[] = [], upkeep: number[] = [];
  for (let k = 0; k < 4; k++) { tph.push(d[1 + 3 * k]); price.push(d[2 + 3 * k]); upkeep.push(d[3 + 3 * k]); }
  const n = d[13];
  const block = Array.from(d.subarray(14, 14 + n));
  const quote: SignalPanel["quote"] = [];
  for (let k = 0, i = 14 + n; k < 4; k++, i += 6) quote.push({ piece: Array.from(d.subarray(i, i + 3)), block: Array.from(d.subarray(i + 3, i + 6)) });
  return { level: d[0], tph, price, upkeep, block, quote };
}
