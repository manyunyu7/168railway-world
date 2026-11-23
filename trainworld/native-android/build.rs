// SPDX-License-Identifier: AGPL-3.0-only
// Compile the zone information used by Studio's localTime.ts without bundling the full registry.
use serde_json::Value;
use std::{env, fmt::Write, fs, path::PathBuf};

fn main() {
    const SOURCE: &str = "../app/src/game/registry.json";
    println!("cargo:rerun-if-changed={SOURCE}");
    let registry: Value = serde_json::from_slice(&fs::read(SOURCE).unwrap()).unwrap();
    const INCOME: &str = "../app/src/game/countryIncome.json";
    println!("cargo:rerun-if-changed={INCOME}");
    let incomes: Value = serde_json::from_slice(&fs::read(INCOME).unwrap()).unwrap();
    let usd = &incomes["usd"];
    let catalog = "android-lib/src/main/assets/world-native/cities.tsv";
    println!("cargo:rerun-if-changed={catalog}");
    let ids: std::collections::HashSet<_> = fs::read_to_string(catalog)
        .unwrap()
        .lines()
        .filter_map(|line| line.split_once('\t').map(|(id, _)| id.to_string()))
        .collect();
    let mut countries = String::from("const STARTING_COUNTRIES: &[(&str,&str)] = &[");
    for city in registry["cities"].as_array().unwrap() {
        let id = city["id"].as_str().unwrap();
        if ids.contains(id) {
            writeln!(
                countries,
                "({:?},{:?}),",
                id,
                city["country"].as_str().unwrap()
            )
            .unwrap();
        }
    }
    countries.push_str("];");
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("starting_countries.rs"),
        countries,
    )
    .unwrap();
    // Only coordinates and annual visits enter the native solver.
    const SITES: &str = "../app/src/game/attractions.json";
    println!("cargo:rerun-if-changed={SITES}");
    let sites: Value = serde_json::from_slice(&fs::read(SITES).unwrap()).unwrap();
    let mut binary = Vec::new();
    for row in sites["rows"].as_array().unwrap() {
        for column in 2..5 {
            binary.extend(row[column].as_f64().unwrap().to_le_bytes());
        }
    }
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    fs::write(out_dir.join("attractions.bin"), binary).unwrap();
    build_calendar(&out_dir);
    let mut places = String::from("const PLACES: &[(&str, usize, &str)] = &[\n");
    for city in registry["cities"].as_array().unwrap() {
        writeln!(
            places,
            "({:?}, {}, {:?}),",
            city["id"].as_str().unwrap(),
            city["place"].as_u64().unwrap(),
            city["country"].as_str().unwrap_or("")
        )
        .unwrap();
    }
    places.push_str("];");
    fs::write(out_dir.join("places.rs"), places).unwrap();
    let frame = &registry["market"]["frame"];
    let mut overlay = format!(
        "const MARKET_ORIGIN:(f64,f64)=({:?},{:?});\nconst OVERLAY_PLACES:&[(&str,u32,bool)]=&[\n",
        frame["lon"].as_f64().unwrap(),
        frame["lat"].as_f64().unwrap()
    );
    for c in registry["cities"].as_array().unwrap() {
        writeln!(
            overlay,
            "({:?},{},{}),",
            c["id"].as_str().unwrap(),
            c["place"].as_u64().unwrap(),
            c["market"].as_i64().unwrap_or(-1) >= 0
        )
        .unwrap();
    }
    overlay.push_str("];");
    fs::write(out_dir.join("overlay_places.rs"), overlay).unwrap();
    let mut boxes = String::from("const ZONE_BOXES: &[(&str, [f64; 4])] = &[\n");
    for city in registry["cities"].as_array().unwrap() {
        let bounds: [f64; 4] = std::array::from_fn(|i| city["box"][i].as_f64().unwrap());
        writeln!(boxes, "({:?}, {bounds:?}),", city["id"].as_str().unwrap()).unwrap();
    }
    boxes.push_str("];");
    fs::write(out_dir.join("zone_boxes.rs"), boxes).unwrap();
    let clock = registry["clockUtcOffset"].as_f64().unwrap();
    let mut output = format!(
        "const CLOCK_OFFSET: f64 = {clock:?};\nconst ZONES: &[(&str, f64, f64, f64, f64)] = &[\n"
    );
    for city in registry["cities"].as_array().unwrap() {
        let id = city["id"].as_str().unwrap();
        let lon = city["center"][0].as_f64().unwrap();
        let lat = city["center"][1].as_f64().unwrap();
        let offset = city["utcOffset"]
            .as_f64()
            .unwrap_or_else(|| (lon / 15. + 0.5).floor());
        // game/income.ts runScale, US price list where a country has no value.
        let price = city["country"]
            .as_str()
            .and_then(|country| usd[country].as_f64())
            .filter(|gdp| *gdp > 0.)
            .map_or(1., |gdp| {
                ((gdp / usd["US"].as_f64().unwrap()).sqrt() * 100.)
                    .round()
                    .max(1.)
                    / 100.
            });
        writeln!(output, "({id:?}, {lon:?}, {lat:?}, {offset:?}, {price:?}),").unwrap();
    }
    output.push_str("];\n");
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("time_zones.rs"),
        output,
    )
    .unwrap();
}

fn build_calendar(dir: &std::path::Path) {
    const SOURCE: &str = "../app/src/game/holidays.json";
    println!("cargo:rerun-if-changed={SOURCE}");
    let h: Value = serde_json::from_slice(&fs::read(SOURCE).unwrap()).unwrap();
    let mut countries = h["countries"]
        .as_object()
        .unwrap()
        .keys()
        .collect::<Vec<_>>();
    countries.sort();
    let mut bytes = Vec::new();
    for country in &countries {
        let c = country.as_str();
        let mut days = vec![0u8; 7 * 365];
        for w in 0..7 {
            let key = w.to_string();
            for d in 0..365 {
                let wd = (w + d) % 7;
                let k = h["weeks"][c][wd].as_u64().map_or(
                    if wd == 5 {
                        1
                    } else if wd == 6 {
                        2
                    } else {
                        0
                    },
                    |v| v as u8,
                );
                days[w * 365 + d] = k + 8 * u8::from(k != 0);
            }
            for v in h["workdays"][c][&key].as_array().into_iter().flatten() {
                days[w * 365 + v.as_u64().unwrap() as usize] = 0;
            }
            for row in h["countries"][c][&key].as_array().into_iter().flatten() {
                if !h["working"][c]
                    .as_array()
                    .is_some_and(|a| a.contains(&row[1]))
                {
                    days[w * 365 + row[0].as_u64().unwrap() as usize] = 2 + 8;
                }
            }
        }
        for w in 0..7i64 {
            for r in h["supers"][c][w.to_string()]
                .as_array()
                .into_iter()
                .flatten()
            {
                let start = r[0].as_i64().unwrap();
                let out = r[1].as_i64().unwrap();
                let away = r[2].as_i64().unwrap();
                for d in start..start + out + away + r[3].as_i64().unwrap() {
                    let dow = (w + d.div_euclid(365)).rem_euclid(7) as usize;
                    days[dow * 365 + d.rem_euclid(365) as usize] = if d < start + out {
                        4
                    } else if d < start + out + away {
                        3
                    } else {
                        5
                    };
                }
            }
        }
        bytes.extend(days);
    }
    fs::write(
        dir.join("calendar_ids.rs"),
        format!("const COUNTRIES:&[&str]=&{countries:?};"),
    )
    .unwrap();
    fs::write(dir.join("calendar.bin"), bytes).unwrap();
}
