//! Lines sharing track drawn side by side (web `map/lineBands.ts`, T-1799): per edge with two or
//! more bands, the bands left to right in its a to b direction, and per route point the path entry
//! it lies on, so the map can shift each line's polyline sideways by its slot.
//!
//! The order is the web's pairwise rule: of two lines on an edge, follow both ahead (the lower id's
//! own direction) to the node where they part and put the one turning more to the left on the left;
//! if they never part ahead, the same looking back; same route both ways, by line id. Lines of one
//! colour share one band on an edge (web `sameColour`). The web's corridors (separate tracks side
//! by side drawn as one ribbon, T-1848) and its long S-curve eases are not ported: the map eases a
//! slot change over one 70 m step of the route polyline.

use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap};
use trainworld_sim::track::wasm::TrackApi;

/// how far along an edge its leaving direction is measured, m (web `BEARING_M`)
const BEARING_M: f64 = 60.;
/// the route polyline's step, m (`World::geometry`'s `run_polyline`)
pub(crate) const ROUTE_STEP_M: f64 = 70.;

struct Line {
    id: u32,
    colour: u32,
    path: Vec<u32>,
    len: f64,
}

#[derive(Clone, Copy)]
struct On {
    line: usize,
    k: usize,
}

pub(crate) struct Bands {
    /// per shared edge: its bands left to right in its a to b direction, each the line ids in it
    pub order: BTreeMap<u32, Vec<Vec<u32>>>,
}

impl Bands {
    pub(crate) fn to_json(&self) -> Value {
        Value::Array(self.order.iter().map(|(e, slots)| json!([e, slots])).collect())
    }
}

/// The edge `ends` and polylines the rule reads.
struct Net<'a> {
    api: &'a TrackApi,
    ends: HashMap<u32, (u32, u32)>,
    mercator: bool,
    leave: std::cell::RefCell<HashMap<(u32, bool), [f64; 2]>>,
}

impl Net<'_> {
    /// the unit direction edge `e` leaves `node` in (y north)
    fn dir_at(&self, e: u32, node: u32) -> [f64; 2] {
        let from_a = self.ends.get(&e).map_or(true, |&(a, _)| a == node);
        if let Some(v) = self.leave.borrow().get(&(e, from_a)) {
            return *v;
        }
        let p = self.api.edge_polyline(e, 0.);
        let n = p.len() / 2;
        let mut v = [1., 0.];
        if n >= 2 {
            let at = |i: usize| {
                let j = if from_a { i } else { n - 1 - i };
                [p[j * 2] as f64, p[j * 2 + 1] as f64]
            };
            let o = at(0);
            let k = if self.mercator { (o[1] / 6371008.8).cosh() } else { 1. };
            let (mut x, mut d) = (o, 0.);
            for i in 1..n {
                if d >= BEARING_M * k {
                    break;
                }
                let q = at(i);
                d += (q[0] - x[0]).hypot(q[1] - x[1]);
                x = q;
            }
            let l = (x[0] - o[0]).hypot(x[1] - o[1]);
            if l > 0. {
                v = [(x[0] - o[0]) / l, (x[1] - o[1]) / l];
            }
        }
        self.leave.borrow_mut().insert((e, from_a), v);
        v
    }
    /// how far left (rad) leaving along `out` turns from arriving along `inn`, at `node`
    fn turn(&self, inn: u32, out: u32, node: u32) -> f64 {
        let [ax, ay] = self.dir_at(inn, node);
        let [bx, by] = self.dir_at(out, node);
        (-ax * by + ay * bx).atan2(-ax * bx - ay * by)
    }
    /// line `l`'s entry `j` steps from `k`: the edge and the node it is entered from
    fn step(&self, l: &Line, k: usize, fwd: bool, j: usize) -> Option<(u32, u32)> {
        let i = if fwd { k.checked_add(j)? } else { k.checked_sub(j)? };
        let p = *l.path.get(i)?;
        let &(a, b) = self.ends.get(&(p >> 1))?;
        let from_a = (p & 1 == 0) == fwd;
        Some((p >> 1, if from_a { a } else { b }))
    }
    /// of two lines on `edge`, walking towards its b end or away: < 0 when `x` turns left of `y`
    /// where they part, > 0 right, 0 if they never part
    fn part(&self, edge: u32, lines: &[Line], x: On, y: On, to_b: bool) -> i32 {
        let (lx, ly) = (&lines[x.line], &lines[y.line]);
        let fx = (lx.path[x.k] & 1 == 0) == to_b;
        let fy = (ly.path[y.k] & 1 == 0) == to_b;
        let mut prev = edge;
        for j in 1..=lx.path.len().min(ly.path.len()) {
            let (Some(a), Some(b)) = (self.step(lx, x.k, fx, j), self.step(ly, y.k, fy, j)) else { return 0 };
            if a != b {
                if a.1 != b.1 {
                    return 0;
                }
                let (ta, tb) = (self.turn(prev, a.0, a.1), self.turn(prev, b.0, b.1));
                return if ta > tb { -1 } else if ta < tb { 1 } else { 0 };
            }
            prev = a.0;
        }
        0
    }
    /// < 0: `x` left of `y` in the edge's a to b direction (web `compare`)
    fn compare(&self, edge: u32, lines: &[Line], x: On, y: On) -> i32 {
        let (ix, iy) = (lines[x.line].id, lines[y.line].id);
        let lead = if ix < iy { x } else { y };
        let to_b = lines[lead.line].path[lead.k] & 1 == 0;
        let first = self.part(edge, lines, x, y, to_b);
        let second = if first != 0 { 0 } else { self.part(edge, lines, x, y, !to_b) };
        let s = if to_b { 1 } else { -1 };
        let by_id = (ix as i64 - iy as i64).signum() as i32;
        if first != 0 { s * first } else if second != 0 { -s * second } else { s * by_id }
    }
}

