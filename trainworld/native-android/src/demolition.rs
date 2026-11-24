// SPDX-License-Identifier: AGPL-3.0-only
use super::World;
use jni::{objects::JClass, sys::{jboolean, jint, jlong, jstring}, JNIEnv};
use serde_json::{json, Value};

const SERVICE: i32 = 0;
const STATION: i32 = 1;
const TRACK: i32 = 2;

fn targets(w: &World, kind: i32) -> Value {
    let net = &w.api.world().net;
    match kind {
        SERVICE => (0..net.line_count() as u32)
            .filter(|&id| net.line_ok(id))
            .map(|id| json!({"id":id,"name":w.api.line_name(id)})).collect(),
        STATION => (0..net.node_count() as u32)
            .filter(|&id| net.is_station(id))
            .map(|id| json!({"id":id,"name":w.api.node_name(id)})).collect(),
        _ => json!([]),
    }
}

// TWG1 numbers living lines densely in ascending engine-id order. The JSON envelope
// carries the web editor's per-line fields and must use the same ids as the track stream.
pub(super) fn remap_header(header: &mut Value, lines: &[u32]) {
    let map: std::collections::HashMap<_, _> = lines.iter().enumerate()
        .map(|(dense, id)| (id.to_string(), dense.to_string())).collect();
    for path in ["/fares/lines", "/shapes", "/lineDays", "/notes"] {
        if let Some(old) = header.pointer_mut(path).and_then(Value::as_object_mut) {
            *old = std::mem::take(old).into_iter()
                .filter_map(|(key, value)| {
                    if key.parse::<u32>().is_err() {
                        return Some((key, value));
                    }
                    map.get(&key).map(|id| (id.clone(), value))
                })
                .collect();
        }
    }
    if let Some(hidden) = header.get_mut("hidden").and_then(Value::as_array_mut) {
        *hidden = hidden.iter().filter_map(|value| map.get(value.as_str()?).map(|id| json!(id))).collect();
    }
}

