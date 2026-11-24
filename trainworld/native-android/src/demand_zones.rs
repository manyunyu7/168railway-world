//! Candidate pack boxes and exact boundary reach, as in workers/cityReach.ts.
use crate::World;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::jstring,
    JNIEnv,
};
use serde_json::{json, Value};
use std::path::Path;
include!(concat!(env!("OUT_DIR"), "/zone_boxes.rs"));
const DEG: f64 = 111_320.;
const LOCAL: f64 = 4500.;

pub(super) fn bounds(id: &str) -> Option<[f64; 4]> {
    ZONE_BOXES.iter().find(|(z, _)| *z == id).map(|(_, b)| *b)
}

struct Boundary(Vec<Vec<[f64; 2]>>);
impl Boundary {
    fn read(path: &Path) -> Result<Self, String> {
        let data: Value =
            serde_json::from_reader(std::fs::File::open(path).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        let mut rings = Vec::new();
        for f in data["features"]
            .as_array()
            .ok_or("Batas wilayah tidak valid")?
        {
            let g = &f["geometry"];
            let polygons: Vec<Vec<Vec<[f64; 2]>>> =
                match g["type"].as_str() {
                    Some("Polygon") => vec![serde_json::from_value(g["coordinates"].clone())
                        .map_err(|e| e.to_string())?],
                    Some("MultiPolygon") => serde_json::from_value(g["coordinates"].clone())
                        .map_err(|e| e.to_string())?,
                    _ => continue,
                };
            rings.extend(polygons.into_iter().flatten().filter(|r| r.len() >= 3));
        }
        Ok(Self(rings))
    }
    fn reaches(&self, lon: f64, lat: f64, dx: f64) -> bool {
        self.distance(lon, lat, dx, LOCAL * LOCAL) <= LOCAL * LOCAL
    }
    fn distance(&self, lon: f64, lat: f64, dx: f64, stop: f64) -> f64 {
        let mut inside = false;
        let mut best = f64::INFINITY;
        for ring in &self.0 {
            let mut prev = ring.last().unwrap();
            for next in ring {
                let (ax, ay) = ((prev[0] - lon) * dx, (prev[1] - lat) * DEG);
                let (bx, by) = ((next[0] - lon) * dx, (next[1] - lat) * DEG);
                if (ay > 0.) != (by > 0.) && ax - ay * (bx - ax) / (by - ay) > 0. {
                    inside = !inside;
                }
                let (x, y) = (bx - ax, by - ay);
                let length = x * x + y * y;
                let t = if length > 0. {
                    (-(ax * x + ay * y) / length).clamp(0., 1.)
                } else {
                    0.
                };
                best = best.min((ax + t * x).powi(2) + (ay + t * y).powi(2));
                if best <= stop {
                    return best;
                }
                prev = next;
            }
        }
        if inside {
            0.
        } else {
            best
        }
    }
}

#[derive(Default)]
pub(super) struct Geography(Vec<(&'static str, [f64; 4], Boundary)>);
impl Geography {
    pub(super) fn read(dir: &Path, ids: &[String]) -> Result<Self, String> {
        let mut areas = Vec::new();
        for &(id, b) in ZONE_BOXES {
            if !ids.iter().any(|s| s == id) {
                continue;
            }
            let file = dir.join(format!("{id}.boundary.geojson"));
            if file.exists() {
                areas.push((id, b, Boundary::read(&file)?));
            }
        }
        Ok(Self(areas))
    }
    pub(super) fn upkeep(
        &self,
        api: &mut trainworld_sim::track::wasm::TrackApi,
    ) -> crate::passenger_account::Regions {
        use trainworld_sim::track::params::{ECONOMY, UPKEEP_BUS_KM};
        let mut totals = crate::passenger_account::Regions::new();
        let mut add = |lon, lat, v| *totals.entry(self.zone(lon, lat).to_owned()).or_default() += v;
        for (x, y, v) in api.infrastructure_upkeep() {
            add(x, y, v);
        }
        for row in api.lines_info().chunks_exact(22) {
            let id = row[0] as u32;
            if !api.is_bus(id) || api.is_ferry(id) {
                continue;
            }
            let route = api.bus_route(id);
            if route.first() != Some(&1.) {
                continue;
            }
            let pts: Vec<_> = route[1..].chunks_exact(3).collect();
            if pts.len() < 2 {
                continue;
            }
            let frame = api.world().net.frame();
            let mut pieces = Vec::new();
            let mut start = 0;
            let mut acc = 0.;
            let mut length = 0.;
            for i in 1..pts.len() {
                let (a, b) = (pts[i - 1], pts[i]);
                let km = (b[0] - a[0]).hypot(b[1] - a[1])
                    / if frame.scaled() {
                        trainworld_sim::track::frame::merc_k((a[1] + b[1]) / 2.)
                    } else {
                        1.
                    };
                acc += km;
                length += km;
                if acc >= 200. || i + 1 == pts.len() {
                    let (ax, ay) = frame.to_lonlat(pts[start][0], pts[start][1]);
                    let (bx, by) = frame.to_lonlat(b[0], b[1]);
                    pieces.push(((ax + bx) / 2., (ay + by) / 2., acc));
                    acc = 0.;
                    start = i;
                }
            }
            if length > 0. {
                let rate = api.bus_road_m(id) / 1000. * UPKEEP_BUS_KM * ECONOMY / 1e6 / length;
                for (x, y, d) in pieces {
                    add(x, y, d * rate);
                }
            }
        }
        let total: f64 = totals.values().sum();
        if total > 0. {
            for x in totals.values_mut() {
                *x /= total;
            }
        }
        totals
    }
    pub(super) fn zone(&self, lon: f64, lat: f64) -> &'static str {
        let dx = DEG * lat.to_radians().cos();
        let mut best = 40000_f64.powi(2);
        let mut zone = "";
        for (id, [w, s, e, n], boundary) in &self.0 {
            let bx = (w - lon).max(lon - e).max(0.) * dx;
            let by = (s - lat).max(lat - n).max(0.) * DEG;
            if bx * bx + by * by > best {
                continue;
            }
            let d = boundary.distance(lon, lat, dx, 0.);
            if d < best {
                best = d;
                zone = id;
                if d == 0. {
                    break;
                }
            }
        }
        zone
    }
}
pub(super) fn plan(bytes: &[u8], dir: &Path) -> Result<Value, String> {
    let mut world = World::open(bytes).ok_or("Simpanan tidak dapat dibaca")?;
    let api = &mut world.api;
    let mut xy = Vec::new();
    for row in api
        .nodes_info()
        .chunks_exact(10)
        .filter(|r| r[7] > 0. && r[8] > 0.)
    {
        xy.push((row[1], row[2]));
    }
    for row in api.lines_info().chunks_exact(22).filter(|r| r[3] == 1.) {
        let line = row[0] as u32;
        if api.is_bus(line) {
            for stop in api.bus_stops(line).chunks_exact(8) {
                xy.push((stop[1], stop[2]));
            }
        }
    }
    let points: Vec<_> = xy
        .into_iter()
        .map(|(x, y)| {
            let ll = api.to_lonlat(x, y);
            (ll[0], ll[1], DEG * ll[1].to_radians().cos())
        })
        .collect();
    let mut local = Vec::new();
    let mut markets = Vec::new();
    let mut missing = Vec::new();
    for &(id, [w, s, e, n]) in ZONE_BOXES {
        let near = |&(x, y, k): &(f64, f64, f64), m: f64| {
            (w - x) * k <= m && (x - e) * k <= m && (s - y) * DEG <= m && (y - n) * DEG <= m
        };
        if !points.iter().any(|p| near(p, 30000.)) {
            continue;
        }
        markets.push(id);
        if !points.iter().any(|p| near(p, LOCAL)) {
            continue;
        }
        let path = dir.join(format!("{id}.boundary.geojson"));
        if !path.is_file() {
            missing.push(id);
            continue;
        }
        let boundary = Boundary::read(&path)?;
        if points
            .iter()
            .filter(|p| near(p, LOCAL))
            .any(|&(x, y, k)| boundary.reaches(x, y, k))
        {
            local.push(id);
        }
    }
    for zone in world.header["opened"].as_array().into_iter().flatten().filter_map(Value::as_str) {
        if let Some(&(id, _)) = ZONE_BOXES.iter().find(|row| row.0 == zone) {
            if !local.contains(&id) { local.push(id); }
            if !markets.contains(&id) { markets.push(id); }
        }
    }
    Ok(json!({"local":local,"markets":markets,"missing":missing}))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_plan(
    mut env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    dir: JString,
) -> jstring {
    let result = (|| {
        let bytes = env.convert_byte_array(bytes).map_err(|e| e.to_string())?;
        let dir: String = env.get_string(&dir).map_err(|e| e.to_string())?.into();
        plan(&bytes, Path::new(&dir))
    })()
    .unwrap_or_else(|e: String| json!({"error":e}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires network save and pinned geographic boundaries used by the web worker"]
    fn reference_coverage() {
        let save = std::fs::read(std::env::var("WORLD_SAVE").unwrap()).unwrap();
        let dir = std::env::var("WORLD_CITY_DATA").unwrap();
        let actual = plan(&save, Path::new(&dir)).unwrap();
        assert_eq!(actual["missing"], json!([]));
        for (field, var) in [
            ("local", "WORLD_LOCAL_PACKS"),
            ("markets", "WORLD_MARKET_ZONES"),
        ] {
            let expected = std::env::var(var).unwrap();
            let expected: std::collections::BTreeSet<_> = expected.split(',').collect();
            let got: std::collections::BTreeSet<_> = actual[field]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap())
                .collect();
            assert_eq!(expected, got, "{field}");
        }
    }
}