/// The bands of every rail line on shared edges.
pub(crate) fn bands(api: &TrackApi, lines_info: &[f64]) -> Bands {
    let lines: Vec<Line> = lines_info
        .chunks_exact(22)
        .filter(|r| r[2] == 1. && !api.is_bus(r[0] as u32))
        .map(|r| Line { id: r[0] as u32, colour: r[1] as u32, path: api.line_path(r[0] as u32), len: r[9] })
        .filter(|l| !l.path.is_empty())
        .collect();
    let net = Net {
        api,
        ends: api.edges_info().chunks_exact(10).map(|r| (r[0] as u32, (r[1] as u32, r[2] as u32))).collect(),
        mercator: api.frame_kind() == 2,
        leave: Default::default(),
    };
    // per edge: the lines on it, each with its first entry in its path
    let mut on: BTreeMap<u32, Vec<On>> = BTreeMap::new();
    for (i, l) in lines.iter().enumerate() {
        for (k, p) in l.path.iter().enumerate() {
            let v = on.entry(p >> 1).or_default();
            if !v.iter().any(|x| x.line == i) {
                v.push(On { line: i, k });
            }
        }
    }
    let mut order = BTreeMap::new();
    for (&edge, v) in &on {
        // one band per colour, ordered by its longest line (lowest id on a tie)
        let mut slots: Vec<(On, Vec<u32>)> = vec![];
        for x in v {
            let l = &lines[x.line];
            match slots.iter_mut().find(|(r, _)| lines[r.line].colour == l.colour) {
                Some((rep, ids)) => {
                    ids.push(l.id);
                    let r = &lines[rep.line];
                    if l.len > r.len || (l.len == r.len && l.id < r.id) {
                        *rep = *x;
                    }
                }
                None => slots.push((*x, vec![l.id])),
            }
        }
        if slots.len() < 2 {
            continue;
        }
        // by how many of the others each is left of (web: a fixed order where partings go round)
        let m = slots.len();
        let mut wins = vec![0; m];
        let mut c = vec![0; m * m];
        for i in 0..m {
            for j in i + 1..m {
                let r = net.compare(edge, &lines, slots[i].0, slots[j].0).signum();
                c[i * m + j] = r;
                c[j * m + i] = -r;
                if r < 0 { wins[i] += 1 } else if r > 0 { wins[j] += 1 }
            }
        }
        let mut idx: Vec<usize> = (0..m).collect();
        idx.sort_by(|&i, &j| {
            wins[j].cmp(&wins[i]).then(c[i * m + j].cmp(&0)).then(lines[slots[i].0.line].id.cmp(&lines[slots[j].0.line].id))
        });
        order.insert(edge, idx.into_iter().map(|i| { let mut ids = slots[i].1.clone(); ids.sort(); ids }).collect());
    }
    Bands { order }
}

