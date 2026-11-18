mod demand;
mod fares;
mod finance;
mod regional;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use jni::{
    objects::{JByteArray, JClass},
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
        Some(Self {
            api,
            header,
            hour: i64::MIN,
            trips: vec![],
        })
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
    let world = unsafe { &mut *(handle as *mut World) };
    finance::tick(world, time);
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
#[cfg(test)]
mod tests {
    use super::*;
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
