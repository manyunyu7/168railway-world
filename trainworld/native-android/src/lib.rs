use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jdouble, jfloatArray, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::io::{Read, Write};
use trainworld_sim::track::wasm::TrackApi;

struct World {
    api: TrackApi,
    header: Value,
    hour: i64,
    trips: Vec<f32>,
}

impl World {
    fn open(input: &[u8]) -> Option<Self> {
        let mut raw = Vec::new();
        GzDecoder::new(input)
            .take((32 << 20) + 1)
            .read_to_end(&mut raw)
            .ok()?;
        if raw.len() > 32 << 20 || raw.get(0..4)? != b"TWG1" {
            return None;
        }
        let n = u32::from_le_bytes(raw.get(4..8)?.try_into().ok()?) as usize;
        let header: Value = serde_json::from_slice(raw.get(8..8 + n)?).ok()?;
        if header["game"] != "anitabuilder" || header["format"] != 1 {
            return None;
        }
        let mut api = TrackApi::new(-74., 40.7, true);
        if !api.load(raw.get(8 + n..)?) {
            return None;
        }
        if header["nativeWorkshop"] == true {
            api.set_cash(header["cash"].as_f64().unwrap_or(6000.));
            api.set_fleet(header["fleet"].as_u64().unwrap_or(0) as u32);
            api.set_unlimited(true);
        }
        Some(Self {
            api,
            header,
            hour: i64::MIN,
            trips: vec![],
        })
    }
    fn workshop() -> Self {
        let mut api = TrackApi::new(-73.985, 40.758, true);
        api.set_unlimited(true);
        Self {
            api,
            header: json!({"game":"anitabuilder","format":1,"city":"nyc","clock":111600,"cash":6000.0,"fleet":0,"mode":"sandbox","fares":{"base":1.5,"perKm":0.1},"ledger":[],"nativeWorkshop":true}),
            hour: i64::MIN,
            trips: vec![],
        }
    }

    // Work on a copy. A refused station or service must not leave half-built track in the active slot.
    fn build_service(
        &mut self,
        a: [f64; 2],
        b: [f64; 2],
        name: &str,
        clock: f64,
    ) -> Result<Value, String> {
        if self.header["nativeWorkshop"] != true || !clock.is_finite() {
            return Err("Buka Dunia bangun untuk mengedit.".into());
        }
        if !a.into_iter().chain(b).all(|v| v.is_finite()) || (a[0] - b[0]).hypot(a[1] - b[1]) < 350.
        {
            return Err("Letakkan kedua stasiun setidaknya 350 meter terpisah.".into());
        }
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 48 {
            return Err("Nama layanan harus terdiri dari 1–48 karakter.".into());
        }
        let mut next = Self::open(&self.save(clock).ok_or("Simpanan sementara gagal")?)
            .ok_or("Jaringan gagal disalin")?;
        let api = &mut next.api;
        api.set_now(clock);
        api.set_unlimited(true);
        let ends = [0., 0., a[0], a[1], 0., 0., 0., b[0], b[1], 0.];
        if api.add_route(&ends, &[], 2, false) != 0 {
            return Err(format!("Ruas ditolak: {}", api.issue_kinds()));
        }
        let nodes = api.nodes_info();
        let mut stops = vec![];
        for (point, label) in [(a, "A"), (b, "B")] {
            let row = nodes
                .chunks_exact(10)
                .filter(|r| (r[1] - point[0]).hypot(r[2] - point[1]) < 1. && r[7] == 0.)
                .min_by(|r, s| {
                    (r[1] - point[0])
                        .hypot(r[2] - point[1])
                        .total_cmp(&(s[1] - point[0]).hypot(s[2] - point[1]))
                })
                .ok_or("Ujung jalur tidak ditemukan. Geser lokasi stasiun.")?;
            let id = row[0] as u32;
            if api.add_station(1, id, row[1], row[2], 160, format!("{name} {label}")) != 0 {
                return Err(format!("Stasiun ditolak: {}", api.issue_kinds()));
            }
            stops.push(id);
        }
        let line = api.new_line_id();
        let colors = [0x007866, 0xcf5823, 0x6944ad, 0x0876b6, 0xbd3a62];
        if api.set_line_with_trains(
            line,
            &stops,
            4,
            3,
            2,
            20.,
            60.,
            4,
            colors[line as usize % colors.len()],
            name.into(),
        ) != 0
            || api.construct_line(line) != 0
        {
            return Err(format!("Layanan ditolak: {}", api.issue_kinds()));
        }
        let lines = api.lines_info();
        if !lines
            .chunks_exact(22)
            .any(|r| r[0] as u32 == line && r[3] == 1.)
        {
            return Err("Layanan belum dapat beroperasi.".into());
        }
        let trips = api.line_trips(line, clock, clock + 7200.);
        let departure = trips
            .chunks_exact(4)
            .filter_map(|r| (r[2] > clock).then_some(r[1].max(clock)))
            .min_by(f64::total_cmp)
            .ok_or("Jadwal keberangkatan tidak tersedia.")?;
        next.header["cash"] = json!(api.cash());
        next.header["fleet"] = json!(api.fleet());
        next.header["nativeLastLine"] = json!(line);
        *self = next;
        Ok(json!({"line":line,"departure":departure,"name":name}))
    }

