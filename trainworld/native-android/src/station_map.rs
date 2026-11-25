// SPDX-License-Identifier: AGPL-3.0-only
use serde_json::{json, Value};
use std::collections::HashSet;
use trainworld_sim::track::wasm::TrackApi;

type Point = [f64; 2];

pub fn edge_use(api: &TrackApi, lines: &[f64]) -> Vec<usize> {
    let net = &api.world().net;
    let mut counts = vec![0; net.edge_alive.len()];
    for line in lines.chunks_exact(22).filter(|r| r[2] == 1.) {
        let mut seen = HashSet::new();
        for &port in net.line_path(line[0] as u32) {
            let edge = (port >> 1) as usize;
            if seen.insert(edge) {
                counts[edge] += 1;
            }
        }
    }
    counts
}

fn heading(way: &[Point]) -> Option<Point> {
    way.windows(2).find_map(|p| {
        let (x, y) = (p[1][0] - p[0][0], p[1][1] - p[0][1]);
        let len = x.hypot(y);
        (len > 1e-8).then(|| [x / len, y / len])
    })
}

// Extend the last tangent if the track ends inside the physical platform.
fn along(way: &[Point], mut distance: f64) -> Point {
    let mut direction = [1., 0.];
    for p in way.windows(2) {
        let (dx, dy) = (p[1][0] - p[0][0], p[1][1] - p[0][1]);
        let len = dx.hypot(dy);
        if len <= 1e-8 {
            continue;
        }
        direction = [dx / len, dy / len];
        if distance <= len {
            return [
                p[0][0] + direction[0] * distance,
                p[0][1] + direction[1] * distance,
            ];
        }
        distance -= len;
    }
    let last = way.last().copied().unwrap_or_default();
    [
        last[0] + direction[0] * distance,
        last[1] + direction[1] * distance,
    ]
}

fn sample(ways: &[Vec<Point>], length: f64, angle: f64) -> Vec<Point> {
    let Some(main) = ways.first() else {
        return (0..=12)
            .map(|i| {
                let t = (i as f64 / 12. - 0.5) * length;
                [t * angle.cos(), t * angle.sin()]
            })
            .collect();
    };
    let out = heading(main).unwrap_or([1., 0.]);
    let opposite = ways
        .iter()
        .skip(1)
        .filter_map(|way| {
            let h = heading(way)?;
            Some((out[0] * h[0] + out[1] * h[1], way))
        })
        .min_by(|a, b| a.0.total_cmp(&b.0))
        .map(|(_, w)| w);
    (0..=12)
        .map(|i| {
            let dist = length * i as f64 / 12.;
            match opposite {
                Some(other) if dist < length / 2. => along(other, length / 2. - dist),
                Some(_) => along(main, dist - length / 2.),
                None => along(main, dist),
            }
        })
        .collect()
}

pub fn shape(api: &TrackApi, node: usize, counts: &[usize]) -> Value {
    let net = &api.world().net;
    if net.node_berth[node].is_some() {
        return Value::Null;
    }
    let (x, y) = (net.node_x[node], net.node_y[node]);
    let frame = net.frame();
    let unit = frame.scale_at(frame.to_lonlat(x, y).1).0;
    let mut ports = net.node_ports[node].ends().to_vec();
    ports.sort_by_key(|p| (std::cmp::Reverse(counts[(p >> 1) as usize]), *p));
    let mut ways = Vec::with_capacity(ports.len());
    for p in ports {
        let mut way: Vec<_> = api
            .edge_render(p >> 1)
            .chunks_exact(3)
            .map(|q| [q[0] - x, q[1] - y])
            .collect();
        if p & 1 != 0 {
            way.reverse();
        }
        if heading(&way).is_some() {
            ways.push(way);
        }
    }
    let points = sample(
        &ways,
        f64::from(net.node_platform[node]) * unit,
        net.node_heading[node],
    );
    let offsets: Vec<i32> = points
        .iter()
        .flatten()
        .map(|p| (p * 10.).round() as i32)
        .collect();
    json!({"dm":offsets,"halfWidth":unit*6.,"upgrades":net.node_upgrades[node],"tracks":net.node_tracks(node as u32),"end":ways.len()==1})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn a_terminus_starts_at_the_bumper_and_follows_the_curve() {
        let line = sample(&[vec![[0., 0.], [0., 60.], [30., 60.]]], 120., 0.);
        assert_eq!(
            (line[0], line[6], line[12]),
            ([0., 0.], [0., 60.], [60., 60.])
        );
    }
    #[test]
    fn a_junction_spans_the_most_opposite_way() {
        let line = sample(
            &[
                vec![[0., 0.], [20., 0.]],
                vec![[0., 0.], [0., 10.]],
                vec![[0., 0.], [-10., 0.]],
            ],
            120.,
            0.,
        );
        assert_eq!(
            (line[0], line[6], line[12]),
            ([-60., 0.], [0., 0.], [60., 0.])
        );
    }
}
