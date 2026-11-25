//! Web LinesTab's figures per line (ui/LinesTab.tsx `RidersToday`, `MoneyToday`, `TrainsNow`): riders
//! in the past 24 hours (`TrackApi::past_day`), the expected profit today as fares a whole local day
//! takes less the past 24 hours' running cost and the line's share of a day's upkeep
//! (game/today.ts `dayFares`, `runningToday`, `upkeepToday`), and the vehicles the level now needs.
use crate::World;
use serde_json::{json, Value};
use std::collections::HashMap;

/// Hours a day at each demand level (web clock.worker `LEVEL_HOURS`: 06-20 high, 20-24 medium,
/// 00-06 low), for `line_upkeep`.
const LEVEL_HOURS: [f64; 3] = [14., 4., 6.];

/// Web game/periods.ts: the demand level at local time `local`.
fn level_at(local: f64) -> usize {
    match (local.rem_euclid(86400.) / 3600.) as u32 {
        6..=19 => 0,
        20..=23 => 1,
        _ => 2,
    }
}

/// Each line's riders in the past 24 hours from `TrackApi::past_day`'s layout.
pub(crate) fn riders_of(day: &[f64]) -> HashMap<u32, f64> {
    let mut out = HashMap::new();
    let mut at = 1;
    for _ in 0..day.first().copied().unwrap_or(0.) as usize {
        let Some(head) = day.get(at..at + 7) else { break };
        let (id, n, spans) = (head[0] as u32, head[1] as usize, head[6] as usize);
        *out.entry(id).or_default() += head[2];
        at += 15 + 2 * spans + n;
    }
    out
}

/// Each station's boardings in the past 24 hours from `TrackApi::past_day`'s layout, the stops of
/// every rail line added up by station node (web game/today.ts `stationsToday`), in node order.
/// Bus lines' stops are not stations and are left out.
pub(crate) fn stations_of(api: &trainworld_sim::track::wasm::TrackApi, day: &[f64]) -> Vec<(u32, f64)> {
    let mut out: std::collections::BTreeMap<u32, f64> = Default::default();
    let mut at = 1;
    for _ in 0..day.first().copied().unwrap_or(0.) as usize {
        let Some(head) = day.get(at..at + 7) else { break };
        let (id, n, spans) = (head[0] as u32, head[1] as usize, head[6] as usize);
        let from = at + 7 + 2 * spans;
        at += 15 + 2 * spans + n;
        if api.world().net.is_bus(id) {
            continue;
        }
        let (stops, Some(board)) = (api.line_stops(id), day.get(from..from + n)) else { continue };
        if stops.len() != n {
            continue;
        }
        for (node, b) in stops.into_iter().zip(board) {
            *out.entry(node).or_default() += b;
        }
    }
    out.into_iter().collect()
}

/// Running cost over the 24 hours before `now`, each hour's `hour_cost(epoch)` (US$M an hour) by
/// the part of it inside them (web `runningToday`).
pub(crate) fn past_day_running(now: f64, hour_cost: impl Fn(f64) -> f64) -> f64 {
    let (t, t0) = (now / 3600., now / 3600. - 24.);
    let mut cost = 0.;
    let mut h = t0.floor();
    while h < t {
        let part = (h + 1.).min(t) - h.max(t0);
        if part > 0. {
            cost += hour_cost(h * 3600.) * part;
        }
        h += 1.;
    }
    cost
}

