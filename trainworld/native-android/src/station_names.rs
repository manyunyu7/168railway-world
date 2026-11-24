// SPDX-License-Identifier: AGPL-3.0-only
//! A station's suggested names for the rename dialog's "Nama usulan berikutnya" (web
//! map/stationNames.ts `nextSuggestion`, game/placeList.ts `hoodsNear`, `townsNear`, `placeName`):
//! a small neighbourhood near it, then the city or town it lies in, each avoiding the other
//! stations' names; a tap steps to the next, wrapping round. The places are the loaded city packs'
//! (the list new stations are named from); the web's real stations and cross streets come from map
//! tiles Android does not have.
use super::*;
use std::sync::{Mutex, OnceLock};
use trainworld_sim::track::frame::great_circle;

/// A city pack's place: frame x, y, kind (the pack's `kinds`: city, town, borough, suburb, quarter,
/// village, neighbourhood, hamlet), population (0 unknown) and name.
#[derive(Clone, Debug)]
pub(crate) struct Place {
    pub x: f64,
    pub y: f64,
    pub kind: u8,
    pub pop: f64,
    pub name: String,
}

const CITY: u8 = 0;
const TOWN: u8 = 1;
const SUBURB: u8 = 3;
const QUARTER: u8 = 4;
const VILLAGE: u8 = 5;
const NEIGHBOURHOOD: u8 = 6;
const HAMLET: u8 = 7;

/// Web `NAMING_M`: kinds a station may be named after, within this many metres.
fn naming_m(kind: u8) -> f64 {
    match kind {
        NEIGHBOURHOOD => 2500.,
        QUARTER => 3000.,
        SUBURB | 2 => 4000.,
        HAMLET => 3000.,
        VILLAGE => 4000.,
        TOWN => 5000.,
        CITY => 6000.,
        _ => 2500.,
    }
}
const HOOD_PEER_MAX_M: f64 = 3500.;
const HOOD_POP_MAX: f64 = 200_000.;
const HOOD_REACH: f64 = 1.5;
const HOOD_REACH_MIN_M: f64 = 800.;
/// A taken nearest place gives way to an untaken one at most this much further away.
const PLACE_SLACK_M: f64 = 800.;
const COMPASS: [&str; 8] = ["east", "northeast", "north", "northwest", "west", "southwest", "south", "southeast"];

fn store() -> &'static Mutex<Vec<Place>> {
    static PLACES: OnceLock<Mutex<Vec<Place>>> = OnceLock::new();
    PLACES.get_or_init(|| Mutex::new(Vec::new()))
}

pub(crate) fn set_places(rows: Vec<Place>) {
    *store().lock().unwrap_or_else(|e| e.into_inner()) = rows;
}

/// A place near the station: metres to its label, and the station's offset from it (east, north).
struct Near<'a> {
    place: &'a Place,
    d: f64,
    dx: f64,
    dy: f64,
}

fn is_hood(kind: u8) -> bool {
    matches!(kind, NEIGHBOURHOOD | QUARTER | SUBURB)
}

/// Web `townReach`: how far a settlement's name reaches, 0 for a place that is no settlement.
fn town_reach(p: &Place) -> f64 {
    match p.kind {
        CITY => (4500. * (p.pop / 1e6).sqrt()).clamp(6000., 20_000.),
        TOWN => 5000.,
        VILLAGE => 4000.,
        HAMLET => 3000.,
        _ => 0.,
    }
}

/// Web `endsInStation`: a stop's own label in OSM never names a station, unless it is a settlement.
fn ends_in_station(name: &str, kind: u8) -> bool {
    let n = name.trim_end().trim_end_matches('.').to_lowercase();
    let last = n.rsplit(|c: char| !c.is_alphanumeric()).next().unwrap_or("");
    matches!(last, "station" | "sta" | "stn") && !matches!(kind, CITY | TOWN | VILLAGE | HAMLET)
}

