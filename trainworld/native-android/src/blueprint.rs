// SPDX-License-Identifier: AGPL-3.0-only
//! The Build tab's blueprint panel (web `ui/Blueprint.tsx`, `game/blueprintStretches.ts`,
//! `game/blueprintPick.ts`): the plan by part joined by track, every row's switch (the pieces' own
//! `off`, left out of the quote and of Construct), the quote by kind, and the edits that act on
//! what is switched on: level, track type, track count, removal and Construct.
use super::construction::{finish, response};
use super::World;
use jni::{
    objects::{JClass, JIntArray},
    sys::{jboolean, jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

/// a stretch end this close to a station (pack metres) is named by it (web `NEAR_STATION_M`)
const NEAR_STATION_M: f64 = 250.;
/// how far apart quad track's twin stations may be (web `TWIN_M`)
const TWIN_M: f64 = 80.;

pub(crate) const OP_SWITCH: i32 = 0;
pub(crate) const OP_LEVEL: i32 = 1;
pub(crate) const OP_KIND: i32 = 2;
pub(crate) const OP_TRACKS: i32 = 3;
pub(crate) const OP_REMOVE: i32 = 4;
pub(crate) const OP_CONSTRUCT: i32 = 5;

#[derive(Clone)]
struct Edge {
    id: u32,
    a: u32,
    b: u32,
    tracks: u8,
    len: f64,
    lo: i8,
    hi: i8,
    off: bool,
    crossover: bool,
    kind: u8,
}

#[derive(Clone)]
struct Row {
    station: bool,
    label: String,
    edges: Vec<u32>,
    stations: Vec<u32>,
    nodes: Vec<u32>,
    length: f64,
    cost: f64,
    on_cost: f64,
    on: bool,
    off: bool,
    lo: i8,
    hi: i8,
    crossings: u32,
    ends: Vec<u32>,
}

struct Part {
    label: String,
    rows: Vec<Row>,
    crossovers: Vec<u32>,
    station_count: usize,
    junctions: u32,
    junction_cost: f64,
    junction_on: f64,
}

pub(crate) fn level_name(l: i64) -> &'static str {
    match l {
        3 => "Viaduk tinggi",
        1 | 2 => "Viaduk",
        0 => "Permukaan tanah",
        -1 => "Galian terbuka",
        -2 => "Terowongan",
        _ => "Terowongan dalam",
    }
}
pub(crate) fn level_label(l: i64) -> String {
    match l {
        l if l > 0 => format!("+{l}"),
        l if l < 0 => format!("−{}", -l),
        _ => "0".into(),
    }
}
fn level_words(l: i64) -> String {
    format!("{} ({})", level_name(l).to_lowercase(), level_label(l))
}
/// "3,7 km", "850 m"
fn distance(m: f64) -> String {
    if m < 1000. {
        format!("{} m", m.round() as i64)
    } else {
        format!("{:.1} km", m / 1000.).replace('.', ",")
    }
}
fn cap(s: &str) -> String {
    let mut c = s.chars();
    c.next()
        .map(|f| f.to_uppercase().collect::<String>() + c.as_str())
        .unwrap_or_default()
}

/// The quote's kinds (web `KINDS` and `blueprint.kindTramCars`).
fn kind_title(kind: i64) -> &'static str {
    match kind {
        0 => "Rel",
        1 => "Stasiun",
        2 => "Persimpangan",
        3 => "Flyover",
        4 => "Persilangan sebidang",
        5 => "Kereta",
        6 => "Medan",
        7 => "Perlintasan jalan",
        8 => "Peningkatan stasiun",
        9 => "Lorong",
        10 => "Rute bus",
        11 => "Bus",
        12 => "Dermaga",
        13 => "Kapal",
        14 => "Rute feri",
        15 => "Gerbong trem",
        _ => "Lainnya",
    }
}

/// A quote row as the list reads it (web `rowName`).
fn row_name(kind: i64, level: i64, tracks: i64, quantity: f64) -> String {
    let n = quantity.round() as i64;
    let lw = level_words(level);
    match kind {
        5 => format!("{n} gerbong baru"),
        10 => distance(quantity),
        11 => format!("{n} bus baru"),
        12 => format!("{n} dermaga"),
        13 => format!("{n} kapal baru"),
        14 => format!("Rute feri {}", distance(quantity)),
        15 => format!("{n} gerbong trem baru"),
        9 => format!("{n} lorong"),
        7 => format!("{n} perlintasan jalan"),
        0 if tracks == 4 || tracks == 7 => format!("{} rel trem, {lw}", distance(quantity)),
        0 if tracks == 5 || tracks == 8 => {
            format!("{} rel trem di jalan, {lw}", distance(quantity))
        }
        0 => format!("{} {lw}", distance(quantity)),
        _ => {
            let noun = match kind {
                1 => "stasiun",
                2 => "persimpangan",
                3 => "flyover",
                4 => "persilangan sebidang",
                6 => "tanah",
                8 => "peningkatan stasiun",
                _ => "item",
            };
            format!("{n} {noun}, {lw}")
        }
    }
}

