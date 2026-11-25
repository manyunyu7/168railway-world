// SPDX-License-Identifier: AGPL-3.0-only
//! The web's crowding report, read from the boarding account and never written to it: the line
//! panel's crowding dots per demand level, average load and past 24 hours' full trains and riders
//! left behind (ui/inspector/schedule.tsx `LevelName`, `PastDay`; game/today.ts `crowdLevel`,
//! `stretchColour`), the Issues list's "Line X padat" rows (ui/TroubleList.tsx), the Traffic view's
//! crowded stretches with their busiest hour (map/renderer.ts `setFull`, map/trackHover.ts) and each
//! stretch's average load (map/stretchLoad.ts), and the map's capacity tags and track at capacity
//! (map/capacityMarkers.ts, `fullTrack`).
use crate::train_riders::LineRiders;
use crate::World;
use serde_json::{json, Value};
use std::collections::HashMap;
use trainworld_sim::track::{
    boarding::{CROWDED_FROM, PERIODS, PERIOD_STRIDE},
    net::LoopKind,
    params::day_kind_at,
    service::LineSvc,
};

/// Web PERIODS (game/periods.ts): each period's hours and demand level (0 high, 1 medium, 2 low).
const PERIOD_HOURS: [(u32, u32); PERIODS] = [(6, 10), (10, 16), (16, 20), (20, 24), (0, 6)];
const PERIOD_LEVEL: [usize; PERIODS] = [0, 0, 0, 1, 2];
/// Web `CROWD_GRADES` (game/today.ts): past 100% as rounded, orange from 130%, red from 140%.
pub(crate) const CROWD_GRADES: [f64; 3] = [1.005, 1.3, 1.4];
/// Web `CROWD_STOPS` (game/palette.ts).
const CROWD_STOPS: [(f64, u32); 6] =
    [(0.7, 0x4f9a4f), (0.8, 0x98bd3a), (1., 0xd8b425), (1.3, 0xe07a1f), (1.4, 0xc43c3c), (1.5, 0xa82846)];
/// Web `CROWD_MEAN_POWER`, `CROWD_SKEW` (game/today.ts).
const CROWD_MEAN_POWER: f64 = 2.;
const CROWD_SKEW: f64 = 0.3;
/// Web TroubleList: a line is listed for crowding past these shares of the past 24 hours.
const LEFT_SHARE: f64 = 0.03;
const LEFT_MIN: f64 = 10.;
const FULL_SHARE: f64 = 0.25;

fn rgb(c: u32) -> [f64; 3] {
    [(c >> 16 & 255) as f64, (c >> 8 & 255) as f64, (c & 255) as f64]
}
fn pack(c: [f64; 3]) -> u32 {
    c.iter().fold(0, |a, &v| a << 8 | (v.round().clamp(0., 255.) as u32))
}

/// Web `crowdColour`: a load as a share of the rated load on the crowding ramp, 0xRRGGBB.
pub(crate) fn crowd_colour(v: f64) -> u32 {
    let s = &CROWD_STOPS;
    if v <= s[0].0 {
        return s[0].1;
    }
    for i in 1..s.len() {
        if v > s[i].0 {
            continue;
        }
        let t = (v - s[i - 1].0) / (s[i].0 - s[i - 1].0);
        let (a, b) = (rgb(s[i - 1].1), rgb(s[i].1));
        return pack([0, 1, 2].map(|k| a[k] + (b[k] - a[k]) * t));
    }
    s[s.len() - 1].1
}

/// Web `crowdGrade`: 0 within the rated load, 1 past it, 2 orange, 3 red.
pub(crate) fn crowd_grade(load: f64) -> u8 {
    if load >= CROWD_GRADES[2] { 3 } else if load >= CROWD_GRADES[1] { 2 } else if load >= CROWD_GRADES[0] { 1 } else { 0 }
}

/// Web `loadText`: "132%".
pub(crate) fn load_text(share: f64) -> String {
    format!("{}%", (share * 100.).round())
}

