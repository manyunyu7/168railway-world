// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use rquickjs::{ArrayBuffer, Context, Function, Object, Runtime};
use std::{
    collections::BTreeSet,
    fs,
    path::Path,
    sync::atomic::{AtomicU8, Ordering},
    time::{Duration, Instant},
};

/// The roads a bus route may use, chosen while drawing (Android only; the owner asked for it):
/// every road a bus drives (web `busRoad`).
pub const ALL_ROADS: u8 = 0;
/// The main roads only: web `RAIL_CLASSES` (trunk, primary, secondary, tertiary) and motorways.
pub const MAIN_ROADS: u8 = 1;
/// Every bus road but motorways (web `busRoadFilter(false)`).
pub const NO_TOLL: u8 = 2;

/// The choice the draft's preview and `Engine.build` mode 5 both route with, so the built route is
/// the one previewed. Not part of the save: the built route keeps the roads it was laid on.
static BUS_ROADS: AtomicU8 = AtomicU8::new(ALL_ROADS);

pub(super) fn roads() -> u8 {
    BUS_ROADS.load(Ordering::Relaxed)
}

/// Why a narrowed choice found no route, in place of the planner's own words.
fn narrowed(roads: u8) -> Option<&'static str> {
    match roads {
        MAIN_ROADS => Some("Tidak ada jalur lewat jalan besar saja. Coba Semua jalan."),
        NO_TOLL => Some("Tidak ada jalur tanpa tol. Coba Semua jalan."),
        _ => None,
    }
}

pub fn geographic(api: &TrackApi, points: &[[f64; 2]]) -> Vec<[f64; 2]> {
    points
        .iter()
        .map(|p| {
            let ll = api.to_lonlat(p[0], p[1]);
            [ll[0], ll[1]]
        })
        .collect()
}
fn tile([lon, lat]: [f64; 2]) -> (i32, i32) {
    let n = 16384.;
    (
        ((lon + 180.) / 360. * n).floor() as i32,
        ((1. - lat.to_radians().tan().asinh() / std::f64::consts::PI) * 0.5 * n).floor() as i32,
    )
}
fn tiles(points: &[[f64; 2]]) -> Result<BTreeSet<(i32, i32)>, String> {
    if points.iter().any(|[lon, lat]| {
        !lon.is_finite() || !lat.is_finite() || lon.abs() > 180. || lat.abs() > 85.
    }) {
        return Err("Titik berada di luar cakupan peta jalan.".into());
    }
    let mut keys = BTreeSet::new();
    for leg in points.windows(2) {
        let (a, b) = (tile(leg[0]), tile(leg[1]));
        let (x0, x1) = (a.0.min(b.0) - 1, a.0.max(b.0) + 1);
        let (y0, y1) = (a.1.min(b.1) - 1, a.1.max(b.1) + 1);
        if x0 < 0 || y0 < 0 || x1 >= 16384 || y1 >= 16384 || (x1 - x0 + 1) * (y1 - y0 + 1) > 49 {
            return Err(
                "Ruas jalan terlalu panjang. Tambah titik lintasan yang lebih dekat.".into(),
            );
        }
        for x in x0..=x1 {
            for y in y0..=y1 {
                keys.insert((x, y));
            }
        }
        if keys.len() > 64 {
            return Err("Pilih lintasan jalan yang lebih pendek.".into());
        }
    }
    Ok(keys)
}