    fn save(&self, clock: f64) -> Option<Vec<u8>> {
        let mut header = self.header.clone();
        header["clock"] = json!(clock);
        let bytes = serde_json::to_vec(&header).ok()?;
        let mut out = GzEncoder::new(Vec::new(), Compression::fast());
        out.write_all(b"TWG1").ok()?;
        out.write_all(&(bytes.len() as u32).to_le_bytes()).ok()?;
        out.write_all(&bytes).ok()?;
        out.write_all(&self.api.save()).ok()?;
        out.finish().ok()
    }
    fn geometry(&self) -> Value {
        let lines = self.api.lines_info();
        let mut routes = Vec::new();
        for row in lines.chunks_exact(22).filter(|r| r[2] == 1.) {
            let id = row[0] as u32;
            let path = self.api.run_polyline(id, 0, 70.);
            let points: Vec<i32> = path.iter().map(|v| v.round() as i32).collect();
            routes.push(json!({"id":id,"color":row[1] as u32,"points":points,"name":self.api.line_name(id)}));
        }
        let mut stops = Vec::new();
        for row in self
            .api
            .nodes_info()
            .chunks_exact(10)
            .filter(|r| r[7] > 0. && r[8] > 0.)
        {
            stops.push(json!([
                row[1].round() as i32,
                row[2].round() as i32,
                self.api.node_name(row[0] as u32)
            ]));
        }
        let water: Value = serde_json::from_str(include_str!("../assets/nyc-water.json")).unwrap();
        let b = water["bounds"].as_array().unwrap();
        let sw = self
            .api
            .from_lonlat(b[0].as_f64().unwrap(), b[1].as_f64().unwrap());
        let ne = self
            .api
            .from_lonlat(b[2].as_f64().unwrap(), b[3].as_f64().unwrap());
        json!({"waterBox":[sw[0],sw[1],ne[0],ne[1]],"routes":routes,"stations":stops,"clock":self.header["clock"],"city":self.header["city"]})
    }
    fn positions(&mut self, time: f64) -> Vec<f32> {
        let hour = (time / 3600.).floor() as i64;
        let epoch = (hour * 3600) as f64;
        if self.hour != hour {
            self.trips = self.api.trips(epoch);
            self.hour = hour;
        }
        let mut out = Vec::new();
        for trip in self.trips.chunks_exact(4) {
            let dep = epoch + trip[1] as f64;
            if time < dep || time >= epoch + trip[2] as f64 {
                continue;
            }
            let profile = trip[0] as usize;
            let line = (profile / 6) as u32;
            let p = self
                .api
                .train_at(line, profile / 3 % 2, profile % 3, dep, time);
            if let [x, y] = p.as_slice() {
                out.extend_from_slice(&[line as f32, *x as f32, *y as f32]);
            }
        }
        out
    }
}