/// the quote's kinds that are no piece of track or station (web `NOT_TRACK`)
fn not_track(kind: i64) -> bool {
    matches!(kind, 5 | 10..=15)
}

impl World {
    /// Unbuilt, live track pieces and blueprint stations, as they stand.
    fn blueprint_edges(&self) -> Vec<Edge> {
        let net = &self.api.world().net;
        self.api
            .edges_info()
            .chunks_exact(10)
            .map(|r| {
                let id = r[0] as u32;
                Edge {
                    id,
                    a: r[1] as u32,
                    b: r[2] as u32,
                    tracks: r[3] as u8,
                    len: r[5],
                    lo: r[8] as i8,
                    hi: r[9] as i8,
                    off: net.edge_off[id as usize],
                    crossover: net.edge_crossover[id as usize],
                    kind: self.api.edge_kind(id),
                }
            })
            .collect()
    }

    /// The pieces switched on: unbuilt edges and stations not off (what Construct takes).
    fn blueprint_on(&self) -> (Vec<u32>, Vec<u32>) {
        let n = &self.api.world().net;
        let edges = (0..n.edge_count() as u32)
            .filter(|&e| n.edge_ok(e) && !n.edge_built[e as usize] && !n.edge_off[e as usize])
            .collect();
        let stations = (0..n.node_count() as u32)
            .filter(|&s| {
                n.node_ok(s) && n.is_station(s) && !n.node_built[s as usize] && !n.node_off[s as usize]
            })
            .collect();
        (edges, stations)
    }

