//! Bodies sampled in travel order along Studio's scheduled run. Distances are ground metres.
//! Coordinates sent to Canvas are relative to the camera so a close-up in a Mercator world
//! keeps the sub-metre precision of the engine.
use super::World;
use jni::{
    objects::JClass,
    sys::{jbyteArray, jdouble, jfloat, jfloatArray, jint, jlong},
    JNIEnv,
};
use trainworld_sim::track::profile;

pub(crate) const STRIDE: usize = 15;
struct Camera {
    x: f64,
    y: f64,
    half_width: f64,
    half_height: f64,
    detail: i32,
}

impl World {
    fn train_scene(&mut self, time: f64, view: &Camera) -> Vec<f32> {
        let mut bodies = Vec::new();
        self.train_scene_into(time, view, &mut bodies);
        bodies
    }

    /// Writes the scene into `bodies`, reusing its allocation: Canvas asks for it every frame.
    fn train_scene_into(&mut self, time: f64, view: &Camera, bodies: &mut Vec<f32>) {
        let epoch = self.load_trip_hour(time);
        let model = self.api.world();
        let net = &model.net;
        bodies.clear();
        bodies.push(0.); // The network total is independent of camera and detail.
        // Parsed once per frame rather than once per trip.
        // Hidden lines and their quad-track twins (line_visibility.rs).
        let hidden = crate::line_visibility::drawn_hidden(self);
        for trip in self.trips.chunks_exact(4) {
            let elapsed = time - epoch - f64::from(trip[1]);
            if elapsed < 0. || time >= epoch + f64::from(trip[2]) {
                continue;
            }
            bodies[0] += 1.;
            if view.detail == 0 {
                continue;
            }
            let profile = trip[0] as usize;
            let id = profile / 6;
            let run = profile / 3 % 2;
            let service = &model.svc.lines[id];
            if hidden.contains(&id) {
                continue;
            }
            let r = &service.runs[run];
            let total = service.train_len.min(r.len);
            if total <= 0. {
                continue;
            }
            let (s, _) = profile::state_at(&r.prof[profile % 3].phases, elapsed);
            // Terminal/platform clearance follows the actual stops. A through end can straddle
            // the first or last point, while a terminating train stands clear of the buffer.
            let terminal = |i: usize| {
                if service.bus.is_some() {
                    return total / 2.;
                }
                let node = r.nodes[i] as usize;
                if net.node_platform[node] > 0 && net.built_ports(r.nodes[i]) > 1 {
                    0.
                } else {
                    total / 2.
                }
            };
            let start = if r.nodes.is_empty() {
                total / 2.
            } else {
                terminal(0)
            };
            let end = if r.nodes.is_empty() {
                total / 2.
            } else {
                terminal(r.nodes.len() - 1)
            };
            let midpoint = s.clamp(start, (r.len - end).max(start));
            let position = |d: f64| model.svc.position(net, id as u32, run, d.clamp(0., r.len));
            let (x, y) = position(midpoint);
            let lat = net.frame().to_lonlat(x, y).1;
            let (ground_scale, _) = net.frame().scale_at(lat);
            let margin = ground_scale * (total + 100.);
            if (x - view.x).abs() > view.half_width + margin
                || (y - view.y).abs() > view.half_height + margin
            {
                continue;
            }
            let kind = if service.ferry {
                3
            } else if service.bus.is_some() {
                2
            } else if net.line_tram(id as u32) {
                1
            } else {
                0
            };
            let cars = if view.detail >= 2 && kind < 2 {
                service.cars.max(1)
            } else {
                1
            };
            let pitch = total / f64::from(cars);
            for car in 0..cars {
                let centre = midpoint - total / 2. + (f64::from(car) + 0.5) * pitch;
                // Extend the polyline at a through end, as lineAtX in the web train shader.
                let sample = |d: f64| {
                    if (0. ..=r.len).contains(&d) {
                        return position(d);
                    }
                    let at = d.clamp(0., r.len);
                    let inner = if d < 0. {
                        1_f64.min(r.len)
                    } else {
                        (r.len - 1.).max(0.)
                    };
                    let a = position(at);
                    let b = position(inner);
                    let f = (d - at) / (inner - at);
                    (a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f)
                };
                let a = sample(centre - pitch / 2.);
                let m = sample(centre);
                let b = sample(centre + pitch / 2.);
                let side = if kind < 2 {
                    let (edge, _, _) =
                        model
                            .svc
                            .segment_at(net, id as u32, run, centre.clamp(0., r.len));
                    if net.edge_tracks[edge as usize] == 2 {
                        if net.right_hand {
                            1.
                        } else {
                            -1.
                        }
                    } else {
                        0.
                    }
                } else {
                    0.
                };
                let flags = kind | (if car + 1 == cars { 4 } else { 0 });
                bodies.extend_from_slice(&[
                    (net.line_colour[id] & 0xffffff) as f32,
                    flags as f32,
                    (a.0 - view.x) as f32,
                    (a.1 - view.y) as f32,
                    (m.0 - view.x) as f32,
                    (m.1 - view.y) as f32,
                    (b.0 - view.x) as f32,
                    (b.1 - view.y) as f32,
                    ground_scale as f32,
                    side,
                    pitch as f32,
                    profile as f32,
                    (trip[1] * 100.).round(),
                    car as f32,
                    cars as f32,
                ]);
            }
        }
        debug_assert_eq!((bodies.len() - 1) % STRIDE, 0);
    }
}

