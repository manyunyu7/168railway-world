mod defaults;
mod stops;
use super::*;

impl World {
    pub(super) fn service_stop_names(&self, id: u32) -> Vec<String> {
        stops::labels(&self.api, id).stops
    }

    fn services(&self) -> Value {
        self.api
            .lines_info()
            .chunks_exact(22)
            .map(|r| {
                let id = r[0] as u32;
                json!({"id":id,"name":self.api.line_name(id),"running":r[3]==1.,"group":self.api.line_group(id),"groupName":self.api.line_group_name(id)})
            })
            .collect()
    }

    /// Web `limitsFrom` (LineInspector.tsx) over `line_held`: per demand level what limits the
    /// line, null where nothing does: the shortest headway with every other line as it runs (s,
    /// infinite as null when no room is left), the headway a count is capped by (capS), the
    /// resource's kind (capacityKinds.ts) and the station at or nearest it.
    fn limits(&self, id: u32) -> Value {
        let held = self.api.line_held(id);
        let nodes = self.api.nodes_info();
        let nearest = |x: f64, y: f64| {
            nodes
                .chunks_exact(10)
                .filter(|n| n[7] > 0.)
                .min_by(|a, b| {
                    (a[1] - x)
                        .hypot(a[2] - y)
                        .total_cmp(&(b[1] - x).hypot(b[2] - y))
                })
                .map(|n| self.api.node_name(n[0] as u32))
        };
        (0..3)
            .map(|lev| {
                let at = 21 + 5 * lev;
                let Some(&[s, kind, node, x, y]) = held.get(at..at + 5) else {
                    return Value::Null;
                };
                if !(s > 0.) {
                    return Value::Null;
                }
                let place = if node >= 0. && self.api.world().net.is_station(node as u32) {
                    Some(self.api.node_name(node as u32))
                } else {
                    nearest(x, y)
                };
                let cap = held.get(36 + lev).copied().unwrap_or(0.);
                let fin = |v: f64| if v.is_finite() { json!(v) } else { Value::Null };
                json!({"s": fin(s), "capS": fin(if cap > 0. { cap } else { s }), "kind": kind, "place": place.unwrap_or_default()})
            })
            .collect()
    }

