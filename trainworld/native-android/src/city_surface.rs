// SPDX-License-Identifier: AGPL-3.0-only
use super::World;
use flate2::read::GzDecoder;
use jni::{
    objects::{JByteArray, JClass, JDoubleArray, JString},
    sys::{jfloatArray, jintArray, jlong},
    JNIEnv,
};
use serde_json::Value;
use std::io::Read;
use trainworld_sim::track::cost::ClassGrid;

const EARTH: f64 = 6_371_008.8;

fn array<'a>(h: &Value, bin: &'a [u8], key: &str, dtype: &str, size: usize) -> Option<&'a [u8]> {
    let a = h["arrays"]
        .as_array()?
        .iter()
        .find(|v| v["name"] == key && v["dtype"] == dtype)?;
    let start = usize::try_from(a["offset"].as_u64()?).ok()?;
    let len = usize::try_from(a["count"].as_u64()?)
        .ok()?
        .checked_mul(size)?;
    bin.get(start..start.checked_add(len)?)
}
// clock.worker.ts ensureStreets: packs predating the street grid use the default road speed.
pub(super) fn install_streets(api: &mut trainworld_sim::track::wasm::TrackApi, h: &Value, cells: &[u8]) -> Option<bool> {
    let f32s = |name| Some(array(h, cells, name, "f32", 4)?.chunks_exact(4)
        .map(|v| f32::from_le_bytes(v.try_into().unwrap())).collect::<Vec<_>>());
    let u32s = |name| Some(array(h, cells, name, "u32", 4)?.chunks_exact(4)
        .map(|v| u32::from_le_bytes(v.try_into().unwrap())).collect::<Vec<_>>());
    let u16s = |name| Some(array(h, cells, name, "u16", 2)?.chunks_exact(2)
        .map(|v| u16::from_le_bytes(v.try_into().unwrap())).collect::<Vec<_>>());
    let g = &h["gravity"];
    Some(api.add_pack_streets(
        g["zone_m"].as_f64()?, g["grid_origin_m"][0].as_f64()?, g["grid_origin_m"][1].as_f64()?,
        g["grid"][0].as_u64()?.try_into().ok()?, g["grid"][1].as_u64()?.try_into().ok()?,
        &f32s("pop")?, &f32s("jobs")?, &u32s("zone")?, &u16s("zone_gx")?, &u16s("zone_gy")?,
        array(h, cells, "zone_size", "u8", 1).unwrap_or_default(), &u32s("zone_cells9")?,
        h["origin"]["lon"].as_f64()?, h["origin"]["lat"].as_f64()?,
    ))
}
pub(super) fn attach(
    world: &mut World,
    header: &str,
    cells: &[u8],
    terrain: &[u8],
) -> Option<Vec<i32>> {
    let h: Value = serde_json::from_str(header).ok()?;
    let mut raw = Vec::new();
    GzDecoder::new(terrain)
        .take((64 << 20) + 1)
        .read_to_end(&mut raw)
        .ok()?;
    if raw.len() > 64 << 20 {
        return None;
    }
    let grid = ClassGrid::decode(&raw)?;
    let water = &h["water"];
    let rows: Vec<u32> = array(&h, cells, "water_row", "u32", 4)?
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let runs: Vec<u16> = array(&h, cells, "water_x", "u16", 2)?
        .chunks_exact(2)
        .map(|b| u16::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let w = u32::try_from(water["size"][0].as_u64()?).ok()?;
    let ht = u32::try_from(water["size"][1].as_u64()?).ok()?;
    if w == 0
        || ht == 0
        || rows.len() != ht as usize + 1
        || rows.first() != Some(&0)
        || rows.last().copied()? as usize != runs.len()
        || rows
            .windows(2)
            .any(|a| a[0] > a[1] || (a[1] - a[0]) % 2 != 0)
    {
        return None;
    }
    let cell = water["cell_m"].as_f64()?;
    let (x0, y0) = (
        water["origin_m"][0].as_f64()?,
        water["origin_m"][1].as_f64()?,
    );
    let (lon, lat) = (h["origin"]["lon"].as_f64()?, h["origin"]["lat"].as_f64()?);
    if cell <= 0.0 || !world.api.add_zone_classes(&raw) {
        return None;
    }
    world
        .api
        .add_pack_water(cell, x0, y0, w, ht, &rows, &runs, lon, lat);
    let kx = EARTH * lat.to_radians().cos();
    let southwest = world.api.from_lonlat(
        lon + (x0 / kx).to_degrees(),
        lat + (y0 / EARTH).to_degrees(),
    );
    let northeast = world.api.from_lonlat(
        lon + ((x0 + f64::from(w) * cell) / kx).to_degrees(),
        lat + ((y0 + f64::from(ht) * cell) / EARTH).to_degrees(),
    );
    let (west, south, east, north) = (southwest[0], southwest[1], northeast[0], northeast[1]);
    let dx = east - west;
    let dy = north - south;
    if dx <= 0. || dy <= 0. {
        return None;
    }
    let step = dx.max(dy) / 1024.;
    let (cols, lines) = ((dx / step).ceil() as usize, (dy / step).ceil() as usize);
    let mut pixels = Vec::with_capacity(6 + cols * lines);
    pixels.extend([cols as i32, lines as i32]);
    pixels.extend([west, south, east, north].map(|n| (n as f32).to_bits() as i32));
    let colors = [0xffedf4e9u32, 0xffdce8cd, 0xffbbd4ae, 0xff98b99a];
    for y in 0..lines {
        for x in 0..cols {
            let pos = world.api.to_lonlat(
                west + (x as f64 + 0.5) * dx / cols as f64,
                north - (y as f64 + 0.5) * dy / lines as f64,
            );
            let col = (((pos[0] - lon).to_radians() * kx - x0) / cell).floor() as i64;
            let row = (((pos[1] - lat).to_radians() * EARTH - y0) / cell).floor() as i64;
            let wet = (0..i64::from(ht)).contains(&row) && (0..i64::from(w)).contains(&col) && {
                let r = row as usize;
                runs[rows[r] as usize..rows[r + 1] as usize]
                    .partition_point(|&v| i64::from(v) <= col)
                    % 2
                    == 1
            };
            let color = if wet {
                0xffaccfdc
            } else {
                colors[grid.at(pos[0], pos[1]).unwrap_or(0) as usize]
            };
            pixels.push(color as i32);
        }
    }
    world.operating = None;
    Some(pixels)
}

fn empty_city(id: &str, header: &str) -> Option<World> {
    let h: Value = serde_json::from_str(header).ok()?;
    let lon = h["origin"]["lon"].as_f64()?;
    let lat = h["origin"]["lat"].as_f64()?;
    Some(World::sandbox(id, lon, lat))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_project(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    coords: JDoubleArray,
) -> jfloatArray {
    let project = || -> Option<jfloatArray> {
        let world = unsafe { (handle as *const World).as_ref() }?;
        let n = env.get_array_length(&coords).ok()?;
        if n % 2 != 0 || n > 100_000 {
            return None;
        }
        let mut values = vec![0.; n as usize];
        env.get_double_array_region(&coords, 0, &mut values).ok()?;
        let mut xy = Vec::with_capacity(n as usize);
        for ll in values.chunks_exact(2) {
            xy.extend(
                world
                    .api
                    .from_lonlat(ll[0], ll[1])
                    .into_iter()
                    .map(|v| v as f32),
            );
        }
        let result = env.new_float_array(n).ok()?;
        env.set_float_array_region(&result, 0, &xy).ok()?;
        Some(result.into_raw())
    };
    project().unwrap_or(std::ptr::null_mut())
}

// An isolated terrain loader in exactly the editor's projection. Zone rasters are immutable.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_fork(
    _: JNIEnv, _: JClass, source: jlong,
) -> jlong {
    let src = unsafe { &*(source as *const World) };
    let origin = src.api.frame_origin();
    let mut candidate = World::sandbox("", origin[0], origin[1]);
    candidate.api.set_empty_frame(src.api.frame_kind());
    candidate.api.copy_city_surface_from(&src.api);
    Box::into_raw(Box::new(candidate)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_worldHome(
    _: JNIEnv, _: JClass, handle: jlong, setting: i32,
) -> jni::sys::jboolean {
    let world = unsafe { &mut *(handle as *mut World) };
    match setting {
        1 => world.header["home"] = Value::from("world"),
        0 => { world.header.as_object_mut().unwrap().remove("home"); },
        _ => (),
    }
    (world.header["home"].as_str() == Some("world")) as _
}

/// A save opened with the terrain and water of world `surface`'s opened cities, as `Engine.open` then
/// `copy` give it but derived once instead of twice: each derive of Seluruh Indonesia's 199 lines
/// took seconds on a phone, and the checkpoint behind every panel waited for both (20 s).
#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_open(
    env: JNIEnv, _: JClass, bytes: jni::objects::JByteArray, surface: jlong,
) -> jlong {
    let Ok(input) = env.convert_byte_array(bytes) else { return 0 };
    let mut api = trainworld_sim::track::wasm::TrackApi::new(-74., 40.7, true);
    if surface != 0 {
        api.copy_city_surface_from(&unsafe { &*(surface as *const World) }.api);
    }
    World::open_with(&input, api).map_or(0, |w| Box::into_raw(Box::new(w)) as jlong)
}

/// What the map draws of world `handle` (`Engine.geometry`) as a short key, its clock and cash left
/// out, with them: `{"key","clock","cash"}`. A checkpoint whose world draws the same as the last one
/// skips the 19 MB geometry of Seluruh Indonesia and the map's reload (seconds, on the UI thread).
pub(crate) fn geometry_key(world: &World) -> Value {
    use std::hash::{Hash, Hasher};
    let mut g = world.geometry();
    let clock = g["clock"].take();
    let cash = g["cash"].take();
    let mut h = std::collections::hash_map::DefaultHasher::new();
    g.to_string().hash(&mut h);
    serde_json::json!({"key": format!("{:016x}", h.finish()), "clock": clock, "cash": cash})
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_geometryKey(
    env: JNIEnv, _: JClass, handle: jlong,
) -> jni::sys::jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let value = geometry_key(unsafe { &*(handle as *const World) });
    env.new_string(value.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

// Checkpoints serialize the network; the downloaded terrain and water masks stay local.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_copy(
    _: JNIEnv, _: JClass, source: jlong, destination: jlong,
) {
    if source != destination {
        let src = unsafe { &*(source as *const World) };
        let dst = unsafe { &mut *(destination as *mut World) };
        dst.api.copy_city_surface_from(&src.api);
    }
}

fn visit(world: &mut World, home: &str, zone: &str) -> bool {
    let mut changed = false;
    for key in ["opened", "cities"] {
        if !world.header[key].is_array() {
            world.header[key] = serde_json::json!([]);
            changed = true;
        }
        let ids = world.header[key].as_array_mut().unwrap();
        for id in [home, zone] {
            if !ids.iter().any(|entry| entry.as_str() == Some(id)) {
                ids.push(Value::from(id));
                changed = true;
            }
        }
    }
    changed
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_visit(
    mut env: JNIEnv, _: JClass, handle: jlong, home: JString, zone: JString,
) -> jni::sys::jboolean {
    let home: String = match env.get_string(&home) { Ok(v) => v.into(), Err(_) => return 0 };
    let zone: String = match env.get_string(&zone) { Ok(v) => v.into(), Err(_) => return 0 };
    visit(unsafe { &mut *(handle as *mut World) }, &home, &zone) as _
}

// Remember where the player has explored independently of the zones needed for demand.
fn visit_viewport(world: &mut World, home: &str, zone: &str) -> bool {
    let new = !world.header["opened"].as_array().is_some_and(|v| v.iter().any(|id| id == zone));
    let changed = visit(world, home, zone);
    if new && home != zone {
        if !world.header["androidViewed"].is_array() {
            world.header["androidViewed"] = serde_json::json!([]);
        }
        world.header["androidViewed"].as_array_mut().unwrap().push(Value::from(zone));
    }
    changed
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_visitViewport(
    mut env: JNIEnv, _: JClass, handle: jlong, home: JString, zone: JString,
) -> jni::sys::jboolean {
    let Ok(home) = env.get_string(&home).map(String::from) else {return 0};
    let Ok(zone) = env.get_string(&zone).map(String::from) else {return 0};
    visit_viewport(unsafe { &mut *(handle as *mut World) }, &home, &zone) as _
}

/// The renderer uses the same CRS as the loaded TWG1, including legacy LCC city saves.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_lonLat(
    env:JNIEnv,_:JClass,handle:jlong,x:f32,y:f32
) -> jni::sys::jdoubleArray {
    let Some(world) = (unsafe { (handle as *const World).as_ref() }) else { return std::ptr::null_mut(); };
    let ll=world.api.to_lonlat(x.into(),y.into());
    let Ok(array)=env.new_double_array(2) else {return std::ptr::null_mut();};
    if env.set_double_array_region(&array,0,&ll).is_err(){return std::ptr::null_mut();}
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_empty(
    mut env: JNIEnv,
    _: JClass,
    id: JString,
    header: JString,
) -> jlong {
    let mut get = || -> Option<World> {
        let id: String = env.get_string(&id).ok()?.into();
        let header: String = env.get_string(&header).ok()?.into();
        empty_city(&id, &header)
    };
    get().map_or(0, |world| Box::into_raw(Box::new(world)) as jlong)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_CitySurface_attach(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    header: JString,
    cells: JByteArray,
    terrain: JByteArray,
) -> jintArray {
    let result = (|| {
        let w = unsafe { (handle as *mut World).as_mut() }?;
        let h: String = env.get_string(&header).ok()?.into();
        let pixels = attach(
            w,
            &h,
            &env.convert_byte_array(cells).ok()?,
            &env.convert_byte_array(terrain).ok()?,
        )?;
        let out = env.new_int_array(pixels.len() as i32).ok()?;
        env.set_int_array_region(&out, 0, &pixels).ok()?;
        Some(out.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, path::Path};
    #[test]
    fn viewing_an_empty_city_keeps_the_clock_manifest() {
        let mut world = World::sandbox("jak", 106.82, -6.2);
        let dir = Path::new("/no-cached-boundaries");
        let before = crate::demand_zones::plan(&world.save(111600.).unwrap(), dir).unwrap();
        assert!(visit_viewport(&mut world, "jak", "bad"));
        assert!(!visit_viewport(&mut world, "jak", "bad"));
        let bytes = world.save(111600.).unwrap();
        let after = crate::demand_zones::plan(&bytes, dir).unwrap();
        assert_eq!(after["local"], before["local"]);
        assert_eq!(after["markets"], before["markets"]);
        assert_eq!(after["viewed"], serde_json::json!(["bad"]));
        assert_eq!(World::open(&bytes).unwrap().header["opened"], serde_json::json!(["jak","bad"]));
    }

    /// `open` with a surface world reads the same world as `World::open` (a sandbox has no water
    /// rasters; with them the masks are set before the one derive, as `TrackApi::load` does).
    #[test]
    fn opening_onto_a_surface_reads_the_same_world() {
        let mut world = World::sandbox("jak", 106.82, -6.2);
        let time = world.header["clock"].as_f64().unwrap();
        let p = world.api.from_lonlat(106.82, -6.2);
        world.build_service([p[0], p[1]], [p[0] + 3000., p[1]], "Uji", time).unwrap();
        let archive = world.save(time).unwrap();
        let plain = World::open(&archive).unwrap();
        let mut api = trainworld_sim::track::wasm::TrackApi::new(-74., 40.7, true);
        api.copy_city_surface_from(&world.api);
        let onto = World::open_with(&archive, api).unwrap();
        assert_eq!(plain.save(time), onto.save(time));
        assert_eq!(plain.geometry(), onto.geometry());
        // the same drawing whatever the clock and cash; another line changes it
        let key = geometry_key(&plain);
        let mut later = World::open(&world.save(time + 3600.).unwrap()).unwrap();
        later.api.set_cash(1.);
        assert_eq!(geometry_key(&later)["key"], key["key"]);
        assert_eq!(geometry_key(&later)["clock"].as_f64(), Some(time + 3600.));
        world.build_service([p[0], p[1] + 3000.], [p[0] + 3000., p[1] + 3000.], "Dua", time).unwrap();
        assert_ne!(geometry_key(&world)["key"], key["key"]);
    }

    #[test]
    fn two_cities_share_the_network_after_save_and_reopen() {
        let mut world = World::sandbox("jak", 106.82, -6.2);
        world.header.as_object_mut().unwrap().remove("opened");
        assert!(visit(&mut world, "jak", "bad"));
        assert!(!visit(&mut world, "jak", "bad"));
        let time = world.header["clock"].as_f64().unwrap();
        for (city, lon, lat) in [("Jakarta", 106.82, -6.2), ("Bandung", 107.62, -6.92)] {
            let point = world.api.from_lonlat(lon, lat);
            let a = [point[0], point[1]];
            world.build_service(a, [a[0] + 2000., a[1]], city, time).unwrap();
        }
        let endpoints = [(106.83, -6.22), (107.64, -6.94)].map(|(lon, lat)| {
            let xy = world.api.from_lonlat(lon, lat);
            [xy[0], xy[1]]
        });
        world.build_service(endpoints[0], endpoints[1], "Jakarta–Bandung", time).unwrap();
        let archive = world.save(time).unwrap();
        let restored = World::open(&archive).unwrap();
        assert_eq!(restored.header["opened"], serde_json::json!(["jak", "bad"]));
        assert_eq!(restored.api.line_name(0), "Jakarta");
        assert_eq!(restored.api.line_name(1), "Bandung");
        assert_eq!(restored.api.line_name(2), "Jakarta–Bandung");
        assert_eq!(restored.api.line_stops(2).len(), 2);
        assert!(restored.api.line_stop_s(2, 0)[1] > 100_000.);
        let demand = crate::demand_zones::plan(&archive, Path::new("/no-cached-boundaries")).unwrap();
        for k in ["local", "markets"] {
            for city in ["jak", "bad"] {
                assert!(demand[k].as_array().unwrap().iter().any(|id| id == city));
            }
        }
    }

    #[test]
    #[ignore = "requires the Jakarta pack and pinned OpenFreeMap road tiles"]
    fn jakarta_bus_follows_original_road_graph_moves_and_reopens() {
        let root = std::path::PathBuf::from(std::env::var("NATIVE_CITY_RELEASE").unwrap());
        let header = fs::read_to_string(root.join("jak.json")).unwrap();
        let mut w = empty_city("jak", &header).unwrap();
        w.roads_dir = Some(std::env::var("WORLD_ROADS").unwrap().into());
        let points = [[106.8171, -6.1988], [106.8227, -6.18825]].map(|p| {
            let a = w.api.from_lonlat(p[0], p[1]);
            [a[0], a[1]]
        });
        let r = w
            .build_alignment(&points, "Bus Sudirman", 111600., 5, &[])
            .unwrap();
        let dep = r["departure"].as_f64().unwrap();
        assert!(w.api.is_bus(0));
        assert!(w.geometry()["stations"].as_array().unwrap().len() >= 2);
        let route = w.api.bus_route(0);
        assert!(route.len() > 20);
        assert_ne!(w.positions(dep + 5.), w.positions(dep + 25.));
        let saved = w.save(dep + 25.).unwrap();
        if let Some(root) = std::env::var_os("WORLD_MOBILITY_EXPORT") {
            fs::write(Path::new(&root).join("jakarta-bus.save"), &saved).unwrap();
        }
        let mut reopened = World::open(&saved).unwrap();
        assert_eq!(reopened.api.bus_route(0), route);
        assert_eq!(reopened.positions(dep + 25.), w.positions(dep + 25.));
        eprintln!("Bus route: {} points", (route.len() - 1) / 3);
    }
    #[test]
    #[ignore = "requires the pinned Jakarta data pack"]
    fn jakarta_ferry_stays_on_water_moves_and_reopens() {
        let root = std::path::PathBuf::from(std::env::var("NATIVE_CITY_RELEASE").unwrap());
        let header = fs::read_to_string(root.join("jak.json")).unwrap();
        let mut w = empty_city("jak", &header).unwrap();
        attach(
            &mut w,
            &header,
            &fs::read(root.join("jak.bin")).unwrap(),
            &fs::read(root.join("terrain/jak.terrain")).unwrap(),
        )
        .unwrap();
        let to_xy = |lon: f64, lat: f64| {
            let p = w.api.from_lonlat(lon, lat);
            [p[0], p[1]]
        };
        let ports = [to_xy(106.81375, -6.1211), to_xy(106.8268, -6.1173)];
        let inland = [to_xy(106.823, -6.179), to_xy(106.836, -6.182)];
        assert!(w
            .build_alignment(&inland, "Daratan", 111600., 4, &[])
            .is_err());
        assert!(w.api.ports().is_empty());
        let r = w
            .build_alignment(&ports, "Feri Ancol", 111600., 4, &[])
            .unwrap();
        let dep = r["departure"].as_f64().unwrap();
        assert!(w.api.is_ferry(0));
        assert_eq!(w.api.ports().len(), 2);
        let route = w.api.ferry_route(0);
        assert!(route.len() > 8);
        for pair in route[1..].chunks_exact(4).collect::<Vec<_>>().windows(2) {
            let (a, b) = (pair[0], pair[1]);
            let n = ((a[0] - b[0]).hypot(a[1] - b[1]) / 5.).ceil() as usize;
            for i in 0..=n {
                let t = i as f64 / (n.max(1) as f64);
                assert_eq!(
                    w.api
                        .native_pack_water_at(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])),
                    Some(true)
                );
            }
        }
        assert_ne!(w.positions(dep + 5.), w.positions(dep + 25.));
        let saved = w.save(dep + 25.).unwrap();
        if let Some(root) = std::env::var_os("WORLD_MOBILITY_EXPORT") {
            fs::write(Path::new(&root).join("jakarta-ferry.save"), &saved).unwrap();
        }
        let mut reopened = World::open(&saved).unwrap();
        assert_eq!(reopened.api.ferry_route(0), route);
        assert_eq!(reopened.positions(dep + 25.), w.positions(dep + 25.));
    }

    #[test]
    fn jakarta_starts_empty_places_rail_at_real_coordinates_and_reopens() {
        let Ok(dir) = std::env::var("NATIVE_CITY_RELEASE") else {
            return;
        };
        let root = Path::new(&dir);
        let header = fs::read_to_string(root.join("jak.json")).unwrap();
        let mut world = empty_city("jak", &header).unwrap();
        let pixels = attach(
            &mut world,
            &header,
            &fs::read(root.join("jak.bin")).unwrap(),
            &fs::read(root.join("terrain/jak.terrain")).unwrap(),
        )
        .unwrap();
        assert!(pixels[6..].contains(&(0xffaccfdcu32 as i32)));
        assert!(world.geometry()["stations"].as_array().unwrap().is_empty());
        let a = world.api.from_lonlat(106.8236, -6.1861);
        let b = world.api.from_lonlat(106.8311, -6.1964);
        let result = world
            .build_service([a[0], a[1]], [b[0], b[1]], "Uji Jakarta", 111600.)
            .unwrap();
        let t = result["departure"].as_f64().unwrap() + 20.;
        let mut restored = World::open(&world.save(t).unwrap()).unwrap();
        assert_eq!(restored.header["city"], "nyc");
        assert_eq!(restored.header["home"], "jak");
        assert_eq!(restored.positions(t), world.positions(t));
        assert!(!restored.positions(t).is_empty());
        let geo = restored.geometry();
        let stations = geo["stations"].as_array().unwrap();
        assert_eq!(stations.len(), 2);
        for station in stations {
            let ll = restored
                .api
                .to_lonlat(station[0].as_f64().unwrap(), station[1].as_f64().unwrap());
            assert!((ll[0] - 106.83).abs() < 0.02 && (ll[1] + 6.19).abs() < 0.02);
        }
        println!("Jakarta verified at 106.83 E / 6.19 S: starts empty, mapped water, 1 test service, 2 stations, save and moving trains reopened");
    }

    #[test]
    fn city_release_worlds_match_geography_moves_and_offline_reopen() {
        let Ok(dir) = std::env::var("NATIVE_CITY_RELEASE") else {
            return;
        };
        let root = Path::new(&dir);
        for (city, lon, lat) in [("tyo", 139.76, 35.68), ("lon", -0.12, 51.50)] {
            let network = fs::read(root.join(format!("real/{city}.save"))).unwrap();
            let mut w = World::open(&network).unwrap();
            let header = fs::read_to_string(root.join(format!("{city}.json"))).unwrap();
            let cells = fs::read(root.join(format!("{city}.bin"))).unwrap();
            let terrain = fs::read(root.join(format!("terrain/{city}.terrain"))).unwrap();
            let pixels = attach(&mut w, &header, &cells, &terrain).unwrap();
            assert!(
                pixels.len() > 10000
                    && pixels[6..].contains(&(0xffaccfdcu32 as i32))
                    && pixels[6..].contains(&(0xffedf4e9u32 as i32))
            );
            let geo = w.geometry();
            let stations = geo["stations"].as_array().unwrap();
            let count = stations
                .iter()
                .filter(|p| {
                    let ll = w
                        .api
                        .to_lonlat(p[0].as_f64().unwrap(), p[1].as_f64().unwrap());
                    (ll[0] - lon).abs() < 0.5 && (ll[1] - lat).abs() < 0.5
                })
                .count();
            println!(
                "{city}: {} services, {} stations, {count} near city, {} active, {}x{} mapped",
                geo["routes"].as_array().unwrap().len(),
                stations.len(),
                w.positions(111600.).len() / 3,
                pixels[0],
                pixels[1]
            );
            assert!(count > 30);
            assert_ne!(w.positions(111600.), w.positions(111610.));
            let save = w.save(111610.).unwrap();
            let mut restore = World::open(&save).unwrap();
            assert_eq!(restore.positions(111610.), w.positions(111610.));
        }
    }
}
