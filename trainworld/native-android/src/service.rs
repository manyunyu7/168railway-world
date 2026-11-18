use super::*;

impl World {
    fn services(&self) -> Value {
        self.api
            .lines_info()
            .chunks_exact(22)
            .map(|r| {
                let id = r[0] as u32;
                json!({"id":id,"name":self.api.line_name(id),"running":r[3]==1.})
            })
            .collect()
    }

    fn inspect_service(&self, id: u32, now: f64) -> Option<Value> {
        let rows = self.api.lines_info();
        let r = rows.chunks_exact(22).find(|r| r[0] as u32 == id)?;
        let stops = self.api.line_stops(id);
        let at = stops
            .first()
            .map(|&n| self.api.station_departures(id, n, now, now + 3600.))
            .unwrap_or_default();
        let trips: Vec<f64> = at.chunks_exact(3).take(6).map(|r| r[2]).collect();
        let names: Vec<_> = stops.iter().map(|&n| self.api.node_name(n)).collect();
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
            json!({"name":self.api.line_name(id),"color":r[1],"cars":r[8],"count":[r[13],r[14],r[15]],"headway":self.api.line_auto(id),
          "weekend":weekend.first().copied().unwrap_or(100.),"dwell":r[19],"turn":r[20],"cycle":r[10],"length":r[9],"limit":self.api.line_limit(id),
          "vehicle":vehicle,"stops":names,"depart":trips,"running":r[3]==1.}),
        )
    }

    fn schedule(&mut self, id: u32, now: f64, v: &Value) -> Result<Value, String> {
        if self.header["nativeWorkshop"] != true {
            return Err("Jadwal contoh hanya dapat dilihat.".into());
        }
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
        let max = if headway { 7200 } else { 128 };
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
        let cars = number("cars", 0, 12)?;
        let dwell = number("dwell", 5, 600)?;
        let turn = number("turn", 15, 1800)?;
        let mut next = Self::open(&self.save(now).ok_or("Gagal mencadangkan permainan.")?)
            .ok_or("Gagal membaca permainan.")?;
        next.api.copy_city_surface_from(&self.api);
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
            || api.set_line_weekend_share(id, weekend) != 0;
        if failed {
            return Err(format!("Jadwal ditolak: {}", api.issue_kinds()));
        }
        next.header["cash"] = json!(api.cash());
        next.header["fleet"] = json!(api.fleet());
        *self = next;
        self.inspect_service(id, now)
            .ok_or("Layanan tidak ditemukan.".into())
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
        assert_eq!(saved["name"], "Hudson Lokal");
        assert_eq!(saved["headway"], json!([180., 360., 720.]));
        assert_eq!(saved["dwell"], 32.);
        assert_eq!(saved["turn"], 110.);
        let w = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(w.inspect_service(0, t).unwrap(), saved);
    }
}
