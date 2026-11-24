// SPDX-License-Identifier: AGPL-3.0-only
//! Web stationLife.ts `arrive` (T-686): the riders at a train pulling in at a stop, from the
//! boarding account (`train_loads::Loads`), for the station's sound and the faces rising from its
//! platform (FaceLayer.kt). Who gets off is what the train carried in, plus who got on, less what
//! it carries on; who got on is the platform's crowd when the train came, plus who arrive until it
//! leaves, less who it leaves behind. Faces are sad when the train before left `CROWDED_LEFT` or
//! more riders behind, else tired when the platform's riders waited `TIRED_WAIT_S` or more on
//! average.
use crate::{train_loads::Loads, World};

/// Web `TIRED_WAIT_S`: ten minutes, orange on the crowding dot's scale.
pub(crate) const TIRED_WAIT_S: f64 = 600.;
/// Web `CROWDED_LEFT`.
pub(crate) const CROWDED_LEFT: f64 = 20.;
/// Asked per arrival: profile, departure ×100 (as `train_arrivals`), the stop's index in its run.
pub(crate) const ASK: usize = 3;
/// Answered per arrival: riders off, riders on, mood (0 happy, 1 tired, 2 sad); NaN, NaN, 0 for a
/// trip or platform the account does not know.
pub(crate) const ANSWER: usize = 3;

/// Web `arrive`'s riders for each arrival of `ask` at game time `time`.
pub(crate) fn arrival_riders(world: &World, loads: &Loads, time: f64, ask: &[f32]) -> Vec<f32> {
    let model = world.api.world();
    let mut out = Vec::with_capacity(ask.len() / ASK * ANSWER);
    for a in ask.chunks_exact(ASK) {
        let row = riders(world, model, loads, time, a[0] as usize, f64::from(a[1]) / 100., a[2] as usize);
        match row {
            Some((off, on, mood)) => out.extend_from_slice(&[off as f32, on as f32, mood as f32]),
            None => out.extend_from_slice(&[f32::NAN, f32::NAN, 0.]),
        }
    }
    out
}

fn riders(
    world: &World,
    model: &trainworld_sim::track::world::TrackWorld,
    loads: &Loads,
    time: f64,
    profile: usize,
    dep: f64,
    k: usize,
) -> Option<(f64, f64, u8)> {
    let i = loads.trip(world, profile, dep)?;
    let line = profile / 6;
    let run = profile / 3 % 2;
    let r = model.svc.lines.get(line)?.runs.get(run)?;
    let n = r.stop_s.len();
    if k >= n || n < 2 {
        return None;
    }
    let node = *r.nodes.get(*r.stop_idx.get(k)? as usize)?;
    // run 0's numbering of the stretch leaving stop `seg` of this run
    let stretch = |seg: usize| if run == 0 { seg } else { n - 2 - seg };
    let before = if k > 0 { loads.stretch(i, stretch(k - 1))? } else { 0. };
    let after = if k < n - 1 { loads.stretch(i, stretch(k))? } else { 0. };
    let (on, waited, left) = loads.boarded_at(node, line as u32, run as u32, time).unwrap_or((0., 0., 0.));
    let off = (before + on - after).max(0.);
    let mood = if left >= CROWDED_LEFT {
        2
    } else if waited >= TIRED_WAIT_S {
        1
    } else {
        0
    };
    Some((off, on, mood))
}

/// Web faces' country (T-1907, faceTones.ts): the ISO code of a zone the map's outlines found
/// (ViewportZones.place), "" for none.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_FaceLayer_country(
    mut env: jni::JNIEnv,
    _: jni::objects::JClass,
    zone: jni::objects::JString,
) -> jni::sys::jstring {
    let zone: String = env.get_string(&zone).map(Into::into).unwrap_or_default();
    env.new_string(crate::passenger_account::country_of(&zone))
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::train_arrivals::STRIDE;
    use crate::train_riders::tests::{figures, packed};

    #[test]
    fn arrivals_carry_their_riders_off_and_on() {
        let mut world = World::workshop();
        let start = world
            .build_path(&[[0., 0.], [3000., 0.], [6000., 0.], [9000., 0.]], "Uji", 111600.)
            .unwrap()["departure"]
            .as_f64()
            .unwrap();
        let line = 0u32;
        let stops = world.api.line_stops(line).len();
        assert_eq!(stops, 4);
        let lr = figures(stops, 400.);
        for d in 0..6 {
            world.api.set_riders(packed(line, &lr), d);
        }
        while world.api.boarding_step(32) {}
        let (mut seen, mut off_total, mut on_total, mut sides) = (0, 0., 0., 0);
        let mut prev = start + 60.;
        let mut loads: Option<Loads> = None;
        for step in 61..7200 {
            let now = start + f64::from(step);
            let rows = world.train_arrivals(prev, now, 4500., 0., 9000., 3000.);
            prev = now;
            if rows.is_empty() {
                continue;
            }
            if !loads.as_ref().is_some_and(|l| l.current(now)) {
                loads = Loads::new(&mut world, now);
            }
            let Some(l) = loads.as_ref() else { continue };
            let ask: Vec<f32> = rows.chunks_exact(STRIDE).flat_map(|r| [r[4], r[5], r[6]]).collect();
            let got = arrival_riders(&world, l, now, &ask);
            assert_eq!(got.len(), rows.len() / STRIDE * ANSWER);
            for (row, a) in rows.chunks_exact(STRIDE).zip(got.chunks_exact(ANSWER)) {
                // the stop's node is one of the line's, its index inside the run
                assert!(world.api.line_stops(line).contains(&(row[7] as u32)), "{row:?}");
                assert!((row[6] as usize) < stops);
                // a straight line east-west: the riders' side is across it, north or south
                assert!(row[8].abs() < 1e-3 && (row[9].abs() - 1.).abs() < 1e-3, "{row:?}");
                sides += 1;
                if a[0].is_nan() {
                    continue;
                }
                seen += 1;
                assert!(a[0] >= 0. && a[1] >= 0. && (0. ..=2.).contains(&a[2]), "{a:?}");
                off_total += f64::from(a[0]);
                on_total += f64::from(a[1]);
            }
        }
        println!("{sides} arrivals, {seen} known: {off_total:.0} off, {on_total:.0} on");
        assert!(sides > 0, "trains pull in");
        assert!(seen > 0, "the account knows the arrivals' riders");
        assert!(off_total > 1., "riders get off: {off_total}");
        assert!(on_total > 1., "riders get on: {on_total}");
        // a trip the account does not know answers NaN
        let l = loads.unwrap();
        let none = arrival_riders(&world, &l, start + 100., &[9999., 0., 1.]);
        assert!(none[0].is_nan() && none[1].is_nan());
    }
}
