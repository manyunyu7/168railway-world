//! Financial fields use the upstream TWG1 units (millions of USD, local simulation days).
use super::World;
use jni::{
    objects::JClass,
    sys::{jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

const TERM: f64 = 60.;
const RATE: f64 = 0.01;
fn payment(amount: f64) -> f64 {
    amount * RATE / (1. - (1. + RATE).powf(-TERM))
}
fn loan(h: &Value) -> Option<(f64, f64, f64)> {
    let a = h["loan"].as_f64()?;
    if !(a > 0.) {
        return None;
    }
    let (pay, days) = match (h["loanPay"].as_f64(), h["loanDays"].as_f64()) {
        (Some(p), Some(d)) => (p, d),
        _ => (payment(a), TERM),
    };
    (days > 0.).then_some((a, pay, days))
}
/// Web `LOAN_AMOUNTS` (game/loan.ts) and the game's starting cash (`START_CASH`), US$M.
const AMOUNTS: [i32; 3] = [1000, 5000, 10000];
const START_CASH: f64 = 10000.;
/// Web `loanProfit` (game/loan.ts): the mean of fares less running and upkeep over the last 7
/// whole days before `today`, with how many; None before any fares.
fn loan_profit(h: &Value, today: i64) -> Option<(f64, usize)> {
    let days: Vec<_> = h["ledger"]
        .as_array()
        .into_iter()
        .flatten()
        .rev()
        .filter(|d| d["day"].as_i64().is_some_and(|n| n < today))
        .take(7)
        .collect();
    if !days.iter().any(|d| d["fares"].as_f64().unwrap_or(0.) > 0.) {
        return None;
    }
    let sum = days
        .iter()
        .map(|d| {
            d["fares"].as_f64().unwrap_or(0.)
                - d["running"].as_f64().unwrap_or(0.)
                - d["upkeep"].as_f64().unwrap_or(0.)
        })
        .sum::<f64>();
    Some((sum / days.len() as f64, days.len()))
}
/// Web `loanAllowed`: a payment within the profit, or before any profit only the smallest.
fn loan_allowed(amount: i32, profit: Option<(f64, usize)>) -> bool {
    profit.map_or(amount == AMOUNTS[0], |(per_day, _)| {
        payment(amount as f64) <= per_day
    })
}
/// The local game day `clock` falls in.
fn today(h: &Value, clock: f64) -> i64 {
    ((clock + super::local_time::day_shift(h)) / 86400.).floor() as i64
}
fn clear(h: &mut Value) {
    if let Some(o) = h.as_object_mut() {
        for key in ["loan", "loanPay", "loanDays"] {
            o.remove(key);
        }
    }
}
// Keep rows sorted even when an imported save already has entries after its clock.
pub(super) fn entry<'a>(header: &'a mut Value, key: &str, index: &str, at: i64) -> &'a mut Value {
    if !header[key].is_array() {
        header[key] = json!([]);
    }
    let rows = header[key].as_array_mut().unwrap();
    let mut i = rows.partition_point(|row| row[index].as_i64().unwrap_or(i64::MIN) < at);
    if rows.get(i).and_then(|row| row[index].as_i64()) != Some(at) {
        let mut row = json!({"fares":0,"running":0,"trains":0,"build":0,"rides":0});
        row[index] = at.into();
        rows.insert(i, row);
    }
    if key == "hours" && rows.len() > 48 {
        // Leave the current row accessible when a save carries future ledger entries.
        let trim = (rows.len() - 48).min(i);
        rows.drain(..trim);
        i -= trim;
    }
    &mut rows[i]
}

pub(super) fn add(row: &mut Value, field: &str, v: f64) {
    row[field] = (row[field].as_f64().unwrap_or(0.) + v).into();
}

fn book(header: &mut Value, key: &str, index: &str, at: i64, charge: f64, trains: f64) {
    let row = entry(header, key, index, at);
    add(row, "build", charge - trains);
    add(row, "trains", trains);
}

