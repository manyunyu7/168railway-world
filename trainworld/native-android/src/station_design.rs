// SPDX-License-Identifier: AGPL-3.0-only
//! Designing a station on the map (StationDesign.kt): the station view's own controls as the web
//! has them (StationInspector.tsx `StationDetails`: the platform length stepper and the platform
//! tracks, StationSides.tsx T-3332; StationUpgrades.tsx: the upgrades and the building tool,
//! buildingDraw.ts T-2218), worked out on a fork of the world for the map's preview and price,
//! then applied to the world itself once confirmed.
//!
//! Coordinates crossing this boundary are in the world frame (the map's plane), so the map draws
//! the outline where the platforms are. A fork made by save and open may number nodes and
//! buildings anew, so a request names its station by id and place (`at`), and the building as
//! "the station's own".

use super::World;
use jni::{
    objects::{JClass, JString},
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::f64::consts::{PI, TAU};
use trainworld_sim::track::building::platform_line;
use trainworld_sim::track::upgrades::{PASSING, TERMINAL};

/// Web PLATFORM_MIN_M / PLATFORM_MAX_M (boarding.ts) and TRAM_PLATFORM (ui.ts): the lengths a
/// player can set, a car (20 m) apart.
const RAIL_M: [u16; 2] = [60, 400];
const TRAM_M: [u16; 2] = [20, 80];
const STEP_M: u16 = 20;
/// Web buildings.ts OFFER_PAD_M and complexes.ts BOX_TOUCH_M: the offered outline's margin.
const OFFER_PAD_M: f64 = 15.;
const BOX_TOUCH_M: f64 = 16.;

/// Web StationSides `upAxis`: the station's axis turned to point up the screen.
fn up_axis(heading: f64) -> f64 {
    let (s, c) = heading.sin_cos();
    if s > 1e-6 || (s.abs() <= 1e-6 && c > 0.) { heading } else { heading + PI }
}

/// Web `compass`: the nearest of the four words.
fn compass(h: f64) -> &'static str {
    let a = h.rem_euclid(TAU);
    if !(PI / 4. ..7. * PI / 4.).contains(&a) {
        "east"
    } else if a < 3. * PI / 4. {
        "north"
    } else if a < 5. * PI / 4. {
        "west"
    } else {
        "south"
    }
}

/// The station named by `node`, or the station standing at `at` when the fork numbered it anew.
fn resolve(w: &World, node: u32, at: &Value) -> Option<u32> {
    let net = &w.api.world().net;
    let here = |n: u32| net.node_ok(n) && net.is_station(n);
    let Some([x, y]) = at.as_array().and_then(|a| Some([a.first()?.as_f64()?, a.get(1)?.as_f64()?])) else {
        return here(node).then_some(node);
    };
    let close = |n: u32| (net.node_x[n as usize] - x).hypot(net.node_y[n as usize] - y) < 0.5;
    if (node as usize) < net.node_count() && here(node) && close(node) {
        return Some(node);
    }
    (0..net.node_count() as u32).find(|&n| here(n) && close(n))
}

/// The building the station belongs to, if any.
fn own_building(w: &World, node: u32) -> Option<u32> {
    let net = &w.api.world().net;
    (0..net.buildings.len() as u32)
        .find(|&b| net.buildings[b as usize].is_some() && w.api.building_members(b).contains(&node))
}

/// Web `offeredRectangle` for one station: the smallest rectangle round its box along its
/// platform, OFFER_PAD_M out; flat x, y pairs in the world frame.
fn offered(w: &World, node: u32) -> Vec<f64> {
    let net = &w.api.world().net;
    let i = node as usize;
    let line = platform_line(net, node);
    let (first, last) = match (line.first(), line.last()) {
        (Some(a), Some(b)) if (b[0] - a[0]).hypot(b[1] - a[1]) > 1e-6 => (*a, *b),
        _ => {
            let h = net.node_heading[i];
            let d = net.node_platform[i] as f64 / 2. * net.k_at(net.node_y[i]);
            let (x, y) = (net.node_x[i], net.node_y[i]);
            ([x - h.cos() * d, y - h.sin() * d], [x + h.cos() * d, y + h.sin() * d])
        }
    };
    let a = (last[1] - first[1]).atan2(last[0] - first[0]);
    let (s, c) = a.sin_cos();
    let pts: Vec<[f64; 2]> = if line.len() >= 2 { line.clone() } else { vec![first, last] };
    let (mut u0, mut u1, mut v0, mut v1) = (f64::MAX, f64::MIN, f64::MAX, f64::MIN);
    for p in &pts {
        let (u, v) = (p[0] * c + p[1] * s, -p[0] * s + p[1] * c);
        u0 = u0.min(u);
        u1 = u1.max(u);
        v0 = v0.min(v);
        v1 = v1.max(v);
    }
    let pad = (BOX_TOUCH_M / 2. + OFFER_PAD_M) * net.k_at(net.node_y[i]);
    let (u0, u1, v0, v1) = (u0 - pad, u1 + pad, v0 - pad, v1 + pad);
    [[u0, v0], [u1, v0], [u1, v1], [u0, v1]]
        .iter()
        .flat_map(|&[u, v]| [u * c - v * s, u * s + v * c])
        .collect()
}

