//! A line's look as the web's line title edits it (ui/inspector/LineTitle.tsx): colour and symbol
//! through the engine (`lineLook` → `set_line_badge`), and the bullet shape (T-3319) in the save
//! header's `shapes`, `{"<line id>": "circle" | "square" | "diamond" | "outline"}`, the web's own
//! format (game/lineShape.ts `shapesFrom`). A line without an entry wears its mode's shape.
use super::*;

/** What a player can pick (web `LINE_SHAPES`); a ferry's "pill" is only its default look. */
pub(super) const SHAPES: [&str; 4] = ["circle", "square", "diamond", "outline"];
/** The longest symbol: the five characters the web's `letterOf` and a clone's symbol show. */
pub(super) const SYMBOL_MAX: usize = 5;

/// Web `MODE_SHAPES`: train and tram a circle, bus a rounded square, ferry a pill.
pub(super) fn mode_shape(api: &TrackApi, id: u32) -> &'static str {
    if api.is_bus(id) {
        "square"
    } else if api.is_ferry(id) {
        "pill"
    } else {
        "circle"
    }
}

/// The shape the player gave line `id`, if a valid one is saved (web `shapesFrom`).
pub(super) fn own_shape(header: &Value, id: u32) -> Option<&str> {
    header["shapes"][id.to_string()].as_str().filter(|s| SHAPES.contains(s))
}

/// Every live line's symbol ("" for none) and drawn shape, by id, for the badges and the map.
pub(super) fn looks(w: &World) -> Value {
    let net = &w.api.world().net;
    let mut out = serde_json::Map::new();
    for id in (0..net.line_count() as u32).filter(|&l| net.line_ok(l)) {
        let shape = own_shape(&w.header, id).unwrap_or_else(|| mode_shape(&w.api, id));
        out.insert(id.to_string(), json!({"symbol": w.api.line_symbol(id), "shape": shape}));
    }
    Value::Object(out)
}

impl World {
    /// Set line `id`'s colour (0xRRGGBB), symbol ("" gives the badge back to the name) and shape
    /// ("" or its mode's shape gives it back to the mode) as one edit.
    pub(super) fn set_line_look(&mut self, id: u32, colour: u32, symbol: &str, shape: &str, now: f64) -> Result<Value, String> {
        let symbol = symbol.trim();
        if symbol.chars().count() > SYMBOL_MAX {
            return Err(format!("Simbol paling banyak {SYMBOL_MAX} karakter."));
        }
        if !shape.is_empty() && !SHAPES.contains(&shape) && shape != "pill" {
            return Err("Bentuk tidak dikenal.".into());
        }
        if !self.api.world().net.line_ok(id) {
            return Err("Line tidak ditemukan.".into());
        }
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        next.api.set_now(now);
        let name = next.api.line_name(id);
        if next.api.set_line_badge(id, name, colour & 0xffffff, symbol.to_owned()) != 0 {
            return Err(crate::issue_text::describe(&next.api.issue_kinds()));
        }
        let own = (!shape.is_empty() && shape != mode_shape(&next.api, id) && shape != "pill").then_some(shape);
        let key = id.to_string();
        match own {
            Some(s) => {
                if !next.header["shapes"].is_object() {
                    next.header["shapes"] = json!({});
                }
                next.header["shapes"][&key] = json!(s);
            }
            None => {
                let empty = next.header.get_mut("shapes").and_then(Value::as_object_mut).map(|m| {
                    m.remove(&key);
                    m.is_empty()
                });
                if empty == Some(true) {
                    next.header.as_object_mut().unwrap().remove("shapes");
                }
            }
        }
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now).ok_or("Layanan tidak ditemukan.".into())
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineLook_apply(
    mut env: JNIEnv, _: JClass, handle: jlong, id: i32, now: f64, colour: i32, symbol: JString, shape: JString,
) -> jstring {
    let symbol: String = env.get_string(&symbol).map(|s| s.into()).unwrap_or_default();
    let shape: String = env.get_string(&shape).map(|s| s.into()).unwrap_or_default();
    let value = unsafe { (handle as *mut World).as_mut() }
        .map(|w| w.set_line_look(id as u32, colour as u32, &symbol, &shape, now).unwrap_or_else(|e| json!({"error": e})))
        .unwrap_or(Value::Null);
    env.new_string(value.to_string()).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colour_symbol_and_shape_survive_a_reload_in_the_web_header() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        let name = w.api.line_name(0);
        let r = w.set_line_look(0, 0x1f6fd1, " A1 ", "diamond", t).unwrap();
        assert_eq!(r["color"].as_f64(), Some(0x1f6fd1 as f64));
        assert_eq!(r["symbol"], "A1");
        assert_eq!(r["shape"], "diamond");
        assert_eq!(r["name"], name.as_str(), "the name stays");
        // the web's header: {"shapes": {"0": "diamond"}}
        assert_eq!(w.header["shapes"], json!({"0": "diamond"}));
        let back = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(back.api.line_symbol(0), "A1");
        assert_eq!(back.api.lines_info()[1] as u32, 0x1f6fd1);
        assert_eq!(back.header["shapes"], json!({"0": "diamond"}));
        assert_eq!(looks(&back)["0"], json!({"symbol": "A1", "shape": "diamond"}));
    }

    #[test]
    fn mode_shape_and_blank_symbol_give_the_look_back() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        w.set_line_look(0, 0xd7263d, "X", "outline", t).unwrap();
        let r = w.set_line_look(0, 0xd7263d, "", "circle", t).unwrap();
        assert_eq!(r["symbol"], "");
        assert!(r["shape"].is_null(), "a train's circle is its mode's, not its own");
        assert_eq!(r["modeShape"], "circle");
        assert!(w.header.get("shapes").is_none(), "an empty shapes object is left out, as the web does");
        assert_eq!(looks(&w)["0"]["shape"], "circle");
        assert!(w.set_line_look(0, 0, "ABCDEF", "", t).is_err());
        assert!(w.set_line_look(0, 0, "", "star", t).is_err());
        assert!(w.set_line_look(9, 0, "", "", t).is_err());
    }

    #[test]
    fn a_web_save_header_shape_is_read() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        w.header["shapes"] = json!({"0": "square", "7": "diamond", "x": "circle"});
        let back = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(own_shape(&back.header, 0), Some("square"));
        assert_eq!(looks(&back)["0"]["shape"], "square");
        w.header["shapes"] = json!({"0": "pill"});
        assert_eq!(own_shape(&w.header, 0), None, "a saved pill is no choice (web T-3357)");
    }
}