pub(super) fn book_charge(header: &mut Value, clock: f64, charge: f64, trains: f64) {
    if charge > 0. {
        let day = ((clock + super::local_time::day_shift(header)) / 86400.).floor() as i64;
        let hour = (clock / 3600.).floor() as i64;
        for (key, index, at) in [("ledger", "day", day), ("hours", "hour", hour)] {
            book(header, key, index, at, charge, trains);
            book(&mut header["regions"][""], key, index, at, charge, trains);
        }
    }
}
fn value(row: &Value, key: &str) -> f64 {
    row[key].as_f64().unwrap_or(0.)
}

fn trim_future(header: &mut Value, day: i64, hour: i64, shift: f64) -> f64 {
    let mut fares = 0.;
    let mut subtract = [0.; 5];
    let fields = ["fares", "running", "upkeep", "rides", "points"];
    for (key, index, at) in [("hours", "hour", hour), ("ledger", "day", day)] {
        let Some(rows) = header[key].as_array_mut() else {
            continue;
        };
        let mut trains = 0.;
        let mut build = 0.;
        rows.retain(|row| {
            let n = row[index].as_i64().unwrap_or(at);
            if n <= at {
                return true;
            }
            trains += value(row, "trains");
            build += value(row, "build");
            if key == "ledger" {
                fares += value(row, "fares");
            } else if ((n as f64 * 3600. + shift) / 86400.).floor() == day as f64 {
                for (amount, field) in subtract.iter_mut().zip(fields) {
                    *amount += value(row, field);
                }
                fares += value(row, "fares");
            }
            false
        });
        if key == "ledger" {
            if let Some(today) = rows.iter_mut().find(|r| r[index].as_i64() == Some(at)) {
                for (field, amount) in fields.into_iter().zip(subtract) {
                    add(today, field, -amount);
                }
            }
        }
        if trains != 0. || build != 0. {
            let row = entry(header, key, index, at);
            add(row, "trains", trains);
            add(row, "build", build);
        }
    }
    fares
}