/// What the design panel shows for station `node`: its platform, the tracks it stands on, its
/// upgrades as offered and its building (or the outline a new one starts from).
pub(crate) fn state(w: &World, node: u32, at: &Value) -> Result<Value, String> {
    let node = resolve(w, node, at).ok_or("Stasiun belum tersedia.")?;
    let api = &w.api;
    let net = &api.world().net;
    let i = node as usize;
    let port = net.node_berth[i].is_some();
    let tram = net.node_price_kind(node).spec().family != 0;
    let [min, max] = if tram { TRAM_M } else { RAIL_M };
    // web StationSides: left and right as the map shows the station turned to point up the screen;
    // trains keep right (or left), so those at a platform on the left track run against the axis
    let double = net.node_tracks(node) == 2;
    let up = up_axis(net.node_heading[i]);
    let left = (if api.right_hand() { up + PI } else { up }).rem_euclid(TAU);
    let right = (left + PI).rem_euclid(TAU);
    let one = api.station_one(node);
    let now = api.station_one_heading(node);
    let serves = |h: f64| one == 1 && now.is_finite() && (now - h).cos() > 0.;
    let building = own_building(w, node).and_then(|b| {
        let hall = net.buildings[b as usize].as_ref()?;
        let corners: Vec<f64> = hall.corners.iter().flat_map(|p| [p[0], p[1]]).collect();
        Some(json!({"name": hall.name, "corners": corners, "area": api.building_area(b),
            "members": api.building_members(b).len()}))
    });
    Ok(json!({
        "node": node, "name": api.node_name(node), "at": [net.node_x[i], net.node_y[i]],
        "built": net.node_built[i], "port": port, "tram": tram,
        "length": net.node_platform[i], "min": min, "max": max, "step": STEP_M,
        "double": double, "one": one, "left": left, "right": right,
        "leftWay": compass(left), "rightWay": compass(right),
        "onLeft": serves(left), "onRight": serves(right),
        "tracksCost": api.station_tracks_cost(node),
        "upgrades": super::station_facilities::offered(w, node),
        "building": building,
        "offer": if port || tram { Value::Null } else { json!(offered(w, node)) },
        "groundScale": net.k_at(net.node_y[i]),
    }))
}

/// A refused change, in the panel's words (web issues.*).
fn refusal(kinds: &str) -> String {
    match kinds.split(['\n', ':']).next().unwrap_or("") {
        "Money" => "Saldo tidak cukup untuk perubahan ini.".into(),
        "OneTrackHere" => "Peron di satu rel butuh rel yang menembus stasiun.".into(),
        "OneTrackStop" => "Di sini kereta hanya berhenti untuk arah sebaliknya.".into(),
        "PlatformJoint" => "Peron akan sampai ke ujung potongan rel. Pindahkan atau perpendek peronnya.".into(),
        "PlatformTooLong" => "Rel di sini terlalu pendek untuk peron ini.".into(),
        "PlatformNotLevel" => "Peron akan berada di tanjakan.".into(),
        "PlatformCrossing" => "Ada rel lain yang bersilangan di sini pada tingkat yang sama.".into(),
        "StationCurveTight" => "Rel di sini menikung terlalu tajam untuk stasiun.".into(),
        "StationOnJunction" => "Stasiun tidak bisa berada di persimpangan.".into(),
        k if k.starts_with("Building") => super::station_facilities::building_issue(kinds),
        _ => crate::issue_text::describe(kinds),
    }
}