impl World {
    /// Web TrainInspector (SPEC 8): the tapped train's line, where it heads, its next stop and how
    /// far along its run it is, for the trip `profile` departing `dep` seconds into its hour.
    fn train_info(&mut self, profile: usize, dep: f64, time: f64) -> Option<serde_json::Value> {
        let epoch = self.load_trip_hour(time);
        let model = self.api.world();
        let id = profile / 6;
        let run = profile / 3 % 2;
        let service = model.svc.lines.get(id)?;
        let r = service.runs.get(run)?;
        let elapsed = time - epoch - dep;
        if elapsed < 0. {
            return None;
        }
        let (s, _) = profile::state_at(&r.prof.get(profile % 3)?.phases, elapsed);
        let s = s.clamp(0., r.len);
        let names: Vec<String> = r
            .stop_idx
            .iter()
            .map(|&i| self.api.node_name(r.nodes[i as usize]))
            .collect();
        let next = r.stop_s.iter().position(|&x| x > s + 1.);
        let vehicle = if self.api.is_bus(id as u32) {
            "Bus"
        } else if self.api.is_ferry(id as u32) {
            "Feri"
        } else if self.api.line_tram(id as u32) {
            "Trem"
        } else {
            "Kereta"
        };
        Some(serde_json::json!({
            "line": id, "name": self.api.line_name(id as u32), "color": model.net.line_colour[id] & 0xffffff,
            "run": run, "s": s, "len": r.len, "cars": service.cars, "vehicle": vehicle,
            "towards": names.last(), "next": next.and_then(|k| names.get(k)), "nextIndex": next,
            "stops": names, "stopS": r.stop_s,
        }))
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_trainInfo(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    profile: jint,
    dep: jdouble,
    time: jdouble,
) -> jni::sys::jstring {
    if handle == 0 || profile < 0 || !time.is_finite() {
        return std::ptr::null_mut();
    }
    let world = unsafe { &mut *(handle as *mut World) };
    // A panic across JNI aborts the app (it did once on a stop past the named list): answer null instead.
    let value = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| world.train_info(profile as usize, dep, time)))
        .ok()
        .flatten()
        .unwrap_or(serde_json::Value::Null);
    env.new_string(value.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_trainScene(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
    x: jfloat,
    y: jfloat,
    hw: jfloat,
    hh: jfloat,
    detail: jint,
) -> jfloatArray {
    if handle == 0 || !time.is_finite() {
        return std::ptr::null_mut();
    }
    let world = unsafe { &mut *(handle as *mut World) };
    let values = world.train_scene(
        time,
        &Camera {
            x: x.into(),
            y: y.into(),
            half_width: hw.into(),
            half_height: hh.into(),
            detail,
        },
    );
    let Ok(array) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

thread_local! {
    /// The last frame's bodies, kept so a frame allocates nothing on the Rust side.
    static SCENE: std::cell::RefCell<Vec<f32>> = const { std::cell::RefCell::new(Vec::new()) };
}

/// `Engine.trainScene` into the caller's array, so the map's every-frame redraw allocates no new
/// Java array: answers the scene's length, and copies it only when `out` holds that many floats
/// (a longer scene asks the caller to grow `out` and ask again). -1 when there is no scene.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_TrainScene_fill(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
    x: jfloat,
    y: jfloat,
    hw: jfloat,
    hh: jfloat,
    detail: jint,
    out: jfloatArray,
) -> jint {
    if handle == 0 || !time.is_finite() || out.is_null() {
        return -1;
    }
    let world = unsafe { &mut *(handle as *mut World) };
    let out = unsafe { jni::objects::JFloatArray::from_raw(out) };
    let camera = Camera {
        x: x.into(),
        y: y.into(),
        half_width: hw.into(),
        half_height: hh.into(),
        detail,
    };
    SCENE.with_borrow_mut(|bodies| {
        world.train_scene_into(time, &camera, bodies);
        let room = env.get_array_length(&out).unwrap_or(0) as usize;
        if bodies.len() <= room && env.set_float_array_region(&out, 0, bodies).is_err() {
            return -1;
        }
        bodies.len() as jint
    })
}

/// A visual preview may inspect the network and ledger, but must never settle money.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_Engine_previewArchive(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    time: jdouble,
) -> jbyteArray {
    if handle == 0 || !time.is_finite() {
        return std::ptr::null_mut();
    }
    let world = unsafe { &*(handle as *const World) };
    world
        .save(world.native_clock(time))
        .and_then(|bytes| env.byte_array_from_slice(&bytes).ok())
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bodies_follow_a_bend_and_the_clock_even_at_a_stand() {
        let mut world = World::workshop();
        let start = world
            .build_alignment(
                &[[0., 0.], [7000., 0.]],
                "Tikungan",
                111600.,
                0,
                &[[0., 3500., 1700.]],
            )
            .unwrap()["departure"]
            .as_f64()
            .unwrap();
        let camera = |detail| Camera {
            x: 3500.,
            y: 850.,
            half_width: 5000.,
            half_height: 4000.,
            detail,
        };
        let mut found_bend = false;
        for seconds in (0..600).step_by(3) {
            let t = start + f64::from(seconds);
            let detail = world.train_scene(t, &camera(2));
            let pill = world.train_scene(t, &camera(1));
            let count = world.positions(t).len() / 3;
            assert_eq!(detail[0] as usize, count);
            assert_eq!(pill.len(), 1 + count * STRIDE);
            let cars = detail[1..].chunks_exact(STRIDE).collect::<Vec<_>>();
            for train in cars.split_inclusive(|c| c[1] as i32 & 4 != 0) {
                assert_eq!(train.len(), train[0][14] as usize);
                for (index, c) in train.iter().enumerate() {
                    assert_eq!(c[13] as usize, index);
                    assert!((19.9..20.1).contains(&c[10]));
                    assert!(f32::hypot(c[6] - c[2], c[7] - c[3]) > 19.);
                }
                for pair in train.windows(2) {
                    let (a, b) = (pair[0], pair[1]);
                    assert!(f32::hypot(a[6] - b[2], a[7] - b[3]) < 0.02);
                    let (dx, dy) = (a[6] - a[2], a[7] - a[3]);
                    let (ux, uy) = (b[6] - b[2], b[7] - b[3]);
                    found_bend |= (dx * uy - dy * ux).abs() > 1.;
                }
            }
            if found_bend {
                break;
            }
        }
        assert!(
            found_bend,
            "separate cars take separate headings on the curved rail"
        );
        // T-3420: a line hidden on the map draws no trains; the network total still counts them
        let t = start + 20.;
        let shown = world.train_scene(t, &camera(2));
        crate::line_visibility::set_hidden(&mut world, &[0]);
        let hidden = world.train_scene(t, &camera(2));
        assert!(shown.len() > 1 && hidden.len() == 1, "{} {}", shown.len(), hidden.len());
        assert_eq!(shown[0], hidden[0]);
        crate::line_visibility::set_hidden(&mut world, &[]);
        let far = Camera {
            x: 1e7,
            y: 1e7,
            half_width: 250.,
            half_height: 250.,
            detail: 2,
        };
        let t = start + 20.;
        assert_eq!(world.train_scene(t, &far).len(), 1);
        assert_eq!(world.train_scene(t, &camera(0)).len(), 1);
        let expected = world.train_scene(t, &camera(2));
        let mut reopened = World::open(&world.save(t).unwrap()).unwrap();
        assert_eq!(expected, reopened.train_scene(t, &camera(2)));
    }
}

#[cfg(test)]
mod train_info_debug {
    use super::*;
    #[test]
    #[ignore]
    fn every_trip_of_a_saved_world_answers() {
        let bytes = std::fs::read(std::env::var("WORLD_DEBUG_SAVE").unwrap()).unwrap();
        let mut w = World::open(&bytes).unwrap();
        let t0 = w.header["clock"].as_f64().unwrap_or(0.);
        for k in 0..6 {
            let t = t0 + 600. * k as f64;
            w.load_trip_hour(t);
            let trips: Vec<f32> = w.trips.clone();
            for trip in trips.chunks_exact(4) {
                let dep = (f64::from(trip[1]) * 100.).round() / 100.;
                let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| w.train_info(trip[0] as usize, dep, t)));
                if r.is_err() { println!("PANIC profile {} dep {} t {}", trip[0], dep, t); }
            }
        }
    }
}