    /// Web `blueprintParts`: the blueprint by part joined by blueprint track, each cut into
    /// stretches at stations, junctions and track ends, priced from the track model's pieces.
    fn blueprint_parts(&self) -> Vec<Part> {
        let net = &self.api.world().net;
        let all: Vec<Edge> = self.blueprint_edges();
        let all_built: HashMap<u32, bool> = all
            .iter()
            .map(|e| (e.id, net.edge_built[e.id as usize]))
            .collect();
        let by_id: HashMap<u32, &Edge> = all.iter().map(|e| (e.id, e)).collect();
        let pieces = self.api.blueprint_piece_costs();
        let (mut edge_cost, mut station_cost, mut junction_cost) =
            (HashMap::new(), HashMap::new(), HashMap::new());
        let (mut crossings, mut passages) = (vec![], vec![]);
        for p in pieces.chunks_exact(5) {
            let (a, b, c, on) = (p[1] as u32, p[2] as u32, p[3], p[4] == 1.);
            match p[0] as u8 {
                0 => {
                    edge_cost.insert(a, (c, on));
                }
                1 => {
                    station_cost.insert(a, (c, on));
                }
                2 => {
                    junction_cost.insert(a, (c, on));
                }
                3 => crossings.push((a, b, c, on)),
                4 => passages.push((a, b, c, on)),
                _ => {}
            }
        }
        let is_station = |n: u32| net.is_station(n) && !net.is_port(n);
        let name_of = |n: u32| {
            let s = self.api.node_name(n);
            if s.is_empty() {
                "Stasiun".to_string()
            } else {
                s
            }
        };
        let bp: Vec<&Edge> = all.iter().filter(|e| !all_built[&e.id]).collect();
        let mut all_at: HashMap<u32, Vec<u32>> = HashMap::new();
        let mut bp_at: HashMap<u32, Vec<u32>> = HashMap::new();
        for e in all.iter().filter(|e| !e.crossover) {
            all_at.entry(e.a).or_default().push(e.id);
            all_at.entry(e.b).or_default().push(e.id);
            if !all_built[&e.id] {
                bp_at.entry(e.a).or_default().push(e.id);
                bp_at.entry(e.b).or_default().push(e.id);
            }
        }
        let count = |m: &HashMap<u32, Vec<u32>>, n: u32| m.get(&n).map_or(0, |v| v.len());
        // parts: nodes joined by blueprint track, crossovers included
        let mut up: HashMap<u32, u32> = HashMap::new();
        fn find(up: &mut HashMap<u32, u32>, n: u32) -> u32 {
            let mut r = n;
            while let Some(&p) = up.get(&r) {
                if p == r {
                    break;
                }
                r = p;
            }
            up.insert(n, r);
            r
        }
        for e in &bp {
            let (ra, rb) = (find(&mut up, e.a), find(&mut up, e.b));
            if ra != rb {
                up.insert(ra, rb);
            }
        }
        let mut roots: Vec<u32> = vec![];
        let mut by_root: HashMap<u32, Vec<&Edge>> = HashMap::new();
        for e in &bp {
            let r = find(&mut up, e.a);
            if !by_root.contains_key(&r) {
                roots.push(r);
            }
            by_root.entry(r).or_default().push(e);
        }
        let splits = |n: u32| is_station(n) || count(&bp_at, n) != 2 || count(&all_at, n) >= 3;
        let end_name = |n: u32| -> (String, bool) {
            if is_station(n) {
                return (name_of(n), true);
            }
            let (x, y) = (net.node_x[n as usize], net.node_y[n as usize]);
            let mut best = None;
            let mut bd = NEAR_STATION_M;
            for s in 0..net.node_count() as u32 {
                if !is_station(s) {
                    continue;
                }
                let d = (net.node_x[s as usize] - x).hypot(net.node_y[s as usize] - y);
                let nm = self.api.node_name(s);
                if d < bd && !nm.is_empty() {
                    bd = d;
                    best = Some(nm);
                }
            }
            if let Some(b) = best {
                return (b, true);
            }
            let k = count(&all_at, n);
            let word = if k >= 3 {
                "persimpangan"
            } else if k <= 1 {
                "ujung rel"
            } else {
                "rel yang sudah dibangun"
            };
            (word.into(), false)
        };
        let stretch_label = |a: u32, b: u32| {
            let (mut x, mut y) = (end_name(a), end_name(b));
            if !x.1 && y.1 {
                std::mem::swap(&mut x, &mut y);
            }
            format!("{} ke {}", if x.1 { x.0 } else { cap(&x.0) }, y.0)
        };
        let station_row = |n: u32| -> Row {
            let (c, on) = station_cost.get(&n).copied().unwrap_or((0., false));
            let off = net.node_off[n as usize];
            let level = net.node_level[n as usize];
            Row {
                station: true,
                label: name_of(n),
                edges: all_at.get(&n).cloned().unwrap_or_default(),
                stations: vec![n],
                nodes: vec![n],
                length: 0.,
                cost: c,
                on_cost: if on { c } else { 0. },
                on: !off,
                off,
                lo: level,
                hi: level,
                crossings: 0,
                ends: vec![n],
            }
        };
        let stretch_row = |ids: Vec<u32>, a: u32, b: u32| -> Row {
            let mut r = Row {
                station: false,
                label: stretch_label(a, b),
                edges: vec![],
                stations: vec![],
                nodes: vec![],
                length: 0.,
                cost: 0.,
                on_cost: 0.,
                on: false,
                off: false,
                lo: 3,
                hi: -3,
                crossings: 0,
                ends: if is_station(a) && is_station(b) {
                    vec![a, b]
                } else {
                    vec![]
                },
            };
            let mut nodes = HashSet::new();
            for id in &ids {
                let e = by_id[id];
                let (c, on) = edge_cost.get(id).copied().unwrap_or((0., false));
                nodes.insert(e.a);
                nodes.insert(e.b);
                r.length += e.len;
                r.cost += c;
                if on {
                    r.on_cost += c;
                }
                if e.off {
                    r.off = true;
                } else {
                    r.on = true;
                }
                r.lo = r.lo.min(e.lo);
                r.hi = r.hi.max(e.hi);
            }
            r.edges = ids;
            r.nodes = nodes.into_iter().collect();
            r.nodes.sort_unstable();
            r
        };
        let mut used: HashSet<u32> = HashSet::new();
        let mut parts = vec![];
        for root in roots {
            let edges = &by_root[&root];
            let mut seen: HashSet<u32> = HashSet::new();
            let mut rows = vec![];
            let mut named: Vec<u32> = vec![];
            let (mut junctions, mut j_cost, mut j_on) = (0, 0., 0.);
            let mut nodes_of: Vec<u32> = edges.iter().flat_map(|e| [e.a, e.b]).collect();
            nodes_of.sort_unstable();
            nodes_of.dedup();
            for e in edges.iter().filter(|e| e.crossover) {
                let (c, on) = edge_cost.get(&e.id).copied().unwrap_or((0., false));
                seen.insert(e.id);
                junctions += 1;
                j_cost += c;
                if on {
                    j_on += c;
                }
            }
            for n in &nodes_of {
                if let Some(&(c, on)) = junction_cost.get(n) {
                    junctions += 1;
                    j_cost += c;
                    if on {
                        j_on += c;
                    }
                }
            }
            // walk from a node: its station row, then each stretch out of it to the next split
            let mut stack = vec![];
            let ends: Vec<u32> = nodes_of
                .iter()
                .copied()
                .filter(|&n| count(&bp_at, n) == 1)
                .collect();
            let start = ends
                .iter()
                .copied()
                .find(|&n| is_station(n))
                .or(ends.first().copied())
                .or(nodes_of.iter().copied().find(|&n| splits(n)))
                .unwrap_or(edges[0].a);
            let mut starts = vec![start];
            starts.extend(edges.iter().map(|e| e.a));
            for s in starts {
                if edges.iter().all(|e| seen.contains(&e.id)) {
                    break;
                }
                stack.push(s);
                while let Some(n) = stack.pop() {
                    if is_station(n) && !named.contains(&n) {
                        named.push(n);
                        if !net.node_built[n as usize] && station_cost.contains_key(&n) {
                            rows.push(station_row(n));
                            used.insert(n);
                        }
                    }
                    let mut next = vec![];
                    for &e0 in bp_at.get(&n).map_or(&[][..], |v| &v[..]) {
                        if seen.contains(&e0) {
                            continue;
                        }
                        let mut ids = vec![];
                        let (mut at, mut e) = (n, Some(e0));
                        while let Some(id) = e.filter(|id| !seen.contains(id)) {
                            seen.insert(id);
                            ids.push(id);
                            let edge = by_id[&id];
                            at = if edge.a == at { edge.b } else { edge.a };
                            if splits(at) {
                                break;
                            }
                            e = bp_at[&at].iter().copied().find(|x| !seen.contains(x));
                        }
                        rows.push(stretch_row(ids, n, at));
                        next.push(at);
                    }
                    // depth first in track order, as the web's recursive walk
                    stack.extend(next.into_iter().rev());
                }
            }
            let crossovers = edges
                .iter()
                .filter(|e| e.crossover)
                .map(|e| e.id)
                .collect();
            parts.push(self.part_of(rows, &named, junctions, j_cost, j_on, crossovers));
        }
        // blueprint stations on built track only
        let mut lone: Vec<u32> = station_cost.keys().copied().collect();
        lone.sort_unstable();
        for n in lone {
            if used.contains(&n)
                || !is_station(n)
                || net.node_built[n as usize]
                || count(&bp_at, n) > 0
            {
                continue;
            }
            parts.push(self.part_of(vec![station_row(n)], &[n], 0, 0., 0., vec![]));
        }
        // flat crossings go with a blueprint stretch they are on, passages with a blueprint station
        let mut add = |hit: &dyn Fn(&Row) -> bool, c: f64, on: bool, crossing: bool| {
            for p in parts.iter_mut() {
                if let Some(r) = p.rows.iter_mut().find(|r| hit(r)) {
                    r.cost += c;
                    if on {
                        r.on_cost += c;
                    }
                    if crossing {
                        r.crossings += 1;
                    }
                    return;
                }
            }
        };
        for (e1, e2, c, on) in crossings {
            add(&|r: &Row| !r.station && r.edges.contains(&e1), c, on, true);
            let _ = e2;
        }
        for (a, b, c, on) in passages {
            add(
                &|r: &Row| r.station && (r.stations[0] == a || r.stations[0] == b),
                c,
                on,
                false,
            );
        }
        for p in parts.iter_mut() {
            merge_twins(p, |a, b| {
                let (i, j) = (a as usize, b as usize);
                (net.node_x[i] - net.node_x[j]).hypot(net.node_y[i] - net.node_y[j]) < TWIN_M
                    && self.api.node_name(a) == self.api.node_name(b)
            });
        }
        parts.sort_by(|a, b| part_cost(b).total_cmp(&part_cost(a)));
        parts
    }

