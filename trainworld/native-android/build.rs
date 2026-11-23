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
    let ids: std::collections::HashSet<_> = fs::read_to_string(catalog).unwrap().lines()
        .filter_map(|line| line.split_once('\t').map(|(id, _)| id.to_string())).collect();
    let mut countries = String::from("const STARTING_COUNTRIES: &[(&str,&str)] = &[");
    for city in registry["cities"].as_array().unwrap() {
        let id = city["id"].as_str().unwrap();
        if ids.contains(id) {
            writeln!(countries,"({:?},{:?}),",id,city["country"].as_str().unwrap()).unwrap();
        }
    }
    countries.push_str("];");
    fs::write(PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("starting_countries.rs"),countries).unwrap();
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
