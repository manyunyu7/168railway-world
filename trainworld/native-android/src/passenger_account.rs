//! Combine the assigned populations by purpose before applying the day's load factors.
//! Periods use the same route-node order as TrackApi's boarding account.
use serde_json::Value;
use std::collections::BTreeMap;
use trainworld_sim::{
    demand::{
        api::DemandApi,
        days::{self, LONGER, PURPOSES, WORK},
        everyday::EverydayApi,
        intercity::IntercityApi,
    },
    track::params::{ECONOMY, PEAK_HOUR_FACTOR, PERIOD_HOURS},
};

#[derive(Default)]
struct Period {
    board: Vec<f32>,
    alight: Vec<f32>,
    arrive: Vec<f32>,
    km: f64,
    extra: f64,
    base_extra: f64,
}
#[derive(Default)]
struct Line {
    days: [[Period; 5]; 6],
    own: f64,
    rate_multiple: f64,
    base: f64,
    rate: f64,
}

#[derive(Default)]
pub(super) struct Forecast {
    lines: BTreeMap<u32, Line>,
    trips: [f64; 6],
    transfers: BTreeMap<([u32; 3], [u32; 3]), f64>,
}

fn accumulate(target: &mut [f32], src: &[f32]) {
    for (x, v) in target.iter_mut().zip(src) {
        *x += v;
    }
}

impl Forecast {
    pub(super) fn add_period(
        &mut self,
        q: usize,
        cities: &[(DemandApi, EverydayApi)],
        market: Option<&IntercityApi>,
        arrivals: &[f32],
        rounds: &[f32],
        ids: &[u32],
        counts: &[u32],
        hop: &[f32],
        own: &[f32],
        mul: &[f32],
        fares: &Value,
    ) -> Result<(), String> {
        let n = hop.len();
        let mut parts = vec![vec![0.; 3 * n]; PURPOSES];
        let mut trips = [0.; PURPOSES];
        let mut fare_x = vec![0.; n];
        let mut base_x = vec![0.; n];
        let mut raw = vec![0.; 3 * n];
        let mut lean = Vec::new();
        for (s, e) in cities {
            let work = [s.seg(q), s.board(q), s.alight(q)].concat();
            let everyday = [e.seg(q), e.board(q), e.alight(q)].concat();
            if work.len() != raw.len() || everyday.len() != raw.len() {
                return Err("Jumlah simpul demand tidak cocok".into());
            }
            accumulate(&mut raw, &work);
            accumulate(&mut raw, &everyday);
            accumulate(&mut parts[WORK], &work);
            trips[WORK] += s.summary(q)[2];
            if n > 0 {
                for (k, loads) in e.part_loads(q).chunks_exact(3 * n).enumerate() {
                    accumulate(&mut parts[days::everyday(k)], loads);
                }
            }
            for (k, v) in e.kind_totals(q).chunks_exact(3).enumerate() {
                trips[days::everyday(k)] += v[1];
            }
            accumulate(&mut fare_x, &s.fare_extra(q));
            accumulate(&mut fare_x, &e.fare_extra(q));
            accumulate(&mut base_x, &s.base_extra(q));
            accumulate(&mut base_x, &e.base_extra(q));
        }
        if let Some(m) = market {
            let regional = [m.seg(q), m.board(q), m.alight(q)].concat();
            accumulate(&mut raw, &regional);
            accumulate(&mut parts[LONGER], &regional);
            let mut distribute = |purpose: usize, loads: &[f32]| {
                accumulate(&mut parts[purpose], loads);
                for (left, v) in parts[LONGER].iter_mut().zip(loads) {
                    *left -= v;
                }
            };
            distribute(WORK, &m.commute_loads(q));
            if n > 0 {
                for (k, loads) in m.everyday_loads(q).chunks_exact(3 * n).enumerate() {
                    distribute(days::everyday(k), loads);
                }
            }
            lean = m.lean_loads(q);
            let totals = m.part_summary(q);
            if totals.len() >= 8 {
                trips[LONGER] += totals[1];
                trips[WORK] += totals[3];
                for (k, v) in totals[8..].chunks_exact(4).enumerate() {
                    trips[days::everyday(k)] += v[1];
                }
            }
            accumulate(&mut fare_x, &m.fare_extra(q));
            accumulate(&mut base_x, &m.base_extra(q));
        }
        let mut stops = Vec::new();
        for (&id, &count) in ids.iter().zip(counts) {
            for run in 0..2 {
                for stop in 0..count {
                    stops.push([id, run, stop]);
                }
            }
        }
        let selected = vec![1; n];
        let mut add_pairs = |triples: Vec<f32>| {
            for row in triples.chunks_exact(3) {
                let (a, b) = (stops[row[0] as usize], stops[row[1] as usize]);
                *self.transfers.entry((a, b)).or_default() += f64::from(row[2]);
            }
        };
        for (s, e) in cities {
            add_pairs(s.transfers_day(&selected, days::AVERAGE as u32));
            add_pairs(e.transfers_day(&selected, days::AVERAGE as u32));
        }
        if let Some(m) = market {
            add_pairs(m.transfers_day(&selected, days::AVERAGE as u32));
        }
        for d in 0..6 {
            let mut loads = vec![0.; 3 * n];
            for (p, vals) in parts.iter().enumerate() {
                let f = days::load_factor(d, p, q);
                self.trips[d] += trips[p] * f64::from(f);
                let sign = days::lean_sign(d);
                for (j, (&v, out)) in vals.iter().zip(&mut loads).enumerate() {
                    let x = if p == LONGER && sign != 0. {
                        (v + sign * lean.get(j).copied().unwrap_or(0.)).max(0.)
                    } else {
                        v
                    };
                    *out += x * f;
                }
            }
            let mut offset = 0;
            for (&id, &stops) in ids.iter().zip(counts) {
                let end = offset + 2 * stops as usize;
                let line = self.lines.entry(id).or_default();
                line.own = own[offset].into();
                line.rate_multiple = mul[offset].into();
                line.base = fares["base"].as_f64().unwrap_or(0.);
                line.rate = fares["rate"].as_f64().unwrap_or(0.);
                let period = &mut line.days[d][q];
                period.board.extend_from_slice(&loads[n + offset..n + end]);
                period
                    .alight
                    .extend_from_slice(&loads[2 * n + offset..2 * n + end]);
                for j in offset..end {
                    let scale = if raw[j] > 0. {
                        f64::from(loads[j]) / f64::from(raw[j])
                    } else {
                        0.
                    };
                    period.km += f64::from(loads[j]) * f64::from(hop[j]);
                    period.extra += scale * f64::from(fare_x[j]);
                    period.base_extra += scale * f64::from(base_x[j]);
                    let a = if raw[n + j] < 0.999 * rounds[j] {
                        arrivals[j].max(raw[n + j])
                    } else {
                        raw[n + j]
                    };
                    period.arrive.push(if raw[n + j] > 0. {
                        a * loads[n + j] / raw[n + j]
                    } else {
                        a
                    });
                }
                offset = end;
            }
        }
        Ok(())
    }