    fn part_of(
        &self,
        rows: Vec<Row>,
        named: &[u32],
        junctions: u32,
        junction_cost: f64,
        junction_on: f64,
        crossovers: Vec<u32>,
    ) -> Part {
        let name = |n: u32| {
            let s = self.api.node_name(n);
            if s.is_empty() {
                "Stasiun".to_string()
            } else {
                s
            }
        };
        let label = match (named.first(), named.last()) {
            (Some(&f), Some(&l)) if name(f) == name(l) => name(f),
            (Some(&f), Some(&l)) => format!("{} ke {}", name(f), name(l)),
            _ => "Rel".into(),
        };
        let station_count = named.iter().map(|&n| name(n)).collect::<HashSet<_>>().len();
        Part {
            label,
            rows,
            crossovers,
            station_count,
            junctions,
            junction_cost,
            junction_on,
        }
    }

    /// Everything the panel shows, one answer: the parts and their rows, the quote by kind, the
    /// total, what is switched off, and what the edits would act on (the pieces switched on).
    pub(crate) fn blueprint_overview(&mut self) -> Value {
        let items = self.api.blueprint_cost_items();
        let blueprint_cost = self.api.blueprint_cost();
        let parts = self.blueprint_parts();
        let net = &self.api.world().net;
        let centre = |nodes: &[u32]| {
            let k = nodes.len().max(1) as f64;
            let x: f64 = nodes.iter().map(|&n| net.node_x[n as usize]).sum::<f64>() / k;
            let y: f64 = nodes.iter().map(|&n| net.node_y[n as usize]).sum::<f64>() / k;
            [x, y]
        };
        let row_json = |r: &Row| {
            json!({"kind": if r.station {"station"} else {"stretch"}, "label": r.label,
                "edges": r.edges, "stations": r.stations, "length": r.length,
                "cost": r.cost, "onCost": r.on_cost, "on": r.on, "off": r.off,
                "levelMin": r.lo, "levelMax": r.hi, "crossings": r.crossings, "at": centre(&r.nodes)})
        };
        let parts_json: Vec<Value> = parts
            .iter()
            .map(|p| {
                let mut edges: Vec<u32> = p.rows.iter().filter(|r| !r.station).flat_map(|r| r.edges.clone()).collect();
                edges.extend(&p.crossovers);
                let stations: Vec<u32> = p.rows.iter().flat_map(|r| r.stations.clone()).collect();
                let nodes: Vec<u32> = p.rows.iter().flat_map(|r| r.nodes.clone()).collect();
                let on = p.rows.iter().any(|r| r.on);
                let off = p.rows.iter().any(|r| r.off);
                json!({"label": p.label, "edges": edges, "stations": stations,
                    "length": p.rows.iter().map(|r| r.length).sum::<f64>(),
                    "cost": part_cost(p), "onCost": p.rows.iter().map(|r| r.on_cost).sum::<f64>() + p.junction_on,
                    "on": on, "off": off, "stationCount": p.station_count,
                    "junctions": p.junctions, "junctionCost": p.junction_cost, "junctionOnCost": p.junction_on,
                    "at": centre(&nodes), "rows": p.rows.iter().map(row_json).collect::<Vec<_>>()})
            })
            .collect();
        let mut total = 0.;
        let mut on_infra = 0.;
        let quote: Vec<Value> = items
            .chunks_exact(7)
            .map(|r| {
                let kind = r[0] as i64;
                total += r[6];
                if !matches!(kind, 5 | 11 | 13 | 15) {
                    on_infra += r[6];
                }
                json!({"kind": kind, "title": kind_title(kind),
                    "label": row_name(kind, r[1] as i64, r[2] as i64, r[5]),
                    "cost": r[6], "track": !not_track(kind)})
            })
            .collect();
        let off_edges = (0..net.edge_count() as u32)
            .filter(|&e| net.edge_ok(e) && !net.edge_built[e as usize] && net.edge_off[e as usize])
            .count();
        let off_stations = (0..net.node_count() as u32)
            .filter(|&n| {
                net.is_station(n) && !net.node_built[n as usize] && net.node_off[n as usize]
            })
            .count();
        let off_cost = blueprint_cost - on_infra;
        // what the edits act on: the switched-on pieces (web `pickedState`)
        let (on_e, on_s) = self.blueprint_on();
        let on_set: HashSet<u32> = on_e.iter().copied().collect();
        let (mut length, mut level, mut hsr, mut regular, mut tram, mut ones, mut twos) =
            (0., None::<Option<i8>>, 0, 0, 0, 0, 0);
        for e in self.blueprint_edges().iter().filter(|e| on_set.contains(&e.id)) {
            length += e.len;
            let l = (e.lo == e.hi).then_some(e.lo);
            level = Some(match level {
                None => l,
                Some(was) if was == l => l,
                _ => None,
            });
            if !e.crossover {
                match e.kind {
                    0 => regular += 1,
                    1 => hsr += 1,
                    _ => tram += 1,
                }
                if e.tracks == 1 {
                    ones += 1
                } else {
                    twos += 1
                }
            }
        }
        json!({
            "total": total, "cash": self.api.cash(), "sandbox": self.header["mode"] == "sandbox",
            "offCost": if off_edges + off_stations > 0 && off_cost >= 0.5 { off_cost } else { 0. },
            "offEdges": off_edges, "offStations": off_stations,
            "parts": parts_json, "quote": quote,
            "picked": {"edges": on_e.len(), "stations": on_s.len(), "length": length,
                "level": level.flatten(), "hsr": hsr, "regular": regular, "tram": tram,
                "ones": ones, "twos": twos},
        })
    }

