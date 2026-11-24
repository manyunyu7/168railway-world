// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use rquickjs::{ArrayBuffer, Context, Function, Object, Runtime};
use std::{
    collections::BTreeSet,
    fs,
    path::Path,
    time::{Duration, Instant},
};

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
) -> Result<Value, String> {
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
                    street.is_some_and(|(rail, _)| rail),
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
        return Err(e.into());
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
    let mut output = resolve(api, &points, dir, Some((rail, speed)))?;
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
) -> Result<u32, String> {
    let output = resolve(api, points, dir, None)?;
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
        return Err(format!("Jalur bus ditolak: {}", api.issue_kinds()));
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