/// One change to the station, booked as the other panel edits are: the money it took off the
/// balance (trains included), 0 when nothing changed.
fn charged(w: &mut World, clock: f64, change: impl FnOnce(&mut trainworld_sim::track::wasm::TrackApi) -> u32) -> Result<f64, String> {
    super::finance::tick(w, clock);
    w.api.set_now(clock);
    let before = w.api.cash();
    if change(&mut w.api) != 0 {
        return Err(refusal(&w.api.issue_kinds()));
    }
    let charge = (before - w.api.cash()).max(0.);
    let trains = if charge > 0. { w.api.last_train_charge() } else { 0. };
    w.header["cash"] = w.api.cash().into();
    super::finance::book_charge(&mut w.header, clock, charge, trains);
    w.hour = i64::MIN;
    w.operating = None;
    Ok(charge)
}

/// Apply the panel's changes in order: `{"at":[x,y], "ops":[…]}` with ops
/// `{"op":"length","m"}`, `{"op":"tracks","one"}` (single track: with or without a passing
/// loop; double: both tracks), `{"op":"way","heading"}` (double track: one track's platform),
/// `{"op":"upgrade","bit","on"}`, `{"op":"building","corners":[x,y,…]}` (the station's
/// building reshaped, or a new one) and `{"op":"removeBuilding"}`. Returns what it cost.
pub(crate) fn apply(w: &mut World, node: u32, time: f64, request: &Value) -> Result<Value, String> {
    let node = resolve(w, node, &request["at"]).ok_or("Stasiun belum tersedia.")?;
    if !time.is_finite() {
        return Err("Jam permainan tidak valid.".into());
    }
    let clock = w.native_clock(time);
    let mut total = 0.;
    let mut changed = false;
    for op in request["ops"].as_array().into_iter().flatten() {
        let charge = match op["op"].as_str().unwrap_or("") {
            "length" => {
                let st = w.api.world().net.station_data(node).ok_or("Stasiun belum tersedia.")?;
                let tram = w.api.world().net.node_price_kind(node).spec().family != 0;
                let [min, max] = if tram { TRAM_M } else { RAIL_M };
                let m = op["m"].as_u64().map(|m| m.clamp(min as u64, max as u64) as u16).ok_or("Panjang peron tidak valid.")?;
                if m == st.platform {
                    continue;
                }
                charged(w, clock, |api| api.set_station_props(node, m, st.name.clone()))?
            }
            "tracks" => {
                let one = op["one"].as_bool().ok_or("Pilihan rel tidak valid.")?;
                charged(w, clock, |api| api.set_station_tracks(node, one))?
            }
            "way" => {
                let h = op["heading"].as_f64().filter(|h| h.is_finite()).ok_or("Arah tidak valid.")?;
                charged(w, clock, |api| api.set_station_way(node, h))?
            }
            "upgrade" => {
                let bit = op["bit"].as_u64().and_then(|b| u8::try_from(b).ok()).ok_or("Fasilitas tidak valid.")?;
                let on = op["on"].as_bool().unwrap_or(false);
                let offered = super::station_facilities::offered(w, node);
                // a one-track station has no terminal or passing tracks to take away (web T-3332)
                if !on && !offered.iter().any(|v| v["bit"] == bit && v["on"] == true) && matches!(bit, TERMINAL | PASSING) {
                    continue;
                }
                let r = super::station_facilities::edit(w, node, bit, on, clock).map_err(String::from)?;
                if r["changed"] != true {
                    continue;
                }
                r["charge"].as_f64().unwrap_or(0.)
            }
            "building" | "removeBuilding" => {
                let net = &w.api.world().net;
                let i = node as usize;
                let (x, y, k) = (net.node_x[i], net.node_y[i], net.k_at(net.node_y[i]));
                let id = own_building(w, node);
                let request = if op["op"] == "removeBuilding" {
                    let Some(id) = id else { continue };
                    json!({"action": "remove", "id": id})
                } else {
                    let flat = op["corners"].as_array().ok_or("Sudut bangunan tidak valid.")?;
                    let points: Vec<Value> = flat
                        .chunks_exact(2)
                        .map(|p| json!([(p[0].as_f64().unwrap_or(f64::NAN) - x) / k, (p[1].as_f64().unwrap_or(f64::NAN) - y) / k]))
                        .collect();
                    let name = id
                        .and_then(|b| net.buildings[b as usize].as_ref().map(|h| h.name.clone()))
                        .unwrap_or_else(|| w.api.node_name(node));
                    let mut r = json!({"action": "building", "points": points, "name": name});
                    if let Some(id) = id {
                        r["id"] = id.into();
                    }
                    r
                };
                let r = super::station_facilities::station_structure(w, node, clock, &request)?;
                r["charge"].as_f64().unwrap_or(0.)
            }
            _ => return Err("Perubahan stasiun tidak dikenal.".into()),
        };
        total += charge;
        changed = true;
    }
    Ok(json!({"changed": changed, "charge": total, "cash": w.api.cash(), "node": node}))
}