    /// One undoable edit on the blueprint. `OP_SWITCH` switches `edges` and `stations` off
    /// (`value` 1) or on (0); every other op acts on the pieces switched on: `OP_LEVEL` to level
    /// `value`, `OP_KIND` to track type `value` (1 regular, 2 high speed, 3 tram), `OP_TRACKS` to
    /// `value` tracks, `OP_REMOVE` deletes them, `OP_CONSTRUCT` builds them with the bus and ferry
    /// routes (web Construct). Uncommitted, the answer is what it would do.
    pub(crate) fn blueprint_edit(
        &mut self,
        op: i32,
        value: i32,
        edges: &[u32],
        stations: &[u32],
        clock: f64,
        commit: bool,
    ) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        let mut next = self.edit_candidate(clock).ok_or("Dunia belum siap.")?;
        let (on_e, on_s) = next.blueprint_on();
        let n = on_e.len();
        let to = match op {
            OP_LEVEL => format!("tingkat {}", level_label(value as i64)),
            OP_KIND => match value {
                1 => "rel biasa",
                2 => "rel kecepatan tinggi",
                _ => "rel trem",
            }
            .into(),
            OP_TRACKS => if value == 1 { "rel tunggal" } else { "rel ganda" }.into(),
            _ => String::new(),
        };
        let cannot = || {
            if n == 1 {
                format!("Rel ini tidak bisa dipindah ke {to}.")
            } else {
                format!("Tak satu pun dari {n} potongan bisa dipindah ke {to}.")
            }
        };
        let mut result = json!({"edges": n, "stations": on_s.len()});
        let r = match op {
            OP_SWITCH => {
                if edges.is_empty() && stations.is_empty() {
                    return Err("Tidak ada rencana yang dipilih.".into());
                }
                next.api.set_blueprint_off(edges, stations, value != 0)
            }
            OP_LEVEL | OP_KIND | OP_TRACKS => {
                if n == 0 {
                    return Err("Belum ada rel rencana yang dipilih.".into());
                }
                let r = match op {
                    OP_LEVEL => next.api.set_edges_level(&on_e, value.clamp(-3, 3) as i8),
                    OP_KIND => next.api.set_edges_kind(&on_e, value),
                    _ => next.api.set_edges_tracks(&on_e, value as u8),
                };
                if r != 0 {
                    return Err(cannot());
                }
                result["skipped"] = json!(next.api.last_skipped());
                result["to"] = json!(to);
                r
            }
            OP_REMOVE => {
                if on_e.is_empty() && on_s.is_empty() {
                    return Err("Belum ada rencana yang dipilih.".into());
                }
                if !commit {
                    let lines = next.api.removal_lines(&on_e, &on_s);
                    let length: f64 = next
                        .blueprint_edges()
                        .iter()
                        .filter(|e| on_e.contains(&e.id))
                        .map(|e| e.len)
                        .sum();
                    result["lines"] = json!(lines.len() / 3);
                    result["length"] = json!(length);
                    return Ok(result);
                }
                next.api.delete_pieces(&on_e, &on_s)
            }
            OP_CONSTRUCT => {
                if !commit {
                    let net = &next.api.world().net;
                    let mut buses = net.bus_lines();
                    buses.extend(net.ferry_lines());
                    result["cost"] = json!(next.api.construct_cost(&on_e, &on_s, &buses));
                    return Ok(result);
                }
                next.api.construct_all()
            }
            _ => return Err("Perintah rencana tidak dikenal.".into()),
        };
        if r != 0 {
            return Err(match op {
                OP_SWITCH => "Tidak ada yang berubah.".into(),
                OP_REMOVE => "Gagal menghapus. Coba lagi.".into(),
                _ => format!("Rencana gagal dibangun: {}", crate::issue_text::describe(&next.api.issue_kinds())),
            });
        }
        Ok(finish(self, next, clock, commit, result))
    }
}