/// Web `skewedLoad`.
fn skewed_load(per: &[f64]) -> f64 {
    let (mut peak, mut sum) = (0f64, 0f64);
    for &x in per {
        peak = peak.max(x);
        if x > 1. {
            sum += (x - 1.).powf(CROWD_MEAN_POWER);
        }
    }
    if peak <= 1. {
        return peak;
    }
    1. + (sum / per.len() as f64).powf(1. / CROWD_MEAN_POWER)
}

/// Web `stretchColour`: the crowding dot's fill.
pub(crate) fn stretch_colour(per: &[f64]) -> u32 {
    let sk = rgb(crowd_colour(skewed_load(per)));
    let mut sum = [0f64; 3];
    for &x in per {
        let c = rgb(crowd_colour(x));
        for i in 0..3 {
            sum[i] += c[i];
        }
    }
    let n = per.len().max(1) as f64;
    pack([0, 1, 2].map(|i| (1. - CROWD_SKEW) * sum[i] / n + CROWD_SKEW * sk[i]))
}

/// A line's stop count from its `line_periods` numbers, or None where they have no loads (web
/// `peaksAt`): `PERIODS * (PERIOD_STRIDE + 2n + 3(n - 1))` numbers.
fn stops_of(v: &[f64]) -> Option<usize> {
    if v.is_empty() || v.len() % PERIODS != 0 {
        return None;
    }
    let q = v.len() / PERIODS;
    let rest = q.checked_sub(PERIOD_STRIDE - 3)?;
    (rest % 5 == 0 && rest / 5 >= 2).then_some(rest / 5)
}

/// One demand level's crowding (web `crowdLevel`).
#[derive(Debug, PartialEq)]
pub(crate) struct Level {
    /// the dot's fill, `stretch_colour` of each stretch's busiest hour over the level's periods
    pub fill: u32,
    /// the fullest stretch's busiest hour, its ring
    pub peak: f64,
    pub per: Vec<f64>,
    /// per period of the level with trains: its index and its fullest stretch's load
    pub periods: Vec<(usize, f64)>,
}

/// Web `crowdLevel` for demand level `level` from a line's `line_periods` numbers; None when the
/// level runs no trains or the numbers have no loads.
pub(crate) fn crowd_level(v: &[f64], level: usize) -> Option<Level> {
    let n = stops_of(v)?;
    let s = n - 1;
    let at = v.len() - PERIODS * s;
    let (mut trains, mut peak) = (0f64, 0f64);
    let mut per = vec![0f64; s];
    let mut periods = vec![];
    for i in 0..PERIODS {
        if PERIOD_LEVEL[i] != level || !(v[i * PERIOD_STRIDE] > 0.) {
            continue;
        }
        trains += v[i * PERIOD_STRIDE];
        let mut most = 0f64;
        for k in 0..s {
            let x = v[at + i * s + k];
            per[k] = per[k].max(x);
            most = most.max(x);
        }
        peak = peak.max(most);
        periods.push((i, most));
    }
    (trains > 0.).then(|| Level { fill: stretch_colour(&per), peak, per, periods })
}

/// Web schedule.tsx `averageLoad`: the day's rider-seconds aboard over what its trips hold at the
/// rated load, each trip its level's time from the first stop to the last, up to crush.
pub(crate) fn average_load(v: &[f64], ls: &LineSvc) -> Option<f64> {
    let (rated, crush) = (ls.rated(), ls.crush());
    let (mut aboard, mut room) = (0f64, 0f64);
    for i in 0..PERIODS {
        let o = i * PERIOD_STRIDE;
        if !(v.get(o).copied().unwrap_or(0.) > 0.) {
            continue;
        }
        let time = ls.runs[0].prof[PERIOD_LEVEL[i]].duration;
        aboard += v[o + 5];
        room += v[o] * time * rated;
    }
    (room > 0.).then(|| (crush / rated).min(aboard / room))
}

/// Trips a day both ways from `line_periods` numbers (map/stretchLoad.ts `tripsOn`).
pub(crate) fn trips_a_day(v: &[f64]) -> f64 {
    (0..PERIODS).filter_map(|i| v.get(i * PERIOD_STRIDE)).sum()
}

