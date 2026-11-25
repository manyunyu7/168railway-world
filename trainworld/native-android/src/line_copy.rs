//! "Salin lini" from a line's panel, as the web's LineInspector `copy` (op `copyLine`, clock worker):
//! the engine's `copy_line` (stops and settings, no trains, the source's group), the web's copy name
//! and symbol (game/cloneNames.ts `cloneName` / `cloneSymbol`), the source's colour (`set_line_badge`),
//! its own bullet shape in the header's `shapes` (`newLineShape`), a fresh 1.00x fare (`newLineFare`)
//! and shown on the map (T-3420: the new id out of `hidden`). One edit, so one undo.
use super::*;
use std::collections::HashSet;

/// web cloneNames `SYMBOL_MAX` (= line_look::SYMBOL_MAX).
const SYMBOL_MAX: usize = super::line_look::SYMBOL_MAX;

/// web lineLetter `letterOf`: the badge letters a line without a symbol shows.
pub(super) fn letter_of(name: &str) -> String {
    for p in ["Line ", "Bus "] {
        if let Some(rest) = name.strip_prefix(p) {
            let n = rest.chars().count();
            if (1..=2).contains(&n) && !rest.chars().any(char::is_whitespace) {
                return rest.to_owned();
            }
        }
    }
    let t = name.trim();
    let n = t.chars().count();
    let upper = t.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit());
    let alnum = t.chars().all(|c| c.is_ascii_alphanumeric()) && t.chars().any(|c| c.is_ascii_digit());
    if (1..=5).contains(&n) && (upper || alnum) {
        return t.to_owned();
    }
    t.chars().next().map_or("?".into(), |c| c.to_uppercase().collect())
}

/// web `nextLetter`: "Line 1" -> "Line 1a", "Line 1a" -> "Line 1b", the first one `taken` refuses not.
fn next_letter(text: &str, taken: impl Fn(&str) -> bool) -> Option<String> {
    let chars: Vec<char> = text.chars().collect();
    let last = *chars.last()?;
    let (head, letter) = if last.is_ascii_digit() {
        (text.to_owned(), None)
    } else if last.is_ascii_alphabetic() && chars.len() >= 2 && chars[chars.len() - 2].is_ascii_digit() {
        (chars[..chars.len() - 1].iter().collect::<String>(), Some(last))
    } else {
        return None;
    };
    let from = letter.map_or(b'a', |l| l.to_ascii_lowercase() as u8 + 1);
    let upper = letter.is_some_and(|l| l.is_ascii_uppercase());
    (from..=b'z').map(|c| format!("{head}{}", if upper { c.to_ascii_uppercase() } else { c } as char)).find(|s| !taken(s))
}

/// web `cloneName`: the next letter after a trailing number, else "<name> clone N" (a clone's own
/// "clone N" stepped to the lowest free N).
pub(super) fn clone_name(name: &str, names: &HashSet<String>) -> String {
    let full = name.trim();
    let t = match full.rfind(" clone ") {
        Some(i) if !full[i + 7..].is_empty() && full[i + 7..].bytes().all(|b| b.is_ascii_digit()) => &full[..i],
        _ => full,
    };
    if t == full {
        if let Some(s) = next_letter(t, |s| names.contains(s)) {
            return s;
        }
    }
    (1..).map(|n| format!("{t} clone {n}")).find(|s| !names.contains(s)).unwrap()
}

/// web `cloneSymbol`: the next letter after a trailing number, else the first letter and the lowest
/// number no line's symbol uses; `symbols` in lower case.
pub(super) fn clone_symbol(symbol: &str, symbols: &HashSet<String>) -> String {
    let taken = |s: &str| symbols.contains(&s.to_lowercase());
    if let Some(s) = next_letter(symbol, taken).filter(|s| s.chars().count() <= SYMBOL_MAX) {
        return s;
    }
    let first = symbol.chars().next().unwrap_or('?');
    (1..).map(|n| format!("{first}{n}")).find(|s| !taken(s)).unwrap()
}

