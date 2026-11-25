//! Runtime clocks own a separate World. A checkpoint therefore cannot change the editor's
//! network or balance until the activity has validated and persisted the complete TWG1.
use crate::{finance, World};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jlong},
    JNIEnv,
};
use std::{cell::RefCell, collections::HashMap};

struct Session {
    world: World,
    passengers: Option<crate::timed_account::Account>,
    loads: Option<crate::train_loads::Loads>,
    forecast: Option<std::sync::Arc<crate::passenger_account::Forecast>>,
    anchor: f64,
    wall_ms: i64,
    rate: f64,
}

impl Session {
    fn new(mut world: World, wall_ms: i64) -> Option<Self> {
        finance::prepare_session(&mut world);
        let anchor = world.header["clock"].as_f64()?;
        (anchor.is_finite() && anchor >= 0. && wall_ms >= 0).then_some(Self {
            world,
            passengers: None,
            loads: None,
            forecast: None,
            anchor,
            wall_ms,
            rate: 0.,
        })
    }

    fn settle(&mut self, wall_ms: i64) -> f64 {
        let elapsed = wall_ms.saturating_sub(self.wall_ms).max(0);
        let now = (self.anchor + elapsed as f64 * self.rate / 1000.)
            .max(self.world.header["clock"].as_f64().unwrap_or(self.anchor));
        finance::tick_with_account(&mut self.world, now, self.passengers.as_mut());
        now
    }

    /// Hot swap after an edit: this runtime was opened from the edited world at the edit's
    /// clock while the old runtime kept the trains moving. It takes over at the old one's game
    /// time `time`; the hours in between are settled here, once, with the edited network and its
    /// riders (the old runtime's ledger is dropped, never saved), so nothing is counted twice and
    /// no hour is skipped. A `time` behind this runtime's own clock changes nothing.
    fn catch_up(&mut self, wall_ms: i64, time: f64) -> bool {
        if !time.is_finite() || wall_ms < self.wall_ms {
            return false;
        }
        let now = self.settle(wall_ms);
        self.anchor = now.max(time);
        self.wall_ms = wall_ms;
        self.settle(wall_ms);
        true
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
    net.node_count() == 0 && net.edge_count() == 0 && net.line_count() == 0
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
    register(session)
}

fn register(session: Session) -> jlong {
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
pub extern "system" fn Java_world_nativegame_SimulationEngine_openNetwork(
    mut env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    manifest: JString,
    ms: jlong,
) -> jlong {
    let open = || -> Result<Session, String> {
        let archive = env.convert_byte_array(bytes).map_err(|e| e.to_string())?;
        let text: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        let files: crate::demand::DemandFiles =
            serde_json::from_str(&text).map_err(|e| e.to_string())?;
        let mut world = World::open(&archive).ok_or("Simpanan tidak dapat dibaca")?;
        let (account, forecast) = crate::demand::reporting_open(|| {
            files.read()?.account_and_forecast(&archive, &mut world)
        })?;
        let mut session = Session::new(world, ms).ok_or("Jam simpanan tidak valid")?;
        session.passengers = Some(account);
        session.forecast = Some(forecast);
        Ok(session)
    };
    match open() {
        Ok(session) => register(session),
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            0
        }
    }
}

/// How far the `openNetwork` running now (or the last one) has come, 0 to 1: its forecast's rounds
/// done over all of them (`demand::open_progress`), for the clock's "Menyiapkan penumpang" percent.
/// Any thread may ask while the open works on its own.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_openProgress(
    _: JNIEnv,
    _: JClass,
) -> jni::sys::jfloat {
    crate::demand::open_progress()
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_loads(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    time: f64,
) -> jni::sys::jintArray {
    let rows = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        clock.passengers.as_ref()?;
        if !clock.loads.as_ref().is_some_and(|l| l.current(time)) {
            clock.loads = crate::train_loads::Loads::new(&mut clock.world, time);
        }
        Some(clock.loads.as_ref()?.rows(&clock.world, time))
    });
    rows.and_then(|r| {
        let a = env.new_int_array(i32::try_from(r.len()).ok()?).ok()?;
        env.set_int_array_region(&a, 0, &r).ok()?;
        Some(a.into_raw())
    })
    .unwrap_or(std::ptr::null_mut())
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

