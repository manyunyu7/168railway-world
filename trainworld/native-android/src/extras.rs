// SPDX-License-Identifier: AGPL-3.0-only
//! Paid parking and adverts: 168Railway's own (the owner's request), not the web's. Their settings
//! live in the header's `nativeExtras` while a world is open, so undo snapshots, edit candidates and
//! clock sessions carry them; on disk they are split off into a file beside the save
//! (`<save>.extras.json`, `WorldExtras.kt`), as the web refuses a header key it does not know
//! (`headerUnread`). A save without them opens as the web's.
//!
//! Money is the engine's US$M at the economy's scale (`ECONOMY`, as fares): rupiah per rider are
//! turned into US$ at the app's IDR rate (world-native/currencies.json) and scaled as fares are.
use super::World;
use crate::passenger_account::Forecast;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{Read, Write};
use trainworld_sim::track::{
    params::ECONOMY,
    upgrades::{PARK_RIDE, PARK_RIDE_MIN},
    wasm::TrackApi,
};

pub(crate) const KEY: &str = "nativeExtras";
/// Rupiah to the US$ (world-native/currencies.json, IDR).
pub(crate) const RP_PER_USD: f64 = 16000.;
/// A car park's fee a day, rupiah: the default, the most, and the stepper's step.
pub(crate) const FEE_DEFAULT: f64 = 5000.;
pub(crate) const FEE_MAX: f64 = 20000.;
pub(crate) const FEE_STEP: f64 = 1000.;
/// At the most fee the park and ride takes this share less off a ride (`bonus`).
pub(crate) const FEE_CUT: f64 = 0.6;
/// Car park sizes: name, slots, US$M to build from none (the small one comes with the park and
/// ride's own price), US$M upkeep a day.
/// Sized on the Jakarta scenario: its outer KRL stations draw 8.000 to 14.000 cars a day.
pub(crate) const SIZES: [(&str, f64, f64, f64); 3] =
    [("Kecil", 1000., 0., 0.001), ("Sedang", 4000., 5., 0.004), ("Besar", 12000., 15., 0.012)];
/// An advert contract's game days.
pub(crate) const AD_DAYS: f64 = 30.;
/// Adverts' rupiah per person: at a station each boarding and alighting, in a train each rider.
pub(crate) const AD_STATION_RP: f64 = 150.;
pub(crate) const AD_TRAIN_RP: f64 = 200.;
/// A contract's price, US$M, paid when signed: a base plus `AD_SHARE` of its money over the
/// contract as the last forecast has it, so a quiet station's advert does not pay.
pub(crate) const AD_STATION_BASE: f64 = 0.2;
pub(crate) const AD_TRAIN_BASE: f64 = 0.5;
pub(crate) const AD_SHARE: f64 = 0.4;
/// Minutes an advert adds to boarding there (the station) or onto it (the line): about 1% fewer
/// people at the busiest Jakarta station and on a KRL line (0,5 minutes took 2,9% and 1,8%;
/// 0,2 and 0,3 took 1,4% and 0,6%).
pub(crate) const AD_STATION_MIN: f32 = 0.15;
pub(crate) const AD_TRAIN_MIN: f32 = 0.4;

/// An advert contract's price, US$M, for money `day` a day (None: no forecast yet).
pub(crate) fn ad_price(trains: bool, day: Option<f64>) -> f64 {
    (if trains { AD_TRAIN_BASE } else { AD_STATION_BASE }) + AD_SHARE * AD_DAYS * day.unwrap_or(0.)
}

