// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use jni::{objects::JIntArray, sys::jboolean};
use std::collections::HashMap;

impl World {
    /// Select only built platforms. A serialized transaction packs valid node IDs in ascending order.
    fn line_on_existing_track(
        &mut self,
        selected: &[i32],
        name: &str,
        clock: f64,
        vehicle: i32,
        commit: bool,
    ) -> Result<Value, String> {
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 48 {
            return Err("Isi nama layanan dengan 1–48 karakter.".into());
        }
        if selected.len() < 2 || selected.windows(2).any(|pair| pair[0] == pair[1]) {
            return Err("Pilih setidaknya dua stasiun berbeda sesuai urutan perjalanan.".into());
        }
        if !clock.is_finite() || !matches!(vehicle, -1 | 0 | 1 | 4) {
            return Err("Pilihan layanan tidak valid.".into());
        }
        let net = &self.api.world().net;
        let packed: HashMap<_, _> = (0..net.node_count() as u32)
            .filter(|&n| net.node_ok(n))
            .enumerate()
            .filter(|(_, n)| net.is_station(*n) && net.node_built[*n as usize])
            .map(|(idx, n)| (n as i32, idx as u32))
            .collect();
        let stops = selected
            .iter()
            .map(|id| packed.get(id).copied())
            .collect::<Option<Vec<_>>>()
            .ok_or("Pilih peron dari stasiun yang sudah dibangun.")?;
        let clock = self.native_clock(clock);
        let mut next = self
            .edit_candidate(clock)
            .ok_or("Jaringan belum siap disalin.")?;
        let api = &mut next.api;
        api.set_now(clock);
        if !api.order_fault(&stops).is_empty() {
            return Err("Rel belum menghubungkan stasiun sesuai urutan yang dipilih.".into());
        }
        let tram = api.node_kind(stops[0]) >= 2;
        if vehicle != -1 && (vehicle == 4) != tram {
            return Err("Jenis armada harus sesuai dengan rel pada peron awal.".into());
        }
        let id = api.new_line_id();
        let colors = [0x007866, 0xcf5823, 0x6944ad, 0x0876b6, 0xbd3a62];
        let colour = colors[id as usize % colors.len()];
        // Same initial fleet, dwell, turnaround and default inheritance as the web worker's addLine.
        let (dwell, turn, cars) = if tram {
            let defaults = TrackApi::kind_defaults(3);
            (defaults[0] as f32, defaults[1] as f32, 2)
        } else {
            let cars = next.header["newCars"]
                .as_u64()
                .filter(|n| *n <= 12)
                .unwrap_or(5);
            (30., 180., cars as u8)
        };
        let cash_before = api.cash();
        if api.set_line_with_trains(id, &stops, 0, 0, 0, dwell, turn, cars, colour, name.into())
            != 0
        {
            return Err(format!("Layanan ditolak: {}", api.issue_kinds()));
        }
        if api.line_stops(id) != stops && api.order_stops(id, &stops) != 0 {
            return Err("Urutan stasiun ini tidak dapat dilalui oleh satu layanan.".into());
        }
        let network = &api.world().net;
        if network
            .line_path(id)
            .iter()
            .map(|&p| p >> 1)
            .chain(network.back_only_edges(id))
            .any(|e| !network.edge_built[e as usize])
        {
            return Err(
                "Bangun semua ruas rel untuk perjalanan pergi dan pulang terlebih dahulu.".into(),
            );
        }
        if vehicle == 1 && api.set_line_fast(id, true) != 0 {
            return Err(format!(
                "Armada kereta cepat ditolak: {}",
                api.issue_kinds()
            ));
        }
        let kind = if tram {
            "tram"
        } else if vehicle == 1 {
            "fast"
        } else {
            "rail"
        };
        if let Some(values) = next.header["newHeadway"][kind].as_array() {
            if let [high, medium, low] = values.as_slice() {
                let seconds = [high, medium, low].map(|v| {
                    v.as_f64()
                        .filter(|s| s.is_finite() && *s >= 0.)
                        .map(|s| s as u32)
                });
                if let [Some(h), Some(m), Some(l)] = seconds {
                    api.set_auto_headway(id, h, m, l);
                }
            }
        }
        if let Some(share) = next.header["newWeekendShare"]
            .as_u64()
            .filter(|n| (10..=200).contains(n) && *n != 100)
        {
            api.set_line_weekend_share(id, share as u32);
        }
        let mut result = json!({"id":id, "line":id, "name":name, "stations":stops.len(), "cost":cash_before-api.cash()});
        result["path"] = json!(api.run_polyline(id, 0, 70.));
        if commit {
            next.book_service_edit(self, clock);
            next.roads_dir = self.roads_dir.clone();
            *self = next;
        }
        Ok(result)
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRailLine_create(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    nodes: JIntArray,
    title: JString,
    clock: jdouble,
    vehicle: i32,
    commit: jboolean,
) -> jstring {
    let result = (|| {
        let w = unsafe { (handle as *mut World).as_mut() }.ok_or("Dunia belum siap.")?;
        let count = env
            .get_array_length(&nodes)
            .map_err(|_| "Daftar stasiun tidak valid.")? as usize;
        if count < 2
            || count
                > w.api
                    .world()
                    .net
                    .node_count()
                    .saturating_mul(2)
                    .saturating_add(1)
        {
            return Err("Jumlah pemberhentian tidak valid.".into());
        }
        let mut selected = vec![0; count];
        env.get_int_array_region(&nodes, 0, &mut selected)
            .map_err(|_| "Daftar stasiun tidak dapat dibaca.")?;
        let name: String = env
            .get_string(&title)
            .map_err(|_| "Nama layanan tidak valid.")?
            .into();
        w.line_on_existing_track(&selected, &name, clock, vehicle, commit != 0)
    })()
    .unwrap_or_else(|error: String| json!({"error":error}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disconnected_network_is_refused_and_defaults_survive_a_connected_line() {
        let mut world = World::workshop();
        let now = 111600.;
        let first = world
            .build_path(&[[0., 0.], [6000., 0.]], "Barat", now)
            .unwrap();
        let second = world
            .build_path(&[[0., 2000.], [6000., 2000.]], "Timur", now)
            .unwrap();
        let west = world.api.line_stops(first["line"].as_u64().unwrap() as u32);
        let east = world
            .api
            .line_stops(second["line"].as_u64().unwrap() as u32);
        let before = world.save(now).unwrap();
        assert!(world
            .line_on_existing_track(&[west[0] as i32, east[1] as i32], "Putus", now, 0, true)
            .is_err());
        assert_eq!(world.save(now).unwrap(), before);
        world.header["newCars"] = json!(3);
        world.header["newHeadway"] = json!({"rail":[600,900,1200]});
        world.header["newWeekendShare"] = json!(80);
        let result = world
            .line_on_existing_track(&[west[0] as i32, west[1] as i32], "Tambahan", now, 0, true)
            .unwrap();
        let id = result["id"].as_u64().unwrap() as u32;
        assert_eq!(world.api.line_auto(id), vec![600., 900., 1200.]);
        assert_eq!(world.api.line_weekend(id)[0], 80.);
        assert_eq!(
            world
                .api
                .lines_info()
                .chunks_exact(22)
                .find(|row| row[0] as u32 == id)
                .unwrap()[8],
            3.
        );
    }

    #[test]
    #[ignore = "requires Jabodetabek 2026 via WORLD_ROUTE_FIXTURE"]
    fn distant_stations_can_use_a_headshunt_on_the_way() {
        let bytes = std::fs::read(std::env::var("WORLD_ROUTE_FIXTURE").unwrap()).unwrap();
        let mut world = World::open(&bytes).unwrap();
        let find = |name: &str| {
            world
                .api
                .nodes_info()
                .chunks_exact(10)
                .find(|r| r[7] > 0. && r[8] > 0. && world.api.node_name(r[0] as u32) == name)
                .unwrap()[0] as i32
        };
        let stops = [find("Poris"), find("Tanah Abang")];
        let preview = world
            .line_on_existing_track(&stops, "Uji", 111600., -1, false)
            .unwrap();
        let created = world
            .line_on_existing_track(&stops, "Uji", 111600., -1, true)
            .unwrap();
        assert_eq!(preview["path"], created["path"]);
        assert!(preview["path"].as_array().unwrap().len() > 200);
        let id = created["id"].as_u64().unwrap() as u32;
        let net = &world.api.world().net;
        assert!(!net.path_reversals(net.line_path(id)).is_empty());
    }

    #[test]
    fn preview_then_make_an_unscheduled_line_without_changing_infrastructure() {
        let mut world = World::workshop();
        let now = 111600.;
        let first = world
            .build_path(&[[0., 0.], [6000., 0.]], "Jaringan", now)
            .unwrap();
        let stations: Vec<_> = world
            .api
            .line_stops(first["line"].as_u64().unwrap() as u32)
            .into_iter()
            .map(|n| n as i32)
            .collect();
        let before = world.save(now).unwrap();
        let infrastructure = world.api.edges_info();
        let mut selected = stations.clone();
        selected.reverse();
        let preview = world
            .line_on_existing_track(&selected, "Sore", now, 0, false)
            .unwrap();
        assert_eq!(preview["cost"], 0.);
        let path = preview["path"].as_array().unwrap();
        assert!(
            path.len() > 4,
            "an unscheduled service still needs its track highlight"
        );
        assert!(
            path[0].as_f64().unwrap() > path[path.len() - 2].as_f64().unwrap(),
            "reverse selection follows the track back to the first station"
        );
        assert_eq!(world.save(now).unwrap(), before);
        assert!(world
            .line_on_existing_track(&[stations[0], -1], "Gagal", now, 0, true)
            .is_err());
        assert_eq!(world.save(now).unwrap(), before);
        let result = world
            .line_on_existing_track(&selected, "Sore", now, 0, true)
            .unwrap();
        assert_eq!(result, preview);
        let reopened = World::open(&world.save(now).unwrap()).unwrap();
        let id = result["id"].as_u64().unwrap() as u32;
        assert_eq!(reopened.api.line_name(id), "Sore");
        assert_eq!(
            reopened.api.line_stops(id),
            selected.into_iter().map(|s| s as u32).collect::<Vec<_>>()
        );
        assert!(reopened.api.edges_info() == infrastructure);
        assert!(reopened.api.line_trips(id, now, now + 7200.).is_empty());
    }
}
