//! Web stationLife.ts `read` for the station sound (map/stationSound.ts): the trains that pulled
//! in at a stop inside the camera between two clock readings. A bus uses no platform and is
//! skipped, as is the first stop of a run (where the trip starts: not an arrival).
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jfloat, jfloatArray, jlong},
    JNIEnv,
};
use trainworld_sim::track::profile;

/// x, y (relative to the camera, as train_scene), line, note (0..11), profile, departure ×100.
pub(crate) const STRIDE: usize = 6;
/// Web `MAX_GAP_S`: a longer jump of the clock (a seek, a resume) sounds nothing.
const MAX_GAP_S: f64 = 150.;

/// The first five lines of a city take C, E, G, D, A (web stationSound.ts `FIRST_LINES`).
const FIRST_LINES: [u32; 5] = [0, 4, 7, 2, 9];

/// Web `lineNote`: by rank for the first five, else from the line's number over the 12 semitones.
pub(crate) fn line_note(line: u32, rank: usize) -> u32 {
    if let Some(&n) = FIRST_LINES.get(rank) {
        return n;
    }
    let mut h = (line.wrapping_add(1)).wrapping_mul(0x9e37_79b1);
    h ^= h >> 15;
    h = h.wrapping_mul(0x85eb_ca6b);
    h ^= h >> 13;
    h % 12
}

impl World {
    /// Each line's note: the one the save keeps (web `notes`: line id → [note, city]); a line
    /// without one takes the next rank among those, in line number order, as the web gives it.
    fn line_notes(&self, lines: usize) -> Vec<u32> {
        let stored = self.header["notes"].as_object();
        let mut rank = stored.map_or(0, |m| m.values().filter(|v| v.is_array()).count());
        (0..lines)
            .map(|id| {
                stored
                    .and_then(|m| m.get(&id.to_string()))
                    .and_then(|v| v.get(0))
                    .and_then(|v| v.as_u64())
                    .map(|n| (n % 12) as u32)
                    .unwrap_or_else(|| {
                        rank += 1;
                        line_note(id as u32, rank - 1)
                    })
            })
            .collect()
    }

    pub(crate) fn train_arrivals(&mut self, prev: f64, now: f64, x: f64, y: f64, hw: f64, hh: f64) -> Vec<f32> {
        let mut out = Vec::new();
        if !(now - prev > 0.) || now - prev > MAX_GAP_S {
            return out;
        }
        let epoch = self.load_trip_hour(now);
        let model = self.api.world();
        let net = &model.net;
        let notes = self.line_notes(model.svc.lines.len());
        let hidden = self.header["hidden"].as_array();
        for trip in self.trips.chunks_exact(4) {
            let dep = epoch + f64::from(trip[1]);
            let tau = now - dep;
            if tau < 0. || now >= epoch + f64::from(trip[2]) + 60. {
                continue;
            }
            let profile = trip[0] as usize;
            let id = profile / 6;
            let run = profile / 3 % 2;
            let Some(service) = model.svc.lines.get(id) else { continue };
            if service.bus.is_some()
                || hidden.is_some_and(|ids| {
                    ids.iter()
                        .any(|v| v.as_str().and_then(|s| s.parse::<usize>().ok()) == Some(id))
                })
            {
                continue;
            }
            let Some(r) = service.runs.get(run) else { continue };
            let Some(prof) = r.prof.get(profile % 3) else { continue };
            let s_now = profile::state_at(&prof.phases, tau).0;
            let s_prev = profile::state_at(&prof.phases, (prev - dep).max(0.)).0;
            if s_now <= s_prev {
                continue;
            }
            for &at in &r.stop_s {
                // the train's offset passes the stop's (web: within 2 m of it)
                if at <= 2. || s_prev >= at - 2. || s_now < at - 2. {
                    continue;
                }
                let (px, py) = model.svc.position(net, id as u32, run, at.clamp(0., r.len));
                if (px - x).abs() > hw || (py - y).abs() > hh {
                    continue;
                }
                out.extend_from_slice(&[
                    (px - x) as f32,
                    (py - y) as f32,
                    id as f32,
                    notes.get(id).copied().unwrap_or(0) as f32,
                    profile as f32,
                    (trip[1] * 100.).round(),
                ]);
            }
        }
        out
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldSound_arrivals(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    prev: jdouble,
    now: jdouble,
    x: jfloat,
    y: jfloat,
    hw: jfloat,
    hh: jfloat,
) -> jfloatArray {
    if handle == 0 || !prev.is_finite() || !now.is_finite() {
        return std::ptr::null_mut();
    }
    let world = unsafe { &mut *(handle as *mut World) };
    let values = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        world.train_arrivals(prev, now, x.into(), y.into(), hw.into(), hh.into())
    }))
    .unwrap_or_default();
    let Ok(array) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notes_follow_the_web() {
        assert_eq!((0..5).map(|r| line_note(9, r)).collect::<Vec<_>>(), vec![0, 4, 7, 2, 9]);
        // web lineNote(3, 99): the hash over the 12 semitones, the same in Rust and JS
        for line in 0..40 {
            assert!(line_note(line, 99) < 12);
        }
        let mut h: u32 = 4u32.wrapping_mul(0x9e37_79b1);
        h ^= h >> 15;
        h = h.wrapping_mul(0x85eb_ca6b);
        h ^= h >> 13;
        assert_eq!(line_note(3, 99), h % 12);
    }

    #[test]
    fn trains_arrive_at_the_stops_in_view_only() {
        let mut world = World::workshop();
        let start = world
            .build_alignment(&[[0., 0.], [3000., 0.], [7000., 0.]], "Tiga", 111600., 0, &[])
            .unwrap()["departure"]
            .as_f64()
            .unwrap();
        let (mut at_middle, mut at_end, mut at_start) = (0, 0, 0);
        let mut prev = start;
        for step in 1..2400 {
            let now = start + f64::from(step);
            let a = world.train_arrivals(prev, now, 3500., 0., 6000., 2000.);
            prev = now;
            assert_eq!(a.len() % STRIDE, 0);
            for row in a.chunks_exact(STRIDE) {
                let x = f64::from(row[0]) + 3500.;
                assert!(row[3] < 12.);
                if (x - 3000.).abs() < 150. {
                    at_middle += 1;
                } else if (x - 7000.).abs() < 150. {
                    at_end += 1;
                } else if x.abs() < 150. {
                    at_start += 1;
                }
            }
        }
        assert!(at_middle > 0, "a train pulls in at the middle stop");
        assert!(at_end > 0 || at_start > 0, "a train reaches an end of the line");
        // the camera far away hears nothing; nor does a clock jump past MAX_GAP_S
        assert!(world.train_arrivals(start + 100., start + 400., 3500., 0., 6000., 2000.).is_empty());
        let mut none = 0;
        let mut prev = start;
        for step in 1..2400 {
            let now = start + f64::from(step);
            none += world.train_arrivals(prev, now, 1e7, 1e7, 500., 500.).len();
            prev = now;
        }
        assert_eq!(none, 0);
    }
}
