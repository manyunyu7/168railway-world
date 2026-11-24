// SPDX-License-Identifier: AGPL-3.0-only
//! Riders for the inspectors, from the boarding account (`train_loads::Loads`) and the demand
//! forecast behind it: web TrainInspector `OnBoard`, `TrainStops` and `OneRider`
//! (ui/inspector/*.tsx, game/riderSample.ts), and the station's `BoardingsToday` with
//! `PurposeSplit`.
use crate::{train_loads::Loads, World};
use serde_json::{json, Value};
use trainworld_sim::track::{
    boarding::{period_at, CRUSH_PER_CAR, PERIODS, RATED_PER_CAR, SEATS_PER_CAR},
    profile,
    net::LoopKind,
};

/// Web `PURPOSES` (game/purposes.ts) in its order: key, Indonesian name (id.json `purposes.*`) and
/// colour (`COLOUR`).
pub(crate) const PURPOSE_KEYS: [(&str, &str, u32); 8] = [
    ("work", "Kerja", 0x3978c6),
    ("school", "Sekolah", 0x168b91),
    ("shopping", "Belanja", 0xd94a53),
    ("social", "Rekreasi", 0x9460b4),
    ("medical", "Berobat", 0xe98abd),
    ("airport", "Bandara", 0xa8a032),
    ("attractions", "Tempat wisata", 0xd9822b),
    ("longer", "Perjalanan antarkota", 0x8a817b),
];

/// Web `PERIODS` names (id.json `periods.*`), in `PERIOD_HOURS` order.
const PERIOD_NAMES: [&str; PERIODS] = ["Puncak pagi", "Siang", "Puncak sore", "Malam", "Dini hari"];

/// Web TrainStops `AHEAD`: stops listed ahead of the train.
const AHEAD: usize = 4;

/// Each `PURPOSE_KEYS` entry's model purposes (`days::*`): the map filter's
/// (`trip_cells::PURPOSES`), then the inter-city trips.
pub(crate) fn purpose_members() -> Vec<(&'static str, Vec<usize>)> {
    use trainworld_sim::demand::days::{everyday, LONGER, WORK};
    let mut out: Vec<_> = crate::trip_cells::PURPOSES
        .iter()
        .map(|(key, kinds)| {
            let members = if kinds.is_empty() {
                vec![WORK]
            } else {
                kinds.iter().map(|&k| everyday(k as usize)).collect()
            };
            (*key, members)
        })
        .collect();
    out.push(("longer", vec![LONGER]));
    out
}

/// One line's riders on the average day, per period, at its `2n` route nodes (run 0's stops,
/// then run 1's in its own travel order), as the boarding account reads them.
pub(crate) struct LineRiders {
    pub n: usize,
    pub board: [Vec<f32>; PERIODS],
    pub alight: [Vec<f32>; PERIODS],
    /// Per `PURPOSE_KEYS` and period: the stretch leaving each route node's riders, then the
    /// boardings at each (`4n`).
    pub purposes: Vec<[Vec<f32>; PERIODS]>,
}

impl LineRiders {
    fn fits(&self, n: usize) -> bool {
        self.n == n
            && n >= 2
            && self.board.iter().all(|b| b.len() == 2 * n)
            && self.alight.iter().all(|a| a.len() == 2 * n)
    }
}

/// Web `alightShares` (TrainStops.tsx), a port of `alight_shares` in sim boarding.rs: per period
/// the share of the riders arriving at each stop of run `r` who get off there.
pub(crate) fn alight_shares(lr: &LineRiders, r: usize, wrap: bool) -> Vec<Vec<f64>> {
    let n = lr.n;
    let mut day_in = vec![0.; n];
    let mut day_off = vec![0.; n];
    let mut out = vec![vec![-1.; n]; PERIODS];
    for (p, row) in out.iter_mut().enumerate() {
        let at = |v: &[f32], k: usize| f64::from(v.get(r * n + k).copied().unwrap_or(0.)).max(0.);
        let (b, a) = (&lr.board[p], &lr.alight[p]);
        if b.len() < 2 * n || a.len() < 2 * n {
            continue;
        }
        let mut on = 0.;
        if wrap {
            let (mut x, mut low) = (0f64, 0f64);
            for k in 0..n {
                x -= at(a, k);
                low = low.min(x);
                x += at(b, k);
            }
            on = -low;
        }
        for k in 0..n {
            let off = at(a, k);
            day_in[k] += on;
            day_off[k] += off;
            row[k] = if on > 1e-6 { (off / on).clamp(0., 1.) } else { -1. };
            on = (on + at(b, k) - off).max(0.);
        }
    }
    for row in &mut out {
        for k in 0..n {
            if row[k] < 0. {
                row[k] = if day_in[k] > 1e-6 { (day_off[k] / day_in[k]).clamp(0., 1.) } else { 0. };
            }
        }
        if !wrap {
            row[n - 1] = 1.;
        }
    }
    out
}

