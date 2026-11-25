//! The forecast's solve as it was before its periods ran on threads of their own (each period
//! after the other, its commuters and everyday outings on two threads): the reference the
//! parallel `solve` is held to, bit for bit.
#![allow(clippy::all)]
use super::*;

// Each population sees the same frozen load. The summation between barriers stays ordered.
fn advance_v0(
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

pub(super) fn solve_v0(
    save: &[u8],
    packs: &[LocalPack],
    periods: &[usize],
    market_input: Option<MarketInput>,
    mut account: Option<&mut crate::passenger_account::Forecast>,
    geo: Option<&crate::demand_zones::Geography>,
    // the zone whose trip rows to write, and whether with each filter purpose's (`trip_cells`)
    cells: Option<TripRows>,
) -> Result<Vec<Value>, String> {
    let world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
    let first = packs.first().ok_or("Paket kota tidak tersedia")?;
    let fares = crate::fares::Fares::read(&world.header["fares"], &first.country);
    let mut api = world.api;
    let mut frames = Vec::new();
    for pack in packs {
        let city: Value = serde_json::from_str(&pack.header).map_err(|e| e.to_string())?;
        crate::city_surface::install_streets(&mut api, &city, &pack.cells);
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
    // Android's paid parking and adverts (`extras`): the solve's stations in order
    let mut nodes = vec![0u32; ix.len()];
    for (&id, &i) in &ix {
        nodes[i as usize] = id;
    }
    let line_ids: Vec<u32> = lines.iter().map(|l| l.0).collect();
    let empty = std::collections::BTreeMap::new();
    let before = account.as_deref().map_or(&empty, |a| a.parked_before());
    let terms = crate::extras::demand_terms(&world.header, &api, &nodes, &line_ids, before)
        .unwrap_or_default();
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
        let local = &crate::fares::Fares::read(&world.header["fares"], &pack.country).local;
        let wallet = scalar(local, "weight");
        solver.set_fare_weight(wallet)?;
        outings.set_fare_weight(wallet)?;
        solver.set_chunked(true);
        solver.set_complexes(&complexes);
        outings.set_complexes(&complexes);
        solver.set_upgrades(&upgrades);
        outings.set_upgrades(&upgrades);
        solver.set_terms(&terms.0, &terms.1, &terms.2);
        outings.set_terms(&terms.0, &terms.1, &terms.2);
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
        let mut m = crate::regional::open(index, drives, zones)?;
        let regional = &fares.market;
        m.set_fare_weight(scalar(regional, "weight"))?;
        m.set_fare_factors(&regional["factors"].to_string())?;
        m.set_complexes(&complexes);
        m.set_upgrades(&upgrades);
        m.set_terms(&terms.0, &terms.1, &terms.2);
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
        if !advance_v0(&mut cities, market.as_mut(), period, 0, &[]) {
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
                if !advance_v0(&mut cities, market.as_mut(), period, 4, &frozen) {
                    return Err("Susunan kapasitas akhir berbeda".into());
                }
                break;
            }
            if !advance_v0(&mut cities, market.as_mut(), period, round + 1, &frozen) {
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
            // cars parked: the home-end commuters riding to a car park, both ways, halved
            for (solver, _) in &cities {
                for (i, v) in solver.parked_riders(period, trainworld_sim::demand::days::AVERAGE).into_iter().enumerate() {
                    if let Some(&node) = nodes.get(i) {
                        account.add_parked(node, f64::from(v) / 2.);
                    }
                }
            }
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Old and new solve of the same inputs: their period figures, forecast and trip rows, to the bit.
    pub(in crate::demand) fn same_as_before(
        save: &[u8],
        packs: &[LocalPack],
        market: Option<MarketInput>,
        geo: Option<&crate::demand_zones::Geography>,
    ) {
        let periods = [0, 1, 2, 3, 4];
        let (mut f0, mut f1) = (crate::passenger_account::Forecast::default(), crate::passenger_account::Forecast::default());
        let (mut r0, mut r1) = (ZoneRows::new(), ZoneRows::new());
        let t = std::time::Instant::now();
        let a = solve_v0(save, packs, &periods, market, Some(&mut f0), geo, Some(TripRows::Into(ALL_ZONES, true, &mut r0))).unwrap();
        let before = t.elapsed();
        let t = std::time::Instant::now();
        let b = solve(save, packs, &periods, market, Some(&mut f1), geo, Some(TripRows::Into(ALL_ZONES, true, &mut r1))).unwrap();
        eprintln!("solve before {before:?}, now {:?}", t.elapsed());
        assert_eq!(a, b, "period figures");
        let (d0, d1) = (format!("{f0:?}"), format!("{f1:?}"));
        if let Some(at) = d0.bytes().zip(d1.bytes()).position(|(x, y)| x != y) {
            let lo = at.saturating_sub(300);
            panic!("forecast differs at {at}:\n{}\n---\n{}", &d0[lo..(at + 200).min(d0.len())], &d1[lo..(at + 200).min(d1.len())]);
        }
        assert_eq!(d0, d1, "forecast");
        assert_eq!(r0.len(), r1.len());
        for ((z0, x), (z1, y)) in r0.iter().zip(&r1) {
            assert_eq!(z0, z1);
            assert_eq!(x.len(), y.len(), "{z0}");
            assert!(x.iter().zip(y).all(|(p, q)| p.to_bits() == q.to_bits()), "trip rows of {z0}");
        }
        // and without the forecast or rows (`estimate`'s one period, a lens's own solve)
        for one in [&[2usize][..], &periods[..]] {
            let a = solve_v0(save, packs, one, market, None, None, None).unwrap();
            let b = solve(save, packs, one, market, None, None, None).unwrap();
            assert_eq!(a, b, "periods {one:?}");
        }
    }

    /// Two synthetic cities on the workshop's running service, every thread count from one.
    #[test]
    fn parallel_periods_solve_as_before() {
        let save = super::super::tests::served_save();
        let pack = |id: &str, lon: f64, lat: f64| {
            let (header, cells) = super::super::tests::zone_pack(id, lon, lat);
            LocalPack { header, cells, country: String::new() }
        };
        let packs = [pack("nyx", -73.985, 40.758), pack("nyy", -73.9, 40.8)];
        for threads in ["1", "3", "8"] {
            std::env::set_var("WORLD_SOLVE_THREADS", threads);
            same_as_before(&save, &packs, None, None);
        }
        std::env::remove_var("WORLD_SOLVE_THREADS");
    }

    /// The bundled scenarios with their city packs and the market, as the clock opens them
    /// (`open_bench::manifest`; WORLD_CITY_DATA, WORLD_MARKET_INDEX, WORLD_SCENARIOS).
    #[test]
    #[ignore = "requires the city packs (WORLD_CITY_DATA, WORLD_MARKET_INDEX)"]
    fn parallel_periods_solve_scenarios_as_before() {
        let dir = std::env::var("WORLD_CITY_DATA").unwrap();
        let names = std::env::var("WORLD_SCENARIOS").unwrap_or_else(|_| "jak,jak_mulai".into());
        for name in names.split(',') {
            let save = super::super::open_bench::scenario(name);
            let inputs = super::super::open_bench::manifest(&save, &dir, "jak").read().unwrap();
            let zones: Vec<_> = inputs.markets.iter().map(String::as_str).collect();
            let market = Some((inputs.index.as_slice(), inputs.drives.as_slice(), &zones[..]));
            eprintln!("{name}:");
            same_as_before(&save, &inputs.packs, market, Some(&inputs.geo));
        }
    }
}

#[cfg(test)]
mod progress {
    use super::*;
    /// The clock's open reports its rounds as they finish, from the setup's share to all but the
    /// account's, then 1; a solve outside an open (a lens, an estimate) leaves the figure alone.
    #[test]
    fn open_progress_counts_the_rounds() {
        let save = super::super::tests::served_save();
        let (header, cells) = super::super::tests::zone_pack("nyx", -73.985, 40.758);
        let packs = [LocalPack { header, cells, country: String::new() }];
        let seen = std::sync::Mutex::new(Vec::<f32>::new());
        let done = std::sync::atomic::AtomicBool::new(false);
        std::thread::scope(|scope| {
            scope.spawn(|| {
                while !done.load(std::sync::atomic::Ordering::Relaxed) {
                    seen.lock().unwrap().push(open_progress());
                    std::thread::yield_now();
                }
            });
            reporting_open(|| {
                let mut f = crate::passenger_account::Forecast::default();
                solve(&save, &packs, &[0, 1, 2, 3, 4], None, Some(&mut f), None, None).unwrap();
            });
            done.store(true, std::sync::atomic::Ordering::Relaxed);
        });
        assert_eq!(open_progress(), 1.);
        let seen = seen.into_inner().unwrap();
        assert!(seen.windows(2).all(|w| w[0] <= w[1]), "never goes back");
        assert!(seen.iter().any(|&f| f > SETUP_SHARE && f < 1. - ACCOUNT_SHARE), "rounds in between: {seen:?}");
        // outside an open
        set_open_progress(0.25);
        solve(&save, &packs, &[0], None, None, None, None).unwrap();
        assert_eq!(open_progress(), 0.25);
        // the rounds' share
        let r = Rounds { done: 0.into(), total: 25, report: true };
        for _ in 0..25 {
            r.tick();
        }
        assert!((open_progress() - (1. - ACCOUNT_SHARE)).abs() < 1e-6);
    }
}