/// Rupiah from riders as engine money, US$M.
pub(crate) fn rupiah(rp: f64) -> f64 {
    rp / RP_PER_USD * ECONOMY / 1e6
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Park {
    pub fee: f64,
    pub size: usize,
}
impl Default for Park {
    fn default() -> Self {
        Self { fee: FEE_DEFAULT, size: 0 }
    }
}
impl Park {
    pub(crate) fn slots(&self) -> f64 {
        SIZES[self.size].1
    }
}

fn extras(h: &Value) -> &Value {
    &h[KEY]
}
/// Station `node`'s car park settings (the default where none were set).
pub(crate) fn park(h: &Value, node: u32) -> Park {
    let v = &extras(h)["park"][node.to_string()];
    Park {
        fee: v["fee"].as_f64().unwrap_or(FEE_DEFAULT).clamp(0., FEE_MAX),
        size: v["size"].as_u64().map_or(0, |s| (s as usize).min(SIZES.len() - 1)),
    }
}
/// The end of the advert contract at station (`trains` false) or line `id`, game seconds.
pub(crate) fn ad_until(h: &Value, trains: bool, id: u32) -> Option<f64> {
    extras(h)[if trains { "trainAds" } else { "ads" }][id.to_string()].as_f64()
}
fn ads(h: &Value, trains: bool) -> Vec<(u32, f64)> {
    extras(h)[if trains { "trainAds" } else { "ads" }]
        .as_object()
        .into_iter()
        .flatten()
        .filter_map(|(k, v)| Some((k.parse().ok()?, v.as_f64()?)))
        .collect()
}
/// The minutes a park and ride takes off a ride (`PARK_RIDE_MIN` free): less as the fee rises,
/// and only for the share of its users with a slot.
pub(crate) fn bonus(fee: f64, slots: f64, users: f64) -> f32 {
    let room = if users > slots { slots / users } else { 1. };
    (f64::from(PARK_RIDE_MIN) * (1. - fee.clamp(0., FEE_MAX) / FEE_MAX * FEE_CUT) * room) as f32
}
/// Stations with a park and ride.
fn parks(api: &TrackApi) -> Vec<u32> {
    api.station_upgrades()
        .chunks_exact(2)
        .filter(|p| p[1] as u8 & PARK_RIDE != 0)
        .map(|p| p[0])
        .collect()
}

/// The demand's terms (`DemandApi::set_terms`) for the stations `nodes` (the solve's station
/// order) and lines `lines`, at the header's clock; None where nothing differs from the web (no
/// park and ride, no advert running). `before`: the cars each park had the solve before.
pub(crate) fn demand_terms(
    h: &Value,
    api: &TrackApi,
    nodes: &[u32],
    lines: &[u32],
    before: &BTreeMap<u32, f64>,
) -> Option<(Vec<f32>, Vec<f32>, Vec<f32>)> {
    let clock = h["clock"].as_f64().unwrap_or(0.);
    let parks = parks(api);
    let stations: Vec<u32> = ads(h, false).into_iter().filter(|a| a.1 > clock).map(|a| a.0).collect();
    let trains: Vec<u32> = ads(h, true).into_iter().filter(|a| a.1 > clock).map(|a| a.0).collect();
    if parks.is_empty() && stations.is_empty() && trains.is_empty() {
        return None;
    }
    let ride = if parks.is_empty() {
        vec![]
    } else {
        nodes
            .iter()
            .map(|n| {
                if !parks.contains(n) {
                    return 0.;
                }
                let p = park(h, *n);
                bonus(p.fee, p.slots(), before.get(n).copied().unwrap_or(0.))
            })
            .collect()
    };
    let board = if stations.is_empty() {
        vec![]
    } else {
        nodes.iter().map(|n| if stations.contains(n) { AD_STATION_MIN } else { 0. }).collect()
    };
    let line = if trains.is_empty() {
        vec![]
    } else {
        lines.iter().map(|l| if trains.contains(l) { AD_TRAIN_MIN } else { 0. }).collect()
    };
    Some((ride, board, line))
}
/// What of the extras changes the demand, for the solve cache's key.
pub(crate) fn demand_key(h: &Value) -> Value {
    let clock = h["clock"].as_f64().unwrap_or(0.);
    let on = |trains| ads(h, trains).into_iter().filter(|a| a.1 > clock).map(|a| a.0).collect::<Vec<_>>();
    json!([extras(h)["park"], on(false), on(true)])
}

/// Boardings and alightings a day at each station, and riders a day on each line, on the average
/// day (`Forecast::day_stops`, stops laid out as the achievements read them).
pub(crate) fn traffic(f: &Forecast, api: &TrackApi) -> (BTreeMap<u32, f64>, BTreeMap<u32, f64>) {
    let (mut stations, mut lines) = (BTreeMap::new(), BTreeMap::new());
    for (id, (board, alight)) in f.day_stops() {
        let stops = api.line_stops(id);
        let n = stops.len();
        for (k, v) in board.iter().zip(&alight).enumerate() {
            let stop = if k < n { stops.get(k) } else { n.checked_sub(1 + k - n).and_then(|i| stops.get(i)) };
            if let Some(s) = stop {
                *stations.entry(*s).or_insert(0.) += v.0 + v.1;
            }
        }
        lines.insert(id, board.iter().sum::<f64>());
    }
    (stations, lines)
}

/// A car park's day: the cars it holds of `cars`, and their fees, US$M.
pub(crate) fn park_day(p: Park, cars: f64) -> (f64, f64) {
    let used = cars.min(p.slots());
    (used, used * rupiah(p.fee))
}

/// Upkeep a day of every car park, US$M.
pub(crate) fn park_upkeep_day(h: &Value, api: &TrackApi) -> f64 {
    parks(api).into_iter().map(|n| SIZES[park(h, n).size].3).sum()
}

/// The passenger account's parking and advert money (`timed_account`), from a forecast.
#[derive(Default, Clone, Debug)]
pub(crate) struct Daily {
    /// US$M a day from every car park.
    parking: f64,
    /// Contracts: their end, game seconds, and US$M a day.
    ads: Vec<(f64, f64)>,
}
impl Daily {
    pub(crate) fn new(h: &Value, api: &TrackApi, f: &Forecast) -> Self {
        let parking = parks(api).into_iter().map(|n| park_day(park(h, n), f.parked(n)).1).sum();
        let (stations, lines) = traffic(f, api);
        let mut all = vec![];
        for (n, until) in ads(h, false) {
            all.push((until, stations.get(&n).copied().unwrap_or(0.) * rupiah(AD_STATION_RP)));
        }
        for (l, until) in ads(h, true) {
            all.push((until, lines.get(&l).copied().unwrap_or(0.) * rupiah(AD_TRAIN_RP)));
        }
        Self { parking, ads: all }
    }
    /// Parking and advert money from game time `t0` to `t1`, US$M: a contract pays to its end.
    pub(crate) fn income(&self, t0: f64, t1: f64) -> (f64, f64) {
        let days = (t1 - t0).max(0.) / 86400.;
        let ads = self.ads.iter().map(|&(until, day)| day * (t1.min(until) - t0).max(0.) / 86400.).sum();
        (self.parking * days, ads)
    }
}

/// The extras' station and line ids as `TrackApi::save` renumbers them (dense, live only).
pub(crate) fn remap(h: &mut Value, nodes: &[u32], lines: &[u32]) {
    let Some(x) = h.get_mut(KEY).and_then(Value::as_object_mut) else { return };
    for (field, ids) in [("park", nodes), ("ads", nodes), ("trainAds", lines)] {
        let map: BTreeMap<String, String> =
            ids.iter().enumerate().map(|(dense, id)| (id.to_string(), dense.to_string())).collect();
        if let Some(old) = x.get_mut(field).and_then(Value::as_object_mut) {
            *old = std::mem::take(old).into_iter().filter_map(|(k, v)| map.get(&k).map(|d| (d.clone(), v))).collect();
        }
    }
}

fn set(h: &mut Value, field: &str, id: u32, v: Option<Value>) {
    if !h[KEY].is_object() {
        h[KEY] = json!({});
    }
    if !h[KEY][field].is_object() {
        h[KEY][field] = json!({});
    }
    let o = h[KEY][field].as_object_mut().unwrap();
    match v {
        Some(v) => {
            o.insert(id.to_string(), v);
        }
        None => {
            o.remove(&id.to_string());
        }
    }
    let x = h[KEY].as_object_mut().unwrap();
    x.retain(|_, v| v.as_object().is_none_or(|o| !o.is_empty()));
    if x.is_empty() {
        h.as_object_mut().unwrap().remove(KEY);
    }
}
/// Pays `price` US$M now, booked as construction; a sandbox pays nothing.
fn pay(w: &mut World, clock: f64, price: f64) -> Result<(), &'static str> {
    if price <= 0. || w.header["mode"] == "sandbox" {
        return Ok(());
    }
    let cash = w.api.cash();
    if cash < price {
        return Err("Saldo tidak cukup.");
    }
    w.api.set_cash(cash - price);
    w.header["cash"] = (cash - price).into();
    super::finance::book_charge(&mut w.header, clock, price, 0.);
    Ok(())
}
/// Station `node`'s car park: its fee (rupiah, to `FEE_STEP`) and size; a bigger size pays the
/// difference, a smaller one refunds nothing.
pub(crate) fn set_park(w: &mut World, node: u32, fee: f64, size: usize, clock: f64) -> Result<Value, &'static str> {
    let clock = w.native_clock(clock);
    let net = &w.api.world().net;
    if !net.is_station(node) || net.node_upgrades[node as usize] & PARK_RIDE == 0 {
        return Err("Stasiun ini belum punya tempat parkir.");
    }
    if !fee.is_finite() || !(0. ..=FEE_MAX).contains(&fee) || size >= SIZES.len() || !clock.is_finite() {
        return Err("Pengaturan parkir tidak valid.");
    }
    let fee = (fee / FEE_STEP).round() * FEE_STEP;
    super::finance::tick(w, clock);
    let old = park(&w.header, node);
    let price = (SIZES[size].2 - SIZES[old.size].2).max(0.);
    pay(w, clock, price)?;
    let next = Park { fee, size };
    set(&mut w.header, "park", node, (next != Park::default()).then(|| json!({"fee":fee,"size":size})));
    Ok(json!({"charge":price,"cash":w.api.cash()}))
}
/// Signs (or renews) the advert contract at station (`trains` false) or line `id` for `AD_DAYS`
/// from now, or from a running one's end; `on` false ends it at once, refunding nothing.
pub(crate) fn set_ad(w: &mut World, trains: bool, id: u32, on: bool, clock: f64) -> Result<Value, &'static str> {
    let clock = w.native_clock(clock);
    let net = &w.api.world().net;
    let ok = if trains { net.line_ok(id) } else { net.is_station(id) && net.node_built[id as usize] };
    if !ok || !clock.is_finite() {
        return Err("Iklan tidak tersedia di sini.");
    }
    super::finance::tick(w, clock);
    let field = if trains { "trainAds" } else { "ads" };
    if !on {
        set(&mut w.header, field, id, None);
        return Ok(json!({"charge":0,"cash":w.api.cash()}));
    }
    let price = ad_price(trains, ad_day(w, trains, id));
    pay(w, clock, price)?;
    let from = ad_until(&w.header, trains, id).filter(|&u| u > clock).unwrap_or(clock);
    set(&mut w.header, field, id, Some(json!(from + AD_DAYS * 86400.)));
    Ok(json!({"charge":price,"cash":w.api.cash()}))
}

