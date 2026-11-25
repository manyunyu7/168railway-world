//! The schedule panel's default headways for new lines (web `DefaultHeadways`,
//! ui/inspector/schedule.tsx, and game/newHeadway.ts) and the line's average speed (web
//! `AverageSpeed`). The defaults live in the save header's `newHeadway`, one set per kind of line,
//! which `native_line` already gives every new line of that kind.
use super::*;

/// Web `lineKind`: the kind of new line whose defaults line `id` sets, None for a bus or ferry line
/// (they have their own).
fn line_kind(api: &TrackApi, id: u32) -> Option<&'static str> {
    if api.is_bus(id) || api.is_ferry(id) {
        return None;
    }
    Some(if api.line_tram(id) {
        "tram"
    } else if api.line_fast(id) {
        "fast"
    } else {
        "rail"
    })
}

/// The header's defaults for `kind`, as `native_line` reads them.
fn default_of(header: &Value, kind: &str) -> Option<[u32; 3]> {
    let values = header["newHeadway"][kind].as_array()?;
    let [h, m, l] = values.as_slice() else { return None };
    let s = |v: &Value| v.as_f64().filter(|s| s.is_finite() && *s >= 0.).map(|s| s as u32);
    Some([s(h)?, s(m)?, s(l)?])
}

/// Web `autoTrainsFor` with no track limit (game/world.ts, `auto_trains` in the sim).
fn auto_trains_for(trip: f64, headway: u32, halves: bool) -> u32 {
    if halves {
        return 2 * auto_trains_for(trip / 2., headway, false);
    }
    if headway == 0 || !(trip > 0.) {
        return 0;
    }
    ((trip / (headway as f64 * 1.005) - 1e-6).ceil().max(0.) as u32).min(9999)
}

/// Web `AverageSpeed`: the distance both ways over the round trip at `level` minus the turning at
/// the ends, so the stops and the waiting at busy track count but not the layover; a loop once round
/// its lap, minus a balloon's turning. None when there is no time or no length.
pub(super) fn average_speed(api: &TrackApi, id: u32, row: &[f64], level: usize) -> Option<f64> {
    let (km, trip, turn) = (row[9] / 1000., row[10 + level.min(2)], row[20]);
    let kind = api.line_loop(id);
    let s = match kind {
        0 => trip - 2. * turn,
        _ => trip / if api.line_one_way(id) { 1. } else { 2. } - if kind == 2 { turn } else { 0. },
    };
    if !(s > 0.) || !(km > 0.) {
        return None;
    }
    Some(if kind == 0 { 2. * km } else { km } / (s / 3600.))
}

impl World {
    fn line_row(&self, id: u32) -> Option<Vec<f64>> {
        self.api.lines_info().chunks_exact(22).find(|r| r[0] as u32 == id).map(<[f64]>::to_vec)
    }

    /// Web `headwaysOfTrains`: the headways the line's trains give now, each rounded to the coarsest
    /// of 1 minute, 30, 10 or 5 seconds that keeps them.
    fn headways_of_trains(&self, id: u32) -> Option<[u32; 3]> {
        let r = self.line_row(id)?;
        let halves = self.api.line_loop(id) != 0 && !self.api.line_one_way(id);
        Some(std::array::from_fn(|lev| {
            let (k, trip) = (r[13 + lev] as u32, r[10 + lev]);
            if k == 0 || !(trip > 0.) {
                return 0;
            }
            let exact = (trip / k as f64).ceil() as u32;
            [60, 30, 10, 5]
                .into_iter()
                .map(|step| exact.div_ceil(step) * step)
                .find(|&h| auto_trains_for(trip, h, halves) == k)
                .unwrap_or(exact)
        }))
    }

    /// What the panel shows: the line's kind (null for a bus or ferry line), the kind's defaults
    /// (null while none is set) and whether the line already runs at them (web `same`).
    pub(super) fn schedule_defaults(&self, id: u32) -> Value {
        let Some(kind) = self.line_row(id).and(line_kind(&self.api, id)) else {
            return json!({"kind": null});
        };
        let now = default_of(&self.header, kind);
        let auto = self.api.line_auto(id);
        let same = now.is_some_and(|d| auto.len() == 3 && auto.iter().zip(d).all(|(a, b)| *a as u32 == b));
        json!({"kind": kind, "default": now, "same": same})
    }

