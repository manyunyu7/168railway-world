// SPDX-License-Identifier: AGPL-3.0-only
//! A line's own fare multiple (web T-3567: game/fareCurve.ts `tidyLineFare`, `ownLineFare` and the
//! clock worker's `setLineFare`). Kept in the save header as the web keeps it, `fares.lines` by line id,
//! a line at 1.00x having no key; demand reads it through `Fares::multiplier`.
use super::{fare_editor, finance, World};
use jni::{objects::JClass, sys::{jdouble, jint, jlong, jstring}, JNIEnv};
use serde_json::{json, Map, Value};

/// web LINE_FARE_MAX
pub(super) const MAX: f64 = 3.;

/// web tidyLineFare: 0 to 3 in hundredths; not a number is 1.
pub(super) fn tidy(v: f64) -> f64 {
    if v.is_finite() { ((v * 100.).round() / 100.).clamp(0., MAX) } else { 1. }
}

impl World {
    /// web ownLineFare: the line's own multiple, 1 unless set.
    pub(super) fn line_fare(&self, id: u32) -> f64 {
        self.header["fares"]["lines"][id.to_string()].as_f64().filter(|v| v.is_finite() && *v >= 0.).unwrap_or(1.)
    }

    /// web setLineFare: line `id`'s multiple set to `mul`, tidied; its hidden quad-track twins with it.
    pub(super) fn set_line_fare(&mut self, id: u32, now: f64, mul: f64) -> Result<f64, String> {
        let net = &self.api.world().net;
        if !mul.is_finite() || id as usize >= net.line_count() || !net.line_ok(id) {
            return Err("Lini sudah tidak tersedia.".into());
        }
        let twins: Vec<u32> = (0..net.line_count() as u32)
            .filter(|&l| l != id && net.line_ok(l) && self.api.line_twin_of(l) == id as i32)
            .collect();
        let v = tidy(mul);
        // money earned so far is booked at the old fares
        let now = self.native_clock(now);
        finance::tick(self, now);
        if !self.header["fares"].is_object() {
            self.header["fares"] = fare_editor::fresh();
        }
        let fares = self.header["fares"].as_object_mut().unwrap();
        let mut own = fares.remove("lines").and_then(|l| match l { Value::Object(m) => Some(m), _ => None })
            .unwrap_or_else(Map::new);
        for l in std::iter::once(id).chain(twins) {
            if v == 1. { own.remove(&l.to_string()); } else { own.insert(l.to_string(), json!(v)); }
        }
        if !own.is_empty() {
            fares.insert("lines".into(), Value::Object(own));
        }
        Ok(v)
    }
}

fn reply(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineFare_get(env: JNIEnv, _: JClass, handle: jlong, id: jint) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }
        .map_or(json!({"error":"Dunia belum siap."}), |w| json!({"fare":w.line_fare(id as u32),"max":MAX}));
    reply(&env, v)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineFare_set(env: JNIEnv, _: JClass, handle: jlong, id: jint, now: jdouble, mul: jdouble) -> jstring {
    let v = match unsafe { (handle as *mut World).as_mut() } {
        None => json!({"error":"Dunia belum siap."}),
        Some(w) => w.set_line_fare(id as u32, now, mul).map_or_else(|e| json!({"error":e}), |v| json!({"fare":v,"max":MAX})),
    };
    reply(&env, v)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fares::Fares;

    #[test]
    fn a_line_fare_is_tidied_kept_and_survives_a_save() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        assert_eq!(w.line_fare(0), 1.);
        // stepped as the web stepper does, in hundredths
        assert_eq!(w.set_line_fare(0, t, 1.25).unwrap(), 1.25);
        assert_eq!(w.header["fares"]["lines"], json!({"0":1.25}));
        assert_eq!(w.header["fares"]["base"], 1.5, "the rest of the fares stay");
        assert_eq!(Fares::read(&w.header["fares"], "ID").multiplier(0, false, false, false), (1.25, 1.25));
        // out of range is cut to the web's bounds, in hundredths
        assert_eq!(w.set_line_fare(0, t, 7.).unwrap(), 3.);
        assert_eq!(w.set_line_fare(0, t, -0.5).unwrap(), 0.);
        assert_eq!(w.set_line_fare(0, t, 1.234).unwrap(), 1.23);
        // not a number, or no such line, is refused and nothing changes
        assert!(w.set_line_fare(0, t, f64::NAN).is_err());
        assert!(w.set_line_fare(9, t, 2.).is_err());
        assert_eq!(w.line_fare(0), 1.23);
        let bytes = w.save(t).unwrap();
        let mut back = World::open(&bytes).unwrap();
        assert_eq!(back.line_fare(0), 1.23);
        assert_eq!(back.header["fares"]["lines"], json!({"0":1.23}));
        // back to 1.00x leaves no key, as the web saves it
        assert_eq!(back.set_line_fare(0, t, 1.).unwrap(), 1.);
        assert!(back.header["fares"].get("lines").is_none());
        let again = World::open(&back.save(t).unwrap()).unwrap();
        assert_eq!(again.line_fare(0), 1.);
    }

    #[test]
    fn tidy_matches_the_web() {
        assert_eq!(tidy(f64::INFINITY), 1.);
        assert_eq!(tidy(0.05 * 3.), 0.15);
        assert_eq!(tidy(2.999), 3.);
        assert_eq!(tidy(-0.004), 0.);
    }
}
