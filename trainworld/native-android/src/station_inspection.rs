// SPDX-License-Identifier: AGPL-3.0-only
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

fn served_nodes(w: &World, line: usize, run: usize) -> Vec<(usize, u32)> {
    let svc = &w.api.world().svc.lines[line];
    let path = &svc.runs[run];
    (0..path.stop_idx.len())
        .filter(|&k| !path.passes(k))
        .filter_map(|k| svc.stop_node(run, k).map(|id| (k, id)))
        .collect()
}

/// A built station's upkeep a game day, US$, as the web's `stationUpkeepOf` (regionMoney.ts) and
/// the track model's `station_upkeep`: a kind's own figure (a tram stop, a port), else dearer below
/// ground, a one-platform station its share.
fn upkeep(net: &trainworld_sim::track::net::Network, id: u32) -> f64 {
    use trainworld_sim::track::params::{ECONOMY, UPKEEP_ONE_PLATFORM_SHARE};
    let share = if net.one_platform(id) {
        UPKEEP_ONE_PLATFORM_SHARE
    } else {
        1.0
    };
    net.node_kind(id).spec().station_upkeep.unwrap_or_else(|| {
        trainworld_sim::track::cost::station_upkeep(net.node_level[id as usize]) * share
    }) * ECONOMY
}

/// The web's `stationUpkeep` hover: an underground station's and any other's a day, US$.
fn upkeep_rates() -> [f64; 2] {
    use trainworld_sim::track::params::{ECONOMY, UPKEEP_STATION, UPKEEP_STATION_UNDER};
    [UPKEEP_STATION_UNDER * ECONOMY, UPKEEP_STATION * ECONOMY]
}

fn station(w: &World, node: u32, now: f64) -> Value {
    let net = &w.api.world().net;
    let complex = net.complex_of(node);
    if complex.is_empty() || !now.is_finite() {
        return Value::Null;
    }
    let mut lines = Vec::new();
    let mut departures = Vec::new();
    for (id, line) in w.api.world().svc.lines.iter().enumerate() {
        if !line.ok || line.is_bus() {
            continue;
        }
        let id_u = id as u32;
        let mut directions = Vec::new();
        let mut stops = Vec::new();
        for run in 0..if line.one_way { 1 } else { 2 } {
            let path = served_nodes(w, id, run);
            if path.iter().any(|(_, n)| complex.contains(n)) {
                if let Some((_, last)) = path.last() {
                    directions.push(json!({"run":run,"to":w.api.node_name(*last)}));
                }
                stops.extend(
                    path.into_iter()
                        .filter_map(|(_, n)| complex.contains(&n).then_some(n)),
                );
            }
        }
        if directions.is_empty() {
            continue;
        }
        lines.push(json!({"id":id,"name":w.api.line_name(id_u),"color":net.line_colour[id],"runs":directions}));
        stops.sort_unstable();
        stops.dedup();
        for n in stops {
            for dep in w
                .api
                .station_departures(id_u, n, now, now + 7200.)
                .chunks_exact(3)
            {
                departures.push((
                    dep[2],
                    json!({"at":dep[2],"shift":line.shift,"run":dep[0] as u32,"line":id_u,"node":n}),
                ));
            }
        }
    }
    departures.sort_by(|a, b| a.0.total_cmp(&b.0));
    let platforms:Vec<_>=complex.iter().filter(|&&id|net.node_built[id as usize]).map(|&id|json!({
        "facilities":super::station_facilities::offered(w,id),"id":id, "name":w.api.node_name(id), "meters":net.node_platform[id as usize], "level":net.node_level[id as usize],
        "upkeep":upkeep(net,id)
    })).collect();
    json!({"id":node,"name":w.api.node_name(node),"platforms":platforms,"lines":lines,"upkeepRates":upkeep_rates(),
        "departures":departures.into_iter().map(|(_,d)|d).collect::<Vec<_>>()})
}

