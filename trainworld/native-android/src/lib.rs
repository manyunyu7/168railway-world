mod bus_plan;
mod calendar;
mod city_surface;
mod construction;
mod demand;
mod all_cities;
mod demand_zones;
mod passenger_account;
mod timed_account;
mod train_loads;
mod trip_cells;
mod network_layers;
mod demolition;
mod fare_editor;
mod fares;
mod ferry_plan;
mod finance;
mod local_time;
mod new_game;
mod operating;
mod regional;
mod simulation;
mod station_facilities;
mod station_inspection;
mod station_map;
mod track_inspection;
mod train_scene;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jdouble, jfloatArray, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::io::{Read, Write};
use trainworld_sim::track::wasm::TrackApi;

mod service;
mod native_line;

struct World {
    api: TrackApi,
    header: Value,
    hour: i64,
    trips: Vec<f32>,
    operating: Option<operating::Costs>,
    roads_dir: Option<std::path::PathBuf>,
}

impl World {
    fn open(input: &[u8]) -> Option<Self> {
        let mut raw = Vec::new();
        GzDecoder::new(input)
            .take((32 << 20) + 1)
            .read_to_end(&mut raw)
            .ok()?;
        if raw.len() > 32 << 20 || raw.get(0..4)? != b"TWG1" {
            return None;
        }
        let n = u32::from_le_bytes(raw.get(4..8)?.try_into().ok()?) as usize;
        let track_start = 8usize.checked_add(n)?;
        let mut header: Value = serde_json::from_slice(raw.get(8..track_start)?).ok()?;
        if header["game"] != "anitabuilder" || header["format"] != 1 {
            return None;
        }
        let track = raw.get(track_start..)?;
        if header["level"].as_u64().is_some_and(|v| v > u64::from(TrackApi::save_level()))
            || TrackApi::save_unread(track) > 0
        {
            // Re-encoding these bytes with the current engine would discard newer network data.
            return None;
        }
        let mut api = TrackApi::new(-74., 40.7, true);
        local_time::install(&mut api);
        if !api.load(track) {
            return None;
        }
        let fields = header.as_object_mut()?;
        let old_workshop = fields.remove("nativeWorkshop") == Some(json!(true));
        let archive_city = fields.remove("nativeArchiveCity");
        if old_workshop {
            let city = archive_city.or_else(|| fields.get("city").cloned())?;
            fields.insert("city".into(), json!("nyc"));
            fields.entry("start").or_insert_with(|| city.clone());
            fields.entry("home").or_insert_with(|| city.clone());
            fields.entry("opened").or_insert_with(|| json!([city]));
            fields.entry("cities").or_insert_with(|| json!([city]));
        } else if let Some(city) = archive_city {
            fields.entry("home").or_insert(city);
        }
        {
            api.set_cash(header["cash"].as_f64().unwrap_or(6000.));
            api.set_fleet(header["fleet"].as_u64().unwrap_or(0) as u32);
            api.set_fleet_fast(header["fleetFast"].as_u64().unwrap_or(0) as u32);
            api.set_fleet_bus(header["fleetBus"].as_u64().unwrap_or(0) as u32);
            api.set_fleet_ferry(header["fleetFerry"].as_u64().unwrap_or(0) as u32);
            api.set_fleet_tram(header["fleetTram"].as_u64().unwrap_or(0) as u32);
            api.set_unlimited(header["mode"] == "sandbox");
        }
        Some(Self {
            api,
            header,
            hour: i64::MIN,
            trips: vec![],
            operating: None,
            roads_dir: None,
        })
    }
    fn workshop() -> Self {
        Self::sandbox("nyc", -73.985, 40.758)
    }

    fn sandbox(city: &str, lon: f64, lat: f64) -> Self {
        let mut api = TrackApi::new(lon, lat, true);
        api.set_empty_frame(2);
        local_time::install(&mut api);
        api.set_unlimited(true);
        Self {
            api,
            header: json!({
                "game": "anitabuilder", "format": 1, "level": TrackApi::save_level(),
                "city": "nyc", "home": city, "start": city, "cities": [city], "opened": [city],
                "clock": 111600, "cash": 6000.0, "fleet": 0, "mode": "sandbox",
                "fares": {"base": 1.5, "perKm": 0.1}, "ledger": []
            }),
            hour: i64::MIN,
            trips: vec![],
            operating: None,
            roads_dir: None,
        }
    }

    // Work on a copy. A refused station or service must not leave half-built track in the active slot.
    #[cfg(test)]
    fn build_service(
        &mut self,
        a: [f64; 2],
        b: [f64; 2],
        name: &str,
        clock: f64,
    ) -> Result<Value, String> {
        self.build_path(&[a, b], name, clock)
    }

