// SPDX-License-Identifier: AGPL-3.0-only
//! The clock worker's costHour, runTail and runLag for the live network. Rates come from TrackApi.
use crate::local_time;
use std::collections::HashMap;
use trainworld_sim::track::{
    params::{
        row_of_day, ECONOMY, HS_RUN_COST_CAR_KM, HS_RUN_COST_TRAIN_KM, PERIOD_HOURS, PERIOD_LEVEL,
        RUN_COST_CAR_KM, RUN_COST_TRAIN_KM,
    },
    wasm::TrackApi,
};

#[derive(Default)]
struct Group {
    shift: f64,
    rates: [f64; 6],
    tail: [[f64; 5]; 2],
}

pub(super) struct Costs {
    pub(super) lag: f64,
    groups: Vec<Group>,
    lines: HashMap<u32, Group>,
}

fn install_prices(api: &mut TrackApi, lines: &[f64]) {
    let mut stops = HashMap::new();
    let nodes = api.nodes_info();
    for r in nodes.chunks_exact(10).filter(|r| r[7] > 0.) {
        stops.insert(r[0] as u32, (r[1], r[2]));
    }
    let mut prices = HashMap::new();
    let mut factors = Vec::new();
    for line in lines.chunks_exact(22).filter(|r| r[3] == 1.) {
        let id = line[0] as u32;
        if api.is_bus(id) {
            for s in api.bus_stops(id).chunks_exact(8) {
                stops.entry(s[0] as u32).or_insert((s[1], s[2]));
            }
        }
        let ids = api.line_stops(id);
        let distance = api.line_stop_s(id, 0);
        let mut sum = 0.;
        let mut total = 0.;
        for (ids, pair) in ids.windows(2).zip(distance.windows(2)) {
            let km = (pair[1] - pair[0]).max(0.) / 1000.;
            for node in ids {
                let price = prices.entry(*node).or_insert_with(|| {
                    stops.get(node).map_or(1., |&(x, y)| {
                        let ll = api.to_lonlat(x, y);
                        local_time::running_price(ll[0], ll[1])
                    })
                });
                sum += *price * km * 0.5;
            }
            total += km;
        }
        if total > 0. {
            factors.extend([id as f64, sum / total]);
        }
    }
    api.set_line_run_factors(&factors);
}

impl Costs {
    pub(super) fn new(api: &mut TrackApi) -> Self {
        Self::with_prices(api, &[])
    }
    pub(super) fn with_prices(api: &mut TrackApi, factors: &[f64]) -> Self {
        let lines = api.lines_info();
        if factors.is_empty() {
            install_prices(api, &lines);
        } else {
            api.set_line_run_factors(factors);
        }
        let mut groups: Vec<Group> = api
            .running_shifts()
            .iter()
            .map(|&shift| Group {
                shift,
                rates: std::array::from_fn(|k| api.running_cost_in(k, shift)),
                ..Group::default()
            })
            .collect();
        let mut line_groups = HashMap::new();
        let mut weight = 0.;
        let mut weighted_time = 0.;
        for r in lines.chunks_exact(22).filter(|r| r[3] == 1.) {
            let id = r[0] as u32;
            let cars = if r[21] > 0. { r[21] } else { r[8] };
            let one_way = r[11] / 2. - r[20];
            let w = r[6] * r[9] * cars;
            if w > 0. && one_way > 0. {
                weight += w;
                weighted_time += w * one_way;
            }
            let Some(group) = groups.iter_mut().find(|g| g.shift == api.line_shift(id)) else {
                continue;
            };
            let (train, car) = if api.line_fast(id) {
                (HS_RUN_COST_TRAIN_KM, HS_RUN_COST_CAR_KM)
            } else {
                (RUN_COST_TRAIN_KM, RUN_COST_CAR_KM)
            };
            let ways = if api.line_one_way(id) { 1. } else { 2. };
            let per_tph = ways * r[9] / 1000. * (train + car * cars) * ECONOMY / 1e6
                * api.line_run_factor(id);
            let mut line = Group {
                shift: api.line_shift(id),
                rates: std::array::from_fn(|k| {
                    f64::from(api.line_tph_on(id, (k / 3) as u32, (k % 3) as u32)) * per_tph
                }),
                ..Default::default()
            };
            let lead = (one_way / 2.).clamp(0., 7200.);
            for row in 0..2 {
                for (p, &(_, end)) in PERIOD_HOURS.iter().enumerate() {
                    let next = PERIOD_HOURS
                        .iter()
                        .position(|&(start, _)| start == end % 24.)
                        .unwrap();
                    if api.line_tph_on(id, row, PERIOD_LEVEL[next] as u32) == 0. {
                        let tph = f64::from(api.line_tph_on(id, row, PERIOD_LEVEL[p] as u32));
                        let cost = tph * per_tph * lead / 3600.;
                        group.tail[row as usize][p] += cost;
                        line.tail[row as usize][p] = cost;
                    }
                }
            }
            line_groups.insert(id, line);
        }
        Self {
            lines: line_groups,
            lag: if weight > 0. {
                (weighted_time / weight).min(7200.)
            } else {
                0.
            },
            groups,
        }
    }

    pub(super) fn line_hour(&self, id: u32, start: f64, country: &str) -> f64 {
        self.lines.get(&id).map_or(0., |g| {
            (0..6)
                .map(|j| {
                    let t = start + g.shift + self.lag * (0.5 - (j as f64 + 0.5) / 6.);
                    g.between(t, t + 3600., country)
                })
                .sum::<f64>()
                / 6.
        })
    }
    pub(super) fn per_hour(&self, start: f64) -> f64 {
        self.groups
            .iter()
            .map(|g| {
                (0..6)
                    .map(|sample| {
                        let begin = start + g.shift + self.lag * (0.5 - (sample as f64 + 0.5) / 6.);
                        g.between(begin, begin + 3600., "")
                    })
                    .sum::<f64>()
                    / 6.
            })
            .sum()
    }
}

impl Group {
    fn between(&self, mut from: f64, to: f64, country: &str) -> f64 {
        let mut sum = 0.;
        while from < to {
            let day = (from / 86400.).floor();
            let hour = (from - day * 86400.) / 3600.;
            let p = PERIOD_HOURS
                .iter()
                .position(|&(a, b)| hour >= a && hour < b)
                .unwrap();
            let row = if country.is_empty() {
                row_of_day(day as i64)
            } else {
                crate::calendar::kind_row(country, day as i64).1
            };
            let (a, b) = PERIOD_HOURS[p];
            let end = to.min(day * 86400. + b * 3600.);
            let rate = self.rates[row * 3 + PERIOD_LEVEL[p]] + self.tail[row][p] / (b - a);
            sum += rate * (end - from) / 3600.;
            from = end;
        }
        sum
    }
}
