//! Commute assignment for a snapshot of the native editor. A background thread owns both
//! the snapshot and solver so advancing trains and editing the live track stay independent.
use super::World;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jint, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap};
use trainworld_sim::{
    demand::api::DemandApi,
    track::frame::{Frame, FrameKind},
};

const ATTRACTIONS: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/attractions.bin"));

fn attractions(city: &str, frame: &Frame) -> Vec<f32> {
    let Some([w, s, e, n]) = super::demand_zones::bounds(city) else {
        return vec![];
    };
    let dy = 1000. / 111_320.;
    let dx = dy / s.abs().max(n.abs()).min(85.).to_radians().cos();
    let mut sites = Vec::new();
    for row in ATTRACTIONS.chunks_exact(24) {
        let value = |i: usize| f64::from_le_bytes(row[i..i + 8].try_into().unwrap());
        let (lon, lat) = (value(0), value(8));
        if lon >= w - dx && lon <= e + dx && lat >= s - dy && lat <= n + dy {
            let (x, y) = frame.from_lonlat(lon, lat);
            sites.extend([x as f32, y as f32, value(16) as f32]);
        }
    }
    sites
}

type MarketInput<'a> = (&'a [u8], &'a [u8], &'a [&'a str]);
struct LocalPack {
    header: String,
    cells: Vec<u8>,
    country: String,
}

fn commute(
    save: &[u8],
    packs: &[LocalPack],
    period: usize,
    market_input: Option<MarketInput>,
) -> Result<Value, String> {
    let world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
    let first = packs.first().ok_or("Paket kota tidak tersedia")?;
    let fares = super::fares::Fares::read(&world.header["fares"], &first.country);
    let mut api = world.api;
    let mut frames = Vec::new();
    for pack in packs {
        let city: Value = serde_json::from_str(&pack.header).map_err(|e| e.to_string())?;
        super::city_surface::install_streets(&mut api, &city, &pack.cells);
        let origin = &city["origin"];
        frames.push((
            city["city"].as_str().unwrap_or("").to_owned(),
            Frame::new(
                FrameKind::Equirect,
                origin["lon"].as_f64().ok_or("Bujur tidak ada")?,
                origin["lat"].as_f64().ok_or("Lintang tidak ada")?,
            ),
        ));
    }
    let nodes = api.nodes_info();
    let mut coords: HashMap<u32, (f64, f64)> = nodes
        .chunks_exact(10)
        .map(|r| (r[0] as u32, (r[1], r[2])))
        .collect();
    let rows = api.lines_info();
    let mut lines = Vec::new();
    let mut ids = BTreeSet::new();
    for row in rows.chunks_exact(22).filter(|r| r[3] == 1.) {
        let id = row[0] as u32;
        if api.is_bus(id) {
            for stop in api.bus_stops(id).chunks_exact(8).filter(|s| s[5] < 0.) {
                coords.entry(stop[0] as u32).or_insert((stop[1], stop[2]));
            }
        }
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
    let mut ll_all = Vec::new();
    let mut ix = HashMap::new();
    for id in ids {
        ix.insert(id, (ll_all.len() / 2) as u32);
        let (x, y) = coords[&id];
        let ll = api.to_lonlat(f64::from(x as f32), f64::from(y as f32));
        ll_all.extend_from_slice(&ll[..2]);
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
    let mut upgrades = vec![0u8; ix.len()];
    for pair in api.station_upgrades().chunks_exact(2) {
        if let Some(i) = ix.get(&pair[0]) {
            upgrades[*i as usize] = pair[1] as u8;
        }
    }
    let mut complexes: Vec<u32> = (0..ix.len() as u32).collect();
    let net = &api.world().net;
    for (&station, &index) in &ix {
        if station as usize >= net.node_count() || complexes[index as usize] != index {
            continue;
        }
        let members = net.built_complex_of(station);
        if let Some(group) = members.iter().filter_map(|s| ix.get(s)).min().copied() {
            for member in members.iter().filter_map(|s| ix.get(s)) {
                complexes[*member as usize] = group;
            }
        }
    }
    for (id, hall) in net.buildings.iter().enumerate() {
        if hall.is_some() {
            for member in api.building_members(id as u32) {
                if let Some(&slot) = ix.get(&member) {
                    upgrades[slot as usize] |= trainworld_sim::track::upgrades::IN_BUILDING;
                }
            }
        }
    }
    let mut passages = vec![];
    for pair in api.station_links().chunks_exact(2) {
        if let (Some(a), Some(b)) = (ix.get(&pair[0]), ix.get(&pair[1])) {
            passages.extend([*a, *b]);
        }
    }
    let mut span = vec![1.; 10 * stops.len()];
    let mut off = 0;
    let (mut tram, mut ferry, mut catch_n, mut catch_ll) = (vec![], vec![], vec![], vec![]);
    for (id, _, ss, _) in &lines {
        let n = ss.len();
        let part = api.line_span(*id);
        if part.len() == 10 * n {
            for q in 0..5 {
                span[2 * (q * stops.len() + off)..2 * (q * stops.len() + off + n)]
                    .copy_from_slice(&part[2 * q * n..2 * (q + 1) * n]);
            }
        }
        off += n;
        tram.push(api.line_tram(*id) as u8);
        ferry.push(api.is_ferry(*id) as u8);
        let catches = if api.is_bus(*id) {
            api.bus_catches(*id)
        } else {
            vec![]
        };
        let mut k = 0;
        for _ in 0..n {
            let count = catches.get(k).copied().unwrap_or(0.) as usize;
            catch_n.push(count as u32);
            if k < catches.len() {
                k += 1;
            }
            if count > (catches.len() - k) / 2 {
                return Err("Koridor bus tidak lengkap".into());
            }
            for v in catches[k..k + 2 * count].chunks_exact(2) {
                let ll = api.to_lonlat(f64::from(v[0] as f32), f64::from(v[1] as f32));
                catch_ll.extend_from_slice(&ll[..2]);
            }
            k += 2 * count;
        }
    }
    let (mut hop_km, mut base_mul, mut rate_mul) = (vec![], vec![], vec![]);
    for (id, _, ss, _) in &lines {
        let (own, rate) =
            fares.multiplier(*id, api.line_fast(*id), api.is_ferry(*id), api.is_bus(*id));
        for run in 0..2 {
            let hops = api.line_stop_s(*id, run);
            for k in 0..ss.len() {
                hop_km.push(if k + 1 < hops.len() {
                    ((hops[k + 1] - hops[k]) / 1000.).max(0.) as f32
                } else {
                    0.
                });
                base_mul.push(own);
                rate_mul.push(rate);
            }
        }
    }
    let to_curve = |v: &Value| {
        v["curve"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_f64().unwrap() as f32)
            .collect::<Vec<_>>()
    };
    let scalar = |v: &Value, k: &str| v[k].as_f64().unwrap() as f32;
    if span.iter().all(|&v| v == 1.) {
        span.clear();
    }
    let mut cities = Vec::new();
    for (pack, (id, frame)) in packs.iter().zip(frames) {
        let project = |points: &[f64]| -> Vec<f32> {
            points
                .chunks_exact(2)
                .flat_map(|ll| {
                    let (x, y) = frame.from_lonlat(ll[0], ll[1]);
                    [x as f32, y as f32]
                })
                .collect()
        };
        let xy = project(&ll_all);
        let catch_xy = project(&catch_ll);
        let sites = attractions(&id, &frame);
        #[cfg(test)]
        if let Ok(path) = std::env::var("WORLD_NETWORK_EXPORT") {
            let packed = json!({"attractions":sites,"xy":xy,"ll":ll_all,"counts":counts,"stops":stops,"back":reverse,"times":times,"tph":tph,"cars":cars,"complexes":complexes,"upgrades":upgrades,"passages":passages,"span":span,"tram":tram,"ferry":ferry,"catchN":catch_n,"catchXY":catch_xy,"catchLL":catch_ll,"hopKm":hop_km,"baseMul":base_mul,"rateMul":rate_mul,"localFare":fares.local,"marketFare":fares.market,"rawFare":fares.raw,"country":pack.country});
            std::fs::write(path, packed.to_string()).map_err(|e| e.to_string())?;
        }
        let mut solver = DemandApi::new(&pack.header, &pack.cells)?;
        let mut outings = trainworld_sim::demand::everyday::EverydayApi::beside_with(
            &solver,
            &pack.header,
            &pack.cells,
            &sites,
        )?;
        let local = &super::fares::Fares::read(&world.header["fares"], &pack.country).local;
        let wallet = scalar(local, "weight");
        solver.set_fare_weight(wallet)?;
        outings.set_fare_weight(wallet)?;
        solver.set_chunked(true);
        solver.set_complexes(&complexes);
        outings.set_complexes(&complexes);
        solver.set_upgrades(&upgrades);
        outings.set_upgrades(&upgrades);
        solver.set_passages(&passages);
        outings.set_passages(&passages);
        solver.set_span(&span);
        outings.set_span(&span);
        solver.set_tram_lines(&tram);
        solver.set_bus(&catch_n, &catch_xy);
        outings.set_bus(&catch_n, &catch_xy);
        solver.set_back_stops(&reverse);
        outings.set_back_stops(&reverse);
        solver.set_network(&xy, &counts, &stops, &times, &tph, &cars)?;
        outings.set_network(&xy, &counts, &stops, &times, &tph, &cars)?;
        let (base, rate) = (scalar(local, "base"), scalar(local, "rate"));
        solver.set_fares(base, rate, &hop_km)?;
        outings.set_fares(base, rate, &hop_km)?;
        solver.set_fare_mul(&rate_mul)?;
        outings.set_fare_mul(&rate_mul)?;
        solver.set_base_mul(&base_mul)?;
        outings.set_base_mul(&base_mul)?;
        let curve = to_curve(local);
        solver.set_fare_curve(&curve)?;
        outings.set_fare_curve(&curve)?;
        let transfer = scalar(local, "transfer");
        solver.set_transfer_fare(transfer)?;
        outings.set_transfer_fare(transfer)?;
        outings.set_ferry(&ferry)?;
        if !solver.solve(period) || !outings.solve(period) {
            return Err("Periode perjalanan tidak tersedia".into());
        }
        cities.push((solver, outings));
    }
    let mut market = if let Some((index, drives, zones)) = market_input {
        let mut m = super::regional::open(index, drives, zones)?;
        let regional = &fares.market;
        m.set_fare_weight(scalar(regional, "weight"))?;
        m.set_fare_factors(&regional["factors"].to_string())?;
        m.set_complexes(&complexes);
        m.set_upgrades(&upgrades);
        m.set_passages(&passages);
        m.set_span(&span);
        m.set_bus_ll(&catch_n, &catch_ll);
        m.set_back_stops(&reverse);
        m.set_network_ll(&ll_all, &counts, &stops, &times, &tph, &cars)?;
        m.set_fares(scalar(regional, "base"), scalar(regional, "rate"), &hop_km)?;
        m.set_fare_mul(&rate_mul)?;
        m.set_base_mul(&base_mul)?;
        m.set_fare_curve(&to_curve(regional))?;
        if !m.solve(period) {
            return Err("Perjalanan antarwilayah tidak tersedia".into());
        }
        Some(m)
    } else {
        None
    };
    // Match the web worker's three rounds: freeze the combined loads before advancing
    // either population, since each shares the same seats and boarding capacity.
    let mut free_board = Vec::new();
    for round in 0..=3 {
        let mut frozen = Vec::<f32>::new();
        for (solver, outings) in &cities {
            let combined = [
                solver.seg(period),
                solver.board(period),
                solver.alight(period),
            ]
            .concat();
            let everyday = [
                outings.seg(period),
                outings.board(period),
                outings.alight(period),
            ]
            .concat();
            if frozen.is_empty() {
                frozen.resize(combined.len(), 0.);
            }
            if combined.len() != frozen.len() || everyday.len() != frozen.len() {
                return Err("Susunan ruas perjalanan berbeda".into());
            }
            for ((total, a), b) in frozen.iter_mut().zip(combined).zip(everyday) {
                *total += a + b;
            }
        }
        if let Some(m) = market.as_mut() {
            let loads = [m.seg(period), m.board(period), m.alight(period)].concat();
            if loads.len() != frozen.len() {
                return Err("Susunan jaringan regional berbeda".into());
            }
            for (f, x) in frozen.iter_mut().zip(loads) {
                *f += x;
            }
        }
        let nodes = frozen.len() / 3;
        let board = &frozen[nodes..2 * nodes];
        if round == 0 {
            free_board.extend_from_slice(board);
        }
        if round == 3 {
            // The web accounts for passengers who would have boarded without crowding.
            let arrivals = free_board
                .iter()
                .zip(board)
                .map(|(free, last)| free.max(*last))
                .collect::<Vec<_>>();
            frozen.extend(arrivals);
            if cities.iter_mut().any(|(solver, outings)| {
                !solver.cap_with_external(period, &frozen)
                    || !outings.cap_with_external(period, &frozen)
            }) || market
                .as_mut()
                .is_some_and(|m| !m.cap_with_external(period, &frozen))
            {
                return Err("Susunan kapasitas akhir berbeda".into());
            }
            break;
        }
        if let Some(m) = market.as_mut() {
            if !m.crowd_with_external(period, &frozen) {
                return Err("Perhitungan kepadatan regional gagal".into());
            }
        }
        if cities.iter_mut().any(|(solver, outings)| {
            !solver.crowd_with_external(period, &frozen)
                || !outings.crowd_with_external(period, &frozen)
        }) {
            return Err("Perhitungan kapasitas tidak tersedia".into());
        }
    }
    let mut work = [0.; 14];
    let mut other = [0.; 14];
    let mut boardings = 0.;
    for (solver, outings) in &cities {
        for (total, values) in [
            (&mut work, solver.summary(period)),
            (&mut other, outings.summary(period)),
        ] {
            total[0] = values[0];
            for i in 1..total.len() {
                total[i] += values[i];
            }
        }
        boardings += solver
            .board(period)
            .into_iter()
            .chain(outings.board(period))
            .map(f64::from)
            .sum::<f64>();
    }
    let regional = market
        .as_ref()
        .map(|m| m.summary(period))
        .unwrap_or_else(|| vec![0.; 4]);
    let regional_board = market.as_ref().map(|m| m.board(period)).unwrap_or_default();
    boardings += regional_board.into_iter().map(f64::from).sum::<f64>();
    Ok(
        json!({"period":period,"lines":lines.len(),"stations":ix.len(),"rounds":work[0],"commuteRail":work[2],"outingsRail":other[2],"regionalRail":regional[2],"regionalTrips":regional[1],"trips":work[1]+other[1]+regional[1],"rail":work[2]+other[2]+regional[2],"walk":work[3]+other[3]+regional[3],"unserved":work[13]+other[13],"boardings":boardings}),
    )
}
#[derive(serde::Deserialize)]
pub(super) struct DemandFiles {
    local: Vec<[String; 2]>,
    markets: Vec<String>,
    index: Option<String>,
    drives: Option<String>,
}
pub(super) struct Inputs {
    packs: Vec<LocalPack>,
    markets: Vec<String>,
    index: Vec<u8>,
    drives: Vec<u8>,
}
impl DemandFiles {
    pub(super) fn read(&self) -> Result<Inputs, String> {
        use std::fs;
        let mut packs = Vec::new();
        for [h, c] in &self.local {
            let header = fs::read_to_string(h).map_err(|e| e.to_string())?;
            let city: Value = serde_json::from_str(&header).map_err(|e| e.to_string())?;
            let country = city["country"].as_str().unwrap_or("").to_owned();
            packs.push(LocalPack {
                header,
                country,
                cells: fs::read(c).map_err(|e| e.to_string())?,
            });
        }
        let markets = self
            .markets
            .iter()
            .map(fs::read_to_string)
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        let read_optional = |p: &Option<String>| {
            p.as_ref()
                .map(fs::read)
                .transpose()
                .map(Option::unwrap_or_default)
                .map_err(|e| e.to_string())
        };
        Ok(Inputs {
            packs,
            markets,
            index: read_optional(&self.index)?,
            drives: read_optional(&self.drives)?,
        })
    }
}
impl Inputs {
    fn estimate(&self, save: &[u8], period: usize) -> Result<Value, String> {
        if period >= 5 {
            return Err("Periode perjalanan tidak tersedia".into());
        }
        let zones: Vec<_> = self.markets.iter().map(String::as_str).collect();
        let market = (!zones.is_empty()).then_some((
            self.index.as_slice(),
            self.drives.as_slice(),
            zones.as_slice(),
        ));
        commute(save, &self.packs, period, market)
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_estimate(
    mut env: JNIEnv,
    _: JClass,
    save: JByteArray,
    manifest: JString,
    period: jint,
) -> jstring {
    let result = (|| {
        let save = env.convert_byte_array(save).map_err(|e| e.to_string())?;
        let spec: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        let files: DemandFiles = serde_json::from_str(&spec).map_err(|e| e.to_string())?;
        let data = files.read()?;
        data.estimate(&save, period as usize)
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
        let locals = std::env::var("WORLD_LOCAL_PACKS").unwrap_or_else(|_| id.clone());
        let packs: Vec<_> = locals
            .split(',')
            .map(|z| {
                let zone: Value =
                    serde_json::from_slice(&read(dir.join(format!("{z}.market.json"))).unwrap())
                        .unwrap();
                LocalPack {
                    header: std::fs::read_to_string(dir.join(format!("{z}.json"))).unwrap(),
                    cells: read(dir.join(format!("{z}.bin"))).unwrap(),
                    country: zone["country"].as_str().unwrap_or("").to_owned(),
                }
            })
            .collect();
        let base = std::env::var("WORLD_MARKET_INDEX").ok();
        let mi = base.as_ref().map(|p| std::fs::read(p).unwrap());
        let drives = base
            .as_ref()
            .map(|_| std::fs::read(dir.join("market-drives.bin")).unwrap());
        let market_zones = std::env::var("WORLD_MARKET_ZONES").unwrap_or_else(|_| id.clone());
        let zone_headers: Vec<_> = market_zones
            .split(',')
            .map(|z| std::fs::read_to_string(dir.join(format!("{z}.market.json"))).unwrap())
            .collect();
        let zones: Vec<_> = zone_headers.iter().map(String::as_str).collect();
        let market = mi
            .as_ref()
            .zip(drives.as_ref())
            .map(|(i, d)| (i.as_slice(), d.as_slice(), zones.as_slice()));
        let period = std::env::var("WORLD_PERIOD").map_or(0, |value| {
            value.parse::<usize>().expect("WORLD_PERIOD must be 0..4")
        });
        assert!(period < 5);
        let r = commute(&save, &packs, period, market).unwrap();
        if market.is_some() {
            assert!(r["regionalTrips"].as_f64().unwrap() > 0.)
        }
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
