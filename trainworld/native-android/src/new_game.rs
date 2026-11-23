// SPDX-License-Identifier: AGPL-3.0-only
use crate::{fares, local_time, World};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray},
    JNIEnv,
};
use serde_json::json;

include!(concat!(env!("OUT_DIR"), "/starting_countries.rs"));

// Reset a new blank or real-network template as clock.worker.ts does on "new".
// Existing account worlds go through World::open and never pass this path.
fn prepare(bytes: &[u8], city: &str, sandbox: bool) -> Option<Vec<u8>> {
    let (_, country) = STARTING_COUNTRIES.iter().find(|(id, _)| *id == city)?;
    let mut world = World::open(bytes)?;
    let header = world.header.as_object_mut()?;
    for field in [
        "loan",
        "loanPay",
        "loanDays",
        "regions",
        "lineDays",
        "stars",
        "goals",
        "notes",
        "newHeadway",
        "newWeekendShare",
        "achievements",
        "demandFine",
        "demandBuilt",
        "dayShift",
        "guide",
        "copy",
    ] {
        header.remove(field);
    }
    world.header["city"] = json!("nyc");
    world.header["home"] = json!(city);
    world.header["start"] = json!(city);
    world.header["opened"] = json!([city]);
    world.header["mode"] = json!(if sandbox { "sandbox" } else { "normal" });
    world.header["cash"] = json!(10000.0);
    if !sandbox {
        world.header["guide"] = if world.api.lines_info().is_empty() {
            json!({"done":0})
        } else {
            json!({"done":0,"card":2})
        };
    }
    world.header["newCars"] = json!(5);
    world.header["ledger"] = json!([]);
    world.header["hours"] = json!([]);
    world.header["fares"] = fares::starting_fares(country);
    world.api.set_cash(10000.0);
    world.api.set_unlimited(sandbox);
    let clock = 111600.0 + (-local_time::day_shift(&world.header)).rem_euclid(86400.0);
    world.save(clock)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioNewGame_prepare(
    mut env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
    city: JString,
    sandbox: jboolean,
) -> jbyteArray {
    let data = env.convert_byte_array(bytes).ok();
    let city: Option<String> = env.get_string(&city).ok().map(Into::into);
    data.zip(city)
        .and_then(|(bytes, city)| prepare(&bytes, &city, sandbox != 0))
        .and_then(|bytes| env.byte_array_from_slice(&bytes).ok())
        .map_or(std::ptr::null_mut(), |array| array.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioNewGame_localShift(
    mut env: JNIEnv,
    _: JClass,
    city: JString,
) -> jni::sys::jint {
    let city: Option<String> = env.get_string(&city).ok().map(Into::into);
    local_time::day_shift(&json!({"start":city})) as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn new_modes_reset_template_and_start_at_local_seven() {
        let bytes = include_bytes!("../../T-007-real-nyc.save");
        for (city, sandbox) in [("jak", false), ("tyo", true), ("nyc", false)] {
            let world = World::open(&prepare(bytes, city, sandbox).unwrap()).unwrap();
            assert_eq!(world.header["cash"], 10000.0);
            if sandbox {
                assert!(world.header.get("guide").is_none());
            } else {
                assert_eq!(world.header["guide"],json!({"done":0,"card":2}));
            }
            assert_eq!(
                world.header["mode"],
                if sandbox { "sandbox" } else { "normal" }
            );
            assert_eq!(
                (world.header["clock"].as_f64().unwrap() + local_time::day_shift(&world.header))
                    % 86400.0,
                25200.0
            );
            assert_eq!(world.header["fares"]["income"], 0.5);
            assert!(world.api.lines_info().len() > 100);
        }
    }
}
