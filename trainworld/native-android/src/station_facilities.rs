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

// TrackApi changes the network and cash. Its host books each payment in TWG1 hourly/daily records.
fn book(header: &mut Value, key: &str, index: &str, at: i64, charge: f64, trains: f64) {
    if !header[key].is_array() {
        header[key] = json!([])
    }
    let rows = header[key].as_array_mut().unwrap();
    let i = rows.partition_point(|row| row[index].as_i64().unwrap_or(i64::MIN) < at);
    if rows.get(i).and_then(|row| row[index].as_i64()) != Some(at) {
        let mut row = json!({"fares":0,"running":0,"trains":0,"build":0,"rides":0});
        row[index] = at.into();
        rows.insert(i, row);
    }
    for (field, amount) in [("build", charge - trains), ("trains", trains)] {
        rows[i][field] = (rows[i][field].as_f64().unwrap_or(0.) + amount).into();
    }
    if key == "hours" && rows.len() > 48 {
        rows.drain(..rows.len() - 48);
    }
}

fn edit(w: &mut World, node: u32, bit: u8, on: bool, clock: f64) -> Result<Value, &'static str> {
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
    if charge > 0. {
        let day = ((clock + w.header["dayShift"].as_f64().unwrap_or(0.)) / 86400.).floor() as i64;
        let hour = (clock / 3600.).floor() as i64;
        for (key, index, at) in [("ledger", "day", day), ("hours", "hour", hour)] {
            book(&mut w.header, key, index, at, charge, trains);
            book(&mut w.header["regions"][""], key, index, at, charge, trains);
        }
    }
    w.hour = i64::MIN;
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
        w.build_service([0., 0.], [1600., 0.], "Facilities test", clock)
            .unwrap();
        let n = (0..w.api.world().net.node_count() as u32)
            .find(|&n| w.api.world().net.is_station(n))
            .unwrap();
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
            &w.header["ledger"][0],
            &w.header["hours"][0],
            &w.header["regions"][""]["ledger"][0],
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
        assert_eq!(restored.header["ledger"][0]["build"], 5.);
    }
}
