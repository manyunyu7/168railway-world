//! Timing of the passenger account, from Studio's moneyTiming.ts and clock.worker.ts.
use crate::operating::Costs;
use crate::passenger_account::{Forecast, LineIncome, Regions};
use serde_json::Value;
use std::collections::BTreeMap;
use trainworld_sim::track::{
    boarding::{shape_cum, shape_strength, SHAPE},
    params::{day_kind_at, PEAK_HOUR_FACTOR, PERIOD_HOURS},
    wasm::TrackApi,
};

pub(super) struct Account {
    lines: BTreeMap<u32, (f64, LineIncome)>,
    hour: i64,
    shares: Vec<f32>,
    previous: f64,
    lag: f64,
    totals: [f64; 4],
    regions: BTreeMap<String, [f64; 4]>,
    rates: BTreeMap<u32, [f64; 2]>,
    upkeep: Regions,
    calendar_day: i64,
}
fn between(table: &[[f64; 5]; 6], mut start: f64, end: f64, country: &str) -> f64 {
    let mut total = 0.;
    while start < end {
        let midnight = (start / 86400.).floor() * 86400.;
        let hour = (start - midnight) / 3600.;
        let Some(p) = PERIOD_HOURS
            .iter()
            .position(|&(a, b)| a <= hour && hour < b)
        else {
            break;
        };
        let (a, b) = PERIOD_HOURS[p];
        let base = midnight + a * 3600.;
        let len = (b - a) * 3600.;
        let stop = end.min(base + len);
        let c = shape_strength(SHAPE[p], b - a, PEAK_HOUR_FACTOR[p].into());
        let share = shape_cum(SHAPE[p], c, (stop - base) / len)
            - shape_cum(SHAPE[p], c, (start - base) / len);
        total += table[crate::calendar::kind_row(country, (start / 86400.).floor() as i64).0][p]
            * (b - a)
            * share;
        start = stop;
    }
    total
}
fn fare_hour(table: &[[f64; 5]; 6], start: f64, lag: f64, country: &str) -> f64 {
    if lag <= 0. {
        return between(table, start, start + 3600., country);
    }
    (0..6)
        .map(|j| {
            let a = start - lag * (j as f64 + 0.5) / 6.;
            between(table, a, a + 3600., country)
        })
        .sum::<f64>()
        / 6.
}