pub(super) fn resolve(
    api: &TrackApi,
    points: &[[f64; 2]],
    dir: &Path,
    street: Option<(bool, f64)>,
    roads: u8,
) -> Result<Value, String> {
    // A street-following rail or tram line loads the main roads or every road, as before.
    let keep = match street {
        Some((rail, _)) => {
            if rail {
                MAIN_ROADS
            } else {
                ALL_ROADS
            }
        }
        None if roads <= NO_TOLL => roads,
        None => return Err("Pilihan jalan tidak dikenal.".into()),
    };
    let geographic = geographic(api, points);
    let want = tiles(&geographic)?;
    let rt = Runtime::new().map_err(|e| e.to_string())?;
    rt.set_memory_limit(160 << 20);
    let stop = Instant::now() + Duration::from_secs(30);
    rt.set_interrupt_handler(Some(Box::new(move || Instant::now() > stop)));
    let ctx = Context::full(&rt).map_err(|e| e.to_string())?;
    let read = ctx.with(|c| -> Result<String, String> {
        let js = |e: rquickjs::Error| format!("Perencana jalan gagal: {e}");
        c.eval::<(), _>(include_str!("../roads/text-decoder.js"))
            .map_err(js)?;
        c.eval::<(), _>(include_str!("../roads/planner.js"))
            .map_err(js)?;
        let roads: Object = c.globals().get("Roads").map_err(js)?;
        let load: Function = roads.get("addTile").map_err(js)?;
        let mut total = 0;
        for (x, y) in want {
            let bytes = fs::read(dir.join(format!("14-{x}-{y}.pbf")))
                .map_err(|_| "Unduh data jalan untuk lintasan ini terlebih dahulu.")?;
            total += bytes.len();
            if bytes.len() > 2 << 20 || total > 40 << 20 {
                return Err("Data jalan terlalu besar untuk satu layanan.".into());
            }
            if !bytes.is_empty() {
                load.call::<_, ()>((
                    x,
                    y,
                    ArrayBuffer::new_copy(c.clone(), bytes).map_err(js)?,
                    keep,
                ))
                .map_err(js)?;
            }
        }
        let coords = serde_json::to_string(&geographic).map_err(|e| e.to_string())?;
        match street {
            Some((rail, speed)) => roads
                .get::<_, Function>("follow")
                .map_err(js)?
                .call::<_, String>((coords, rail, speed))
                .map_err(js),
            None => roads
                .get::<_, Function>("plan")
                .map_err(js)?
                .call::<_, String>((coords,))
                .map_err(js),
        }
    })?;
    let output: Value = serde_json::from_str(&read).map_err(|e| e.to_string())?;
    if let Some(e) = output["error"].as_str() {
        return Err(street
            .is_none()
            .then(|| narrowed(keep))
            .flatten()
            .unwrap_or(e)
            .into());
    }
    Ok(output)
}

pub(super) fn street_route(
    api: &TrackApi,
    coords: &[f64],
    dir: &Path,
    rail: bool,
    speed: f64,
) -> Result<Vec<f64>, String> {
    let points = coords
        .chunks_exact(2)
        .map(|p| [p[0], p[1]])
        .collect::<Vec<_>>();
    let mut output = resolve(api, &points, dir, Some((rail, speed)), ALL_ROADS)?;
    let line: Vec<f64> = serde_json::from_value(output["line"].take())
        .map_err(|_| "Lintasan jalan tidak terbaca.")?;
    if line.len() < 4 || line.len() % 2 != 0 || line.len() > 64_000 {
        return Err("Pilih ruas jalan yang lebih pendek.".into());
    }
    Ok(line)
}

