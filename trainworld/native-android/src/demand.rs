//! Commute assignment for a snapshot of the native editor. A background thread owns both
//! the snapshot and solver so advancing trains and editing the live track stay independent.
use super::World;
use jni::{
    objects::{JByteArray, JClass},
    sys::{jint, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap};
use trainworld_sim::{
    demand::api::DemandApi,
    track::frame::{Frame, FrameKind},
};

fn commute(save: &[u8], header: &str, cells: &[u8], period: usize) -> Result<Value, String> {
    let world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
    let api = world.api;
    let city: Value = serde_json::from_str(header).map_err(|e| e.to_string())?;
    let origin = &city["origin"];
    let frame = Frame::new(
        FrameKind::Equirect,
        origin["lon"].as_f64().ok_or("Bujur tidak ada")?,
        origin["lat"].as_f64().ok_or("Lintang tidak ada")?,
    );
    let nodes = api.nodes_info();
    let coords: HashMap<u32, (f64, f64)> = nodes
        .chunks_exact(10)
        .map(|r| (r[0] as u32, (r[1], r[2])))
        .collect();
    let rows = api.lines_info();
    let mut lines = Vec::new();
    let mut ids = BTreeSet::new();
    for row in rows.chunks_exact(22).filter(|r| r[3] == 1.) {
        let id = row[0] as u32;
        let stops = api.line_stops(id);
        let reverse = api.line_back_stops(id);
        if stops.len() < 2 || !stops.iter().all(|id| coords.contains_key(id)) {
            continue;
        }
        let back =
            if reverse.len() == stops.len() && reverse.iter().all(|id| coords.contains_key(id)) {
                reverse
            } else {
                stops.clone()
            };
        ids.extend(stops.iter().chain(&back).copied());
        lines.push((id, row, stops, back));
    }
    let mut xy = Vec::new();
    let mut ix = HashMap::new();
    for id in ids {
        ix.insert(id, (xy.len() / 2) as u32);
        let (x, y) = coords[&id];
        let ll = api.to_lonlat(x, y);
        let (px, py) = frame.from_lonlat(ll[0], ll[1]);
        xy.extend([px as f32, py as f32]);
    }
    let (mut counts, mut stops, mut reverse, mut times, mut tph, mut cars) =
        (vec![], vec![], vec![], vec![], vec![], vec![]);
    for (id, row, served, back) in &lines {
        let n = served.len();
        counts.push(n as u32);
        stops.extend(served.iter().map(|id| ix[id]));
        reverse.extend(back.iter().map(|id| ix[id]));
        tph.extend(row[5..8].iter().map(|v| *v as f32));
        cars.push(if api.is_bus(*id) {
            1. / 3.
        } else if api.is_ferry(*id) {
            2.
        } else {
            row[21] as f32
        });
        for run in 0..2 {
            for level in 0..3 {
                let arr = api.stop_times(*id, run, level);
                let dep = api.stop_departures(*id, run, level);
                let mut v = vec![0.; n];
                for j in 0..n
                    .saturating_sub(1)
                    .min(arr.len().saturating_sub(1))
                    .min(dep.len())
                {
                    let dwell = if j == 0 { 0. } else { dep[j] - arr[j] };
                    v[if run == 0 { j } else { n - 1 - j }] = (arr[j + 1] - dep[j] + dwell) as f32;
                }
                if api.line_loop(*id) == 1 {
                    v[if run == 0 { n - 1 } else { 0 }] = row[19].max(1.) as f32
                }
                times.extend(v);
            }
        }
    }
    #[cfg(test)]
    if let Ok(path) = std::env::var("WORLD_NETWORK_EXPORT") {
        let packed = json!({"xy":xy,"counts":counts,"stops":stops,"back":reverse,"times":times,"tph":tph,"cars":cars});
        std::fs::write(path, packed.to_string()).map_err(|e| e.to_string())?;
    }
    let mut solver = DemandApi::new(header, cells)?;
    let mut outings =
        trainworld_sim::demand::everyday::EverydayApi::beside(&solver, header, cells)?;
    solver.set_back_stops(&reverse);
    outings.set_back_stops(&reverse);
    solver.set_network(&xy, &counts, &stops, &times, &tph, &cars)?;
    outings.set_network(&xy, &counts, &stops, &times, &tph, &cars)?;
    if !solver.solve(period) || !outings.solve(period) {
        return Err("Periode perjalanan tidak tersedia".into());
    }
    // Match the web worker's three rounds: freeze the combined loads before advancing
    // either population, since each shares the same seats and boarding capacity.
    for _ in 0..3 {
        let mut frozen = Vec::new();
        for (commute, everyday) in [
            (solver.seg(period), outings.seg(period)),
            (solver.board(period), outings.board(period)),
            (solver.alight(period), outings.alight(period)),
        ] {
            if commute.len() != everyday.len() {
                return Err("Susunan ruas perjalanan berbeda".into());
            }
            frozen.extend(commute.iter().zip(everyday).map(|(a, b)| a + b));
        }
        if !solver.crowd_with_external(period, &frozen)
            || !outings.crowd_with_external(period, &frozen)
        {
            return Err("Perhitungan kapasitas tidak tersedia".into());
        }
    }
    let work = solver.summary(period);
    let other = outings.summary(period);
    let boardings = solver
        .board(period)
        .into_iter()
        .chain(outings.board(period))
        .map(f64::from)
        .sum::<f64>();
    Ok(
        json!({"period":period,"lines":lines.len(),"stations":xy.len()/2,"rounds":work[0],"commuteRail":work[2],"outingsRail":other[2],"trips":work[1]+other[1],"rail":work[2]+other[2],"walk":work[3]+other[3],"unserved":work[13]+other[13],"boardings":boardings}),
    )
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_estimate(
    env: JNIEnv,
    _: JClass,
    save: JByteArray,
    header: JByteArray,
    cells: JByteArray,
    period: jint,
) -> jstring {
    let result = (|| {
        let read = |a| env.convert_byte_array(a).map_err(|e| e.to_string());
        let h = String::from_utf8(read(header)?).map_err(|e| e.to_string())?;
        commute(&read(save)?, &h, &read(cells)?, period as usize)
    })()
    .unwrap_or_else(|e| json!({"error":e}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires the pinned city pack and an original network save"]
    fn city_commute() {
        let dir = std::path::PathBuf::from(std::env::var("WORLD_CITY_DATA").unwrap());
        let id = std::env::var("WORLD_CITY").unwrap();
        let read = std::fs::read;
        let save = read(std::path::PathBuf::from(
            std::env::var("WORLD_SAVE").unwrap(),
        ))
        .unwrap();
        let h = std::fs::read_to_string(dir.join(format!("{id}.json"))).unwrap();
        let b = read(dir.join(format!("{id}.bin"))).unwrap();
        let r = commute(&save, &h, &b, 0).unwrap();
        eprintln!("{r}");
        if let Ok(path) = std::env::var("WORLD_RESULT_EXPORT") {
            std::fs::write(path, r.to_string()).unwrap();
        }
        assert_eq!(r["rounds"].as_f64(), Some(3.0));
        assert!(r["outingsRail"].as_f64().unwrap() > 0.);
        assert!(r["rail"].as_f64().unwrap() > 0.);
        assert!(r["rail"].as_f64().unwrap() < r["trips"].as_f64().unwrap());
        assert!(r["boardings"].as_f64().unwrap() > 0.);
    }
}
