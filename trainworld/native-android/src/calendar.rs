//! Local game years are 365 days and start one weekday later each year.
include!(concat!(env!("OUT_DIR"), "/calendar_ids.rs"));
static DAYS: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/calendar.bin"));
pub(super) fn kind_row(country: &str, day: i64) -> (usize, usize) {
    let doy = (day + 25).rem_euclid(365);
    let jan = (day - doy - 1).rem_euclid(7);
    if let Ok(id) = COUNTRIES.binary_search(&country) {
        let byte = DAYS[id * 7 * 365 + jan as usize * 365 + doy as usize];
        (usize::from(byte & 7), usize::from(byte >> 3))
    } else {
        let k = trainworld_sim::track::params::day_kind_at(day as f64 * 86400.);
        (k, usize::from(k != 0))
    }
}
