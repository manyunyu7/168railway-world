//! Achievements (web `game/achievements.ts`, T-2124): the steps met now, worked out from the world
//! and the average day's forecast, and the steps earned, kept in the save header's `achievements`
//! with the game second each landed (`{at, of?, v?, in?}`, the web's own record, so a save carries
//! them between the two). Never taken back; a sandbox earns none; an older save with no record
//! works its out quietly the first time (none of them new).
//!
//! Worked out here: the four tiered ones (cities served, trips a day, busiest station, busiest
//! line) and the firsts Over the border, Longest road, Bullet train, City circle, All change, Transit
//! city and the hidden national railways. The rest (two continents, string of pearls, money train,
//! stay on the ground, sleeper, trainsfan, the corridors, Grand Tour) need figures the native game
//! does not have yet: they are listed, and shown as earned when the save has them (from the web).
use crate::World;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jdouble, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const CITY_TIERS: [f64; 4] = [2., 10., 25., 100.];
pub(crate) const TRIP_TIERS: [f64; 5] = [1e6, 10e6, 100e6, 300e6, 1e9];
pub(crate) const STATION_TIERS: [f64; 4] = [500e3, 1e6, 2e6, 5e6];
pub(crate) const LINE_TIERS: [f64; 4] = [500e3, 1e6, 3e6, 10e6];
const LONG_M: f64 = 5_000_000.;
const BULLET_KMH: f64 = 300.;
const CIRCLE_STOPS: usize = 10;
const CHANGES_PER_TRIP: f64 = 1.;
const CHANGE_MIN_TRIPS: f64 = 100e3;
const CITY_SHARE: f64 = 20.;
const RIDES_PER_PERSON: f64 = 3.;
/// web `NATIONS`: country, share of its rides a day by train (%), people
const NATIONS: [(&str, f64, f64); 14] = [
    ("JP", 10., 126_117_819.), ("US", 2., 327_534_599.), ("ES", 3., 45_649_934.), ("GB", 4., 66_408_930.),
    ("IT", 3., 59_366_251.), ("DE", 4., 83_346_335.), ("KR", 10., 51_205_458.), ("TW", 10., 23_752_850.),
    ("CN", 2., 1_412_121_555.), ("ID", 2., 267_744_927.), ("MX", 2., 126_014_694.), ("BR", 2., 201_987_873.),
    ("AU", 4., 24_664_377.), ("TR", 2., 87_720_246.),
];

/// web `tierKey`: "trips:1000000"
pub(crate) fn tier_key(id: &str, tier: f64) -> String {
    format!("{id}:{}", tier as u64)
}

fn tiers(met: &mut Map<String, Value>, id: &str, list: &[f64], v: f64, detail: Value) {
    for &t in list.iter().filter(|&&t| v >= t) {
        met.insert(tier_key(id, t), detail.clone());
    }
}

/// Every step met now and the tiered figures. `manifest` as `NativeRidership.networkLayers` takes
/// it; empty for the network's alone (no zones: no cities, trips or riders).
pub(crate) fn figures(save: &[u8], manifest: &str) -> Result<Value, String> {
    let mut world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
    let inputs = if manifest.trim().is_empty() {
        None
    } else {
        let files: crate::demand::DemandFiles = serde_json::from_str(manifest).map_err(|e| e.to_string())?;
        Some(files.read()?)
    };
    let forecast = match &inputs {
        Some(i) => Some(i.forecast(save, &mut world)?),
        None => None,
    };
    Ok(work_out(&world, inputs.as_ref().map(|i| i.geography()), forecast.as_deref()))
}