/// See `Session::catch_up`.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_catchUp(
    _: JNIEnv,
    _: JClass,
    id: jlong,
    ms: jlong,
    time: jdouble,
) -> jboolean {
    SESSIONS.with_borrow_mut(|s| {
        s.active
            .get_mut(&id)
            .is_some_and(|clock| clock.catch_up(ms, time))
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

/// Web stationLife `arrive` (`station_life::arrival_riders`): riders off, on and the faces' mood
/// for each arrival of `ask` (profile, departure ×100, stop index) at game time `time`; null
/// before the riders are in.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_arrivalRiders(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    time: jdouble,
    ask: jni::sys::jfloatArray,
) -> jni::sys::jfloatArray {
    let ask = unsafe { jni::objects::JFloatArray::from_raw(ask) };
    let Ok(n) = env.get_array_length(&ask) else { return std::ptr::null_mut() };
    let mut query = vec![0f32; n.max(0) as usize];
    if env.get_float_array_region(&ask, 0, &mut query).is_err() || !time.is_finite() {
        return std::ptr::null_mut();
    }
    let values = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        clock.passengers.as_ref()?;
        if !clock.loads.as_ref().is_some_and(|l| l.current(time)) {
            clock.loads = crate::train_loads::Loads::new(&mut clock.world, time);
        }
        Some(crate::station_life::arrival_riders(&clock.world, clock.loads.as_ref()?, time, &query))
    });
    values
        .and_then(|v| {
            let a = env.new_float_array(i32::try_from(v.len()).ok()?).ok()?;
            env.set_float_array_region(&a, 0, &v).ok()?;
            Some(a.into_raw())
        })
        .unwrap_or(std::ptr::null_mut())
}

/// Web TrainInspector OnBoard (`train_riders::train`): who is aboard the trip `profile` leaving
/// `dep` s into its hour, at game time `time`; JSON, `null` before the riders are in.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_trainRiders(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    profile: jni::sys::jint,
    dep: jdouble,
    time: jdouble,
    seed: jlong,
) -> jni::sys::jstring {
    let value = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        clock.passengers.as_ref()?;
        if profile < 0 || !time.is_finite() {
            return None;
        }
        if !clock.loads.as_ref().is_some_and(|l| l.current(time)) {
            clock.loads = crate::train_loads::Loads::new(&mut clock.world, time);
        }
        let line = profile as u32 / 6;
        let riders = clock.forecast.as_ref().and_then(|f| f.line_riders(line));
        crate::train_riders::train(
            &mut clock.world,
            clock.loads.as_ref()?,
            riders.as_ref(),
            profile as usize,
            dep,
            time,
            seed as u64,
        )
    });
    let text = value.unwrap_or(serde_json::Value::Null).to_string();
    env.new_string(text)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// Web StationInspector BoardingsToday and PurposeSplit (`train_riders::station`) for the station
/// complex of `node`, at game time `time`; JSON, `null` before the riders are in.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_stationRiders(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    node: jni::sys::jint,
    time: jdouble,
) -> jni::sys::jstring {
    let value = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        clock.passengers.as_ref()?;
        let forecast = clock.forecast.clone()?;
        (node >= 0 && time.is_finite()).then(|| {
            let mut v = crate::train_riders::station(&clock.world, node as u32, time, &|l| {
                forecast.line_riders(l)
            });
            v["transfers"] = crate::train_riders::transfers_at(&clock.world, node as u32, &forecast.transfers()).into();
            v
        })
    });
    let text = value.unwrap_or(serde_json::Value::Null).to_string();
    env.new_string(text)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// Web LineInspector's stop rows (`train_riders::line`) for line `line` at game time `time`;
