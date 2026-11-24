// SPDX-License-Identifier: AGPL-3.0-only
//! The bus tool while drawing (web busDraw.ts, T-1464): each placed point routes along the road
//! network to the next, through the same planner `Engine.build` uses, without changing the world.
//! Also the automatic name of a bus stop that is no station (web world.ts `stopsOf`): "Halte bus
//! dekat {stasiun}" within 1 km of a named station, else the nearest city place.
use super::*;
use std::sync::{Mutex, OnceLock};
use trainworld_sim::track::frame::great_circle;

/// Nearest named station within this, m (web `stopsOf`).
const STATION_M: f64 = 1000.;
/// Nearest city place within this, m.
const PLACE_M: f64 = 1500.;

/// City places (frame x, y, name) of the loaded city packs; not part of the save.
fn places() -> &'static Mutex<Vec<(f64, f64, String)>> {
    static PLACES: OnceLock<Mutex<Vec<(f64, f64, String)>>> = OnceLock::new();
    PLACES.get_or_init(|| Mutex::new(Vec::new()))
}

pub(super) fn set_places(rows: Vec<(f64, f64, String)>) {
    *places().lock().unwrap_or_else(|e| e.into_inner()) = rows;
}

/// The automatic name of a bus stop at frame point (x, y), if anything named is near.
pub(super) fn stop_name(api: &TrackApi, x: f64, y: f64) -> Option<String> {
    let at = api.to_lonlat(x, y);
    let metres = |px: f64, py: f64| {
        let p = api.to_lonlat(px, py);
        great_circle(at[0], at[1], p[0], p[1])
    };
    let station = api
        .nodes_info()
        .chunks_exact(10)
        .filter(|r| r[7] > 0.)
        .map(|r| (metres(r[1], r[2]), r[0] as u32))
        .filter(|(d, _)| *d < STATION_M)
        .filter_map(|(d, n)| {
            let name = api.node_name(n);
            (!name.trim().is_empty()).then_some((d, name))
        })
        .min_by(|a, b| a.0.total_cmp(&b.0));
    if let Some((_, name)) = station {
        return Some(format!("Halte bus dekat {}", name.trim()));
    }
    let list = places().lock().unwrap_or_else(|e| e.into_inner());
    list.iter()
        .map(|(px, py, name)| (metres(*px, *py), name))
        .filter(|(d, name)| *d < PLACE_M && !name.trim().is_empty())
        .min_by(|a, b| a.0.total_cmp(&b.0))
        .map(|(_, name)| format!("Halte {}", name.trim()))
}

/// `name`, or "name 2", "name 3"… when the line already has it.
pub(super) fn unique(name: String, taken: &mut Vec<String>) -> String {
    let free = if taken.contains(&name) {
        (2..)
            .map(|k| format!("{name} {k}"))
            .find(|n| !taken.contains(n))
            .unwrap()
    } else {
        name
    };
    taken.push(free.clone());
    free
}

impl World {
    /// The road route through `via` (x, y, kind: 1 a stop, 0 a bend) as `Engine.build` would lay
    /// it, read only: its polyline, length in km and each stop on the road with its name.
    pub(super) fn bus_preview(&self, via: &[[f64; 3]], roads: u8) -> Result<Value, String> {
        if !(2..=88).contains(&via.len()) || via.iter().flatten().any(|v| !v.is_finite()) {
            return Err("Titik rute tidak valid.".into());
        }
        let dir = self
            .roads_dir
            .as_deref()
            .ok_or("Data jalan belum tersedia.")?;
        let points: Vec<[f64; 2]> = via.iter().map(|p| [p[0], p[1]]).collect();
        let output = bus_plan::resolve(&self.api, &points, dir, None, roads)?;
        let rows: Vec<[f64; 3]> =
            serde_json::from_value(output["points"].clone()).map_err(|e| e.to_string())?;
        let mut path = Vec::with_capacity(rows.len() * 2);
        let mut metres = 0.;
        let mut ends = vec![];
        for (i, v) in rows.iter().enumerate() {
            if i > 0 {
                metres += great_circle(rows[i - 1][0], rows[i - 1][1], v[0], v[1]);
            }
            let xy = self.api.from_lonlat(v[0], v[1]);
            path.extend([xy[0], xy[1]]);
            if v[2] as u32 & 1 == 1 {
                ends.push([xy[0], xy[1]]);
            }
        }
        // Each placed point is a leg's end on the road; a too short leg can collapse them.
        let snapped = ends.len() == via.len();
        let mut taken = vec![];
        let stops: Vec<Value> = via
            .iter()
            .enumerate()
            .filter(|(_, p)| p[2] == 1.)
            .map(|(i, p)| {
                let [x, y] = if snapped { ends[i] } else { [p[0], p[1]] };
                let name = stop_name(&self.api, x, y)
                    .map(|n| unique(n, &mut taken))
                    .unwrap_or_default();
                json!([x, y, name])
            })
            .collect();
        Ok(json!({"path": path, "km": metres / 1000., "stops": stops, "snapped": snapped}))
    }
}

