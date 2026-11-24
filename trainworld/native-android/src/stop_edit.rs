// SPDX-License-Identifier: AGPL-3.0-only
//! A line's stops edited on the map, as the web does while "Ubah perhentian" is on
//! (map/stopMarkers.ts, map/tools.ts `toggle`): every station has a marker in its tier from the
//! line tool's own search (`TrackApi::stop_tiers`), a tap adds the station (`add_stop`, the web's
//! `insertStop`) or takes every visit of it out (`setStops`, a loop closing again on its new first
//! stop, T-3050), and a hold closes, opens or turns a loop (`edit_line_loop`, T-3109).
use super::{finance, issue_text, World};
use jni::{objects::{JClass, JString}, sys::{jdouble, jint, jlong, jstring}, JNIEnv};
use serde_json::{json, Value};

/// `stop_tiers`' numbers by name, as the web's `tiersFrom`.
const TIERS: [&str; 9] = ["on", "route", "reroute", "cut", "reverse", "close", "open", "turn", "unturn"];
/// The web's `issues.insertStop`, said when a station has nowhere to go among the stops.
const NO_PLACE: &str = "Tidak ada rel yang menghubungkan stasiun-stasiun ini kalau perhentian itu disisipkan. Cek sambungan relnya.";
/// The web's `tools.stopFailed`.
const FAILED: &str = "Perhentian itu tidak bisa diubah. Coba lagi.";

impl World {
    /// The map's markers for line `id`: its stops, whether it is a loop, whether trams run it, and per
    /// station node `[node, tier]`, a "reverse" one with the name of the station its trains would
    /// turn back at ("" for a bare track end, web T-797). None for a bus or ferry line or no line.
    pub(super) fn stop_edit_state(&self, id: u32) -> Option<Value> {
        let api = &self.api;
        let net = &api.world().net;
        if !net.line_ok(id) || api.is_bus(id) || api.is_ferry(id) {
            return None;
        }
        let stops = api.line_stops(id);
        let tiers: Vec<Value> = api
            .stop_tiers(id)
            .chunks_exact(3)
            .map(|r| {
                let tier = TIERS.get(r[1] as usize).copied().unwrap_or("cut");
                if tier == "reverse" {
                    let at = (r[2] >= 0. && net.is_station(r[2] as u32)).then(|| api.node_name(r[2] as u32)).unwrap_or_default();
                    json!([r[0] as u32, tier, at])
                } else {
                    json!([r[0] as u32, tier])
                }
            })
            .collect();
        let closed = stops.len() >= 3 && stops.first() == stops.last();
        Some(json!({"stops": stops, "closed": closed, "tram": api.line_tram(id), "tiers": tiers}))
    }

