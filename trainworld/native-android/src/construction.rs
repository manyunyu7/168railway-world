// SPDX-License-Identifier: AGPL-3.0-only
use super::{TrackApi, World};
use jni::{
    objects::{JClass, JDoubleArray, JString},
    sys::{jboolean, jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use trainworld_sim::track::{geom, params::LEVEL_H};

#[derive(Clone, Copy)]
struct Target {
    kind: u32,
    id: u32,
    x: f64,
    y: f64,
}

/// Respect the layer and track family when extending rail. For stations the user taps
/// the visible network and the platform inherits the selected piece's elevation.
fn snap(api: &TrackApi, x: f64, y: f64, radius: f64, rail: Option<(i8, u8)>) -> Option<Target> {
    let net = &api.world().net;
    let mut edge = None;
    let mut nearest = radius;
    for i in 0..net.edge_count() {
        let e = i as u32;
        if !net.edge_ok(e) || !net.edge_built[i] || net.edge_crossover[i] {
            continue;
        }
        if rail.is_some_and(|(_, kind)| (api.edge_kind(e) >= 2) != (kind >= 2)) {
            continue;
        }
        let (s, d) = net.project(e, x, y);
        if d > nearest {
            continue;
        }
        if rail.is_some_and(|(level, _)| {
            (geom::height_at(net.edge_vert(e), s) / LEVEL_H).round() as i8 != level
        }) {
            continue;
        }
        let (sx, sy, _) = geom::pos_at(net.edge_pieces(e), s);
        nearest = d;
        edge = Some(Target {
            kind: 2,
            id: e,
            x: sx,
            y: sy,
        });
    }
    // A tap at a joint can project onto either touching piece. Reusing the joint
    // avoids a zero-length split, especially when placing terminal platforms.
    let e = edge?;
    let i = e.id as usize;
    let endpoint = [net.edge_a[i], net.edge_b[i]]
        .into_iter()
        .map(|id| {
            let j = id as usize;
            let d = (net.node_x[j] - x).hypot(net.node_y[j] - y);
            (id, d)
        })
        .filter(|&(id, d)| {
            d <= radius
                && d <= nearest + 12.
                && rail.is_none_or(|(level, _)| net.node_level[id as usize] == level)
        })
        .min_by(|a, b| a.1.total_cmp(&b.1));
    Some(match endpoint {
        Some((id, _)) => Target {
            kind: 1,
            id,
            x: net.node_x[id as usize],
            y: net.node_y[id as usize],
        },
        None => e,
    })
}

fn is_point(x: f64, y: f64) -> bool {
    x.is_finite() && y.is_finite() && x.abs() <= 50_000_000. && y.abs() <= 50_000_000.
}
fn finish(
    world: &mut World,
    mut next: World,
    clock: f64,
    commit: bool,
    mut result: Value,
) -> Value {
    result["cost"] = json!(
        next.header["cash"]
            .as_f64()
            .unwrap_or_else(|| world.api.cash())
            - next.api.cash()
    );
    if commit {
        next.book_service_edit(world, clock);
        next.roads_dir = world.roads_dir.clone();
        *world = next;
    }
    result
}

#[derive(Clone, Copy)]
struct TrackDesign {
    speed: f64,
    flyover_speed: f64,
    auto_ramp: bool,
    flying: bool,
}

impl World {
    fn lay_track(
        &mut self,
        points: &[f64],
        level: i32,
        kind: i32,
        tracks: i32,
        clock: f64,
        commit: bool,
        design: TrackDesign,
    ) -> Result<Value, String> {
        if !(4..=256).contains(&points.len())
            || points.len() % 2 != 0
            || !points.chunks_exact(2).all(|p| is_point(p[0], p[1]))
            || !clock.is_finite()
        {
            return Err("Pilih titik awal dan akhir rel di peta.".into());
        }
        let follows_roads = matches!(kind, 2 | 10 | 11);
        let kind = match kind {
            10 => 0,
            11 => 1,
            k => k,
        };
        if !(-3..=3).contains(&level)
            || !matches!(kind, 0..=3)
            || !matches!(tracks, 1 | 2 | 4)
            || (kind >= 2 && tracks == 4)
            || (kind == 2 && level != 0)
        {
            return Err("Kombinasi jenis, level, dan jumlah rel tidak sesuai.".into());
        }
        let clock = self.native_clock(clock);
        let mut next = self
            .edit_candidate(clock)
            .ok_or("Salinan dunia belum tersedia.")?;
        let api = &mut next.api;
        api.set_now(clock);
        api.set_route_kind(kind as u8);
        api.set_route_hsr(kind == 1);
        if ![design.speed, design.flyover_speed]
            .into_iter()
            .all(|v| (20.0..=320.0).contains(&v))
        {
            return Err("Kecepatan rancangan harus 20–320 km/jam.".into());
        }
        api.set_design_speed(design.speed);
        api.set_flyover_speed(design.flyover_speed);
        api.set_route_ramp(kind < 2 && design.auto_ramp);
        let mut endpoints = [
            points[0],
            points[1],
            points[points.len() - 2],
            points[points.len() - 1],
        ];
        let corners = if follows_roads {
            let dir = self
                .roads_dir
                .as_deref()
                .ok_or("Data jalan belum tersedia.")?;
            let street = super::bus_plan::street_route(api, points, dir, kind != 2, design.speed)?;
            let first = api.from_lonlat(street[0], street[1]);
            let last = api.from_lonlat(street[street.len() - 2], street[street.len() - 1]);
            endpoints = [first[0], first[1], last[0], last[1]];
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(8);
            let tolerances: &[f64] = if kind == 2 {
                &[10., 20., 40.]
            } else {
                &[20., 40.]
            };
            let mut fit = None;
            for &tol in tolerances {
                let mut trace = if kind == 2 {
                    api.trace_kind(&street, tol, 2, level as i8)
                } else {
                    api.trace_road(&street, tol, kind == 1, tracks == 4, level as i8)
                };
                while !trace.step() {
                    if std::time::Instant::now() > deadline {
                        return Err("Pilih lintasan jalan yang lebih pendek.".into());
                    }
                }
                let out = trace.result();
                let worst = if kind == 2 {
                    (1.5 * tol).max(45.)
                } else {
                    1.5 * tol
                };
                if out.len() >= 3 && out[0] <= worst {
                    fit = Some(out[3..].to_vec());
                    break;
                }
            }
            if kind == 2 {
                api.set_route_street(&street)
            }
            api.set_route_tangent(true);
            fit.ok_or("Tikungan terlalu rapat untuk jenis rel ini. Pilih jalan lain.")?
        } else {
            points[2..points.len() - 2]
                .chunks_exact(2)
                .flat_map(|p| [p[0], p[1], 0., level as f64])
                .collect()
        };
        let mut ends = Vec::with_capacity(10);
        for p in endpoints.chunks_exact(2) {
            let t = snap(api, p[0], p[1], 30., Some((level as i8, kind as u8))).unwrap_or(Target {
                kind: 0,
                id: 0,
                x: p[0],
                y: p[1],
            });
            ends.extend_from_slice(&[t.kind as f64, t.id as f64, t.x, t.y, level as f64]);
        }
        let flying = kind < 2 && design.flying;
        let preview = api.preview_route(&ends, &corners, tracks as u8, flying);
        let path = preview
            .get(5 + 4 * preview.get(3).copied().unwrap_or(0.) as usize..)
            .unwrap_or_default()
            .chunks_exact(3)
            .flat_map(|p| [p[0], p[1]])
            .collect::<Vec<_>>();
        if api.add_route(&ends, &corners, tracks as u8, flying) != 0 {
            return Err(format!("Rancangan rel ditolak: {}", api.issue_kinds()));
        }
        // Construct just the newly drawn blueprint, preserving other plans in imported saves.
        let edges: Vec<_> = api
            .dirty_edges()
            .into_iter()
            .filter(|&e| {
                let n = &api.world().net;
                n.edge_ok(e) && !n.edge_built[e as usize]
            })
            .collect();
        if edges.is_empty() || api.construct(&edges, &[]) != 0 {
            return Err(format!("Rel gagal dibangun: {}", api.issue_kinds()));
        }
        let length = edges
            .iter()
            .map(|&e| api.world().net.edge_len[e as usize])
            .sum::<f64>();
        Ok(finish(
            self,
            next,
            clock,
            commit,
            json!({"length":length,"path":path}),
        ))
    }

    fn place_platform(
        &mut self,
        x: f64,
        y: f64,
        radius: f64,
        name: &str,
        length: i32,
        clock: f64,
        commit: bool,
    ) -> Result<Value, String> {
        let name = name.trim();
        if !is_point(x, y)
            || !clock.is_finite()
            || !radius.is_finite()
            || radius <= 0.
            || radius > 100_000.
        {
            return Err("Ketuk rel yang sudah dibangun.".into());
        }
        if name.is_empty() || name.chars().count() > 48 || !(1..=1000).contains(&length) {
            return Err("Periksa nama stasiun dan panjang peron.".into());
        }
        let clock = self.native_clock(clock);
        let mut next = self
            .edit_candidate(clock)
            .ok_or("Salinan dunia belum tersedia.")?;
        let api = &mut next.api;
        api.set_now(clock);
        let t = snap(api, x, y, radius, None)
            .ok_or("Tidak ada rel di titik ini. Ketuk lebih dekat ke rel.")?;
        let old = api.world().net.node_platform.clone();
        if api.add_station(t.kind, t.id, t.x, t.y, length as u16, name.into()) != 0 {
            return Err(format!("Peron gagal ditempatkan: {}", api.issue_kinds()));
        }
        let n = &api.world().net;
        let node = (0..n.node_count())
            .find(|&i| {
                n.node_ok(i as u32)
                    && n.node_platform[i] > 0
                    && old.get(i).copied().unwrap_or(0) == 0
            })
            .ok_or("Peron baru tidak ditemukan.")?;
        let info = json!({"node":node,"x":n.node_x[node],"y":n.node_y[node]});
        if api.construct(&[], &[node as u32]) != 0 {
            return Err(format!("Stasiun gagal dibangun: {}", api.issue_kinds()));
        }
        Ok(finish(self, next, clock, commit, info))
    }
}

fn response(env: JNIEnv, result: Result<Value, String>) -> jstring {
    env.new_string(
        result
            .unwrap_or_else(|error| json!({"error":error}))
            .to_string(),
    )
    .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeConstruction_rail(
    env: JNIEnv,
    _: JClass,
    h: jlong,
    points: JDoubleArray,
    level: jint,
    kind: jint,
    tracks: jint,
    clock: jdouble,
    commit: jboolean,
    speed: jdouble,
    flyover_speed: jdouble,
    auto_ramp: jboolean,
    flying: jboolean,
) -> jstring {
    let result = (|| {
        let world = unsafe { (h as *mut World).as_mut() }.ok_or("Dunia belum siap.")?;
        let count = env
            .get_array_length(&points)
            .map_err(|_| "Titik rel belum tersedia.")?;
        if !(4..=256).contains(&count) {
            return Err("Pilih 2–128 titik rel.".into());
        }
        let mut values = vec![0.; count as usize];
        env.get_double_array_region(&points, 0, &mut values)
            .map_err(|_| "Titik rel belum tersedia.")?;
        world.lay_track(
            &values,
            level,
            kind,
            tracks,
            clock,
            commit != 0,
            TrackDesign {
                speed,
                flyover_speed,
                auto_ramp: auto_ramp != 0,
                flying: flying != 0,
            },
        )
    })();
    response(env, result)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeConstruction_station(
    mut env: JNIEnv,
    _: JClass,
    h: jlong,
    x: jdouble,
    y: jdouble,
    radius: jdouble,
    name: JString,
    length: jint,
    clock: jdouble,
    commit: jboolean,
) -> jstring {
    let result = (|| {
        let world = unsafe { (h as *mut World).as_mut() }.ok_or("Dunia belum siap.")?;
        let name: String = env
            .get_string(&name)
            .map_err(|_| "Nama belum tersedia.")?
            .into();
        world.place_platform(x, y, radius, &name, length, clock, commit != 0)
    })();
    response(env, result)
}

#[cfg(test)]
mod tests {
    use super::*;
    const DESIGN: TrackDesign = TrackDesign {
        speed: 160.,
        flyover_speed: 80.,
        auto_ramp: true,
        flying: false,
    };
    #[test]
    fn viaduct_then_station_preserves_stage_and_quotes() {
        let mut w = World::workshop();
        let p = [0., 0., 5000., 0.];
        let clock = 111600.;
        let quote = w.lay_track(&p, 1, 0, 2, clock, false, DESIGN).unwrap();
        assert!(quote["cost"].as_f64().unwrap() > 0.);
        assert!(w.api.edges_info().is_empty());
        assert_eq!(
            w.lay_track(&p, 1, 0, 2, clock, true, DESIGN).unwrap(),
            quote
        );
        let edges = w.api.edges_info();
        assert_eq!(edges[4], 1.);
        assert_eq!(edges[8], 1.);
        assert!(w.api.lines_info().is_empty());
        assert!(w.api.nodes_info().chunks_exact(10).all(|r| r[7] == 0.));
        let saved = w.save(clock).unwrap();
        let mut w = World::open(&saved).unwrap();
        let quote = w
            .place_platform(2500., 13., 65., "Stasiun Viaduk", 160, clock, false)
            .unwrap();
        assert_eq!(saved, w.save(clock).unwrap());
        let placed = w
            .place_platform(2500., 13., 65., "Stasiun Viaduk", 160, clock, true)
            .unwrap();
        assert_eq!(quote, placed);
        let mut w = World::open(&w.save(clock).unwrap()).unwrap();
        let r = w.api.nodes_info();
        let st = r.chunks_exact(10).find(|r| r[8] > 0.).unwrap();
        assert_eq!(st[3], 1.);
        assert_eq!(w.api.node_name(st[0] as u32), "Stasiun Viaduk");
        assert!(w.api.lines_info().is_empty());
        let saved = w.save(clock).unwrap();
        assert!(w
            .place_platform(0., 3000., 100., "Jauh", 160, clock, true)
            .is_err());
        assert_eq!(saved, w.save(clock).unwrap());
    }
    #[test]
    fn separate_blueprints_and_refused_charge_leave_the_rest_intact() {
        let clock = 111600.;
        let mut w = World::workshop();
        assert_eq!(
            w.api.add_route(
                &[0., 0., 0., 8000., 0., 0., 0., 5000., 8000., 0.],
                &[],
                2,
                false
            ),
            0
        );
        let coordinates = [0., 0., 4000., 0.];
        w.lay_track(&coordinates, -1, 0, 1, clock, true, DESIGN)
            .unwrap();
        let edges = w.api.edges_info();
        assert_eq!(edges.chunks_exact(10).filter(|r| r[4] == 0.).count(), 1);
        assert!(edges
            .chunks_exact(10)
            .any(|r| r[4] == 1. && r[3] == 1. && r[8] == -1.));
        w.header["mode"] = json!("normal");
        w.header["cash"] = json!(0.);
        w.api.set_cash(0.);
        w.api.set_unlimited(false);
        let before = w.save(clock).unwrap();
        assert!(w
            .lay_track(&[0., 4000., 3000., 4000.], 0, 0, 2, clock, true, DESIGN)
            .is_err());
        assert_eq!(before, w.save(clock).unwrap());
    }
    #[test]
    #[ignore = "requires pinned Jakarta road tiles"]
    fn sudirman_tram_uses_streets_and_survives_save() {
        let mut w = World::sandbox("jak", 106.82, -6.19);
        let dir = std::env::var("WORLD_ROADS").unwrap();
        w.roads_dir = Some(dir.into());
        let mut points = vec![];
        for (x, y) in [(106.8171, -6.1988), (106.8227, -6.18825)] {
            points.extend(w.api.from_lonlat(x, y));
        }
        let result = w
            .lay_track(&points, 0, 2, 2, 111600., true, DESIGN)
            .unwrap();
        assert!(result["path"].as_array().unwrap().len() > 6);
        assert!(result["cost"].as_f64().unwrap() > 0.);
        let reopened = World::open(&w.save(111600.).unwrap()).unwrap();
        let n = &reopened.api.world().net;
        assert!((0..n.edge_count() as u32).any(|e| n.edge_ok(e) && reopened.api.edge_kind(e) == 2));
        let mut main = World::sandbox("jak", 106.82, -6.19);
        main.roads_dir = w.roads_dir.clone();
        let mut arterial = main.api.from_lonlat(106.815126, -6.199611);
        arterial.extend(main.api.from_lonlat(106.822891, -6.188257));
        let r = main
            .lay_track(&arterial, 1, 10, 2, 111600., true, DESIGN)
            .unwrap();
        assert!(r["path"].as_array().unwrap().len() > 6);
        assert!(main.api.edges_info().chunks_exact(10).all(|r| r[8] == 1.));
    }
}