/// Riders a day on each stretch, both ways, in run 0's numbering (web `dailyRiders`, the forecast's
/// as train_riders.rs `line` adds them for the stop rows' "next").
pub(crate) fn stretch_riders(lr: &LineRiders, wrap: bool) -> Vec<f64> {
    let n = lr.n;
    let mut load = vec![0f64; n.saturating_sub(1)];
    if n < 2 {
        return load;
    }
    for run in 0..2 {
        let stop = |j: usize| if run == 0 { j } else { n - 1 - j };
        for p in 0..PERIODS {
            let b = |j: usize| f64::from(lr.board[p].get(run * n + j).copied().unwrap_or(0.)).max(0.);
            let a = |j: usize| f64::from(lr.alight[p].get(run * n + j).copied().unwrap_or(0.)).max(0.);
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
                on = (on + b(j) - a(j)).max(0.);
                if j + 1 < n {
                    load[if run == 0 { k } else { k - 1 }] += on;
                }
            }
        }
    }
    load
}

/// One line's past 24 hours from `TrackApi::past_day` (web `LineToday`).
#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub(crate) struct PastDay {
    pub riders: f64,
    pub full: f64,
    pub left: f64,
    pub trains: f64,
}

/// Each line's past 24 hours, `TrackApi::past_day`'s layout (`Boarding::today`).
pub(crate) fn past_days(day: &[f64]) -> HashMap<u32, PastDay> {
    let mut out = HashMap::new();
    let mut at = 1;
    for _ in 0..day.first().copied().unwrap_or(0.) as usize {
        let Some(head) = day.get(at..at + 7) else { break };
        let (id, n, spans) = (head[0] as u32, head[1] as usize, head[6] as usize);
        let tail = at + 7 + 2 * spans + n;
        let Some(t) = day.get(tail..tail + 8) else { break };
        out.insert(id, PastDay { riders: head[2], full: head[3], left: head[4], trains: t[2] });
        at = tail + 8;
    }
    out
}

/// The crowded stretches at the end of a `Boarding::hour` buffer (web `readBoardHour`): per one its
/// line, run 0 stretch, busiest hour's load as a share of the rated load and that hour of the day.
pub(crate) fn crowded(b: &[f32]) -> Vec<(u32, u32, f64, u32)> {
    if b.len() < 5 {
        return vec![];
    }
    let c = |i: usize| b[i] as usize;
    let at = 5 + 2 * c(0) + c(1) + 12 * c(2) + 2 * c(3) + 2 * c(4) + c(3) + 2 * c(4);
    let Some(&nf) = b.get(at) else { return vec![] };
    let nf = nf as usize;
    let (pairs, loads, hours) = (at + 1, at + 1 + 2 * nf, at + 1 + 3 * nf);
    if b.len() < hours + nf {
        return vec![];
    }
    (0..nf)
        .map(|j| (b[pairs + 2 * j] as u32, b[pairs + 2 * j + 1] as u32, f64::from(b[loads + j]), b[hours + j] as u32))
        .collect()
}

/// Web `vehicleOf`'s word for a line, the i18n `vehicle` select's key.
fn vehicle(world: &World, id: u32) -> &'static str {
    let api = &world.api;
    if api.is_ferry(id) { "boat" } else if api.is_bus(id) { "bus" } else if api.line_tram(id) { "tram" } else { "train" }
}

/// "06:00-10:00"
fn hour_span(p: usize) -> String {
    let (a, b) = PERIOD_HOURS[p];
    format!("{a:02}:00-{b:02}:00")
}