#[cfg(test)]
mod bench {
    use super::*;
    /// `cargo test --release --lib train_scene_bench -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn train_scene_bench() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/android-lib/src/main/assets/world-native/scenarios");
        for name in ["jak", "jak_2019", "jak_tj", "jak_krl", "indo"] {
            let bytes = std::fs::read(format!("{dir}/{name}.save")).unwrap();
            let Some(mut w) = World::open(&bytes) else { println!("{name}: unreadable"); continue };
            let t0 = (w.header["clock"].as_f64().unwrap_or(0.) / 3600.).floor() * 3600. + 7. * 3600.;
            let t0 = t0 - t0.rem_euclid(86400.) + 7. * 3600.;
            let s = std::time::Instant::now();
            w.load_trip_hour(t0);
            let hour_ms = s.elapsed().as_secs_f64() * 1e3;
            let trips = w.trips.len() / 4;

            // Fit the whole network, as the opening view does.
            let g = w.geometry();
            let st = g["stations"].as_array().cloned().unwrap_or_default();
            let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
            for p in &st { let (x, y) = (p[0].as_f64().unwrap(), p[1].as_f64().unwrap()); x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y); }
            if st.is_empty() { x0 = 0.; y0 = 0.; x1 = 1.; y1 = 1.; }
            let wide = Camera { x: (x0 + x1) / 2., y: (y0 + y1) / 2., half_width: (x1 - x0) / 2. + 500., half_height: (y1 - y0) / 2. + 500., detail: 1 };
            let first = w.train_scene(t0 + 60., &wide);
            let (cx, cy) = if first.len() > STRIDE { (wide.x + f64::from(first[5]), wide.y + f64::from(first[6])) } else { (wide.x, wide.y) };
            let close = Camera { x: cx, y: cy, half_width: 1500., half_height: 3000., detail: 2 };
            for (label, cam) in [("wide", &wide), ("close", &close)] {
                let frames = 600; // 10 s at 60 fps, 1×: one game second per frame
                let s = std::time::Instant::now();
                let mut worst = 0f64; let mut bodies = 0;
                for f in 0..frames {
                    let a = std::time::Instant::now();
                    bodies = w.train_scene(t0 + 60. + f as f64, cam).len() / STRIDE;
                    worst = worst.max(a.elapsed().as_secs_f64());
                }
                let per = s.elapsed().as_secs_f64() * 1e3 / frames as f64;
                println!("{name}: trips/h {trips} hour-load {hour_ms:.1} ms | {label}: {per:.3} ms/frame (worst {:.2} ms) bodies {bodies}", worst * 1e3);
            }
        }
    }
}

