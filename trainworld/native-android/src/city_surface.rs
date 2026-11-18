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
    Some(pixels)
}

fn empty_city(id: &str, header: &str) -> Option<World> {
    let h: Value = serde_json::from_str(header).ok()?;
    let lon = h["origin"]["lon"].as_f64()?;
    let lat = h["origin"]["lat"].as_f64()?;
    let mut world = World::workshop();
    world.api = trainworld_sim::track::wasm::TrackApi::new(lon, lat, true);
    world.api.set_unlimited(true);
    world.header["city"] = serde_json::json!(id);
    Some(world)
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
        assert_eq!(restored.header["city"], "jak");
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