fn part_cost(p: &Part) -> f64 {
    p.rows.iter().map(|r| r.cost).sum::<f64>() + p.junction_cost
}

/// Quad track's twin stations, and the stretches between twins, are one row each.
fn merge_twins(p: &mut Part, near: impl Fn(u32, u32) -> bool) {
    let mut out: Vec<Row> = vec![];
    for r in p.rows.drain(..) {
        let twin = out.iter_mut().find(|o| {
            o.station == r.station
                && !r.ends.is_empty()
                && r.ends.len() == o.ends.len()
                && r.ends.iter().all(|&a| o.ends.iter().any(|&b| near(a, b)))
        });
        match twin {
            None => out.push(r),
            Some(was) => {
                was.edges.extend(&r.edges);
                was.stations.extend(&r.stations);
                was.nodes.extend(&r.nodes);
                was.length = was.length.max(r.length);
                was.cost += r.cost;
                was.on_cost += r.on_cost;
                was.crossings += r.crossings;
                was.on |= r.on;
                was.off |= r.off;
                was.lo = was.lo.min(r.lo);
                was.hi = was.hi.max(r.hi);
            }
        }
    }
    p.rows = out;
}

fn ids(env: &JNIEnv, a: &JIntArray) -> Vec<u32> {
    let n = env.get_array_length(a).unwrap_or(0).clamp(0, 1 << 20);
    let mut v = vec![0; n as usize];
    if n > 0 && env.get_int_array_region(a, 0, &mut v).is_err() {
        return vec![];
    }
    v.into_iter().filter(|&x| x >= 0).map(|x| x as u32).collect()
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_BlueprintPanel_overview(
    env: JNIEnv,
    _: JClass,
    h: jlong,
) -> jstring {
    let result = unsafe { (h as *mut World).as_mut() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .map(|w| w.blueprint_overview());
    response(env, result)
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_world_nativegame_BlueprintPanel_edit(
    env: JNIEnv,
    _: JClass,
    h: jlong,
    clock: jdouble,
    op: jint,
    value: jint,
    edges: JIntArray,
    stations: JIntArray,
    commit: jboolean,
) -> jstring {
    let (edges, stations) = (ids(&env, &edges), ids(&env, &stations));
    let result = unsafe { (h as *mut World).as_mut() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .and_then(|w| w.blueprint_edit(op, value, &edges, &stations, clock, commit != 0));
    response(env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    const CLOCK: f64 = 111600.;

    /// Two separate plans: 4 km of track with a station, and 3 km elsewhere.
    fn two_plans() -> World {
        let mut w = World::workshop();
        w.plan_track(&[0., 0., 4000., 0.]);
        w.place_platform(2000., 8., 30., "Pusat", 160, CLOCK, true, true)
            .unwrap();
        w.plan_track(&[0., 6000., 3000., 6000.]);
        w
    }

    fn built_edges(w: &World) -> usize {
        w.api
            .edges_info()
            .chunks_exact(10)
            .filter(|r| r[4] == 1.)
            .count()
    }

    fn ids_of(v: &Value, key: &str) -> Vec<u32> {
        v[key]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_u64().unwrap() as u32)
            .collect()
    }

    #[test]
    fn parts_list_each_plan_with_its_cost_and_total() {
        let mut w = two_plans();
        let o = w.blueprint_overview();
        let parts = o["parts"].as_array().unwrap();
        assert_eq!(parts.len(), 2);
        let sum: f64 = parts.iter().map(|p| p["onCost"].as_f64().unwrap()).sum();
        let infra: f64 = o["quote"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|r| r["track"] == true)
            .map(|r| r["cost"].as_f64().unwrap())
            .sum();
        assert!((sum - infra).abs() < 1e-6, "{sum} vs {infra}");
        assert!(o["total"].as_f64().unwrap() > 0.);
        // the first part, the dearer, is the one with the station, cut at it
        let first = &parts[0];
        assert_eq!(first["stationCount"], 1);
        assert_eq!(first["label"], "Pusat");
        let rows = first["rows"].as_array().unwrap();
        assert!(rows.iter().any(|r| r["kind"] == "station"));
        assert_eq!(rows.iter().filter(|r| r["kind"] == "stretch").count(), 2);
        assert!(rows.iter().all(|r| r["label"].as_str().unwrap().contains(" ke ") || r["kind"] == "station"));
        assert_eq!(o["picked"]["stations"], 1);
        assert_eq!(o["picked"]["level"], 1);
    }

    #[test]
    fn construct_builds_only_what_is_switched_on() {
        let mut w = two_plans();
        let o = w.blueprint_overview();
        let parts = o["parts"].as_array().unwrap();
        let (big, small) = (&parts[0], &parts[1]);
        let small_edges = ids_of(small, "edges");
        // switch the station's part off: the total falls to the other part's
        w.blueprint_edit(OP_SWITCH, 1, &ids_of(big, "edges"), &ids_of(big, "stations"), CLOCK, true)
            .unwrap();
        let o2 = w.blueprint_overview();
        assert_eq!(o2["offStations"], 1);
        assert!(o2["offCost"].as_f64().unwrap() > 0.);
        assert!(
            (o2["total"].as_f64().unwrap() - small["cost"].as_f64().unwrap()).abs() < 1e-6
        );
        let quote = w
            .blueprint_edit(OP_CONSTRUCT, 0, &[], &[], CLOCK, false)
            .unwrap();
        let built = w
            .blueprint_edit(OP_CONSTRUCT, 0, &[], &[], CLOCK, true)
            .unwrap();
        assert!((built["cost"].as_f64().unwrap() - quote["cost"].as_f64().unwrap()).abs() < 1e-6);
        assert_eq!(built_edges(&w), small_edges.len());
        // the part switched off is still a plan, its station unbuilt
        let o3 = w.blueprint_overview();
        assert_eq!(o3["parts"].as_array().unwrap().len(), 1);
        assert_eq!(o3["picked"]["edges"], 0);
        assert!(w.blueprint_edit(OP_CONSTRUCT, 0, &[], &[], CLOCK, true).is_err());
        // switched on again, it builds too
        let left = &o3["parts"][0];
        w.blueprint_edit(OP_SWITCH, 0, &ids_of(left, "edges"), &ids_of(left, "stations"), CLOCK, true)
            .unwrap();
        w.blueprint_edit(OP_CONSTRUCT, 0, &[], &[], CLOCK, true)
            .unwrap();
        assert!(w.blueprint_overview()["parts"].as_array().unwrap().is_empty());
    }

    #[test]
    fn remove_takes_only_switched_on_plans_and_leaves_built_track() {
        let mut w = World::workshop();
        w.lay_built(&[0., -6000., 4000., -6000.]);
        let built = built_edges(&w);
        w.plan_track(&[0., 0., 4000., 0.]);
        w.plan_track(&[0., 6000., 3000., 6000.]);
        let o = w.blueprint_overview();
        let parts = o["parts"].as_array().unwrap();
        assert_eq!(parts.len(), 2);
        let keep = &parts[1];
        w.blueprint_edit(OP_SWITCH, 1, &ids_of(keep, "edges"), &[], CLOCK, true)
            .unwrap();
        let preview = w.blueprint_edit(OP_REMOVE, 0, &[], &[], CLOCK, false).unwrap();
        assert_eq!(preview["lines"], 0);
        assert!(preview["length"].as_f64().unwrap() > 3000.);
        let before = w.save(CLOCK).unwrap();
        let mut copy = World::open(&before).unwrap();
        copy.blueprint_edit(OP_REMOVE, 0, &[], &[], CLOCK, true)
            .unwrap();
        let left = copy.blueprint_overview();
        let parts = left["parts"].as_array().unwrap();
        assert_eq!(parts.len(), 1);
        assert_eq!(ids_of(&parts[0], "edges"), ids_of(keep, "edges"));
        assert_eq!(parts[0]["on"], false);
        assert_eq!(built_edges(&copy), built);
        // nothing switched on: nothing to remove
        assert!(copy.blueprint_edit(OP_REMOVE, 0, &[], &[], CLOCK, true).is_err());
    }

    #[test]
    fn mass_level_and_type_change_only_switched_on_plans() {
        let mut w = two_plans();
        let o = w.blueprint_overview();
        let parts = o["parts"].as_array().unwrap();
        let other = &parts[1];
        w.blueprint_edit(OP_SWITCH, 1, &ids_of(other, "edges"), &[], CLOCK, true)
            .unwrap();
        let moved = w.blueprint_edit(OP_LEVEL, 0, &[], &[], CLOCK, true).unwrap();
        assert_eq!(moved["to"], "tingkat 0");
        let o2 = w.blueprint_overview();
        assert_eq!(o2["picked"]["level"], 0);
        let level_of = |w: &World, e: u32| {
            w.api.edges_info().chunks_exact(10).find(|r| r[0] as u32 == e).unwrap()[8]
        };
        for e in ids_of(other, "edges") {
            assert_eq!(level_of(&w, e), 1.);
        }
        w.blueprint_edit(OP_KIND, 2, &[], &[], CLOCK, true).unwrap();
        let o3 = w.blueprint_overview();
        assert_eq!(o3["picked"]["regular"], 0);
        assert!(o3["picked"]["hsr"].as_u64().unwrap() > 0);
        // the part switched off kept its type
        let o4 = w.blueprint_overview();
        let off = o4["parts"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["on"] == false)
            .unwrap()
            .clone();
        for e in ids_of(&off, "edges") {
            assert_eq!(w.api.edge_kind(e), 0);
        }
        // an uncommitted edit leaves the world as it was
        let saved = w.save(CLOCK).unwrap();
        w.blueprint_edit(OP_LEVEL, 2, &[], &[], CLOCK, false).unwrap();
        assert_eq!(saved, w.save(CLOCK).unwrap());
    }

    impl World {
        fn plan_track(&mut self, points: &[f64]) {
            self.lay_test_track(points, true);
        }
        fn lay_built(&mut self, points: &[f64]) {
            self.lay_test_track(points, false);
        }
    }
}