fn reply(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_BusDraft_preview(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    via: jni::objects::JDoubleArray,
) -> jstring {
    let result = (|| -> Result<Value, String> {
        let n = env.get_array_length(&via).map_err(|e| e.to_string())? as usize;
        if n % 3 != 0 || n > 264 {
            return Err("Titik rute tidak valid.".into());
        }
        let mut raw = vec![0.; n];
        env.get_double_array_region(&via, 0, &mut raw)
            .map_err(|e| e.to_string())?;
        let w = unsafe { (handle as *const World).as_ref() }.ok_or("Dunia belum tersedia.")?;
        let via: Vec<[f64; 3]> = raw.chunks_exact(3).map(|p| [p[0], p[1], p[2]]).collect();
        w.bus_preview(&via, bus_plan::roads())
    })()
    .unwrap_or_else(|e| json!({"error": e}));
    reply(&env, result)
}

/// The loaded cities' places as frame x, y and names, for naming bus stops.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_BusDraft_places(
    mut env: JNIEnv,
    _: JClass,
    xy: jni::objects::JDoubleArray,
    names: JString,
) {
    let n = env.get_array_length(&xy).unwrap_or(0).max(0) as usize;
    let mut raw = vec![0.; n];
    if env.get_double_array_region(&xy, 0, &mut raw).is_err() {
        return;
    }
    let names: String = env.get_string(&names).map(|v| v.into()).unwrap_or_default();
    let rows = raw
        .chunks_exact(2)
        .zip(names.split('\n'))
        .filter(|(p, _)| p[0].is_finite() && p[1].is_finite())
        .map(|(p, name)| (p[0], p[1], name.to_owned()))
        .collect();
    set_places(rows);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preview_without_road_data_says_so_and_changes_nothing() {
        let mut w = World::workshop();
        let via = [[0., 0., 1.], [2000., 0., 1.]];
        assert_eq!(
            w.bus_preview(&via, bus_plan::ALL_ROADS).unwrap_err(),
            "Data jalan belum tersedia."
        );
        let empty = std::env::temp_dir().join("bus-draft-no-tiles");
        std::fs::create_dir_all(&empty).unwrap();
        w.roads_dir = Some(empty);
        let before = w.save(111600.).unwrap();
        assert!(w
            .bus_preview(&via, bus_plan::ALL_ROADS)
            .unwrap_err()
            .contains("Unduh data jalan"));
        assert!(w.bus_preview(&via[..1], bus_plan::ALL_ROADS).is_err());
        assert_eq!(before, w.save(111600.).unwrap());
    }

    #[test]
    fn bus_stops_are_named_for_a_near_station_then_a_city_place() {
        let mut w = World::workshop();
        // Far from the other tests' workshop lines, which keep "Halte n".
        let (ox, oy) = (60000., 60000.);
        w.build_service([ox, oy], [ox + 3000., oy], "Kereta", 111600.)
            .unwrap();
        let station = w.api.line_stops(0)[0];
        w.api.set_station_name(station, "Sudirman".into());
        let mut rows = places().lock().unwrap().clone();
        rows.retain(|p| p.0 < 50000.);
        rows.push((ox + 9000., oy, "Menteng".into()));
        set_places(rows);
        assert_eq!(
            stop_name(&w.api, ox + 200., oy + 150.).unwrap(),
            "Halte bus dekat Sudirman"
        );
        assert_eq!(stop_name(&w.api, ox + 9100., oy).unwrap(), "Halte Menteng");
        assert_eq!(stop_name(&w.api, ox + 20000., oy), None);
        let line = w.api.new_line_id();
        assert_eq!(
            w.api.set_bus_line(
                line,
                &[ox + 8000., oy, 0., ox + 9000., oy, 0., ox + 10000., oy, 0.],
                300,
                600,
                1200,
                20.,
                60.,
                0x555555,
                "Bus Menteng".into()
            ),
            0
        );
        let names = w.service_stop_names(line);
        assert!(names.len() >= 2);
        assert_eq!(names[0], "Halte Menteng");
        assert!(names
            .iter()
            .skip(1)
            .all(|n| n.starts_with("Halte Menteng ")));
        let reopened = World::open(&w.save(111600.).unwrap()).unwrap();
        assert_eq!(reopened.service_stop_names(line), names);
    }

    #[test]
    #[ignore = "requires pinned Jakarta road tiles (WORLD_ROADS)"]
    fn jakarta_preview_follows_roads_like_the_build() {
        let mut w = World::sandbox("jak", 106.82, -6.19);
        w.roads_dir = Some(std::env::var("WORLD_ROADS").unwrap().into());
        let mut via = vec![];
        for (x, y) in [(106.8171, -6.1988), (106.8227, -6.18825)] {
            let p = w.api.from_lonlat(x, y);
            via.push([p[0], p[1], 1.]);
        }
        let before = w.save(111600.).unwrap();
        let v = w.bus_preview(&via, bus_plan::ALL_ROADS).unwrap();
        assert_eq!(before, w.save(111600.).unwrap());
        let path = v["path"].as_array().unwrap();
        assert!(path.len() > 12, "{v}");
        let direct = (via[0][0] - via[1][0]).hypot(via[0][1] - via[1][1]) / 1000.;
        let km = v["km"].as_f64().unwrap();
        assert!(km >= direct * 0.95 && km < direct * 3., "{km} vs {direct}");
        assert_eq!(v["snapped"], json!(true));
        let stops = v["stops"].as_array().unwrap();
        assert_eq!(stops.len(), 2);
        // Stops sit on the road route's ends.
        assert_eq!(stops[0][0], path[0]);
        assert_eq!(stops[1][1], path[path.len() - 1]);
        let points: Vec<[f64; 2]> = via.iter().map(|p| [p[0], p[1]]).collect();
        let built = w.build_alignment(&points, "Bus Sudirman", 111600., 5, &[]);
        assert!(built.is_ok(), "{built:?}");
        let route = w.api.bus_route(0);
        let n = (route.len() - 1) / 3;
        assert_eq!(path.len() / 2, n, "the preview is the built road");
        eprintln!(
            "preview {} points, {km:.2} km; build {n} points",
            path.len() / 2
        );
    }
}