/// Web `placeName`: the first place's name, another within PLACE_SLACK_M further when it is taken,
/// the way the station lies from it ("North Kemang"), or a number ("Kemang 2"); None with none.
fn place_name(near: &[&Near], taken: &dyn Fn(&str) -> bool) -> Option<String> {
    let first = near.first()?;
    if let Some(free) = near.iter().find(|n| n.d <= first.d + PLACE_SLACK_M && !taken(&n.place.name)) {
        return Some(free.place.name.clone());
    }
    let name = &first.place.name;
    if first.d > 150. {
        let a = first.dy.atan2(first.dx);
        let way = COMPASS[((a / (std::f64::consts::PI / 4.)).round() as i64 + 8).rem_euclid(8) as usize];
        let named = format!("{}{} {name}", way[..1].to_uppercase(), &way[1..]);
        if !taken(&named) {
            return Some(named);
        }
    }
    (2..).map(|k| format!("{name} {k}")).find(|n| !taken(n))
}

/// Web `suggestNames` without the tiles: the small neighbourhood and the town a point at frame
/// (x, y) would be named after, each avoiding `taken`.
pub(crate) fn suggestions(api: &TrackApi, places: &[Place], x: f64, y: f64, taken: &dyn Fn(&str) -> bool) -> [Option<String>; 2] {
    let at = api.to_lonlat(x, y);
    let lat_m = 111_320.;
    let lon_m = lat_m * at[1].to_radians().cos();
    let measured: Vec<Near> = places
        .iter()
        .filter(|p| !p.name.trim().is_empty() && !ends_in_station(&p.name, p.kind))
        .map(|p| {
            let ll = api.to_lonlat(p.x, p.y);
            Near { place: p, d: great_circle(at[0], at[1], ll[0], ll[1]), dx: (at[0] - ll[0]) * lon_m, dy: (at[1] - ll[1]) * lat_m }
        })
        .filter(|n| n.d <= 20_000.)
        .collect();
    // web hoodsNear over placesNear: small subdivisions, nearest first
    let mut hoods: Vec<&Near> = measured
        .iter()
        .filter(|n| n.d <= naming_m(n.place.kind) && is_hood(n.place.kind) && n.place.pop <= HOOD_POP_MAX)
        .filter(|n| {
            let peer = places
                .iter()
                .filter(|o| o.kind == n.place.kind && !std::ptr::eq(*o, n.place))
                .map(|o| (o.x - n.place.x).hypot(o.y - n.place.y))
                .fold(f64::INFINITY, f64::min);
            let s = if peer <= HOOD_PEER_MAX_M { peer } else { f64::INFINITY };
            s.is_finite() && n.d <= (HOOD_REACH * s).max(HOOD_REACH_MIN_M)
        })
        .collect();
    hoods.sort_by(|a, b| a.d.total_cmp(&b.d));
    // web townsNear: the settlement the point lies in, nearest relative to its reach first
    let mut towns: Vec<(f64, &Near)> = measured
        .iter()
        .filter_map(|n| {
            let r = town_reach(n.place);
            (r > 0. && n.d / r <= 1.).then_some((n.d / r, n))
        })
        .collect();
    towns.sort_by(|a, b| a.0.total_cmp(&b.0));
    let towns: Vec<&Near> = towns.into_iter().map(|t| t.1).collect();
    [place_name(&hoods, taken), place_name(&towns, taken)
    ]
}

/// Web `nextSuggestion`: the suggestion after `current`, wrapping round (the first when `current` is
/// none of them); None when there is nothing else to offer.
pub(crate) fn next_name(list: &[Option<String>], current: &str) -> Option<String> {
    let mut uniq: Vec<&String> = Vec::new();
    for n in list.iter().flatten() {
        if !uniq.contains(&n) {
            uniq.push(n);
        }
    }
    if uniq.is_empty() {
        return None;
    }
    let i = uniq.iter().position(|n| n.as_str() == current).map_or(0, |i| i + 1);
    let next = uniq[i % uniq.len()];
    (next != current).then(|| next.clone())
}

impl World {
    /// The next suggested name for station `node`, or None.
    pub(crate) fn next_station_name(&self, node: u32, current: &str) -> Option<String> {
        let nodes = self.api.nodes_info();
        let row = nodes.chunks_exact(10).find(|r| r[0] as u32 == node)?;
        let others: Vec<String> = nodes
            .chunks_exact(10)
            .filter(|r| r[7] > 0. && r[0] as u32 != node)
            .map(|r| self.api.node_name(r[0] as u32))
            .collect();
        let taken = |n: &str| others.iter().any(|o| o == n);
        let places = store().lock().unwrap_or_else(|e| e.into_inner());
        next_name(&suggestions(&self.api, &places, row[1], row[2], &taken), current)
    }
}