/// Per point of `run_polyline(id, 0, ROUTE_STEP_M)`: the path entry (`edge << 1 | dir`) it lies on,
/// as `segment_at` finds it; empty for a bus line.
pub(crate) fn route_entries(api: &TrackApi, id: u32, len: f64) -> Vec<u32> {
    if api.is_bus(id) {
        return vec![];
    }
    let path = api.line_path(id);
    if path.is_empty() {
        return vec![];
    }
    let lens: HashMap<u32, f64> = api.edges_info().chunks_exact(10).map(|r| (r[0] as u32, r[5])).collect();
    let mut off = Vec::with_capacity(path.len());
    let mut s = 0.;
    for p in &path {
        off.push(s);
        s += lens.get(&(p >> 1)).copied().unwrap_or(0.);
    }
    let n = (len / ROUTE_STEP_M).ceil() as usize;
    (0..=n)
        .map(|i| {
            let s = (i as f64 * ROUTE_STEP_M).min(len);
            let k = off.partition_point(|&o| o <= s).saturating_sub(1).min(path.len() - 1);
            path[k]
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::World;

    #[test]
    fn lines_on_one_edge_get_bands_and_a_branch_turning_left_is_on_the_left() {
        let mut w = World::workshop();
        let t = 111600.;
        // a trunk west to east, then a branch leaving its middle station northwards (left of east)
        w.build_path(&[[0., 0.], [2000., 0.], [4000., 0.]], "Utama", t).unwrap();
        w.build_path(&[[0., 0.], [2000., 0.], [2000., 2500.]], "Cabang", t).unwrap();
        let info = w.api.lines_info();
        let b = bands(&w.api, &info);
        assert!(!b.order.is_empty(), "the shared edge has bands");
        // the shared edge is the first of each line's path
        let p0 = w.api.line_path(0)[0];
        assert_eq!(w.api.line_path(1)[0] >> 1, p0 >> 1);
        let slots = &b.order[&(p0 >> 1)];
        assert_eq!(slots.len(), 2);
        // left in travel east: the branch turning north; a to b east keeps the order, b to a flips it
        let left_in_travel = if p0 & 1 == 0 { &slots[0] } else { &slots[1] };
        assert_eq!(left_in_travel, &vec![1]);
        // edges only one line runs on have no bands
        let last = *w.api.line_path(0).last().unwrap();
        assert!(!b.order.contains_key(&(last >> 1)));
        assert!(b.to_json().as_array().unwrap().len() == b.order.len());
    }

    #[test]
    fn a_branch_turning_right_is_on_the_right() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_path(&[[0., 0.], [2000., 0.], [4000., 0.]], "Utama", t).unwrap();
        w.build_path(&[[0., 0.], [2000., 0.], [2000., -2500.]], "Cabang", t).unwrap();
        let b = bands(&w.api, &w.api.lines_info());
        let p0 = w.api.line_path(0)[0];
        let slots = &b.order[&(p0 >> 1)];
        let left_in_travel = if p0 & 1 == 0 { &slots[0] } else { &slots[1] };
        assert_eq!(left_in_travel, &vec![0]);
    }

    #[test]
    fn route_points_know_their_edge() {
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [2000., 0.], [4000., 0.]], "Utama", 111600.).unwrap();
        let info = w.api.lines_info();
        let len = info[9];
        let at = route_entries(&w.api, 0, len);
        let pts = w.api.run_polyline(0, 0, ROUTE_STEP_M);
        assert_eq!(at.len() * 2, pts.len());
        let path = w.api.line_path(0);
        assert_eq!(at[0], path[0]);
        assert_eq!(*at.last().unwrap(), *path.last().unwrap());
        // every point lies on (within a metre of) the edge it is given
        for (i, p) in at.iter().enumerate() {
            let e = w.api.edge_polyline(p >> 1, 0.);
            let (x, y) = (pts[i * 2], pts[i * 2 + 1]);
            let near = e.chunks_exact(2).collect::<Vec<_>>().windows(2).map(|s| {
                let (ax, ay, bx, by) = (s[0][0] as f64, s[0][1] as f64, s[1][0] as f64, s[1][1] as f64);
                let (dx, dy) = (bx - ax, by - ay);
                let l2 = dx * dx + dy * dy;
                let u = if l2 > 0. { (((x - ax) * dx + (y - ay) * dy) / l2).clamp(0., 1.) } else { 0. };
                (ax + u * dx - x).hypot(ay + u * dy - y)
            }).fold(f64::INFINITY, f64::min);
            assert!(near < 1., "point {i} is {near} m off its edge");
        }
    }
}