pub fn build(
    api: &mut TrackApi,
    points: &[[f64; 2]],
    name: &str,
    dir: &Path,
    roads: u8,
) -> Result<u32, String> {
    let output = resolve(api, points, dir, None, roads)?;
    let pts: Vec<[f64; 3]> =
        serde_json::from_value(output["points"].clone()).map_err(|e| e.to_string())?;
    let route: Vec<_> = pts
        .iter()
        .flat_map(|v| {
            let xy = api.from_lonlat(v[0], v[1]);
            [xy[0], xy[1], v[2]]
        })
        .collect();
    let id = api.new_line_id();
    if api.set_bus_line(id, &route, 480, 720, 1200, 20., 60., 0xC16A14, name.into()) != 0
        || api.construct_line(id) != 0
    {
        return Err(format!("Jalur bus ditolak: {}", crate::issue_text::describe(&api.issue_kinds())));
    }
    Ok(id)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_requiredRoads(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    coords: jni::objects::JDoubleArray,
) -> jstring {
    let result = (|| -> Result<_, String> {
        let n = env.get_array_length(&coords).map_err(|e| e.to_string())? as usize;
        if n < 4 || n > 176 || n % 2 != 0 {
            return Err("Titik rute tidak valid.".into());
        }
        let mut raw = vec![0.; n];
        env.get_double_array_region(&coords, 0, &mut raw)
            .map_err(|e| e.to_string())?;
        if raw.iter().any(|p| !p.is_finite()) {
            return Err("Koordinat tidak valid.".into());
        }
        let w = unsafe { (handle as *const World).as_ref() }.ok_or("Dunia belum tersedia.")?;
        let pairs = raw
            .chunks_exact(2)
            .map(|p| [p[0], p[1]])
            .collect::<Vec<_>>();
        Ok(json!({"tiles":tiles(&geographic(&w.api,&pairs))?}))
    })()
    .unwrap_or_else(|e| json!({"error":e}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// The draft's road choice (`ALL_ROADS`, `MAIN_ROADS`, `NO_TOLL`); anything else is every road.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_BusRoads_set(
    _: JNIEnv,
    _: JClass,
    roads: jni::sys::jint,
) {
    let v = if (0..=NO_TOLL as i32).contains(&roads) {
        roads as u8
    } else {
        ALL_ROADS
    };
    BUS_ROADS.store(v, Ordering::Relaxed);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn varint(out: &mut Vec<u8>, mut v: u64) {
        while v >= 0x80 {
            out.push(v as u8 | 0x80);
            v >>= 7;
        }
        out.push(v as u8);
    }
    fn field(out: &mut Vec<u8>, f: u64, bytes: &[u8]) {
        varint(out, f << 3 | 2);
        varint(out, bytes.len() as u64);
        out.extend(bytes);
    }
    fn zig(v: i64) -> u64 {
        ((v << 1) ^ (v >> 63)) as u64
    }

    /// One OpenMapTiles vector tile whose `transportation` layer holds `roads`: class and line
    /// points in tile units (extent 4096).
    fn mvt(roads: &[(&str, &[(i64, i64)])]) -> Vec<u8> {
        let classes: Vec<&str> = roads.iter().map(|r| r.0).collect();
        let mut layer = vec![];
        field(&mut layer, 1, b"transportation");
        for (class, pts) in roads {
            let mut geom = vec![];
            let (mut x, mut y) = (0, 0);
            for (i, &(px, py)) in pts.iter().enumerate() {
                if i == 0 {
                    varint(&mut geom, 1 | 1 << 3);
                } else if i == 1 {
                    varint(&mut geom, 2 | ((pts.len() as u64 - 1) << 3));
                }
                varint(&mut geom, zig(px - x));
                varint(&mut geom, zig(py - y));
                (x, y) = (px, py);
            }
            let mut tags = vec![];
            varint(&mut tags, 0);
            varint(
                &mut tags,
                classes.iter().position(|c| c == class).unwrap() as u64,
            );
            let mut feature = vec![];
            field(&mut feature, 2, &tags);
            varint(&mut feature, 3 << 3);
            varint(&mut feature, 2);
            field(&mut feature, 4, &geom);
            field(&mut layer, 2, &feature);
        }
        field(&mut layer, 3, b"class");
        for class in &classes {
            let mut value = vec![];
            field(&mut value, 1, class.as_bytes());
            field(&mut layer, 4, &value);
        }
        varint(&mut layer, 5 << 3);
        varint(&mut layer, 4096);
        varint(&mut layer, 15 << 3);
        varint(&mut layer, 2);
        let mut out = vec![];
        field(&mut out, 3, &layer);
        out
    }

    /// Frame point of tile (tx, ty)'s unit point (ux, uy).
    fn at(api: &TrackApi, (tx, ty): (i32, i32), ux: f64, uy: f64) -> [f64; 2] {
        let n = 16384.;
        let lon = (tx as f64 + ux / 4096.) / n * 360. - 180.;
        let my = std::f64::consts::PI * (1. - 2. * (ty as f64 + uy / 4096.) / n);
        let lat = my.sinh().atan().to_degrees();
        let p = api.from_lonlat(lon, lat);
        [p[0], p[1]]
    }

    /// Frame point back to its unit point in tile `t`.
    fn units(api: &TrackApi, (tx, ty): (i32, i32), x: f64, y: f64) -> (f64, f64) {
        let ll = api.to_lonlat(x, y);
        let n = 16384. * 4096.;
        let ux = (ll[0] + 180.) / 360. * n;
        let uy = (1. - ll[1].to_radians().tan().asinh() / std::f64::consts::PI) * 0.5 * n;
        (ux - tx as f64 * 4096., uy - ty as f64 * 4096.)
    }

    /// A town of one tile: from A to B a straight minor street, and a primary road round it;
    /// from C to D only a motorway with a minor street to each end.
    fn town(name: &str) -> (World, (i32, i32), std::path::PathBuf) {
        let mut w = World::workshop();
        let origin = w.api.to_lonlat(0., 0.);
        let t = tile([origin[0], origin[1]]);
        let dir = std::env::temp_dir().join(format!("bus-roads-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for x in t.0 - 2..=t.0 + 2 {
            for y in t.1 - 2..=t.1 + 2 {
                std::fs::write(dir.join(format!("14-{x}-{y}.pbf")), b"").unwrap();
            }
        }
        let bytes = mvt(&[
            ("minor", &[(800, 2000), (3200, 2000)]),
            (
                "primary",
                &[(800, 2000), (800, 1400), (3200, 1400), (3200, 2000)],
            ),
            ("minor", &[(800, 3600), (800, 3200)]),
            ("motorway", &[(800, 3200), (3200, 3200)]),
            ("minor", &[(3200, 3200), (3200, 3600)]),
        ]);
        std::fs::write(dir.join(format!("14-{}-{}.pbf", t.0, t.1)), bytes).unwrap();
        w.roads_dir = Some(dir.clone());
        (w, t, dir)
    }

    fn via(points: &[[f64; 2]]) -> Vec<[f64; 3]> {
        points.iter().map(|p| [p[0], p[1], 1.]).collect()
    }

    #[test]
    fn main_roads_only_keeps_the_route_off_minor_streets_in_preview_and_build() {
        let (mut w, t, dir) = town("main");
        let ab = [at(&w.api, t, 800., 2000.), at(&w.api, t, 3200., 2000.)];
        let before = w.save(111600.).unwrap();

        let all = w.bus_preview(&via(&ab), ALL_ROADS).unwrap();
        let main = w.bus_preview(&via(&ab), MAIN_ROADS).unwrap();
        assert_eq!(
            before,
            w.save(111600.).unwrap(),
            "a preview changes nothing"
        );
        let (km_all, km_main) = (all["km"].as_f64().unwrap(), main["km"].as_f64().unwrap());
        // The minor street is the straight 2400 units; the primary road is 3600.
        assert!((km_main / km_all - 1.5).abs() < 0.05, "{km_all} {km_main}");
        let path: Vec<f64> = serde_json::from_value(main["path"].clone()).unwrap();
        let off_minor = |api: &TrackApi, path: &[f64]| {
            // Each point and each stretch's middle.
            let pts: Vec<[f64; 2]> = path.chunks_exact(2).map(|p| [p[0], p[1]]).collect();
            let mids = pts
                .windows(2)
                .map(|s| [(s[0][0] + s[1][0]) / 2., (s[0][1] + s[1][1]) / 2.]);
            pts.iter().copied().chain(mids).all(|p| {
                let (ux, uy) = units(api, t, p[0], p[1]);
                // Nothing along the minor street's line between its ends.
                !((ux - 1000.).min(3000. - ux) > 0. && (uy - 2000.).abs() < 50.)
            })
        };
        assert!(off_minor(&w.api, &path), "{path:?}");
        let all_path: Vec<f64> = serde_json::from_value(all["path"].clone()).unwrap();
        assert!(
            !off_minor(&w.api, &all_path),
            "every road takes the minor street"
        );

        // The build lays the same route as the preview.
        let line = build(&mut w.api, &ab, "Bus Raya", &dir, MAIN_ROADS).unwrap();
        let route = w.api.bus_route(line);
        let built: Vec<f64> = route[1..]
            .chunks_exact(3)
            .flat_map(|p| [p[0], p[1]])
            .collect();
        assert_eq!(built.len(), path.len(), "the build is the preview");
        assert!(off_minor(&w.api, &built));
    }

    #[test]
    fn narrowed_roads_without_a_route_say_so() {
        let (w, t, _) = town("narrow");
        let cd = [at(&w.api, t, 800., 3500.), at(&w.api, t, 3200., 3500.)];
        assert!(w.bus_preview(&via(&cd), ALL_ROADS).is_ok());
        assert_eq!(
            w.bus_preview(&via(&cd), NO_TOLL).unwrap_err(),
            "Tidak ada jalur tanpa tol. Coba Semua jalan."
        );
        assert_eq!(
            w.bus_preview(&via(&cd), MAIN_ROADS).unwrap_err(),
            "Tidak ada jalur lewat jalan besar saja. Coba Semua jalan."
        );
        assert!(w.bus_preview(&via(&cd), 7).is_err());
    }
}