/// JSON, `null` before the riders are in.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_lineRiders(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    line: jni::sys::jint,
    time: jdouble,
) -> jni::sys::jstring {
    let value = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        clock.passengers.as_ref()?;
        let forecast = clock.forecast.clone()?;
        (line >= 0 && time.is_finite()).then(|| {
            let riders = forecast.line_riders(line as u32);
            crate::train_riders::line(&clock.world, line as u32, time, riders.as_ref())
        })
    });
    let text = value.unwrap_or(serde_json::Value::Null).to_string();
    env.new_string(text)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

/// Web LinesTab's figures for every line at game time `time` (`line_summary::summary`); JSON,
/// `null` before the riders are in.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_SimulationEngine_lineSummary(
    env: JNIEnv,
    _: JClass,
    id: jlong,
    time: jdouble,
) -> jni::sys::jstring {
    let value = SESSIONS.with_borrow_mut(|s| {
        let clock = s.active.get_mut(&id)?;
        if !time.is_finite() {
            return None;
        }
        let world = &mut clock.world;
        if world.operating.is_none() {
            world.operating = Some(crate::operating::Costs::new(&mut world.api));
        }
        let account = clock.passengers.as_ref()?;
        let costs = world.operating.as_ref()?;
        Some(crate::line_summary::summary(
            world,
            time,
            |l| account.day_fares(l, time),
            |l| {
                let country = account.country(l);
                crate::line_summary::past_day_running(time, |epoch| costs.line_hour(l, epoch, country))
            },
        ))
    });
    let text = value.unwrap_or(serde_json::Value::Null).to_string();
    env.new_string(text)
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
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

    fn running_world() -> World {
        let mut world = World::workshop();
        let t = 111600.;
        world
            .build_service([0., 0.], [3500., 600.], "Hudson", t)
            .unwrap();
        world.header["mode"] = "normal".into();
        world.header["clock"] = t.into();
        world.header["cash"] = 50_000.into();
        world
    }

    #[test]
    fn hot_swap_settles_the_gap_once_with_the_edited_world() {
        let edited = running_world();
        let t1 = edited.header["clock"].as_f64().unwrap();
        let bytes = edited.save(t1).unwrap();
        // the runtime that ran on without a swap: the reference ledger
        let mut steady = Session::new(World::open(&bytes).unwrap(), 0).unwrap();
        assert!(steady.set_rate(0, 240.));
        let t2 = steady.settle(60_000); // 4 game hours later
        assert_eq!(t2, t1 + 4. * 3600.);
        // the new runtime opened (paused) at the edit's clock, ready after a 20 s warm
        let mut fresh = Session::new(World::open(&bytes).unwrap(), 20_000).unwrap();
        assert_eq!(fresh.settle(60_000), t1);
        let started = std::time::Instant::now();
        assert!(fresh.catch_up(60_000, t2));
        assert!(fresh.set_rate(60_000, 240.));
        let took = started.elapsed();
        println!("swap with a 4 h catch-up took {took:?}");
        assert!(took.as_millis() < 50, "swap blocked for {took:?}");
        assert_eq!(fresh.settle(60_000), t2);
        let (a, b) = (steady.world.api.cash(), fresh.world.api.cash());
        assert!(a < 50_000., "the gap's running costs are charged");
        assert!((a - b).abs() < 1e-6, "ledger {a} vs {b}");
        assert_eq!(steady.world.header["hours"], fresh.world.header["hours"]);
        // both run on in step afterwards
        assert_eq!(steady.settle(61_000), fresh.settle(61_000));
        // a stale target behind the runtime's clock rewinds nothing and charges nothing more
        let cash = fresh.world.api.cash();
        assert!(fresh.catch_up(61_000, t1));
        assert_eq!(fresh.settle(61_000), t2 + 240.);
        assert_eq!(fresh.world.api.cash(), cash);
        assert!(!fresh.catch_up(500, t2 + 9999.));
        assert!(!fresh.catch_up(62_000, f64::NAN));
    }
}
