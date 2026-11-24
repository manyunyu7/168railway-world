//! Read-only Android map overlays. Money is in millions per average game day.
use std::collections::{BTreeMap, HashMap};
use trainworld_sim::track::{params::*, profile, wasm::TrackApi};
pub(super) struct Traffic {
    pub board: Vec<f64>,
    pub incoming: Vec<f64>,
    pub rate_scales: Vec<f64>,
    pub segments: Vec<f64>,
    /// `segments` per map filter purpose (`trip_cells::PURPOSES`), empty when not worked out
    pub purposes: Vec<Vec<f64>>,
    pub fares: f64,
}
#[derive(Default, serde::Serialize)]
pub(super) struct Layers {
    stops: Vec<f64>,
    rail: Vec<f64>,
    edges: Vec<f64>,
    spans: Vec<f64>,
    /// per `spans` row, its riders a day under each map filter purpose (`trip_cells::PURPOSES`
    /// order): the Traffic widths under the purpose filter, bus and ferry lines too (web
    /// demandLayers.ts `stretchDay`); the money stays every purpose's
    purpose_spans: Vec<f64>,
    road: Vec<f64>,
}
type Pieces = Vec<Vec<(usize, f64)>>;
fn pieces(path: &[u32], len: &[f64], stops: &[f64]) -> Pieces {
    let mut at = 0.;
    let mut out = vec![vec![]; stops.len().saturating_sub(1)];
    for &p in path {
        let i = (p >> 1) as usize;
        let end = at + len[i];
        for (k, s) in stops.windows(2).enumerate() {
            let reach = end.min(s[1]) - at.max(s[0]);
            if reach > 1e-6 {
                out[k].push((i, reach));
            }
        }
        at = end;
    }
    out
}
fn spread(rows: &[(usize, f64)], value: f64, col: usize, edges: &mut [[f64; 2]]) {
    let sum = rows.iter().map(|r| r.1).sum::<f64>();
    if sum > 0. {
        for &(i, v) in rows {
            edges[i][col] += value * v / sum;
        }
    }
}
fn ratio(f: f64, c: f64) -> f64 {
    if c > 0. {
        f / c
    } else if f > 0. {
        1000.
    } else {
        -1.
    }
}
fn speed(api: &TrackApi, id: u32, run: usize, x: f64) -> f64 {
    let ph = &api.world().svc.lines[id as usize].runs[run].prof[1].phases;
    if ph.is_empty() {
        -1.
    } else {
        3.6 * profile::speed_at(ph, x)
    }
}
pub(super) fn build(api: &TrackApi, traffic: &BTreeMap<u32, Traffic>) -> Layers {
    let mut out = Layers::default();
    let net = &api.world().net;
    let frame = net.frame();
    let (eu, nu) = api.map_upkeep();
    let mut money: Vec<_> = eu.into_iter().map(|u| [0., u]).collect();
    let mut stopping = HashMap::<u32, Vec<(u32, usize, f64)>>::new();
    let mut node_counts = HashMap::<u32, [f64; 2]>::new();
    let mut placed = BTreeMap::new();
    let mut road_money = BTreeMap::new();
    let mut speed_paths = HashMap::<usize, Vec<(u32, f64, bool)>>::new();
    for row in api.lines_info().chunks_exact(22).filter(|r| r[3] == 1.) {
        let id = row[0] as u32;
        let Some(t) = traffic.get(&id) else { continue };
        let bus = api.is_bus(id);
        let ferry = api.is_ferry(id);
        let road = bus || ferry;
        let ss = api.line_stops(id);
        let back = api.line_back_stops(id);
        let n = ss.len();
        if n < 2 || t.board.len() != 2 * n {
            continue;
        }
        let d = api.line_stop_s(id, 0);
        let rev = api.line_stop_s(id, 1);
        if d.len() != n || rev.len() != n || d[n - 1] <= 0. {
            continue;
        }
        let wt = PERIOD_HOURS
            .iter()
            .enumerate()
            .map(|(p, (a, b))| (b - a) * row[5 + PERIOD_LEVEL[p]])
            .sum::<f64>();
        let units = api.money_params();
        let rate = if bus {
            units[12]
        } else if ferry {
            units[19]
        } else if api.line_fast(id) {
            units[6] + units[5] * row[21]
        } else if api.line_tram(id) {
            let c = &net.node_kind(ss[0]).spec().car;
            c.run_train_km + c.run_car_km * row[21]
        } else {
            units[3] + units[1] * row[21]
        };
        let running = wt * row[9] / 1000.
            * if api.line_one_way(id) { 1. } else { 2. }
            * api.line_run_factor(id)
            * rate
            * ECONOMY
            / 1e6;
        for (i, &s) in ss.iter().enumerate() {
            if !bus && wt > 0. {
                stopping.entry(s).or_default().push((id, i, wt));
            }
            for (k, node) in [(i, s), (2 * n - 1 - i, back.get(i).copied().unwrap_or(s))] {
                let v = node_counts.entry(node).or_default();
                v[0] += t.board[k];
                v[1] += t.incoming[k];
            }
        }
        let way = api.line_path(id);
        let back_way = api.line_back_path(id);
        let a = pieces(&way, &net.edge_len, &d);
        let b = pieces(&back_way, &net.edge_len, &rev);
        let mut off = 0.;
        for &p in &way {
            let i = (p >> 1) as usize;
            speed_paths
                .entry(i)
                .or_default()
                .push((id, off, p & 1 != 0));
            off += net.edge_len[i];
        }
        let mut pk = vec![[0.; 2]; n - 1];
        let mut sum = 0.;
        for k in 0..n - 1 {
            let j = n - 2 - k;
            pk[k] = [
                t.segments[k] * (d[k + 1] - d[k]) * t.rate_scales[k],
                t.segments[n + j] * (rev[j + 1] - rev[j]) * t.rate_scales[k + 1],
            ];
            sum += pk[k][0] + pk[k][1];
        }
        let mut road_rows = vec![[0.; 2]; n - 1];
        if !road {
            for (path, multiple) in [
                (&way, if back_way.is_empty() { 1. } else { 0.5 }),
                (&back_way, 0.5),
            ] {
                let length = path
                    .iter()
                    .map(|p| net.edge_len[(p >> 1) as usize])
                    .sum::<f64>();
                for p in path {
                    let i = (p >> 1) as usize;
                    if length > 0. {
                        money[i][1] += running * multiple * net.edge_len[i] / length;
                    }
                }
            }
        }
        for k in 0..n - 1 {
            let (x, y) = (d[k], d[k + 1]);
            let total = d[n - 1];
            let j = n - 2 - k;
            let rides = t.segments[k] + t.segments[n + j];
            let f = if sum > 0. { t.fares / sum } else { 0. };
            if road {
                let u = if bus {
                    net.bus_road_m(id) / 1000. * UPKEEP_BUS_KM * ECONOMY / 1e6
                } else {
                    0.
                };
                road_rows[k] = [
                    f * (pk[k][0] + pk[k][1]),
                    (running + u) * (y - x) / (total - d[0]),
                ];
                let parts = ((y - x) / 125.).ceil().max(1.) as usize;
                for part in 0..parts {
                    let frac = (part as f64 + 0.5) / parts as f64;
                    let v = speed(api, id, 0, x + frac * (y - x)).max(speed(
                        api,
                        id,
                        1,
                        rev[j] + (1. - frac) * (rev[j + 1] - rev[j]),
                    ));
                    out.road.extend([
                        id.into(),
                        (x + part as f64 / parts as f64 * (y - x)) / total,
                        (x + (part + 1) as f64 / parts as f64 * (y - x)) / total,
                        v,
                    ]);
                }
            } else if back_way.is_empty() {
                spread(&a[k], f * (pk[k][0] + pk[k][1]), 0, &mut money);
            } else {
                spread(&a[k], f * pk[k][0], 0, &mut money);
                spread(&b[j], f * pk[k][1], 0, &mut money);
            }
            out.spans
                .extend([id.into(), x / total, y / total, rides, -1.]);
            for p in 0..crate::trip_cells::PURPOSES.len() {
                let seg = t.purposes.get(p).filter(|v| v.len() == t.segments.len());
                out.purpose_spans.push(seg.map_or(0., |v| v[k] + v[n + j]));
            }
        }
        if road {
            road_money.insert(id, road_rows);
        } else {
            placed.insert(id, a);
        }
    }
    for (s, calls) in stopping {
        let total = calls.iter().map(|x| x.2).sum::<f64>();
        let u = nu.get(s as usize).copied().unwrap_or(0.);
        for (id, k, w) in calls {
            let n = placed
                .get(&id)
                .map(Vec::len)
                .or_else(|| road_money.get(&id).map(Vec::len))
                .unwrap_or(0);
            let sides = [k.checked_sub(1), (k < n).then_some(k)];
            let count = sides.iter().flatten().count();
            for j in sides.into_iter().flatten() {
                let amount = u * w / total / count as f64;
                if let Some(r) = road_money.get_mut(&id) {
                    r[j][1] += amount;
                } else if let Some(p) = placed.get(&id) {
                    spread(&p[j], amount, 1, &mut money);
                }
            }
        }
    }
    let mut cursor = HashMap::<u32, usize>::new();
    for row in out.spans.chunks_exact_mut(5) {
        let id = row[0] as u32;
        if let Some(rows) = road_money.get(&id) {
            let k = cursor.entry(id).or_default();
            row[4] = ratio(rows[*k][0], rows[*k][1]);
            *k += 1;
        }
    }
    let mut groups = BTreeMap::<u32, [f64; 5]>::new();
    for r in api
        .nodes_info()
        .chunks_exact(10)
        .filter(|r| r[7] > 0. && r[8] > 0.)
    {
        let s = r[0] as u32;
        let id = net.built_complex_of(s).into_iter().min().unwrap_or(s);
        let v = groups.entry(id).or_default();
        v[0] += r[1];
        v[1] += r[2];
        v[2] += 1.;
        if let Some(c) = node_counts.get(&s) {
            v[3] += c[0];
            v[4] += c[0].min(c[1]);
        }
    }
    for [x, y, n, b, t] in groups.into_values() {
        if b > 0. {
            let (ll, lat) = frame.to_lonlat(x / n, y / n);
            out.stops.extend([ll, lat, b, t]);
        }
    }
    let mut adj = vec![Vec::new(); net.node_count()];
    for e in 0..net.edge_count() {
        if net.edge_ok(e as u32) && net.edge_built[e] {
            for node in [net.edge_a[e], net.edge_b[e]] {
                adj[node as usize].push(e);
            }
        }
    }
    let mut seen = vec![false; money.len()];
    let mut ratios = BTreeMap::new();
    for e in 0..net.edge_count() {
        if !net.edge_ok(e as u32) || !net.edge_built[e] {
            continue;
        }
        if !seen[e] {
            let mut chain = vec![e];
            seen[e] = true;
            for mut node in [net.edge_a[e], net.edge_b[e]] {
                let mut edge = e;
                while net.node_platform[node as usize] == 0 && adj[node as usize].len() == 2 {
                    let Some(&next) = adj[node as usize].iter().find(|&&a| a != edge && !seen[a])
                    else {
                        break;
                    };
                    chain.push(next);
                    seen[next] = true;
                    edge = next;
                    node = if net.edge_a[next] == node {
                        net.edge_b[next]
                    } else {
                        net.edge_a[next]
                    };
                }
            }
            let f = chain.iter().map(|&i| money[i][0]).sum();
            let c = chain.iter().map(|&i| money[i][1]).sum();
            let r = ratio(f, c);
            if r >= 0. {
                for i in chain {
                    ratios.insert(i, r);
                }
            }
        }
        let pts = api.edge_render(e as u32);
        let speeds = api.edge_render_speeds(e as u32);
        // The web renderer stores strokes as float32 Mercator metres relative to HOME_ANCHOR.
        let circumference = 40_075_016.686;
        let merc_y = |lat: f64| 0.5 - lat.to_radians().tan().asinh() / (2. * std::f64::consts::PI);
        let anchor = merc_y(40.73);
        let unproject = |p: [f64; 3]| {
            (
                p[0] / circumference * 360. - 73.98,
                ((0.5 - anchor - p[1] / circumference) * 2. * std::f64::consts::PI)
                    .sinh()
                    .atan()
                    .to_degrees(),
            )
        };
        let points = pts
            .chunks_exact(3)
            .map(|p| {
                let (lon, lat) = frame.to_lonlat(p[0], p[1]);
                [
                    f64::from(((lon + 73.98) / 360. * circumference) as f32),
                    f64::from(((merc_y(lat) - anchor) * circumference) as f32),
                    p[2],
                ]
            })
            .collect::<Vec<_>>();
        if points.len() < 2 {
            continue;
        }
        let mut keep = vec![0usize];
        for j in 1..points.len() {
            let i = *keep.last().unwrap();
            let near = (points[j][0] - points[i][0]).hypot(points[j][1] - points[i][1]) < 8.;
            if near && j + 1 < points.len() {
                continue;
            }
            if near && keep.len() > 1 {
                keep.pop();
            }
            keep.push(j);
        }
        let length =
            |i: usize, j: usize| (points[j][0] - points[i][0]).hypot(points[j][1] - points[i][1]);
        let total = keep.windows(2).map(|ij| length(ij[0], ij[1])).sum::<f64>();
        let mut off = 0.;
        for ij in keep.windows(2) {
            let (i, j) = (ij[0], ij[1]);
            let len = length(i, j);
            let f = if total > 0. {
                (off + len / 2.) / total
            } else {
                0.5
            };
            off += len;
            let mut v = -1f64;
            for &(id, start, reverse) in speed_paths.get(&e).into_iter().flatten() {
                let s = start + if reverse { 1. - f } else { f } * net.edge_len[e];
                let end = api.world().svc.lines[id as usize].runs[1].len;
                v = v.max(speed(api, id, 0, s)).max(speed(api, id, 1, end - s));
            }
            let (a, b) = unproject(points[i]);
            let (c, d) = unproject(points[j]);
            let h = |z: f64| ((z / 8. + 4.) * 32.).round().clamp(0., 255.);
            let level = (h(points[i][2]) + h(points[j][2])) / 64. - 4.;
            out.rail.extend([
                a,
                b,
                c,
                d,
                level,
                if v >= 0. {
                    v
                } else {
                    speeds.get(i).copied().unwrap_or(160.).into()
                },
            ]);
        }
    }
    for (i, v) in ratios {
        out.edges.extend([i as f64, v]);
    }
    out
}

impl Layers {
    pub(super) fn project(&mut self, api: &TrackApi) {
        for (rows, stride, pairs) in [(&mut self.stops, 4, 1), (&mut self.rail, 6, 2)] {
            for r in rows.chunks_exact_mut(stride) {
                for p in r[..2 * pairs].chunks_exact_mut(2) {
                    let xy = api.from_lonlat(p[0], p[1]);
                    p.copy_from_slice(&xy);
                }
            }
        }
    }
}
