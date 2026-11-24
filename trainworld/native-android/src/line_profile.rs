// SPDX-License-Identifier: AGPL-3.0-only
//! A rail line along its length for the line panel: web `LineProfile.tsx` (T-2734, the cross
//! section: the track's height over the ground by level, its stops) with the speed its trains run
//! at each place (the free-running profile of run 0), and web `LineTopSpeed.tsx` (T-2987: the top
//! speed reached and the line's speed limit).
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use trainworld_sim::track::{geom, net::{path_dir, path_edge}, profile};

/// Points along the line the panel draws.
const SAMPLES: usize = 240;
/// Web LineTopSpeed `STEP`, `MIN`.
const STEP: u32 = 10;
const MIN: u32 = 40;

impl World {
    /// `{len, points: [[s m, km/h, track height over the ground m]...], stops: [[s, name]...],
    /// reached, limit, kindTop}`, read west to east as the web's strip; None for a bus or ferry
    /// line or one with no route.
    pub(super) fn line_profile(&self, id: u32) -> Option<Value> {
        let api = &self.api;
        if api.is_bus(id) || api.is_ferry(id) {
            return None;
        }
        let w = api.world();
        let (net, ls) = (&w.net, w.svc.lines.get(id as usize).filter(|l| l.ok)?);
        let run = &ls.runs[0];
        if run.segs.is_empty() || !(run.len > 1.) || run.stop_s.len() < 2 {
            return None;
        }
        let len = run.len;
        let height = |s: f64| {
            let k = run.seg_off.partition_point(|&o| o <= s).saturating_sub(1).min(run.segs.len() - 1);
            let p = run.segs[k];
            let e = path_edge(p);
            let vert = net.edge_vert(e);
            if vert.is_empty() {
                return 0.;
            }
            let ds = (s - run.seg_off[k]).clamp(0., net.edge_len[e as usize]);
            geom::height_at(vert, if path_dir(p) == 0 { ds } else { net.edge_len[e as usize] - ds })
        };
        let mut points: Vec<[f64; 3]> = (0..=SAMPLES)
            .map(|i| {
                let s = len * i as f64 / SAMPLES as f64;
                [s, profile::speed_at(&run.free.phases, s) * 3.6, height(s)]
            })
            .collect();
        let mut stops: Vec<(f64, String)> = (0..run.stop_s.len())
            .map(|k| (run.stop_s[k].clamp(0., len), ls.stop_node(0, k).map(|n| api.node_name(n)).unwrap_or_default()))
            .collect();
        // T-2779: west on the left; a line that starts in the east is read backwards
        let x = |n: Option<u32>| n.map_or(0., |n| net.node_x[n as usize]);
        if x(ls.stop_node(0, 0)) > x(ls.stop_node(0, run.stop_s.len() - 1)) {
            points.reverse();
            points.iter_mut().for_each(|p| p[0] = len - p[0]);
            stops.reverse();
            stops.iter_mut().for_each(|p| p.0 = len - p.0);
        }
        let reached = api.line_stretch_speeds(id).chunks_exact(3).map(|r| r[0]).fold(0., f64::max);
        let stops: Vec<Value> = stops.into_iter().map(|(s, name)| json!([s, name])).collect();
        Some(json!({"len":len,"points":points,"stops":stops,"reached":reached,"limit":api.line_limit(id),
            "kindTop":if api.line_fast(id) { 320 } else { 160 },"step":STEP,"min":MIN}))
    }

    /// Web `edit({op: "lineLimit"})`: cap the line's trains at `kmh` everywhere, 0 for no limit.
    fn set_line_speed_limit(&mut self, id: u32, kmh: u32, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() {
            return Err("Jam permainan tidak valid.".into());
        }
        super::finance::tick(self, clock);
        self.api.set_now(clock);
        if self.api.set_line_limit(id, kmh) != 0 {
            return Err(format!("Batas laju ditolak: {}", crate::issue_text::describe(&self.api.issue_kinds())));
        }
        self.hour = i64::MIN;
        self.operating = None;
        self.line_profile(id).ok_or_else(|| "Line tidak ditemukan.".into())
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineProfileView_read(env: JNIEnv, _: JClass, handle: jlong, id: jint) -> jstring {
    let data = unsafe { (handle as *const World).as_ref() }.and_then(|w| w.line_profile(id as u32));
    string(&env, data.unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineProfileView_limit(env: JNIEnv, _: JClass, handle: jlong, id: jint, kmh: jint, clock: jdouble) -> jstring {
    let result = unsafe { (handle as *mut World).as_mut() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .and_then(|w| w.set_line_speed_limit(id as u32, kmh.max(0) as u32, clock));
    string(&env, result.unwrap_or_else(|e| json!({"error":e})))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_profile_runs_west_to_east_with_speeds_heights_and_a_limit() {
        let mut w = World::workshop();
        let t = 111600.;
        // built east to west: the panel still reads it from the west
        w.build_path(&[[6000., 0.], [3000., 0.], [0., 0.]], "Barat", t).unwrap();
        let v = w.line_profile(0).unwrap();
        let len = v["len"].as_f64().unwrap();
        assert!(len > 5000., "{len}");
        let points = v["points"].as_array().unwrap();
        assert_eq!(points.len(), SAMPLES + 1);
        assert_eq!(points[0][0], 0.);
        assert!(points.windows(2).all(|p| p[0][0].as_f64() <= p[1][0].as_f64()), "s rises along the strip");
        let top = points.iter().map(|p| p[1].as_f64().unwrap()).fold(0., f64::max);
        assert!(top > 40., "trains get going between stops: {top}");
        assert!(points.iter().all(|p| p[2].as_f64().unwrap().is_finite()));
        let stops = v["stops"].as_array().unwrap();
        assert_eq!(stops.len(), 3);
        assert!(stops[0][0].as_f64() < stops[2][0].as_f64());
        // the stop built last, at x 0 (west), comes first
        assert_eq!(stops[0][1], "Barat C");
        assert_eq!(stops[2][1], "Barat A");
        let reached = v["reached"].as_f64().unwrap();
        assert!(reached > 40. && reached <= 160., "{reached}");
        assert_eq!(v["limit"], 0);
        // the limit caps the trains everywhere, and comes off again
        let limited = w.set_line_speed_limit(0, 60, t).unwrap();
        assert_eq!(limited["limit"], 60);
        let capped = limited["points"].as_array().unwrap().iter().map(|p| p[1].as_f64().unwrap()).fold(0., f64::max);
        assert!(capped <= 60.5, "{capped}");
        assert_eq!(w.set_line_speed_limit(0, 0, t).unwrap()["limit"], 0);
        assert!(w.line_profile(99).is_none());
    }
}
