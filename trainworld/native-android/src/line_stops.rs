// SPDX-License-Identifier: AGPL-3.0-only
//! A line's stops edited from its panel, as the web LineInspector does while its stops are edited
//! (ui/inspector/LineInspector.tsx `dotClick`, `afterStop`; ops `dropStop` and `stopPassed`): a
//! stop between the ends taken out keeping the track (`TrackApi::drop_stop`), and a station the
//! line passes between two stops made a stop (`TrackApi::stop_passed`, from `passed_stations`).
use super::{finance, World};
use jni::{objects::JClass, sys::{jdouble, jint, jlong, jstring}, JNIEnv};
use serde_json::{json, Value};

impl World {
    /// Web `dotClick`'s rule, without the ends (they shorten the line, not skip a stop): stop `i`
    /// can be passed when the line is on rails, has more than two stops, its trains do not turn
    /// back there, a loop keeps three, and the station is called at once.
    fn can_skip(&self, id: u32, i: usize) -> bool {
        let api = &self.api;
        let stops = api.line_stops(id);
        let n = stops.len();
        if api.is_bus(id) || api.is_ferry(id) || n <= 2 || i == 0 || i + 1 >= n {
            return false;
        }
        let looped = stops.first() == stops.last();
        if looped && n <= 3 {
            return false;
        }
        if api.line_reversals(id).iter().any(|&k| k as usize == i) {
            return false;
        }
        stops.iter().filter(|&&s| s == stops[i]).count() == 1
    }

    /// For the panel: per stop whether it can be passed, and the stations passed between stops
    /// (`[gap, node, name]`, gap k lying between stop k and stop k + 1).
    pub(super) fn line_stop_edits(&self, id: u32) -> Option<Value> {
        let api = &self.api;
        if !api.world().net.line_ok(id) {
            return None;
        }
        let n = api.line_stops(id).len();
        let rail = !api.is_bus(id) && !api.is_ferry(id);
        let skip: Vec<bool> = (0..n).map(|i| self.can_skip(id, i)).collect();
        let passed: Vec<Value> = if rail {
            api.passed_stations(id)
                .chunks_exact(2)
                .map(|p| json!([p[0] as usize, p[1] as u32, api.node_name(p[1] as u32)]))
                .collect()
        } else {
            vec![]
        };
        Some(json!({"rail": rail, "skip": skip, "passed": passed}))
    }

