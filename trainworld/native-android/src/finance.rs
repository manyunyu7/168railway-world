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
fn clear(h: &mut Value) {
    if let Some(o) = h.as_object_mut() {
        for key in ["loan", "loanPay", "loanDays"] {
            o.remove(key);
        }
    }
}
// Keep rows sorted even when an imported save already has entries after its clock.
fn entry<'a>(header: &'a mut Value, key: &str, index: &str, at: i64) -> &'a mut Value {
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

fn add(row: &mut Value, field: &str, v: f64) {
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
pub(super) fn tick(w: &mut World, now: f64) {
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
        cash -= owed + upkeep + running;
        for (key, index, at) in [("ledger", "day", day), ("hours", "hour", hour)] {
            let row = entry(&mut w.header, key, index, at);
            add(row, "upkeep", upkeep);
            add(row, "running", running);
            if owed > 0. {
                add(row, "loan", owed);
            }
            if upkeep > 0. || running > 0. {
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
            if ![1000, 5000, 10000].contains(&op) || loan(h).is_some() {
                return Err("Pilihan pinjaman tidak tersedia".into());
            }
            let today = ((clock + super::local_time::day_shift(h)) / 86400.).floor() as i64;
            let days: Vec<_> = h["ledger"]
                .as_array()
                .into_iter()
                .flatten()
                .rev()
                .filter(|d| d["day"].as_i64().is_some_and(|n| n < today))
                .take(7)
                .collect();
            let recorded = days.iter().any(|d| d["fares"].as_f64().unwrap_or(0.) > 0.);
            let profit = days
                .iter()
                .map(|d| {
                    d["fares"].as_f64().unwrap_or(0.)
                        - d["running"].as_f64().unwrap_or(0.)
                        - d["upkeep"].as_f64().unwrap_or(0.)
                })
                .sum::<f64>()
                / (days.len().max(1) as f64);
            if (recorded && payment(amount) > profit) || (!recorded && op != 1000) {
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
    let (_, pay, days) = loan(h).unwrap_or((0., 0., 0.));
    Ok(
        json!({"cash":cash,"ledger":h.get("ledger"),"upkeep":w.api.upkeep()[0],"operating":[w.api.running_cost(0),w.api.running_cost(1),w.api.running_cost(2)],"dailyPayment":pay,"remainingDays":days,"outstanding":pay*(1.-(1.+RATE).powf(-days))/RATE,"sandbox":h["mode"]=="sandbox"}),
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
    fn borrow_overnight_and_repay() {
        let mut w = World::open(include_bytes!("../../T-007-real-nyc.save")).unwrap();
        let now = 86390.;
        w.header["clock"] = now.into();
        w.header["cash"] = 6000.into();
        w.header["ledger"] = json!([]);
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
