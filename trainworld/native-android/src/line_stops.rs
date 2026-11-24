// SPDX-License-Identifier: AGPL-3.0-only
//! A line's stops edited from its panel, as the web LineInspector does while its stops are edited
//! (ui/inspector/LineInspector.tsx `dotClick`, `afterStop`; ops `dropStop` and `stopPassed`): a
//! stop between the ends taken out keeping the track (`TrackApi::drop_stop`), and a station the
//! line passes between two stops made a stop (`TrackApi::stop_passed`, from `passed_stations`).
use super::{finance, World};
use jni::{objects::{JClass, JString}, sys::{jdouble, jint, jlong, jstring}, JNIEnv};
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
        Some(json!({"rail": rail, "skip": skip, "passed": passed, "nodes": api.line_stops(id), "loop": self.line_loop_edits(id)}))
    }

    /// The loop edits the web's stop markers offer on a long press (map/stopMarkers.ts `LOOP_TIERS`,
    /// T-798, T-3021, T-3109): `closed` the line is a loop, `close` it can be closed (its last stop
    /// on to its first), `turn` the stops before a loop of track where its end can turn round it,
    /// `unturn` the stop where a line so turned opens again. Rail lines only.
    fn line_loop_edits(&self, id: u32) -> Value {
        let api = &self.api;
        let stops = api.line_stops(id);
        let closed = stops.len() >= 3 && stops.first() == stops.last();
        if api.is_bus(id) || api.is_ferry(id) {
            return json!({"closed": closed, "close": false, "turn": [], "unturn": null});
        }
        let tiers = api.loop_tiers(id);
        let at = |t: f64| tiers.chunks_exact(2).filter(move |p| p[1] == t).map(|p| p[0] as u32);
        let turn: Vec<u32> = at(7.).collect();
        json!({"closed": closed, "close": at(5.).next().is_some(), "turn": turn, "unturn": at(8.).next()})
    }

    /// The stops a loop edit leaves, as the web line tool's `toggle` works them out for a tier
    /// (map/tools.ts): `None` when the engine's own op does it (`turn_end`, `open_loop`).
    fn loop_stops(&self, id: u32, action: &str, station: u32) -> Result<Option<Vec<u32>>, String> {
        let stops = self.api.line_stops(id);
        let n = stops.len();
        let closed = n >= 3 && stops[0] == stops[n - 1];
        let edits = self.line_loop_edits(id);
        match action {
            "close" => {
                if closed || !edits["close"].as_bool().unwrap_or(false) || (station != stops[0] && station != stops[n - 1]) {
                    return Err("Tidak bisa membuat putaran di sini.".into());
                }
                let mut s = stops.to_vec();
                s.push(stops[0]);
                Ok(Some(s))
            }
            "open" => {
                if !closed {
                    return Err("Line ini bukan putaran.".into());
                }
                let cyc = &stops[..n - 1];
                let distinct = cyc.iter().collect::<std::collections::HashSet<_>>().len() == n - 1;
                match cyc.iter().position(|&s| s == station) {
                    // T-3118: a ring opened at one of its stops ends there
                    Some(k) if distinct => Ok(Some(cyc[k + 1..].iter().chain(&cyc[..=k]).copied().collect())),
                    _ => Ok(None),
                }
            }
            "turn" => {
                if !edits["turn"].as_array().is_some_and(|t| t.iter().any(|v| v.as_u64() == Some(station as u64))) {
                    return Err("Tidak bisa membuat putaran di sini.".into());
                }
                Ok(None)
            }
            "unturn" => {
                if edits["unturn"].as_u64() != Some(station as u64) {
                    return Err("Tidak bisa menghapus putaran di sini.".into());
                }
                Ok(None)
            }
            _ => Err("Perubahan putaran tidak dikenal.".into()),
        }
    }

    /// A loop closed, opened or turned (web `tools.loopStop`), booked as the other stop edits.
    pub(super) fn edit_line_loop(&mut self, id: u32, action: &str, station: u32, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() {
            return Err("Jam permainan tidak valid.".into());
        }
        if !self.api.world().net.line_ok(id) {
            return Err("Line sudah tidak tersedia.".into());
        }
        let stops = self.loop_stops(id, action, station)?;
        let name = self.api.node_name(station);
        finance::tick(self, clock);
        self.api.set_now(clock);
        let r = match (action, stops) {
            (_, Some(s)) => self.api.set_stops(id, &s),
            ("turn", None) => self.api.turn_end(id, station),
            (_, None) => self.api.open_loop(id),
        };
        if r != 0 {
            return Err(self.refusal("Rel tidak membentuk putaran yang bisa dilalui line ini."));
        }
        Ok(self.booked(clock, name, id))
    }

    pub(super) fn refusal(&self, other: &str) -> String {
        if self.api.issue_kinds().starts_with("Money:") {
            "Saldo tidak cukup untuk kereta tambahan yang dibutuhkan.".into()
        } else {
            other.into()
        }
    }

    /// The header's cash and fleets after a line edit, its charge booked; the panel's answer.
    pub(super) fn booked(&mut self, clock: f64, name: String, id: u32) -> Value {
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
        json!({"name": name, "charge": charge, "stops": self.api.line_stops(id).len()})
    }

    /// Web `dropStop` (Lewati) or `stopPassed` (Berhenti di sini), booked as the other panel edits
    /// are: the trains a longer or shorter round trip needs are bought or sold at once.
    /// Every station the line passes without stopping made a stop, one after another; those the
    /// engine refuses are left passed. Booked as one edit.
    fn stop_at_all(&mut self, id: u32, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() { return Err("Jam permainan tidak valid.".into()); }
        if !self.api.world().net.line_ok(id) { return Err("Line sudah tidak tersedia.".into()); }
        finance::tick(self, clock);
        self.api.set_now(clock);
        let mut added = 0;
        let mut tried = std::collections::HashSet::new();
        loop {
            let passed = self.api.passed_stations(id);
            let Some(node) = passed.chunks_exact(2).map(|p| p[1] as u32).find(|n| !tried.contains(n)) else { break };
            tried.insert(node);
            if self.api.stop_passed(id, node) == 0 { added += 1; }
        }
        if added == 0 { return Err("Tidak ada stasiun yang dilewati line ini.".into()); }
        let mut v = self.booked(clock, format!("{added} stasiun"), id);
        v["added"] = json!(added);
        Ok(v)
    }

    fn edit_line_stop(&mut self, id: u32, stop: Option<usize>, station: Option<u32>, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() {
            return Err("Jam permainan tidak valid.".into());
        }
        if !self.api.world().net.line_ok(id) {
            return Err("Line sudah tidak tersedia.".into());
        }
        let name = match (stop, station) {
            (Some(i), _) => {
                if !self.can_skip(id, i) {
                    return Err("Line harus tetap punya perhentian ini.".into());
                }
                self.api.node_name(self.api.line_stops(id)[i])
            }
            (None, Some(node)) => {
                let passed = self.api.passed_stations(id);
                if !passed.chunks_exact(2).any(|p| p[1] as u32 == node) {
                    return Err("Stasiun ini tidak dilewati line.".into());
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
            return Err(self.refusal(if stop.is_some() {
                "Line harus tetap punya perhentian ini."
            } else {
                "Tidak ada rel yang menghubungkan stasiun-stasiun ini kalau perhentian itu disisipkan. Cek sambungan relnya."
            }));
        }
        Ok(self.booked(clock, name, id))
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineStops_stopAll(env: JNIEnv, _: JClass, handle: jlong, id: jint, now: jdouble) -> jstring {
    let value = unsafe { (handle as *mut World).as_mut() }
        .map(|w| w.stop_at_all(id as u32, now).unwrap_or_else(|e| json!({"error": e})))
        .unwrap_or(Value::Null);
    string(&env, value)
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

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineStops_loop(mut env: JNIEnv, _: JClass, handle: jlong, id: jint, action: JString, node: jint, now: jdouble) -> jstring {
    let action: String = env.get_string(&action).map(Into::into).unwrap_or_default();
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => Err("Dunia belum siap.".to_string()),
        Some(_) if node < 0 => Err("Stasiun tidak dikenal.".to_string()),
        Some(w) => w.edit_line_loop(id.max(0) as u32, &action, node as u32, now),
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
    fn stop_at_all_passed_stations() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.], [12000., 0.]], "Uji", t).unwrap();
        let id = 0;
        w.edit_line_stop(id, Some(1), None, t).unwrap();
        w.edit_line_stop(id, Some(2), None, t).unwrap();
        let before = w.api.line_stops(id).len();
        let r = w.stop_at_all(id, t).unwrap();
        assert!(r["added"].as_i64().unwrap() >= 1);
        assert!(w.api.line_stops(id).len() > before);
        assert!(w.stop_at_all(id, t).is_err());
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

#[cfg(test)]
mod loop_tests {
    use super::*;

    const T: f64 = 111600.;

    fn node(w: &mut World, x: f64, y: f64) -> u32 {
        let n = w.api.new_node_id();
        assert_eq!(w.api.set_node(n, x, y, 0, false), 0, "{}", w.api.issue_kinds());
        n
    }

    fn edge(w: &mut World, a: u32, b: u32, pis: &[(f64, f64)]) {
        let e = w.api.new_edge_id();
        let pis: Vec<f64> = pis.iter().flat_map(|&(x, y)| [x, y, 0., 0.]).collect();
        assert_eq!(w.api.set_edge(e, a, b, 2, &pis), 0, "{}", w.api.issue_kinds());
    }

    fn station(w: &mut World, n: u32) {
        assert_eq!(w.api.set_station(n, 200, format!("S{n}")), 0, "{}", w.api.issue_kinds());
    }

    /// Four stations on a ring of track 3 km round the middle, each quarter one curve.
    fn ring() -> (World, Vec<u32>) {
        let mut w = World::workshop();
        let r = 3000.;
        let at: Vec<(f64, f64)> = (0..4).map(|k| { let a = k as f64 * std::f64::consts::FRAC_PI_2; (r * a.cos(), r * a.sin()) }).collect();
        let ns: Vec<u32> = at.iter().map(|&(x, y)| node(&mut w, x, y)).collect();
        for k in 0..4 {
            let a = (k as f64 + 0.5) * std::f64::consts::FRAC_PI_2;
            let pi = (r * std::f64::consts::SQRT_2 * a.cos(), r * std::f64::consts::SQRT_2 * a.sin());
            edge(&mut w, ns[k], ns[(k + 1) % 4], &[pi]);
        }
        for &n in &ns { station(&mut w, n); }
        assert_eq!(w.api.construct_all(), 0, "{}", w.api.issue_kinds());
        (w, ns)
    }

    /// The sim's teardrop (track/wasm/turn_tests.rs): a stem A - B - M - J and a loop at J through K.
    fn teardrop() -> (World, [u32; 4]) {
        let mut w = World::workshop();
        let a = node(&mut w, -4000., 0.);
        let b = node(&mut w, -2000., 0.);
        let m = node(&mut w, 0., 0.);
        let j = node(&mut w, 1000., 0.);
        let k = node(&mut w, 2000., 750.);
        edge(&mut w, a, b, &[]);
        edge(&mut w, b, m, &[]);
        edge(&mut w, m, j, &[]);
        edge(&mut w, j, k, &[(1200., 0.)]);
        edge(&mut w, k, j, &[(2800., 1500.), (3500., -750.), (1400., 0.)]);
        for n in [a, b, m, k] { station(&mut w, n); }
        assert_eq!(w.api.construct_all(), 0, "{}", w.api.issue_kinds());
        (w, [a, b, m, k])
    }

    fn line(w: &mut World, stops: &[u32]) -> u32 {
        let id = w.api.new_line_id();
        assert_eq!(w.api.set_line_with_trains(id, stops, 4, 3, 2, 30., 180., 4, 0, "Uji".into()), 0, "{}", w.api.issue_kinds());
        assert_eq!(w.api.order_stops(id, stops), 0, "{}", w.api.issue_kinds());
        id
    }

    fn running(w: &World, id: u32) -> bool {
        w.api.lines_info().chunks_exact(22).any(|r| r[0] as u32 == id && r[3] == 1.) && !w.api.line_trips(id, T, T + 7200.).is_empty()
    }

    #[test]
    fn a_line_on_a_ring_closes_into_a_loop_runs_and_opens_again_through_a_save() {
        let (mut w, ns) = ring();
        let id = line(&mut w, &ns);
        assert!(running(&w, id));
        let e = w.line_stop_edits(id).unwrap();
        assert_eq!(e["loop"]["closed"], false);
        assert_eq!(e["loop"]["close"], true, "{e}");
        assert!(w.edit_line_loop(id, "close", ns[1], T).is_err(), "only at an end");
        w.edit_line_loop(id, "close", ns[0], T).unwrap();
        let s = w.api.line_stops(id);
        assert_eq!(s.first(), s.last());
        assert_eq!(&s[..4], ns.as_slice());
        assert_eq!(w.api.line_loop(id), 1);
        assert!(running(&w, id), "the loop runs");
        assert_eq!(w.line_stop_edits(id).unwrap()["loop"]["closed"], true);

        // kept through save and open
        let mut back = World::open(&w.save(T).unwrap()).unwrap();
        assert_eq!(back.api.line_stops(id), s);
        assert_eq!(back.api.line_loop(id), 1);
        assert!(running(&back, id));

        // opened at its first stop: the ring ends there (web T-3118)
        back.edit_line_loop(id, "open", ns[0], T).unwrap();
        assert_eq!(back.api.line_stops(id), vec![ns[1], ns[2], ns[3], ns[0]]);
        assert_eq!(back.api.line_loop(id), 0);
        assert!(running(&back, id));
        assert!(back.edit_line_loop(id, "open", ns[0], T).is_err(), "not a loop now");
        let again = World::open(&back.save(T).unwrap()).unwrap();
        assert_eq!(again.api.line_loop(id), 0);
    }

    #[test]
    fn a_line_end_turns_round_a_loop_of_track_and_unturns() {
        let (mut w, [a, b, m, k]) = teardrop();
        let id = line(&mut w, &[a, b, m, k]);
        let e = w.line_stop_edits(id).unwrap();
        assert_eq!(e["loop"]["turn"], json!([m]), "{e}");
        assert!(w.edit_line_loop(id, "turn", b, T).is_err());
        w.edit_line_loop(id, "turn", m, T).unwrap();
        assert_eq!(w.api.line_stops(id), vec![a, b, m, k, m, b, a]);
        assert_eq!(w.api.line_loop(id), 2);
        assert!(running(&w, id));
        let back = World::open(&w.save(T).unwrap()).unwrap();
        assert_eq!(back.api.line_stops(id), vec![a, b, m, k, m, b, a]);
        let e = w.line_stop_edits(id).unwrap();
        assert_eq!(e["loop"]["unturn"], json!(m), "{e}");
        w.edit_line_loop(id, "unturn", m, T).unwrap();
        assert_eq!(w.api.line_stops(id), vec![a, b, m, k]);
        assert!(running(&w, id));
    }

    #[test]
    fn a_new_line_picked_back_to_its_first_station_is_made_a_loop() {
        let (mut w, ns) = ring();
        let mut picked: Vec<i32> = ns.iter().map(|&n| n as i32).collect();
        picked.push(picked[0]);
        let preview = w.line_on_existing_track(&picked, "Lingkar", T, 0, false).unwrap();
        assert_eq!(preview["loop"], true);
        let made = w.line_on_existing_track(&picked, "Lingkar", T, 0, true).unwrap();
        let id = made["id"].as_u64().unwrap() as u32;
        let s = w.api.line_stops(id);
        assert_eq!(s.len(), 5);
        assert_eq!(s[0], s[4]);
        assert_eq!(w.api.line_loop(id), 1);
        let back = World::open(&w.save(T).unwrap()).unwrap();
        assert_eq!(back.api.line_loop(id), 1);

        // a line whose last station has no track round to its first is refused, nothing made
        let (mut w, [a, b, m, _]) = teardrop();
        let before = w.save(T).unwrap();
        assert!(w.line_on_existing_track(&[a as i32, b as i32, m as i32, a as i32], "Putus", T, 0, true).is_err());
        assert_eq!(w.save(T).unwrap(), before);
    }
}
