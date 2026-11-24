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
    Ok(solve(save, packs, &[period], market_input, None, None, None)?.remove(0))
}

// Each population sees the same frozen load. The summation between barriers stays ordered.
fn advance(
    cities: &mut [(DemandApi, trainworld_sim::demand::everyday::EverydayApi)],
    market: Option<&mut trainworld_sim::demand::intercity::IntercityApi>,
    period: usize,
    round: usize,
    load: &[f32],
) -> bool {
    let (work, outings): (Vec<_>, Vec<_>) = cities.iter_mut().map(|(s, e)| (s, e)).unzip();
    std::thread::scope(|scope| {
        let commuters = scope.spawn(move || {
            work.into_iter().all(|s| match round {
                0 => s.solve(period),
                4 => s.cap_with_external(period, load),
                _ => s.crowd_with_external(period, load),
            })
        });
        let everyday = scope.spawn(move || {
            outings.into_iter().all(|s| match round {
                0 => s.solve(period),
                4 => s.cap_with_external(period, load),
                _ => s.crowd_with_external(period, load),
            })
        });
        let regional = market.is_none_or(|s| match round {
            0 => s.solve(period),
            4 => s.cap_with_external(period, load),
            _ => s.crowd_with_external(period, load),
        });
        let a = commuters.join().unwrap_or(false);
        let b = everyday.join().unwrap_or(false);
        a && b && regional
    })
}
/// The trip rows' zone meaning every local zone of the solve (the opened cities of a world, each
/// zone's rows in turn, all in the one world frame once packed).
pub(super) const ALL_ZONES: &str = "*";
/// Trip rows (`trip_cells::rows` or `purpose_rows`, longitude and latitude first) per local zone of
/// a solve, in its zone order.
pub(super) type ZoneRows = Vec<(String, Vec<f64>)>;
/// `ZoneRows` packed for the map (`pack_cells`), per local zone.
pub(super) type ZoneCells = Vec<(String, Vec<f32>)>;
/// Which trip rows a solve also works out (`solve`'s `cells`).
pub(super) enum TripRows<'a> {
    /// zone `.0`'s rows (`ALL_ZONES`: every zone's), with each filter purpose's when `.1`, into
    /// `.2` before the solve returns
    Into(&'a str, bool, &'a mut ZoneRows),
    /// every zone's filter purpose rows, worked out on a thread of their own once the periods
    /// are in, so the forecast is not kept waiting for them
    Later(std::sync::Arc<PendingRows>),
}
impl TripRows<'_> {
    /// whether zone `zone`'s periods are kept for the rows
    fn keeps(&self, zone: &str) -> bool {
        match self {
            TripRows::Into(id, _, _) => *id == ALL_ZONES || *id == zone,
            TripRows::Later(_) => true,
        }
    }
}
/// `TripRows::Later`'s rows once in, each zone's packed for the map (`pack_cells`).
#[derive(Default)]
pub(super) struct PendingRows {
    rows: std::sync::Mutex<Option<Result<std::sync::Arc<ZoneCells>, String>>>,
    done: std::sync::Condvar,
}
impl PendingRows {
    fn ready(&self) -> bool {
        self.rows
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .is_some()
    }
    /// whether their thread gave up (the next lens solves again)
    fn failed(&self) -> bool {
        self.rows
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
            .is_some_and(Result::is_err)
    }
    /// The rows, waiting for them while their thread works.
    fn wait(&self) -> Result<std::sync::Arc<ZoneCells>, String> {
        let mut rows = self
            .rows
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        loop {
            if let Some(done) = rows.as_ref() {
                return done.clone();
            }
            rows = self
                .done
                .wait(rows)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
        }
    }
}
/// The regional population handed to the rows' thread (`TripRows::Later`).
struct Regional(Option<trainworld_sim::demand::intercity::IntercityApi>);
// SAFETY: `IntercityApi` is not `Send` only for the `Rc`s of its market. Every one of them belongs
// to this value: `regional::open` drops its pack store before it returns, `solve` clones none out
// and no thread-local keeps one. Moving the sole owner moves the whole graph, so no count is ever
// touched from two threads.
unsafe impl Send for Regional {}
/// Fills a `PendingRows` once; dropped unfilled (a panic, a failed spawn) it gives up, so no lens
/// waits for ever.
struct Fill(std::sync::Arc<PendingRows>);
impl Fill {
    fn put(&self, rows: Result<ZoneCells, String>) {
        let mut slot = self
            .0
            .rows
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if slot.is_none() {
            *slot = Some(rows.map(std::sync::Arc::new));
        }
        self.0.done.notify_all();
    }
}
impl Drop for Fill {
    fn drop(&mut self) {
        self.put(Err("Perhitungan perjalanan terputus".into()));
    }
}
/// The trip rows of zone `id` (`ALL_ZONES`: every zone of the solve) from the solved populations.
fn zone_trip_rows(
    id: &str,
    by_purpose: bool,
    frames: &[(String, Frame)],
    cities: &[(DemandApi, trainworld_sim::demand::everyday::EverydayApi)],
    market: Option<&trainworld_sim::demand::intercity::IntercityApi>,
) -> Result<ZoneRows, String> {
    let rows = if by_purpose {
        crate::trip_cells::purpose_rows
    } else {
        crate::trip_cells::rows
    };
    let mut out = ZoneRows::new();
    for ((zone, frame), (work, outings)) in frames.iter().zip(cities) {
        if id == ALL_ZONES || zone == id {
            out.push((zone.clone(), rows(zone, *frame, work, outings, market)?));
        }
    }
    if out.is_empty() {
        return Err("Zona perjalanan tidak tersedia".into());
    }
    Ok(out)
}
/// What the trip lenses' call (`Inputs::trip_cells`) is doing, for its progress line: 0 nothing,
/// 1 reading the network, 2 waiting for a solve already running (the clock's), 3 solving.
static LENS_STAGE: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);
fn lens_stage(stage: i32) {
    LENS_STAGE.store(stage, std::sync::atomic::Ordering::Relaxed);
}
/// `rows`' zone `id` (`ALL_ZONES`: each zone's in turn) as one run of rows; None for no such zone.
fn zone_rows<T: Copy>(rows: &[(String, Vec<T>)], id: &str) -> Option<Vec<T>> {
    let mut out = Vec::new();
    let mut found = false;
    for (zone, part) in rows {
        if id == ALL_ZONES || zone == id {
            found = true;
            out.extend_from_slice(part);
        }
    }
    found.then_some(out)
}
/// Filter purpose rows as the map draws them: each row's position in the world frame `frame`
/// instead of its longitude and latitude, its counts after it, as floats.
fn pack_cells(frame: Frame, rows: &[f64]) -> Vec<f32> {
    let mut packed = Vec::with_capacity(rows.len());
    for row in rows.chunks_exact(crate::trip_cells::PURPOSE_STRIDE) {
        let (x, y) = frame.from_lonlat(row[0], row[1]);
        packed.extend([x as f32, y as f32]);
        packed.extend(row[2..].iter().map(|v| *v as f32));
    }
    packed
}
fn solve(
    save: &[u8],
    packs: &[LocalPack],
    periods: &[usize],
    market_input: Option<MarketInput>,
    mut account: Option<&mut super::passenger_account::Forecast>,
    geo: Option<&crate::demand_zones::Geography>,
    // the zone whose trip rows to write, and whether with each filter purpose's (`trip_cells`)
    cells: Option<TripRows>,
) -> Result<Vec<Value>, String> {
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
    for (pack, (id, frame)) in packs.iter().zip(frames.iter().cloned()) {
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
        Some(m)
    } else {
        None
    };
    if let Some(account) = account.as_deref_mut() {
        account.set_layout(
            &packs
                .iter()
                .map(|p| {
                    serde_json::from_str::<Value>(&p.header).unwrap()["city"]
                        .as_str()
                        .unwrap_or("")
                        .to_string()
                })
                .collect::<Vec<_>>(),
        );
        if let Some(geo) = geo {
            let mut prices = std::collections::BTreeMap::new();
            for (id, _, served, _) in &lines {
                let zones = served
                    .iter()
                    .map(|s| {
                        let i = 2 * ix[s] as usize;
                        geo.zone(ll_all[i], ll_all[i + 1])
                    })
                    .collect();
                account.line_zones(*id, zones, &fares, &mut prices);
            }
        }
    }
    let mut results = Vec::new();
    for &period in periods {
        if !advance(&mut cities, market.as_mut(), period, 0, &[]) {
            return Err("Periode perjalanan tidak tersedia".into());
        }
        // Match the web worker's three rounds: freeze the combined loads before advancing
        // either population, since each shares the same seats and boarding capacity.
        let mut free_board = Vec::new();
        let mut rounds_board = Vec::new();
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
                    *total += a;
                    *total += b;
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
                rounds_board.extend_from_slice(board);
                free_board = arrivals;
                frozen.extend(&free_board);
                if !advance(&mut cities, market.as_mut(), period, 4, &frozen) {
                    return Err("Susunan kapasitas akhir berbeda".into());
                }
                break;
            }
            if !advance(&mut cities, market.as_mut(), period, round + 1, &frozen) {
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
        results.push(
        json!({"period":period,"lines":lines.len(),"stations":ix.len(),"rounds":work[0],"commuteRail":work[2],"outingsRail":other[2],"regionalRail":regional[2],"regionalTrips":regional[1],"trips":work[1]+other[1]+regional[1],"rail":work[2]+other[2]+regional[2],"walk":work[3]+other[3]+regional[3],"unserved":work[13]+other[13],"boardings":boardings}),
    );
        if let Some(account) = account.as_deref_mut() {
            account.add_period(
                period,
                &cities,
                market.as_ref(),
                &free_board,
                &rounds_board,
                &lines.iter().map(|l| l.0).collect::<Vec<_>>(),
                &counts,
                &hop_km,
                &base_mul,
                &rate_mul,
                &fares.market,
            )?;
        }
        for (i, (work, other)) in cities.iter_mut().enumerate() {
            if cells.as_ref().is_some_and(|c| c.keeps(&frames[i].0)) {
                continue;
            }
            work.forget_period(period);
            other.forget_period(period);
        }
        if cells.is_none() {
            if let Some(m) = market.as_mut() {
                m.forget_period(period);
            }
        }
    }
    match cells {
        Some(TripRows::Into(id, by_purpose, out)) => {
            *out = zone_trip_rows(id, by_purpose, &frames, &cities, market.as_ref())?;
        }
        Some(TripRows::Later(pending)) => {
            let fill = Fill(pending);
            let regional = Regional(market);
            let world_frame = api.world().net.frame();
            // a failed spawn drops `fill`, which tells the waiting lenses
            let _ = std::thread::Builder::new()
                .name("trip-rows".into())
                .spawn(move || {
                    let market = regional;
                    let rows = zone_trip_rows(ALL_ZONES, true, &frames, &cities, market.0.as_ref());
                    fill.put(rows.map(|zones| {
                        zones
                            .into_iter()
                            .map(|(zone, rows)| (zone, pack_cells(world_frame, &rows)))
                            .collect()
                    }))
                });
        }
        None => {}
    }
    Ok(results)
}
#[derive(Clone, PartialEq, serde::Deserialize)]
pub(super) struct DemandFiles {
    local: Vec<[String; 2]>,
    markets: Vec<String>,
    index: Option<String>,
    drives: Option<String>,
    boundaries: Option<String>,
}
pub(super) struct Inputs {
    files: DemandFiles,
    packs: Vec<LocalPack>,
    markets: Vec<String>,
    index: Vec<u8>,
    drives: Vec<u8>,
    geo: crate::demand_zones::Geography,
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
        let ids = self
            .markets
            .iter()
            .filter_map(|s| {
                let p = std::path::Path::new(s);
                let name = p.file_name()?.to_str()?;
                Some(
                    if name == "market.json" {
                        p.parent()?.file_name()?.to_str()?
                    } else {
                        name.strip_suffix(".market.json")?
                    }
                    .to_owned(),
                )
            })
            .collect::<Vec<_>>();
        let geo = self
            .boundaries
            .as_ref()
            .map(|s| crate::demand_zones::Geography::read(std::path::Path::new(s), &ids))
            .transpose()?
            .unwrap_or_default();
        Ok(Inputs {
            files: self.clone(),
            geo,
            packs,
            markets,
            index: read_optional(&self.index)?,
            drives: read_optional(&self.drives)?,
        })
    }
}
// Preview, playback, network overlays and the trip lenses share an assignment for identical
// track, fares and immutable release assets. Holding the lock during the solve coalesces
// overlapping callers: a lens opened while the clock is still warming waits for that solve.
struct CachedForecast {
    track: Vec<u8>,
    fares: Value,
    files: DemandFiles,
    /// None for a network with no running service (nothing to forecast, trips still drawn)
    forecast: Option<std::sync::Arc<crate::passenger_account::Forecast>>,
    /// every zone's filter purpose rows (`trip_cells::purpose_rows`): the Home, Destination and
    /// Total trips lenses
    cells: std::sync::Arc<PendingRows>,
}
/// The last solves, most recent first. Two: the clock's inputs and the lenses' (which also carry
/// the viewed zones, `NativeRidership.inputs` includeViewed) may differ.
static FORECAST: std::sync::Mutex<Vec<CachedForecast>> = std::sync::Mutex::new(Vec::new());
const KEPT_SOLVES: usize = 2;
impl Inputs {
    /// The zones of the boundaries read (`DemandFiles::boundaries`), for the all cities view.
    pub(super) fn geography(&self) -> &crate::demand_zones::Geography {
        &self.geo
    }
    pub(super) fn forecast(
        &self,
        save: &[u8],
        world: &mut World,
    ) -> Result<std::sync::Arc<crate::passenger_account::Forecast>, String> {
        Ok(self
            .solved(save, world, false)?
            .and_then(|(f, _)| f)
            .unwrap_or_default())
    }
    /// The trip lenses' cells of zone `zone` (`ALL_ZONES` for every one): per cell its x and y in
    /// the world frame then `trip_cells::PURPOSE_STRIDE - 2` counts. Read from the clock's solve
    /// when that is for the same network and files, otherwise solved once and kept.
    pub(super) fn trip_cells(&self, save: &[u8], zone: &str) -> Result<Vec<f32>, String> {
        lens_stage(1);
        let packed = (|| {
            let mut world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
            let (_, pending) = self
                .solved(save, &mut world, true)?
                .ok_or("Data perjalanan belum tersedia")?;
            if !pending.ready() {
                lens_stage(2);
            }
            let cells = pending.wait()?;
            zone_rows(&cells, zone).ok_or_else(|| "Zona perjalanan tidak tersedia".to_string())
        })();
        lens_stage(0);
        packed
    }
    /// The five periods' solve for `world` (streets installed here), from the cache when the track,
    /// fares and files match. With no running service there is nothing to forecast: None unless
    /// `cells` asks for the trip rows anyway (everyone walks or drives).
    #[allow(clippy::type_complexity)]
    fn solved(
        &self,
        save: &[u8],
        world: &mut World,
        cells: bool,
    ) -> Result<
        Option<(
            Option<std::sync::Arc<crate::passenger_account::Forecast>>,
            std::sync::Arc<PendingRows>,
        )>,
        String,
    > {
        for pack in &self.packs {
            let header: Value = serde_json::from_str(&pack.header).map_err(|e| e.to_string())?;
            super::city_surface::install_streets(&mut world.api, &header, &pack.cells);
        }
        let running = world.api.world().svc.lines.iter().any(|s| s.running);
        if !running && !cells {
            return Ok(None);
        }
        let track = world.api.save();
        let fares = &world.header["fares"];
        let mut cache = match FORECAST.try_lock() {
            Ok(cache) => cache,
            Err(std::sync::TryLockError::WouldBlock) => {
                if cells {
                    lens_stage(2);
                }
                FORECAST
                    .lock()
                    .map_err(|_| "Perhitungan perjalanan terputus")?
            }
            Err(_) => return Err("Perhitungan perjalanan terputus".into()),
        };
        // a solve whose rows were lost is solved again for a lens (its forecast still serves)
        cache.retain(|c| !(cells && c.cells.failed()));
        if let Some(i) = cache
            .iter()
            .position(|c| c.track == track && c.fares == *fares && c.files == self.files)
        {
            let saved = cache.remove(i);
            let found = (saved.forecast.clone(), saved.cells.clone());
            cache.insert(0, saved);
            return Ok(Some(found));
        }
        if cells {
            lens_stage(3);
        }
        let mut forecast = running.then(crate::passenger_account::Forecast::default);
        let rows = std::sync::Arc::new(PendingRows::default());
        let zones: Vec<_> = self.markets.iter().map(String::as_str).collect();
        let market = (!zones.is_empty()).then_some((
            self.index.as_slice(),
            self.drives.as_slice(),
            zones.as_slice(),
        ));
        solve(
            save,
            &self.packs,
            &[0, 1, 2, 3, 4],
            market,
            forecast.as_mut(),
            Some(&self.geo),
            Some(TripRows::Later(rows.clone())),
        )?;
        let forecast = forecast.map(std::sync::Arc::new);
        cache.insert(
            0,
            CachedForecast {
                track,
                fares: fares.clone(),
                files: self.files.clone(),
                forecast: forecast.clone(),
                cells: rows.clone(),
            },
        );
        cache.truncate(KEPT_SOLVES);
        Ok(Some((forecast, rows)))
    }
    pub(super) fn account(
        &self,
        save: &[u8],
        world: &mut World,
    ) -> Result<super::timed_account::Account, String> {
        self.account_and_forecast(save, world).map(|(a, _)| a)
    }
    /// `account` and the forecast it was made from (the train and station inspectors' riders).
    pub(super) fn account_and_forecast(
        &self,
        save: &[u8],
        world: &mut World,
    ) -> Result<
        (
            super::timed_account::Account,
            std::sync::Arc<crate::passenger_account::Forecast>,
        ),
        String,
    > {
        let forecast = self.forecast(save, world)?;
        let costs = super::operating::Costs::with_prices(&mut world.api, &forecast.run_prices());
        let clock = world.header["clock"].as_f64().ok_or("Jam tidak valid")?;
        let upkeep = self.geo.upkeep(&mut world.api);
        let account =
            super::timed_account::Account::new(&forecast, &mut world.api, clock, &costs, upkeep);
        world.operating = Some(costs);
        Ok((account, forecast))
    }

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
    fn zone_rows_join_zones_and_pack_projects_positions() {
        let stride = crate::trip_cells::PURPOSE_STRIDE;
        let row = |lon: f64, v: f64| {
            let mut r = vec![lon, -6.2];
            r.extend((2..stride).map(|k| v + k as f64));
            r
        };
        let rows: ZoneRows = vec![
            ("jak".into(), [row(106.8, 0.), row(106.9, 1.)].concat()),
            ("bdg".into(), vec![]),
            ("bgr".into(), row(106.7, 2.)),
        ];
        assert_eq!(zone_rows(&rows, ALL_ZONES).unwrap().len(), 3 * stride);
        assert_eq!(zone_rows(&rows, "bgr").unwrap(), row(106.7, 2.));
        assert_eq!(zone_rows(&rows, "bdg").unwrap(), Vec::<f64>::new());
        assert!(zone_rows(&rows, "sby").is_none());
        let world = World::workshop();
        let all = zone_rows(&rows, ALL_ZONES).unwrap();
        let packed = pack_cells(world.api.world().net.frame(), &all);
        assert_eq!(packed.len(), all.len());
        for (p, r) in packed.chunks_exact(stride).zip(all.chunks_exact(stride)) {
            let xy = world.api.from_lonlat(r[0], r[1]);
            assert_eq!(p[..2], [xy[0] as f32, xy[1] as f32]);
            assert!(p[2..].iter().zip(&r[2..]).all(|(a, b)| *a == *b as f32));
        }
    }
    /// The trip lenses read the clock's solve (`Inputs::trip_cells` after `account_and_forecast`)
    /// and draw exactly what their own solve gave (the old `tripCells`: every period kept, no
    /// forecast), and times both. Inputs as `NativeRidership.inputs` makes them, from
    /// WORLD_CITY_DATA (packs, `market-drives.bin`, boundaries), WORLD_CITY, WORLD_MARKET_INDEX and
    /// WORLD_SAVE. WORLD_LENS_PHASE=before|after runs only the clock's solve, before or after the
    /// lenses joined it (peak memory per run).
    #[test]
    #[ignore = "requires the pinned city packs and a network save"]
    fn trip_lens_from_clock_solve() {
        use std::time::Instant;
        let dir = std::env::var("WORLD_CITY_DATA").unwrap();
        let home = std::env::var("WORLD_CITY").unwrap();
        let save = std::fs::read(std::env::var("WORLD_SAVE").unwrap()).unwrap();
        let zones = crate::demand_zones::plan(&save, std::path::Path::new(&dir)).unwrap();
        let ids = |k: &str| -> Vec<String> {
            zones[k]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_owned())
                .collect()
        };
        let mut local = vec![home.clone()];
        local.extend(ids("local").into_iter().filter(|z| *z != home));
        let mut markets = ids("markets");
        if !markets.contains(&home) {
            markets.push(home.clone());
        }
        let spec = json!({
            "local": local.iter().map(|z| [format!("{dir}/{z}.json"), format!("{dir}/{z}.bin")]).collect::<Vec<_>>(),
            "markets": markets.iter().map(|z| format!("{dir}/{z}.market.json")).collect::<Vec<_>>(),
            "index": std::env::var("WORLD_MARKET_INDEX").unwrap(),
            "drives": format!("{dir}/market-drives.bin"),
            "boundaries": dir,
        });
        let files: DemandFiles = serde_json::from_value(spec).unwrap();
        let world = World::open(&save).unwrap();
        let lines = world
            .api
            .world()
            .svc
            .lines
            .iter()
            .filter(|s| s.running)
            .count();
        eprintln!("local {local:?}, markets {markets:?}, running lines {lines}");
        let t = Instant::now();
        let data = files.read().unwrap();
        eprintln!("read inputs: {:?}", t.elapsed());
        let zone_ids: Vec<_> = data.markets.iter().map(String::as_str).collect();
        let market = Some((data.index.as_slice(), data.drives.as_slice(), &zone_ids[..]));
        match std::env::var("WORLD_LENS_PHASE").as_deref() {
            Ok("before") => {
                let t = Instant::now();
                let mut f = crate::passenger_account::Forecast::default();
                solve(
                    &save,
                    &data.packs,
                    &[0, 1, 2, 3, 4],
                    market,
                    Some(&mut f),
                    Some(&data.geo),
                    None,
                )
                .unwrap();
                eprintln!("clock solve without the lenses' rows: {:?}", t.elapsed());
                return;
            }
            Ok("after") => {
                let t = Instant::now();
                let mut world = World::open(&save).unwrap();
                data.account_and_forecast(&save, &mut world).unwrap();
                eprintln!("clock solve with the lenses' rows: {:?}", t.elapsed());
                return;
            }
            _ => {}
        }
        // the old tripCells: its own solve, every period kept
        let t = Instant::now();
        let mut rows = ZoneRows::new();
        solve(
            &save,
            &data.packs,
            &[0, 1, 2, 3, 4],
            market,
            None,
            None,
            Some(TripRows::Into(ALL_ZONES, true, &mut rows)),
        )
        .unwrap();
        let frame = World::open(&save).unwrap().api.world().net.frame();
        let packed = |id: &str| {
            let rows = zone_rows(&rows, id).unwrap();
            let fresh = World::open(&save).unwrap();
            // the old tripCells' projection, through the editor's API
            let mut out = Vec::with_capacity(rows.len());
            for row in rows.chunks_exact(crate::trip_cells::PURPOSE_STRIDE) {
                out.extend(
                    fresh
                        .api
                        .from_lonlat(row[0], row[1])
                        .into_iter()
                        .map(|v| v as f32),
                );
                out.extend(row[2..].iter().map(|v| *v as f32));
            }
            assert_eq!(out, pack_cells(frame, &rows));
            out
        };
        let expected = packed(ALL_ZONES);
        eprintln!(
            "lens, own solve (before): {:?}, {} cells",
            t.elapsed(),
            expected.len() / crate::trip_cells::PURPOSE_STRIDE
        );
        // the clock's solve, then the lens on the same network
        FORECAST.lock().unwrap().clear();
        let t = Instant::now();
        let mut world = World::open(&save).unwrap();
        let (_, forecast) = data.account_and_forecast(&save, &mut world).unwrap();
        eprintln!("clock solve with the lenses' rows: {:?}", t.elapsed());
        let t = Instant::now();
        let got = files.read().unwrap().trip_cells(&save, ALL_ZONES).unwrap();
        eprintln!("lens after the clock (after): {:?}", t.elapsed());
        assert_eq!(got.len(), expected.len());
        assert!(
            got.iter()
                .zip(&expected)
                .all(|(a, b)| a.to_bits() == b.to_bits()),
            "lens cells differ"
        );
        for zone in &local {
            let one = data.trip_cells(&save, zone).unwrap();
            assert_eq!(one, packed(zone), "{zone}");
        }
        // the same forecast again, from the cache
        let mut again = World::open(&save).unwrap();
        assert!(std::sync::Arc::ptr_eq(
            &forecast,
            &data.forecast(&save, &mut again).unwrap()
        ));
        // an edit away and back: the lens solves once more, then reads its own
        let mut edited = World::open(&save).unwrap();
        edited.header["fares"]["lensProbe"] = 1.into();
        let other = edited
            .save(edited.header["clock"].as_f64().unwrap())
            .unwrap();
        let t = Instant::now();
        data.trip_cells(&other, ALL_ZONES).unwrap();
        eprintln!("lens on another network (own solve): {:?}", t.elapsed());
        let t = Instant::now();
        assert_eq!(data.trip_cells(&save, ALL_ZONES).unwrap(), got);
        data.trip_cells(&other, ALL_ZONES).unwrap();
        eprintln!("both lenses again from the cache: {:?}", t.elapsed());
    }
    /// The purpose filter's rows (`trip_cells::purpose_rows`) against every purpose's: the same
    /// cells and totals in the first eight counts, and the seven purposes never above them (the
    /// longer trips make the rest). WORLD_CITY_DATA holds `<city>.json/.bin/.market.json` and
    /// `market-drives.bin`; WORLD_MARKET_INDEX the regional index; WORLD_SAVE a network save.
    /// The Traffic view's riders per map filter purpose (`purpose_spans`) against every purpose's
    /// (`spans`, unchanged): one row of seven a span, none negative, together never above the
    /// span's riders (the longer trips make the rest), on rail and bus lines both. Inputs as in
    /// `purpose_cells`, the boundaries in WORLD_CITY_DATA too.
    #[test]
    #[ignore = "requires the pinned city pack and an original network save"]
    fn purpose_traffic() {
        let dir = std::env::var("WORLD_CITY_DATA").unwrap();
        let id = std::env::var("WORLD_CITY").unwrap();
        let save = std::fs::read(std::env::var("WORLD_SAVE").unwrap()).unwrap();
        let spec = json!({
            "local": [[format!("{dir}/{id}.json"), format!("{dir}/{id}.bin")]],
            "markets": [format!("{dir}/{id}.market.json")],
            "index": std::env::var("WORLD_MARKET_INDEX").unwrap(),
            "drives": format!("{dir}/market-drives.bin"),
            "boundaries": dir,
        });
        let files: DemandFiles = serde_json::from_value(spec).unwrap();
        let mut world = World::open(&save).unwrap();
        let forecast = files.read().unwrap().forecast(&save, &mut world).unwrap();
        crate::operating::Costs::with_prices(&mut world.api, &forecast.run_prices());
        let layers = serde_json::to_value(crate::network_layers::build(
            &world.api,
            &forecast.map_lines(),
        ))
        .unwrap();
        let col = |k: &str| -> Vec<f64> {
            layers[k]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_f64().unwrap())
                .collect()
        };
        let (spans, parts) = (col("spans"), col("purpose_spans"));
        let np = crate::trip_cells::PURPOSES.len();
        assert!(!spans.is_empty());
        assert_eq!(spans.len() / 5 * np, parts.len());
        let (mut all, mut filtered, mut bus) = (0., vec![0.; np], 0.);
        for (s, p) in spans.chunks_exact(5).zip(parts.chunks_exact(np)) {
            assert!(p.iter().all(|v| *v >= 0.), "negative riders {p:?}");
            let sum: f64 = p.iter().sum();
            assert!(
                sum <= s[3] * 1.0001 + 0.01,
                "purposes {sum} above the span's {}",
                s[3]
            );
            all += s[3];
            for (f, v) in filtered.iter_mut().zip(p) {
                *f += v;
            }
            if world.api.is_bus(s[0] as u32) {
                bus += sum;
            }
        }
        let shares: Vec<_> = crate::trip_cells::PURPOSES
            .iter()
            .zip(&filtered)
            .map(|((n, _), v)| format!("{n} {:.1}%", 100. * v / all))
            .collect();
        eprintln!(
            "spans {}, riders-km rows {all:.0}, bus by purpose {bus:.0}: {}",
            spans.len() / 5,
            shares.join(", ")
        );
        assert!(filtered[0] > 0., "no work riders");
    }
    #[test]
    #[ignore = "requires the pinned city pack and an original network save"]
    fn purpose_cells() {
        let dir = std::path::PathBuf::from(std::env::var("WORLD_CITY_DATA").unwrap());
        let id = std::env::var("WORLD_CITY").unwrap();
        let read = |p: std::path::PathBuf| std::fs::read(p).unwrap();
        let save = read(std::env::var("WORLD_SAVE").unwrap().into());
        // WORLD_LOCAL_PACKS: the zones of the solve (default WORLD_CITY); WORLD_CITY `*` asks for all
        let locals = std::env::var("WORLD_LOCAL_PACKS").unwrap_or_else(|_| id.clone());
        let headers: Vec<_> = locals
            .split(',')
            .map(|z| std::fs::read_to_string(dir.join(format!("{z}.market.json"))).unwrap())
            .collect();
        let packs: Vec<_> = locals
            .split(',')
            .zip(&headers)
            .map(|(z, zone)| LocalPack {
                header: std::fs::read_to_string(dir.join(format!("{z}.json"))).unwrap(),
                cells: read(dir.join(format!("{z}.bin"))),
                country: serde_json::from_str::<Value>(zone).unwrap()["country"]
                    .as_str()
                    .unwrap_or("")
                    .to_owned(),
            })
            .collect();
        let index = read(std::env::var("WORLD_MARKET_INDEX").unwrap().into());
        let drives = read(dir.join("market-drives.bin"));
        let zones: Vec<_> = headers.iter().map(String::as_str).collect();
        let market = Some((index.as_slice(), drives.as_slice(), &zones[..]));
        let run = |by_purpose| {
            let mut cells = Vec::new();
            solve(
                &save,
                &packs,
                &[0, 1, 2, 3, 4],
                market,
                None,
                None,
                Some(TripRows::Into(&id, by_purpose, &mut cells)),
            )
            .unwrap();
            zone_rows(&cells, &id).unwrap()
        };
        let (all, parts) = (run(false), run(true));
        let stride = crate::trip_cells::PURPOSE_STRIDE;
        assert_eq!(all.len() % 10, 0);
        assert_eq!(parts.len() % stride, 0);
        assert_eq!(all.len() / 10, parts.len() / stride, "cells");
        let names = crate::trip_cells::PURPOSES.map(|p| p.0);
        let mut sums = vec![0f64; 1 + names.len()];
        for (a, p) in all.chunks_exact(10).zip(parts.chunks_exact(stride)) {
            for k in 0..10 {
                assert!(
                    (a[k] - p[k]).abs() <= 1e-6 * a[k].abs().max(1.),
                    "column {k}: {} vs {}",
                    a[k],
                    p[k]
                );
            }
            for k in 0..8 {
                let filtered: f64 = (0..names.len()).map(|j| p[10 + 8 * j + k]).sum();
                assert!(
                    filtered <= a[2 + k] * 1.0001 + 0.01,
                    "purposes {filtered} above every purpose's {}",
                    a[2 + k]
                );
            }
            for (j, s) in sums.iter_mut().enumerate() {
                *s += p[2 + 8 * j..10 + 8 * j].iter().sum::<f64>();
            }
        }
        let shares: Vec<_> = names
            .iter()
            .zip(&sums[1..])
            .map(|(n, s)| format!("{n} {:.1}%", 100. * s / sums[0]))
            .collect();
        eprintln!(
            "cells {}, legs {:.0}: {}",
            all.len() / 10,
            sums[0],
            shares.join(", ")
        );
        assert!(
            names
                .iter()
                .zip(&sums[1..])
                .all(|(n, s)| *s > 0. || *n == "airport"),
            "a purpose with no trips"
        );
    }
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
        if let Ok(dir) = std::env::var("WORLD_ACCOUNT_EXPORT") {
            let mut forecast = crate::passenger_account::Forecast::default();
            let zone_ids = market_zones
                .split(',')
                .map(str::to_owned)
                .collect::<Vec<_>>();
            let geo = crate::demand_zones::Geography::read(
                &std::path::PathBuf::from(std::env::var("WORLD_CITY_DATA").unwrap()),
                &zone_ids,
            )
            .unwrap();
            let mut cells = Vec::new();
            let output = std::env::var("WORLD_TRIP_EXPORT").ok();
            // with each filter purpose's counts after every purpose's (`trip_cells::purpose_rows`)
            let by_purpose = std::env::var("WORLD_TRIP_PURPOSES").is_ok();
            solve(
                &save,
                &packs,
                &[0, 1, 2, 3, 4],
                market,
                Some(&mut forecast),
                Some(&geo),
                output
                    .as_ref()
                    .map(|_| TripRows::Into(id.as_str(), by_purpose, &mut cells)),
            )
            .unwrap();
            if let Some(path) = output {
                let cells = zone_rows(&cells, &id).unwrap();
                std::fs::write(path, serde_json::to_vec(&cells).unwrap()).unwrap();
            }
            let dir = std::path::PathBuf::from(dir);
            std::fs::create_dir_all(&dir).unwrap();
            if let Ok(reference) = std::env::var("WORLD_REFERENCE_CHECKPOINT") {
                let reference = World::open(&std::fs::read(reference).unwrap()).unwrap();
                let mut world = World::open(&save).unwrap();
                for p in &packs {
                    super::super::city_surface::install_streets(
                        &mut world.api,
                        &serde_json::from_str(&p.header).unwrap(),
                        &p.cells,
                    );
                }
                let costs =
                    crate::operating::Costs::with_prices(&mut world.api, &forecast.run_prices());
                if let Ok(out) = std::env::var("WORLD_NETWORK_LAYERS") {
                    std::fs::write(
                        out,
                        serde_json::to_vec(&crate::network_layers::build(
                            &world.api,
                            &forecast.map_lines(),
                        ))
                        .unwrap(),
                    )
                    .unwrap();
                }
                let upkeep = geo.upkeep(&mut world.api);
                let mut account = crate::timed_account::Account::new(
                    &forecast,
                    &mut world.api,
                    world.header["clock"].as_f64().unwrap(),
                    &costs,
                    upkeep,
                );
                if let Ok(path) = std::env::var("WORLD_PREVIEW_REFERENCE") {
                    let reference: Value =
                        serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
                    let time = reference["clock"].as_f64().unwrap();
                    let rows = crate::train_loads::Loads::new(&mut world, time)
                        .unwrap()
                        .rows(&world, time);
                    std::fs::write(
                        dir.join("trainLoads.json"),
                        json!({"clock":time,"rows":rows}).to_string(),
                    )
                    .unwrap();
                }
                world.operating = Some(costs);
                crate::finance::prepare_session(&mut world);
                crate::finance::tick_with_account(
                    &mut world,
                    reference.header["clock"].as_f64().unwrap(),
                    Some(&mut account),
                );
                std::fs::write(dir.join("settled.json"), world.header.to_string()).unwrap();
            }
            for day in 0..6 {
                let (packed, arrive) = forecast.riders(day);
                std::fs::write(
                    dir.join(format!("riders{day}.json")),
                    json!({ "day":day, "packed":packed, "arrive":arrive }).to_string(),
                )
                .unwrap();
            }
            std::fs::write(
                dir.join("transfers.json"),
                json!({"packed":forecast.transfers()}).to_string(),
            )
            .unwrap();
            std::fs::write(
                dir.join("income.json"),
                serde_json::to_vec(&forecast.income()).unwrap(),
            )
            .unwrap();
        }
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

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_tripCells(
    mut env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    manifest: JString,
    city: JString,
) -> jni::sys::jfloatArray {
    let calculate = || -> Result<_, String> {
        let save = env.convert_byte_array(bytes).map_err(|e| e.to_string())?;
        let spec: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        let city: String = env.get_string(&city).map_err(|e| e.to_string())?.into();
        let files: DemandFiles = serde_json::from_str(&spec).map_err(|e| e.to_string())?;
        let packed = files.read()?.trip_cells(&save, &city)?;
        let size = i32::try_from(packed.len()).map_err(|e| e.to_string())?;
        let array = env.new_float_array(size).map_err(|e| e.to_string())?;
        env.set_float_array_region(&array, 0, &packed)
            .map_err(|e| e.to_string())?;
        Ok(array.into_raw())
    };
    match calculate() {
        Ok(array) => array,
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}

/// `LENS_STAGE`: what a running `tripCells` is doing, for the lens's progress line.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_tripCellsStage(
    _: JNIEnv,
    _: JClass,
) -> jint {
    LENS_STAGE.load(std::sync::atomic::Ordering::Relaxed)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_networkLayers(
    mut env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    manifest: JString,
) -> jstring {
    let calculate = || -> Result<String, String> {
        let save = env.convert_byte_array(bytes).map_err(|e| e.to_string())?;
        let spec: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        let files: DemandFiles = serde_json::from_str(&spec).map_err(|e| e.to_string())?;
        let mut world = World::open(&save).ok_or("Simpanan tidak dapat dibaca")?;
        let forecast = files.read()?.forecast(&save, &mut world)?;
        super::operating::Costs::with_prices(&mut world.api, &forecast.run_prices());
        let mut layers = crate::network_layers::build(&world.api, &forecast.map_lines());
        layers.project(&world.api);
        serde_json::to_string(&layers).map_err(|e| e.to_string())
    };
    match calculate() {
        Ok(rows) => env
            .new_string(rows)
            .map_or(std::ptr::null_mut(), |s| s.into_raw()),
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}