fn request(env: &mut JNIEnv, text: &JString) -> Option<Value> {
    env.get_string(text).ok().and_then(|v| serde_json::from_str(&String::from(v)).ok())
}

fn reply(env: JNIEnv, value: Result<Value, String>) -> jstring {
    let v = value.unwrap_or_else(|e| json!({"error": e}));
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationDesign_state(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: jint,
    at: JString,
) -> jstring {
    let at = request(&mut env, &at).unwrap_or(Value::Null);
    let result = unsafe { (handle as *const World).as_ref() }
        .ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|w| state(w, node as u32, &at));
    reply(env, result)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationDesign_apply(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: jint,
    clock: jdouble,
    command: JString,
) -> jstring {
    let req = request(&mut env, &command);
    let result = unsafe { (handle as *mut World).as_mut() }
        .zip(req)
        .ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|(w, r)| apply(w, node as u32, clock, &r));
    reply(env, result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use trainworld_sim::track::upgrades::WAITING_ROOM;

    fn station(w: &World) -> u32 {
        let net = &w.api.world().net;
        (0..net.node_count() as u32).find(|&n| net.is_station(n)).unwrap()
    }

    #[test]
    fn a_fork_previews_the_price_the_world_then_pays() {
        let clock = 111600.;
        let mut w = World::workshop();
        w.build_service([0., 0.], [2300., 0.], "Rancang", clock - 86400.).unwrap();
        let node = station(&w);
        let s = state(&w, node, &Value::Null).unwrap();
        assert_eq!(s["min"], 60);
        assert!(s["building"].is_null());
        let offer: Vec<f64> = s["offer"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        assert_eq!(offer.len(), 8);
        let at = s["at"].clone();
        let longer = s["length"].as_u64().unwrap() + 40;
        let ops = json!({"at": at, "ops": [
            {"op": "length", "m": longer},
            {"op": "upgrade", "bit": WAITING_ROOM, "on": true},
            {"op": "building", "corners": offer},
        ]});
        // the preview on a fork: priced, the world itself untouched
        super::super::finance::tick(&mut w, clock);
        let cash = w.api.cash();
        let mut fork = w.edit_candidate(clock).unwrap();
        let preview = apply(&mut fork, 9999, clock, &ops).unwrap();
        assert!(preview["charge"].as_f64().unwrap() > 0., "{preview}");
        let forked = state(&fork, 9999, &at).unwrap();
        assert_eq!(forked["length"].as_u64().unwrap(), longer);
        assert!(forked["building"]["area"].as_f64().unwrap() > 1000.);
        assert_eq!(w.api.cash(), cash);
        assert!(state(&w, node, &at).unwrap()["building"].is_null());
        // confirmed: the world pays what the preview said, and books it
        let done = apply(&mut w, node, clock, &ops).unwrap();
        assert!((done["charge"].as_f64().unwrap() - preview["charge"].as_f64().unwrap()).abs() < 1e-6);
        assert!((cash - w.api.cash() - done["charge"].as_f64().unwrap()).abs() < 1e-6);
        let booked: f64 = w.header["ledger"].as_array().unwrap().iter().map(|e| e["build"].as_f64().unwrap_or(0.)).sum();
        assert!(booked > 0.);
        let after = state(&w, node, &at).unwrap();
        assert_eq!(after["length"].as_u64().unwrap(), longer);
        assert!(after["building"]["corners"].as_array().unwrap().len() == 8);
        // reshaped, then taken down; nothing comes back
        let mut moved = offer.clone();
        moved[2] += 10.;
        moved[4] += 10.;
        apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "building", "corners": moved}]})).unwrap();
        let reshaped = state(&w, node, &at).unwrap();
        assert!((reshaped["building"]["corners"][2].as_f64().unwrap() - moved[2]).abs() < 1e-6);
        let cash = w.api.cash();
        let gone = apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "removeBuilding"}]})).unwrap();
        assert_eq!(gone["charge"], 0.);
        assert_eq!(w.api.cash(), cash);
        assert!(state(&w, node, &at).unwrap()["building"].is_null());
        // the same length again changes nothing; a refused step says why and keeps the world
        let same = apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "length", "m": longer}]})).unwrap();
        assert_eq!(same["changed"], false);
        assert!(apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "nope"}]})).is_err());
    }

    #[test]
    fn sides_follow_the_web_and_single_track_switches_its_loop() {
        let clock = 111600.;
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [2300., 0.], [4600., 0.]], "Sisi", clock - 86400.).unwrap();
        let net = &w.api.world().net;
        let node = (0..net.node_count() as u32).find(|&n| net.is_station(n) && net.node_ports[n as usize].n == 2).unwrap();
        let s = state(&w, node, &Value::Null).unwrap();
        assert_eq!(s["one"], 0);
        assert_eq!(s["onLeft"], false);
        assert_eq!(s["onRight"], false);
        // an east-west station: its axis points east, right-hand traffic on the left track runs west
        let words = [s["leftWay"].as_str().unwrap(), s["rightWay"].as_str().unwrap()];
        assert!(words.contains(&"east") && words.contains(&"west"), "{s}");
        let at = s["at"].clone();
        if s["double"] == true {
            let r = apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "way", "heading": s["left"]}]}));
            r.unwrap();
            let t = state(&w, node, &at).unwrap();
            assert_eq!(t["one"], 1);
            assert_eq!(t["onLeft"], true);
            assert_eq!(t["onRight"], false);
        } else {
            let r = apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "tracks", "one": true}]}));
            if r.is_ok() {
                assert_eq!(state(&w, node, &at).unwrap()["one"], 2);
            }
        }
        // back to both tracks
        apply(&mut w, node, clock, &json!({"at": at, "ops": [{"op": "tracks", "one": false}]})).unwrap();
        assert_eq!(state(&w, node, &at).unwrap()["one"], 0);
        assert_eq!(compass(0.), "east");
        assert_eq!(compass(PI / 2.), "north");
        assert_eq!(compass(PI), "west");
        assert_eq!(compass(-PI / 2.), "south");
        assert_eq!(compass(up_axis(PI)), "east");
        assert_eq!(compass(up_axis(-PI / 2.)), "north");
    }

    /// StationDesign.kt copies the world once on opening and forks that copy on its worker for
    /// every tap: the copy's fork must price and draw a change exactly as a fork of the world.
    #[test]
    fn a_fork_of_the_opening_copy_previews_as_a_fork_of_the_world() {
        let clock = 111600.;
        let mut w = World::workshop();
        w.build_service([0., 0.], [2300., 0.], "Rancang", clock - 86400.).unwrap();
        let node = station(&w);
        let s = state(&w, node, &Value::Null).unwrap();
        let at = s["at"].clone();
        let longer = s["length"].as_u64().unwrap() + 40;
        let ops = json!({"at": at, "ops": [
            {"op": "length", "m": longer},
            {"op": "upgrade", "bit": WAITING_ROOM, "on": true},
        ]});
        let mut direct = w.edit_candidate(clock).unwrap();
        let copy = w.edit_candidate(clock).unwrap();
        let mut via = copy.edit_candidate(clock).unwrap();
        let a = apply(&mut direct, 9999, clock, &ops).unwrap();
        let b = apply(&mut via, 9999, clock, &ops).unwrap();
        assert_eq!(a, b);
        assert_eq!(direct.api.cash(), via.api.cash());
        assert_eq!(state(&direct, 9999, &at).unwrap(), state(&via, 9999, &at).unwrap());
        assert_eq!(direct.geometry(), via.geometry());
        // the copy itself stays as opened, for the next tap
        assert_eq!(state(&copy, 9999, &at).unwrap()["length"], s["length"]);
    }
}
