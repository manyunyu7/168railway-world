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

include!(concat!(env!("OUT_DIR"), "/places.rs"));
pub(super) type Regions = BTreeMap<String, f64>;
fn add(map: &mut Regions, id: &str, value: f64) {
    *map.entry(id.to_owned()).or_default() += value;
}
fn normalized(map: &Regions) -> Regions {
    let total: f64 = map.values().sum();
    map.iter()
        .filter(|(_, v)| **v > 0.)
        .map(|(k, v)| (k.clone(), v / total))
        .collect()
}
#[derive(Default)]
struct Period {
    board: Vec<f32>,
    alight: Vec<f32>,
    arrive: Vec<f32>,
    km: f64,
    paid_km: f64,
    scaled_board: f64,
    regional_board: Regions,
    regional_km: Regions,
    extra: f64,
    base_extra: f64,
}
#[derive(Default)]
struct Line {
    days: [[Period; 5]; 7],
    segments: Vec<f64>,
    /// `segments` of each map filter purpose (`trip_cells::PURPOSES` order): the Traffic view's
    /// widths under the purpose filter (web `lineLoadsFor`, `purposeDemand`)
    purpose_segments: Vec<Vec<f64>>,
    incoming: Vec<f64>,
    zones: Vec<&'static str>,
    scales: Vec<[f64; 2]>,
    run: Regions,
    own: f64,
    rate_multiple: f64,
    base: f64,
    rate: f64,
}

#[derive(Default)]
pub(super) struct Forecast {
    lines: BTreeMap<u32, Line>,
    trips: [f64; 7],
    city_ids: Vec<String>,
    regional_trips: [Regions; 6],
    transfers: BTreeMap<([u32; 3], [u32; 3]), f64>,
}

fn accumulate(target: &mut [f32], src: &[f32]) {
    for (x, v) in target.iter_mut().zip(src) {
        *x += v;
    }
}

