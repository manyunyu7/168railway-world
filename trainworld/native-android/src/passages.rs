// SPDX-License-Identifier: AGPL-3.0-only
//! The passages on the map and in the station inspector, as the web has them.
//!
//! Map (web map/network.ts `complexPassages` + stationGeometry.ts `bridgeEnds`, renderer.ts
//! `syncGroups`): zoomed in (station boxes) a passage is a bridge between the nearest points of two
//! platforms, for each of the player's links and for the shortest joins that connect a complex whose
//! boxes do not touch; zoomed out (dots) each group the complex distances make is joined by its
//! shortest connectors between dots, plus the player's links between groups. Built stations only,
//! as the Android map draws them.
//!
//! Inspector (web StationInspector.tsx `StationLinks`): the player's links touching a complex,
//! with their length and price.

use serde_json::{json, Value};
use trainworld_sim::track::building::platform_line;
use trainworld_sim::track::upgrades::passage_price;
use trainworld_sim::track::wasm::TrackApi;

type P = [f64; 2];

/// Platforms closer than this, ground m, have boxes that touch (game/complexes.ts `BOX_TOUCH_M`).
const BOX_TOUCH_M: f64 = 16.0;
/// game/complexes.ts `DEMAND_MERGE_M` and `PLATFORM_GAP_M`.
const DEMAND_MERGE_M: f64 = 100.0;
const PLATFORM_GAP_M: f64 = 50.0;
/// A bridge starts this far inside a platform's end, CSS px at zoom 15 (stationGeometry.ts
/// `BRIDGE_INSET_PX`), so its white crosses the box's rim.
const BRIDGE_INSET_PX: f64 = 6.0;
/// Mercator metres a CSS px covers at zoom 15 (512 px tiles).
const MERC_M_PER_PX_Z15: f64 = 40_075_016.7 / (512.0 * 32768.0);

struct Uf(Vec<usize>);
impl Uf {
    fn new(n: usize) -> Self { Uf((0..n).collect()) }
    fn find(&mut self, mut i: usize) -> usize {
        while self.0[i] != i {
            self.0[i] = self.0[self.0[i]];
            i = self.0[i];
        }
        i
    }
    fn union(&mut self, a: usize, b: usize) -> bool {
        let (a, b) = (self.find(a), self.find(b));
        if a == b { return false; }
        self.0[a] = b;
        true
    }
}

fn nearest_on_segments(p0: P, p1: P, q0: P, q1: P) -> (P, P) {
    let u = [p1[0] - p0[0], p1[1] - p0[1]];
    let v = [q1[0] - q0[0], q1[1] - q0[1]];
    let w = [p0[0] - q0[0], p0[1] - q0[1]];
    let (a, b, c) = (u[0] * u[0] + u[1] * u[1], u[0] * v[0] + u[1] * v[1], v[0] * v[0] + v[1] * v[1]);
    let (d, e) = (u[0] * w[0] + u[1] * w[1], v[0] * w[0] + v[1] * w[1]);
    let den = a * c - b * b;
    let mut s = if a <= 1e-12 { 0.0 } else if den > 1e-12 { ((b * e - c * d) / den).clamp(0.0, 1.0) } else { 0.0 };
    let mut t = if c <= 1e-12 { 0.0 } else { (b * s + e) / c };
    if t < 0.0 {
        t = 0.0;
        s = if a <= 1e-12 { 0.0 } else { (-d / a).clamp(0.0, 1.0) };
    } else if t > 1.0 {
        t = 1.0;
        s = if a <= 1e-12 { 0.0 } else { ((b - d) / a).clamp(0.0, 1.0) };
    }
    ([p0[0] + s * u[0], p0[1] + s * u[1]], [q0[0] + t * v[0], q0[1] + t * v[1]])
}

fn segments(p: &[P]) -> Vec<(P, P)> {
    if p.len() < 2 { p.iter().map(|&q| (q, q)).collect() } else { p.windows(2).map(|w| (w[0], w[1])).collect() }
}

/// The nearest points of two polylines and their distance.
fn nearest(a: &[P], b: &[P]) -> (P, P, f64) {
    let mut best = (a.first().copied().unwrap_or_default(), b.first().copied().unwrap_or_default(), f64::INFINITY);
    for (p0, p1) in segments(a) {
        for &(q0, q1) in &segments(b) {
            let (p, q) = nearest_on_segments(p0, p1, q0, q1);
            let d = (p[0] - q[0]).hypot(p[1] - q[1]);
            if d < best.2 { best = (p, q, d); }
        }
    }
    best
}

