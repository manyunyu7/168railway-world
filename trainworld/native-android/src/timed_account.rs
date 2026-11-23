//! Timing of the passenger account, from Studio's moneyTiming.ts and clock.worker.ts.
use crate::passenger_account::{Forecast, LineIncome};
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
    totals: (f64, f64),
}
fn between(table: &[[f64; 5]; 6], mut start: f64, end: f64) -> f64 {
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
        total += table[day_kind_at(start)][p] * (b - a) * share;
        start = stop;
    }
    total
}
fn fare_hour(table: &[[f64; 5]; 6], start: f64, lag: f64) -> f64 {
    if lag <= 0. {
        return between(table, start, start + 3600.);
    }
    (0..6)
        .map(|j| {
            let a = start - lag * (j as f64 + 0.5) / 6.;
            between(table, a, a + 3600.)
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
    pub(super) fn new(forecast: &Forecast, api: &mut TrackApi, clock: f64, lag: f64) -> Self {
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
            lag,
            totals: (0., 0.),
        };
        account.prepare(api, hour);
        account.previous = clock.rem_euclid(3600.) / 3600.;
        account
    }
    fn prepare(&mut self, api: &mut TrackApi, hour: i64) {
        if self.hour == hour {
            return;
        }
        self.hour = hour;
        self.previous = 0.;
        let epoch = hour as f64 * 3600.;
        self.shares = hour_shares(&api.boarding_hour(epoch));
        self.totals = (0., 0.);
        for (shift, money) in self.lines.values() {
            self.totals.0 += fare_hour(&money.fares, epoch + shift, self.lag);
            self.totals.1 += between(&money.rides, epoch + shift, epoch + shift + 3600.);
        }
    }
    pub(super) fn settle(&mut self, api: &mut TrackApi, hour: i64, end: f64) -> (f64, f64) {
        self.prepare(api, hour);
        let secs = (end - hour as f64 * 3600.).clamp(0., 3600.);
        let share = self
            .shares
            .get(secs.floor() as usize)
            .map_or(secs / 3600., |&s| s.into());
        let delta = (share - self.previous).max(0.);
        self.previous = self.previous.max(share);
        (self.totals.0 * delta, self.totals.1 * delta)
    }
}