pub(super) fn prepare_session(w: &mut World) {
    let net = &w.api.world().net;
    if w.header["start"].is_null() && w.header["dayShift"].as_f64().is_none() {
        if let Some(i) = (0..net.node_count())
            .find(|&i| net.node_ok(i as u32) && net.node_platform[i] > 0 && net.node_built[i])
        {
            let ll = w.api.to_lonlat(net.node_x[i], net.node_y[i]);
            w.header["dayShift"] = net.time_zones.shift_at(ll[0], ll[1]).into();
        }
    }
    let clock = value(&w.header, "clock");
    let shift = super::local_time::day_shift(&w.header);
    let hour = (clock / 3600.).floor() as i64;
    let day = ((clock + shift) / 86400.).floor() as i64;
    if let Some(lines) = w.header["lineDays"].as_object_mut() {
        lines.retain(|_, v| {
            let Some(rows) = v.as_array_mut() else {
                return false;
            };
            let kept = rows
                .chunks_exact(3)
                .filter(|r| r[0].as_i64().is_some_and(|d| d <= day))
                .rev()
                .take(31)
                .collect::<Vec<_>>();
            *rows = kept.into_iter().rev().flatten().cloned().collect();
            !rows.is_empty()
        });
    }
    let Some(last) = w.header["hours"].as_array().and_then(|r| r.last()) else {
        return;
    };
    let last_hour = last["hour"].as_i64().unwrap_or(hour);
    if last_hour <= hour {
        return;
    }
    let last_start = last_hour as f64 * 3600.;
    let last_day = ((last_start + shift) / 86400.).floor() as i64;
    let up_hour = w.api.upkeep()[0] / 24.;
    let costs = w
        .operating
        .get_or_insert_with(|| super::operating::Costs::new(&mut w.api));
    let replay = |mut t: f64, end: f64| {
        let mut sum = 0.;
        while t < end {
            let h = (t / 3600.).floor() * 3600.;
            let finish = end.min(h + 3600.);
            if finish <= t {
                break;
            }
            sum += costs.per_hour(h) * (finish - t) / 3600.;
            t = finish;
        }
        sum
    };
    let mut kept = 0.;
    let mut again = 0.;
    for row in w.header["ledger"].as_array().into_iter().flatten() {
        let d = row["day"].as_i64().unwrap_or(day);
        if d > day && d < last_day {
            let start = d as f64 * 86400. - shift;
            kept += value(row, "running");
            again += replay(start, start + 86400.);
        }
    }
    let times = if kept > 0. && again > 0. {
        kept / again
    } else {
        1.
    };
    let full = times * costs.per_hour(last_start);
    let end = last_start
        + 3600.
            * if full > 0. {
                (value(last, "running") / full).min(1.)
            } else {
                1.
            };
    let back = times * replay(clock.max(end - 366. * 86400.), end);
    let up_end = last_start
        + 3600.
            * if up_hour > 0. {
                (value(last, "upkeep") / up_hour).min(1.)
            } else {
                0.
            };
    let up_back = up_hour * ((up_end - clock) / 3600.).clamp(0., 366. * 24.);
    let fares = trim_future(&mut w.header, day, hour, shift);
    if let Some(zones) = w.header["regions"].as_object_mut() {
        for z in zones.values_mut() {
            trim_future(z, day, hour, shift);
        }
    }
    let cash = w.header["cash"].as_f64().unwrap_or(w.api.cash()) + back + up_back - fares;
    w.header["cash"] = cash.into();
    w.api.set_cash(cash);
}
pub(super) fn tick(w: &mut World, now: f64) {
    tick_with_account(w, now, None)
}
pub(super) fn tick_with_account(
    w: &mut World,
    now: f64,
    mut account: Option<&mut crate::timed_account::Account>,
) {
    let start = w.header["clock"].as_f64().unwrap_or(now);
    if !now.is_finite() || now <= start {
        return;
    }
    let shift = super::local_time::day_shift(&w.header);
    let daily_upkeep = w.api.upkeep()[0];
    let costs = w
        .operating
        .get_or_insert_with(|| super::operating::Costs::new(&mut w.api));
    let mut t = if now - start > 366. * 86400. {
        ((now - 366. * 86400.) / 3600.).floor() * 3600.
    } else {
        start
    };
    let mut cash = w.header["cash"].as_f64().unwrap_or(w.api.cash());
    while t < now {
        let hour = (t / 3600.).floor() as i64;
        let day = ((t + shift) / 86400.).floor() as i64;
        let end = now
            .min((hour + 1) as f64 * 3600.)
            .min((day + 1) as f64 * 86400. - shift);
        if end <= t {
            break;
        }
        let elapsed_days = (end - t) / 86400.;
        let upkeep = elapsed_days * daily_upkeep;
        let running = costs.per_hour(hour as f64 * 3600.) * (end - t) / 3600.;
        let mut owed = 0.;
        if let Some((_, pay, days)) = loan(&w.header) {
            let d = elapsed_days.min(days);
            owed = pay * d;
            if days - d < 1e-9 {
                clear(&mut w.header);
            } else {
                w.header["loanDays"] = (days - d).into();
                w.header["loanPay"] = pay.into();
            }
        }
        let [fares, rides, running, points] =
            account.as_deref_mut().map_or([0., 0., running, 0.], |a| {
                a.settle(
                    &mut w.api,
                    &mut w.header,
                    hour,
                    day,
                    end,
                    (end - t) / 3600.,
                    upkeep,
                    costs,
                )
            });
        cash += fares - (owed + upkeep + running);
        for (key, index, at) in [("ledger", "day", day), ("hours", "hour", hour)] {
            let row = entry(&mut w.header, key, index, at);
            add(row, "fares", fares);
            add(row, "rides", rides);
            if points > 0. {
                add(row, "points", points);
            }
            add(row, "upkeep", upkeep);
            add(row, "running", running);
            if owed > 0. {
                add(row, "loan", owed);
            }
            if account.is_none() && (upkeep > 0. || running > 0.) {
                // Same unallocated bucket as Studio until geographic shares are available.
                let regional = entry(&mut w.header["regions"][""], key, index, at);
                add(regional, "upkeep", upkeep);
                add(regional, "running", running);
            }
        }
        t = end;
    }
    w.header["cash"] = cash.into();
    w.api.set_cash(cash);
    w.header["clock"] = now.into();
}
/// The money tab's Kereta section (web MoneyTab.tsx): vehicles owned and needed by kind, with the
/// prices its hovers quote (US$M, `money_params`).
fn fleet(api: &trainworld_sim::track::wasm::TrackApi) -> Value {
    let p = api.money_params();
    json!({"cars": api.fleet(), "carsNeeded": api.cars_needed(), "fast": api.fleet_fast(),
        "fastNeeded": api.cars_needed_fast(), "tram": api.fleet_tram(), "tramsNeeded": api.trams_needed(),
        "bus": api.fleet_bus(), "busesNeeded": api.buses_needed(), "ferry": api.fleet_ferry(),
        "boatsNeeded": api.boats_needed(), "carPrice": p[0], "fastPrice": p[4], "busPrice": p[11], "boatPrice": p[18]})
}
pub(super) fn apply(w: &mut World, clock: f64, op: i32) -> Result<Value, String> {
    if !clock.is_finite() {
        return Err("Jam tidak valid".into());
    }
    tick(w, clock);
    let h = &mut w.header;
    let mut cash = h["cash"].as_f64().unwrap_or(w.api.cash());
    let balance = loan(h).map_or(0., |(_, p, d)| p * (1. - (1. + RATE).powf(-d)) / RATE);
    if op != 0 {
        if h["mode"] == "sandbox" {
            return Err("Pinjaman tersedia pada permainan dengan anggaran".into());
        }
        if op == -1 {
            if loan(h).is_none() || cash < balance {
                return Err("Saldo tidak cukup atau tidak ada pinjaman".into());
            }
            cash -= balance;
            clear(h);
        } else {
            let amount = op as f64;
            if !AMOUNTS.contains(&op) || loan(h).is_some() {
                return Err("Pilihan pinjaman tidak tersedia".into());
            }
            if !loan_allowed(op, loan_profit(h, today(h, clock))) {
                return Err("Laba harian belum mencukupi untuk besaran ini".into());
            }
            cash += amount;
            h["loan"] = amount.into();
            h["loanDays"] = TERM.into();
            h["loanPay"] = payment(amount).into();
        }
        h["clock"] = clock.into();
        h["cash"] = cash.into();
        w.api.set_cash(cash);
    }
    let (amount, pay, days) = loan(h).unwrap_or((0., 0., 0.));
    let day = today(h, clock);
    // the web's loan rows: each size with its payment, refused past what the profit pays (LoanRows)
    let profit = loan_profit(h, day);
    let offers: Vec<_> = AMOUNTS
        .iter()
        .map(|&a| {
            json!({"amount": a, "payment": payment(a as f64), "allowed": loan_allowed(a, profit),
            "profit": profit.map(|p| p.0), "days": profit.map(|p| p.1)})
        })
        .collect();
    Ok(
        json!({"cash":cash,"ledger":h.get("ledger"),"upkeep":w.api.upkeep()[0],"operating":[w.api.running_cost(0),w.api.running_cost(1),w.api.running_cost(2)],"dailyPayment":pay,"remainingDays":days,"outstanding":pay*(1.-(1.+RATE).powf(-days))/RATE,"sandbox":h["mode"]=="sandbox",
            "today":day,"loan":amount,"offers":offers,"startCash":START_CASH,"fleet":fleet(&w.api),"built":w.api.built_cost(),"hours":h.get("hours"),"dayShift":super::local_time::day_shift(h),"term":TERM,"rate":RATE}),
    )
}
#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeFinance_query(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
    now: jdouble,
    op: jint,
) -> jstring {
    if ptr == 0 {
        return std::ptr::null_mut();
    }
    let w = unsafe { &mut *(ptr as *mut World) };
    let r = apply(w, w.native_clock(now), op).unwrap_or_else(|e| json!({"error":e}));
    env.new_string(r.to_string())
        .map_or(std::ptr::null_mut(), |x| x.into_raw())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn replay_reverses_future_fares_and_preserves_construction_once() {
        let mut world = World::workshop();
        let today = json!({"day":0,"fares":10,"running":0,"upkeep":0,"build":0,"trains":0});
        let tomorrow = json!({"day":1,"fares":7,"build":2,"trains":3});
        let later = json!({"hour":2,"fares":4,"build":0,"trains":0});
        let last = json!({"hour":24,"fares":7,"build":2,"trains":3});
        let history = json!({"ledger":[today,tomorrow],"hours":[later,last]});
        world.header = json!({"clock":3600,"cash":100,"lineDays":{"3":[0,10,0,1,7,0]},
            "regions":{"jak":history},"ledger":history["ledger"],"hours":history["hours"]});
        prepare_session(&mut world);
        assert_eq!(world.api.cash(), 89.);
        assert_eq!(world.header["ledger"][0]["fares"].as_f64(), Some(6.));
        assert_eq!(world.header["ledger"][0]["trains"].as_f64(), Some(3.));
        assert_eq!(world.header["hours"][0]["build"].as_f64(), Some(2.));
        assert_eq!(
            world.header["ledger"],
            world.header["regions"]["jak"]["ledger"]
        );
        assert_eq!(world.header["lineDays"]["3"], json!([0, 10, 0]));
        let before = world.header.clone();
        prepare_session(&mut world);
        assert_eq!(world.header, before);
    }
    #[test]
    fn borrow_overnight_and_repay() {
        let mut w = World::open(include_bytes!("../../T-007-real-nyc.save")).unwrap();
        let now = 86390.;
        w.header["clock"] = now.into();
        w.header["cash"] = 6000.into();
        w.header["ledger"] = json!([]);
        // before any fares only the smallest loan is offered, as the web's loanFirst
        let offers = apply(&mut w, now, 0).unwrap()["offers"].clone();
        let allowed: Vec<_> = offers
            .as_array()
            .unwrap()
            .iter()
            .map(|o| o["allowed"].as_bool().unwrap())
            .collect();
        assert_eq!(allowed, [true, false, false]);
        assert!(offers[0]["profit"].is_null());
        // the Kereta section: the NYC network owns and needs cars, and has built something
        let state = apply(&mut w, now, 0).unwrap();
        assert!(state["fleet"]["cars"].as_u64().unwrap() > 0);
        assert!(state["fleet"]["carsNeeded"].as_u64().unwrap() > 0);
        assert!(state["built"].as_f64().unwrap() > 0.);
        assert!(apply(&mut w, now, 5000).is_err());
        assert!(apply(&mut w, now, 1000).is_ok());
        assert!(apply(&mut w, now, 1000).is_err());
        let later = now + 86400.;
        assert_eq!(w.edit_candidate(later).unwrap().header["clock"], now);
        let mut candidate = World::open(&w.save(now).unwrap()).unwrap();
        let from_edit = apply(&mut candidate, later, 0).unwrap();
        let r = apply(&mut w, later, 0).unwrap();
        assert_eq!(from_edit, r);
        assert_eq!(candidate.header["ledger"], w.header["ledger"]);
        let running: f64 = w.header["ledger"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| row["running"].as_f64().unwrap())
            .sum();
        let paid = payment(1000.) + w.api.upkeep()[0] + running;
        assert!((r["cash"].as_f64().unwrap() - (7000. - paid)).abs() < 1e-8);
        assert_eq!(w.header["ledger"].as_array().unwrap().len(), 2);
        let saved = w.save(later).unwrap();
        let mut reopened = World::open(&saved).unwrap();
        let before = apply(&mut reopened, later, 0).unwrap();
        assert_eq!(before["outstanding"], r["outstanding"]);
        assert!(apply(&mut reopened, later, -1).is_ok());
        assert!(loan(&reopened.header).is_none());
    }
}
