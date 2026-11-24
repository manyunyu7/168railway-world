// SPDX-License-Identifier: AGPL-3.0-only
//! The track inspector's Signalling section (web ui/inspector/PieceSignal.tsx) and the Signals lens: four
//! levels per piece of track (`trainworld_sim::track::signal`), set on the piece or its whole block, the
//! dearer ones paid at once on built track. The engine's `TrackApi` does the work, as on the web.
use super::World;
use jni::{
    objects::{JClass, JIntArray},
    sys::{jdouble, jint, jintArray, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

/// `TrackApi::signal_panel` as the panel reads it: per level the most trains an hour each way, the
/// price and upkeep a day on the piece (US$M), the block's pieces, and per level the quotes
/// [paid now, added to the blueprint, upkeep change] for the piece and for the block.
fn panel(w: &World, e: u32) -> Value {
    let d = w.api.signal_panel(e);
    if d.len() < 14 {
        return Value::Null;
    }
    let per = |k: usize| -> Vec<f64> { (0..4).map(|l| d[1 + 3 * l + k]).collect() };
    let n = d[13] as usize;
    let block: Vec<u32> = d[14..14 + n].iter().map(|&x| x as u32).collect();
    let quotes: Vec<Value> = (0..4).map(|l| {
        let i = 14 + n + 6 * l;
        json!({"piece": &d[i..i + 3], "block": &d[i + 3..i + 6]})
    }).collect();
    json!({"level": d[0], "tph": per(0), "price": per(1), "upkeep": per(2), "block": block, "quote": quotes})
}

/// The web PieceSignal pick: `level` on these pieces, the rise in a built piece's price paid now.
fn set(w: &mut World, edges: &[u32], level: u32, clock: f64) -> Result<Value, String> {
    if edges.is_empty() || level > 3 {
        return Err("Tidak ada rel yang dipilih.".into());
    }
    let clock = w.native_clock(clock);
    if !clock.is_finite() {
        return Err("Jam permainan tidak valid.".into());
    }
    super::finance::tick(w, clock);
    w.api.set_now(clock);
    let r = w.api.set_edges_signal(edges, level as u8);
    if r != 0 {
        let issues = w.api.issue_kinds();
        if issues.is_empty() {
            return Ok(json!({"changed": false}));
        }
        return Err(if issues.starts_with("Money") { "Saldo tidak cukup untuk persinyalan ini.".into() } else { "Persinyalan ditolak di rel ini.".into() });
    }
    let charge = w.api.last_charge();
    super::track_inspection::book(w, clock);
    Ok(json!({"changed": true, "charge": charge, "cash": w.api.cash()}))
}

fn ids(env: &JNIEnv, a: &JIntArray) -> Vec<u32> {
    let n = env.get_array_length(a).unwrap_or(0).clamp(0, 1 << 20);
    let mut v = vec![0; n as usize];
    if n > 0 && env.get_int_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v.into_iter().filter(|&x| x >= 0).map(|x| x as u32).collect()
}

fn reply(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

fn world<'a>(handle: jlong) -> Option<&'a mut World> {
    unsafe { (handle as *mut World).as_mut() }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackSignal_panel(env: JNIEnv, _: JClass, handle: jlong, edge: jint) -> jstring {
    reply(&env, world(handle).map(|w| panel(w, edge as u32)).unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackSignal_set(
    env: JNIEnv, _: JClass, handle: jlong, edges: JIntArray, level: jint, clock: jdouble,
) -> jstring {
    let edges = ids(&env, &edges);
    let r = world(handle).ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|w| set(w, &edges, level.max(0) as u32, clock))
        .unwrap_or_else(|e| json!({"error": e}));
    reply(&env, r)
}

/// The Signals lens: edge and level pairs of the pieces other than closed block.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackSignal_levels(env: JNIEnv, _: JClass, handle: jlong) -> jintArray {
    let pairs: Vec<i32> = world(handle).map(|w| w.api.edge_signals().into_iter().map(|x| x as i32).collect()).unwrap_or_default();
    let Ok(out) = env.new_int_array(pairs.len() as i32) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&out, 0, &pairs).is_err() {
        return std::ptr::null_mut();
    }
    out.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;
    use trainworld_sim::track::params::{SIGNAL_CBTC, SIGNAL_CLOSED, SIGNAL_OPEN};

    fn paid_world() -> (World, f64, u32) {
        let mut w = World::workshop();
        let clock = 111600.;
        w.build_service([0., 0.], [1600., 0.], "Lintas", clock - 86400.).unwrap();
        super::super::finance::tick(&mut w, clock);
        w.header["mode"] = "normal".into();
        w.api.set_unlimited(false);
        w.api.set_cash(5000.);
        w.header["cash"] = 5000.into();
        let net = &w.api.world().net;
        let e = (0..net.edge_count() as u32)
            .filter(|&e| net.edge_ok(e) && net.edge_built[e as usize] && !net.edge_crossover[e as usize])
            .max_by(|&a, &b| net.edge_len[a as usize].total_cmp(&net.edge_len[b as usize]))
            .unwrap();
        (w, clock, e)
    }

    #[test]
    fn the_panel_reads_four_levels_and_the_block() {
        let (w, _, e) = paid_world();
        let p = panel(&w, e);
        assert_eq!(p["level"], SIGNAL_CLOSED as f64);
        let tph: Vec<f64> = p["tph"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        assert!(tph[0] < tph[1] && tph[1] < tph[2] && tph[2] < tph[3], "{tph:?}");
        assert!(p["block"].as_array().unwrap().iter().any(|b| b == e));
        assert_eq!(p["quote"].as_array().unwrap().len(), 4);
        assert!(p["quote"][3]["piece"][0].as_f64().unwrap() > 0.);
        assert_eq!(panel(&w, u32::MAX), Value::Null);
    }

    #[test]
    fn cbtc_is_paid_booked_and_saved_and_open_block_is_free() {
        let (mut w, clock, e) = paid_world();
        let cash = w.api.cash();
        let price = panel(&w, e)["quote"][3]["piece"][0].as_f64().unwrap();
        let r = set(&mut w, &[e], SIGNAL_CBTC as u32, clock).unwrap();
        assert_eq!(r["changed"], true);
        assert!((cash - w.api.cash() - price).abs() < 1e-6, "{} paid, {price} quoted", cash - w.api.cash());
        assert_eq!(w.header["cash"].as_f64().unwrap(), w.api.cash());
        assert_eq!(w.api.edge_signal(e), SIGNAL_CBTC);
        // the network save carries it (the shared save format, LSG1)
        let saved = w.api.save();
        let mut back = trainworld_sim::track::wasm::TrackApi::new(0., 0., true);
        assert!(back.load(&saved));
        assert_eq!(back.edge_signal(e), SIGNAL_CBTC);
        // the same again changes nothing; open block costs nothing
        assert_eq!(set(&mut w, &[e], SIGNAL_CBTC as u32, clock).unwrap()["changed"], false);
        let cash = w.api.cash();
        assert_eq!(set(&mut w, &[e], SIGNAL_OPEN as u32, clock).unwrap()["changed"], true);
        assert_eq!(w.api.cash(), cash);
        // short of cash: refused
        w.api.set_cash(0.);
        assert!(set(&mut w, &[e], SIGNAL_CBTC as u32, clock).is_err());
    }
}