/// `line` shortened by `inset` at each end (at most to a point at its middle).
fn inset_line(line: &[P], inset: f64) -> Vec<P> {
    let len: f64 = line.windows(2).map(|w| (w[1][0] - w[0][0]).hypot(w[1][1] - w[0][1])).sum();
    let cut = inset.min(len * 0.45);
    if line.len() < 2 || cut <= 0.0 { return line.to_vec(); }
    let at = |d: f64| -> P {
        let mut left = d;
        for w in line.windows(2) {
            let s = (w[1][0] - w[0][0]).hypot(w[1][1] - w[0][1]);
            if left <= s && s > 0.0 {
                let f = left / s;
                return [w[0][0] + f * (w[1][0] - w[0][0]), w[0][1] + f * (w[1][1] - w[0][1])];
            }
            left -= s;
        }
        line[line.len() - 1]
    };
    let mut out = vec![at(cut)];
    let mut run = 0.0;
    for w in line.windows(2) {
        run += (w[1][0] - w[0][0]).hypot(w[1][1] - w[0][1]);
        if run > cut && run < len - cut { out.push(w[1]); }
    }
    out.push(at(len - cut));
    out
}

/// The map's passages: `bridges` (zoomed in) and `connectors` (zoomed out), each flat
/// [x1, y1, x2, y2, ...] in plane units.
pub fn drawn(api: &TrackApi) -> Value {
    let net = &api.world().net;
    let st: Vec<u32> = (0..net.node_count() as u32).filter(|&n| net.is_station(n) && net.node_built[n as usize]).collect();
    let n = st.len();
    let index: std::collections::HashMap<u32, usize> = st.iter().enumerate().map(|(i, &s)| (s, i)).collect();
    let lines: Vec<Vec<P>> = st.iter().map(|&s| platform_line(net, s)).collect();
    let k: Vec<f64> = st.iter().map(|&s| net.k_at(net.node_y[s as usize])).collect();
    let xy: Vec<P> = st.iter().map(|&s| [net.node_x[s as usize], net.node_y[s as usize]]).collect();
    let reach: Vec<f64> = (0..n).map(|i| lines[i].iter().map(|p| (p[0] - xy[i][0]).hypot(p[1] - xy[i][1])).fold(0.0, f64::max)).collect();
    let mid: Vec<P> = lines.iter().zip(&xy).map(|(l, p)| l.get(l.len() / 2).copied().unwrap_or(*p)).collect();
    // every pair the complex rule may link: centres within this window of each other
    let one = (0..n).map(|i| reach[i].max(1.5 * net.node_platform[st[i] as usize] as f64 * k[i])).fold(0.0, f64::max);
    let kmax = k.iter().copied().fold(1.0, f64::max);
    let window = 2.0 * one + (DEMAND_MERGE_M + PLATFORM_GAP_M) * kmax;
    let mut order: Vec<usize> = (0..n).collect();
    order.sort_by(|&a, &b| xy[a][0].total_cmp(&xy[b][0]));
    let mut near = Uf::new(n); // the distance groups (web `dotGroups`)
    let mut complex = Uf::new(n); // distance groups and the player's links (web `complexOf`)
    let mut close: Vec<(usize, usize)> = Vec::new();
    for (p, &i) in order.iter().enumerate() {
        for &j in &order[p + 1..] {
            if xy[j][0] - xy[i][0] > window { break; }
            if (xy[j][1] - xy[i][1]).abs() > window { continue; }
            if net.near_enough(st[i], st[j]) {
                near.union(i, j);
                complex.union(i, j);
            }
            let kk = (k[i] + k[j]) / 2.0;
            if (xy[i][0] - xy[j][0]).hypot(xy[i][1] - xy[j][1]) <= reach[i] + reach[j] + DEMAND_MERGE_M * kk {
                close.push((i.min(j), i.max(j)));
            }
        }
    }
    // the player's links between built stations, none inside one station building (its floor joins them)
    let halls: Vec<Vec<u32>> = (0..net.buildings.len() as u32).filter(|&b| net.buildings[b as usize].is_some()).map(|b| api.building_members(b)).collect();
    let one_floor = |a: u32, b: u32| halls.iter().any(|m| m.contains(&a) && m.contains(&b));
    let links: Vec<(usize, usize)> = net
        .links_live()
        .filter_map(|[a, b]| Some((*index.get(&a)?, *index.get(&b)?)))
        .collect();
    for &(i, j) in &links { complex.union(i, j); }
    // the joins drawn for a complex whose boxes do not touch: shortest first, the links counting as joined
    let mut joined = Uf::new(n);
    for &(i, j) in &links { joined.union(i, j); }
    let mut joins: Vec<(f64, usize, usize)> = Vec::new();
    for &(i, j) in &close {
        if complex.find(i) != complex.find(j) { continue; }
        let gap = nearest(&lines[i], &lines[j]).2;
        if gap <= BOX_TOUCH_M * (k[i] + k[j]) / 2.0 { joined.union(i, j); } else { joins.push((gap, i, j)); }
    }
    joins.sort_by(|p, q| p.0.total_cmp(&q.0).then(p.1.cmp(&q.1)).then(p.2.cmp(&q.2)));
    let mut pairs: Vec<(usize, usize)> = links.iter().copied().filter(|&(i, j)| !one_floor(st[i], st[j])).collect();
    for (_, i, j) in joins {
        if joined.union(i, j) && !one_floor(st[i], st[j]) { pairs.push((i, j)); }
    }
    let mercator_per_frame = if api.frame_kind() == 2 { 1.0 } else { 1.0 / api.frame_origin()[1].to_radians().cos().max(0.01) };
    let inset = BRIDGE_INSET_PX * MERC_M_PER_PX_Z15 / mercator_per_frame;
    let mut bridges: Vec<f32> = Vec::new();
    for (i, j) in pairs {
        let (p, q, _) = nearest(&inset_line(&lines[i], inset), &inset_line(&lines[j], inset));
        bridges.extend([p[0] as f32, p[1] as f32, q[0] as f32, q[1] as f32]);
    }
    // zoomed out: each distance group's shortest connectors between dots (Prim), then links across groups
    let mut groups: std::collections::BTreeMap<usize, Vec<usize>> = Default::default();
    for i in 0..n { groups.entry(near.find(i)).or_default().push(i); }
    let mut connectors: Vec<f32> = Vec::new();
    let mut connect = |a: P, b: P| connectors.extend([a[0] as f32, a[1] as f32, b[0] as f32, b[1] as f32]);
    for m in groups.values().filter(|m| m.len() > 1) {
        let d = |a: usize, b: usize| (mid[a][0] - mid[b][0]).hypot(mid[a][1] - mid[b][1]);
        let mut best: Vec<(f64, usize)> = m.iter().map(|&j| (d(m[0], j), m[0])).collect();
        let mut done = vec![false; m.len()];
        done[0] = true;
        for _ in 1..m.len() {
            let Some(next) = (0..m.len()).filter(|&q| !done[q]).min_by(|&a, &b| best[a].0.total_cmp(&best[b].0)) else { break };
            done[next] = true;
            connect(mid[best[next].1], mid[m[next]]);
            for q in 0..m.len() {
                if !done[q] && d(m[next], m[q]) < best[q].0 { best[q] = (d(m[next], m[q]), m[next]); }
            }
        }
    }
    for &(i, j) in &links {
        if near.find(i) != near.find(j) { connect(mid[i], mid[j]); }
    }
    json!({"bridges": bridges, "connectors": connectors})
}