    /// A tap on station `station`'s marker (web `tools.toggle` with no tier): put in where the line
    /// tool puts it, or every visit of it taken out. Booked as the panel's other stop edits.
    pub(super) fn toggle_line_stop(&mut self, id: u32, station: u32, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() {
            return Err("Jam permainan tidak valid.".into());
        }
        if !self.api.world().net.line_ok(id) {
            return Err("Line sudah tidak tersedia.".into());
        }
        if self.api.is_bus(id) || self.api.is_ferry(id) {
            return Err(FAILED.into());
        }
        if !self.api.world().net.is_station(station) {
            return Err("Stasiun tidak dikenal.".into());
        }
        let before = self.api.line_stops(id);
        let added = !before.contains(&station);
        let name = self.api.node_name(station);
        finance::tick(self, clock);
        self.api.set_now(clock);
        let r = if added {
            self.api.add_stop(id, station)
        } else {
            let n = before.len();
            let closed = n >= 3 && before[0] == before[n - 1];
            let mut stops: Vec<u32> = before.iter().copied().filter(|&s| s != station).collect();
            if closed && stops.len() >= 2 && stops[0] != stops[stops.len() - 1] {
                stops.push(stops[0]);
            }
            self.api.set_stops(id, &stops)
        };
        if r != 0 {
            if added && r == 2 {
                return Err(NO_PLACE.into());
            }
            let said = issue_text::describe(&self.api.issue_kinds());
            return Err(self.refusal(if said.is_empty() { FAILED } else { &said }));
        }
        let mut v = self.booked(clock, name, id);
        v["added"] = added.into();
        Ok(v)
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StopEditMode_state(env: JNIEnv, _: JClass, handle: jlong, id: jint) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }.and_then(|w| if id < 0 { None } else { w.stop_edit_state(id as u32) });
    string(&env, v.unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StopEditMode_toggle(env: JNIEnv, _: JClass, handle: jlong, id: jint, node: jint, now: jdouble) -> jstring {
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => Err("Dunia belum siap.".to_string()),
        Some(_) if node < 0 || id < 0 => Err("Stasiun tidak dikenal.".to_string()),
        Some(w) => w.toggle_line_stop(id as u32, node as u32, now),
    };
    string(&env, v.unwrap_or_else(|e| json!({"error": e})))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StopEditMode_loop(mut env: JNIEnv, _: JClass, handle: jlong, id: jint, action: JString, node: jint, now: jdouble) -> jstring {
    let action: String = env.get_string(&action).map(Into::into).unwrap_or_default();
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => Err("Dunia belum siap.".to_string()),
        Some(_) if node < 0 || id < 0 => Err("Stasiun tidak dikenal.".to_string()),
        Some(w) => w.edit_line_loop(id as u32, &action, node as u32, now),
    };
    string(&env, v.unwrap_or_else(|e| json!({"error": e})))
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: f64 = 111600.;

    fn tier_of(state: &Value, node: u32) -> String {
        state["tiers"].as_array().unwrap().iter().find(|r| r[0] == node).map(|r| r[1].as_str().unwrap().to_string()).unwrap_or_default()
    }

    fn running(w: &World, id: u32) -> bool {
        w.api.lines_info().chunks_exact(22).any(|r| r[0] as u32 == id && r[3] == 1.)
    }

    /// Every station's tier is the engine's `stop_tiers`, by name.
    fn same_as_engine(w: &World, id: u32) {
        let state = w.stop_edit_state(id).unwrap();
        let raw = w.api.stop_tiers(id);
        assert_eq!(state["tiers"].as_array().unwrap().len(), raw.len() / 3);
        for r in raw.chunks_exact(3) {
            assert_eq!(tier_of(&state, r[0] as u32), TIERS[r[1] as usize], "node {}", r[0]);
        }
        assert_eq!(state["stops"], json!(w.api.line_stops(id)));
    }

    #[test]
    fn a_tap_takes_a_stop_out_and_puts_it_back_and_both_survive_a_save() {
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.], [12000., 0.]], "Uji", T).unwrap();
        let id = 0;
        let s0 = w.api.line_stops(id).to_vec();
        assert_eq!(s0.len(), 5);
        same_as_engine(&w, id);
        let state = w.stop_edit_state(id).unwrap();
        assert!(s0.iter().all(|&s| tier_of(&state, s) == "on"), "{state}");
        assert_eq!(state["closed"], false);

        // the middle stop tapped: taken out, still on the line's path so a tap puts it back
        let r = w.toggle_line_stop(id, s0[2], T).unwrap();
        assert_eq!(r["added"], false);
        assert_eq!(r["stops"], 4);
        assert_eq!(w.api.line_stops(id), &[s0[0], s0[1], s0[3], s0[4]]);
        assert!(running(&w, id));
        same_as_engine(&w, id);
        assert_eq!(tier_of(&w.stop_edit_state(id).unwrap(), s0[2]), "route");

        let mut back = World::open(&w.save(T).unwrap()).unwrap();
        assert_eq!(back.api.line_stops(id), &[s0[0], s0[1], s0[3], s0[4]]);
        assert_eq!(tier_of(&back.stop_edit_state(id).unwrap(), s0[2]), "route");

        // tapped again: back in its place between its neighbours
        let r = back.toggle_line_stop(id, s0[2], T).unwrap();
        assert_eq!(r["added"], true);
        assert_eq!(back.api.line_stops(id), s0.as_slice());
        assert!(running(&back, id));
        let again = World::open(&back.save(T).unwrap()).unwrap();
        assert_eq!(again.api.line_stops(id), s0.as_slice());
        same_as_engine(&again, id);
    }

    #[test]
    fn a_station_on_no_track_of_the_line_is_cut_and_refused() {
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.]], "Uji", T).unwrap();
        w.build_path(&[[0., 20000.], [3000., 20000.]], "Jauh", T).unwrap();
        let id = 0;
        let s0 = w.api.line_stops(id).to_vec();
        let far = w.api.line_stops(1)[0];
        same_as_engine(&w, id);
        assert_eq!(tier_of(&w.stop_edit_state(id).unwrap(), far), "cut");
        let before = w.save(T).unwrap();
        assert_eq!(w.toggle_line_stop(id, far, T).unwrap_err(), NO_PLACE);
        assert_eq!(w.api.line_stops(id), s0.as_slice());
        assert_eq!(w.save(T).unwrap(), before, "nothing changed");
        assert!(w.toggle_line_stop(9, s0[1], T).is_err(), "no such line");
    }

    #[test]
    #[ignore]
    fn stop_tiers_on_the_krl_world_are_quick() {
        let bytes = include_bytes!("../android-lib/src/main/assets/world-native/scenarios/jak_krl.save");
        let w = World::open(bytes).unwrap();
        let ids: Vec<u32> = w.api.lines_info().chunks_exact(22).map(|r| r[0] as u32).collect();
        for id in ids {
            let t = std::time::Instant::now();
            let Some(s) = w.stop_edit_state(id) else { continue };
            let mut n = std::collections::BTreeMap::new();
            for r in s["tiers"].as_array().unwrap() { *n.entry(r[1].as_str().unwrap().to_string()).or_insert(0) += 1; }
            println!("{} {} stops {} ms {:?}", w.api.line_name(id), s["stops"].as_array().unwrap().len(), t.elapsed().as_millis(), n);
        }
    }
}
