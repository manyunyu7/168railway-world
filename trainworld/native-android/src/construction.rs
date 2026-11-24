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
fn snap(
    api: &TrackApi,
    x: f64,
    y: f64,
    radius: f64,
    rail: Option<(i8, u8)>,
    include_plans: bool,
    allow_node: bool,
) -> Option<Target> {
    let net = &api.world().net;
    let mut edge = None;
    let mut nearest = radius;
    for i in 0..net.edge_count() {
        let e = i as u32;
        if !net.edge_ok(e) || (!include_plans && !net.edge_built[i]) || net.edge_crossover[i] {
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
    if !allow_node {
        return Some(e);
    }
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
struct TrackDesign<'a> {
    profile: &'a [f64],
    speed: f64,
    flyover_speed: f64,
    auto_ramp: bool,
    flying: bool,
    radius: f64,
    deferred: bool,
    preview_only: bool,
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
        design: TrackDesign<'_>,
    ) -> Result<Value, String> {
        if !(4..=256).contains(&points.len())
            || points.len() % 2 != 0
            || !points.chunks_exact(2).all(|p| is_point(p[0], p[1]))
            || !clock.is_finite()
        {
            return Err("Pilih titik awal dan akhir rel di peta.".into());
        }
        // Each tap retains its own elevation, corner radius and endpoint attachment choice.
        if !design.profile.is_empty()
            && (design.profile.len() != points.len() / 2 * 3
                || design.profile.chunks_exact(3).any(|p| {
                    !(-3.0..=3.0).contains(&p[0])
                        || p[0].fract() != 0.
                        || !(0.0..=100000.).contains(&p[1])
                        || !matches!(p[2], 0. | 1. | 2.)
                        || (kind == 2 && p[0] != 0.)
                }))
        {
            return Err("Periksa elevasi, radius dan sambungan titik rel.".into());
        }
        let at = |i: usize| {
            design
                .profile
                .get(3 * i..3 * i + 3)
                .map(|v| (v[0] as i8, v[1], v[2] as u8))
                .unwrap_or((level as i8, design.radius, 1))
        };
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
        api.set_route_tangent(true);
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
            fit.ok_or("Tikungan terlalu rapat untuk jenis rel ini. Pilih jalan lain.")?
        } else {
            points[2..points.len() - 2]
                .chunks_exact(2)
                .enumerate()
                .flat_map(|(i, p)| {
                    let (z, r, _) = at(i + 1);
                    [p[0], p[1], r, z as f64]
                })
                .collect()
        };
        let mut ends = Vec::with_capacity(10);
        for (i, p) in endpoints.chunks_exact(2).enumerate() {
            let (z, _, connect) = at(if i == 0 { 0 } else { points.len() / 2 - 1 });
            let t = if connect != 0 {
                snap(
                    api,
                    p[0],
                    p[1],
                    30.,
                    Some((z, kind as u8)),
                    design.deferred,
                    connect != 2,
                )
            } else {
                None
            }
            .unwrap_or(Target {
                kind: 0,
                id: 0,
                x: p[0],
                y: p[1],
            });
            ends.extend_from_slice(&[t.kind as f64, t.id as f64, t.x, t.y, z as f64]);
        }
        let flying = kind < 2 && design.flying;
        let preview = api.preview_route(&ends, &corners, tracks as u8, flying);
        let path = preview
            .get(5 + 4 * preview.get(3).copied().unwrap_or(0.) as usize..)
            .unwrap_or_default()
            .chunks_exact(3)
            .flat_map(|p| [p[0], p[1]])
            .collect::<Vec<_>>();
        let curve = preview
            .get(5..5 + 4 * preview[3] as usize)
            .unwrap_or_default()
            .chunks_exact(4)
            .filter(|v| v[2] > 0.)
            .min_by(|a, b| a[2].total_cmp(&b[2]));
        if design.preview_only {
            let vertices = &preview[5..5 + 4 * preview[3] as usize];
            let tags: Vec<_> = vertices.chunks_exact(4).filter(|v| v[2] > 0.).collect();
            let tips: Vec<_> = ends
                .chunks_exact(5)
                .map(|e| json!([e[2], e[3], e[0]]))
                .collect();
            let heights = &preview[5 + 4 * preview[3] as usize..];
            let mut max_grade: f64 = 0.;
            let mut grade_mark = None;
            for segment in heights.windows(6).step_by(3) {
                let run = (segment[3] - segment[0]).hypot(segment[4] - segment[1]);
                if run > 0.01 {
                    let grade = (segment[5] - segment[2]) / run * 100.;
                    if grade.abs() > max_grade {
                        max_grade = grade.abs();
                        grade_mark = Some([segment[3], segment[4], grade]);
                    }
                }
            }
            let issue =
                (preview[0] == 0.).then(|| format!("Rancangan rel ditolak: {}", api.issue_kinds()));
            return Ok(
                json!({"path":path,"marks":api.preview_marks(),"issue":issue,"length":preview[2],"cost":preview[1],
                "tags":tags,"tips":tips,"arc":api.route_arc(),"ramp":grade_mark,"grade":max_grade,"radius":curve.map(|v|v[2]),"speed":curve.map(|v|v[3])}),
            );
        }
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
        if edges.is_empty() || (!design.deferred && api.construct(&edges, &[]) != 0) {
            return Err(format!("Rel gagal dibangun: {}", api.issue_kinds()));
        }
        let length = edges
            .iter()
            .map(|&e| api.world().net.edge_len[e as usize])
            .sum::<f64>();
        if let [.., x, y] = path.as_slice() {
            next.header["androidTrackEnd"] = json!([x, y, level, kind, tracks]);
        }
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
        deferred: bool,
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
        let t = snap(api, x, y, radius, None, deferred, true)
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
        if !deferred && api.construct(&[], &[node as u32]) != 0 {
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
    radius: jdouble,
    deferred: jboolean,
    preview_only: jboolean,
    profile: JDoubleArray,
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
        let n = env
            .get_array_length(&profile)
            .map_err(|_| "Profil rel tidak valid.")?;
        if n != count / 2 * 3 {
            return Err("Profil titik rel tidak lengkap.".into());
        }
        let mut profile_values = vec![0.; n as usize];
        env.get_double_array_region(&profile, 0, &mut profile_values)
            .map_err(|_| "Profil titik rel belum tersedia.")?;
        world.lay_track(
            &values,
            level,
            kind,
            tracks,
            clock,
            commit != 0,
            TrackDesign {
                profile: &profile_values,
                speed,
                flyover_speed,
                auto_ramp: auto_ramp != 0,
                flying: flying != 0,
                radius,
                deferred: deferred != 0,
                preview_only: preview_only != 0,
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
    deferred: jboolean,
) -> jstring {
    let result = (|| {
        let world = unsafe { (h as *mut World).as_mut() }.ok_or("Dunia belum siap.")?;
        let name: String = env
            .get_string(&name)
            .map_err(|_| "Nama belum tersedia.")?
            .into();
        world.place_platform(
            x,
            y,
            radius,
            &name,
            length,
            clock,
            commit != 0,
            deferred != 0,
        )
    })();
    response(env, result)
}

impl World {
    fn build_rail_plans(&mut self, clock: f64, commit: bool) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        let mut next = self.edit_candidate(clock).ok_or("Dunia belum siap.")?;
        let n = &next.api.world().net;
        let edges: Vec<_> = (0..n.edge_count() as u32)
            .filter(|&e| n.edge_ok(e) && !n.edge_built[e as usize] && !n.edge_off[e as usize])
            .collect();
        let stations: Vec<_> = (0..n.node_count() as u32)
            .filter(|&s| {
                n.node_ok(s)
                    && n.is_station(s)
                    && !n.node_built[s as usize]
                    && !n.node_off[s as usize]
            })
            .collect();
        let quote = next.api.construct_cost(&edges, &stations, &[]);
        let summary = json!({"edges":edges.len(),"stations":stations.len(),"cost":quote});
        if !commit {
            return Ok(summary);
        }
        if next.api.construct(&edges, &stations) != 0 {
            return Err(format!(
                "Rencana gagal dibangun: {}",
                next.api.issue_kinds()
            ));
        }
        Ok(finish(self, next, clock, true, summary))
    }
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeConstruction_buildPlans(
    env: JNIEnv,
    _: JClass,
    h: jlong,
    clock: jdouble,
    commit: jboolean,
) -> jstring {
    let result = unsafe { (h as *mut World).as_mut() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .and_then(|w| w.build_rail_plans(clock, commit != 0));
    response(env, result)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeConstruction_platformPoint(
    env: JNIEnv,
    _: JClass,
    h: jlong,
    x: jdouble,
    y: jdouble,
    radius: jdouble,
    plans: jboolean,
) -> jstring {
    let result = unsafe { (h as *const World).as_ref() }
        .and_then(|w| snap(&w.api, x, y, radius, None, plans != 0, true))
        .map(|t| json!({"x":t.x,"y":t.y}))
        .ok_or_else(|| "Ketuk lebih dekat ke rel.".to_string());
    response(env, result)
}
/// Touch picking uses screen-space tolerances. Nodes take precedence over the track under them.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeConstruction_railPoint(
    env: JNIEnv,
    _: JClass,
    h: jlong,
    x: jdouble,
    y: jdouble,
    unit: jdouble,
    family: jint,
    level: jint,
) -> jstring {
    let result = unsafe { (h as *const World).as_ref() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .map(|w| json!(rail_touch_point(&w.api, x, y, unit, family, level)));
    response(env, result)
}

fn rail_touch_point(
    api: &TrackApi,
    x: f64,
    y: f64,
    unit: f64,
    family: i32,
    level: i32,
) -> [f64; 4] {
    let net = &api.world().net;
    let mut nearest = 12. * unit;
    let mut target = [x, y, 0., level as f64];
    for n in 0..net.node_count() {
        if !net.node_ok(n as u32)
            || net.node_ports[n].n == 0
            || (api.node_kind(n as u32) >= 2) != (family >= 2)
        {
            continue;
        }
        let distance = (net.node_x[n] - x).hypot(net.node_y[n] - y);
        if distance < nearest {
            nearest = distance;
            target = [net.node_x[n], net.node_y[n], 1., net.node_level[n] as f64];
        }
    }
    if target[2] == 1. {
        return target;
    }
    nearest = 8. * unit;
    for i in 0..net.edge_count() {
        let e = i as u32;
        if !net.edge_ok(e) || net.edge_crossover[i] || (api.edge_kind(e) >= 2) != (family >= 2) {
            continue;
        }
        let (s, d) = net.project(e, x, y);
        if d < nearest {
            nearest = d;
            let (sx, sy, _) = geom::pos_at(net.edge_pieces(e), s);
            let z = (geom::height_at(net.edge_vert(e), s) / LEVEL_H).round();
            target = [sx, sy, 2., z];
        }
    }
    target
}
#[cfg(test)]
mod tests {
    use super::*;
    const DESIGN: TrackDesign<'static> = TrackDesign {
        profile: &[],
        speed: 160.,
        flyover_speed: 80.,
        auto_ramp: true,
        flying: false,
        radius: 0.,
        deferred: false,
        preview_only: false,
    };
    #[test]
    fn touch_snap_distinguishes_nodes_track_and_open_ground_at_each_zoom() {
        let mut world = World::workshop();
        world
            .lay_track(&[0., 0., 4000., 0.], 1, 0, 2, 111600., true, DESIGN)
            .unwrap();
        assert_eq!(
            rail_touch_point(&world.api, 10., 2., 1., 0, 0),
            [0., 0., 1., 1.]
        );
        assert_eq!(
            rail_touch_point(&world.api, 2000., 6., 1., 0, 0),
            [2000., 0., 2., 1.]
        );
        assert_eq!(
            rail_touch_point(&world.api, 2000., 6., 0.5, 0, 0),
            [2000., 6., 0., 0.]
        );
        assert_eq!(
            rail_touch_point(&world.api, 0., 0., 1., 3, 0),
            [0., 0., 0., 0.]
        );
    }

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
            .place_platform(2500., 13., 65., "Stasiun Viaduk", 160, clock, false, false)
            .unwrap();
        assert_eq!(saved, w.save(clock).unwrap());
        let placed = w
            .place_platform(2500., 13., 65., "Stasiun Viaduk", 160, clock, true, false)
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
            .place_platform(0., 3000., 100., "Jauh", 160, clock, true, false)
            .is_err());
        assert_eq!(saved, w.save(clock).unwrap());
    }
    #[test]
    fn curved_plan_survives_reopen_and_is_charged_only_when_built() {
        let mut w = World::workshop();
        let clock = 111600.;
        let cash = w.api.cash();
        let design = TrackDesign {
            deferred: true,
            radius: 800.,
            ..DESIGN
        };
        let points = [0., 0., 4000., 0., 8000., 2000.];
        let preview = w
            .lay_track(
                &points,
                1,
                0,
                2,
                clock,
                false,
                TrackDesign {
                    preview_only: true,
                    ..design
                },
            )
            .unwrap();
        assert!(preview["radius"].as_f64().unwrap() > 0.);
        assert!(w.api.edges_info().is_empty());
        let planned = w.lay_track(&points, 1, 0, 2, clock, true, design).unwrap();
        assert_eq!(planned["path"], preview["path"]);
        w.place_platform(2000., 8., 30., "Pusat", 160, clock, true, true)
            .unwrap();
        let mut reopened = World::open(&w.save(clock).unwrap()).unwrap();
        assert_eq!(cash, reopened.api.cash());
        let quote = reopened.build_rail_plans(clock, false).unwrap();
        assert_eq!(quote["stations"], 1);
        assert!(quote["cost"].as_f64().unwrap() > 0.);
        let built = reopened.build_rail_plans(clock, true).unwrap();
        assert_eq!(
            reopened
                .api
                .edges_info()
                .chunks_exact(10)
                .filter(|e| e[4] == 0.)
                .count(),
            0
        );
        assert!((built["cost"].as_f64().unwrap() - quote["cost"].as_f64().unwrap()).abs() < 1e-6);
        assert!(reopened.build_rail_plans(clock, true).is_err());
    }
    #[test]
    fn per_point_levels_build_a_ramp_and_preserve_endpoint_choice() {
        let mut world = World::workshop();
        let time = 111600.;
        let baseline = [0., 0., 5000., 0.];
        world
            .lay_track(&baseline, 0, 0, 2, time, true, DESIGN)
            .unwrap();
        for connect in [false, true] {
            let mut copy = World::open(&world.save(time).unwrap()).unwrap();
            let profile = [0., 0., if connect { 1. } else { 0. }, 1., 0., 0.];
            let design = TrackDesign {
                profile: &profile,
                ..DESIGN
            };
            let points = [5000., 8., 10000., 1000.];
            let preview = copy
                .lay_track(
                    &points,
                    1,
                    0,
                    2,
                    time,
                    false,
                    TrackDesign {
                        preview_only: true,
                        ..design
                    },
                )
                .unwrap();
            assert!(preview["grade"].as_f64().unwrap() > 0.);
            copy.lay_track(&points, 1, 0, 2, time, true, design)
                .unwrap();
            let joints = copy
                .api
                .nodes_info()
                .chunks_exact(10)
                .filter(|r| r[4] > 1.)
                .count();
            assert_eq!(joints > 0, connect);
        }
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
