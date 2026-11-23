//! Original regional-market solver with one or more open zones and the global registry.
use flate2::read::GzDecoder;
use std::io::Read;
use trainworld_sim::demand::intercity::{IntercityApi, MarketPacks};

pub(super) fn open(index: &[u8], drives: &[u8], zones: &[&str]) -> Result<IntercityApi, String> {
    let mut world = String::new();
    GzDecoder::new(index)
        .take(24 * 1024 * 1024 + 1)
        .read_to_string(&mut world)
        .map_err(|e| e.to_string())?;
    if world.len() > 24 * 1024 * 1024 {
        return Err("Indeks wilayah terlalu besar".into());
    }
    let mut packs = MarketPacks::new(&world)?;
    drop(world);
    packs.set_drives(drives)?;
    for zone in zones {
        packs.add(zone)?;
    }
    let mut market = packs.market()?;
    market.set_sampled(true);
    Ok(market)
}
