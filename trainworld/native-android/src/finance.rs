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
fn record(h: &mut Value, key: &str, field: &str, id: i64, amount: f64, limit: usize) {
    if !h[key].is_array() {
        h[key] = json!([])
    }
    let a = h[key].as_array_mut().unwrap();
    if a.last().and_then(|x| x[field].as_i64()) != Some(id) {
        let mut row = json!({"fares":0,"running":0,"trains":0,"build":0,"rides":0});
        row[field] = id.into();
        a.push(row);
        if a.len() > limit {
            a.drain(..a.len() - limit);
        }
    }
    let row = a.last_mut().unwrap();
    row["loan"] = json!(row["loan"].as_f64().unwrap_or(0.) + amount);
}
pub(super) fn tick(w: &mut World, now: f64) {
    let start = w.header["clock"].as_f64().unwrap_or(now);
    if !now.is_finite() || now <= start || loan(&w.header).is_none() {
        return;
    }
    let shift = w.header["dayShift"].as_f64().unwrap_or(0.);
    let mut t = start;
    while t < now {
        let Some((_, pay, days)) = loan(&w.header) else {
            break;
        };
        let hour = (t / 3600.).floor() as i64;
        let day = ((t + shift) / 86400.).floor() as i64;
        let end = now
            .min((hour + 1) as f64 * 3600.)
            .min((day + 1) as f64 * 86400. - shift);
        if end <= t {
            break;
        }
        let d = ((end - t) / 86400.).min(days);
        let owed = pay * d;
        let cash = w.header["cash"].as_f64().unwrap_or(w.api.cash()) - owed;
        w.header["cash"] = cash.into();
        w.api.set_cash(cash);
        record(&mut w.header, "ledger", "day", day, owed, usize::MAX);
        record(&mut w.header, "hours", "hour", hour, owed, 48);
        if days - d < 1e-9 {
            clear(&mut w.header)
        } else {
            w.header["loanDays"] = (days - d).into();
            w.header["loanPay"] = pay.into();
        }
        t = end;
    }
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
            let today = ((clock + h["dayShift"].as_f64().unwrap_or(0.)) / 86400.).floor() as i64;
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
        json!({"cash":cash,"upkeep":w.api.upkeep()[0],"operating":[w.api.running_cost(0),w.api.running_cost(1),w.api.running_cost(2)],"dailyPayment":pay,"remainingDays":days,"outstanding":pay*(1.-(1.+RATE).powf(-days))/RATE,"sandbox":h["mode"]=="sandbox"}),
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
    let r = apply(w, now, op).unwrap_or_else(|e| json!({"error":e}));
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
        let r = apply(&mut w, later, 0).unwrap();
        let paid = payment(1000.);
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
