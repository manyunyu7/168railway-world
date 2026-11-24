//! The all cities view (web ui/inspector/AllCitiesView.tsx, the sums of ui/Cities.tsx): every zone
//! with a built station, its stations and lines, its trips on the average day as the web's
//! `cityDemandViews`, and its profit as `figures()` reads the region ledgers. Points are not kept
//! on Android, so there are none.
use crate::World;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::jstring,
    JNIEnv,
};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const LEDGER: [&str; 5] = ["fares", "running", "upkeep", "trains", "build"];

/// A region's profit row as web `figures()`: yesterday's once the whole ledger has a yesterday (a
/// zone with no row then is all zero), before that today so far; all zero before any day (the
/// web's live day so far, nothing earned yet).
fn figures(header: &Value, zone: &str) -> Option<(Value, bool)> {
    let days = header["ledger"].as_array().map_or(&[][..], Vec::as_slice);
    let Some(today) = days.last().and_then(|d| d["day"].as_i64()) else {
        let mut out = json!({"day": null});
        for k in LEDGER {
            out[k] = 0.into();
        }
        return Some((out, false));
    };
    let rows = header["regions"][zone]["ledger"].as_array();
    let row = |day: i64| rows.and_then(|r| r.iter().find(|d| d["day"].as_i64() == Some(day)));
    let yesterday = days.iter().any(|d| d["day"].as_i64() == Some(today - 1));
    let day = if yesterday { today - 1 } else { today };
    let mut out = json!({"day": day});
    for k in LEDGER {
        out[k] = row(day).and_then(|r| r[k].as_f64()).unwrap_or(0.).into();
    }
    Some((out, yesterday))
}

/// Each region's points as web `pointsScore` (points.ts): its forecast weekday's, every line's
/// points an hour times the period's hours; with no forecast (or none above zero) what it earned
/// in the past 24 whole game hours of the hour ledgers (`pointsPast24h`).
fn points(
    header: &Value,
    forecast: Option<&crate::passenger_account::Forecast>,
) -> BTreeMap<String, f64> {
    use trainworld_sim::track::params::PERIOD_HOURS;
    let mut out: BTreeMap<String, f64> = BTreeMap::new();
    for line in forecast.map(|f| f.income()).unwrap_or_default().values() {
        for (z, days) in &line.points {
            let v: f64 = days[0]
                .iter()
                .zip(PERIOD_HOURS)
                .map(|(p, (a, b))| p * (b - a))
                .sum();
            *out.entry(z.clone()).or_default() += v;
        }
    }
    out.retain(|z, v| !z.is_empty() && *v > 0.);
    if !out.is_empty() {
        return out.into_iter().map(|(z, v)| (z, v.round())).collect();
    }
    let Some(now) = header["hours"]
        .as_array()
        .and_then(|h| h.last())
        .and_then(|h| h["hour"].as_i64())
    else {
        return out;
    };
    for (z, r) in header["regions"].as_object().into_iter().flatten() {
        let t: f64 = r["hours"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|e| e["hour"].as_i64().is_some_and(|h| h >= now - 24 && h < now))
            .map(|e| e["points"].as_f64().unwrap_or(0.))
            .sum();
        if t > 0. {
            out.insert(z.clone(), t);
        }
    }
    out
}

fn money(rows: &[(Value, bool)]) -> Value {
    if rows.is_empty() {
        return Value::Null;
    }
    let mut sum = json!({"day": rows[0].0["day"], "yesterday": rows.iter().all(|r| r.1)});
    for k in LEDGER {
        sum[k] = rows
            .iter()
            .map(|r| r.0[k].as_f64().unwrap_or(0.))
            .sum::<f64>()
            .into();
    }
    sum
}

fn split(trips: f64, rail: f64, walk: f64, none: f64) -> Value {
    if trips <= 0. {
        return Value::Null;
    }
    let pct = |v: f64| 100. * v / trips;
    json!({"train": pct(rail), "walk": pct(walk), "drive": (100. - pct(rail) - pct(walk) - pct(none)).max(0.), "none": pct(none)})
}