impl Forecast {
    pub(super) fn set_layout(&mut self, cities: &[String]) {
        self.city_ids = cities.to_vec();
    }
    pub(super) fn line_zones(
        &mut self,
        id: u32,
        zones: Vec<&'static str>,
        fares: &crate::fares::Fares,
        prices: &mut BTreeMap<&'static str, [f64; 2]>,
    ) {
        let line = self.lines.entry(id).or_default();
        line.scales = zones
            .iter()
            .map(|zone| {
                let country = PLACES.iter().find(|r| r.0 == *zone).map_or("", |r| r.2);
                *prices.entry(country).or_insert_with(|| {
                    let local = crate::fares::Fares::read(&fares.raw, country);
                    ["base", "rate"].map(|k| {
                        let base = fares.market[k].as_f64().unwrap_or(0.);
                        if base > 0. {
                            local.local[k].as_f64().unwrap_or(0.) / base
                        } else {
                            1.
                        }
                    })
                })
            })
            .collect();
        line.zones = zones;
    }

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
        for (city, (s, e)) in cities.iter().enumerate() {
            let totals = e.kind_totals(q);
            for d in 0..6 {
                let mut rail = s.summary(q)[2] * f64::from(days::load_factor(d, WORK, q));
                for (k, t) in totals.chunks_exact(3).enumerate() {
                    rail += t[1] * f64::from(days::load_factor(d, days::everyday(k), q));
                }
                add(&mut self.regional_trips[d], &self.city_ids[city], rail);
            }
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
            let nplaces = PLACES.iter().map(|v| v.1).max().unwrap() + 1;
            let tri = |i: usize| i * (2 * nplaces - i + 1) / 2;
            let by_place: BTreeMap<_, Vec<_>> =
                PLACES.iter().fold(BTreeMap::new(), |mut map, &(id, k, _)| {
                    map.entry(k).or_default().push(id);
                    map
                });
            for r in m.corridor_rail(q).chunks_exact(3) {
                let key = r[0] as usize;
                let mut lo = 0;
                let mut hi = nplaces;
                while lo + 1 < hi {
                    let mid = (lo + hi) / 2;
                    if tri(mid) <= key {
                        lo = mid
                    } else {
                        hi = mid
                    }
                }
                let dest = lo + key - tri(lo);
                for place in [lo, dest] {
                    if let Some(zones) = by_place.get(&place) {
                        for d in 0..6 {
                            let count = (r[1] * f64::from(days::load_factor(d, LONGER, q))
                                + r[2] * f64::from(days::load_factor(d, WORK, q)))
                                / (2 * zones.len()) as f64;
                            for z in zones {
                                add(&mut self.regional_trips[d], z, count);
                            }
                        }
                    }
                }
            }
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
        let mut incoming = vec![0.; n];
        let mut append = |rows: Vec<f32>, factor: f64| {
            for (v, x) in incoming.iter_mut().zip(rows) {
                *v += f64::from(x) * factor;
            }
        };
        for (s, e) in cities {
            append(
                s.transfers_in(q),
                f64::from(days::load_factor(days::AVERAGE, WORK, q)),
            );
            let mut total = 0.;
            let mut weighted = 0.;
            for (k, t) in e.kind_totals(q).chunks_exact(3).enumerate() {
                total += t[1];
                weighted +=
                    t[1] * f64::from(days::load_factor(days::AVERAGE, days::everyday(k), q));
            }
            append(
                e.transfers_in(q),
                if total > 0. { weighted / total } else { 0. },
            );
        }
        if let Some(m) = market {
            append(
                m.transfers_in(q),
                f64::from(days::load_factor(days::AVERAGE, LONGER, q)),
            );
        }
        for d in 0..=days::AVERAGE {
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
                if d == days::AVERAGE {
                    line.segments.resize(end - offset, 0.);
                    line.incoming.resize(end - offset, 0.);
                    for j in offset..end {
                        line.segments[j - offset] += f64::from(loads[j]);
                        line.incoming[j - offset] += incoming[j];
                    }
                    // each filter purpose's riders, its model purposes' parts times their factors
                    // (school counts university, T-192); the longer trips have no filter
                    line.purpose_segments
                        .resize(crate::trip_cells::PURPOSES.len(), vec![]);
                    for (out, (_, kinds)) in line
                        .purpose_segments
                        .iter_mut()
                        .zip(crate::trip_cells::PURPOSES)
                    {
                        out.resize(end - offset, 0.);
                        let members: Vec<usize> = if kinds.is_empty() {
                            vec![WORK]
                        } else {
                            kinds.iter().map(|&k| days::everyday(k as usize)).collect()
                        };
                        for &p in &members {
                            let f = f64::from(days::load_factor(d, p, q));
                            for j in offset..end {
                                out[j - offset] += f64::from(parts[p][j]) * f;
                            }
                        }
                    }
                }
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
                    let km = f64::from(loads[j]) * f64::from(hop[j]);
                    period.km += km;
                    let k = j - offset;
                    let count = stops as usize;
                    let at = if k < count { k } else { 2 * count - 1 - k };
                    let next = if k < count {
                        (at + 1).min(count - 1)
                    } else {
                        at.saturating_sub(1)
                    };
                    let zone = line.zones.get(at).copied().unwrap_or("");
                    let [base_scale, rate_scale] = line.scales.get(at).copied().unwrap_or([1.; 2]);
                    period.scaled_board += f64::from(loads[n + j]) * base_scale;
                    period.paid_km += km * rate_scale;
                    let other = line.zones.get(next).copied().unwrap_or("");
                    add(&mut period.regional_board, zone, loads[n + j].into());
                    add(&mut period.regional_km, zone, km / 2.);
                    add(&mut period.regional_km, other, km / 2.);
                    if d == 0 && q == 0 && k < count {
                        add(&mut line.run, zone, f64::from(hop[j]) / 2.);
                        add(&mut line.run, other, f64::from(hop[j]) / 2.);
                    }
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
    pub(super) fn run_prices(&self) -> Vec<f64> {
        self.lines
            .iter()
            .filter_map(|(&id, l)| {
                let sum = l.run.values().sum::<f64>();
                (sum > 0.).then(|| {
                    [
                        f64::from(id),
                        l.run
                            .iter()
                            .map(|(z, v)| v * crate::local_time::zone_price(z))
                            .sum::<f64>()
                            / sum,
                    ]
                })
            })
            .flatten()
            .collect()
    }
    pub(super) fn map_lines(&self) -> BTreeMap<u32, crate::network_layers::Traffic> {
        let mut total_board = 0.;
        let mut pool = 0.;
        let mut extra = 0.;
        let mut caps = Vec::new();
        let mut kms = Vec::new();
        let mut lines = BTreeMap::new();
        for (&id, l) in &self.lines {
            let ps = &l.days[days::AVERAGE];
            let pk = ps.iter().map(|p| p.km).sum::<f64>();
            let mut board = vec![0.; l.incoming.len()];
            for p in ps {
                for (t, &v) in board.iter_mut().zip(&p.board) {
                    *t += f64::from(v);
                }
            }
            let b = board.iter().sum::<f64>();
            total_board += b;
            let scaled = ps.iter().map(|p| p.scaled_board).sum::<f64>();
            let contributing = if pk > 0. { scaled } else { 0. };
            pool += contributing;
            caps.push(contributing * l.own);
            kms.push(if b > 0. { pk * scaled / b * l.own } else { 0. });
            if l.base > 0. {
                extra += ps.iter().map(|p| p.base_extra).sum::<f64>() / l.base;
            }
            lines.insert(
                id,
                crate::network_layers::Traffic {
                    board,
                    rate_scales: l.scales.iter().map(|s| s[1]).collect(),
                    segments: l.segments.clone(),
                    purposes: l.purpose_segments.clone(),
                    incoming: l.incoming.clone(),
                    fares: (ps.iter().map(|p| p.paid_km).sum::<f64>() * l.rate_multiple * l.rate
                        + ps.iter().map(|p| p.extra).sum::<f64>())
                        * ECONOMY
                        / 1e6,
                },
            );
        }
        if total_board > 0. {
            pool *= (self.trips[days::AVERAGE] / total_board).min(1.);
        }
        if self.lines.values().any(|l| l.own != 1.) {
            pool = (pool + extra).max(0.);
        }
        for ((line, l), sh) in lines
            .values_mut()
            .zip(self.lines.values())
            .zip(share_base(pool, &caps, &kms))
        {
            line.fares += sh * l.base * ECONOMY / 1e6;
        }
        lines
    }
    pub(super) fn income(&self) -> BTreeMap<u32, LineIncome> {
        let mut result = BTreeMap::<u32, LineIncome>::new();
        for d in 0..6 {
            let mut region_board = Regions::new();
            for line in self.lines.values() {
                for p in &line.days[d] {
                    for (z, v) in &p.regional_board {
                        add(&mut region_board, z, *v);
                    }
                }
            }
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
                let scaled = l.days[d].iter().map(|p| p.scaled_board).sum::<f64>();
                let contributing = if pk > 0. { scaled } else { 0. };
                pool += contributing * per;
                caps.push(contributing * l.own);
                kms.push(if b > 0. { pk * scaled / b * l.own } else { 0. });
            }
            if self.lines.values().any(|l| l.own != 1.) {
                pool = (pool + extra).max(0.);
            }
            let shares = share_base(pool, &caps, &kms);
            for (i, (&id, l)) in self.lines.iter().enumerate() {
                let total: f64 = l.days[d].iter().map(|p| p.km).sum();
                let out = result.entry(id).or_default();
                if d == 0 {
                    let mut board = Regions::new();
                    let mut km = Regions::new();
                    for p in &l.days[d] {
                        for (z, v) in &p.regional_board {
                            add(&mut board, z, *v);
                        }
                        for (z, v) in &p.regional_km {
                            add(&mut km, z, *v);
                        }
                    }
                    out.run_shares = normalized(&l.run);
                    out.ride_shares = normalized(&board);
                    let base = l.base * shares[i];
                    let distance = (l.rate_multiple
                        * l.rate
                        * l.days[d].iter().map(|p| p.paid_km).sum::<f64>()
                        + l.days[d].iter().map(|p| p.extra).sum::<f64>())
                    .max(0.);
                    let mut fare = Regions::new();
                    for (z, v) in &out.ride_shares {
                        add(&mut fare, z, v * base);
                    }
                    for (z, v) in normalized(&km) {
                        add(&mut fare, &z, v * distance);
                    }
                    out.fare_shares = normalized(&fare);
                    out.country = PLACES
                        .iter()
                        .find(|x| Some(&x.0) == l.zones.first())
                        .map_or("", |x| x.2);
                }
                for (q, p) in l.days[d].iter().enumerate() {
                    let share = if total > 0. { p.km / total } else { 0. };
                    let (a, b) = PERIOD_HOURS[q];
                    let hours = b - a;
                    out.fares[d][q] = (l.base * shares[i] * share
                        + l.rate_multiple * l.rate * p.paid_km
                        + p.extra)
                        * ECONOMY
                        / (hours * 1e6);
                    out.rides[d][q] = boarded[i] * per * share / hours;
                    let all: f64 = p.regional_board.values().sum();
                    if all > 0. {
                        for (z, &b) in &p.regional_board {
                            let region = region_board[z];
                            if b <= 0. || region <= 0. {
                                continue;
                            }
                            let trips =
                                self.regional_trips[d].get(z).copied().unwrap_or(0.) / region;
                            out.points.entry(z.clone()).or_insert([[0.; 5]; 6])[d][q] =
                                b * (20. * trips + p.km / all) / hours;
                        }
                    }
                }
            }
        }
        result
    }
}

#[derive(Default, serde::Serialize)]
pub(super) struct LineIncome {
    pub country: &'static str,
    pub fare_shares: Regions,
    pub ride_shares: Regions,
    pub run_shares: Regions,
    pub points: BTreeMap<String, [[f64; 5]; 6]>,
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
