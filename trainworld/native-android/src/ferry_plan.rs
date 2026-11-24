// SPDX-License-Identifier: AGPL-3.0-only
use super::*;
use std::{
    cmp::Reverse,
    collections::{BinaryHeap, HashMap},
};

type Cell = (i32, i32);
const NEIGHBOURS: [Cell; 8] = [
    (1, 0),
    (-1, 0),
    (0, 1),
    (0, -1),
    (1, 1),
    (-1, -1),
    (1, -1),
    (-1, 1),
];

struct WaterGrid<'a> {
    api: &'a TrackApi,
    size: f64,
}
impl WaterGrid<'_> {
    fn cell(&self, p: [f64; 2]) -> Cell {
        (
            (p[0] / self.size).round() as i32,
            (p[1] / self.size).round() as i32,
        )
    }
    fn xy(&self, p: Cell) -> [f64; 2] {
        [p.0 as f64 * self.size, p.1 as f64 * self.size]
    }
    fn sample(&self, p: Cell) -> Option<bool> {
        let [x, y] = self.xy(p);
        self.api.native_pack_water_at(x, y)
    }
    fn snap(&self, p: [f64; 2]) -> Result<(Cell, Cell), String> {
        let c = self.cell(p);
        let mut best = None;
        for dx in -12..=12 {
            for dy in -12..=12 {
                let a = (c.0 + dx, c.1 + dy);
                if self.sample(a) != Some(false) {
                    continue;
                }
                for (qx, qy) in NEIGHBOURS[..4].iter().copied() {
                    let b = (a.0 + qx, a.1 + qy);
                    if self.sample(b) == Some(true) {
                        let pos = self.xy(a);
                        let d = (pos[0] - p[0]).hypot(pos[1] - p[1]);
                        if d <= 12. * self.size && best.is_none_or(|(v, _, _)| d < v) {
                            best = Some((d, a, b))
                        }
                    }
                }
            }
        }
        best.map(|(_, a, b)| (a, b)).ok_or(
            "Ketuk tepi perairan, maksimal 300 meter dari garis pantai dalam paket kota.".into(),
        )
    }
    fn route(&self, start: Cell, end: Cell) -> Result<Vec<Cell>, String> {
        let heuristic = |p: Cell| {
            let dx = (p.0 - end.0).abs();
            let dy = (p.1 - end.1).abs();
            10 * dx.max(dy) + 4 * dx.min(dy)
        };
        let mut heap = BinaryHeap::from([Reverse((heuristic(start), 0, start))]);
        let mut seen = HashMap::from([(start, (0, start))]);
        while let Some(Reverse((_, cost, p))) = heap.pop() {
            if p == end {
                let mut path = vec![p];
                let mut last = p;
                while last != start {
                    last = seen[&last].1;
                    path.push(last)
                }
                path.reverse();
                return Ok(path);
            }
            if seen[&p].0 != cost {
                continue;
            }
            if seen.len() > 150000 {
                return Err(
                    "Pencarian air terlalu luas. Tambah titik lintasan dengan menahan peta.".into(),
                );
            }
            for (dx, dy) in NEIGHBOURS {
                let q = (p.0 + dx, p.1 + dy);
                let diag = dx != 0 && dy != 0;
                let nc = cost + if diag { 14 } else { 10 };
                if seen.get(&q).is_some_and(|(c, _)| *c <= nc) || self.sample(q) != Some(true) {
                    continue;
                }
                if diag
                    && (self.sample((p.0 + dx, p.1)) != Some(true)
                        || self.sample((p.0, p.1 + dy)) != Some(true))
                {
                    continue;
                }
                let a = self.xy(p);
                let b = self.xy(q);
                if ![0.25, 0.5, 0.75].into_iter().all(|t| {
                    self.api
                        .native_pack_water_at(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
                        == Some(true)
                }) {
                    continue;
                }
                seen.insert(q, (nc, p));
                heap.push(Reverse((nc + heuristic(q), nc, q)));
            }
        }
        Err("Tidak ada jalur air yang menghubungkan titik ini.".into())
    }
}

pub(super) fn build(
    api: &mut TrackApi,
    points: &[[f64; 2]],
    bends: &[[f64; 3]],
    name: &str,
) -> Result<u32, String> {
    let grid = WaterGrid {
        size: 25. * api.world().net.k_at(points[0][1]),
        api,
    };
    let docks = points
        .iter()
        .map(|p| grid.snap(*p))
        .collect::<Result<Vec<_>, _>>()?;
    let mut vertices = Vec::<(Cell, bool)>::new();
    for (leg, pair) in docks.windows(2).enumerate() {
        if pair[0].1 == pair[1].1 {
            return Err("Pilih dua dermaga berbeda.".into());
        }
        let mut stops = vec![pair[0].1];
        for p in bends.iter().filter(|b| b[0] as usize == leg) {
            let c = grid.cell([p[1], p[2]]);
            if grid.sample(c) != Some(true) {
                return Err("Letakkan titik lintasan feri di atas air.".into());
            }
            stops.push(c)
        }
        stops.push(pair[1].1);
        for section in stops.windows(2) {
            let path = grid.route(section[0], section[1])?;
            if vertices.is_empty() {
                vertices.push((section[0], true))
            }
            for tri in path.windows(3) {
                if (tri[1].0 - tri[0].0, tri[1].1 - tri[0].1)
                    != (tri[2].0 - tri[1].0, tri[2].1 - tri[1].1)
                {
                    vertices.push((tri[1], false))
                }
            }
            if vertices.last().unwrap().0 != section[1] {
                vertices.push((section[1], false))
            }
        }
        vertices.last_mut().unwrap().1 = true;
    }
    let placements: Vec<_> = docks
        .iter()
        .map(|(a, b)| (grid.xy(*a), grid.xy(*b)))
        .collect();
    let shape: Vec<f64> = vertices
        .into_iter()
        .flat_map(|(c, dock)| {
            let [x, y] = grid.xy(c);
            [
                x,
                y,
                if dock { 1. } else { 0. },
                if dock { 2.8 } else { 12.5 },
            ]
        })
        .collect();
    let mut ports = Vec::new();
    for (i, (a, b)) in placements.into_iter().enumerate() {
        if api.add_port(
            a[0],
            a[1],
            b[0],
            b[1],
            format!("{name} {}", (b'A' + i as u8) as char),
        ) != 0
        {
            return Err(format!("Dermaga ditolak: {}", crate::issue_text::describe(&api.issue_kinds())));
        }
        ports.push(api.last_port());
    }
    let line = api.new_line_id();
    if api.set_ferry_line(
        line,
        &ports,
        &shape,
        480,
        720,
        1200,
        60.,
        120.,
        0x007c96,
        name.into(),
    ) != 0
        || api.construct_line(line) != 0
    {
        return Err(format!("Feri ditolak: {}", crate::issue_text::describe(&api.issue_kinds())));
    }
    Ok(line)
}