    /// Web `makeDefaultHeadway`: line `id`'s headways become the default for new lines of its kind.
    /// A line on counts is switched to headways first, at the headways its trains give, so it runs
    /// the same service.
    pub(super) fn make_default_headway(&mut self, id: u32, now: f64) -> Result<Value, String> {
        let now = self.native_clock(now);
        let kind = line_kind(&self.api, id).ok_or("Line bus dan feri tidak memakai selang bawaan.")?;
        let auto = self.api.line_auto(id);
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        next.api.set_now(now);
        let headways = if auto.len() == 3 {
            [auto[0] as u32, auto[1] as u32, auto[2] as u32]
        } else {
            let h = self.headways_of_trains(id).ok_or("Line tidak ditemukan.")?;
            if next.api.set_auto_headway(id, h[0], h[1], h[2]) != 0 {
                return Err(format!("Jadwal ditolak: {}", crate::issue_text::describe(&next.api.issue_kinds())));
            }
            h
        };
        if !next.header["newHeadway"].is_object() {
            next.header["newHeadway"] = json!({});
        }
        next.header["newHeadway"][kind] = json!(headways);
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now).ok_or("Layanan tidak ditemukan.".into())
    }

    /// Web `applyDefaultHeadway`: give line `id` the default headways of its kind, as a headway edit.
    pub(super) fn apply_default_headway(&mut self, id: u32, now: f64) -> Result<Value, String> {
        let now = self.native_clock(now);
        let kind = line_kind(&self.api, id).ok_or("Line bus dan feri tidak memakai selang bawaan.")?;
        let h = default_of(&self.header, kind).ok_or("Belum ada bawaan.")?;
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        let api = &mut next.api;
        api.set_now(now);
        // as `schedule`: the first call switches a line on counts to headways keeping its fleet, the
        // second sizes the fleet to the headways
        let entering = api.line_auto(id).is_empty();
        if api.set_auto_headway(id, h[0], h[1], h[2]) != 0 || (entering && api.set_auto_headway(id, h[0], h[1], h[2]) != 0) {
            return Err(format!("Jadwal ditolak: {}", crate::issue_text::describe(&api.issue_kinds())));
        }
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now).ok_or("Layanan tidak ditemukan.".into())
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_ScheduleDefaults_query(env: JNIEnv, _: JClass, handle: jlong, id: i32) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }.map_or(Value::Null, |w| w.schedule_defaults(id as u32));
    string(&env, v)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_ScheduleDefaults_makeDefault(env: JNIEnv, _: JClass, handle: jlong, id: i32, now: f64) -> jstring {
    let v = unsafe { (handle as *mut World).as_mut() }
        .map_or(Value::Null, |w| w.make_default_headway(id as u32, now).unwrap_or_else(|e| json!({"error": e})));
    string(&env, v)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_ScheduleDefaults_useDefault(env: JNIEnv, _: JClass, handle: jlong, id: i32, now: f64) -> jstring {
    let v = unsafe { (handle as *mut World).as_mut() }
        .map_or(Value::Null, |w| w.apply_default_headway(id as u32, now).unwrap_or_else(|e| json!({"error": e})));
    string(&env, v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_line_on_counts_becomes_the_default_and_another_takes_it() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        assert_eq!(w.schedule_defaults(0), json!({"kind": "rail", "default": null, "same": false}));
        assert!(w.apply_default_headway(0, t).is_err(), "no default yet");
        let counts = json!({"name":"Hudson","mode":"count","peak":4,"day":2,"night":1,"weekend":100,"cars":4,"dwell":30,"turn":120});
        let before = w.schedule(0, t, &counts).unwrap();
        assert!(before["headway"].as_array().unwrap().is_empty());
        let given = w.headways_of_trains(0).unwrap();
        assert!(given.iter().all(|&h| h > 0 && h % 5 == 0), "{given:?}");
        // web setAutoTrains: on headways, at what its trains give, with the same trains
        let made = w.make_default_headway(0, t).unwrap();
        assert_eq!(made["headway"], json!(given.map(f64::from)));
        assert_eq!(made["count"], before["count"], "the same service");
        assert_eq!(w.header["newHeadway"]["rail"], json!(given));
        assert_eq!(w.schedule_defaults(0)["same"], true);

        let other = json!({"name":"Hudson","mode":"headway","peak":900,"day":1200,"night":1800,"weekend":100,"cars":4,"dwell":30,"turn":120});
        w.schedule(0, t, &other).unwrap();
        assert_eq!(w.schedule_defaults(0)["same"], false);
        let used = w.apply_default_headway(0, t).unwrap();
        assert_eq!(used["headway"], json!(given.map(f64::from)));
        // kept in the save's header, read by every new line of the kind
        let back = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(back.schedule_defaults(0), json!({"kind": "rail", "default": given, "same": true}));
    }

    #[test]
    fn average_speed_is_the_length_both_ways_over_the_trip_less_the_turns() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        let s = w.inspect_service(0, t).unwrap();
        let r = w.line_row(0).unwrap();
        let level = s["level"].as_u64().unwrap() as usize;
        let expect = 2. * r[9] / 1000. / ((r[10 + level] - 2. * r[20]) / 3600.);
        let kmh = s["averageSpeed"].as_f64().unwrap();
        assert!((kmh - expect).abs() < 1e-9, "{kmh} {expect}");
        assert!(kmh > 5. && kmh < 160., "{kmh}");
        assert_eq!(average_speed(&w.api, 0, &[0.; 22], 0), None, "no length, no speed");
    }
}