#[cfg(test)]
mod same_as_before {
    use super::*;
    use trainworld_sim::track::profile;
    /// The scene as it was built before it reused one buffer and parsed `hidden` once a frame.
    impl World {
    fn train_scene_before(&mut self, time: f64, view: &Camera) -> Vec<f32> {
        let epoch = self.load_trip_hour(time);
        let model = self.api.world();
        let net = &model.net;
        let mut bodies = vec![0.]; // The network total is independent of camera and detail.
        let hidden = crate::line_visibility::drawn_hidden(self);
        for trip in self.trips.chunks_exact(4) {
            let elapsed = time - epoch - f64::from(trip[1]);
            if elapsed < 0. || time >= epoch + f64::from(trip[2]) {
                continue;
            }
            bodies[0] += 1.;
            if view.detail == 0 {
                continue;
            }
            let profile = trip[0] as usize;
            let id = profile / 6;
            let run = profile / 3 % 2;
            let service = &model.svc.lines[id];
            if hidden.contains(&id) {
                continue;
            }
            let r = &service.runs[run];
            let total = service.train_len.min(r.len);
            if total <= 0. {
                continue;
            }
            let (s, _) = profile::state_at(&r.prof[profile % 3].phases, elapsed);
            // Terminal/platform clearance follows the actual stops. A through end can straddle
            // the first or last point, while a terminating train stands clear of the buffer.
            let terminal = |i: usize| {
                if service.bus.is_some() {
                    return total / 2.;
                }
                let node = r.nodes[i] as usize;
                if net.node_platform[node] > 0 && net.built_ports(r.nodes[i]) > 1 {
                    0.
                } else {
                    total / 2.
                }
            };
            let start = if r.nodes.is_empty() {
                total / 2.
            } else {
                terminal(0)
            };
            let end = if r.nodes.is_empty() {
                total / 2.
            } else {
                terminal(r.nodes.len() - 1)
            };
            let midpoint = s.clamp(start, (r.len - end).max(start));
            let position = |d: f64| model.svc.position(net, id as u32, run, d.clamp(0., r.len));
            let (x, y) = position(midpoint);
            let lat = net.frame().to_lonlat(x, y).1;
            let (ground_scale, _) = net.frame().scale_at(lat);
            let margin = ground_scale * (total + 100.);
            if (x - view.x).abs() > view.half_width + margin
                || (y - view.y).abs() > view.half_height + margin
            {
                continue;
            }
            let kind = if service.ferry {
                3
            } else if service.bus.is_some() {
                2
            } else if net.line_tram(id as u32) {
                1
            } else {
                0
            };
            let cars = if view.detail >= 2 && kind < 2 {
                service.cars.max(1)
            } else {
                1
            };
            let pitch = total / f64::from(cars);
            for car in 0..cars {
                let centre = midpoint - total / 2. + (f64::from(car) + 0.5) * pitch;
                // Extend the polyline at a through end, as lineAtX in the web train shader.
                let sample = |d: f64| {
                    if (0. ..=r.len).contains(&d) {
                        return position(d);
                    }
                    let at = d.clamp(0., r.len);
                    let inner = if d < 0. {
                        1_f64.min(r.len)
                    } else {
                        (r.len - 1.).max(0.)
                    };
                    let a = position(at);
                    let b = position(inner);
                    let f = (d - at) / (inner - at);
                    (a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f)
                };
                let a = sample(centre - pitch / 2.);
                let m = sample(centre);
                let b = sample(centre + pitch / 2.);
                let side = if kind < 2 {
                    let (edge, _, _) =
                        model
                            .svc
                            .segment_at(net, id as u32, run, centre.clamp(0., r.len));
                    if net.edge_tracks[edge as usize] == 2 {
                        if net.right_hand {
                            1.
                        } else {
                            -1.
                        }
                    } else {
                        0.
                    }
                } else {
                    0.
                };
                let flags = kind | (if car + 1 == cars { 4 } else { 0 });
                bodies.extend_from_slice(&[
                    (net.line_colour[id] & 0xffffff) as f32,
                    flags as f32,
                    (a.0 - view.x) as f32,
                    (a.1 - view.y) as f32,
                    (m.0 - view.x) as f32,
                    (m.1 - view.y) as f32,
                    (b.0 - view.x) as f32,
                    (b.1 - view.y) as f32,
                    ground_scale as f32,
                    side,
                    pitch as f32,
                    profile as f32,
                    (trip[1] * 100.).round(),
                    car as f32,
                    cars as f32,
                ]);
            }
        }
        debug_assert_eq!((bodies.len() - 1) % STRIDE, 0);
        bodies
    }
    }

