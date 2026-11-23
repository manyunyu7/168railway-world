// SPDX-License-Identifier: AGPL-3.0-only
use serde_json::Value;
use std::sync::OnceLock;
use trainworld_sim::track::wasm::TrackApi;

include!(concat!(env!("OUT_DIR"), "/time_zones.rs"));

pub(super) fn install(api: &mut TrackApi) {
    static TABLE: OnceLock<Vec<f64>> = OnceLock::new();
    api.set_time_zones(TABLE.get_or_init(|| {
        // app/src/game/cities.ts CENTRE_REACH_M and localTime.ts timeZoneTable
        let mut table = vec![CLOCK_OFFSET, 120_000.];
        for &(_, lon, lat, offset, _) in ZONES {
            table.extend([lon, lat, offset]);
        }
        table
    }));
}

pub(super) fn day_shift(header: &Value) -> f64 {
    header["start"]
        .as_str()
        .and_then(|start| ZONES.iter().find(|(id, ..)| *id == start))
        .map(|(_, _, _, offset, _)| (offset - CLOCK_OFFSET) * 3600.)
        .or_else(|| header["dayShift"].as_f64())
        .unwrap_or(0.)
}

// The web uses cityWithin's centre fallback while geographic outlines are unloaded.
pub(super) fn running_price(lon: f64, lat: f64) -> f64 {
    let cos = lat.to_radians().cos();
    let limit = (120_000.0_f64 / 111_320.).powi(2);
    ZONES
        .iter()
        .filter_map(|&(_, x, y, _, price)| {
            let distance = ((x - lon) * cos).powi(2) + (y - lat).powi(2);
            (distance <= limit).then_some((distance, price))
        })
        .min_by(|a, b| a.0.total_cmp(&b.0))
        .map_or(1., |(_, price)| price)
}
