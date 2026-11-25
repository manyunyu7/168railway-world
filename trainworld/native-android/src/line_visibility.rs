//! Lines the player hid on the map (web game/lineVisibility.ts, T-3420): the save header's
//! `hidden`, line ids as strings, kept outside the undo history as the web's clock worker keeps
//! them. A view setting only: a hidden line still runs. Its trains (`train_scene.rs`) and its
//! stroke are not drawn, nor those of its quad-track twins (web main.ts `hiddenNums`, T-3333).
use crate::World;
use jni::{
    objects::{JClass, JIntArray},
    sys::{jboolean, jintArray, jlong},
    JNIEnv,
};
use serde_json::Value;

/// Ids of the lines there are (`lines_info`'s rows).
fn alive(world: &World) -> Vec<u32> {
    world.api.lines_info().chunks_exact(22).map(|r| r[0] as u32).collect()
}

/// The lines the player hid, of the lines there are, in id order.
pub(crate) fn hidden(world: &World) -> Vec<u32> {
    let Some(ids) = world.header["hidden"].as_array() else { return vec![] };
    let live = alive(world);
    let mut out: Vec<u32> = ids
        .iter()
        .filter_map(|v| v.as_str()?.parse().ok())
        .filter(|id| live.contains(id))
        .collect();
    out.sort_unstable();
    out.dedup();
    out
}

/// Hide exactly `ids` (web `setLinesHidden`); none hidden leaves no `hidden` in the header (web
/// `savedHidden`). Ids of no line are dropped. True when the header changed.
pub(crate) fn set_hidden(world: &mut World, ids: &[u32]) -> bool {
    let live = alive(world);
    let mut keep: Vec<u32> = ids.iter().copied().filter(|id| live.contains(id)).collect();
    keep.sort_unstable();
    keep.dedup();
    let before = world.header.get("hidden").cloned();
    if keep.is_empty() {
        if let Some(h) = world.header.as_object_mut() {
            h.remove("hidden");
        }
    } else {
        world.header["hidden"] = Value::from(keep.iter().map(|id| id.to_string()).collect::<Vec<_>>());
    }
    before != world.header.get("hidden").cloned()
}

/// The lines not drawn: the hidden ones and their twins. Read once a frame by the train scene, so
/// without hidden lines it looks at nothing else.
pub(crate) fn drawn_hidden(world: &World) -> Vec<usize> {
    let Some(ids) = world.header["hidden"].as_array() else { return vec![] };
    let mut out: Vec<usize> = ids.iter().filter_map(|v| v.as_str()?.parse().ok()).collect();
    if out.is_empty() {
        return out;
    }
    let count = world.api.world().net.line_count() as u32;
    for l in 0..count {
        let of = world.api.line_twin_of(l);
        if of >= 0 && out.contains(&(of as usize)) && !out.contains(&(l as usize)) {
            out.push(l as usize);
        }
    }
    out.sort_unstable();
    out
}

fn int_array(env: &JNIEnv, v: impl IntoIterator<Item = i32>) -> jintArray {
    let v: Vec<i32> = v.into_iter().collect();
    let Ok(a) = env.new_int_array(v.len() as i32) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&a, 0, &v).is_err() {
        return std::ptr::null_mut();
    }
    a.into_raw()
}

/// The lines the player hid (`LineVisibility.hidden`).
#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineVisibility_hidden(env: JNIEnv, _: JClass, handle: jlong) -> jintArray {
    if handle == 0 {
        return int_array(&env, []);
    }
    let world = unsafe { &*(handle as *const World) };
    int_array(&env, hidden(world).into_iter().map(|v| v as i32))
}

/// The lines not drawn, twins included (`LineVisibility.drawn`).
#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineVisibility_drawn(env: JNIEnv, _: JClass, handle: jlong) -> jintArray {
    if handle == 0 {
        return int_array(&env, []);
    }
    let world = unsafe { &*(handle as *const World) };
    int_array(&env, drawn_hidden(world).into_iter().map(|v| v as i32))
}

/// Hide exactly `ids` (`LineVisibility.set`); true when that changed the header.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineVisibility_set(env: JNIEnv, _: JClass, handle: jlong, ids: JIntArray) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let n = env.get_array_length(&ids).unwrap_or(0).clamp(0, 1 << 16);
    let mut v = vec![0; n as usize];
    if n > 0 && env.get_int_array_region(&ids, 0, &mut v).is_err() {
        return 0;
    }
    let ids: Vec<u32> = v.into_iter().filter(|&x| x >= 0).map(|x| x as u32).collect();
    set_hidden(unsafe { &mut *(handle as *mut World) }, &ids) as jboolean
}

/// The hidden lines of world `from` onto `to` (a simulation's checkpoint or an undone edit swaps
/// the world; the web keeps them outside both). True when `to` changed.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineVisibility_carry(_: JNIEnv, _: JClass, from: jlong, to: jlong) -> jboolean {
    if from == 0 || to == 0 || from == to {
        return 0;
    }
    let ids = hidden(unsafe { &*(from as *const World) });
    set_hidden(unsafe { &mut *(to as *mut World) }, &ids) as jboolean
}

#[cfg(test)]
mod tests {
    use super::*;

    fn two_lines() -> World {
        let mut w = World::workshop();
        w.build_path(&[[0., 0.], [3000., 0.], [6000., 0.]], "A", 111600.).unwrap();
        w.build_path(&[[0., 3000.], [3000., 3000.], [6000., 3000.]], "B", 111600.).unwrap();
        w
    }

    #[test]
    fn hiding_is_kept_in_the_header_as_the_web_saves_it() {
        let mut w = two_lines();
        assert_eq!(alive(&w), vec![0, 1]);
        assert!(hidden(&w).is_empty());
        assert!(set_hidden(&mut w, &[1, 1, 7]));
        // as strings, like the web's header; an id of no line is dropped
        assert_eq!(w.header["hidden"], serde_json::json!(["1"]));
        assert_eq!(hidden(&w), vec![1]);
        assert_eq!(drawn_hidden(&w), vec![1]);
        assert!(!set_hidden(&mut w, &[1]));
        // kept through a save and a load
        let bytes = w.save(111600.).unwrap();
        let back = World::open(&bytes).unwrap();
        assert_eq!(hidden(&back), vec![1]);
        // showing them all leaves no key behind (web `savedHidden`)
        assert!(set_hidden(&mut w, &[]));
        assert!(w.header.get("hidden").is_none());
        assert!(drawn_hidden(&w).is_empty());
    }

    #[test]
    fn a_swapped_world_takes_the_hidden_lines() {
        let mut a = two_lines();
        let mut b = two_lines();
        set_hidden(&mut a, &[0]);
        let (pa, pb) = (&mut a as *mut World as jlong, &mut b as *mut World as jlong);
        let env_less = |from, to| {
            let ids = hidden(unsafe { &*(from as *const World) });
            set_hidden(unsafe { &mut *(to as *mut World) }, &ids)
        };
        assert!(env_less(pa, pb));
        assert_eq!(hidden(&b), vec![0]);
        assert!(!env_less(pa, pb));
    }
}