// Handles are owned by one NativeWorldActivity, with calls serialized on the UI thread
// after construction. No mutable global state and no host accounts are accessed here.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_open(
    env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
) -> jlong {
    env.convert_byte_array(bytes)
        .ok()
        .and_then(|b| World::open(&b))
        .map_or(0, |w| Box::into_raw(Box::new(w)) as jlong)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_geometry(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    let world = unsafe { &*(handle as *const World) };
    env.new_string(world.geometry().to_string())
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_positions(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
) -> jfloatArray {
    let world = unsafe { &mut *(handle as *mut World) };
    let values = world.positions(time);
    let Ok(arr) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&arr, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_save(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
) -> jbyteArray {
    let world = unsafe { &*(handle as *const World) };
    world
        .save(time)
        .and_then(|v| env.byte_array_from_slice(&v).ok())
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_close(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut World)) }
    }
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_workshop(_: JNIEnv, _: JClass) -> jlong {
    Box::into_raw(Box::new(World::workshop())) as jlong
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_build(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    ax: jdouble,
    ay: jdouble,
    bx: jdouble,
    by: jdouble,
    name: JString,
    clock: jdouble,
) -> jstring {
    let w = unsafe { &mut *(handle as *mut World) };
    let name: String = env.get_string(&name).map(|v| v.into()).unwrap_or_default();
    let result = w
        .build_service([ax, ay], [bx, by], &name, clock)
        .unwrap_or_else(|reason| json!({"error":reason}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn build_service_survives_save() {
        let mut w = World::workshop();
        let now = 111600.;
        assert!(w.build_service([0., 0.], [1., 1.], "Test", now).is_err());
        assert!(w.api.lines_info().is_empty());
        let result = w
            .build_service([0., 0.], [2500., 900.], "Hudson", now)
            .unwrap();
        let t = result["departure"].as_f64().unwrap() + 2.;
        let a = w.positions(t);
        let b = w.positions(t + 25.);
        assert!(
            !a.is_empty() && a != b,
            "genuine scheduled service must move"
        );
        let mut reopened = World::open(&w.save(t + 25.).unwrap()).unwrap();
        assert_eq!(reopened.positions(t + 25.), b);
        assert_eq!(reopened.geometry()["stations"].as_array().unwrap().len(), 2);
        assert_eq!(
            reopened
                .api
                .line_name(result["line"].as_u64().unwrap() as u32),
            "Hudson"
        );
    }
    #[test]
    fn fixture_moves_and_reopens() {
        let mut w = World::open(include_bytes!("../../T-007-real-nyc.save")).unwrap();
        for (t, expected) in [
            (120., [-2327.91005854, -5233.13626701]),
            (130., [-2295.10236958, -5145.43412719]),
        ] {
            let actual = w.api.train_at(0, 0, 0, 0., t);
            assert!(
                (actual[0] - expected[0]).abs() < 1e-6 && (actual[1] - expected[1]).abs() < 1e-6
            );
            println!("line 0 t={t}: {:?}", actual);
        }
        let geo = w.geometry();
        assert!(geo["routes"].as_array().unwrap().len() > 10);
        let clock = w.header["clock"].as_f64().unwrap();
        let p = w.positions(clock);
        let q = w.positions(clock + 15.);
        assert!(p.len() > 30 && p != q);
        let mut again = World::open(&w.save(clock + 15.).unwrap()).unwrap();
        assert_eq!(again.header["clock"], clock + 15.);
        assert_eq!(again.positions(clock + 15.), q);
        println!(
            "{} lines, {} stations, {} train markers, active clock {}",
            geo["routes"].as_array().unwrap().len(),
            geo["stations"].as_array().unwrap().len(),
            p.len() / 3,
            clock
        );
    }
}