/// Web `StationLinks`: the player's links with an end in `members`, each with both stations, the
/// straight length, its price (US$M) and whether both stations are built.
pub fn listed(api: &TrackApi, members: &[u32]) -> Value {
    let net = &api.world().net;
    let rows: Vec<_> = net
        .links_live()
        .filter(|[a, b]| members.contains(a) || members.contains(b))
        .map(|[a, b]| {
            let m = net.passage_m(a, b);
            json!({"a": a, "b": b, "aName": api.node_name(a), "bName": api.node_name(b), "metres": m,
                "price": passage_price(m), "built": net.node_built[a as usize] && net.node_built[b as usize]})
        })
        .collect();
    Value::Array(rows)
}

/// Web `linkRefusal` (game/links.ts): why `a` and `b` cannot be joined by a passage, or None.
pub fn refusal(api: &TrackApi, a: u32, b: u32) -> Option<String> {
    use trainworld_sim::track::upgrades::PASSAGE_MAX_M;
    let net = &api.world().net;
    if !net.is_station(a) || !net.is_station(b) {
        return Some("Pilih stasiun untuk lorongnya.".into());
    }
    if a == b {
        return Some("Pilih stasiun lain untuk lorongnya.".into());
    }
    if net.complex_of(a).contains(&b) {
        return Some(format!("{} sudah ada di kompleks ini.", api.node_name(b)));
    }
    let m = net.passage_m(a, b);
    if m > PASSAGE_MAX_M + 1e-6 {
        let away = if m >= 1000.0 { format!("{:.1} km", m / 1000.0).replace('.', ",") } else { format!("{} m", m.round()) };
        return Some(format!("{} berjarak {away}. Jangkauan lorong 1 km.", api.node_name(b)));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::World;

    const CLOCK: f64 = 111600.;

    fn stations_at(w: &World, y: f64) -> u32 {
        station_near(w, 0., y)
    }
    fn station_near(w: &World, x: f64, y: f64) -> u32 {
        let net = &w.api.world().net;
        (0..net.node_count() as u32)
            .filter(|&n| net.is_station(n))
            .min_by(|&a, &b| {
                let d = |n: u32| (net.node_x[n as usize] - x).hypot(net.node_y[n as usize] - y);
                d(a).total_cmp(&d(b))
            })
            .unwrap()
    }
    fn bridges(w: &World) -> Vec<f64> {
        w.geometry()["passages"]["bridges"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect()
    }

    /// Two stations 300 m apart: no passage until the player links them; the bridge runs between
    /// the two platforms, is in the geometry after save and open, is listed and refused twice.
    #[test]
    fn a_linked_pair_is_drawn_listed_and_kept() {
        let mut w = World::workshop();
        for (y, name) in [(0., "Utara"), (300., "Selatan")] {
            w.build_service([0., y], [2300., y], name, CLOCK).unwrap();
        }
        let (a, b) = (stations_at(&w, 0.), stations_at(&w, 300.));
        assert!(bridges(&w).is_empty());
        let r = crate::station_facilities::station_structure(&mut w, a, CLOCK, &json!({"action":"link","id":b})).unwrap();
        assert_eq!(r["changed"], true);
        assert!(r["charge"].as_f64().unwrap() > 10.0);
        let got = bridges(&w);
        assert_eq!(got.len(), 4, "{got:?}");
        // from one platform to the other, across the 300 m
        assert!((got[1] - got[3]).abs() > 250.0, "{got:?}");
        let listed = listed(&w.api, &[a]);
        assert_eq!(listed.as_array().unwrap().len(), 1);
        assert!(listed[0]["metres"].as_f64().unwrap() > 290.0);
        // the two are one complex now: a second link is refused, as the web's linkRefusal
        let again = crate::station_facilities::station_structure(&mut w, a, CLOCK, &json!({"action":"link","id":b}));
        assert!(again.unwrap_err().contains("sudah ada di kompleks ini"));
        let opened = World::open(&w.save(CLOCK).unwrap()).unwrap();
        assert_eq!(bridges(&opened), got);
        assert_eq!(opened.geometry()["passages"]["connectors"].as_array().unwrap().len(), 4);
        let mut opened = opened;
        crate::station_facilities::station_structure(&mut opened, a, CLOCK, &json!({"action":"unlink","id":b})).unwrap();
        assert!(bridges(&opened).is_empty());
    }

    /// Stations whose platforms run 40 m apart are one complex by distance; their boxes do not touch,
    /// so a passage is drawn between them without a link (web `complexPassages`).
    #[test]
    fn a_complex_whose_boxes_do_not_touch_is_joined() {
        let mut w = World::workshop();
        w.build_service([0., 0.], [2300., 0.], "Barat", CLOCK).unwrap();
        w.build_service([130., 40.], [2430., 40.], "Timur", CLOCK).unwrap();
        let got = bridges(&w);
        // both ends of the two lines: one bridge each, straight across the 40 m
        assert_eq!(got.len(), 8, "{got:?}");
        assert!(got.chunks_exact(4).all(|q| ((q[3] - q[1]).abs() - 40.).abs() < 1.), "{got:?}");
        assert_eq!(w.geometry()["passages"]["connectors"].as_array().unwrap().len(), 8);
        let (a, b) = (stations_at(&w, 0.), station_near(&w, 130., 40.));
        assert!(w.api.world().net.links_live().next().is_none());
        assert!(refusal(&w.api, a, b).unwrap().contains("sudah ada di kompleks ini"));
        assert!(refusal(&w.api, a, a).is_some());
    }

    #[test]
    fn a_passage_too_long_is_refused() {
        let mut w = World::workshop();
        for (y, name) in [(0., "Utara"), (1300., "Selatan")] {
            w.build_service([0., y], [2300., y], name, CLOCK).unwrap();
        }
        let (a, b) = (stations_at(&w, 0.), stations_at(&w, 1300.));
        let why = refusal(&w.api, a, b).unwrap();
        assert!(why.contains("Jangkauan lorong 1 km"), "{why}");
        assert!(bridges(&w).is_empty());
    }
}