fn work_out(world: &World, geo: Option<&crate::demand_zones::Geography>, forecast: Option<&crate::passenger_account::Forecast>) -> Value {
    let api = &world.api;
    // built stations: their zone ("" outside every zone read, or with no boundaries)
    let mut zone: BTreeMap<u32, &'static str> = BTreeMap::new();
    for n in api.nodes_info().chunks_exact(10).filter(|n| n[7] > 0. && n[8] == 1.) {
        let ll = api.to_lonlat(n[1], n[2]);
        zone.insert(n[0] as u32, geo.map_or("", |g| g.zone(ll[0], ll[1])));
    }
    // rail lines with trains set at some level (web `runningLines`)
    let running: Vec<(u32, f64)> = api
        .lines_info()
        .chunks_exact(22)
        .filter(|r| r[2] == 1. && r[3] == 1. && r[5] + r[6] + r[7] > 0. && !api.is_bus(r[0] as u32))
        .map(|r| (r[0] as u32, r[9]))
        .collect();
    let mut met = Map::new();
    let mut served = BTreeSet::new();
    let (mut long, mut bullet, mut circle): (Option<(String, f64)>, Option<(String, f64)>, Option<(String, usize)>) = (None, None, None);
    for &(id, len) in &running {
        let stops = api.line_stops(id);
        let name = api.line_name(id);
        let mut countries = BTreeSet::new();
        for z in stops.iter().filter_map(|s| zone.get(s)).filter(|z| !z.is_empty()) {
            served.insert(*z);
            let cc = crate::passenger_account::country_of(z);
            if !cc.is_empty() {
                countries.insert(cc);
            }
        }
        if countries.len() > 1 && !met.contains_key("border") {
            met.insert("border".into(), json!({"of": name, "in": countries}));
        }
        if len >= LONG_M && long.as_ref().map_or(true, |l| len > l.1) {
            long = Some((name.clone(), len));
        }
        let top = api.line_stretch_speeds(id).chunks_exact(3).map(|s| s[0]).fold(0., f64::max);
        if top >= BULLET_KMH && bullet.as_ref().map_or(true, |b| top > b.1) {
            bullet = Some((name.clone(), top));
        }
        // a whole circle only: no stop visited twice
        let n = stops.len().saturating_sub(1);
        if api.line_loop(id) == 1 && n >= CIRCLE_STOPS && stops.iter().collect::<BTreeSet<_>>().len() == n && circle.as_ref().map_or(true, |c| n > c.1) {
            circle = Some((name.clone(), n));
        }
    }
    for (key, best) in [("long", long), ("bullet", bullet)] {
        if let Some((of, v)) = best {
            met.insert(key.into(), json!({"of": of, "v": v}));
        }
    }
    if let Some((of, v)) = circle {
        met.insert("circle".into(), json!({"of": of, "v": v}));
    }
    let cities = geo.map(|_| served.len());
    if let Some(n) = cities {
        tiers(&mut met, "cities", &CITY_TIERS, n as f64, json!({"v": n}));
    }
    let mut out = json!({"cities": cities, "trips": null, "station": null, "line": null, "transfersPerTrip": null, "running": running.len()});
    let Some(f) = forecast.filter(|_| geo.is_some() && !running.is_empty()) else {
        out["met"] = met.into();
        return out;
    };
    // trips a day: rail trips of the zones with a built station
    let built: BTreeSet<&str> = zone.values().copied().filter(|z| !z.is_empty()).collect();
    let days = f.city_days();
    let trips: f64 = built.iter().filter_map(|z| days.get(*z)).map(|d| d[1]).sum();
    out["trips"] = trips.into();
    tiers(&mut met, "trips", &TRIP_TIERS, trips, json!({"v": trips}));
    // busiest station and line, by boardings a day
    let board = f.day_boardings();
    let mut at_station: BTreeMap<u32, f64> = BTreeMap::new();
    let mut line: Option<(String, f64)> = None;
    for &(id, _) in &running {
        let Some(b) = board.get(&id) else { continue };
        let stops = api.line_stops(id);
        let n = stops.len();
        for (k, v) in b.iter().enumerate() {
            let stop = if k < n { stops.get(k) } else { n.checked_sub(1 + k - n).and_then(|i| stops.get(i)) };
            if let Some(s) = stop {
                *at_station.entry(*s).or_default() += v;
            }
        }
        let riders: f64 = b.iter().sum();
        if !api.world().net.is_twin(id) && line.as_ref().map_or(true, |l| riders > l.1) {
            line = Some((api.line_name(id), riders));
        }
    }
    if let Some((&s, &v)) = at_station.iter().max_by(|a, b| a.1.total_cmp(b.1)) {
        let d = json!({"of": api.node_name(s), "v": v});
        tiers(&mut met, "station", &STATION_TIERS, v, d.clone());
        out["station"] = d;
    }
    if let Some((of, v)) = line {
        let d = json!({"of": of, "v": v});
        tiers(&mut met, "line", &LINE_TIERS, v, d.clone());
        out["line"] = d;
    }
    // all change: the network's rail trips each changing once on average
    let rail: f64 = days.values().map(|d| d[1]).sum();
    let transfers = f.transfers_a_day();
    if rail > 0. {
        out["transfersPerTrip"] = (transfers / rail).into();
        if rail >= CHANGE_MIN_TRIPS && transfers / rail >= CHANGES_PER_TRIP {
            met.insert("change".into(), json!({"v": transfers / rail}));
        }
    }
    // a city's own trips by train (its 20% goal); `zone` for the page to name
    let mut city: Option<(&str, f64)> = None;
    let mut rail_in: BTreeMap<&str, f64> = BTreeMap::new();
    for z in &built {
        let Some(d) = days.get(*z) else { continue };
        let share = if d[0] > 0. { 100. * d[1] / d[0] } else { 0. };
        if share >= CITY_SHARE && city.map_or(true, |c| share > c.1) {
            city = Some((z, share));
        }
        let cc = crate::passenger_account::country_of(z);
        if !cc.is_empty() {
            *rail_in.entry(cc).or_default() += d[1];
        }
    }
    if let Some((z, v)) = city {
        met.insert("city".into(), json!({"zone": z, "v": v}));
    }
    for (cc, share, people) in NATIONS {
        let v = 100. * rail_in.get(cc).copied().unwrap_or(0.) / (RIDES_PER_PERSON * people);
        if v >= share {
            met.insert(format!("nation:{cc}"), json!({"of": cc, "v": v}));
        }
    }
    out["met"] = met.into();
    out
}

