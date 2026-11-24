//! Daily legs at both ends of each cell, including regional trips and terminal traffic.
use std::collections::HashMap;
use trainworld_sim::{
    demand::{api::DemandApi, days::AVERAGE, everyday::EverydayApi, intercity::IntercityApi},
    track::frame::{Frame, FrameKind},
};
include!(concat!(env!("OUT_DIR"), "/overlay_places.rs"));
const AIRPORT: u32 = 4;
fn market_modes(m: &IntercityApi, place: u32, xy: &[f32], kinds: u32) -> Vec<f64> {
    let day = AVERAGE as u32;
    let mut base = m.zone_legs_day(place, xy, day, 31, kinds, false);
    let network = m.zone_legs_day(place, xy, day, 31, kinds, true);
    for (i, (a, v)) in base.iter_mut().zip(network).enumerate() {
        if i % 3 != 0 {
            *a += v;
        }
    }
    base
}
fn distribute(modes: &mut [f32], n: usize, cell: usize, end: usize, weight: f32, legs: &[f64]) {
    if legs[0] <= 0. {
        return;
    }
    let rail = legs[1].max(0.);
    let walk = legs[2].max(0.);
    for (k, v) in [rail, walk, (legs[0] - rail - walk).max(0.)]
        .into_iter()
        .enumerate()
    {
        let offset = (end * 3 + k) * n + cell;
        modes[offset] = (f64::from(modes[offset]) + f64::from(weight) * v) as f32;
    }
}
fn airport(m: &IntercityApi, place: u32, xy: &[f32], weights: &[f32], modes: &mut [f32]) {
    let n = xy.len() / 2;
    let mut at = vec![];
    let mut positions = vec![];
    let mut counts = vec![];
    for i in 0..n {
        let w = [3, 4, 5, 7]
            .into_iter()
            .map(|k| f64::from(weights[k * n + i]))
            .sum::<f64>() as f32;
        if w > 0. {
            at.push(i);
            positions.extend(&xy[2 * i..2 * i + 2]);
            counts.push(w);
        }
    }
    if at.is_empty() {
        return;
    }
    let legs = |rail| m.airport_legs_day(place, 1, &positions, &counts, AVERAGE as u32, 31, rail);
    let mut total = legs(false);
    for (v, r) in total.iter_mut().zip(legs(true)) {
        *v += r;
    }
    for (i, v) in at.into_iter().zip(total.chunks_exact(3)) {
        distribute(modes, n, i, 1, 1., v);
    }
}
/// The map's purpose filter as the web names it (`FILTER_PURPOSES`, app/src/game/purposes.ts), in
/// its order, each with the everyday kinds it counts (school counts university, T-192). Work is the
/// commutes alone; longer trips have no filter of their own and show under every purpose only.
pub(super) const PURPOSES: [(&str, &[u32]); 7] = [
    ("work", &[]),
    ("school", &[2, 3]),
    ("shopping", &[0]),
    ("social", &[1]),
    ("medical", &[5]),
    ("airport", &[AIRPORT]),
    ("attractions", &[6]),
];
/// The regional layer's kinds bit for commutes (`REG_COMMUTES` in workers/demandClient.ts).
const REG_COMMUTES: u32 = 2;
/// Each cell's share of its 600 m regional square, as `regionalInto` spreads a square's legs.
struct Squares {
    mxy: Vec<f32>,
    centres: Vec<f32>,
    index: Vec<usize>,
    share: Vec<[f32; 2]>,
}
fn squares(frame: Frame, s: &DemandApi, xy: &[f32], shared: bool) -> Squares {
    let n = xy.len() / 2;
    let market_frame = Frame::new(FrameKind::Equirect, MARKET_ORIGIN.0, MARKET_ORIGIN.1);
    let mxy: Vec<_> = xy
        .chunks_exact(2)
        .flat_map(|p| {
            if !shared {
                return [p[0], p[1]];
            }
            let (lon, lat) = frame.to_lonlat(p[0].into(), p[1].into());
            let (x, y) = market_frame.from_lonlat(lon, lat);
            [x as f32, y as f32]
        })
        .collect();
    let mut lookup = HashMap::new();
    let mut index = vec![];
    let mut centres = vec![];
    let mut totals: Vec<[f64; 3]> = vec![];
    let people = s.cell_commuters();
    for (i, p) in mxy.chunks_exact(2).enumerate() {
        let key = ((p[0] / 600.).floor() as i32, (p[1] / 600.).floor() as i32);
        let j = *lookup.entry(key).or_insert_with(|| {
            centres.extend([(key.0 as f32 + 0.5) * 600., (key.1 as f32 + 0.5) * 600.]);
            totals.push([0.; 3]);
            totals.len() - 1
        });
        index.push(j);
        totals[j][0] += f64::from(people[i]);
        totals[j][1] += f64::from(people[i + n]);
        totals[j][2] += 1.;
    }
    let share = index
        .iter()
        .enumerate()
        .map(|(i, &j)| {
            [0, 1].map(|end| {
                let sum = totals[j][end];
                (if sum > 0. {
                    f64::from(people[end * n + i]) / sum
                } else {
                    1. / totals[j][2]
                }) as f32
            })
        })
        .collect();
    Squares {
        mxy,
        centres,
        index,
        share,
    }
}
impl Squares {
    /// The regional legs of the kinds in `kinds` added to `modes`, each square's spread over its cells.
    fn add(&self, m: &IntercityApi, place: u32, kinds: u32, modes: &mut [f32]) {
        let legs = market_modes(m, place, &self.centres, kinds);
        let n = self.index.len();
        for (i, &j) in self.index.iter().enumerate() {
            for end in 0..2 {
                let off = j * 6 + 3 * end;
                if let Some(v) = legs.get(off..off + 3) {
                    distribute(modes, n, i, end, self.share[i][end], v);
                }
            }
        }
    }
}
/// The commutes' legs per cell (eight blocks), both directions of each commuter's day.
fn commutes(s: &DemandApi, day: u32, n: usize) -> Result<Vec<f32>, String> {
    let sums = s.sub_sums_day(day);
    let mut modes = s.cell_modes_day(&sums, day);
    modes.extend(s.cell_unreach_day(&sums, day));
    if modes.len() != 8 * n {
        return Err("Jumlah sel perjalanan tidak cocok".into());
    }
    for v in &mut modes {
        *v *= 2.;
    }
    Ok(modes)
}
/// One everyday purpose's legs per cell (eight blocks), the kinds it counts added up.
fn everyday(e: &EverydayApi, kinds: &[u32], day: u32, n: usize) -> Vec<f32> {
    let mut modes = vec![0f32; 8 * n];
    for &k in kinds {
        let mut part = e.cell_modes_of_day(k, day);
        part.extend(e.cell_unreach_day(k as i32, day));
        if part.len() == modes.len() {
            for (a, b) in modes.iter_mut().zip(part) {
                *a += b;
            }
        }
    }
    modes
}
/// Every purpose's legs per cell (`cellModes("all")` in the web's demand client).
fn all_modes(
    id: &str,
    frame: Frame,
    s: &DemandApi,
    e: &EverydayApi,
    market: Option<&IntercityApi>,
    xy: &[f32],
) -> Result<Vec<f32>, String> {
    let day = AVERAGE as u32;
    let n = xy.len() / 2;
    let mut modes = commutes(s, day, n)?;
    let mut other = e.cell_modes_day(day);
    other.extend(e.cell_unreach_day(-1, day));
    for (i, v) in modes.iter_mut().enumerate() {
        *v += other.get(i).copied().unwrap_or(0.);
    }
    if let Some((m, &(_, place, shared))) = market.zip(OVERLAY_PLACES.iter().find(|r| r.0 == id)) {
        let sq = squares(frame, s, xy, shared);
        sq.add(m, place, u32::MAX, &mut modes);
        let mut terminal = e.cell_modes_of_day(AIRPORT, day);
        terminal.extend(e.cell_unreach_day(AIRPORT as i32, day));
        if terminal.len() == 8 * n {
            let local = terminal.clone();
            sq.add(m, place, 1 << (2 + AIRPORT), &mut terminal);
            airport(m, place, &sq.mxy, &local, &mut terminal);
            airport(m, place, &sq.mxy, &terminal, &mut modes);
        }
    }
    Ok(modes)
}
/// One filter purpose's legs per cell (`purposeModes` in the web's demand client): work from the
/// commutes and their regional part, an everyday purpose from its kinds, their regional part and,
/// for airports, the trips from across the zone line at the terminals.
fn purpose_modes(
    kinds: &[u32],
    s: &DemandApi,
    e: &EverydayApi,
    regional: Option<(&IntercityApi, u32, &Squares)>,
    n: usize,
) -> Result<Vec<f32>, String> {
    let day = AVERAGE as u32;
    let mut modes = if kinds.is_empty() {
        commutes(s, day, n)?
    } else {
        everyday(e, kinds, day, n)
    };
    if let Some((m, place, sq)) = regional {
        let mask = if kinds.is_empty() {
            REG_COMMUTES
        } else {
            kinds.iter().fold(0, |b, k| b | 1 << (2 + k))
        };
        let local = kinds.contains(&AIRPORT).then(|| modes.clone());
        sq.add(m, place, mask, &mut modes);
        if let Some(local) = local {
            airport(m, place, &sq.mxy, &local, &mut modes);
        }
    }
    Ok(modes)
}
/// One cell's eight counts from eight mode blocks: home end rail, walk, road, no route, then the
/// activity end's.
fn counts(modes: &[f32], n: usize, i: usize) -> [f64; 8] {
    [0, 1, 2, 6, 3, 4, 5, 7].map(|k| f64::from(modes[k * n + i]))
}
/// Rows of longitude, latitude and the eight counts of every purpose together, one per cell with trips.
pub(super) fn rows(
    id: &str,
    frame: Frame,
    s: &DemandApi,
    e: &EverydayApi,
    market: Option<&IntercityApi>,
) -> Result<Vec<f64>, String> {
    let xy = s.cell_xy();
    let n = xy.len() / 2;
    let modes = all_modes(id, frame, s, e, market, &xy)?;
    let mut rows = Vec::new();
    for (i, p) in xy.chunks_exact(2).enumerate() {
        let c = counts(&modes, n, i);
        if c.iter().any(|v| *v > 0.) {
            let (lon, lat) = frame.to_lonlat(p[0].into(), p[1].into());
            rows.extend([lon, lat]);
            rows.extend(c);
        }
    }
    Ok(rows)
}
/// Numbers per row of `purpose_rows`: position, every purpose's eight counts, then each `PURPOSES`'.
pub(super) const PURPOSE_STRIDE: usize = 2 + 8 * (1 + PURPOSES.len());
/// `rows` with each filter purpose's eight counts after every purpose's, so the map's purpose
/// filter switches without another solve. A cell is kept when any purpose has trips there.
pub(super) fn purpose_rows(
    id: &str,
    frame: Frame,
    s: &DemandApi,
    e: &EverydayApi,
    market: Option<&IntercityApi>,
) -> Result<Vec<f64>, String> {
    let xy = s.cell_xy();
    let n = xy.len() / 2;
    let all = all_modes(id, frame, s, e, market, &xy)?;
    let place = market.zip(OVERLAY_PLACES.iter().find(|r| r.0 == id));
    let sq = place.map(|(_, &(_, _, shared))| squares(frame, s, &xy, shared));
    let regional = place
        .zip(sq.as_ref())
        .map(|((m, &(_, p, _)), sq)| (m, p, sq));
    let parts = PURPOSES
        .iter()
        .map(|(_, kinds)| purpose_modes(kinds, s, e, regional, n))
        .collect::<Result<Vec<_>, _>>()?;
    let mut rows = Vec::new();
    for (i, p) in xy.chunks_exact(2).enumerate() {
        let mut row = counts(&all, n, i).to_vec();
        for part in &parts {
            row.extend(counts(part, n, i));
        }
        if row.iter().any(|v| *v > 0.) {
            let (lon, lat) = frame.to_lonlat(p[0].into(), p[1].into());
            rows.extend([lon, lat]);
            rows.extend(row);
        }
    }
    Ok(rows)
}