/// `manifest` as `NativeRidership.networkLayers` takes it; empty for the stations, lines and money
/// only (no zones without its boundaries: the totals then hold every built station).
pub(super) fn summary(save: &[u8], manifest: &str) -> Result<Value, String> {
    let mut world = World::open(save).ok_or("Simpanan tidak dapat dibaca")?;
    let inputs = if manifest.trim().is_empty() {
        None
    } else {
        let files: crate::demand::DemandFiles =
            serde_json::from_str(manifest).map_err(|e| e.to_string())?;
        Some(files.read()?)
    };
    let forecast = match &inputs {
        Some(i) => Some(i.forecast(save, &mut world)?),
        None => None,
    };
    let api = &world.api;
    let zone_of = |lon: f64, lat: f64| -> Option<&'static str> {
        inputs.as_ref().map(|i| i.geography().zone(lon, lat))
    };
    // built stations by zone ("" outside every zone read, or every one with no manifest)
    let (mut built, mut all) = (Vec::new(), Vec::new());
    let mut zone = BTreeMap::new();
    for n in api.nodes_info().chunks_exact(10) {
        if n[7] <= 0. {
            continue;
        }
        let ll = api.to_lonlat(n[1], n[2]);
        all.push((ll[0], ll[1]));
        if n[8] == 1. {
            built.push((ll[0], ll[1]));
            zone.insert(n[0] as u32, zone_of(ll[0], ll[1]).unwrap_or(""));
        }
    }
    let counted = |z: &str| inputs.is_none() || !z.is_empty();
    let mut stations: BTreeMap<&str, usize> = BTreeMap::new();
    for z in zone.values().filter(|z| counted(z)) {
        *stations.entry(z).or_default() += 1;
    }
    // listed lines (no hidden twins) with a stop at a built station of the zone
    let mut lines: BTreeMap<&str, usize> = BTreeMap::new();
    let mut any = 0;
    for row in api.lines_info().chunks_exact(22) {
        let id = row[0] as u32;
        if api.world().net.is_twin(id) {
            continue;
        }
        let zones: BTreeSet<&str> = api
            .line_stops(id)
            .iter()
            .filter_map(|s| zone.get(s).copied())
            .filter(|z| counted(z))
            .collect();
        if !zones.is_empty() {
            any += 1;
        }
        for z in zones {
            *lines.entry(z).or_default() += 1;
        }
    }
    let days = forecast.as_ref().map(|f| f.city_days());
    let score = points(&world.header, forecast.as_deref());
    let mut points_all = 0.;
    let mut cities = Vec::new();
    let (mut sums, mut ledgers, mut seen) = ([0.; 4], Vec::new(), false);
    for (&id, &n) in &stations {
        let day = days.and_then(|d| d.get(id));
        let f = if id.is_empty() {
            None
        } else {
            figures(&world.header, id)
        };
        let p = score.get(id).copied().unwrap_or(0.);
        if !id.is_empty() {
            points_all += p;
        }
        let mut row = json!({"id": id, "stations": n, "lines": lines.get(id).copied().unwrap_or(0), "points": p,
            "money": f.as_ref().map_or(Value::Null, |(v, y)| { let mut v = v.clone(); v["yesterday"] = (*y).into(); v })});
        if let Some(&[trips, rail, walk, none]) = day {
            seen = true;
            for (s, v) in sums.iter_mut().zip([trips, rail, walk, none]) {
                *s += v;
            }
            row["trips"] = trips.into();
            row["rail"] = rail.into();
            row["walk"] = walk.into();
            row["none"] = none.into();
            row["drive"] = (trips - rail - walk - none).max(0.).into();
            row["split"] = split(trips, rail, walk, none);
        }
        ledgers.extend(f);
        if !id.is_empty() {
            cities.push(row);
        }
    }
    if inputs.is_none() {
        // no zones: the whole ledger
        let whole = json!({"ledger": world.header["ledger"], "regions": {"": {"ledger": world.header["ledger"]}}});
        ledgers.extend(figures(&whole, ""));
    }
    let [trips, rail, walk, none] = sums;
    let mut totals = json!({
        "cities": stations.keys().filter(|z| !z.is_empty()).count(),
        "stations": stations.values().sum::<usize>(),
        "lines": any,
        "money": money(&ledgers),
        "points": points_all,
        "split": Value::Null,
    });
    if seen {
        totals["trips"] = trips.into();
        totals["rail"] = rail.into();
        totals["walk"] = walk.into();
        totals["none"] = none.into();
        totals["drive"] = (trips - rail - walk - none).max(0.).into();
        totals["split"] = split(trips, rail, walk, none);
    }
    let at = if built.is_empty() { &all } else { &built };
    let bounds = (!at.is_empty()).then(|| {
        let f =
            |g: fn(f64, f64) -> f64, s: f64, k: fn(&(f64, f64)) -> f64| at.iter().map(k).fold(s, g);
        json!([
            f(f64::min, f64::MAX, |p| p.0),
            f(f64::min, f64::MAX, |p| p.1),
            f(f64::max, f64::MIN, |p| p.0),
            f(f64::max, f64::MIN, |p| p.1)
        ])
    });
    // where the game opens: a zone id, "world" (web `WORLD_HOME`) or null
    Ok(json!({"cities": cities, "totals": totals, "bounds": bounds, "home": world.header["home"]}))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_StudioAllCities_summary(
    mut env: JNIEnv,
    _: JClass,
    save: JByteArray,
    manifest: JString,
) -> jstring {
    let result = (|| {
        let save = env.convert_byte_array(save).map_err(|e| e.to_string())?;
        let manifest: String = env.get_string(&manifest).map_err(|e| e.to_string())?.into();
        summary(&save, &manifest)
    })()
    .unwrap_or_else(|e: String| json!({"error": e}));
    env.new_string(result.to_string())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    /// The all cities figures of WORLD_SAVE with the manifest WORLD_ALL_CITIES (as
    /// `NativeRidership.inputs` writes it), printed and written to WORLD_ALL_CITIES_EXPORT when
    /// set: the web's `cityDemandViews` to compare with.
    #[test]
    #[ignore = "requires the pinned city packs and a network save"]
    fn all_cities() {
        let save = std::fs::read(std::env::var("WORLD_SAVE").unwrap()).unwrap();
        let manifest = std::env::var("WORLD_ALL_CITIES").unwrap();
        let out = super::summary(&save, &manifest).unwrap();
        let bare = super::summary(&save, "").unwrap();
        assert_eq!(bare["totals"]["stations"], out["totals"]["stations"]);
        assert!(bare["cities"].as_array().unwrap().is_empty());
        println!("{out:#}");
        if let Ok(path) = std::env::var("WORLD_ALL_CITIES_EXPORT") {
            std::fs::write(path, out.to_string()).unwrap();
        }
    }
}