/// A small seeded generator, so "another rider" draws again and a refresh keeps the same one.
struct Draw(u64);
impl Draw {
    fn next(&mut self) -> f64 {
        // splitmix64
        self.0 = self.0.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        ((z ^ (z >> 31)) >> 11) as f64 / (1u64 << 53) as f64
    }
    /// Web riderSample `draw`: an index in proportion to `w`, an even draw over `fallback` when
    /// none carries weight.
    fn pick(&mut self, w: &[f64], fallback: std::ops::Range<usize>) -> usize {
        let sum: f64 = w.iter().sum();
        if !(sum > 1e-9) {
            let span = fallback.len().max(1);
            return fallback.start + ((self.next() * span as f64) as usize).min(span - 1);
        }
        let mut at = self.next() * sum;
        for (i, v) in w.iter().enumerate() {
            at -= v;
            if at < 0. {
                return i;
            }
        }
        w.len() - 1
    }
}

/// Web riderSample `pickStops`: where a rider aboard on stretch `seg` got on and gets off, in the
/// train's travel order.
fn pick_stops(
    board: &[f32],
    run: usize,
    n: usize,
    seg: usize,
    last: usize,
    shares: &[f64],
    draw: &mut Draw,
) -> Option<(usize, usize)> {
    if seg >= last {
        return None;
    }
    let share = |k: usize| shares.get(k).copied().unwrap_or(0.).clamp(0., 1.);
    let mut on = vec![0.; seg + 1];
    let mut stay = 1.;
    for k in (0..=seg).rev() {
        on[k] = f64::from(board.get(run * n + k).copied().unwrap_or(0.)).max(0.) * stay;
        stay *= 1. - share(k);
    }
    let mut off = vec![0.; last + 1];
    stay = 1.;
    for (m, v) in off.iter_mut().enumerate().skip(seg + 1) {
        *v = stay * share(m);
        stay *= 1. - share(m);
    }
    let from = draw.pick(&on, 0..seg + 1);
    let to = draw.pick(&off, seg + 1..last + 1);
    Some((from, to.max(seg + 1)))
}

/// The lines other than `line` calling at `node` or its complex (web TrainStops `transfers`).
fn transfers(world: &World, node: u32, line: usize) -> Vec<Value> {
    let api = &world.api;
    let net = &api.world().net;
    let mut members = net.complex_of(node);
    if members.is_empty() {
        members.push(node);
    }
    let mut names = std::collections::BTreeSet::new();
    (0..net.line_count() as u32)
        .filter(|&l| l as usize != line && net.line_ok(l))
        .filter(|&l| api.line_stops(l).iter().any(|s| members.contains(s)))
        .filter(|&l| names.insert(api.line_name(l)))
        .map(|l| json!({"name": api.line_name(l), "color": net.line_colour[l as usize] & 0xffffff}))
        .collect()
}

