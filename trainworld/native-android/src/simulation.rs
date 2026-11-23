//! Runtime clocks own a separate World. A checkpoint therefore cannot change the editor's
//! network or balance until the activity has validated and persisted the complete TWG1.
use crate::{finance, World};
use jni::{
    objects::{JByteArray, JClass},
    sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jlong},
    JNIEnv,
};
use std::{cell::RefCell, collections::HashMap};

struct Session {
    world: World,
    anchor: f64,
    wall_ms: i64,
    rate: f64,
}

impl Session {
    fn new(world: World, wall_ms: i64) -> Option<Self> {
        let anchor = world.header["clock"].as_f64()?;
        (anchor.is_finite() && anchor >= 0. && wall_ms >= 0).then_some(Self {
            world,
            anchor,
            wall_ms,
            rate: 0.,
        })
    }

    fn settle(&mut self, wall_ms: i64) -> f64 {
        let elapsed = wall_ms.saturating_sub(self.wall_ms).max(0);
        let now = self.anchor + elapsed as f64 * self.rate / 1000.;
        finance::tick(&mut self.world, now);
        now
    }

    fn set_rate(&mut self, wall_ms: i64, rate: f64) -> bool {
        if !rate.is_finite() || !(0. ..=60000.).contains(&rate) || wall_ms < self.wall_ms {
            return false;
        }
        self.anchor = self.settle(wall_ms);
        self.wall_ms = wall_ms;
        self.rate = rate;
        true
    }
}

// TrackApi owns thread-local caches. All calls for a session run on the opening thread.
// Numeric handles keep a late callback after close from dereferencing freed memory.
#[derive(Default)]
struct Sessions {
    next: i64,
    active: HashMap<i64, Session>,
}
thread_local! { static SESSIONS: RefCell<Sessions> = RefCell::new(Sessions::default()); }

fn supported(world: &World) -> bool {
    let net = &world.api.world().net;
    // These worlds have no infrastructure costs, passenger revenue or regional allocation.
    // Existing financial history is kept on the original clock until replay is supported.
    net.node_count() == 0
        && net.edge_count() == 0
        && net.line_count() == 0
        && world.header["hours"].as_array().is_none_or(|rows| {
            let hour = (world.header["clock"].as_f64().unwrap_or(0.) / 3600.).floor() as i64;
            rows.iter()
                .all(|r| r["hour"].as_i64().is_some_and(|h| h <= hour))
        })
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_supportsEditor(
    _: JNIEnv,
    _: JClass,
    editor: jlong,
) -> jboolean {
    if editor == 0 {
        return 0;
    }
    supported(unsafe { &*(editor as *const World) }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_open(
    env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    ms: jlong,
) -> jlong {
    let Some(session) = env
        .convert_byte_array(bytes)
        .ok()
        .and_then(|b| World::open(&b))
        .filter(supported)
        .and_then(|w| Session::new(w, ms))
    else {
        return 0;
    };
    SESSIONS.with_borrow_mut(|s| {
        let Some(id) = s.next.checked_add(1) else {
            return 0;
        };
        s.next = id;
        s.active.insert(id, session);
        id
    })
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_play(
    _: JNIEnv,
    _: JClass,
    id: jlong,
    ms: jlong,
    rate: jdouble,
) -> jboolean {
    SESSIONS.with_borrow_mut(|s| {
        s.active
            .get_mut(&id)
            .is_some_and(|clock| clock.set_rate(ms, rate))
    }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_pause(
    env: JNIEnv,
    class: JClass,
    id: jlong,
    ms: jlong,
) -> jboolean {
    Java_world_nativegame_SimulationEngine_play(env, class, id, ms, 0.)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_state(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    ms: jlong,
) -> jdoubleArray {
    let values = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        Some([clock.settle(ms), clock.world.api.cash()])
    });
    let result = values.and_then(|v| {
        let a = env.new_double_array(2).ok()?;
        env.set_double_array_region(&a, 0, &v).ok()?;
        Some(a.into_raw())
    });
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_snapshot(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    ms: jlong,
) -> jbyteArray {
    SESSIONS
        .with_borrow_mut(|s| {
            let clock = s.active.get_mut(&id)?;
            let t = clock.settle(ms);
            clock.world.save(t)
        })
        .and_then(|b| env.byte_array_from_slice(&b).ok())
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_close(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    SESSIONS.with_borrow_mut(|s| s.active.remove(&id));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pause_rate_changes_and_snapshots_use_one_settlement_clock() {
        let mut world = World::workshop();
        world.header["mode"] = "normal".into();
        let t = 86390.;
        world.header["clock"] = t.into();
        world.header["cash"] = 6000.into();
        world.header["loan"] = 1000.into();
        world.header["loanPay"] = 24.into();
        world.header["loanDays"] = 5.into();
        let mut s = Session::new(world, 500).unwrap();
        assert_eq!(s.settle(1000), t);
        assert!(s.set_rate(1000, 8.));
        assert_eq!(s.settle(3000), t + 16.);
        assert!(s.set_rate(3000, 2.));
        assert!(s.set_rate(5000, 0.));
        assert_eq!(s.settle(9000), t + 20.);
        assert!(!s.set_rate(899, 100.));
        let checkpoint = s.world.save(t + 20.).unwrap();
        let reopened = World::open(&checkpoint).unwrap();
        assert_eq!(reopened.header["clock"], t + 20.);
        assert!((reopened.api.cash() - (6000. - 20. / 3600.)).abs() < 1e-9);
        assert_eq!(reopened.header["hours"].as_array().unwrap().len(), 2);
    }
}