    /// Web `dropStop` (Lewati) or `stopPassed` (Berhenti di sini), booked as the other panel edits
    /// are: the trains a longer or shorter round trip needs are bought or sold at once.
    fn edit_line_stop(&mut self, id: u32, stop: Option<usize>, station: Option<u32>, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() {
            return Err("Jam permainan tidak valid.".into());
        }
        if !self.api.world().net.line_ok(id) {
            return Err("Lini sudah tidak tersedia.".into());
        }
        let name = match (stop, station) {
            (Some(i), _) => {
                if !self.can_skip(id, i) {
                    return Err("Lini harus tetap punya perhentian ini.".into());
                }
                self.api.node_name(self.api.line_stops(id)[i])
            }
            (None, Some(node)) => {
                let passed = self.api.passed_stations(id);
                if !passed.chunks_exact(2).any(|p| p[1] as u32 == node) {
                    return Err("Stasiun ini tidak dilewati lini.".into());
                }
                self.api.node_name(node)
            }
            _ => return Err("Perhentian tidak dikenal.".into()),
        };
        finance::tick(self, clock);
        self.api.set_now(clock);
        let r = match (stop, station) {
            (Some(i), _) => self.api.drop_stop(id, i as u32),
            (_, Some(node)) => self.api.stop_passed(id, node),
            _ => unreachable!(),
        };
        if r != 0 {
            let kinds = self.api.issue_kinds();
            return Err(if kinds.starts_with("Money:") {
                "Saldo tidak cukup untuk kereta tambahan yang dibutuhkan.".into()
            } else if stop.is_some() {
                "Lini harus tetap punya perhentian ini.".into()
            } else {
                "Tidak ada rel yang menghubungkan stasiun-stasiun ini kalau perhentian itu disisipkan. Cek sambungan relnya.".into()
            });
        }
        let charge = self.api.last_charge();
        let trains = self.api.last_train_charge();
        self.header["cash"] = self.api.cash().into();
        for (key, value) in [
            ("fleet", self.api.fleet()),
            ("fleetFast", self.api.fleet_fast()),
            ("fleetTram", self.api.fleet_tram()),
            ("fleetBus", self.api.fleet_bus()),
            ("fleetFerry", self.api.fleet_ferry()),
        ] {
            self.header[key] = value.into();
        }
        finance::book_charge(&mut self.header, clock, charge, trains);
        self.hour = i64::MIN;
        self.operating = None;
        Ok(json!({"name": name, "charge": charge, "stops": self.api.line_stops(id).len()}))
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineStops_read(env: JNIEnv, _: JClass, handle: jlong, id: jint) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }.and_then(|w| w.line_stop_edits(id.max(0) as u32));
    string(&env, v.unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineStops_skip(env: JNIEnv, _: JClass, handle: jlong, id: jint, index: jint, now: jdouble) -> jstring {
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => Err("Dunia belum siap.".to_string()),
        Some(_) if index < 0 => Err("Perhentian tidak dikenal.".to_string()),
        Some(w) => w.edit_line_stop(id.max(0) as u32, Some(index as usize), None, now),
    };
    string(&env, v.unwrap_or_else(|e| json!({"error": e})))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineStops_stop(env: JNIEnv, _: JClass, handle: jlong, id: jint, node: jint, now: jdouble) -> jstring {
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => Err("Dunia belum siap.".to_string()),
        Some(_) if node < 0 => Err("Stasiun tidak dikenal.".to_string()),
        Some(w) => w.edit_line_stop(id.max(0) as u32, None, Some(node as u32), now),
    };
    string(&env, v.unwrap_or_else(|e| json!({"error": e})))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn running(w: &World, id: u32) -> bool {
        w.api.lines_info().chunks_exact(22).any(|r| r[0] as u32 == id && r[3] == 1.)
    }

    #[test]
    fn a_middle_stop_is_passed_and_stopped_at_again_and_both_survive_a_save() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.], [12000., 0.]], "Uji", t).unwrap();
        let id = 0;
        let s0 = w.api.line_stops(id).to_vec();
        assert_eq!(s0.len(), 5);
        assert!(running(&w, id));
        let e = w.line_stop_edits(id).unwrap();
        assert_eq!(e["skip"], json!([false, true, true, true, false]), "the ends stay");
        assert_eq!(e["passed"], json!([]));

        // a middle stop out: the station is passed between stops 1 and 2, the line still runs
        let r = w.edit_line_stop(id, Some(2), None, t).unwrap();
        assert_eq!(r["stops"], 4);
        assert_eq!(w.api.line_stops(id), &[s0[0], s0[1], s0[3], s0[4]]);
        assert!(running(&w, id), "the service runs on");
        assert!(!w.api.line_trips(id, t, t + 7200.).is_empty());
        let e = w.line_stop_edits(id).unwrap();
        assert_eq!(e["passed"][0][0], 1);
        assert_eq!(e["passed"][0][1], s0[2]);
        assert_eq!(e["passed"][0][2], w.api.node_name(s0[2]));

        // kept through save and open
        let mut back = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(back.api.line_stops(id), &[s0[0], s0[1], s0[3], s0[4]]);
        assert_eq!(back.line_stop_edits(id).unwrap()["passed"], e["passed"]);

        // and the passed station stopped at again goes back in its place
        back.edit_line_stop(id, None, Some(s0[2]), t).unwrap();
        assert_eq!(back.api.line_stops(id), s0.as_slice());
        assert!(running(&back, id));
        let again = World::open(&back.save(t).unwrap()).unwrap();
        assert_eq!(again.api.line_stops(id), s0.as_slice());
        assert_eq!(again.line_stop_edits(id).unwrap()["passed"], json!([]));
    }

    #[test]
    fn ends_unknown_stations_and_two_stop_lines_are_refused() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.]], "Uji", t).unwrap();
        let id = 0;
        let s0 = w.api.line_stops(id).to_vec();
        assert!(w.edit_line_stop(id, Some(0), None, t).is_err(), "first stop");
        assert!(w.edit_line_stop(id, Some(2), None, t).is_err(), "last stop");
        assert!(w.edit_line_stop(id, Some(9), None, t).is_err());
        assert!(w.edit_line_stop(id, None, Some(s0[1]), t).is_err(), "already a stop");
        assert!(w.edit_line_stop(9, Some(1), None, t).is_err(), "no such line");
        assert_eq!(w.api.line_stops(id), s0.as_slice());
        // down to two: the last middle stop goes, then nothing more can
        w.edit_line_stop(id, Some(1), None, t).unwrap();
        assert_eq!(w.api.line_stops(id), &[s0[0], s0[2]]);
        assert_eq!(w.line_stop_edits(id).unwrap()["skip"], json!([false, false]));
        assert!(w.edit_line_stop(id, Some(0), None, t).is_err());
        assert!(w.edit_line_stop(id, Some(1), None, t).is_err());
        assert!(running(&w, id));
    }
}
