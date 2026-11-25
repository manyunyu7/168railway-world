//! Times `SimulationEngine.openNetwork` stage by stage on the bundled scenarios, as the clock
//! opens them on Android: `NativeRidership.inputs` (the zone plan, the manifest), the files read,
//! the editor world opened and the forecast and account worked out.
//!
//! WORLD_CITY_DATA: a folder with the city packs (`<id>.json`, `<id>.bin`, `<id>.market.json`,
//! `<id>.boundary.geojson`, `outlines.json`, `market-drives.bin`); WORLD_MARKET_INDEX: the
//! regional index (`market-….json.gz`); WORLD_SCENARIOS: comma separated scenario names (default
//! `jak,jak_mulai,indo`); WORLD_HOME: the home city (default `jak`).
//!
//! `cargo test --release --lib open_bench -- --ignored --nocapture`
use super::*;
use std::time::Instant;

/// The manifest `NativeRidership.inputs` (regional) makes for `save`.
pub(super) fn manifest(save: &[u8], dir: &str, home: &str) -> DemandFiles {
    let zones = crate::demand_zones::plan(save, std::path::Path::new(dir)).unwrap();
    let ids = |k: &str| -> Vec<String> {
        zones[k].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_owned()).collect()
    };
    let mut local = vec![home.to_owned()];
    for z in ids("local") {
        if !local.contains(&z) {
            local.push(z);
        }
    }
    let mut markets = ids("markets");
    markets.push(home.to_owned());
    markets.dedup();
    let mut seen = std::collections::BTreeSet::new();
    markets.retain(|m| seen.insert(m.clone()));
    let index = std::env::var("WORLD_MARKET_INDEX").unwrap();
    serde_json::from_value(json!({
        "local": local.iter().map(|z| [format!("{dir}/{z}.json"), format!("{dir}/{z}.bin")]).collect::<Vec<_>>(),
        "markets": markets.iter().map(|z| format!("{dir}/{z}.market.json")).collect::<Vec<_>>(),
        "index": index,
        "drives": format!("{dir}/market-drives.bin"),
        "boundaries": dir,
    }))
    .unwrap()
}

pub(super) fn scenario(name: &str) -> Vec<u8> {
    let path = format!(
        "{}/android-lib/src/main/assets/world-native/scenarios/{name}.save",
        env!("CARGO_MANIFEST_DIR")
    );
    std::fs::read(path).unwrap()
}

fn print(stages: &[(String, f64)]) {
    for (name, ms) in stages {
        eprintln!("  {ms:9.1} ms  {name}");
    }
}

#[test]
#[ignore = "requires the city packs (WORLD_CITY_DATA, WORLD_MARKET_INDEX)"]
fn open_network_stages() {
    let dir = std::env::var("WORLD_CITY_DATA").unwrap();
    let home = std::env::var("WORLD_HOME").unwrap_or_else(|_| "jak".into());
    let names = std::env::var("WORLD_SCENARIOS").unwrap_or_else(|_| "jak,jak_mulai,indo".into());
    for name in names.split(',') {
        let save = scenario(name);
        // the clock opens the editor's save (`Engine.save`): a world as saved
        for run in 0..2 {
            FORECAST.lock().unwrap().clear();
            let total = Instant::now();
            prof::start();
            let files = manifest(&save, &dir, &home);
            prof::mark("inputs: zone plan + manifest (Kotlin NativeRidership.inputs)");
            let text = serde_json::to_string(&json!({
                "local": files.local, "markets": files.markets, "index": files.index,
                "drives": files.drives, "boundaries": files.boundaries,
            }))
            .unwrap();
            let files: DemandFiles = serde_json::from_str(&text).unwrap();
            let mut world = World::open(&save).unwrap();
            prof::mark("open: World::open");
            let inputs = files.read().unwrap();
            prof::mark("open: DemandFiles::read");
            let (account, forecast) = inputs.account_and_forecast(&save, &mut world).unwrap();
            let stages = prof::take();
            let took = total.elapsed();
            eprintln!(
                "{name} run {run}: {took:?} total; local {:?} markets {}; lines {}",
                files.local.iter().map(|l| l[0].rsplit('/').next().unwrap().to_owned()).collect::<Vec<_>>(),
                files.markets.len(),
                forecast.map_lines().len(),
            );
            print(&stages);
            drop(account);

        }
    }
}