/// An advert's money a day at station or line `id` by the latest forecast.
fn ad_day(w: &World, trains: bool, id: u32) -> Option<f64> {
    let f = forecast_for(w)?;
    let (stations, lines) = traffic(&f, &w.api);
    Some(if trains { lines.get(&id).copied().unwrap_or(0.) * rupiah(AD_TRAIN_RP) } else { stations.get(&id).copied().unwrap_or(0.) * rupiah(AD_STATION_RP) })
}
/// The inspector's view of a contract: running, days left (whole, up), its end.
fn contract(until: Option<f64>, clock: f64) -> Value {
    match until {
        Some(u) => json!({"signed":true,"active":u > clock,"daysLeft":((u - clock) / 86400.).ceil().max(0.),"until":u}),
        None => json!({"signed":false,"active":false,"daysLeft":0}),
    }
}
fn forecast_for(w: &World) -> Option<std::sync::Arc<Forecast>> {
    crate::demand::latest_forecast(&w.api.save())
}
/// The station inspector's Parkir and Iklan stasiun sections.
pub(crate) fn station(w: &World, node: u32, clock: f64) -> Value {
    let clock = w.native_clock(clock);
    let net = &w.api.world().net;
    if !net.is_station(node) {
        return json!({"error":"Stasiun tidak tersedia."});
    }
    let f = forecast_for(w);
    let people = f.as_ref().map(|f| traffic(f, &w.api).0.get(&node).copied().unwrap_or(0.));
    let has_park = net.node_upgrades[node as usize] & PARK_RIDE != 0;
    let p = park(&w.header, node);
    let sizes: Vec<_> = SIZES.iter().map(|s| json!({"name":s.0,"slots":s.1,"build":s.2,"upkeep":s.3})).collect();
    let mut parking = json!({"has":has_park,"fee":p.fee,"size":p.size,"slots":p.slots(),"sizes":sizes,
        "upkeepDay":SIZES[p.size].3,"feeMax":FEE_MAX,"feeStep":FEE_STEP,"bonusFree":PARK_RIDE_MIN});
    if let Some(f) = &f {
        let cars = f.parked(node);
        let (used, money) = park_day(p, cars);
        parking["cars"] = cars.into();
        parking["used"] = used.into();
        parking["incomeDay"] = money.into();
        parking["bonus"] = bonus(p.fee, p.slots(), cars).into();
    }
    let mut ad = contract(ad_until(&w.header, false, node), clock);
    ad["days"] = AD_DAYS.into();
    let day = people.map(|t| t * rupiah(AD_STATION_RP));
    ad["price"] = ad_price(false, day).into();
    if let (Some(t), Some(d)) = (people, day) {
        ad["people"] = t.into();
        ad["incomeDay"] = d.into();
    }
    json!({"parking":parking,"ad":ad,"built":net.node_built[node as usize],"forecast":f.is_some()})
}
/// The line panel's Iklan kereta section.
pub(crate) fn line(w: &World, id: u32, clock: f64) -> Value {
    let clock = w.native_clock(clock);
    if !w.api.world().net.line_ok(id) {
        return json!({"error":"Line tidak tersedia."});
    }
    let mut ad = contract(ad_until(&w.header, true, id), clock);
    ad["days"] = AD_DAYS.into();
    let riders = forecast_for(w).map(|f| traffic(&f, &w.api).1.get(&id).copied().unwrap_or(0.));
    let day = riders.map(|r| r * rupiah(AD_TRAIN_RP));
    ad["price"] = ad_price(true, day).into();
    if let (Some(r), Some(d)) = (riders, day) {
        ad["riders"] = r.into();
        ad["incomeDay"] = d.into();
    }
    ad
}

