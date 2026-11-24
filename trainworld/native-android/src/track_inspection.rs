// SPDX-License-Identifier: AGPL-3.0-only
//! The web track inspector (ui/inspector/TrackInspector.tsx) for one built piece of track: what it is,
//! its top speed and the player's limit on it (PieceLimit.tsx), its cost and upkeep, the lines on it,
//! and rebuilding it in place (PieceRebuild.tsx, game/rebuild.ts) at a quoted price.
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use trainworld_sim::track::{cost, params::{TrackKind, ECONOMY}};

/// `rebuild_quote`'s level argument for "keep the level" (anything outside -3..=3).
const KEEP_LEVEL: i32 = 99;
/// Web `LEVELS` (game/types.ts), top first.
const LEVELS: [i32; 7] = [3, 2, 1, 0, -1, -2, -3];
/// Web `KIND` (game/rebuild.ts): 1 regular, 2 high speed, 3 tram; 0 keeps.
const REGULAR: i32 = 1;
const HSR: i32 = 2;
const TRAM: i32 = 3;
/// Web PieceLimit `MIN`: the lowest limit a piece takes, km/h.
const LIMIT_MIN: u32 = 40;

fn is_tram(k: TrackKind) -> bool {
    matches!(k, TrackKind::StreetTram | TrackKind::Tram)
}

fn built_piece(w: &World, e: u32) -> bool {
    let net = &w.api.world().net;
    net.edge_ok(e) && net.edge_built[e as usize]
}

/// `edges_info`'s row for `e`: id, a, b, tracks, built, length m, cost US$M, slowest curve km/h, lowest and
/// highest level.
fn info_row(w: &World, e: u32) -> Option<[f64; 10]> {
    w.api.edges_info().chunks_exact(10).find(|r| r[0] as u32 == e).and_then(|r| r.try_into().ok())
}

/// Web `rebuildWhole`: the quote changes something and refuses nothing.
fn whole(q: &[f64]) -> bool {
    q.len() >= 5 && q[3] == 0. && (q[1] > 0. || q[2] > 0.)
}

/// Web `fetchRebuildQuote` for piece `e` alone, in the words and figures the panel needs.
fn quote(w: &mut World, e: u32, level: i32, kind: i32, tracks: i32) -> Value {
    let q = w.api.rebuild_quote(&[e], &[], level, kind, tracks);
    let issues = w.api.issue_kinds();
    if q.len() < 5 {
        return json!({"price":0,"changed":0,"stations":0,"refused":0,"length":0,"why":""});
    }
    let (changed, refused) = (q[1] as usize, q[3] as usize);
    let net = &w.api.world().net;
    let ids = &q[5 + refused..5 + refused + changed];
    // web `quoteLength`: the changed edges and the stretches beyond the pick the station ramps take
    let beyond: f64 = q[5 + refused + changed..].chunks_exact(3).map(|b| b[2]).sum();
    let length = ids.iter().map(|&i| net.edge_len.get(i as usize).copied().unwrap_or(0.)).sum::<f64>() + beyond;
    // web `describeIssues` of the `NoSingle:` kinds: why double track stays double
    let why: Vec<&str> = issues.split('\n').filter_map(|k| match k {
        "NoSingle:Crossover" => Some("Crossover butuh rel ganda."),
        "NoSingle:OneWay" => Some("Ada rel satu arah yang tersambung ke salah satu dari dua relnya."),
        "NoSingle:Flyover" => Some("Flyover butuh rel ganda."),
        _ => None,
    }).collect();
    json!({"price":q[0],"changed":changed,"stations":q[2],"refused":refused,"length":length,"why":why.join(" ")})
}