    #[cfg(test)]
    fn build_path(&mut self, points: &[[f64; 2]], name: &str, clock: f64) -> Result<Value, String> {
        self.build_rail(points, name, clock, 0)
    }

    #[cfg(test)]
    fn build_rail(
        &mut self,
        points: &[[f64; 2]],
        name: &str,
        clock: f64,
        mode: u8,
    ) -> Result<Value, String> {
        self.build_alignment(points, name, clock, mode, &[])
    }

    fn build_alignment(
        &mut self,
        points: &[[f64; 2]],
        name: &str,
        clock: f64,
        mode: u8,
        bends: &[[f64; 3]],
    ) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if bends.len() > 64
            || bends.iter().any(|b| {
                b.iter().any(|v| !v.is_finite())
                    || b[0] < 0.
                    || b[0].fract() != 0.
                    || b[0] as usize >= points.len().saturating_sub(1)
            })
        {
            return Err("Titik lengkung berada di luar ruas.".into());
        }
        if !matches!(mode, 0 | 1 | 3 | 4 | 5) {
            return Err("Jenis rel belum didukung.".into());
        }

        if !clock.is_finite() {
            return Err("Buka Dunia bangun untuk mengedit.".into());
        }
        if !(2..=24).contains(&points.len()) || points.iter().flatten().any(|v| !v.is_finite()) {
            return Err("Pilih antara 2 dan 24 stasiun.".into());
        }
        if points
            .windows(2)
            .any(|p| (p[0][0] - p[1][0]).hypot(p[0][1] - p[1][1]) < 350.)
        {
            return Err("Letakkan stasiun berurutan setidaknya 350 meter terpisah.".into());
        }
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 48 {
            return Err("Nama layanan harus terdiri dari 1–48 karakter.".into());
        }
        let mut next = self.edit_candidate(clock).ok_or("Jaringan gagal disalin")?;
        let api = &mut next.api;
        api.set_now(clock);
        next.roads_dir = self.roads_dir.clone();
        if mode == 5 {
            let dir = next
                .roads_dir
                .as_deref()
                .ok_or("Data jalan belum tersedia.")?;
            let mut via = vec![points[0]];
            for (i, stop) in points[1..].iter().enumerate() {
                via.extend(
                    bends
                        .iter()
                        .filter(|b| b[0] as usize == i)
                        .map(|b| [b[1], b[2]]),
                );
                via.push(*stop);
            }
            let line = bus_plan::build(api, &via, name, dir)?;
            return self.accept_service(next, line, clock, name);
        }
        if mode == 4 {
            let line = ferry_plan::build(api, points, bends, name)?;
            return self.accept_service(next, line, clock, name);
        }
        api.set_route_kind(mode);
        api.set_route_hsr(mode == 1);
        api.set_design_speed(if mode == 1 {
            320.
        } else if mode == 3 {
            80.
        } else {
            160.
        });
        let node_at = |api: &TrackApi,
                       point: [f64; 2],
                       radius: f64,
                       station_only: bool|
         -> Option<(u32, f64, f64)> {
            let nodes = api.nodes_info();
            nodes
                .chunks_exact(10)
                .filter(|r| {
                    !station_only
                        || (r[7] > 0. && ((api.node_kind(r[0] as u32) >= 2) == (mode == 3)))
                })
                .filter_map(|r| {
                    let d = (r[1] - point[0]).hypot(r[2] - point[1]);
                    (d <= radius).then_some((d, r[0] as u32, r[1], r[2]))
                })
                .min_by(|a, b| a.0.total_cmp(&b.0))
                .map(|(_, id, x, y)| (id, x, y))
        };
        let mut stops = Vec::new();
        let mut needs_construction = false;
        let mut previous = None;
        for (i, pair) in points.windows(2).enumerate() {
            let a = previous.or_else(|| node_at(api, pair[0], 120., true));
            let b = node_at(api, pair[1], 120., true);
            let connected = matches!((a,b),(Some((first,_,_)),Some((second,_,_))) if first!=second && api.order_fault(&[first,second]).is_empty());
            let pis: Vec<f64> = bends
                .iter()
                .filter(|p| p[0] as usize == i)
                .flat_map(|p| [p[1], p[2], 0., 0.])
                .collect();
            if connected && !pis.is_empty() {
                return Err("Ruas ini sudah memiliki sambungan. Hapus titik lengkung untuk memakai rel yang ada.".into());
            }
            if !connected {
                needs_construction = true;
                let end = |point: [f64; 2], node: Option<(u32, f64, f64)>| {
                    if let Some((id, x, y)) = node {
                        [1., id as f64, x, y, 0.]
                    } else {
                        [0., 0., point[0], point[1], 0.]
                    }
                };
                let mut ends = end(pair[0], a).to_vec();
                ends.extend(end(pair[1], b));
                if api.add_route(&ends, &pis, 2, false) != 0 {
                    return Err(format!("Ruas {} ditolak: {}", i + 1, api.issue_kinds()));
                }
            }
            let mut endpoints = [None, None];
            for (j, selected) in [a, b].into_iter().enumerate() {
                let point = pair[j];
                let node = match selected {
                    Some(node) => node,
                    None => {
                        let (id, x, y) = node_at(api, point, 1., false)
                            .ok_or("Ujung jalur tidak ditemukan. Geser calon stasiun.")?;
                        let label = (b'A' + (i + j) as u8) as char;
                        if api.add_station(
                            1,
                            id,
                            x,
                            y,
                            if mode == 3 { 40 } else { 160 },
                            format!("{name} {label}"),
                        ) != 0
                        {
                            return Err(format!("Stasiun {label} ditolak: {}", api.issue_kinds()));
                        }
                        (id, x, y)
                    }
                };
                endpoints[j] = Some(node);
            }
            if i == 0 {
                stops.push(endpoints[0].unwrap().0);
            }
            previous = endpoints[1];
            stops.push(previous.unwrap().0);
        }
        let line = api.new_line_id();
        let colors = [0x007866, 0xcf5823, 0x6944ad, 0x0876b6, 0xbd3a62];
        let defaults = TrackApi::kind_defaults(mode);
        if api.set_line_with_trains(
            line,
            &stops,
            4,
            3,
            2,
            if mode == 0 { 20. } else { defaults[0] as f32 },
            if mode == 0 { 60. } else { defaults[1] as f32 },
            4,
            colors[line as usize % colors.len()],
            name.into(),
        ) != 0
            || (mode == 1 && api.set_line_fast(line, true) != 0)
            || (needs_construction && api.construct_line(line) != 0)
        {
            return Err(format!("Layanan ditolak: {}", api.issue_kinds()));
        }
        self.accept_service(next, line, clock, name)
    }
    fn accept_service(
        &mut self,
        mut next: Self,
        line: u32,
        clock: f64,
        name: &str,
    ) -> Result<Value, String> {
        let api = &next.api;
        let lines = api.lines_info();
        if !lines
            .chunks_exact(22)
            .any(|r| r[0] as u32 == line && r[3] == 1.)
        {
            return Err("Layanan belum dapat beroperasi.".into());
        }
        let trips = api.line_trips(line, clock, clock + 7200.);
        let departure = trips
            .chunks_exact(4)
            .filter_map(|r| (r[2] > clock).then_some(r[1].max(clock)))
            .min_by(f64::total_cmp)
            .ok_or("Jadwal keberangkatan tidak tersedia.")?;
        next.book_service_edit(self, clock);
        *self = next;
        Ok(json!({"line":line,"departure":departure,"name":name}))
    }

    fn book_service_edit(&mut self, original: &World, clock: f64) {
        // edit_candidate has already settled the previous network through this clock.
        let cash = self.api.cash();
        let charge = self.header["cash"].as_f64().unwrap_or(cash) - cash;
        let trains = self.api.fleet_value() - original.api.fleet_value();
        finance::book_charge(&mut self.header, clock, charge, trains);
        self.header["cash"] = json!(cash);
        self.header["fleet"] = json!(self.api.fleet());
    }

    fn edit_candidate(&self, clock: f64) -> Option<Self> {
        let clock = self.native_clock(clock);
        // Keep the last accounted time until the clone has booked the intervening interest.
        let accounted = self.header["clock"].as_f64().unwrap_or(clock);
        let mut next = World::open(&self.save(accounted)?)?;
        finance::tick(&mut next, clock);
        // The transaction may change the network before this candidate becomes the live world.
        next.operating = None;
        next.header["clock"] = json!(clock);
        next.api.copy_city_surface_from(&self.api);
        Some(next)
    }

    fn requires_studio_clock(&self) -> bool {
        self.header["mode"] != "sandbox"
    }

    // TWG1 has no boarding-income tables. Only Studio's full worker can settle a normal
    // game's elapsed time. Native edits still book their actual costs at the saved clock.
    fn native_clock(&self, requested: f64) -> f64 {
        if self.requires_studio_clock() {
            self.header["clock"].as_f64().unwrap_or(111600.)
        } else {
            requested
        }
    }

    fn native_save(&mut self, requested: f64) -> Option<Vec<u8>> {
        let clock = self.native_clock(requested);
        finance::tick(self, clock);
        self.save(clock)
    }

    fn save(&self, clock: f64) -> Option<Vec<u8>> {
        let mut header = self.header.clone();
        header["clock"] = json!(clock);
        // Older Android builds wrote this unused key; Studio treats unknown keys as a newer save.
        header.as_object_mut()?.remove("nativeLastLine");
        header["fleetFast"] = json!(self.api.fleet_fast());
        header["fleetBus"] = json!(self.api.fleet_bus());
        header["fleetFerry"] = json!(self.api.fleet_ferry());
        header["fleetTram"] = json!(self.api.fleet_tram());
        let net = &self.api.world().net;
        let lines: Vec<_> = (0..net.line_count() as u32).filter(|&id| net.line_ok(id)).collect();
        if lines.len() != net.line_count() {
            demolition::remap_header(&mut header, &lines);
        }
        let bytes = serde_json::to_vec(&header).ok()?;
        let mut out = GzEncoder::new(Vec::new(), Compression::fast());
        out.write_all(b"TWG1").ok()?;
        out.write_all(&(bytes.len() as u32).to_le_bytes()).ok()?;
        out.write_all(&bytes).ok()?;
        out.write_all(&self.api.save()).ok()?;
        out.finish().ok()
    }
    fn geometry(&self) -> Value {
        let lines = self.api.lines_info();
        let mut routes = Vec::new();
        for row in lines.chunks_exact(22).filter(|r| r[2] == 1.) {
            let id = row[0] as u32;
            let path = self.api.run_polyline(id, 0, 70.);
            let points: Vec<i32> = path.iter().map(|v| v.round() as i32).collect();
            routes.push(json!({"id":id,"color":row[1] as u32,"points":points,"name":self.api.line_name(id)}));
        }
        // Infrastructure belongs to the map even before a passenger service uses it.
        let tracks: Vec<_> = self.api.edges_info().chunks_exact(10)
             .map(|row| {
                let id = row[0] as u32;
                let points: Vec<i32> = self.api.edge_polyline(id, 0.0).into_iter()
                    .map(|coordinate| coordinate.round() as i32).collect();
                json!({"id":id,"points": points, "kind": self.api.edge_kind(id),
                       "level": row[8] as i32, "built":row[4] == 1.0, "topLevel": row[9] as i32, "length":row[5], "speed":row[7], "tracks": row[3] as u8})
            }).collect();
        let nodes=self.api.nodes_info();
        let loose_ends:Vec<_>=nodes.chunks_exact(10).filter(|r| r[4]==1.)
            .map(|r| json!([r[1],r[2],self.api.node_kind(r[0] as u32)])).collect();
        let planned_stations:Vec<_> = nodes.chunks_exact(10)
            .filter(|r|r[7] > 0. && r[8] == 0.).map(|r|json!([r[1],r[2],self.api.node_name(r[0] as u32)])).collect();
        let edge_use = station_map::edge_use(&self.api, &lines);
        let mut stops = Vec::new();
        for (packed,row) in nodes
            .chunks_exact(10)
            .enumerate()
            .filter(|(_,r)| r[7] > 0. && r[8] > 0.)
        {
            stops.push(json!([
                row[1].round() as i32,
                row[2].round() as i32,
                self.api.node_name(row[0] as u32),
                row[0] as u32,
                station_map::shape(&self.api, row[0] as usize, &edge_use),
                packed
            ]));
        }
        for id in self.api.bus_lines() {
            let names = self.service_stop_names(id);
            let line_name = self.api.line_name(id);
            for (s, name) in self.api.bus_stops(id).chunks_exact(8).zip(names) {
                if s[5] < 0. {
                    stops.push(json!([
                        s[1].round() as i32,
                        s[2].round() as i32,
                        format!("{name} · {line_name}"),
                        -1
                    ]));
                }
            }
        }
        let water: Value = serde_json::from_str(include_str!("../assets/nyc-water.json")).unwrap();
        let b = water["bounds"].as_array().unwrap();
        let sw = self
            .api
            .from_lonlat(b[0].as_f64().unwrap(), b[1].as_f64().unwrap());
        let ne = self
            .api
            .from_lonlat(b[2].as_f64().unwrap(), b[3].as_f64().unwrap());
        let origin = self.api.frame_origin();
        let home = self.api.from_lonlat(origin[0], origin[1]);
        json!({"opened":self.header["opened"],"viewed":self.header["androidViewed"],"mercatorPerFrame":if self.api.frame_kind() == 2 { 1. } else {1. / origin[1].to_radians().cos().max(0.01)},"cash":self.api.cash(),"requiresStudioClock":self.requires_studio_clock(),"homePoint":home,"waterBox":[sw[0],sw[1],ne[0],ne[1]],"looseEnds":loose_ends,"lastTrackEnd":self.header["androidTrackEnd"],"plannedStations":planned_stations,"routes":routes,"tracks":tracks,"stations":stops,"clock":self.header["clock"],"city":self.header["city"]})
    }
    fn load_trip_hour(&mut self, time: f64) -> f64 {
        let hour = (time / 3600.).floor() as i64;
        let epoch = (hour * 3600) as f64;
        if self.hour != hour {
            self.trips = self.api.trips(epoch);
            self.hour = hour;
        }
        epoch
    }
    fn positions(&mut self, time: f64) -> Vec<f32> {
        let epoch = self.load_trip_hour(time);
        let mut out = Vec::new();
        for trip in self.trips.chunks_exact(4) {
            let dep = epoch + trip[1] as f64;
            if time < dep || time >= epoch + trip[2] as f64 {
                continue;
            }
            let profile = trip[0] as usize;
            let line = (profile / 6) as u32;
            let p = self
                .api
                .train_at(line, profile / 3 % 2, profile % 3, dep, time);
            if let [x, y] = p.as_slice() {
                out.extend_from_slice(&[line as f32, *x as f32, *y as f32]);
            }
        }
        out
    }
}