/// Keeps the steps in `met` the save has not recorded, at game second `clock`; returns the mode,
/// every step earned and the ones new now (none on an older save's first working-out).
pub(crate) fn record(world: &mut World, met: &Value, clock: f64) -> Value {
    let sandbox = world.header["mode"].as_str() == Some("sandbox");
    if sandbox {
        return json!({"mode": "sandbox", "got": {}, "fresh": []});
    }
    let quiet = !world.header["achievements"].is_object();
    let mut fresh = vec![];
    if let Some(m) = met.as_object().filter(|m| !m.is_empty() || !quiet) {
        if quiet {
            world.header["achievements"] = json!({});
        }
        let got = world.header["achievements"].as_object_mut().unwrap();
        for (k, d) in m {
            if got.contains_key(k) {
                continue;
            }
            let mut rec = d.as_object().cloned().unwrap_or_default();
            // a step carried from another copy of the world keeps when it landed, and is not new
            if rec.contains_key("at") {
                got.insert(k.clone(), rec.into());
                continue;
            }
            rec.insert("at".into(), clock.floor().into());
            got.insert(k.clone(), rec.into());
            if !quiet {
                fresh.push(k.clone());
            }
        }
    }
    json!({"mode": world.header["mode"], "got": world.header["achievements"].as_object().cloned().unwrap_or_default(), "fresh": fresh})
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioAchievements_figures(mut env: JNIEnv, _: JClass, save: JByteArray, manifest: JString) -> jstring {
    let result = (|| {
        let save = env.convert_byte_array(save).map_err(|e| e.to_string())?;
        let manifest: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        figures(&save, &manifest)
    })()
    .unwrap_or_else(|e: String| json!({"error": e}));
    string(&env, result)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioAchievements_record(mut env: JNIEnv, _: JClass, handle: jlong, met: JString, clock: jdouble) -> jstring {
    if handle == 0 {
        return string(&env, json!({"error": "no world"}));
    }
    let world = unsafe { &mut *(handle as *mut World) };
    let met: String = env.get_string(&met).map(Into::into).unwrap_or_default();
    let met: Value = serde_json::from_str(&met).unwrap_or(Value::Null);
    string(&env, record(world, &met, clock))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_match_the_web() {
        assert_eq!(tier_key("trips", 1e6), "trips:1000000");
        assert_eq!(tier_key("trips", 1e9), "trips:1000000000");
        assert_eq!(tier_key("cities", 2.), "cities:2");
    }

    #[test]
    fn steps_are_kept_once_and_a_sandbox_earns_none() {
        let mut w = World::workshop();
        w.header["mode"] = json!("normal");
        w.header["achievements"] = json!({});
        let first = record(&mut w, &json!({"long": {"of": "Utama", "v": 5.2e6}}), 3600.5);
        assert_eq!(first["fresh"], json!(["long"]));
        assert_eq!(first["got"]["long"]["at"], json!(3600.));
        // met again later: still the first time, and nothing new
        let again = record(&mut w, &json!({"long": {"of": "Lain", "v": 6e6}}), 9000.);
        assert_eq!(again["fresh"], json!([]));
        assert_eq!(again["got"]["long"]["of"], json!("Utama"));
        // the record rides in the save
        let reopened = World::open(&w.save(9000.).unwrap()).unwrap();
        assert_eq!(reopened.header["achievements"]["long"]["at"], json!(3600.));
        // carried from another copy (the simulation's snapshot): its own time, not new
        let mut copy = World::workshop();
        copy.header["mode"] = json!("normal");
        copy.header["achievements"] = json!({});
        let carried = record(&mut copy, &again["got"], 99999.);
        assert_eq!(carried["fresh"], json!([]));
        assert_eq!(carried["got"]["long"]["at"], json!(3600.));
        w.header["mode"] = json!("sandbox");
        assert_eq!(record(&mut w, &json!({"bullet": {"v": 320}}), 1.)["fresh"], json!([]));
        assert!(w.header["achievements"].get("bullet").is_none());
    }

    #[test]
    fn an_older_save_works_its_steps_out_quietly() {
        let mut w = World::workshop();
        w.header["mode"] = json!("normal");
        w.header.as_object_mut().unwrap().remove("achievements");
        let r = record(&mut w, &json!({"cities:2": {"v": 3}}), 100.);
        assert_eq!(r["fresh"], json!([]));
        assert!(r["got"]["cities:2"].is_object());
    }

    #[test]
    fn network_steps_from_the_world() {
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.]], "Utama", 111600.).unwrap();
        let f = work_out(&w, None, None);
        // no zones read: no city count, and a short line earns no network first
        assert!(f["cities"].is_null());
        assert_eq!(f["running"], json!(1));
        let met = f["met"].as_object().unwrap();
        assert!(!met.contains_key("long") && !met.contains_key("circle"));
    }
}