/// A save as persisted: without the extras, which are returned beside it (None: there were none,
/// and the bytes are those given). None for bytes that are no TWG1 save.
pub(crate) fn split(bytes: &[u8]) -> Option<(Vec<u8>, Option<Value>)> {
    let mut raw = Vec::new();
    GzDecoder::new(bytes).take((32 << 20) + 1).read_to_end(&mut raw).ok()?;
    if raw.len() > 32 << 20 || raw.get(0..4)? != b"TWG1" {
        return None;
    }
    let n = u32::from_le_bytes(raw.get(4..8)?.try_into().ok()?) as usize;
    let end = 8usize.checked_add(n)?;
    let mut header: Value = serde_json::from_slice(raw.get(8..end)?).ok()?;
    let Some(x) = header.as_object_mut()?.remove(KEY) else {
        return Some((bytes.to_vec(), None));
    };
    let text = serde_json::to_vec(&header).ok()?;
    let mut out = GzEncoder::new(Vec::new(), Compression::fast());
    out.write_all(b"TWG1").ok()?;
    out.write_all(&(text.len() as u32).to_le_bytes()).ok()?;
    out.write_all(&text).ok()?;
    out.write_all(raw.get(end..)?).ok()?;
    Some((out.finish().ok()?, Some(x)))
}
/// Puts extras read from beside a save into the open world (an empty or unreadable text: none).
pub(crate) fn attach(w: &mut World, text: &str) -> bool {
    match serde_json::from_str::<Value>(text) {
        Ok(v) if v.as_object().is_some_and(|o| !o.is_empty()) => {
            w.header[KEY] = v;
            true
        }
        _ => false,
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |s| s.into_raw())
}
fn answer(r: Result<Value, &'static str>) -> Value {
    r.unwrap_or_else(|e| json!({"error":e}))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_station(env: JNIEnv, _: JClass, handle: jlong, node: jint, clock: jdouble) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }.map_or(json!({"error":"Dunia belum siap."}), |w| station(w, node as u32, clock));
    string(&env, v)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_line(env: JNIEnv, _: JClass, handle: jlong, id: jint, clock: jdouble) -> jstring {
    let v = unsafe { (handle as *const World).as_ref() }.map_or(json!({"error":"Dunia belum siap."}), |w| line(w, id as u32, clock));
    string(&env, v)
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_setPark(env: JNIEnv, _: JClass, handle: jlong, node: jint, fee: jdouble, size: jint, clock: jdouble) -> jstring {
    let r = unsafe { (handle as *mut World).as_mut() }.ok_or("Dunia belum siap.").and_then(|w| set_park(w, node as u32, fee, size.max(0) as usize, clock));
    string(&env, answer(r))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_setAd(env: JNIEnv, _: JClass, handle: jlong, trains: jboolean, id: jint, on: jboolean, clock: jdouble) -> jstring {
    let r = unsafe { (handle as *mut World).as_mut() }.ok_or("Dunia belum siap.").and_then(|w| set_ad(w, trains != 0, id as u32, on != 0, clock));
    string(&env, answer(r))
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_attach(mut env: JNIEnv, _: JClass, handle: jlong, text: JString) -> jboolean {
    let text: String = env.get_string(&text).map(Into::into).unwrap_or_default();
    unsafe { (handle as *mut World).as_mut() }.is_some_and(|w| attach(w, &text)) as jboolean
}
/// `split` for Kotlin: the extras' JSON length (4 bytes, big-endian; 0 for none), the JSON, then
/// the save as persisted; null for no TWG1 save.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_WorldExtras_split(env: JNIEnv, _: JClass, bytes: JByteArray) -> jbyteArray {
    let Some((save, x)) = env.convert_byte_array(bytes).ok().and_then(|b| split(&b)) else {
        return std::ptr::null_mut();
    };
    let text = x.map(|v| v.to_string()).unwrap_or_default().into_bytes();
    let mut out = Vec::with_capacity(4 + text.len() + save.len());
    out.extend((text.len() as u32).to_be_bytes());
    out.extend(text);
    out.extend(save);
    env.byte_array_from_slice(&out).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parked_world() -> (World, u32, f64) {
        let mut w = World::workshop();
        let clock = 111600.;
        w.build_service([0., 0.], [1600., 0.], "Parkir", clock - 86400.).unwrap();
        let n = (0..w.api.world().net.node_count() as u32).find(|&n| w.api.world().net.is_station(n)).unwrap();
        super::super::station_facilities::edit(&mut w, n, PARK_RIDE, true, clock).unwrap();
        (w, n, clock)
    }

    #[test]
    fn parking_money_is_fee_times_cars_up_to_the_slots() {
        let p = Park { fee: 5000., size: 0 };
        let (used, money) = park_day(p, 120.);
        assert_eq!(used, 120.);
        // Rp 5.000 = US$0,3125, x100 economy = US$31,25 a car
        assert!((money - 120. * 31.25e-6).abs() < 1e-12);
        let (used, money) = park_day(p, 3000.);
        assert_eq!(used, 1000.);
        assert!((money - 1000. * 31.25e-6).abs() < 1e-12);
        assert_eq!(park_day(Park { fee: 0., size: 2 }, 900.).1, 0.);
    }

    #[test]
    fn a_higher_fee_or_a_full_park_takes_less_off_the_ride() {
        assert_eq!(bonus(0., 300., 100.), 10.);
        assert!((ad_price(true, Some(1.)) - (AD_TRAIN_BASE + AD_SHARE * 30.)).abs() < 1e-12);
        assert!((bonus(5000., 300., 100.) - 8.5).abs() < 1e-6);
        assert!((bonus(FEE_MAX, 300., 100.) - 4.).abs() < 1e-6);
        assert!(bonus(10000., 300., 100.) < bonus(5000., 300., 100.));
        // full: only the slots' share of the users gets it
        assert!((bonus(0., 300., 600.) - 5.).abs() < 1e-6);
    }

    #[test]
    fn settings_charge_and_survive_save_and_open_without_touching_the_save_bytes() {
        let (mut w, n, clock) = parked_world();
        w.header["mode"] = "normal".into();
        w.api.set_unlimited(false);
        w.api.set_cash(100.);
        w.header["cash"] = 100.into();
        let before = w.save(clock).unwrap();
        assert!(split(&before).unwrap().1.is_none(), "no extras: the bytes as they are");
        assert_eq!(split(&before).unwrap().0, before);
        assert!(set_park(&mut w, n, 7400., 2, clock).is_ok());
        assert_eq!(w.api.cash(), 85.);
        assert_eq!(park(&w.header, n), Park { fee: 7000., size: 2 });
        // smaller refunds nothing
        set_park(&mut w, n, 7000., 1, clock).unwrap();
        assert_eq!(w.api.cash(), 85.);
        set_park(&mut w, n, 7000., 2, clock).unwrap();
        assert_eq!(w.api.cash(), 75.);
        // no forecast of this world: the advert costs its base
        let price = ad_price(false, ad_day(&w, false, n));
        assert!(price >= AD_STATION_BASE);
        set_ad(&mut w, false, n, true, clock).unwrap();
        assert!((w.api.cash() - (75. - price)).abs() < 1e-9);
        let saved = w.save(clock).unwrap();
        let back = World::open(&saved).unwrap();
        // dense ids: the station keeps its place among live nodes
        let dense = (0..n).filter(|&i| w.api.world().net.node_ok(i)).count() as u32;
        assert_eq!(park(&back.header, dense), Park { fee: 7000., size: 2 });
        assert_eq!(ad_until(&back.header, false, dense), Some(clock + 30. * 86400.));
        // on disk: the save without them reads as the web's, header and network alike
        let (disk, x) = split(&saved).unwrap();
        let x = x.unwrap();
        let plain = World::open(&disk).unwrap();
        assert!(plain.header.get(KEY).is_none());
        let mut a = back.header.clone();
        a.as_object_mut().unwrap().remove(KEY);
        assert_eq!(a, plain.header);
        assert_eq!(back.api.save(), plain.api.save());
        // the file beside it brings them back
        let mut reopened = World::open(&disk).unwrap();
        assert!(attach(&mut reopened, &x.to_string()));
        assert_eq!(reopened.header, back.header);
        assert!(!attach(&mut reopened, ""));
        // all back to the defaults: no extras left in the header
        set_park(&mut reopened, dense, FEE_DEFAULT, 0, clock).unwrap();
        set_ad(&mut reopened, false, dense, false, clock).unwrap();
        assert!(reopened.header.get(KEY).is_none());
    }

    #[test]
    fn an_advert_contract_ends_exactly_after_thirty_days() {
        let (mut w, n, clock) = parked_world();
        set_ad(&mut w, false, n, true, clock).unwrap();
        let until = ad_until(&w.header, false, n).unwrap();
        assert_eq!(until - clock, 30. * 86400.);
        let daily = Daily { parking: 0., ads: vec![(until, 2.)] };
        let day = 86400.;
        assert!((daily.income(clock, clock + 30. * day).1 - 60.).abs() < 1e-9);
        assert!((daily.income(clock + 29. * day, clock + 31. * day).1 - 2.).abs() < 1e-9);
        assert_eq!(daily.income(until, until + day).1, 0.);
        assert_eq!(contract(Some(until), until - 1.)["active"], true);
        assert_eq!(contract(Some(until), until)["active"], false);
        assert_eq!(contract(Some(until), clock)["daysLeft"], 30.);
        // the demand reads the advert while it runs, and not after
        let mut h = w.header.clone();
        let t = demand_terms(&h, &w.api, &[n], &[], &BTreeMap::new()).unwrap();
        assert_eq!(t.1, vec![AD_STATION_MIN]);
        h["clock"] = until.into();
        let t = demand_terms(&h, &w.api, &[n], &[], &BTreeMap::new()).unwrap();
        assert!(t.1.is_empty());
        // renewing a running contract adds to its end
        set_ad(&mut w, false, n, true, clock + day).unwrap();
        assert_eq!(ad_until(&w.header, false, n), Some(until + 30. * day));
    }
}
