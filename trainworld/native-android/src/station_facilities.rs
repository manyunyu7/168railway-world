// SPDX-License-Identifier: AGPL-3.0-only
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use trainworld_sim::track::upgrades::{PARK_RIDE, PASSING, TERMINAL, WAITING_ROOM};

pub(super) fn offered(w: &World, node: u32) -> Vec<Value> {
    let net = &w.api.world().net;
    if !net.is_station(node) || !net.node_built[node as usize] {
        return vec![];
    }
    let i = node as usize;
    let housed = net
        .buildings
        .iter()
        .enumerate()
        .any(|(id, b)| b.is_some() && w.api.building_members(id as u32).contains(&node));
    let port = net.node_berth[i].is_some();
    let tram = net.node_price_kind(node).spec().family != 0;
    [TERMINAL, PARK_RIDE, WAITING_ROOM, PASSING]
        .into_iter()
        .filter_map(|bit| {
            let on = net.node_upgrades[i] & bit != 0;
            let supported = match bit {
                TERMINAL => !port && net.node_one[i] == 0,
                PARK_RIDE => !port && !tram,
                WAITING_ROOM => !housed,
                PASSING => !port && net.node_one[i] == 0 && net.node_tracks(node) == 2,
                _ => false,
            };
            (supported || on)
                .then(|| json!({"bit":bit,"on":on,"price":w.api.station_upgrade_cost(node,bit)}))
        })
        .collect()
}

