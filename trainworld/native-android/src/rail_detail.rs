//! What Android's rail layer (`RailLayer.kt`) needs beyond an edge's centre line to draw track the
//! way the web does close in (web `map/renderer.ts` T-658, `network.ts`): which edges are a
//! crossover's diagonals (drawn as one track between the pairs they join, never parted into two),
//! and along a ramp its height per drawn point, so the viaduct's edges and the tunnel's shading start
//! and stop where the track leaves the ground rather than at the edge's end.

use std::collections::HashSet;
use trainworld_sim::track::params::LEVEL_H;
use trainworld_sim::track::wasm::TrackApi;

/// The edges that are one diagonal of a crossover (web `TrackEdge.crossover`, T-338).
pub(crate) fn crossovers(api: &TrackApi) -> HashSet<u32> {
    api.crossover_edges().into_iter().collect()
}

/// Per point of `points` (x, y pairs, `edge_polyline`), the track's height there in tenths of a
/// level (a level is `LEVEL_H` m), from the nearest of the edge's render samples; None for an edge
/// that keeps one level throughout, whose `level` says it all.
pub(crate) fn heights(api: &TrackApi, edge: u32, low: i32, high: i32, points: &[i32]) -> Option<Vec<i32>> {
    if low == high {
        return None;
    }
    let samples = api.edge_render(edge);
    if samples.len() < 3 {
        return None;
    }
    Some(
        points
            .chunks_exact(2)
            .map(|p| {
                let (x, y) = (p[0] as f64, p[1] as f64);
                let z = samples
                    .chunks_exact(3)
                    .min_by(|a, b| {
                        let da = (a[0] - x).powi(2) + (a[1] - y).powi(2);
                        let db = (b[0] - x).powi(2) + (b[1] - y).powi(2);
                        da.total_cmp(&db)
                    })
                    .map_or(0., |s| s[2]);
                (z / LEVEL_H * 10.).round() as i32
            })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use crate::World;
    use serde_json::Value;

    fn free(x: f64, y: f64, level: f64) -> [f64; 5] {
        [0., 0., x, y, level]
    }
    fn route(w: &mut World, a: [f64; 5], b: [f64; 5], tracks: u8) {
        let ends: Vec<f64> = a.iter().chain(b.iter()).copied().collect();
        assert_eq!(w.api.add_route(&ends, &[], tracks, false), 0, "{}", w.api.issue_kinds());
    }
    fn tracks(w: &World) -> Vec<Value> {
        w.geometry()["tracks"].as_array().unwrap().clone()
    }

    #[test]
    fn a_ramp_carries_its_height_per_point_and_flat_track_does_not() {
        let mut w = World::workshop();
        route(&mut w, free(0., 0., 0.), free(3000., 0., 0.), 2);
        route(&mut w, free(0., 2000., 0.), free(4000., 2000., 2.), 2);
        let rows = tracks(&w);
        let flat: Vec<_> = rows.iter().filter(|t| t["level"] == t["topLevel"]).collect();
        let ramps: Vec<_> = rows.iter().filter(|t| t["level"] != t["topLevel"]).collect();
        assert!(!flat.is_empty() && !ramps.is_empty(), "{rows:?}");
        assert!(flat.iter().all(|t| t.get("heights").is_none()));
        for t in ramps {
            let h: Vec<i64> = t["heights"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap()).collect();
            assert_eq!(h.len() * 2, t["points"].as_array().unwrap().len());
            let (lo, hi) = (t["level"].as_i64().unwrap() * 10, t["topLevel"].as_i64().unwrap() * 10);
            assert!(h.iter().all(|&z| z >= lo - 5 && z <= hi + 5), "{h:?} within {lo}..{hi}");
            // the ramp climbs: one end low, the other high
            assert!(h.first() != h.last(), "{h:?}");
        }
        assert!(rows.iter().all(|t| t["crossover"] == false));
    }

    #[test]
    fn a_crossover_is_marked_and_its_pairs_stay_double() {
        let mut w = World::workshop();
        route(&mut w, free(0., 0., 0.), free(6000., 0., 0.), 2);
        let gap = 12.; // ground m at y 0, where the Mercator frame's k is 1
        route(&mut w, free(0., gap, 0.), free(6000., gap, 0.), 2);
        let on = w.api.edges_info().chunks_exact(10).map(|r| r[0] as u32).find(|&e| {
            let p = w.api.edge_polyline(e, 0.);
            p.chunks_exact(2).all(|q| q[1].abs() < 1.)
        });
        assert_eq!(w.api.add_crossover(on.unwrap(), 3000., 0.), 0, "{}", w.api.issue_kinds());
        let rows = tracks(&w);
        let marked: Vec<_> = rows.iter().filter(|t| t["crossover"] == true).collect();
        assert_eq!(marked.len(), 2, "two diagonals: {rows:?}");
        assert!(rows.iter().filter(|t| t["crossover"] == false).all(|t| t["tracks"] == 2));
    }
}
