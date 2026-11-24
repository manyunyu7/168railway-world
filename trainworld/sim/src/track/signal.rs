//! Signalling per edge (`Network::edge_signal`): four levels the player picks per piece of track.
//!
//! - Open block (`SIGNAL_OPEN`, mechanical signals): one train between two stations or junctions
//!   at a time, so a section's spacing is at least a train's run through that block plus
//!   `OPEN_BLOCK_MARGIN_S`; no faster than `OPEN_BLOCK_KMH` (`Network::v_cap`). Free.
//! - Closed block (`SIGNAL_CLOSED`, electric colour lights): the model as it always was. Every
//!   edge has it unless the player chose another, so a network nobody changed runs exactly as before.
//! - ATP and CBTC: a section's spacing, a junction's or crossing's conflict and (CBTC) a platform's
//!   margin shortened by `SIGNAL_*_MULT`, at a price and upkeep per km (`cost::signal_cost`).
//!
//! A section spanning edges of several levels runs at its poorest; a junction at the poorest of
//! the track meeting there (crossovers aside).

use super::net::{path_edge, Network};
use super::params::*;
use super::profile::Phase;
use super::service::Run;

impl Network {
    /// The edge's signalling, `SIGNAL_CLOSED` for no such edge.
    pub fn signal_of(&self, e: u32) -> u8 {
        if self.edge_ok(e) { self.edge_signal[e as usize] } else { SIGNAL_CLOSED }
    }

    /// The signalling a node works at: the poorest of the track meeting there, crossovers aside.
    pub fn node_signal(&self, n: u32) -> u8 {
        let mut sig = None;
        for &c in self.node_ports[n as usize].ends() {
            let e = (c >> 1) as usize;
            if self.edge_crossover[e] {
                continue;
            }
            let s = self.edge_signal[e];
            sig = Some(sig.map_or(s, |x: u8| x.min(s)));
        }
        sig.unwrap_or(SIGNAL_CLOSED)
    }

    /// Where an open block ends: a station, a junction or a track end.
    pub fn block_end(&self, n: u32) -> bool {
        self.node_platform[n as usize] > 0 || self.node_ports[n as usize].n != 2
    }

    /// The edges of the block (petak) `e` lies in, from one `block_end` to the next, in order.
    pub fn signal_block(&self, e: u32) -> Vec<u32> {
        if !self.edge_ok(e) {
            return vec![];
        }
        let mut out = vec![e];
        for which in [0u32, 1] {
            let mut side = vec![];
            let (mut cur, mut node) = (e, self.end_node(e, which));
            while !self.block_end(node) && side.len() < 10_000 {
                let Some(&c) = self.node_ports[node as usize].ends().iter().find(|&&c| c >> 1 != cur) else { break };
                let next = c >> 1;
                if next == e || side.contains(&next) {
                    break;
                }
                side.push(next);
                node = self.end_node(next, 1 - (c & 1));
                cur = next;
            }
            if which == 0 {
                side.reverse();
                side.extend(out);
                out = side;
            } else {
                out.extend(side);
            }
        }
        out
    }

    /// A section's spacing `std` (`section_s + v / SIGNAL_BRAKE`) over `edges` at their poorest
    /// signalling; `std` itself, to the bit, where all are closed or open block.
    pub fn signal_spacing(&self, edges: impl Iterator<Item = u32>, std: f64) -> f64 {
        let mut mult: f64 = 0.0;
        for e in edges {
            mult = mult.max(SIGNAL_SECTION_MULT[self.edge_signal[e as usize] as usize]);
        }
        if mult == 0.0 || mult == 1.0 { std } else { std * mult }
    }

    /// A train's hold on a section of double track over its run's entries `segs` (indices into
    /// `run.segs`): `std` shortened by the signalling, or at least the run through each open block
    /// it touches and `OPEN_BLOCK_MARGIN_S`.
    pub fn section_occ(&self, run: &Run, segs: impl Iterator<Item = usize> + Clone, std: f64) -> f64 {
        if segs.clone().all(|i| self.edge_signal[path_edge(run.segs[i]) as usize] == SIGNAL_CLOSED) {
            return std;
        }
        let mut occ = self.signal_spacing(segs.clone().map(|i| path_edge(run.segs[i])), std);
        let mut done = usize::MAX;
        for i in segs {
            if self.edge_signal[path_edge(run.segs[i]) as usize] != SIGNAL_OPEN || (done != usize::MAX && i <= done) {
                continue;
            }
            let (a, b) = self.run_block(run, i);
            done = b;
            occ = occ.max(run_time(&run.free.phases, run.node_s[a], run.node_s[b + 1]) + OPEN_BLOCK_MARGIN_S);
        }
        occ
    }

    /// The first and last entries of `run` in the block entry `i` lies in.
    pub fn run_block(&self, run: &Run, i: usize) -> (usize, usize) {
        let (mut a, mut b) = (i, i);
        while a > 0 && !self.block_end(run.nodes[a]) {
            a -= 1;
        }
        while b + 1 < run.segs.len() && !self.block_end(run.nodes[b + 1]) {
            b += 1;
        }
        (a, b)
    }

    /// The most trains an hour one direction of double track of `e` takes at signalling `sig` (for the
    /// inspector), with trains at the edge's top speed under that signalling: open block from the
    /// block's run at those speeds (accelerating from and braking to a stand at its ends).
    pub fn signal_tph(&self, e: u32, sig: u8) -> f64 {
        if !self.edge_ok(e) {
            return 0.0;
        }
        let i = e as usize;
        let v = |e: u32| {
            let cap = self.v_kind_cap(e);
            let cap = match self.edge_limit[e as usize] { 0 => cap, lim => cap.min(lim as f64 / 3.6) };
            if sig == SIGNAL_OPEN { cap.min(OPEN_BLOCK_KMH / 3.6) } else { cap }
        };
        let std = self.edge_kind[i].spec().section_s + v(e) / SIGNAL_BRAKE;
        let h = if sig == SIGNAL_OPEN {
            let block = self.signal_block(e);
            let top = block.iter().map(|&b| v(b)).fold(f64::INFINITY, f64::min).max(1.0);
            let len: f64 = block.iter().map(|&b| self.edge_len[b as usize]).sum();
            let t = len / top + top / (2.0 * ACCEL_LOW) + top / (2.0 * BRAKE);
            std.max(t + OPEN_BLOCK_MARGIN_S)
        } else {
            std * SIGNAL_SECTION_MULT[(sig as usize).min(SIGNAL_LEVELS - 1)]
        };
        3600.0 / h
    }
}

/// Seconds a run takes from offset `s0` to `s1` on its free profile, leaving `s0` and arriving at
/// `s1`: its stands between (at stops it makes inside, at `s1`) left out.
pub fn run_time(ph: &[Phase], s0: f64, s1: f64) -> f64 {
    let mut t = super::profile::time_at(ph, s1) - super::profile::time_at(ph, s0);
    for k in 0..ph.len().saturating_sub(1) {
        let p = &ph[k];
        if p.v == 0.0 && p.a == 0.0 && p.s > s0 && p.s <= s1 {
            t -= ph[k + 1].t - p.t;
        }
    }
    t.max(0.0)
}