/// The report at game time `now`. `board`: the boarding account's hour buffer for the hour of
/// `now` (`Loads::board`), for the crowded stretches; `riders`: the forecast's riders per line, for
/// each stretch's average load.
pub(crate) fn report(world: &World, now: f64, board: Option<&[f32]>, riders: &dyn Fn(u32) -> Option<LineRiders>) -> Value {
    let api = &world.api;
    let svc = &api.world().svc;
    let days = past_days(&api.past_day(now));
    let mut lines = vec![];
    let mut troubles = vec![];
    for r in api.lines_info().chunks_exact(22) {
        let id = r[0] as u32;
        let Some(ls) = svc.lines.get(id as usize) else { continue };
        let name = api.line_name(id);
        let veh = vehicle(world, id);
        let local = now + ls.shift;
        let v = api.line_periods(id, day_kind_at(local) as u32);
        let levels: Vec<Value> = (0..3)
            .map(|lv| {
                crowd_level(&v, lv).map_or(Value::Null, |c| {
                    let mut tip = vec![format!("Muatan puncak {}", load_text(c.peak))];
                    if c.periods.len() > 1 {
                        tip.extend(c.periods.iter().map(|&(p, x)| format!("Muatan {}: {}", hour_span(p), load_text(x))));
                    }
                    json!({"fill": c.fill, "ring": crowd_colour(c.peak), "peak": c.peak, "grade": crowd_grade(c.peak),
                        "strip": c.per.iter().map(|&x| crowd_colour(x)).collect::<Vec<_>>(), "tip": tip})
                })
            })
            .collect();
        let trips = trips_a_day(&v);
        let n = api.line_stops(id).len();
        let wrap = ls.kind == LoopKind::Circle;
        let stretch = riders(id)
            .filter(|lr| lr.n == n && n >= 2)
            .map(|lr| {
                let room = trips * ls.rated();
                stretch_riders(&lr, wrap)
                    .into_iter()
                    .map(|x| if room > 0. { json!((ls.crush() / ls.rated()).min(x / room)) } else { Value::Null })
                    .collect::<Vec<_>>()
            });
        let day = (r[3] == 1.).then(|| days.get(&id).copied()).flatten();
        if let Some(d) = day {
            let wanted = d.riders + d.left;
            let item = |tier: u8| json!({"tier": tier, "line": id, "name": name, "color": r[1], "left": d.left,
                "wanted": wanted, "full": d.full, "trains": d.trains, "vehicle": veh});
            if d.left >= LEFT_MIN && d.left >= LEFT_SHARE * wanted {
                troubles.push(item(3));
            } else if d.trains > 0. && d.full >= FULL_SHARE * d.trains {
                troubles.push(item(5));
            }
        }
        lines.push(json!({
            "id": id, "vehicle": veh, "levels": levels, "average": average_load(&v, ls),
            "past": day.map(|d| json!({"riders": d.riders, "left": d.left, "full": d.full, "trains": d.trains})),
            "stretches": stretch,
        }));
    }
    // web TroubleList's order within a tier: the most riders left behind, the largest share full
    let size = |t: &Value| if t["tier"] == 3 { t["left"].as_f64().unwrap_or(0.) } else { t["full"].as_f64().unwrap_or(0.) / t["trains"].as_f64().unwrap_or(1.).max(1.) };
    troubles.sort_by(|a, b| a["tier"].as_u64().cmp(&b["tier"].as_u64()).then(size(b).total_cmp(&size(a))));
    let crowded: Vec<Value> = board
        .map(crowded)
        .unwrap_or_default()
        .into_iter()
        .filter(|c| c.2 >= CROWDED_FROM)
        .map(|(l, s, load, hour)| json!([l, s, load, hour, crowd_colour(load)]))
        .collect();
    json!({"time": now, "lines": lines, "troubles": troubles, "crowded": crowded})
}

/// The stretch of line `line` (run 0's numbering, stop k to k + 1) whose track takes edge `edge`,
/// for the track panel's loads (web trackHover's `n.lines.stretch`); None off the line.
pub(crate) fn stretch_of_edge(world: &World, line: u32, edge: u32) -> Option<usize> {
    let run = &world.api.world().svc.lines.get(line as usize)?.runs[0];
    let i = run.segs.iter().position(|&s| s >> 1 == edge)?;
    let n = run.stop_idx.len();
    if n < 2 {
        return None;
    }
    let k = run.stop_idx.partition_point(|&x| x as usize <= i).checked_sub(1)?;
    Some(k.min(n - 2))
}