/// The loaded cities' places: x, y per place, their kinds and populations, names one per line.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationNames_places(
    mut env: JNIEnv,
    _: JClass,
    xy: jni::objects::JDoubleArray,
    kinds: jni::objects::JIntArray,
    pops: jni::objects::JDoubleArray,
    names: JString,
) {
    let n = env.get_array_length(&xy).unwrap_or(0).max(0) as usize;
    let mut raw = vec![0.; n];
    let mut kind = vec![0i32; n / 2];
    let mut pop = vec![0.; n / 2];
    if env.get_double_array_region(&xy, 0, &mut raw).is_err()
        || env.get_int_array_region(&kinds, 0, &mut kind).is_err()
        || env.get_double_array_region(&pops, 0, &mut pop).is_err()
    {
        return;
    }
    let names: String = env.get_string(&names).map(|v| v.into()).unwrap_or_default();
    let rows = raw
        .chunks_exact(2)
        .zip(names.split('\n'))
        .enumerate()
        .filter(|(_, (p, _))| p[0].is_finite() && p[1].is_finite())
        .map(|(i, (p, name))| Place { x: p[0], y: p[1], kind: kind[i].clamp(0, 255) as u8, pop: pop[i].max(0.), name: name.to_owned() })
        .collect();
    set_places(rows);
}

/// The next suggested name for station `node` after `current`; empty when there is none.
#[no_mangle]
pub extern "system" fn Java_world_nativegame_StationNames_next(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    node: i32,
    current: JString,
) -> jstring {
    let current: String = env.get_string(&current).map(|v| v.into()).unwrap_or_default();
    let next = unsafe { (handle as *const World).as_ref() }
        .filter(|_| node >= 0)
        .and_then(|w| w.next_station_name(node as u32, current.trim()))
        .unwrap_or_default();
    env.new_string(next).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn place(x: f64, y: f64, kind: u8, pop: f64, name: &str) -> Place {
        Place { x, y, kind, pop, name: name.into() }
    }

    #[test]
    fn a_station_steps_through_its_neighbourhood_and_town_and_wraps() {
        let world = World::workshop();
        let places = vec![
            place(300., 0., NEIGHBOURHOOD, 0., "Kemang"),
            place(2000., 0., NEIGHBOURHOOD, 0., "Cipete"),
            place(5000., 0., CITY, 9e6, "Jakarta"),
        ];
        let none = |_: &str| false;
        let s = suggestions(&world.api, &places, 0., 0., &none);
        assert_eq!(s, [Some("Kemang".into()), Some("Jakarta".into())]);
        assert_eq!(next_name(&s, "Stasiun 1").as_deref(), Some("Kemang"));
        assert_eq!(next_name(&s, "Kemang").as_deref(), Some("Jakarta"));
        assert_eq!(next_name(&s, "Jakarta").as_deref(), Some("Kemang"));
        // a taken name gives way to the next place within 800 m further, else the way from it
        let taken = |n: &str| n == "Kemang";
        let s = suggestions(&world.api, &places, 0., 0., &taken);
        assert_eq!(s[0].as_deref(), Some("West Kemang"));
        let near = vec![place(100., 0., NEIGHBOURHOOD, 0., "Kemang"), place(2000., 0., NEIGHBOURHOOD, 0., "Cipete")];
        let s = suggestions(&world.api, &near, 0., 0., &taken);
        assert_eq!(s[0].as_deref(), Some("Kemang 2"), "within 150 m it is numbered");
    }

    #[test]
    fn nothing_near_offers_nothing() {
        let world = World::workshop();
        let none = |_: &str| false;
        let s = suggestions(&world.api, &[place(90_000., 0., CITY, 1e6, "Bogor")], 0., 0., &none);
        assert_eq!(s, [None, None]);
        assert_eq!(next_name(&s, "Stasiun 1"), None);
        // the only suggestion is the name it has: nothing else to offer
        assert_eq!(next_name(&[Some("Kemang".into()), None], "Kemang"), None);
        assert!(ends_in_station("Penn Sta.", NEIGHBOURHOOD) && !ends_in_station("Huntington Station", TOWN));
    }
}