/// The panel for built piece `e`, or None when it is not (or no longer) built track.
fn read(w: &mut World, e: u32) -> Option<Value> {
    if !built_piece(w, e) {
        return None;
    }
    let row = info_row(w, e)?;
    let i = e as usize;
    let (kind, crossover, limit, lines) = {
        let net = &w.api.world().net;
        let lines: Vec<u32> = net.lines_on_edge(e).iter().copied().filter(|&l| net.line_ok(l)).collect();
        (net.edge_kind[i], net.edge_crossover[i], net.edge_limit[i], lines)
    };
    let tracks = row[3] as u8;
    let (lo, hi) = (row[8] as i32, row[9] as i32);
    // web `edgeUpkeep` (regionMoney.ts) as the track model counts it (`edge_upkeep`), US$M a day
    let upkeep = if crossover { 0. } else {
        // and its signalling's (ATP, CBTC), as `edge_upkeep` counts it
        let sig = w.api.edge_signal(e);
        row[5] / 1000. * (cost::track_upkeep_km(lo, hi) + cost::signal_upkeep_km(sig)) * cost::track_factor(tracks, kind) * ECONOMY / 1e6
    };
    let colours: std::collections::HashMap<u32, u32> =
        w.api.lines_info().chunks_exact(22).map(|r| (r[0] as u32, r[1] as u32)).collect();
    let lines: Vec<Value> = lines.iter().map(|&l| json!({"id":l,"name":w.api.line_name(l),"color":colours.get(&l).copied().unwrap_or(0)})).collect();
    // web PieceRebuild `open`: the levels and the other family's kinds this piece takes alone
    let (mut levels, mut kinds) = (vec![], vec![]);
    if !crossover {
        let own = (lo == hi).then_some(lo);
        for level in LEVELS {
            if Some(level) != own && whole(&w.api.rebuild_quote(&[e], &[], level, 0, 0)) {
                levels.push(level);
            }
        }
        for k in if is_tram(kind) { vec![REGULAR, HSR] } else { vec![TRAM] } {
            if whole(&w.api.rebuild_quote(&[e], &[], KEEP_LEVEL, k, 0)) {
                kinds.push(k);
            }
        }
    }
    Some(json!({
        "id": e, "kind": kind as u8, "hsr": kind.is_hsr(), "tram": is_tram(kind), "crossover": crossover,
        "tracks": tracks, "length": row[5], "cost": row[6], "slowest": row[7], "levelMin": lo, "levelMax": hi,
        "top": (kind.v_cap() * 3.6).round(), "limit": limit, "upkeep": upkeep, "lines": lines,
        "levels": levels, "kinds": kinds,
    }))
}

/// Books a paid edit the way the station facilities do: cash and fleets into the header, the charge
/// into the ledger, and the running costs worked out again.
pub(super) fn book(w: &mut World, clock: f64) {
    let charge = w.api.last_charge();
    let trains = w.api.last_train_charge();
    w.header["cash"] = w.api.cash().into();
    for (key, value) in [
        ("fleet", w.api.fleet()),
        ("fleetFast", w.api.fleet_fast()),
        ("fleetTram", w.api.fleet_tram()),
        ("fleetBus", w.api.fleet_bus()),
        ("fleetFerry", w.api.fleet_ferry()),
    ] {
        w.header[key] = value.into();
    }
    super::finance::book_charge(&mut w.header, clock, charge, trains);
    w.hour = i64::MIN;
    w.operating = None;
}

fn refusal(issues: &str) -> String {
    let first = issues.split('\n').next().unwrap_or("");
    match first.split(':').next().unwrap_or(first) {
        "Money" => "Saldo tidak cukup untuk membangun ulang rel ini.".into(),
        "TerrainMissing" => "Data medan di sini gagal dimuat. Coba lagi.".into(),
        "TrackFamily" => "Rel trem dan rel kereta tidak bisa disambung. Keduanya bisa bersilangan.".into(),
        "" => "Tidak ada yang bisa dibangun ulang.".into(),
        _ => format!("Studio menolak bangun ulang. {issues}"),
    }
}

/// Web `rebuildPieces` once asked: rebuilds `e` to the choice (a 0 or out-of-range keeps), paid in full.
fn rebuild(w: &mut World, e: u32, level: i32, kind: i32, tracks: i32, clock: f64) -> Result<Value, String> {
    if !built_piece(w, e) {
        return Err("Rel ini sudah tidak ada.".into());
    }
    let clock = w.native_clock(clock);
    if !clock.is_finite() {
        return Err("Jam permainan tidak valid.".into());
    }
    super::finance::tick(w, clock);
    w.api.set_now(clock);
    if w.api.rebuild_pieces(&[e], &[], level, kind, tracks) != 0 {
        return Err(refusal(&w.api.issue_kinds()));
    }
    let skipped = w.api.last_skipped();
    let charge = w.api.last_charge();
    book(w, clock);
    Ok(json!({"changed":true,"charge":charge,"skipped":skipped,"cash":w.api.cash()}))
}

/// Web PieceLimit's edit: `kmh` 0 takes the limit off; otherwise from 40 to the kind of track's top speed.
/// Free.
fn limit(w: &mut World, e: u32, kmh: u32, clock: f64) -> Result<Value, String> {
    if !built_piece(w, e) {
        return Err("Rel ini sudah tidak ada.".into());
    }
    let kmh = if kmh == 0 { 0 } else { kmh.max(LIMIT_MIN) };
    let clock = w.native_clock(clock);
    w.api.set_now(clock);
    let changed = w.api.set_edges_limit(&[e], kmh) == 0;
    if !changed && !w.api.issue_kinds().is_empty() {
        return Err("Batas laju ditolak di rel ini.".into());
    }
    if changed {
        w.hour = i64::MIN;
        w.operating = None;
    }
    Ok(json!({"changed":changed,"limit":w.api.world().net.edge_limit[e as usize]}))
}