/// Web TrainInspector `OnBoard` with `TrainStops` and `OneRider`: the trip `profile` leaving `dep`
/// s into its hour, at game time `time`. `{"finished":true}` once it is out of service; `None`
/// before the account has its riders (the web's "Menghitung").
pub(crate) fn train(
    world: &mut World,
    loads: &Loads,
    riders: Option<&LineRiders>,
    profile: usize,
    dep: f64,
    time: f64,
    seed: u64,
) -> Option<Value> {
    let epoch = world.load_trip_hour(time);
    let i = loads.trip(world, profile, dep)?;
    let trip = [0, 1, 2].map(|k| f64::from(world.trips[i * 4 + k]));
    let now = time - epoch;
    let elapsed = now - trip[1];
    if elapsed < 0. || now >= trip[2] {
        return Some(json!({"finished": true}));
    }
    let model = world.api.world();
    let id = profile / 6;
    let run = profile / 3 % 2;
    let service = model.svc.lines.get(id)?;
    let r = service.runs.get(run)?;
    let n = r.stop_s.len();
    if n < 2 || r.stop_idx.len() != n {
        return None;
    }
    let phases = &r.prof.get(profile % 3)?.phases;
    let (s, _) = profile::state_at(phases, elapsed);
    let last_seg = n - 2;
    let seg = r
        .stop_s
        .partition_point(|&x| x <= s + 1.)
        .saturating_sub(1)
        .min(last_seg);
    let stretch = |k: usize| if run == 0 { k } else { last_seg - k };
    let own = loads.stretch(i, stretch(seg))?;
    let at_end = s >= r.stop_s[n - 1] - 1.;
    let p = period_at(time + service.shift);
    // the typical train this hour on this stretch: its line's trains this way (T-230)
    let (mut sum, mut many) = (0., 0usize);
    for (k, t) in world.trips.chunks_exact(4).enumerate() {
        let pk = t[0] as usize;
        if pk / 6 != id || pk / 3 % 2 != run {
            continue;
        }
        if let Some(v) = loads.stretch(k, stretch(seg)) {
            sum += v;
            many += 1;
        }
    }
    let typical = if at_end || many == 0 { 0. } else { sum / many as f64 };
    let aboard = if at_end { 0. } else { own };
    let cars = if service.ferry {
        2.
    } else if service.bus.is_some() {
        1. / 3.
    } else {
        f64::from(service.cars)
    };
    let (seats, rated, crush) = (SEATS_PER_CAR * cars, RATED_PER_CAR * cars, CRUSH_PER_CAR * cars);
    let state = if aboard <= 0.5 {
        "Kosong"
    } else if aboard <= seats {
        "Ada kursi"
    } else if aboard < crush - 0.5 {
        if aboard <= rated + 0.5 {
            "Berdiri"
        } else {
            "Padat"
        }
    } else {
        "Penuh"
    };
    let wrap = service.kind == LoopKind::Circle;
    let last = if wrap { n - 2 } else { n - 1 };
    let fits = riders.filter(|lr| lr.fits(n));
    let shares = fits.map(|lr| alight_shares(lr, run, wrap));
    let node = |k: usize| r.nodes[r.stop_idx[k] as usize];
    let name = |k: usize| world.api.node_name(node(k));
    // web TrainStops: the next few stops, the riders getting off and on at each
    let mut stops = Vec::new();
    if !at_end {
        let load = |t: usize| if t >= n - 1 { 0. } else { loads.stretch(i, stretch(t)).unwrap_or(0.) };
        let duration = r.prof[profile % 3].duration;
        for k in seg + 1..=last {
            if stops.len() >= AHEAD {
                break;
            }
            let arriving = load(k - 1);
            // seconds until it reaches the stop: the profile's time at its offset
            let target = r.stop_s[k] - 1.;
            let (mut lo, mut hi) = (elapsed.max(0.), duration.max(elapsed));
            let reach = if profile::state_at(phases, hi).0 < target {
                None
            } else {
                for _ in 0..40 {
                    if hi - lo <= 0.5 {
                        break;
                    }
                    let mid = (lo + hi) / 2.;
                    if profile::state_at(phases, mid).0 >= target {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                Some((hi - elapsed).max(0.))
            };
            let pk = period_at(time + service.shift + reach.unwrap_or(0.));
            let end = k == n - 1;
            let share = shares.as_ref().map_or(0., |s| s[pk][k]);
            let off = if end { arriving } else { arriving * share };
            let on = if end { 0. } else { (load(k) - (arriving - off)).max(0.) };
            stops.push(json!({
                "name": name(k), "inS": reach, "off": off, "on": on, "arriving": arriving,
                "last": end, "lines": transfers(world, node(k), id),
            }));
        }
    }
    // web OneRider: one rider drawn from this train's riders
    let mut rider = Value::Null;
    if let (Some(lr), Some(shares), false, true) = (fits, &shares, at_end, aboard > 0.5) {
        let mut draw = Draw(seed ^ ((profile as u64) << 32) ^ dep.to_bits());
        if let Some((from, to)) = pick_stops(&lr.board[p], run, n, seg, last, &shares[p], &mut draw) {
            let weights: Vec<f64> = lr
                .purposes
                .iter()
                .map(|by_period| {
                    f64::from(by_period[p].get(run * n + seg).copied().unwrap_or(0.)).max(0.)
                })
                .collect();
            let total: f64 = weights.iter().sum();
            let k = if total > 0. { draw.pick(&weights, 0..1) } else { 0 };
            let (key, purpose, colour) = PURPOSE_KEYS[k.min(PURPOSE_KEYS.len() - 1)];
            let mut split: Vec<_> = weights
                .iter()
                .zip(PURPOSE_KEYS)
                .filter(|(w, _)| **w > 0.)
                .map(|(w, (_, name, _))| json!({"name": name, "share": w / total}))
                .collect();
            split.sort_by(|a, b| b["share"].as_f64().unwrap_or(0.).total_cmp(&a["share"].as_f64().unwrap_or(0.)));
            split.truncate(4);
            rider = json!({
                "from": name(from), "to": name(to), "purpose": key, "purposeName": purpose,
                "color": colour, "drawnFrom": total, "split": split,
            });
        }
    }
    Some(json!({
        "period": PERIOD_NAMES[p], "aboard": aboard, "typical": typical, "seats": seats,
        "rated": rated, "crush": crush, "state": state, "atEnd": at_end, "stops": stops,
        "rider": rider,
    }))
}

/// Web StationInspector `BoardingsToday` and `PurposeSplit` for `node`'s complex at game time
/// `time`: each line's boardings in the past 24 hours (`TrackApi::past_day`) and the purposes of
/// the forecast's boardings at its stops there; with the stations the riders boarding here most
/// often get off at, and those alighting here most often got on at, by the alighting shares.
pub(crate) fn station(
    world: &World,
    node: u32,
    time: f64,
    riders: &dyn Fn(u32) -> Option<LineRiders>,
) -> Value {
    let api = &world.api;
    let net = &api.world().net;
    let mut members = net.complex_of(node);
    if members.is_empty() {
        members.push(node);
    }
    // TrackApi::past_day: per line id, n, 5 figures, spans, then boardings at each of its stops
    let day = api.past_day(time);
    let mut today = std::collections::HashMap::new();
    let mut at = 1;
    for _ in 0..day.first().copied().unwrap_or(0.) as usize {
        let Some(head) = day.get(at..at + 7) else { break };
        let (l, n, spans) = (head[0] as u32, head[1] as usize, head[6] as usize);
        let first = at + 7 + 2 * spans;
        let Some(stops) = day.get(first..first + n) else { break };
        today.insert(l, stops.to_vec());
        at = first + n + 8;
    }
    let mut lines = Vec::new();
    let mut total = 0.;
    let mut purposes = [0f64; PURPOSE_KEYS.len()];
    let mut ends: [std::collections::BTreeMap<String, f64>; 2] = Default::default();
    let mut forecast: Option<[f64; 2]> = None;
    for (id, svc) in api.world().svc.lines.iter().enumerate() {
        let l = id as u32;
        if !svc.ok || !net.line_ok(l) && svc.bus.is_none() {
            continue;
        }
        let stops = api.line_stops(l);
        let here: Vec<usize> = (0..stops.len()).filter(|&k| members.contains(&stops[k])).collect();
        if here.is_empty() {
            continue;
        }
        let counted = today.get(&l);
        let boardings = counted.map(|v| here.iter().map(|&k| v.get(k).copied().unwrap_or(0.)).sum::<f64>());
        total += boardings.unwrap_or(0.);
        lines.push(json!({"id": l, "name": api.line_name(l), "color": net.line_colour[id] & 0xffffff,
            "boardings": boardings}));
        let Some(lr) = riders(l).filter(|lr| lr.fits(stops.len())) else { continue };
        let n = lr.n;
        let wrap = svc.kind == LoopKind::Circle;
        let last = if wrap { n - 2 } else { n - 1 };
        // the forecast's average day at the complex: riders getting on and off (web LineInspector `on`/`off`)
        let mut f = forecast.unwrap_or([0., 0.]);
        for run in 0..2 {
            for &k in &here {
                let j = if run == 0 { k } else { n - 1 - k };
                for p in 0..PERIODS {
                    f[0] += f64::from(lr.board[p][run * n + j]).max(0.);
                    f[1] += f64::from(lr.alight[p][run * n + j]).max(0.);
                }
            }
        }
        forecast = Some(f);
        for run in 0..2 {
            let shares = alight_shares(&lr, run, wrap);
            let travel = |k: usize| if run == 0 { k } else { n - 1 - k };
            let stop_name = |j: usize| api.node_name(stops[travel(j)]);
            for &k in &here {
                let j = travel(k);
                for p in 0..PERIODS {
                    for (slot, by_period) in purposes.iter_mut().zip(&lr.purposes) {
                        *slot += f64::from(by_period[p].get(2 * n + run * n + j).copied().unwrap_or(0.)).max(0.);
                    }
                    let board = |m: usize| f64::from(lr.board[p][run * n + m]).max(0.);
                    let share = |m: usize| shares[p][m];
                    // where the riders boarding here get off
                    let mut stay = board(j);
                    for m in j + 1..=last {
                        let off = stay * share(m);
                        stay -= off;
                        if off > 0. && !members.contains(&stops[travel(m)]) {
                            *ends[0].entry(stop_name(m)).or_default() += off;
                        }
                    }
                    // where the riders getting off here got on
                    for m in 0..j {
                        let mut stay = board(m);
                        for x in m + 1..j {
                            stay *= 1. - share(x);
                        }
                        let off = stay * share(j);
                        if off > 0. && !members.contains(&stops[travel(m)]) {
                            *ends[1].entry(stop_name(m)).or_default() += off;
                        }
                    }
                }
            }
        }
    }
    let all: f64 = purposes.iter().sum();
    let split: Vec<_> = PURPOSE_KEYS
        .iter()
        .zip(purposes)
        .filter(|(_, v)| all > 0. && *v > 0.)
        .map(|((key, name, colour), v)| json!({"key": key, "name": name, "color": colour, "share": v / all}))
        .collect();
    let top = |m: &std::collections::BTreeMap<String, f64>| {
        let sum: f64 = m.values().sum();
        let mut rows: Vec<_> = m.iter().filter(|(_, v)| **v > 0.).collect();
        rows.sort_by(|a, b| b.1.total_cmp(a.1));
        rows.into_iter()
            .take(3)
            .map(|(name, v)| json!({"name": name, "share": v / sum}))
            .collect::<Vec<_>>()
    };
    json!({"lines": lines, "boardings": (!today.is_empty()).then_some(total), "purposes": split,
        "to": top(&ends[0]), "from": top(&ends[1]),
        "forecast": forecast.map(|[on, off]| json!({"on": on, "off": off}))})
}

/// The riders a day changing lines at the complex of `node` on the forecast's average day:
/// `Forecast::transfers` packs per change the line, run and stop (in the run's order) riders
/// leave, the same where they board, and riders (web `complexTransfers`, both ends counted once).
pub(crate) fn transfers_at(world: &World, node: u32, packed: &[f32]) -> f64 {
    let api = &world.api;
    let mut members = api.world().net.complex_of(node);
    if members.is_empty() {
        members.push(node);
    }
    let mut stops: std::collections::HashMap<u32, Vec<u32>> = Default::default();
    let mut at = |line: f32, run: f32, k: f32| -> bool {
        let s = stops.entry(line as u32).or_insert_with(|| api.line_stops(line as u32));
        let (n, k) = (s.len(), k as usize);
        let i = if run as u32 == 0 { Some(k) } else { n.checked_sub(1 + k) };
        i.and_then(|i| s.get(i)).is_some_and(|x| members.contains(x))
    };
    packed
        .chunks_exact(7)
        .filter(|r| at(r[0], r[1], r[2]) || at(r[3], r[4], r[5]))
        .map(|r| f64::from(r[6]).max(0.))
        .sum()
}

/// One line's stops for its panel (web LineInspector's stop rows): at each stop the boardings in
/// the past 24 hours (`StopToday`, `TrackApi::past_day`), and from the forecast's average day the
/// riders getting on and off there, the riders on the stretch to the next stop in both directions
/// (web `dailyRiders`) and where the riders boarding there get off most, by the alighting shares
/// the web's OneRider draws from. `riders` None: the forecast is not in yet.
pub(crate) fn line(world: &World, id: u32, time: f64, riders: Option<&LineRiders>) -> Value {
    let api = &world.api;
    let stops = api.line_stops(id);
    let n = stops.len();
    let day = api.past_day(time);
    let mut today: Option<Vec<f64>> = None;
    let mut at = 1;
    for _ in 0..day.first().copied().unwrap_or(0.) as usize {
        let Some(head) = day.get(at..at + 7) else { break };
        let (l, m, spans) = (head[0] as u32, head[1] as usize, head[6] as usize);
        let first = at + 7 + 2 * spans;
        let Some(v) = day.get(first..first + m) else { break };
        if l == id {
            today = Some(v.to_vec());
        }
        at = first + m + 8;
    }
    let wrap = api.world().svc.lines.get(id as usize).is_some_and(|s| s.kind == LoopKind::Circle);
    let fits = riders.filter(|lr| lr.fits(n));
    let mut rows: Vec<Value> = (0..n)
        .map(|k| json!({"today": today.as_ref().map(|v| v.get(k).copied().unwrap_or(0.))}))
        .collect();
    if let Some(lr) = fits {
        let last = if wrap { n - 2 } else { n - 1 };
        let mut board = vec![0f64; n];
        let mut alight = vec![0f64; n];
        let mut load = vec![0f64; n];
        let mut to: Vec<std::collections::BTreeMap<u32, f64>> = vec![Default::default(); n];
        for run in 0..2 {
            let shares = alight_shares(lr, run, wrap);
            // run 1 calls at the stops backwards
            let stop = |j: usize| if run == 0 { j } else { n - 1 - j };
            for p in 0..PERIODS {
                let b = |j: usize| f64::from(lr.board[p][run * n + j]).max(0.);
                let a = |j: usize| f64::from(lr.alight[p][run * n + j]).max(0.);
                let mut on = 0.;
                if wrap {
                    let (mut x, mut low) = (0f64, 0f64);
                    for j in 0..n {
                        x -= a(j);
                        low = low.min(x);
                        x += b(j);
                    }
                    on = -low;
                }
                for j in 0..n {
                    let k = stop(j);
                    board[k] += b(j);
                    alight[k] += a(j);
                    on = (on + b(j) - a(j)).max(0.);
                    // the stretch leaving travel stop j is stop k's next one way, its previous the other
                    if j + 1 < n {
                        load[if run == 0 { k } else { k - 1 }] += on;
                    }
                    let mut stay = b(j);
                    for m in j + 1..=last {
                        let off = stay * shares[p][m];
                        stay -= off;
                        if off > 0. && stops[stop(m)] != stops[k] {
                            *to[k].entry(stops[stop(m)]).or_default() += off;
                        }
                    }
                }
            }
        }
        for (k, row) in rows.iter_mut().enumerate() {
            let sum: f64 = to[k].values().sum();
            let mut top: Vec<_> = to[k].iter().filter(|(_, v)| **v > 0.).collect();
            top.sort_by(|a, b| b.1.total_cmp(a.1));
            let top: Vec<Value> = top
                .into_iter()
                .take(2)
                .map(|(&node, v)| json!({"name": api.node_name(node), "share": v / sum}))
                .collect();
            row["on"] = json!(board[k]);
            row["off"] = json!(alight[k]);
            row["next"] = if k + 1 < n { json!(load[k]) } else { Value::Null };
            row["to"] = json!(top);
        }
    }
    json!({"line": id, "forecast": fits.is_some(), "counted": today.is_some(), "stops": rows})
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Riders put on the line by hand, as demand would: per period `per` boarding at each stop
    /// of each direction and getting off at its last.
    pub(crate) fn figures(n: usize, per: f32) -> LineRiders {
        let mut board = vec![0f32; 2 * n];
        let mut alight = vec![0f32; 2 * n];
        for run in 0..2 {
            for k in 0..n - 1 {
                board[run * n + k] = per;
            }
            // half of who is aboard gets off at each stop after the first, the rest at the end
            let mut on = 0f32;
            for k in 0..n {
                let off = if k == n - 1 { on } else if k == 0 { 0. } else { on / 2. };
                alight[run * n + k] = off;
                on = on - off + board[run * n + k];
            }
        }
        let mut purpose = vec![0f32; 4 * n];
        for j in 0..2 * n {
            purpose[j] = per;
            purpose[2 * n + j] = board[j];
        }
        let mut purposes = vec![std::array::from_fn(|_| vec![0f32; 4 * n]); PURPOSE_KEYS.len()];
        purposes[0] = std::array::from_fn(|_| purpose.clone());
        purposes[2] = std::array::from_fn(|_| purpose.iter().map(|v| v / 3.).collect());
        LineRiders {
            n,
            board: std::array::from_fn(|_| board.clone()),
            alight: std::array::from_fn(|_| alight.clone()),
            purposes,
        }
    }

    /// `Forecast::riders`' packing for `TrackApi::set_riders`.
    pub(crate) fn packed(line: u32, lr: &LineRiders) -> Vec<f32> {
        let mut out = trainworld_sim::track::params::PEAK_HOUR_FACTOR.to_vec();
        out.extend([line as f32, lr.n as f32]);
        for p in 0..PERIODS {
            out.extend(&lr.board[p]);
            out.extend(&lr.alight[p]);
        }
        out
    }

    #[test]
    fn a_line_lists_each_stops_riders_the_load_on_and_where_they_get_off() {
        let mut world = World::workshop();
        world.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.).unwrap();
        let n = world.api.line_stops(0).len();
        let lr = figures(n, 400.);
        let v = line(&world, 0, 111600., Some(&lr));
        println!("{v}");
        assert_eq!(v["forecast"], true);
        let rows = v["stops"].as_array().unwrap();
        assert_eq!(rows.len(), n);
        let f = |k: usize, key: &str| rows[k][key].as_f64().unwrap();
        // each way 400 a period get on at every stop but its last
        assert!((f(0, "on") - 400. * PERIODS as f64).abs() < 1e-6);
        assert!((f(1, "on") - 800. * PERIODS as f64).abs() < 1e-6);
        // who gets on is who gets off, over the day
        let (on, off): (f64, f64) = (0..n).map(|k| (f(k, "on"), f(k, "off"))).fold((0., 0.), |a, b| (a.0 + b.0, a.1 + b.1));
        assert!((on - off).abs() < 1e-3, "{on} {off}");
        // the first stretch carries the first stop's boardings one way and its alightings the other
        assert!((f(0, "next") - f(0, "on") - f(0, "off")).abs() < 1e-3);
        assert!(rows[n - 1]["next"].is_null());
        // from the first stop half get off at the next, a quarter at the one after, then the end
        let to = rows[0]["to"].as_array().unwrap();
        assert_eq!(to.len(), 2);
        assert_eq!(to[0]["name"], world.api.node_name(world.api.line_stops(0)[1]));
        assert!((to[0]["share"].as_f64().unwrap() - 0.5).abs() < 1e-9);
        // without the forecast only the counted boardings are there
        let bare = line(&world, 0, 111600., None);
        assert_eq!(bare["forecast"], false);
        assert!(bare["stops"][0].get("on").is_none());
        // a forecast for another stop count is not read
        assert_eq!(line(&world, 0, 111600., Some(&figures(n + 1, 400.)))["forecast"], false);
    }

    #[test]
    fn a_station_sums_the_forecast_day_and_the_changes_made_there() {
        let mut world = World::workshop();
        world.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.).unwrap();
        let stops = world.api.line_stops(0);
        let n = stops.len();
        let lr = figures(n, 400.);
        let k = 1;
        let s = station(&world, stops[k], 111600., &|_| Some(figures(n, 400.)));
        let (mut on, mut off) = (0f64, 0f64);
        for p in 0..PERIODS {
            for (run, j) in [(0, k), (1, n - 1 - k)] {
                on += f64::from(lr.board[p][run * n + j]);
                off += f64::from(lr.alight[p][run * n + j]);
            }
        }
        assert!(on > 0. && off > 0.);
        assert!((s["forecast"]["on"].as_f64().unwrap() - on).abs() < 1e-6, "{s}");
        assert!((s["forecast"]["off"].as_f64().unwrap() - off).abs() < 1e-6, "{s}");
        // without a forecast there is no day to show
        assert!(station(&world, stops[k], 111600., &|_| None)["forecast"].is_null());
        // a change leaving line 0 at stop k (run 0), one at the same stop in run 1's order, one elsewhere
        let packed = [
            0., 0., k as f32, 0., 1., (n - 1 - k) as f32, 120.,
            0., 1., (n - 1 - k) as f32, 0., 0., k as f32, 30.,
            0., 0., 0., 0., 1., (n - 1) as f32, 999.,
            0., 1., 99., 0., 0., 99., 5.,
        ];
        assert_eq!(transfers_at(&world, stops[k], &packed), 150.);
        assert_eq!(transfers_at(&world, stops[k], &[]), 0.);
    }

    #[test]
    fn shares_let_everyone_off_at_the_end_and_half_on_the_way() {
        let lr = figures(4, 300.);
        let s = alight_shares(&lr, 0, false);
        assert_eq!(s.len(), PERIODS);
        assert_eq!(s[0][3], 1.);
        assert!((s[0][1] - 0.5).abs() < 1e-9 && (s[0][2] - 0.5).abs() < 1e-9);
        let mut draw = Draw(7);
        for _ in 0..200 {
            let (from, to) = pick_stops(&lr.board[0], 0, 4, 1, 3, &s[0], &mut draw).unwrap();
            assert!(from <= 1 && (2..=3).contains(&to));
        }
        assert!(pick_stops(&lr.board[0], 0, 4, 3, 3, &s[0], &mut draw).is_none());
    }

    #[test]
    fn a_train_reports_its_riders_the_stops_ahead_and_one_rider() {
        let mut world = World::workshop();
        let start = world
            .build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.)
            .unwrap()["departure"]
            .as_f64()
            .unwrap();
        let line = 0u32;
        let stops = world.api.line_stops(line).len();
        assert_eq!(stops, 4, "a stop at each point");
        let lr = figures(stops, 400.);
        for d in 0..6 {
            world.api.set_riders(packed(line, &lr), d);
        }
        while world.api.boarding_step(32) {}
        // a moment when some train is between its second and third stop
        let mut found = None;
        for seconds in (60..7200).step_by(30) {
            let t = start + f64::from(seconds);
            let Some(loads) = Loads::new(&mut world, t) else { continue };
            let epoch = world.load_trip_hour(t);
            let trips: Vec<[f32; 4]> = world.trips.chunks_exact(4).map(|c| [c[0], c[1], c[2], c[3]]).collect();
            for tr in trips {
                if t < epoch + f64::from(tr[1]) || t >= epoch + f64::from(tr[2]) {
                    continue;
                }
                let v = train(&mut world, &loads, Some(&lr), tr[0] as usize, f64::from(tr[1]), t, 1).unwrap();
                if v["aboard"].as_f64().unwrap_or(0.) > 0.5 && v["stops"].as_array().is_some_and(|s| s.len() >= 2) {
                    found = Some((v, loads, tr, t));
                    break;
                }
            }
            if found.is_some() {
                break;
            }
        }
        let (v, loads, tr, t) = found.expect("a loaded train with stops ahead");
        println!("{v}");
        let aboard = v["aboard"].as_f64().unwrap();
        assert!(v["typical"].as_f64().unwrap() > 0.);
        assert_eq!(v["crush"].as_f64().unwrap(), CRUSH_PER_CAR * v["seats"].as_f64().unwrap() / SEATS_PER_CAR);
        let ahead = v["stops"].as_array().unwrap();
        // arriving less getting off plus boarding is the next stretch's load
        assert!((ahead[0]["arriving"].as_f64().unwrap() - aboard).abs() < 1e-3);
        for pair in ahead.windows(2) {
            let (a, b) = (&pair[0], &pair[1]);
            let leaving = a["arriving"].as_f64().unwrap() - a["off"].as_f64().unwrap() + a["on"].as_f64().unwrap();
            assert!((leaving - b["arriving"].as_f64().unwrap()).abs() < 1e-2, "{a} {b}");
            assert!(a["inS"].as_f64().unwrap() <= b["inS"].as_f64().unwrap());
        }
        let end = ahead.last().unwrap();
        assert_eq!(end["last"], true);
        assert_eq!(end["off"], end["arriving"]);
        let rider = &v["rider"];
        assert!(rider["from"].is_string() && rider["to"].is_string());
        assert_ne!(rider["from"], rider["to"]);
        // work 3 to errands 1 on every stretch
        let split = rider["split"].as_array().unwrap();
        assert_eq!(split[0]["name"], "Kerja");
        assert!((split[0]["share"].as_f64().unwrap() - 0.75).abs() < 1e-6);
        // the same seed keeps the rider, without riders there is none
        let again = train(&mut world, &loads, Some(&lr), tr[0] as usize, f64::from(tr[1]), t, 1).unwrap();
        assert_eq!(again["rider"], v["rider"]);
        let bare = train(&mut world, &loads, None, tr[0] as usize, f64::from(tr[1]), t, 1).unwrap();
        assert!(bare["rider"].is_null() && bare["aboard"] == v["aboard"]);
        // an unknown trip has no figures; the station's lines and purposes
        assert!(train(&mut world, &loads, Some(&lr), tr[0] as usize, f64::from(tr[1]) + 17., t, 1).is_none());
        let node = world.api.line_stops(line)[1];
        let s = station(&world, node, t, &|_| Some(figures(stops, 400.)));
        println!("{s}");
        assert_eq!(s["lines"].as_array().unwrap().len(), 1);
        let shares: f64 = s["purposes"].as_array().unwrap().iter().map(|p| p["share"].as_f64().unwrap()).sum();
        assert!((shares - 1.).abs() < 1e-9);
        assert_eq!(s["purposes"][0]["key"], "work");
        assert!(!s["to"].as_array().unwrap().is_empty() && !s["from"].as_array().unwrap().is_empty());
        let none = station(&world, node, t, &|_| None);
        assert!(none["purposes"].as_array().unwrap().is_empty());
    }
}