fn hour_shares(board: &[f32]) -> Vec<f32> {
    if board.len() < 5 {
        return vec![];
    }
    let (nt, nl, nq, ne) = (
        board[0] as usize,
        board[1] as usize,
        board[2] as usize,
        board[3] as usize,
    );
    let q0 = 5 + 2 * nt + nl;
    let e0 = q0 + 12 * nq;
    let p0 = e0 + 2 * ne;
    let mut bins = vec![0.; 3600];
    let mut total = 0.;
    for q in board[q0..e0].chunks_exact(12) {
        let (first, count) = (q[8] as usize, q[9] as usize);
        let mut p = q[10] as usize;
        let end = p + q[11] as usize;
        for j in first + 1..first + count {
            let i = e0 + 2 * j;
            let (t, prev) = (board[i], board[i - 2]);
            let mut changes = 0.;
            while p < end && board[p0 + 2 * p] <= t {
                if board[p0 + 2 * p] > prev {
                    changes += f64::from(board[p0 + 2 * p + 1]);
                }
                p += 1;
            }
            if !(0. ..3600.).contains(&t) {
                continue;
            }
            let w = (f64::from(board[i - 1])
                + f64::from(q[4]) * (f64::from(t) - f64::from(prev)).max(0.)
                + changes
                - f64::from(board[i + 1]))
            .max(0.);
            bins[t.floor() as usize] += w;
            total += w;
        }
    }
    if total <= 0. {
        return vec![];
    }
    let mut sum = 0.;
    let mut c: Vec<_> = bins
        .into_iter()
        .map(|b| {
            let f = (sum / total) as f32;
            sum += b;
            f
        })
        .collect();
    c.push(1.);
    c
}
impl Account {
    pub(super) fn new(
        forecast: &Forecast,
        api: &mut TrackApi,
        clock: f64,
        costs: &Costs,
        upkeep: Regions,
    ) -> Self {
        for d in 0..6 {
            let (riders, arrivals) = forecast.riders(d);
            api.set_riders(riders, d as u32);
            api.set_arrivals(arrivals, d as u32);
        }
        api.set_transfers(forecast.transfers());
        while api.boarding_step(32) {}
        let lines = forecast
            .income()
            .into_iter()
            .map(|(id, income)| (id, (api.line_shift(id), income)))
            .collect();
        let hour = (clock / 3600.).floor() as i64;
        let mut account = Self {
            lines,
            hour: i64::MIN,
            previous: 0.,
            shares: vec![],
            lag: costs.lag,
            totals: [0.; 4],
            regions: BTreeMap::new(),
            rates: BTreeMap::new(),
            upkeep,
            calendar_day: i64::MIN,
        };
        account.prepare(api, hour, costs);
        account.previous = clock.rem_euclid(3600.) / 3600.;
        account
    }
    fn prepare(&mut self, api: &mut TrackApi, hour: i64, costs: &Costs) {
        if self.hour == hour {
            return;
        }
        self.hour = hour;
        self.previous = 0.;
        let epoch = hour as f64 * 3600.;
        if self.calendar_day != hour.div_euclid(24) {
            self.calendar_day = hour.div_euclid(24);
            let mut specials = Vec::new();
            let mut schedule = Vec::new();
            for (&id, (shift, line)) in &self.lines {
                let day = ((epoch + shift) / 86400.).floor() as i64;
                let mut holidays = Vec::new();
                let mut rows = Vec::new();
                let mut own = false;
                for d in day - 1..=day + 8 {
                    let (k, r) = crate::calendar::kind_row(line.country, d);
                    let week = day_kind_at(d as f64 * 86400.);
                    if k != week {
                        holidays.extend([d as f64, k as f64]);
                    }
                    own |= r != usize::from(week != 0);
                    rows.extend([d as f64, r as f64]);
                }
                if !holidays.is_empty() {
                    specials.extend([id as f64, (holidays.len() / 2) as f64]);
                    specials.extend(holidays);
                }
                if own {
                    schedule.extend([id as f64, 10.]);
                    schedule.extend(rows);
                }
            }
            api.set_line_holidays(specials);
            api.set_line_rows(schedule);
        }
        self.shares = hour_shares(&api.boarding_hour(epoch));
        self.totals = [0.; 4];
        self.regions.clear();
        self.rates.clear();
        for (&id, (shift, money)) in &self.lines {
            let start = epoch + shift;
            let fare = fare_hour(&money.fares, start, self.lag, money.country);
            let rides = between(&money.rides, start, start + 3600., money.country);
            let cost = costs.line_hour(id, epoch, money.country);
            self.rates.insert(id, [fare, cost]);
            for (k, value, shares) in [
                (0, fare, &money.fare_shares),
                (1, rides, &money.ride_shares),
                (2, cost, &money.run_shares),
            ] {
                self.totals[k] += value;
                if shares.is_empty() {
                    self.regions.entry(String::new()).or_default()[k] += value;
                }
                for (z, &f) in shares {
                    self.regions.entry(z.clone()).or_default()[k] += f * value;
                }
            }
            for (z, points) in &money.points {
                let v = fare_hour(points, start, self.lag, money.country);
                self.totals[3] += v;
                self.regions.entry(z.clone()).or_default()[3] += v;
            }
        }
    }
    pub(super) fn settle(
        &mut self,
        api: &mut TrackApi,
        h: &mut Value,
        hour: i64,
        day: i64,
        end: f64,
        dc: f64,
        up: f64,
        costs: &Costs,
    ) -> [f64; 4] {
        use crate::finance::{add, entry};
        self.prepare(api, hour, costs);
        let secs = (end - hour as f64 * 3600.).clamp(0., 3600.);
        let share = self
            .shares
            .get(secs.floor() as usize)
            .map_or(secs / 3600., |&s| s.into());
        let df = (share - self.previous).max(0.);
        self.previous = self.previous.max(share);
        for (id, [fare, cost]) in &self.rates {
            let (f, c) = (fare * df, cost * dc);
            if f == 0. && c == 0. {
                continue;
            }
            let hist = &mut h["lineDays"][id.to_string()];
            if !hist.is_array() {
                *hist = serde_json::json!([]);
            }
            let flat = hist.as_array_mut().unwrap();
            let i = flat
                .chunks_exact(3)
                .position(|row| row[0].as_i64() == Some(day))
                .map(|i| 3 * i)
                .unwrap_or_else(|| {
                    flat.extend([day.into(), 0.into(), 0.into()]);
                    if flat.len() > 93 {
                        flat.drain(..flat.len() - 93);
                    }
                    flat.len() - 3
                });
            flat[i + 1] = (flat[i + 1].as_f64().unwrap_or(0.) + f).into();
            flat[i + 2] = (flat[i + 2].as_f64().unwrap_or(0.) + c).into();
        }
        for (key, index, at) in [("ledger", "day", day), ("hours", "hour", hour)] {
            for (zone, values) in &self.regions {
                let vals = [
                    values[0] * df,
                    values[1] * df,
                    values[2] * dc,
                    values[3] * df,
                ];
                if vals.iter().all(|&v| v == 0.) {
                    continue;
                }
                let row = entry(&mut h["regions"][zone], key, index, at);
                for (k, v) in ["fares", "rides", "running", "points"]
                    .into_iter()
                    .zip(vals)
                {
                    if v != 0. {
                        add(row, k, v);
                    }
                }
            }
            if up > 0. {
                if self.upkeep.is_empty() {
                    add(entry(&mut h["regions"][""], key, index, at), "upkeep", up);
                }
                for (z, f) in &self.upkeep {
                    add(
                        entry(&mut h["regions"][z], key, index, at),
                        "upkeep",
                        up * f,
                    );
                }
            }
        }
        [
            self.totals[0] * df,
            self.totals[1] * df,
            self.totals[2] * dc,
            self.totals[3] * df,
        ]
    }
}