fn reply(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

fn world<'a>(handle: jlong) -> Option<&'a mut World> {
    unsafe { (handle as *mut World).as_mut() }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackInspector_read(env: JNIEnv, _: JClass, handle: jlong, edge: jint) -> jstring {
    reply(&env, world(handle).and_then(|w| read(w, edge as u32)).unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackInspector_quote(
    env: JNIEnv, _: JClass, handle: jlong, edge: jint, level: jint, kind: jint, tracks: jint,
) -> jstring {
    reply(&env, world(handle).map(|w| quote(w, edge as u32, level, kind, tracks)).unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackInspector_rebuild(
    env: JNIEnv, _: JClass, handle: jlong, edge: jint, level: jint, kind: jint, tracks: jint, clock: jdouble,
) -> jstring {
    let r = world(handle).ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|w| rebuild(w, edge as u32, level, kind, tracks, clock))
        .unwrap_or_else(|e| json!({"error":e}));
    reply(&env, r)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrackInspector_limit(
    env: JNIEnv, _: JClass, handle: jlong, edge: jint, kmh: jint, clock: jdouble,
) -> jstring {
    let r = world(handle).ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|w| limit(w, edge as u32, kmh.max(0) as u32, clock))
        .unwrap_or_else(|e| json!({"error":e}));
    reply(&env, r)
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn inspector_reads_the_piece_and_its_line() {
        let (mut w, _, e) = paid_world();
        let p = read(&mut w, e).unwrap();
        assert_eq!(p["id"], e);
        assert!(p["length"].as_f64().unwrap() > 100.);
        assert_eq!(p["levelMin"], 0);
        assert_eq!(p["top"], 160.);
        assert_eq!(p["limit"], 0);
        assert!(p["upkeep"].as_f64().unwrap() > 0.);
        assert_eq!(p["lines"][0]["name"], "Lintas");
        assert!(p["levels"].as_array().unwrap().iter().all(|l| l != 0));
        assert!(read(&mut w, u32::MAX).is_none());
    }

    #[test]
    fn rebuild_changes_the_piece_and_charges_the_quote() {
        let (mut w, clock, e) = paid_world();
        let tracks = w.api.world().net.edge_tracks[e as usize];
        let want = if tracks == 1 { 2 } else { 1 };
        // a level the piece takes alone, else just the track count
        let open = read(&mut w, e).unwrap();
        let level = open["levels"].as_array().unwrap().iter().filter_map(Value::as_i64).find(|&l| l == 1).map_or(KEEP_LEVEL, |l| l as i32);
        let q = quote(&mut w, e, level, 0, want);
        assert!(q["changed"].as_u64().unwrap() >= 1, "{q}");
        assert_eq!(q["refused"], 0, "{q}");
        let price = q["price"].as_f64().unwrap();
        let before = w.api.cash();
        // a quote applies nothing
        assert_eq!(w.api.world().net.edge_tracks[e as usize], tracks);
        let r = rebuild(&mut w, e, level, 0, want, clock).unwrap();
        assert_eq!(r["changed"], true);
        let after = read(&mut w, e).unwrap();
        assert_eq!(after["tracks"], want);
        if level != KEEP_LEVEL {
            assert_eq!(after["levelMax"], level);
        }
        assert!((before - w.api.cash() - price).abs() < 1e-6 + price * 1e-9, "paid {} for a quote of {price}", before - w.api.cash());
        assert_eq!(w.header["cash"], w.api.cash());
        let ledger = w.header["ledger"].as_array().unwrap().last().unwrap();
        assert!((ledger["build"].as_f64().unwrap() - r["charge"].as_f64().unwrap()).abs() < 1e-9 || price == 0.);
        // the rebuilt piece survives a save
        let reopened = World::open(&w.save(clock).unwrap()).unwrap();
        assert_eq!(reopened.api.cash(), w.api.cash());
    }

    #[test]
    fn rebuild_is_refused_when_cash_is_short_and_nothing_changes() {
        let (mut w, clock, e) = paid_world();
        let price = quote(&mut w, e, 1, 0, 0)["price"].as_f64().unwrap();
        assert!(price > 0.);
        w.api.set_cash(price / 2.);
        w.header["cash"] = (price / 2.).into();
        let ledger = w.header["ledger"].clone();
        assert_eq!(rebuild(&mut w, e, 1, 0, 0, clock).unwrap_err(), "Saldo tidak cukup untuk membangun ulang rel ini.");
        assert_eq!(read(&mut w, e).unwrap()["levelMax"], 0);
        assert_eq!(w.api.cash(), price / 2.);
        assert_eq!(w.header["ledger"], ledger);
    }

    #[test]
    fn limit_is_free_and_clamped() {
        let (mut w, clock, e) = paid_world();
        let cash = w.api.cash();
        assert_eq!(limit(&mut w, e, 10, clock).unwrap()["limit"], 40);
        assert_eq!(limit(&mut w, e, 500, clock).unwrap()["limit"], 160);
        assert_eq!(limit(&mut w, e, 160, clock).unwrap()["changed"], false);
        assert_eq!(read(&mut w, e).unwrap()["limit"], 160);
        assert_eq!(limit(&mut w, e, 0, clock).unwrap()["limit"], 0);
        assert_eq!(w.api.cash(), cash);
    }
}