// Handles are owned by one NativeWorldActivity, with calls serialized on the UI thread
// after construction. No mutable global state and no host accounts are accessed here.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_open(
    env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
) -> jlong {
    env.convert_byte_array(bytes)
        .ok()
        .and_then(|b| World::open(&b))
        .map_or(0, |w| Box::into_raw(Box::new(w)) as jlong)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_geometry(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    let world = unsafe { &*(handle as *const World) };
    env.new_string(world.geometry().to_string())
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}
fn guide_network_progress(world: &World, operating: bool) -> u64 {
    let lines = world.api.lines_info();
    let mut mask = 0;
    if operating {
        for line in lines.chunks_exact(22) {
            let trains = &line[13..16];
            if trains.iter().any(|&n| n > 0.) { mask |= 1 << 5; }
            if !world.api.is_bus(line[0] as u32) && trains.iter().all(|&n| n > 0.) { mask |= 1 << 8; }
        }
        if world.header["stars"].as_object().is_some_and(|s| !s.is_empty()) { mask |= 1 << 12; }
        return mask;
    }
    let mut length = 0.;
    let mut constructed = 0.;
    for edge in world.api.edges_info().chunks_exact(10) {
        length += edge[5];
        if edge[4] > 0. { constructed += edge[5]; }
    }
    let mut stations: Vec<[f64; 2]> = Vec::new();
    let mut built: Vec<[f64; 2]> = Vec::new();
    let mut built_count = 0usize;
    fn spaced(points: &mut Vec<[f64; 2]>, x: f64, y: f64) {
        if points.len() < 3 && points.iter().all(|p| (p[0] - x).hypot(p[1] - y) >= 500.) {
            points.push([x,y]);
        }
    }
    for node in world.api.nodes_info().chunks_exact(10).filter(|p| p[7] > 0.) {
        spaced(&mut stations, node[1], node[2]);
        if node[8] > 0. { spaced(&mut built,node[1],node[2]); built_count += 1; }
    }
    if length >= 5000. { mask |= 1; }
    if stations.len() >= 3 { mask |= 1 << 1; }
    if constructed >= 5000. && built.len() >= 3 { mask |= 1 << 2; }
    if !lines.is_empty() { mask |= 1 << 3; }
    if lines.chunks_exact(22).any(|l| world.api.line_stops(l[0] as u32).len() >= built_count.clamp(1,3)) {
        mask |= 1 << 4;
    }
    mask
}

// Tutorial checkpoints use the same bit positions and card numbers as Studio saves.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_guide(
    env: JNIEnv, _: JClass, handle: jlong, bit: i32, card: i32,
) -> jstring {
    let world = unsafe { &mut *(handle as *mut World) };
    if bit == -2 || (0..=16).contains(&bit) || (1..=2).contains(&card) {
        let operating = card == 2 || (card == 0 && world.header["guide"]["card"] == 2);
        let observed = guide_network_progress(world, operating);
        if !world.header["guide"].is_object() { world.header["guide"] = json!({"done":0}); }
        let guide = &mut world.header["guide"];
        let confirmed = if (0..=16).contains(&bit) { 1 << bit } else { 0 };
        guide["done"] = json!(guide["done"].as_u64().unwrap_or(0) | observed | confirmed);
        if (1..=2).contains(&card) {
            let object = guide.as_object_mut().unwrap();
            object.remove("hidden");
            object.remove("closed");
            if card == 2 { object.insert("card".into(), json!(2)); } else { object.remove("card"); }
        }
    }
    env.new_string(world.header["guide"].to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_positions(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
) -> jfloatArray {
    let world = unsafe { &mut *(handle as *mut World) };
    // Position sampling never settles money; Studio can supply its authoritative clock.
    let values = world.positions(time);
    let Ok(arr) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&arr, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_save(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
) -> jbyteArray {
    let world = unsafe { &mut *(handle as *mut World) };
    world
        .native_save(time)
        .and_then(|v| env.byte_array_from_slice(&v).ok())
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}
// Dialogs may still hold the editor handle when a checkpoint completes.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_swapWorlds(
    _: JNIEnv, _: JClass, editor: jlong, candidate: jlong,
) {
    unsafe { std::ptr::swap(editor as *mut World, candidate as *mut World) }
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_close(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut World)) }
    }
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_workshop(_: JNIEnv, _: JClass) -> jlong {
    Box::into_raw(Box::new(World::workshop())) as jlong
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_forkCandidate(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    clock: jdouble,
    dir: JString,
) -> jlong {
    let mut env = env;
    let w = unsafe { (handle as *const World).as_ref() };
    let candidate = (|| {
        let w = w?;
        let mut next = w.edit_candidate(clock)?;
        let directory: String = env.get_string(&dir).ok()?.into();
        next.roads_dir = Some(directory.into());
        Some(next)
    })();
    candidate.map_or(0, |v| Box::into_raw(Box::new(v)) as jlong)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_build(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    points: jni::objects::JDoubleArray,
    name: JString,
    clock: jdouble,
    mode: jni::sys::jint,
    bends: jni::objects::JDoubleArray,
) -> jstring {
    let w = unsafe { &mut *(handle as *mut World) };
    let name: String = env.get_string(&name).map(|v| v.into()).unwrap_or_default();
    let count = env.get_array_length(&points).unwrap_or(0) as usize;
    let mut coords = vec![0.; count.min(48)];
    let valid = count <= 48
        && count % 2 == 0
        && env.get_double_array_region(&points, 0, &mut coords).is_ok();
    let pairs: Vec<[f64; 2]> = if valid {
        coords.chunks_exact(2).map(|r| [r[0], r[1]]).collect()
    } else {
        vec![]
    };
    let len = env.get_array_length(&bends).unwrap_or(-1);
    let mut bend_values = vec![0.; len.clamp(0, 192) as usize];
    let valid_bends = (0..=192).contains(&len)
        && len % 3 == 0
        && env
            .get_double_array_region(&bends, 0, &mut bend_values)
            .is_ok();
    let curve: Vec<[f64; 3]> = bend_values
        .chunks_exact(3)
        .map(|p| [p[0], p[1], p[2]])
        .collect();
    let result = if valid_bends {
        w.build_alignment(
            &pairs,
            &name,
            clock,
            u8::try_from(mode).unwrap_or(255),
            &curve,
        )
    } else {
        Err("Titik lengkung tidak dapat dibaca.".into())
    }
    .unwrap_or_else(|reason| json!({"error":reason}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |v| v.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn a_curve_is_part_of_the_train_path_and_tw_g_save() {
        let mut w = World::workshop();
        w.build_alignment(
            &[[0., 0.], [7000., 0.]],
            "Busur",
            111600.,
            0,
            &[[0., 3500., 1700.]],
        )
        .unwrap();
        let path = w.api.run_polyline(0, 0, 12.);
        assert!(path.chunks_exact(2).any(|p| p[1] > 900.));
        assert_eq!(w.api.line_stops(0).len(), 2);
        assert_eq!(
            World::open(&w.save(111600.).unwrap())
                .unwrap()
                .api
                .run_polyline(0, 0, 12.),
            path
        );
    }

    #[test]
    fn stock_and_tracks_drive_distinct_times_and_survive_save() {
        let t = 111600.;
        let coords = [[0., 0.], [15000., 0.]];
        let mut peaks = vec![];
        for mode in [0, 1, 3] {
            let mut w = World::workshop();
            w.build_rail(&coords, "Uji", t, mode).unwrap();
            let w = World::open(&w.save(t).unwrap()).unwrap();
            assert_eq!(w.api.line_fast(0), mode == 1);
            assert_eq!(w.api.line_tram(0), mode == 3);
            assert_eq!(w.api.edge_kind(0), mode);
            peaks.push(w.api.line_stretch_speeds(0)[0]);
        }
        assert!(peaks[1] > peaks[0] && peaks[0] > peaks[2], "{peaks:?}");
    }
    #[test]
    fn multiple_stops_and_existing_corridor_are_preserved() {
        let mut w = World::workshop();
        let route = [[0., 0.], [1200., 0.], [2900., 240.]];
        w.build_path(&route, "Utama", 111600.).unwrap();
        let geo = w.geometry();
        assert_eq!(geo["stations"].as_array().unwrap().len(), 3);
        let edges = w.api.edges_info().len();
        w.build_path(&[route[0], route[2]], "Ekspres", 111600.)
            .unwrap();
        assert_eq!(w.api.edges_info().len(), edges);
        assert_eq!(w.api.line_stops(1).len(), 2);
        let restored = World::open(&w.save(111600.).unwrap()).unwrap();
        assert_eq!(restored.api.line_stops(0).len(), 3);
    }
    #[test]
    fn tutorial_detects_construction_goals_from_the_saved_network() {
        let mut world = World::workshop();
        assert_eq!(guide_network_progress(&world, false), 0);
        world.build_path(&[[0., 0.], [3000., 0.], [6000., 0.]], "Utama", 111600.).unwrap();
        assert_eq!(guide_network_progress(&world, false) & 31, 31);
        world.header["guide"] = json!({"card": 2, "done": 31});
        let resumed = World::open(&world.save(111600.).unwrap()).unwrap();
        assert_eq!(resumed.header["guide"], world.header["guide"]);
        assert_eq!(guide_network_progress(&resumed, false) & 31, 31);
    }

    #[test]
    fn build_service_survives_save() {
        let mut w = World::workshop();
        let now = 111600.;
        assert!(w.build_service([0., 0.], [1., 1.], "Test", now).is_err());
        assert!(w.api.lines_info().is_empty());
        let result = w
            .build_service([0., 0.], [2500., 900.], "Hudson", now)
            .unwrap();
        let t = result["departure"].as_f64().unwrap() + 2.;
        let a = w.positions(t);
        let b = w.positions(t + 25.);
        assert!(
            !a.is_empty() && a != b,
            "genuine scheduled service must move"
        );
        let mut reopened = World::open(&w.save(t + 25.).unwrap()).unwrap();
        assert_eq!(reopened.positions(t + 25.), b);
        assert_eq!(reopened.geometry()["stations"].as_array().unwrap().len(), 2);
        assert_eq!(
            reopened
                .api
                .line_name(result["line"].as_u64().unwrap() as u32),
            "Hudson"
        );
    }
    #[test]
    fn web_world_accepts_mobile_edits_and_preserves_web_metadata() {
        let mut w = World::workshop();
        w.header.as_object_mut().unwrap().remove("nativeWorkshop");
        w.header["notes"] = json!({"existing":"catatan dari Studio"});
        let clock = w.header["clock"].as_f64().unwrap();
        let added = w.build_service([0.,0.],[2000.,0.],"Lintas baru",clock).unwrap();
        let reopened = World::open(&w.save(clock).unwrap()).unwrap();
        assert_eq!(reopened.header["notes"], w.header["notes"]);
        assert_eq!(reopened.api.line_name(added["line"].as_u64().unwrap() as u32), "Lintas baru");
    }

    #[test]
    fn native_normal_edits_and_exports_keep_the_original_settlement_clock() {
        for legacy in [false, true] {
            let mut w = World::workshop();
            if legacy {
                w.header.as_object_mut().unwrap().remove("mode");
            } else {
                w.header["mode"] = json!("normal");
            }
            w.header["loan"] = json!(1000.);
            w.header["loanDays"] = json!(60.);
            w.header["loanPay"] = json!(20.);
            let t = w.header["clock"].as_f64().unwrap();
            let later = t + 4. * 86400.;
            let mut w = World::open(&w.save(t).unwrap()).unwrap();
            assert_eq!(w.geometry()["requiresStudioClock"], true);
            let before = w.header.clone();
            let untouched = World::open(&w.native_save(later).unwrap()).unwrap();
            assert_eq!(untouched.header, before);
            assert_eq!(w.edit_candidate(later).unwrap().header["clock"], t);
            w.build_service([0., 0.], [2500., 0.], "Jalur dibangun saat jeda", later).unwrap();
            let v = json!({"name":"Jalur pengganti","mode":"headway","peak":180,"day":360,"night":720,
                "weekend":65,"cars":5,"dwell":32,"turn":110});
            w.schedule(0, later, &v).unwrap();
            let restored = World::open(&w.native_save(later).unwrap()).unwrap();
            assert_eq!(restored.header["clock"], t);
            assert_eq!(restored.header["loanDays"], 60.);
            assert_eq!(restored.api.line_name(0), "Jalur pengganti");
            let row = &restored.header["ledger"][0];
            assert!(row["build"].as_f64().unwrap() > 0.);
            assert_eq!(row["running"], 0);
            assert!(row["upkeep"].is_null());
        }
        let mut sandbox = World::workshop();
        let later = sandbox.header["clock"].as_f64().unwrap() + 3600.;
        assert_eq!(sandbox.geometry()["requiresStudioClock"], false);
        assert_eq!(World::open(&sandbox.native_save(later).unwrap()).unwrap().header["clock"], later);
    }

    #[test]
    #[ignore = "driven by check-studio-roundtrip.mjs with a Studio WASM save"]
    fn edit_studio_fixture() {
        let directory = std::path::PathBuf::from(std::env::var_os("STUDIO_FIXTURE_DIR").unwrap());
        let clk = 111600.;
        for frame in 0..3 {
            let bytes = std::fs::read(directory.join(format!("web-{frame}.save"))).unwrap();
            let mut world = World::open(&bytes).expect("Studio save must load in Android");
            assert_eq!(world.api.frame_kind(), frame);
            let start = world.api.from_lonlat(-74.27, 40.80);
            let end = world.api.from_lonlat(-74.24, 40.80);
            world
                .build_service([start[0], start[1]], [end[0], end[1]], "Lintas Android", clk)
                .unwrap();
            let geometry = world.geometry();
            assert!(geometry["stations"].as_array().unwrap().len() > 1700);
            assert_ne!(world.positions(clk + 5.), world.positions(clk + 25.));
            std::fs::write(directory.join(format!("android-{frame}.save")), world.save(clk).unwrap()).unwrap();
        }
        let mut world = World::sandbox("jak", 106.8022, -6.1876);
        let a = world.api.from_lonlat(106.8236, -6.1861);
        let b = world.api.from_lonlat(106.8311, -6.1964);
        world.build_service([a[0], a[1]], [b[0], b[1]], "Lintas Jakarta", clk).unwrap();
        std::fs::write(directory.join("native-jak.save"), world.save(clk).unwrap()).unwrap();
        let local_shift = local_time::day_shift(&world.header);
        // Friday 19:00 Jakarta through Saturday 07:00, crossing all three service bands.
        let from = 5. * 86400. + 19. * 3600. - local_shift;
        world.header["clock"] = json!(from);
        world.header["mode"] = json!("normal");
        world.header["ledger"] = json!([]);
        world.header["hours"] = json!([]);
        world.header["regions"] = json!({});
        let start_save = world.save(from).unwrap();
        std::fs::write(directory.join("timetable-web.save"), &start_save).unwrap();
        let mut budget = World::open(&start_save).unwrap();
        let until = from + 12. * 3600.;
        finance::tick(&mut budget, until);
        std::fs::write(directory.join("timetable-android.save"), budget.save(until).unwrap()).unwrap();
        let initial = std::fs::read(directory.join("accounting-web.save")).unwrap();
        let mut budget = World::open(&initial).unwrap();
        let start = budget.header["clock"].as_f64().unwrap();
        finance::tick(&mut budget, start + 10.);
        let middle = budget.save(start + 10.).unwrap();
        let mut budget = World::open(&middle).unwrap();
        finance::tick(&mut budget, start + 20.);
        let settled = budget.header.clone();
        finance::tick(&mut budget, start + 20.);
        assert_eq!(budget.header, settled, "same clock must not debit twice");
        std::fs::write(directory.join("accounting-android.save"), budget.save(start + 20.).unwrap()).unwrap();
        for fixture in ["future-level.save", "unread-network.save"] {
            assert!(World::open(&std::fs::read(directory.join(fixture)).unwrap()).is_none());
        }
        let old = std::fs::read(directory.join("legacy-jak.save")).unwrap();
        let migrated = World::open(&old).unwrap().save(clk).unwrap();
        std::fs::write(directory.join("migrated-jak.save"), migrated).unwrap();
    }

    #[test]
    fn fixture_moves_and_reopens() {
        let mut w = World::open(include_bytes!("../../T-007-real-nyc.save")).unwrap();
        for (t, expected) in [
            (120., [-2327.91005854, -5233.13626701]),
            (130., [-2295.10236958, -5145.43412719]),
        ] {
            let actual = w.api.train_at(0, 0, 0, 0., t);
            assert!(
                (actual[0] - expected[0]).abs() < 1e-6 && (actual[1] - expected[1]).abs() < 1e-6
            );
            println!("line 0 t={t}: {:?}", actual);
        }
        let geo = w.geometry();
        assert!(geo["routes"].as_array().unwrap().len() > 10);
        let clock = w.header["clock"].as_f64().unwrap();
        let p = w.positions(clock);
        let q = w.positions(clock + 15.);
        assert!(p.len() > 30 && p != q);
        let mut again = World::open(&w.save(clock + 15.).unwrap()).unwrap();
        assert_eq!(again.header["clock"], clock + 15.);
        assert_eq!(again.positions(clock + 15.), q);
        println!(
            "{} lines, {} stations, {} train markers, active clock {}",
            geo["routes"].as_array().unwrap().len(),
            geo["stations"].as_array().unwrap().len(),
            p.len() / 3,
            clock
        );
    }
}