    pub(super) fn riders(&self, d: usize) -> (Vec<f32>, Vec<f32>) {
        let mut riders = PEAK_HOUR_FACTOR.to_vec();
        let mut arrivals = Vec::new();
        for (&id, line) in &self.lines {
            let h = [id as f32, (line.days[d][0].board.len() / 2) as f32];
            riders.extend(h);
            arrivals.extend(h);
            for p in &line.days[d] {
                riders.extend(&p.board);
                riders.extend(&p.alight);
                arrivals.extend(&p.arrive);
            }
        }
        (riders, arrivals)
    }
    pub(super) fn transfers(&self) -> Vec<f32> {
        let mut out = Vec::with_capacity(self.transfers.len() * 7);
        for ((a, b), riders) in &self.transfers {
            out.extend(a.map(|x| x as f32));
            out.extend(b.map(|x| x as f32));
            out.push(*riders as f32);
        }
        out
    }
    pub(super) fn income(&self) -> BTreeMap<u32, LineIncome> {
        let mut result = BTreeMap::<u32, LineIncome>::new();
        for d in 0..6 {
            let boarded: Vec<f64> = self
                .lines
                .values()
                .map(|l| {
                    l.days[d]
                        .iter()
                        .flat_map(|p| &p.board)
                        .map(|&x| f64::from(x))
                        .sum()
                })
                .collect();
            let all = boarded.iter().sum::<f64>();
            let per = if all > 0. {
                (self.trips[d] / all).min(1.)
            } else {
                1.
            };
            let mut pool = 0.;
            let mut extra = 0.;
            let mut caps = Vec::new();
            let mut kms = Vec::new();
            for (l, &b) in self.lines.values().zip(&boarded) {
                let pk: f64 = l.days[d].iter().map(|p| p.km).sum();
                if l.base > 0. {
                    extra += l.days[d].iter().map(|p| p.base_extra).sum::<f64>() / l.base;
                }
                let contributing = if pk > 0. { b } else { 0. };
                pool += contributing * per;
                caps.push(contributing * l.own);
                kms.push(pk * l.own);
            }
            if self.lines.values().any(|l| l.own != 1.) {
                pool = (pool + extra).max(0.);
            }
            let shares = share_base(pool, &caps, &kms);
            for (i, (&id, l)) in self.lines.iter().enumerate() {
                let total: f64 = l.days[d].iter().map(|p| p.km).sum();
                let out = result.entry(id).or_default();
                for (q, p) in l.days[d].iter().enumerate() {
                    let share = if total > 0. { p.km / total } else { 0. };
                    let (a, b) = PERIOD_HOURS[q];
                    let hours = b - a;
                    out.fares[d][q] =
                        (l.base * shares[i] * share + l.rate_multiple * l.rate * p.km + p.extra)
                            * ECONOMY
                            / (hours * 1e6);
                    out.rides[d][q] = boarded[i] * per * share / hours;
                }
            }
        }
        result
    }
}

#[derive(Default, serde::Serialize)]
pub(super) struct LineIncome {
    pub fares: [[f64; 5]; 6],
    pub rides: [[f64; 5]; 6],
}

fn share_base(mut left: f64, caps: &[f64], kms: &[f64]) -> Vec<f64> {
    let b: f64 = caps.iter().sum();
    let k: f64 = kms.iter().sum();
    let weights: Vec<_> = caps
        .iter()
        .zip(kms)
        .map(|(cap, km)| {
            let boarding = if b > 0. { cap / b } else { 0. };
            if k > 0. {
                (boarding + km / k) / 2.
            } else {
                boarding
            }
        })
        .collect();
    let mut paid = vec![0.; caps.len()];
    let mut held = vec![false; caps.len()];
    while left > 0. {
        let free: f64 = weights
            .iter()
            .zip(&held)
            .filter(|(_, h)| !**h)
            .map(|(v, _)| v)
            .sum();
        if free <= 0. {
            break;
        }
        let c = left / free;
        let mut capped = false;
        for i in 0..caps.len() {
            if !held[i] && c * weights[i] > caps[i] {
                held[i] = true;
                paid[i] = caps[i];
                left -= caps[i];
                capped = true;
            }
        }
        if !capped {
            for i in 0..caps.len() {
                if !held[i] {
                    paid[i] = c * weights[i];
                }
            }
            break;
        }
    }
    paid
}
