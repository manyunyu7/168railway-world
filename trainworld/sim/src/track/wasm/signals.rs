//! Signalling per piece of track (`signal.rs`): open block, closed block (every piece's unless the
//! player chose another), ATP and CBTC. Changing it is one undoable edit; on constructed track the
//! dearer signalling is paid at once (the rise in the built price), on a blueprint with its Construct.

use super::*;

#[wasm_bindgen]
impl TrackApi {
    /// The pieces whose signalling is other than closed block, as pairs: edge, level (`SIGNAL_*`:
    /// 0 open block, 2 ATP, 3 CBTC).
    pub fn edge_signals(&self) -> Vec<u32> {
        let n = &self.w.net;
        (0..n.edge_count() as u32).filter(|&e| n.edge_ok(e) && n.edge_signal[e as usize] != SIGNAL_CLOSED).flat_map(|e| [e, n.edge_signal[e as usize] as u32]).collect()
    }

    /// A piece's signalling: 0 open block, 1 closed block, 2 ATP, 3 CBTC (1 for no such piece).
    pub fn edge_signal(&self, e: u32) -> u8 {
        self.w.net.signal_of(e)
    }

    /// The pieces of the block (petak) `e` lies in: from the station, junction or track end before
    /// it to the one after it, in order. Empty for no such piece.
    pub fn signal_block(&self, e: u32) -> Vec<u32> {
        self.w.net.signal_block(e)
    }

    /// For the inspector, about piece `e`: its level, then per level 0 to 3 the most trains an hour
    /// one direction of its track takes (`Network::signal_tph`), the signalling's price on it, US$M,
    /// and its upkeep a game day, US$M. Empty for no such piece.
    pub fn signal_info(&self, e: u32) -> Vec<f64> {
        let n = &self.w.net;
        if !n.edge_ok(e) {
            return vec![];
        }
        let i = e as usize;
        let mut out = vec![n.edge_signal[i] as f64];
        for sig in 0..SIGNAL_LEVELS as u8 {
            out.push(n.signal_tph(e, sig));
            out.push(crate::track::cost::signal_cost(n.edge_len[i], n.edge_tracks[i], n.edge_kind[i], sig));
            out.push(n.edge_len[i] / 1000.0 * crate::track::cost::signal_upkeep_km(sig) * crate::track::cost::track_factor(n.edge_tracks[i], n.edge_kind[i]) * ECONOMY / 1e6);
        }
        out
    }

    /// What setting `ids` to `level` costs: [paid now on constructed track US$M, added to the
    /// blueprint's price US$M, change in upkeep a game day US$M]. Crossovers are left out.
    pub fn signal_quote(&self, ids: &[u32], level: u8) -> Vec<f64> {
        let n = &self.w.net;
        let (mut now, mut plan, mut upkeep) = (0.0, 0.0, 0.0);
        let level = level.min(SIGNAL_CBTC);
        let mut seen: Vec<u32> = vec![];
        for &e in ids {
            if !n.edge_ok(e) || n.edge_crossover[e as usize] || seen.contains(&e) {
                continue;
            }
            seen.push(e);
            let i = e as usize;
            let price = |s: u8| crate::track::cost::signal_cost(n.edge_len[i], n.edge_tracks[i], n.edge_kind[i], s);
            let delta = price(level) - price(n.edge_signal[i]);
            if n.edge_built[i] {
                now += delta.max(0.0);
                let up = |s: u8| n.edge_len[i] / 1000.0 * crate::track::cost::signal_upkeep_km(s) * crate::track::cost::track_factor(n.edge_tracks[i], n.edge_kind[i]) * ECONOMY / 1e6;
                upkeep += up(level) - up(n.edge_signal[i]);
            } else {
                plan += delta;
            }
        }
        vec![now, plan, upkeep]
    }

    /// Set the signalling of these pieces to `level` (0 open block, 1 closed block, 2 ATP, 3 CBTC).
    /// One undoable edit; crossovers keep theirs. Constructed track pays the rise in its price at
    /// once (refused if the cash is short). Returns 1 when nothing changed.
    pub fn set_edges_signal(&mut self, ids: &[u32], level: u8) -> u32 {
        if level > SIGNAL_CBTC {
            return 1;
        }
        let mut ops = vec![];
        for &e in ids {
            if !self.w.net.edge_ok(e) {
                return self.refuse(Issue::NoSuchEdge { edge: e });
            }
            let i = e as usize;
            if self.w.net.edge_crossover[i] || self.w.net.edge_signal[i] == level || ops.iter().any(|o| matches!(o, Op::Signal { id, .. } if *id == e)) {
                continue;
            }
            ops.push(Op::Signal { id: e, signal: level });
        }
        if ops.is_empty() {
            self.money_short = None;
            self.issues = vec![];
            return 1;
        }
        self.run(Op::Batch(ops))
    }
}