    /// Web TroubleList (T-1433) from what the engine knows without the clock's day counts: broken
    /// lines (tier 0), partway reversals (1), track that holds trains back at the current level
    /// (2) and lines without stations (6), worst first.
    fn troubles(&self, now: f64) -> Value {
        const NEAR_RHO: f64 = 0.9;
        let nodes = self.api.nodes_info();
        let nearest = |x: f64, y: f64| {
            nodes
                .chunks_exact(10)
                .filter(|n| n[7] > 0.)
                .min_by(|a, b| (a[1] - x).hypot(a[2] - y).total_cmp(&(b[1] - x).hypot(b[2] - y)))
                .map(|n| self.api.node_name(n[0] as u32))
                .unwrap_or_default()
        };
        let mut out: Vec<(u8, f64, Value)> = Vec::new();
        for r in self.api.lines_info().chunks_exact(22) {
            let id = r[0] as u32;
            let name = self.api.line_name(id);
            let stops = self.api.line_stops(id);
            if stops.is_empty() {
                out.push((6, 0., json!({"tier":6,"line":id,"name":name,"color":r[1]})));
                continue;
            }
            if r[4] == 1. {
                out.push((0, 0., json!({"tier":0,"line":id,"name":name,"color":r[1]})));
                continue;
            }
            for k in self.api.line_reversals(id) {
                if let Some(&node) = stops.get(k as usize) {
                    out.push((1, 1., json!({"tier":1,"line":id,"name":name,"color":r[1],"place":self.api.node_name(node),"node":node})));
                }
            }
            let shift = self.api.world().svc.lines[id as usize].shift;
            let level = match ((now + shift).rem_euclid(86400.) / 3600.) as u32 { 6..=19 => 0, 20..=23 => 1, _ => 2 };
            let held = self.api.line_held(id);
            // the track under what holds it back or limits it, where better signalling would help:
            // open or closed block (web TroubleList's signalling advice)
            let under = self.api.line_held_edges(id);
            let signal_on = |k: usize| -> Option<(u32, u8)> {
                let e = *under.get(k)?;
                (e >= 0.).then(|| (e as u32, self.api.edge_signal(e as u32))).filter(|&(_, s)| s <= trainworld_sim::track::params::SIGNAL_CLOSED)
            };
            if let Some(&[wait, kind, node, x, y, tph, most]) = held.get(7 * level..7 * level + 7) {
                if wait > 0.01 {
                    let place = if node >= 0. && self.api.world().net.is_station(node as u32) { self.api.node_name(node as u32) } else { nearest(x, y) };
                    let mut item = json!({"tier":2,"line":id,"name":name,"color":r[1],"kind":kind,"place":place,"wait":wait,"tph":tph,"most":most,"x":x,"y":y});
                    if let Some((e, s)) = signal_on(level) { item["edge"] = e.into(); item["signal"] = s.into(); }
                    out.push((2, wait, item));
                    continue;
                }
            }
            // Scheduled closer than the track allows (web capacity tag over 100%): the trains past
            // what fits wait at the end of the line.
            let tph = r[5 + level];
            if let Some(&[gap, kind, node, x, y]) = held.get(21 + 5 * level..26 + 5 * level) {
                let rho = if gap > 0. { if gap.is_finite() { tph * gap / 3600. } else { f64::INFINITY } } else { 0. };
                // web: tags from 90% (orange) up; "near" rows sit below spots over capacity (tier 4)
                if rho >= NEAR_RHO {
                    let place = if node >= 0. && self.api.world().net.is_station(node as u32) { self.api.node_name(node as u32) } else { nearest(x, y) };
                    let room = if gap.is_finite() { 3600. / gap } else { 0. };
                    let tier = if rho > 1. { 2 } else { 4 };
                    let mut item = json!({"tier":tier,"rho":rho,"line":id,"name":name,"color":r[1],"kind":kind,"place":place,"wait":0,"over":tph - room,"tph":tph,"most":room,"x":x,"y":y});
                    if let Some((e, s)) = signal_on(3 + level) { item["edge"] = e.into(); item["signal"] = s.into(); }
                    out.push((tier, rho, item));
                }
            }
        }
        out.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.total_cmp(&a.1)));
        Value::Array(out.into_iter().map(|t| t.2).collect())
    }

    pub(super) fn inspect_service(&self, id: u32, now: f64) -> Option<Value> {
        // web game/periods.ts PERIODS: high 06-20, medium 20-24, low 00-06, in the line's local time
        fn level_at(local: f64) -> usize {
            match (local.rem_euclid(86400.) / 3600.) as u32 {
                6..=19 => 0,
                20..=23 => 1,
                _ => 2,
            }
        }
        let rows = self.api.lines_info();
        let r = rows.chunks_exact(22).find(|r| r[0] as u32 == id)?;
        let labels = stops::labels(&self.api, id);
        let board = self
            .service_board(id, 0, now, now + 3600.)
            .unwrap_or_default();
        let upcoming: Vec<_> = board
            .into_iter()
            .take(6)
            .map(|(run, time)| json!([run, time, labels.directions.get(run)]))
            .collect();
        let trips: Vec<_> = upcoming.iter().map(|row| &row[1]).collect();
        let weekend = self.api.line_weekend(id);
        let vehicle = if self.api.is_bus(id) {
            "Bus"
        } else if self.api.is_ferry(id) {
            "Feri"
        } else if self.api.line_tram(id) {
            "Trem"
        } else if self.api.line_fast(id) {
            "Kereta cepat"
        } else {
            "Kereta komuter"
        };
        Some(
            json!({"id":id,"name":self.api.line_name(id),"color":r[1],"symbol":self.api.line_symbol(id),"shape":super::line_look::own_shape(&self.header, id),"modeShape":super::line_look::mode_shape(&self.api, id),"cars":r[8],"count":[r[13],r[14],r[15]],"headway":self.api.line_auto(id),
          "weekend":weekend.first().copied().unwrap_or(100.),
          "limits":self.limits(id),"lineDays":self.header["lineDays"][id.to_string()],
          "today":((now + super::local_time::day_shift(&self.header)) / 86400.).floor(),"weekendManual":weekend.get(1).is_some_and(|&m| m == 1.),"weekendCounts":weekend.get(2..5),"dwell":r[19],"turn":r[20],"cycle":r[10],"cycles":[r[10],r[11],r[12]],"length":r[9],"limit":self.api.line_limit(id),
          "level":level_at(now + self.api.world().svc.lines[id as usize].shift),
          "averageSpeed":defaults::average_speed(&self.api, id, r, level_at(now + self.api.world().svc.lines[id as usize].shift)),
          "shift":self.api.world().svc.lines[id as usize].shift,"vehicle":vehicle,"stops":labels.stops,"unnamedStops":labels.unnamed,"directionNames":labels.directions,"upcoming":upcoming,"depart":trips,"running":r[3]==1.}),
        )
    }

    fn service_board(
        &self,
        id: u32,
        station: usize,
        start: f64,
        end: f64,
    ) -> Option<Vec<(usize, f64)>> {
        let world = self.api.world();
        let line = world.svc.lines.get(id as usize)?;
        let stops = self.api.line_stops(id);
        let node = *stops.get(station)?;
        if line.bus.is_none() {
            return Some(
                self.api
                    .station_departures(id, node, start, end)
                    .chunks_exact(3)
                    .map(|d| (d[0] as usize, d[2]))
                    .collect(),
            );
        }
        // Street and water routes have profiles indexed by stop ordinal, no track platform.
        // A trip straddling the window may have left its terminus before the board's start.
        let mut result = Vec::new();
        for trip in world.svc.line_trips(&world.net, id as usize, start, end) {
            let run = trip.run as usize;
            let k = if run == 0 {
                station
            } else {
                stops.len() - 1 - station
            };
            if k + 1 == stops.len() {
                continue;
            }
            if let Some(offset) = line.runs[run].prof[trip.level as usize].dep.get(k) {
                let at = trip.draw + offset;
                if (start..end).contains(&at) {
                    result.push((run, at));
                }
            }
        }
        result.sort_by(|a, b| a.1.total_cmp(&b.1).then(a.0.cmp(&b.0)));
        Some(result)
    }

    fn timetable(&self, id: u32, start: f64, station: usize) -> Option<Value> {
        let line = self.api.world().svc.lines.get(id as usize)?;
        let end = start + 7200.;
        let labels = stops::labels(&self.api, id);
        let departures = self.service_board(id, station, start, end)?;
        let profiles: Vec<_> = (0..if line.one_way { 1 } else { 2 })
            .flat_map(|run| {
                let path = &line.runs[run];
                (0..3).map(move |level| {
                    let arrival = self.api.stop_times(id, run, level);
                    let departure = self.api.stop_departures(id, run, level);
                    path.stop_s
                        .iter()
                        .zip(arrival)
                        .zip(departure)
                        .map(|((&offset, a), d)| {
                            let fraction = offset / path.len.max(1.);
                            json!([if run == 0 { fraction } else { 1. - fraction }, a, d])
                        })
                        .collect::<Vec<_>>()
                })
            })
            .collect();
        let trips: Vec<_> = self
            .api
            .line_trips(id, start, end)
            .chunks_exact(4)
            .map(|t| json!([t[0] as usize % 6, t[1]]))
            .collect();
        let board: Vec<_> = departures
            .into_iter()
            .map(|(run, at)| json!([run, at, labels.directions.get(run)]))
            .collect();
        Some(json!({"profiles":profiles,"trips":trips,"board":board,"shift":line.shift}))
    }

    pub(super) fn schedule(&mut self, id: u32, now: f64, v: &Value) -> Result<Value, String> {
        let now = self.native_clock(now);
        let old = self
            .inspect_service(id, now)
            .ok_or("Layanan tidak ditemukan.")?;
        let number = |key: &str, min: u32, max: u32| {
            v.get(key)
                .and_then(Value::as_u64)
                .filter(|&n| n >= min as u64 && n <= max as u64)
                .map(|n| n as u32)
                .ok_or_else(|| format!("Nilai {key} harus {min}–{max}."))
        };
        let name = v["name"].as_str().unwrap_or("").trim();
        if name.is_empty() || name.chars().count() > 48 {
            return Err("Nama layanan harus 1–48 karakter.".into());
        }
        let headway = v["mode"] == "headway";
        // web HEADWAY_MAX_S (game/world.ts): four hours, the shortest stretch of one demand level
        let max = if headway { 14400 } else { 128 };
        let counts = [
            number("peak", 0, max)?,
            number("day", 0, max)?,
            number("night", 0, max)?,
        ];
        if headway && counts.iter().any(|n| *n > 0 && *n < 60) {
            return Err(
                "Selang keberangkatan minimal 60 detik. Gunakan 0 untuk menonaktifkan periode."
                    .into(),
            );
        }
        let weekend = number("weekend", 10, 200)?;
        // web DaySections: weekend trains set by hand at each level (the share then kept for the
        // panel); absent, the weekends run at the share, counts set by hand dropped
        let weekend_counts = match v["weekendCounts"].as_array() {
            Some(c) if c.len() == 3 => {
                let mut out = [0u32; 3];
                for (o, n) in out.iter_mut().zip(c) {
                    *o = n
                        .as_u64()
                        .filter(|&n| n <= 128)
                        .ok_or("Kereta akhir pekan harus 0–128.")? as u32;
                }
                Some(out)
            }
            Some(_) => return Err("Kereta akhir pekan harus tiga angka.".into()),
            None => None,
        };
        let cars = number("cars", 0, 12)?;
        let dwell = number("dwell", 5, 600)?;
        let turn = number("turn", 15, 1800)?;
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        let api = &mut next.api;
        api.set_now(now);
        let color = old["color"].as_f64().unwrap_or(0.) as u32;
        let failed = api.set_line_look(id, name.to_owned(), color) != 0
            || api.set_line_cars(id, cars as u8) != 0
            || api.set_line_stop_times(id, dwell, turn) != 0
            || if headway {
                // The first call changes the mode while keeping the current fleet; the
                // following call applies the entered headways to that fleet's size.
                let entering = api.line_auto(id).is_empty();
                api.set_auto_headway(id, counts[0], counts[1], counts[2]) != 0
                    || (entering && api.set_auto_headway(id, counts[0], counts[1], counts[2]) != 0)
            } else {
                api.clear_auto_headway(id) != 0
                    || api.set_train_counts(id, counts[0], counts[1], counts[2]) != 0
            }
            || api.set_line_weekend_share(id, weekend) != 0
            || weekend_counts
                .is_some_and(|c| api.set_line_weekend_counts(id, c[0], c[1], c[2]) != 0);
        if failed {
            return Err(format!("Jadwal ditolak: {}", crate::issue_text::describe(&api.issue_kinds())));
        }
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now)
            .ok_or("Layanan tidak ditemukan.".into())
    }
}