// Preview and commit both use the original edit on an isolated candidate. A refusal
// leaves the live network, clock, ledger and station links intact.
fn remove(w: &mut World, kind: i32, id: u32, clock: f64, commit: bool) -> Result<Value, String> {
    let clock = w.native_clock(clock);
    let net = &w.api.world().net;
    let valid = match kind {
        SERVICE => net.line_ok(id),
        STATION => net.is_station(id),
        TRACK => net.edge_ok(id) && net.edge_built[id as usize],
        _ => false,
    };
    if !valid || !clock.is_finite() {
        return Err("Objek sudah tidak tersedia.".into());
    }
    let live_lines: Vec<_> = (0..net.line_count() as u32).filter(|&line| net.line_ok(line)).collect();
    let dense_id = if kind == SERVICE {
        live_lines.partition_point(|&line| line < id) as u32
    } else if kind == TRACK {
        (0..id).filter(|&edge| net.edge_ok(edge)).count() as u32
    } else {
        (0..id).filter(|&node| net.node_ok(node)).count() as u32
    };
    let mut next = w.edit_candidate(clock).ok_or("Salinan dunia gagal dibuka.")?;
    next.api.set_now(clock);
    let initial_cash = next.api.cash();
    let status = if kind == SERVICE {
        next.api.remove_line(dense_id)
    } else if kind == TRACK {
        next.api.delete_pieces(&[dense_id], &[])
    } else {
        next.api.remove_station(dense_id)
    };
    if status != 0 {
        return Err(format!("Studio menolak pembongkaran. {}", crate::issue_text::describe(&next.api.issue_kinds())));
    }

    let mut affected = Vec::new();
    for (dense, &line) in live_lines.iter().enumerate() {
        let remaining = &next.api.world().net;
        if !remaining.line_ok(dense as u32) {
            affected.push(json!({"name":w.api.line_name(line),"effect":"removed"}));
        } else if kind == TRACK && remaining.line_broken[dense] && !net.line_broken[line as usize] {
            affected.push(json!({"name":w.api.line_name(line),"effect":"broken"}));
        } else if kind == STATION && net.line_stops(line).contains(&id) {
            let effect = if remaining.line_broken[dense] { "broken" } else { "rerouted" };
            affected.push(json!({"name":w.api.line_name(line),"effect":effect}));
        }
    }
    let change = next.api.cash() - initial_cash;
    if commit {
        if kind == SERVICE {
            // Matching the web editor, a reused line id must start with the default fare.
            if let Some(fares) = next.header.get_mut("fares").and_then(Value::as_object_mut) {
                if let Some(lines) = fares.get_mut("lines").and_then(Value::as_object_mut) {
                    lines.retain(|key, _| key.parse::<u32>().map_or(true, |l| next.api.world().net.line_ok(l)));
                    if lines.is_empty() { fares.remove("lines"); }
                }
            }
        }
        next.book_service_edit(w, clock);
        next.roads_dir = w.roads_dir.clone();
        *w = next;
    }
    Ok(json!({"cashChange":change,"affected":affected}))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_DemolitionPanel_targets(
    env: JNIEnv, _: JClass, handle: jlong, kind: jint,
) -> jstring {
    let rows = unsafe { (handle as *const World).as_ref() }.map(|w| targets(w, kind)).unwrap_or(json!([]));
    env.new_string(rows.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_DemolitionPanel_remove(
    env: JNIEnv, _: JClass, handle: jlong, kind: jint, id: jint, clock: f64, commit: jboolean,
) -> jstring {
    let result = unsafe { (handle as *mut World).as_mut() }.ok_or("Dunia belum siap.".into())
        .and_then(|w| remove(w, kind, id as u32, clock, commit != 0))
        .unwrap_or_else(|e| json!({"error":e}));
    env.new_string(result.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removal_previews_without_writing_and_keeps_the_engine_balance_and_backup() {
        let mut w = World::workshop();
        let time = 111600.;
        w.build_path(&[[0.,0.],[1600.,0.],[3200.,0.]], "Lintas", time).unwrap();
        w.header["fares"] = json!({"lines":{"0":1.25},"local":"ID"});
        w.header["mode"] = "normal".into();
        w.api.set_unlimited(false);
        let backup = w.save(time).unwrap();
        let stops = w.api.line_stops(0);
        assert_eq!(stops.len(),3);
        let before = w.api.cash();
        let preview = remove(&mut w,STATION,stops[1],time,false).unwrap();
        assert_eq!(w.save(time).unwrap(),backup);
        assert_eq!(preview["affected"][0]["name"],"Lintas");
        assert_eq!(remove(&mut w, STATION,stops[1],time,true).unwrap(),preview);
        let restored = World::open(&w.save(time).unwrap()).unwrap();
        // TWG1 compacts node numbers: compare positions, not the pre-save integers.
        let stop_positions = |world: &World| {
            let net = &world.api.world().net;
            world.api.line_stops(0).iter().map(|&id| net.node_x[id as usize].round() as i64).collect::<Vec<_>>()
        };
        let source = World::open(&backup).unwrap();
        let original = stop_positions(&source);
        assert_eq!(targets(&restored, STATION).as_array().unwrap().len(), 2);
        assert_eq!(stop_positions(&restored), vec![original[0], original[2]]);
        assert_eq!(restored.api.cash()-before,preview["cashChange"].as_f64().unwrap());
        assert_eq!(restored.api.cash(),before);
        let deleted = remove(&mut w,SERVICE,0,time,true).unwrap();
        assert_eq!(deleted["affected"][0]["effect"],"removed");
        assert_eq!(deleted["cashChange"],0.);
        assert!(w.header["fares"].get("lines").is_none());
        let deleted_save = w.save(time).unwrap();
        assert!(remove(&mut w,SERVICE,u32::MAX,time,true).is_err());
        assert_eq!(w.save(time).unwrap(),deleted_save);
        let reopened = World::open(&deleted_save).unwrap();
        assert_eq!(targets(&reopened,SERVICE),json!([]));
        assert_eq!(targets(&reopened,STATION).as_array().unwrap().len(),2);
        let undo = World::open(&backup).unwrap();
        assert_eq!(stop_positions(&undo),original);
        assert_eq!(undo.header["fares"]["lines"]["0"],1.25);
        assert_eq!(undo.api.cash(),before);
    }
    #[test]
    fn another_deletion_targets_the_named_service_after_twg1_compacts_ids() {
        let mut w = World::workshop();
        let clock = 111600.;
        for (i, name) in ["A", "B", "C"].iter().enumerate() {
            let x = i as f64 * 3000.;
            w.build_service([x, 0.], [x + 1600., 0.], name, clock).unwrap();
        }
        w.header["shapes"] = json!({"0":"circle","1":"square","2":"diamond"});
        w.header["fares"] = json!({"lines":{"0":1.0,"1":1.2,"2":1.5}});
        w.header["hidden"] = json!(["0","2"]);
        w.header["notes"] = json!({"existing":"catatan dari Studio", "2":[4,"jak"]});
        remove(&mut w, SERVICE, 0, clock, true).unwrap();
        let reloaded = World::open(&w.save(clock).unwrap()).unwrap();
        assert_eq!(reloaded.api.line_name(0),"B");
        assert_eq!(reloaded.header["shapes"],json!({"0":"square","1":"diamond"}));
        assert_eq!(reloaded.header["hidden"],json!(["1"]));
        assert_eq!(reloaded.header["notes"],json!({"existing":"catatan dari Studio","1":[4,"jak"]}));
        assert_eq!(reloaded.header["fares"]["lines"],json!({"0":1.2,"1":1.5}));
        // C is still runtime id 2 but the candidate loaded from TWG1 gives it id 1.
        assert_eq!(targets(&w, SERVICE)[1]["id"],2);
        let result=remove(&mut w,SERVICE,2,clock,true).unwrap();
        assert_eq!(result["affected"][0]["name"],"C");
        assert_eq!(targets(&w,SERVICE)[0]["name"],"B");
        assert_eq!(targets(&w,SERVICE).as_array().unwrap().len(),1);
    }

}