fn edit(w: &mut World, node: u32, bit: u8, on: bool, clock: f64) -> Result<Value, &'static str> {
    let clock = w.native_clock(clock);
    if !clock.is_finite() {
        return Err("Jam permainan tidak valid.");
    }
    let option = offered(w, node)
        .into_iter()
        .find(|v| v["bit"] == bit)
        .ok_or("Fasilitas ini tidak tersedia di peron tersebut.")?;
    if option["on"] == on {
        return Ok(json!({"charge":0,"changed":false}));
    }
    super::finance::tick(w, clock);
    w.api.set_now(clock);
    if w.api.set_station_upgrade(&[node], bit, on) != 0 {
        return Err("Perubahan ditolak. Periksa saldo dan kondisi jalur.");
    }
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
    Ok(json!({"charge":charge,"changed":true,"cash":w.api.cash()}))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationInspector_upgrade(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: jint,
    bit: jint,
    on: jint,
    clock: jdouble,
) -> jstring {
    let result = unsafe { (handle as *mut World).as_mut() }.and_then(|w| {
        u8::try_from(bit)
            .ok()
            .map(|bit| edit(w, node as u32, bit, on != 0, clock))
    });
    let payload = result
        .unwrap_or(Err("Dunia belum siap."))
        .unwrap_or_else(|e| json!({"error":e}));
    env.new_string(payload.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paid_upgrade_survives_twg1_and_removal_never_refunds() {
        let mut w = World::workshop();
        let clock = 111600.;
        w.build_service([0., 0.], [1600., 0.], "Facilities test", clock - 86400.)
            .unwrap();
        let n = (0..w.api.world().net.node_count() as u32)
            .find(|&n| w.api.world().net.is_station(n))
            .unwrap();
        super::super::finance::tick(&mut w, clock);
        w.api.set_cash(4.);
        w.api.set_unlimited(false);
        w.header["cash"] = 4.into();
        w.header["mode"] = "normal".into();
        assert!(edit(&mut w, n, WAITING_ROOM, true, clock).is_err());
        assert_eq!(w.api.cash(), 4.);
        w.api.set_cash(100.);
        w.header["cash"] = 100.into();
        let result = edit(&mut w, n, WAITING_ROOM, true, clock).unwrap();
        assert_eq!(result["charge"], 5.);
        assert_eq!(w.header["cash"], 95.);
        for entry in [
            w.header["ledger"].as_array().unwrap().last().unwrap(),
            w.header["hours"].as_array().unwrap().last().unwrap(),
            w.header["regions"][""]["ledger"]
                .as_array()
                .unwrap()
                .last()
                .unwrap(),
        ] {
            assert_eq!(entry["build"], 5.);
        }
        let saved = w.save(clock).unwrap();
        if let Ok(path) = std::env::var("STATION_FACILITY_TEST_SAVE") {
            std::fs::write(path, saved.as_slice()).unwrap();
        }
        let mut restored = World::open(&saved).unwrap();
        assert_eq!(restored.api.cash(), 95.);
        assert_eq!(
            restored.api.world().net.node_upgrades[n as usize] & WAITING_ROOM,
            WAITING_ROOM
        );
        assert_eq!(
            edit(&mut restored, n, WAITING_ROOM, true, clock).unwrap()["changed"],
            false
        );
        assert_eq!(
            edit(&mut restored, n, WAITING_ROOM, false, clock).unwrap()["charge"],
            0.
        );
        assert_eq!(
            restored.api.world().net.node_upgrades[n as usize] & WAITING_ROOM,
            0
        );
        assert_eq!(restored.api.cash(), 95.);
        assert_eq!(
            restored.header["ledger"]
                .as_array()
                .unwrap()
                .last()
                .unwrap()["build"],
            5.
        );
    }
}

/// Coordinates crossing the Android UI boundary are ground metres relative to the
/// selected platform. The world retains its original coordinate frame in TWG1.
pub(crate) fn station_structure(
    w: &mut World,
    node: u32,
    time: f64,
    request: &Value,
) -> Result<Value, String> {
    use trainworld_sim::track::upgrades::PASSAGE_MAX_M;
    let net = &w.api.world().net;
    if !net.is_station(node) || !time.is_finite() {
        return Err("Stasiun belum tersedia.".into());
    }
    let i = node as usize;
    let (x, y) = (net.node_x[i], net.node_y[i]);
    let scale = net.k_at(y);
    let action = request["action"].as_str().unwrap_or("");
    let id = request["id"].as_u64().and_then(|n| u32::try_from(n).ok());
    if action == "list" {
        let mut platforms = Vec::new();
        for other in 0..net.node_count() as u32 {
            if other == node || !net.is_station(other) {
                continue;
            }
            let j = other as usize;
            let meters = net.passage_m(node, other);
            let linked = net.links.contains(&[node.min(other), node.max(other)]);
            if meters > PASSAGE_MAX_M && !linked {
                continue;
            }
            platforms.push(
                json!({"id":other,"name":w.api.node_name(other),"meters":meters,
                "x":(net.node_x[j]-x)/scale,"y":(net.node_y[j]-y)/scale,"built":net.node_built[j],
                "linked":linked,"cost":w.api.passage_cost(node,other)}),
            );
        }
        platforms.sort_by(|a, b| {
            a["meters"]
                .as_f64()
                .unwrap()
                .total_cmp(&b["meters"].as_f64().unwrap())
        });
        let buildings:Vec<_> = net.buildings.iter().enumerate().filter_map(|(j,b)| {
            let b=b.as_ref()?;
            let members=w.api.building_members(j as u32);
            if !members.contains(&node) { return None; }
            let corners:Vec<_>=b.corners.iter().map(|p| [(p[0]-x)/scale,(p[1]-y)/scale]).collect();
            Some(json!({"id":j,"name":b.name,"area":w.api.building_area(j as u32),"points":corners,"members":members.len()}))
        }).collect();
        return Ok(
            json!({"name":w.api.node_name(node),"platforms":platforms,"buildings":buildings}),
        );
    }
    let name = request["name"].as_str().unwrap_or("").to_owned();
    if action == "rename" {
        // web NameEdit: a trimmed new name, empty or unchanged left as it is; set_station_props
        // marks it the player's (T-2683) and keeps the platform
        let name = name.trim().to_owned();
        let old = net.station_data(node).ok_or("Stasiun belum tersedia.")?;
        if name.is_empty() || name == old.name {
            return Ok(json!({"changed":false,"name":old.name}));
        }
        if name.chars().count() > 100 {
            return Err("Nama stasiun paling panjang 100 karakter.".into());
        }
        let clock = w.native_clock(time);
        w.api.set_now(clock);
        if w.api.set_station_props(node, old.platform, name) != 0 {
            return Err("Nama stasiun ditolak.".into());
        }
        w.operating = None;
        return Ok(json!({"changed":true,"name":w.api.node_name(node)}));
    }
    let mut corners = Vec::new();
    if matches!(action, "quote" | "building") {
        let points = request["points"]
            .as_array()
            .filter(|p| (3..=24).contains(&p.len()))
            .ok_or("Bangunan perlu 3–24 sudut.")?;
        for p in points {
            let px = p[0]
                .as_f64()
                .filter(|v| v.abs() < 10000.)
                .ok_or("Koordinat sudut tidak valid.")?;
            let py = p[1]
                .as_f64()
                .filter(|v| v.abs() < 10000.)
                .ok_or("Koordinat sudut tidak valid.")?;
            corners.extend([x + px * scale, y + py * scale]);
        }
        let quote = w.api.building_quote(id.unwrap_or(u32::MAX), &corners);
        let issue = w.api.issue_kinds();
        if !issue.is_empty() {
            return Err(building_issue(&issue));
        }
        if action == "quote" {
            return Ok(
                json!({"area":quote[0],"price":quote[1],"members":quote[2],"built":quote[3]}),
            );
        }
    }
    let clock = w.native_clock(time);
    super::finance::tick(w, clock);
    w.api.set_now(clock);
    let (code, charge) = match action {
        "link" => {
            let other = id.ok_or("Pilih peron tujuan.")?;
            // web linkRefusal: the same station, one complex already, or past a passage's reach
            if let Some(why) = super::passages::refusal(&w.api, node, other) {
                return Err(why);
            }
            let code = w.api.link_stations(node, other);
            (code, w.api.link_charge())
        }
        "unlink" => (w.api.unlink_stations(node, id.ok_or("Pilih lorong.")?), 0.),
        "building" => {
            let code = match id {
                Some(i) => w.api.reshape_building(i, &corners),
                None => w.api.add_building(&corners, name.clone()),
            };
            if code == 0 {
                if let Some(i) = id {
                    w.api.rename_building(i, name);
                }
            }
            (code, w.api.building_charge())
        }
        "remove" => (w.api.remove_building(id.ok_or("Pilih bangunan.")?), 0.),
        _ => return Err("Aksi stasiun tidak dikenal.".into()),
    };
    if code != 0 {
        return Err(building_issue(&w.api.issue_kinds()));
    }
    w.header["cash"] = w.api.cash().into();
    super::finance::book_charge(&mut w.header, clock, charge, 0.);
    w.hour = i64::MIN;
    w.operating = None;
    let other = id.filter(|_| matches!(action, "link" | "unlink")).map(|o| w.api.node_name(o));
    Ok(json!({"changed":true,"cash":w.api.cash(),"charge":charge,"a":w.api.node_name(node),"b":other}))
}
fn building_issue(s: &str) -> String {
    match s.split(':').next().unwrap_or(s) {
        "Money" => "Saldo tidak cukup untuk pembangunan ini.",
        "BuildingCorners" => "Pilih 3–24 sudut yang berbeda.",
        "BuildingEdgesCross" => "Sisi bangunan saling menyilang. Geser salah satu sudut.",
        "BuildingTooWide" => "Jarak antarsudut melebihi batas bangunan.",
        "BuildingTooSmall" => "Luas bangunan minimal 1.000 m².",
        "BuildingNoStation" => "Denah harus menyentuh peron yang sudah dibangun.",
        "BuildingOverlap" => "Denah bertumpang tindih dengan bangunan lain.",
        _ => "Perubahan ditolak. Periksa peron, denah dan saldo.",
    }
    .into()
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationInspector_structure(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: jint,
    time: jdouble,
    command: jni::objects::JString,
) -> jstring {
    let request = env
        .get_string(&command)
        .ok()
        .and_then(|v| serde_json::from_str::<Value>(&String::from(v)).ok());
    let result = unsafe { (handle as *mut World).as_mut() }
        .zip(request)
        .ok_or_else(|| "Dunia belum siap.".to_owned())
        .and_then(|(w, req)| station_structure(w, node as u32, time, &req))
        .unwrap_or_else(|err| json!({"error":err}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod structure_tests {
    use super::*;
    #[test]
    fn passage_and_building_quotes_commits_and_twg1() {
        let clock = 111600.;
        let mut w = World::workshop();
        for (y, name) in [(0., "Utara"), (300., "Selatan")] {
            w.build_service([0., y], [2300., y], name, clock).unwrap();
        }
        let net = &w.api.world().net;
        let node = (0..net.node_count() as u32)
            .find(|&n| net.is_station(n))
            .unwrap();
        let renamed = station_structure(
            &mut w,
            node,
            clock,
            &json!({"action":"rename","name":"  Stasiun Uji  "}),
        )
        .unwrap();
        assert_eq!(renamed["name"], "Stasiun Uji");
        assert!(w.api.world().net.node_named[node as usize]);
        assert_eq!(
            station_structure(&mut w, node, clock, &json!({"action":"rename","name":" "})).unwrap()
                ["changed"],
            false
        );
        let list = station_structure(&mut w, node, clock, &json!({"action":"list"})).unwrap();
        let target = list["platforms"].as_array().unwrap().first().unwrap();
        let id = target["id"].as_u64().unwrap();
        assert_eq!(target["linked"], false);
        let quote = json!({"action":"quote","name":"Aula Utara","points":[[-40,-20],[40,-20],[40,20],[-40,20]]});
        let cash = w.api.cash();
        let q = station_structure(&mut w, node, clock, &quote).unwrap();
        assert!(q["area"].as_f64().unwrap() > 3000.);
        assert_eq!(cash, w.api.cash());
        let mut edit = quote.clone();
        edit["action"] = "building".into();
        let hall = station_structure(&mut w, node, clock, &edit).unwrap();
        assert_eq!(hall["charge"], q["price"]);
        let tunnel =
            station_structure(&mut w, node, clock, &json!({"action":"link","id":id})).unwrap();
        let spent = q["price"].as_f64().unwrap() + tunnel["charge"].as_f64().unwrap();
        assert!((cash - w.api.cash() - spent).abs() < 1e-6);
        let bytes = w.save(clock).unwrap();
        let mut loaded = World::open(&bytes).unwrap();
        let restored =
            station_structure(&mut loaded, node, clock, &json!({"action":"list"})).unwrap();
        assert_eq!(restored["platforms"][0]["linked"], true);
        assert_eq!(restored["buildings"][0]["name"], "Aula Utara");
        let cash = loaded.api.cash();
        station_structure(
            &mut loaded,
            node,
            clock,
            &json!({"action":"unlink","id":id}),
        )
        .unwrap();
        station_structure(
            &mut loaded,
            node,
            clock,
            &json!({"action":"remove","id":restored["buildings"][0]["id"]}),
        )
        .unwrap();
        assert_eq!(cash, loaded.api.cash());
    }
}