/// The copy's symbol as the clock worker picks it: a lettered name's tail ("Line 1d" -> "1d") when free.
fn copy_symbol(name: &str, source: &str, symbols: &HashSet<String>) -> String {
    let chars: Vec<char> = name.chars().collect();
    let n = chars.len();
    if n >= 2 && chars[n - 1].is_ascii_alphabetic() && chars[n - 2].is_ascii_digit() {
        let start = (0..n - 1).rev().take_while(|&i| chars[i].is_ascii_digit()).last().unwrap();
        let tail: String = chars[start..].iter().collect();
        if tail.chars().count() <= SYMBOL_MAX && !symbols.contains(&tail.to_lowercase()) {
            return tail;
        }
    }
    clone_symbol(source, symbols)
}

impl World {
    /// Copy line `source`; the copy's inspection (`inspect_service`, its `id` the new line's).
    pub(super) fn copy_line(&mut self, source: u32, now: f64) -> Result<Value, String> {
        if !self.api.world().net.line_ok(source) {
            return Err("Line tidak ditemukan.".into());
        }
        let mut next = self.edit_candidate(now).ok_or("Gagal membaca permainan.")?;
        next.api.set_now(now);
        let a = &mut next.api;
        let id = a.new_line_id();
        let (mut names, mut symbols, mut colour) = (HashSet::new(), HashSet::new(), 0);
        for r in a.lines_info().chunks_exact(22) {
            let l = r[0] as u32;
            let name = a.line_name(l);
            let symbol = a.line_symbol(l);
            symbols.insert(if symbol.is_empty() { letter_of(&name) } else { symbol }.to_lowercase());
            names.insert(name);
            if l == source {
                colour = r[1] as u32;
            }
        }
        let from = a.line_name(source);
        let name = clone_name(&from, &names);
        if a.copy_line(source, id, name.clone()) != 0 {
            return Err(crate::issue_text::describe(&a.issue_kinds()));
        }
        let own = a.line_symbol(source);
        let symbol = copy_symbol(&name, &if own.is_empty() { letter_of(&from) } else { own }, &symbols);
        if a.set_line_badge(id, name, colour, symbol) != 0 {
            return Err(crate::issue_text::describe(&a.issue_kinds()));
        }
        // web newLineShape: the copy wears the source's own shape, never one a deleted line of its id left
        let key = id.to_string();
        let shape = line_look::own_shape(&next.header, source).map(str::to_owned);
        if let Some(m) = next.header.get_mut("shapes").and_then(Value::as_object_mut) {
            m.remove(&key);
        }
        if let Some(s) = shape {
            if !next.header["shapes"].is_object() {
                next.header["shapes"] = json!({});
            }
            next.header["shapes"][&key] = json!(s);
        }
        // web newLineFare: a new line starts at 1.00x, whatever a deleted line of its id had
        if let Some(own) = next.header.pointer_mut("/fares/lines").and_then(Value::as_object_mut) {
            own.remove(&key);
            if own.is_empty() {
                next.header["fares"].as_object_mut().unwrap().remove("lines");
            }
        }
        // T-3420: the copy is shown
        if let Some(h) = next.header.get_mut("hidden").and_then(Value::as_array_mut) {
            h.retain(|v| v.as_str() != Some(key.as_str()) && v.as_u64() != Some(id as u64));
        }
        next.book_service_edit(self, now);
        *self = next;
        self.inspect_service(id, now).ok_or("Layanan tidak ditemukan.".into())
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_LineCopy_copy(env: JNIEnv, _: JClass, handle: jlong, id: i32, now: f64) -> jstring {
    let value = unsafe { (handle as *mut World).as_mut() }
        .map(|w| w.copy_line(id as u32, now).unwrap_or_else(|e| json!({"error": e})))
        .unwrap_or(Value::Null);
    env.new_string(value.to_string()).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(v: &[&str]) -> HashSet<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn names_and_symbols_as_the_web_clones_them() {
        assert_eq!(clone_name("Line 1", &set(&["Line 1"])), "Line 1a");
        assert_eq!(clone_name("Line 1a", &set(&["Line 1a", "Line 1b"])), "Line 1c");
        assert_eq!(clone_name("Line 1A", &set(&[])), "Line 1B");
        assert_eq!(clone_name("Hudson", &set(&["Hudson"])), "Hudson clone 1");
        assert_eq!(clone_name("Hudson clone 1", &set(&["Hudson clone 1"])), "Hudson clone 2");
        assert_eq!(clone_symbol("1", &set(&["1"])), "1a");
        assert_eq!(clone_symbol("t", &set(&["t"])), "t1");
        assert_eq!(clone_symbol("tm", &set(&["tm", "t1"])), "t2");
        assert_eq!(clone_symbol("1234z", &set(&[])), "11");
        assert_eq!(letter_of("Line 7"), "7");
        assert_eq!(letter_of("KRL"), "KRL");
        assert_eq!(letter_of("b12"), "b12");
        assert_eq!(letter_of("hudson"), "H");
        assert_eq!(copy_symbol("Line 1d", "1", &set(&["1"])), "1d");
        assert_eq!(copy_symbol("Line 1d", "1", &set(&["1", "1d"])), "1a");
    }

    #[test]
    fn a_copy_has_the_sources_stops_look_and_shape_and_survives_a_reload() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Hudson", t).unwrap();
        w.set_line_look(0, 0x1f6fd1, "H", "diamond", t).unwrap();
        // a deleted line's leftovers under the id the copy will take
        let id = w.api.new_line_id();
        w.header["fares"]["lines"] = json!({id.to_string(): 2.0});
        w.header["hidden"] = json!([id.to_string()]);
        let r = w.copy_line(0, t).unwrap();
        assert_eq!(r["id"], json!(id));
        assert_ne!(id, 0);
        assert_eq!(r["name"], "Hudson clone 1");
        assert_eq!(r["symbol"], "H1");
        assert_eq!(r["shape"], "diamond");
        assert_eq!(w.api.line_stops(id), w.api.line_stops(0));
        assert!(!w.api.line_stops(id).is_empty());
        let info = w.api.lines_info();
        let row = |l: u32| info.chunks_exact(22).find(|r| r[0] as u32 == l).unwrap().to_vec();
        let (a, b) = (row(0), row(id));
        assert_eq!(b[1], a[1], "colour");
        assert_eq!(b[8], a[8], "cars");
        assert_eq!(b[19..21], a[19..21], "dwell and turnaround");
        assert_eq!(w.api.line_group(id), w.api.line_group(0), "the copy joins its source's group");
        assert_ne!(w.api.line_group(0), 0);
        assert_eq!(w.line_fare(id), 1.);
        assert!(w.header["fares"].get("lines").is_none());
        assert_eq!(w.header["hidden"], json!([]));
        assert_eq!(w.header["shapes"], json!({"0": "diamond", id.to_string(): "diamond"}));

        let back = World::open(&w.save(t).unwrap()).unwrap();
        assert_eq!(back.api.line_stops(id), back.api.line_stops(0));
        assert_eq!(back.api.line_name(id), "Hudson clone 1");
        assert_eq!(back.api.line_symbol(id), "H1");
        assert_eq!(back.api.lines_info().chunks_exact(22).find(|r| r[0] as u32 == id).unwrap()[1] as u32, 0x1f6fd1);
        assert_eq!(line_look::looks(&back)[id.to_string()], json!({"symbol": "H1", "shape": "diamond"}));

        // a second copy steps on, and a line without its own shape gives none to its copy
        w.set_line_look(0, 0x1f6fd1, "H", "", t).unwrap();
        let r2 = w.copy_line(0, t).unwrap();
        assert_eq!(r2["name"], "Hudson clone 2");
        assert_eq!(r2["symbol"], "H2");
        assert!(r2["shape"].is_null());
        assert!(w.copy_line(99, t).is_err());
    }

    #[test]
    fn a_numbered_line_copies_to_a_lettered_one() {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [3500., 600.], "Line 1", t).unwrap();
        let r = w.copy_line(0, t).unwrap();
        assert_eq!(r["name"], "Line 1a");
        assert_eq!(r["symbol"], "1a", "bullet and name agree");
    }
}