/// The map's capacity tags at demand level `level` (web capacityMarkers.ts, `w.capacity`): per tag
/// x, y, utilisation and 1 when it holds trains back; and track at capacity (`fullTrack`): edges.
pub(crate) fn marks(world: &World, level: usize) -> Value {
    let api = &world.api;
    let tags: Vec<Value> = api
        .markers(level)
        .chunks_exact(7)
        .map(|m| json!([m[0], m[1], m[2], m[6], m[4]]))
        .collect();
    let full: Vec<u32> = api.full_track(level).chunks_exact(3).map(|e| e[0] as u32).collect();
    json!({"level": level, "tags": tags, "full": full})
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CrowdLens_marks(
    env: jni::JNIEnv,
    _: jni::objects::JClass,
    handle: jni::sys::jlong,
    level: jni::sys::jint,
) -> jni::sys::jstring {
    let value = unsafe { (handle as *const World).as_ref() }
        .filter(|_| (0..3).contains(&level))
        .map(|w| marks(w, level as usize))
        .unwrap_or(Value::Null);
    env.new_string(value.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::train_riders::tests::{figures, packed};

    /// A running line of four stations with `per` riders boarding a period at each stop, its account
    /// worked out.
    fn crowded_world(per: f32) -> World {
        let mut world = World::workshop();
        world.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.).unwrap();
        let n = world.api.line_stops(0).len();
        let lr = figures(n, per);
        for d in 0..6 {
            world.api.set_riders(packed(0, &lr), d);
        }
        while world.api.boarding_step(32) {}
        world
    }

    #[test]
    fn colours_follow_the_web_ramp() {
        // web crowdColour: the stops themselves, and halfway between 100% and 130%
        assert_eq!(crowd_colour(0.5), 0x4f9a4f);
        assert_eq!(crowd_colour(1.0), 0xd8b425);
        assert_eq!(crowd_colour(9.0), 0xa82846);
        let mid = crowd_colour(1.15);
        assert_eq!(mid, pack([(0xd8 as f64 + 0xe0 as f64) / 2., (0xb4 as f64 + 0x7a as f64) / 2., (0x25 as f64 + 0x1f as f64) / 2.]));
        // all stretches within the rated load: the fill is their colour
        assert_eq!(stretch_colour(&[0.5, 0.5]), 0x4f9a4f);
        assert_eq!(crowd_grade(1.0), 0);
        assert_eq!(crowd_grade(1.005), 1);
        assert_eq!(crowd_grade(1.35), 2);
        assert_eq!(crowd_grade(1.45), 3);
        assert_eq!(load_text(1.324), "132%");
    }

    #[test]
    fn a_level_reads_the_sim_period_peaks() {
        let world = crowded_world(4000.);
        let ls = &world.api.world().svc.lines[0];
        let v = world.api.line_periods(0, 0);
        let n = world.api.line_stops(0).len();
        assert_eq!(stops_of(&v), Some(n), "line_periods' layout");
        let high = crowd_level(&v, 0).expect("the high level runs trains");
        // the web's peak: the largest busiest-hour load over the high periods, read where period_peaks puts it
        let s = n - 1;
        let at = v.len() - PERIODS * s;
        let want = (0..3).flat_map(|p| (0..s).map(move |k| (p, k))).filter(|&(p, _)| v[p * PERIOD_STRIDE] > 0.)
            .map(|(p, k)| v[at + p * s + k]).fold(0f64, f64::max);
        assert!((high.peak - want).abs() < 1e-12 && high.peak > 0., "{} {want}", high.peak);
        assert_eq!(high.per.len(), s);
        assert_eq!(high.fill, stretch_colour(&high.per));
        // the average load: rider-seconds aboard over the trips' room at the rated load
        let avg = average_load(&v, ls).unwrap();
        let (mut aboard, mut room) = (0., 0.);
        for p in 0..PERIODS {
            aboard += v[p * PERIOD_STRIDE + 5];
            room += v[p * PERIOD_STRIDE] * ls.runs[0].prof[PERIOD_LEVEL[p]].duration * ls.rated();
        }
        assert!((avg - (aboard / room).min(1.5)).abs() < 1e-12 && avg > 0.);
        // a line with no loads has no dot
        assert!(crowd_level(&[], 0).is_none());
    }

    #[test]
    fn the_report_matches_past_day_and_lists_a_crowded_line() {
        let mut world = crowded_world(60000.);
        let now: f64 = 111600. + 86400.;
        let b = world.api.boarding_hour((now / 3600.).floor() * 3600.);
        let n = world.api.line_stops(0).len();
        let lr = figures(n, 60000.);
        let rep = report(&world, now, Some(&b), &|_| Some(figures(n, 60000.)));
        let line = &rep["lines"][0];
        // the same counts as the line list's riders (`line_summary::riders_of`)
        let riders = crate::line_summary::riders_of(&world.api.past_day(now))[&0];
        assert!((line["past"]["riders"].as_f64().unwrap() - riders).abs() < 1e-9);
        let past = &line["past"];
        let (left, full, trains) = (past["left"].as_f64().unwrap(), past["full"].as_f64().unwrap(), past["trains"].as_f64().unwrap());
        assert!(trains > 0. && full > 0. && left > 0., "{past}");
        // web TroubleList: so many left behind lists the line, worst tier first
        let t = &rep["troubles"][0];
        assert_eq!(t["tier"], 3);
        assert_eq!(t["left"].as_f64().unwrap(), left);
        assert_eq!(t["vehicle"], "train");
        // every stretch's average load: the forecast's riders on it over the day's trips at the rated load
        let ls = &world.api.world().svc.lines[0];
        let trips = trips_a_day(&world.api.line_periods(0, day_kind_at(now) as u32));
        let on = stretch_riders(&lr, false);
        for (k, x) in on.iter().enumerate() {
            let got = line["stretches"][k].as_f64().unwrap();
            assert!((got - (x / (trips * ls.rated())).min(1.5)).abs() < 1e-12);
        }
        // crowded stretches as the hour buffer's tail has them, past the rated load
        let tail = crowded(&b);
        assert!(!tail.is_empty(), "a packed line has crowded stretches");
        assert_eq!(rep["crowded"].as_array().unwrap().len(), tail.iter().filter(|c| c.2 >= CROWDED_FROM).count());
        assert!(tail.iter().all(|c| c.0 == 0 && (c.1 as usize) < n - 1 && c.3 < 24));
    }

    #[test]
    fn stretch_riders_match_the_stop_rows() {
        let world = crowded_world(400.);
        let n = world.api.line_stops(0).len();
        let lr = figures(n, 400.);
        let rows = crate::train_riders::line(&world, 0, 111600. + 86400., Some(&lr));
        let on = stretch_riders(&lr, false);
        for k in 0..n - 1 {
            assert!((rows["stops"][k]["next"].as_f64().unwrap() - on[k]).abs() < 1e-6);
        }
    }

    #[test]
    fn a_quiet_line_is_no_trouble_and_edges_find_their_stretch() {
        let world = crowded_world(5.);
        let rep = report(&world, 111600. + 86400., None, &|_| None);
        assert_eq!(rep["troubles"].as_array().unwrap().len(), 0);
        assert!(rep["lines"][0]["stretches"].is_null());
        let run = &world.api.world().svc.lines[0].runs[0];
        let n = run.stop_idx.len();
        // the first edge is on the first stretch, the last on the last
        assert_eq!(stretch_of_edge(&world, 0, run.segs[0] >> 1), Some(0));
        assert_eq!(stretch_of_edge(&world, 0, run.segs[run.segs.len() - 1] >> 1), Some(n - 2));
        assert_eq!(stretch_of_edge(&world, 0, u32::MAX >> 1), None);
        let m = marks(&world, 0);
        assert!(m["tags"].is_array() && m["full"].is_array());
    }

    #[test]
    fn platforms_read_the_hour_queues() {
        let mut world = crowded_world(60000.);
        let now = 111600. + 86400. + 1800.;
        let loads = crate::train_loads::Loads::new(&mut world, now).expect("the hour's loads");
        let node = world.api.line_stops(0)[1];
        let rows = loads.platforms(&[node], now);
        // a middle station: one platform each way, the packed line leaving riders behind there
        assert_eq!(rows.len(), 2, "{rows:?}");
        assert!(rows.iter().all(|r| r.0 == 0 && r.2 >= 0. && r.5 > 0.));
        assert!(rows.iter().any(|r| r.3 > 0. && r.4 > 0.), "{rows:?}");
    }
}
