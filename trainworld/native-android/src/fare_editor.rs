use super::{
    fares::{currency_factor, Fares},
    finance, World,
};
use jni::{
    objects::{JClass, JString},
    sys::{jdouble, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

fn update(raw: &Value, patch: &Value) -> Result<Value, String> {
    let fields = patch.as_object().ok_or("Format tarif salah.")?;
    if fields.is_empty() {
        return Ok(raw.clone());
    }
    for (key, value) in fields {
        let limit = match key.as_str() {
            "base" | "perKm" | "transfer" => 1000.,
            "hsr" | "bus" | "ferry" => 5.,
            _ => return Err("Kolom tarif tidak dikenal.".into()),
        };
        if !value.as_f64().is_some_and(|n| (0. ..=limit).contains(&n)) {
            return Err("Tarif berada di luar batas.".into());
        }
    }
    let mut next = raw.clone();
    if next.is_null() {
        next = json!({"base":0.0862,"perKm":0.006895,"nodes":[[0,0.0862],[20,0.2241],[100,0.7757]],"baseline":"IN","basis":"usd","scales":{}})
    }
    if fields.contains_key("base") || fields.contains_key("perKm") {
        let old = Fares::read(raw, "ID");
        let base = patch["base"]
            .as_f64()
            .unwrap_or_else(|| old.market["base"].as_f64().unwrap());
        let km = patch["perKm"]
            .as_f64()
            .unwrap_or_else(|| old.market["rate"].as_f64().unwrap());
        if base + 100. * km > 1000. {
            return Err("Tarif untuk 100 km melebihi US$1.000.".into());
        }
        // Keep the existing save's currency and scale schema. Web migrates legacy units on load.
        let factor = currency_factor(&next);
        next["base"] = json!(base / factor);
        next["perKm"] = json!(km / factor);
        next["nodes"] = json!([0., 20., 100.].map(|k| [k, (base + k * km) / factor]));
    }
    for key in ["transfer", "bus", "hsr", "ferry"] {
        if let Some(n) = patch[key].as_f64() {
            let precision = if key == "transfer" { 10000. } else { 100. };
            next[key] = json!((n * precision).round() / precision);
        }
    }
    Ok(next)
}
fn editor(w: &mut World, clock: f64, patch: &Value) -> Result<Value, String> {
    let raw = update(&w.header["fares"], patch)?;
    if !patch.as_object().unwrap().is_empty() {
        w.header["fares"] = raw.clone()
    }
    let f = Fares::read(&raw, "ID");
    Ok(
        json!({"finance":finance::apply(w,clock,0)?,"local":f.local,"values":{"base":f.market["base"],"perKm":f.market["rate"],"transfer":raw["transfer"].as_f64().unwrap_or(0.),"hsr":raw["hsr"].as_f64().unwrap_or(1.25),"bus":raw["bus"].as_f64().unwrap_or(1.),"ferry":raw["ferry"].as_f64().unwrap_or(1.)}}),
    )
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeFinance_fares(
    mut env: JNIEnv,
    _: JClass,
    ptr: jlong,
    clock: jdouble,
    input: JString,
) -> jstring {
    let mut run = || -> Result<Value, String> {
        if ptr == 0 {
            return Err("Dunia belum siap.".into());
        }
        let text: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        editor(
            unsafe { &mut *(ptr as *mut World) },
            clock,
            &serde_json::from_str(&text).map_err(|e| e.to_string())?,
        )
    };
    let result = run().unwrap_or_else(|e| json!({"error":e}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn edited_twg1_preserves_legacy_scales_and_other_lines() {
        let input = std::env::var("WORLD_EDITOR_INPUT")
            .map(|path| std::fs::read(path).unwrap())
            .unwrap_or_else(|_| include_bytes!("../../T-007-real-nyc.save").to_vec());
        let mut rows = vec![];
        for raw in [
            Value::Null,
            json!({"base":1.25,"perKm":0.1,"lines":{"7":2.2},"scales":{"ID":1.8}}),
            json!({"base":1,"perKm":0.2,"basis":"usd","ref":"JP","nodes":[[0,1],[20,5],[100,25]],"scales":{"@":2.7,"ID":1.5},"income":0.5}),
        ] {
            let mut world = World::open(&input).unwrap();
            world.header["fares"] = raw.clone();
            assert!(editor(&mut world, 36000., &json!({"bus":6})).is_err());
            assert_eq!(world.header["fares"], raw);
            editor(&mut world, 36000., &json!({"bus":2.5})).unwrap();
            assert_eq!(
                Fares::read(&raw, "ID").local,
                Fares::read(&world.header["fares"], "ID").local
            );
            let price = editor(
                &mut world,
                36000.,
                &json!({"base":1.0,"perKm":0.025,"transfer":0.25,"hsr":2.1,"ferry":1.1}),
            )
            .unwrap();
            let bytes = world.save(36000.).unwrap();
            let mut reopened = World::open(&bytes).unwrap();
            assert_eq!(
                editor(&mut reopened, 36000., &json!({})).unwrap()["values"],
                price["values"]
            );
            let saved = &reopened.header["fares"];
            assert_eq!(saved["lines"], raw["lines"]);
            let f = Fares::read(saved, "ID");
            assert_eq!(f.market["base"], 1.);
            assert!((f.market["rate"].as_f64().unwrap() - 0.025).abs() < 1e-9);
            assert_eq!(f.multiplier(0, false, false, true), (1., 2.5));
            rows.push(
                json!({"country":"ID","rawFare":saved,"marketFare":f.market,"localFare":f.local}),
            );
            if raw["ref"] == "JP" {
                if let Ok(path) = std::env::var("WORLD_EDITOR_SAVE") {
                    std::fs::write(path, bytes).unwrap()
                }
            }
        }
        if let Ok(path) = std::env::var("WORLD_EDITOR_EXPORT") {
            std::fs::write(path, serde_json::to_vec(&rows).unwrap()).unwrap()
        }
    }
}