impl World {
    /// Rename a line, keeping its colour; 1–48 characters as the schedule form allows.
    pub(super) fn rename_line(&mut self, id: u32, name: &str, now: f64) -> Result<Value, String> {
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 48 {
            return Err("Nama line 1–48 huruf.".into());
        }
        let color = self.api.lines_info().chunks_exact(22).find(|r| r[0] as u32 == id).map(|r| r[1] as u32).ok_or("Line tidak ditemukan.")?;
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        next.api.set_now(now);
        if next.api.set_line_look(id, name.to_owned(), color) != 0 {
            return Err(crate::issue_text::describe(&next.api.issue_kinds()));
        }
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now).ok_or("Layanan tidak ditemukan.".into())
    }

    /// Name ideas for a line, after the way real Indonesian services are named: its ends
    /// ("Bogor – Jakarta Kota"), "Commuter Line {end}", "KRL {end}", "Lin {end}", an area word
    /// with its middle stop, and the engine's next free name. Names already taken are skipped.
    pub(super) fn line_name_ideas(&mut self, id: u32) -> Vec<String> {
        let stops = self.api.line_stops(id);
        let names: Vec<String> = stops.iter().map(|&n| self.api.node_name(n)).filter(|n| !n.is_empty()).collect();
        let taken: std::collections::HashSet<String> = self.api.lines_info().chunks_exact(22)
            .map(|r| self.api.line_name(r[0] as u32).to_lowercase()).collect();
        let short = |n: &str| n.split_whitespace().take(2).collect::<Vec<_>>().join(" ");
        let mut out: Vec<String> = Vec::new();
        let mut push = |s: String| { if !s.is_empty() && s.chars().count() <= 48 && !taken.contains(&s.to_lowercase()) && !out.contains(&s) { out.push(s) } };
        if let (Some(a), Some(b)) = (names.first(), names.last()) {
            let vehicle = if self.api.is_bus(id) { "Bus" } else if self.api.is_ferry(id) { "Feri" } else if self.api.line_tram(id) { "Trem" } else { "Commuter Line" };
            if a != b {
                push(format!("{} – {}", short(a), short(b)));
                push(format!("{vehicle} {}", short(b)));
                push(format!("{vehicle} {}", short(a)));
                push(format!("KRL {} – {}", short(a), short(b)));
                push(format!("Lin {}", short(b)));
            } else {
                push(format!("Lingkar {}", short(a)));
                push(format!("{vehicle} Lingkar {}", short(a)));
            }
            if let Some(mid) = names.get(names.len() / 2) {
                push(format!("{vehicle} {} via {}", short(b), short(mid)));
                push(format!("Ekspres {} – {}", short(a), short(b)));
            }
        }
        push(self.api.next_line_name());
        out
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string())
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_ServicePanel_services(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    string(&env, unsafe { &*(handle as *const World) }.services())
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_ServicePanel_inspect(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    id: i32,
    now: f64,
) -> jstring {
    string(
        &env,
        unsafe { &*(handle as *const World) }
            .inspect_service(id as u32, now)
            .unwrap_or(Value::Null),
    )
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioTimetable_query(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    id: i32,
    start: f64,
    station: i32,
) -> jstring {
    let data = unsafe { (handle as *const World).as_ref() }
        .and_then(|w| w.timetable(id as u32, start, station as usize));
    string(&env, data.unwrap_or(Value::Null))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_ServicePanel_update(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    id: i32,
    now: f64,
    value: JString,
) -> jstring {
    let input: String = env.get_string(&value).map(|v| v.into()).unwrap_or_default();
    let v = serde_json::from_str(&input).unwrap_or(Value::Null);
    let result = unsafe { &mut *(handle as *mut World) }
        .schedule(id as u32, now, &v)
        .unwrap_or_else(|e| json!({"error":e}));
    string(&env, result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn weekend_counts_by_hand_and_back_to_the_share() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t)
            .unwrap();
        let base = json!({"name":"Hudson","mode":"count","peak":4,"day":2,"night":1,"weekend":60,"cars":4,"dwell":30,"turn":120});
        let mut by_hand = base.clone();
        by_hand["weekendCounts"] = json!([3, 2, 1]);
        let saved = w.schedule(0, t, &by_hand).unwrap();
        assert_eq!(saved["weekendManual"], true);
        assert_eq!(saved["weekendCounts"], json!([3., 2., 1.]));
        assert_eq!(saved["weekend"], 60.);
        let back = w.schedule(0, t, &base).unwrap();
        assert_eq!(back["weekendManual"], false);
        // web limitsFrom: three levels; many trains on the workshop's track meet its limit
        let mut busy = base.clone();
        busy["peak"] = json!(60);
        let full = w.schedule(0, t, &busy).unwrap();
        let limits = full["limits"].as_array().unwrap();
        assert_eq!(limits.len(), 3);
        println!("limits {}", full["limits"]);
        let high = &limits[0];
        assert!(
            high["s"].as_f64().is_some_and(|s| s > 0.) || high["s"].is_null() && !high.is_null()
        );
        assert!(!high["place"].as_str().unwrap().is_empty());
        let mut bad = base.clone();
        bad["weekendCounts"] = json!([3, 2]);
        assert!(w.schedule(0, t, &bad).is_err());
    }
    #[test]
    #[ignore]
    fn debug_troubles_of_a_saved_world() {
        let bytes = std::fs::read(std::env::var("WORLD_DEBUG_SAVE").unwrap()).unwrap();
        let w = World::open(&bytes).unwrap();
        let t = w.header["clock"].as_f64().unwrap_or(0.);
        println!("clock {t} troubles {}", w.troubles(t));
        for r in w.api.lines_info().chunks_exact(22) {
            let id = r[0] as u32;
            if w.api.line_name(id).contains("Bogor") {
                println!("{} tph {:?} held {:?}", w.api.line_name(id), &r[5..8], w.api.line_held(id));
            }
        }
    }
    #[test]
    fn rename_line_and_offer_names() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        let ideas = w.line_name_ideas(0);
        assert!(!ideas.is_empty());
        assert!(ideas.iter().all(|n| n != "Hudson"));
        let renamed = w.rename_line(0, &ideas[0], t).unwrap();
        assert_eq!(renamed["name"], ideas[0].as_str());
        assert!(w.rename_line(0, "  ", t).is_err());
        let w = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(w.api.line_name(0), ideas[0]);
    }
    #[test]
    fn troubles_list_track_that_holds_trains_back() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        let before = w.troubles(t)[0]["wait"].as_f64().unwrap_or(0.);
        let busy = json!({"name":"Hudson","mode":"count","peak":60,"day":2,"night":1,"weekend":60,"cars":4,"dwell":30,"turn":120});
        w.schedule(0, t, &busy).unwrap();
        let list = w.troubles(t);
        println!("troubles {list}");
        let first = &list[0];
        assert_eq!(first["tier"], 2);
        assert!(first["wait"].as_f64().unwrap() > before);
        assert_eq!(first["kind"], 3.); // the terminus turnaround, as the web's capacity tag
        assert!(!first["place"].as_str().unwrap().is_empty());
    }
    #[test]
    fn timetable_edits_and_headways_survive_reload() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t)
            .unwrap();
        let v = json!({"name":"Hudson Lokal","mode":"headway","peak":180,"day":360,"night":720,"weekend":65,"cars":5,"dwell":32,"turn":110});
        let saved = w.schedule(0, t, &v).unwrap();
        assert!(saved["depart"]
            .as_array()
            .unwrap()
            .iter()
            .all(|v| v.as_f64().unwrap() >= t));
        let chart = w.timetable(0, t, 0).unwrap();
        assert_eq!(chart["profiles"].as_array().unwrap().len(), 6);
        assert!(!chart["trips"].as_array().unwrap().is_empty());
        assert!(!chart["board"].as_array().unwrap().is_empty());
        for row in chart["board"].as_array().unwrap() {
            assert!((t..t + 7200.).contains(&row[1].as_f64().unwrap()));
        }
        assert!(w.timetable(0, t, usize::MAX).is_none());
        assert_eq!(saved["name"], "Hudson Lokal");
        assert_eq!(saved["headway"], json!([180., 360., 720.]));
        assert_eq!(saved["dwell"], 32.);
        assert_eq!(saved["turn"], 110.);
        let w = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(w.inspect_service(0, t).unwrap(), saved);
    }
    #[test]
    fn transjakarta_corridor_three_uses_real_stops_and_timed_departures() {
        let fixture =
            include_bytes!("../android-lib/src/main/assets/world-native/scenarios/jak_mulai.save");
        let mut world = World::open(fixture).unwrap();
        let line = world
            .api
            .bus_lines()
            .into_iter()
            .find(|&n| world.api.line_name(n) == "TransJakarta Koridor 3")
            .unwrap();
        let start = world.header["clock"].as_f64().unwrap();
        let original = world.save(start).unwrap();
        let details = world.inspect_service(line, start).unwrap();
        let names = [
            "Monumen Nasional",
            "Petojo",
            "Roxy",
            "Grogol",
            "Jelambar",
            "Damai",
            "Taman Kota",
            "Jembatan Gantung",
            "Pulo Nangka",
            "Jembatan Baru",
            "Rawa Buaya",
            "Sumur Bor",
            "Pesakih",
            "Kalideres",
        ];
        assert_eq!(details["stops"], json!(names));
        assert_eq!(details["unnamedStops"], 0);
        assert_eq!(
            details["directionNames"],
            json!(["Kalideres", "Monumen Nasional"])
        );
        assert!(details["upcoming"]
            .as_array()
            .unwrap()
            .iter()
            .all(|r| r[0] == 0 && r[2] == "Kalideres"));
        assert_eq!(details["upcoming"].as_array().unwrap().len(), 6);
        for (i, name) in names.iter().enumerate() {
            let chart = world.timetable(line, start, i).unwrap();
            let board = chart["board"].as_array().unwrap();
            assert!(!board.is_empty(), "{name}");
            let values: Vec<_> = board
                .iter()
                .map(|row| {
                    (
                        row[0].as_u64().unwrap(),
                        row[1].as_f64().unwrap(),
                        row[2].as_str().unwrap(),
                    )
                })
                .collect();
            assert!(values.windows(2).all(|w| w[0].1 <= w[1].1));
            for &(run, actual, to) in &values {
                assert_eq!(
                    to,
                    if run == 0 {
                        "Kalideres"
                    } else {
                        "Monumen Nasional"
                    }
                );
                assert!((start..start + 7200.).contains(&actual));
                let ordinal = if run == 0 { i } else { names.len() - 1 - i };
                // Cross-check each board entry against the public Studio trip/profile APIs.
                assert!(world
                    .api
                    .line_trips(line, start, start + 7200.)
                    .chunks_exact(4)
                    .any(|t| {
                        let profile = t[0] as usize % 6;
                        profile / 3 == run as usize
                            && (t[1]
                                + world.api.stop_departures(line, run as usize, profile % 3)
                                    [ordinal]
                                - actual)
                                .abs()
                                < 0.00001
                    }));
            }
            assert_eq!(values.iter().any(|r| r.0 == 0), i + 1 < names.len());
            assert_eq!(values.iter().any(|r| r.0 == 1), i > 0);
        }
        assert_eq!(world.save(start).unwrap(), original);
        let real_node = world.api.line_stops(line)[6];
        assert_eq!(
            world.api.set_station_name(real_node, "Nama pemain".into()),
            0
        );
        assert_eq!(
            world.inspect_service(line, start).unwrap()["stops"][6],
            "Nama pemain"
        );
    }

    #[test]
    fn unlabelled_bus_stops_use_the_same_ordinals_after_reload() {
        let mut world = World::workshop();
        assert_eq!(
            world.api.set_bus_line(
                0,
                &[0., 0., 0., 1800., 0., 0., 4200., 500., 0.],
                300,
                600,
                1200,
                20.,
                60.,
                0x555555,
                "TransJakarta Koridor 3".into()
            ),
            0
        );
        let stops = world.inspect_service(0, 111600.).unwrap();
        let names = stops["stops"].as_array().unwrap();
        assert!(names.len() >= 2);
        assert_eq!(stops["unnamedStops"], names.len());
        for (i, name) in names.iter().enumerate() {
            assert_eq!(name, &json!(format!("Halte {}", i + 1)));
        }
        let restored = World::open(&world.save(111600.).unwrap()).unwrap();
        assert_eq!(
            restored.inspect_service(0, 111600.).unwrap()["stops"],
            stops["stops"]
        );
    }
    #[test]
    fn ferry_board_starts_only_after_construction_and_excludes_terminal_arrivals() {
        let mut world = World::workshop();
        let mut ports = Vec::new();
        let mut route = Vec::new();
        for (x, name) in [
            (0., "Dermaga Barat"),
            (2400., "Dermaga Tengah"),
            (8500., "Dermaga Timur"),
        ] {
            assert_eq!(world.api.add_port(x, -100., x, 0., name.into()), 0);
            ports.push(world.api.last_port());
            route.extend([x, 0., trainworld_sim::track::ferry::K_PORT as f64, 8.]);
        }
        let id = world.api.new_line_id();
        assert_eq!(
            world.api.set_ferry_line(
                id,
                &ports,
                &route,
                600,
                900,
                1200,
                25.,
                60.,
                0x116699,
                "Feri".into()
            ),
            0
        );
        let start = 111600.;
        assert!(world
            .service_board(id, 0, start, start + 7200.)
            .unwrap()
            .is_empty());
        assert_eq!(world.api.construct_line(id), 0);
        for (i, dirs) in [vec![0], vec![0, 1], vec![1]].iter().enumerate() {
            let chart = world.timetable(id, start, i).unwrap();
            let board = chart["board"].as_array().unwrap();
            for &direction in dirs {
                assert!(board.iter().any(|d| d[0] == direction));
            }
            for d in board {
                assert!(dirs.contains(&d[0].as_u64().unwrap()));
                assert_eq!(
                    d[2],
                    if d[0] == 0 {
                        "Dermaga Timur"
                    } else {
                        "Dermaga Barat"
                    }
                );
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TroubleList_query(env: JNIEnv, _: JClass, handle: jlong, now: f64) -> jstring {
    let data = unsafe { (handle as *const World).as_ref() }.map(|w| w.troubles(now));
    string(&env, data.unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_ServicePanel_rename(mut env: JNIEnv, _: JClass, handle: jlong, id: i32, now: f64, name: JString) -> jstring {
    let name: String = env.get_string(&name).map(|s| s.into()).unwrap_or_default();
    let value = unsafe { (handle as *mut World).as_mut() }
        .map(|w| w.rename_line(id as u32, &name, now).unwrap_or_else(|e| json!({"error": e})))
        .unwrap_or(Value::Null);
    string(&env, value)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_ServicePanel_nameIdeas(env: JNIEnv, _: JClass, handle: jlong, id: i32) -> jstring {
    let value = unsafe { (handle as *mut World).as_mut() }.map(|w| json!(w.line_name_ideas(id as u32))).unwrap_or(Value::Null);
    string(&env, value)
}