/// The figures of every line at game time `now`. `fares(line)`: its whole local day's fares (None
/// without riders worked out); `running(line)`: its past 24 hours' running cost. Money in US$M.
pub(crate) fn summary(
    world: &World,
    now: f64,
    fares: impl Fn(u32) -> Option<f64>,
    running: impl Fn(u32) -> f64,
) -> Value {
    let api = &world.api;
    let day = api.past_day(now);
    let riders = riders_of(&day);
    let stations: Vec<Value> = stations_of(api, &day).into_iter().map(|(n, b)| json!([n, b])).collect();
    let shares = api.line_upkeep(&LEVEL_HOURS);
    let upkeep: HashMap<u32, f64> = shares
        .chunks_exact(2)
        .filter(|p| p[0] >= 0.)
        .map(|p| (p[0] as u32, p[1]))
        .collect();
    let mut total = 0.;
    let lines: Vec<Value> = api
        .lines_info()
        .chunks_exact(22)
        .map(|r| {
            let id = r[0] as u32;
            let status = if r[3] == 1. { "running" } else if r[4] == 1. { "broken" } else { "planned" };
            let shift = world.api.world().svc.lines.get(id as usize).map_or(0., |s| s.shift);
            let trains = if r[3] == 1. { r[13 + level_at(now + shift)] } else { 0. };
            let ridden = riders.get(&id).copied();
            total += ridden.unwrap_or(0.);
            // T-2536: no profit before the line runs vehicles and its riders are worked out
            let has_trains = r[5..8].iter().any(|&t| t > 0.);
            let money = (r[3] == 1. && has_trains).then(|| {
                let f = fares(id).unwrap_or(0.);
                let run = running(id);
                let up = upkeep.get(&id).copied().unwrap_or(0.);
                json!({"fares": f, "running": run, "upkeep": up, "profit": f - run - up})
            });
            json!({"id": id, "status": status, "riders": ridden, "trains": trains, "money": money})
        })
        .collect();
    json!({"lines": lines, "riders": total, "stations": stations})
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::train_riders::tests::{figures, packed};

    #[test]
    fn running_covers_exactly_the_past_day() {
        // a whole number of hours and a part hour both add up to 24 hours at a flat rate
        assert!((past_day_running(36000., |_| 2.) - 48.).abs() < 1e-9);
        assert!((past_day_running(36000. + 1800., |_| 2.) - 48.).abs() < 1e-9);
        // the hour 24 hours ago counts by the part still inside: half of it at 10:30
        let v = past_day_running(37800., |e| if e == -14. * 3600. { 10. } else { 0. });
        assert!((v - 5.).abs() < 1e-9, "{v}");
    }

    #[test]
    fn a_day_of_fares_is_every_hour_of_the_local_day() {
        let table = [[1.; 5]; 6];
        // one US$M an hour every period: 24 a day wherever in the day it is asked
        for local in [0., 3600. * 7.5, 86400. * 3. + 82800.] {
            let v = crate::timed_account::whole_day_fares(&table, local, 0., "");
            assert!((v - 24.).abs() < 1e-6, "{local} {v}");
        }
    }

    #[test]
    fn a_running_line_reports_its_riders_money_and_vehicles() {
        let mut world = World::workshop();
        world.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.).unwrap();
        let n = world.api.line_stops(0).len();
        let lr = figures(n, 400.);
        for d in 0..6 {
            world.api.set_riders(packed(0, &lr), d);
        }
        while world.api.boarding_step(32) {}
        let now = 111600. + 86400.;
        let v = summary(&world, now, |_| Some(3.), |_| 1.);
        let line = &v["lines"][0];
        assert_eq!(line["status"], "running");
        let riders = line["riders"].as_f64().unwrap();
        // the same count the line panel's stop rows add up (`train_riders::line`'s "today")
        let rows = crate::train_riders::line(&world, 0, now, None);
        let stops: f64 = rows["stops"].as_array().unwrap().iter().map(|r| r["today"].as_f64().unwrap_or(0.)).sum();
        assert!(riders > 0. && (riders - stops).abs() < 1e-6 * riders.max(1.), "{riders} {stops}");
        assert_eq!(v["riders"].as_f64().unwrap(), riders);
        // each station's boardings: the stops' figures by node, adding up to the line's riders
        let st = v["stations"].as_array().unwrap();
        let nodes = world.api.line_stops(0);
        assert!(!st.is_empty() && st.iter().all(|r| nodes.contains(&(r[0].as_u64().unwrap() as u32))));
        let boarded: f64 = st.iter().map(|r| r[1].as_f64().unwrap()).sum();
        assert!((boarded - riders).abs() < 1e-6 * riders.max(1.), "{boarded} {riders}");
        let money = &line["money"];
        let up = money["upkeep"].as_f64().unwrap();
        assert!(up > 0., "a running line pays a share of its track's upkeep");
        assert!((money["profit"].as_f64().unwrap() - (3. - 1. - up)).abs() < 1e-12);
        assert!(line["trains"].as_f64().unwrap() >= 1.);
    }

    #[test]
    fn a_line_not_running_has_no_money() {
        let world = World::workshop();
        let v = summary(&world, 111600., |_| Some(3.), |_| 1.);
        assert_eq!(v["lines"].as_array().unwrap().len(), 0);
        assert_eq!(v["riders"].as_f64().unwrap(), 0.);
    }
}