fn route(w: &World, id: u32, run: usize, now: f64) -> Value {
    let Some(line) = w.api.world().svc.lines.get(id as usize) else {
        return Value::Null;
    };
    if !now.is_finite() || !line.ok || line.is_bus() || run > 1 || (run == 1 && line.one_way) {
        return Value::Null;
    }
    let trips = w.api.line_trips(id, now, now + 86400.);
    let next = trips
        .chunks_exact(4)
        .filter(|r| (r[0] as usize / 3) % 2 == run && r[1] >= now)
        .min_by(|a, b| a[1].total_cmp(&b[1]));
    let level = next.map_or(1, |r| r[0] as usize % 3);
    let arrivals = w.api.stop_times(id, run, level);
    let departures = w.api.stop_departures(id, run, level);
    let path = served_nodes(w, id as usize, run);
    let count = path.len();
    let stops: Vec<_> = path
        .into_iter()
        .enumerate()
        .map(|(i, (k, node))| {
            let arrival = next.and_then(|r| arrivals.get(k).map(|t| r[1] + t));
            let depart = (i + 1 < count)
                .then(|| next.and_then(|r| departures.get(k).map(|t| r[1] + t)))
                .flatten();
            json!({"node":node,"name":w.api.node_name(node),"arrival":arrival,"departure":depart})
        })
        .collect();
    json!({"id":id,"shift":line.shift,"name":w.api.line_name(id),"color":w.api.world().net.line_colour[id as usize],"run":run,
        "base":next.map(|r|r[1]),"stops":stops})
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationInspector_read(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: jint,
    line: jint,
    run: jint,
    clock: jdouble,
) -> jstring {
    let value = unsafe { (handle as *const World).as_ref() }.map_or(Value::Null, |w| {
        if node >= 0 {
            station(w, node as u32, clock)
        } else {
            route(w, line as u32, run as usize, clock)
        }
    });
    env.new_string(value.to_string())
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn station_transfer_board_and_route_use_native_departures() {
        let w = World::open(include_bytes!("../../T-007-real-nyc.save")).unwrap();
        let now = 111600.;
        let net = &w.api.world().net;
        let mut sample = None;
        for id in 0..net.node_count() as u32 {
            if !net.is_station(id) || net.complex_of(id).len() < 2 {
                continue;
            }
            let v = station(&w, id, now);
            if v["lines"].as_array().unwrap().len() > 1
                && !v["departures"].as_array().unwrap().is_empty()
            {
                sample = Some(v);
                break;
            }
        }
        let s = sample.expect("NYC transfer with upcoming departures");
        let board = s["departures"].as_array().unwrap();
        assert!(board
            .windows(2)
            .all(|pair| pair[0]["at"].as_f64() <= pair[1]["at"].as_f64()));
        let d = &board[0];
        let line = d["line"].as_u64().unwrap() as u32;
        let node = d["node"].as_u64().unwrap() as u32;
        let run = d["run"].as_u64().unwrap() as usize;
        assert!(w
            .api
            .station_departures(line, node, now, now + 7200.)
            .chunks_exact(3)
            .any(|row| row[0] == run as f64 && row[2] == d["at"].as_f64().unwrap()));
        let r = route(&w, line, run, now);
        let steps = r["stops"].as_array().unwrap();
        assert!(steps.len() >= 2 && r["base"].is_number());
        assert!(steps
            .windows(2)
            .all(|pair| pair[0]["arrival"].as_f64() <= pair[1]["arrival"].as_f64()));
        assert!(steps.last().unwrap()["departure"].is_null());
        assert!(route(&w, line, 2, now).is_null());
        // upkeep as web stationUpkeepOf: 500 (2000 below ground) × economy 100, a one-platform station 60%
        assert_eq!(s["upkeepRates"], json!([200000.0, 50000.0]));
        for p in s["platforms"].as_array().unwrap() {
            let id = p["id"].as_u64().unwrap() as u32;
            let base = if p["level"].as_i64().unwrap() < 0 {
                200000.
            } else {
                50000.
            };
            let want = net
                .node_kind(id)
                .spec()
                .station_upkeep
                .map_or(base * if net.one_platform(id) { 0.6 } else { 1. }, |v| {
                    v * 100.
                });
            assert!((p["upkeep"].as_f64().unwrap() - want).abs() < 1e-6, "{p}");
        }
        println!(
            "{}: {} platforms, {} lines; {} timed stops on {}",
            s["name"],
            s["platforms"].as_array().unwrap().len(),
            s["lines"].as_array().unwrap().len(),
            steps.len(),
            r["name"]
        );
    }
}