    #[test]
    fn reused_buffer_answers_exactly_what_the_fresh_scene_did() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/android-lib/src/main/assets/world-native/scenarios");
        let mut compared = 0;
        let mut buffer = Vec::new();
        for name in ["jak", "jak_2019", "jak_tj", "jak_krl", "indo"] {
            let mut w = World::open(&std::fs::read(format!("{dir}/{name}.save")).unwrap()).unwrap();
            let day = (w.header["clock"].as_f64().unwrap_or(0.) / 86400.).floor() * 86400.;
            // Hide one line, as the line list's eye does, so that path is covered too.
            w.header["hidden"] = serde_json::json!(["0"]);
            let g = w.geometry();
            let st = g["stations"].as_array().cloned().unwrap_or_default();
            let Some(p) = st.first() else { continue };
            let (x, y) = (p[0].as_f64().unwrap(), p[1].as_f64().unwrap());
            for (hw, detail) in [(4e5, 1), (3e4, 1), (3000., 2), (800., 2), (4e5, 0)] {
                let cam = Camera { x, y, half_width: hw, half_height: hw * 2., detail };
                // Across an hour boundary, so the trip list reload is covered.
                for k in 0..90 {
                    let t = day + 7. * 3600. + 3000. + 20. * f64::from(k);
                    let before = w.train_scene_before(t, &cam);
                    w.train_scene_into(t, &cam, &mut buffer);
                    assert_eq!(before, buffer, "{name} t {t} detail {detail}");
                    compared += before.len();
                }
            }
        }
        assert!(compared > 100_000, "compared {compared} floats");
    }
}
