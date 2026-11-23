//! Saved fare curves, local income scale and perceived cost. Mirrors game/income.ts,
//! fareCurve.ts, fareDefault.ts and the clock worker's migrations for old TWG1 headers.
use serde_json::{json, Value};
use std::sync::OnceLock;

fn income() -> &'static Value {
    static TABLE: OnceLock<Value> = OnceLock::new();
    TABLE.get_or_init(|| {
        serde_json::from_str(include_str!("../../app/src/game/countryIncome.json")).unwrap()
    })
}
fn gdp(c: &str, k: &str) -> f64 {
    income()[k][c]
        .as_f64()
        .filter(|v| *v > 0.)
        .unwrap_or_else(|| income()[k]["IN"].as_f64().unwrap())
}
fn weight(c: &str) -> f64 {
    4.4 / (gdp(c, "usd") / 90000.).sqrt()
}
fn scale(c: &str) -> f64 {
    ((11.6 * (gdp(c, "usd") / gdp("US", "usd")).sqrt() * 10.).round() / 10.).clamp(0.1, 19.9)
}
fn old_scale(c: &str) -> f64 {
    (((gdp(c, "gdp") / gdp("IN", "gdp")).sqrt() * 10.).round() / 10.).clamp(0.1, 9.9)
}
fn legacy_factor() -> f64 {
    5. / (gdp("US", "gdp") / 60000.).sqrt() * old_scale("US") / (weight("US") * scale("US"))
}
fn round4(v: f64) -> f64 {
    (v * 10000.).round() / 10000.
}
fn fare_round(v: f64) -> f64 {
    let s = if v < 0.25 {
        0.01
    } else if v < 10. {
        0.05
    } else if v < 100. {
        0.5
    } else if v < 1000. {
        1.
    } else {
        5.
    };
    round4((v / s).round() * s)
}
const START: [[f64; 2]; 3] = [[0., 0.0862], [20., 0.2241], [100., 0.7757]];
fn start_nodes(s: f64) -> [[f64; 2]; 3] {
    let k = 2.5 / (START[0][1] * scale("US")) * s;
    START.map(|[x, y]| [x, fare_round(y * k)])
}
pub(super) fn starting_fares(country: &str) -> Value {
    let income_scale = scale(country);
    let nodes = start_nodes(income_scale);
    json!({"base":nodes[0][1],"perKm":(nodes[1][1]-nodes[0][1])/nodes[1][0],
        "nodes":nodes,"scales":{"@":income_scale},"baseline":"IN","basis":"usd","income":0.5})
}
fn same(a: &[[f64; 2]; 3], b: &[[f64; 2]; 3]) -> bool {
    a.iter()
        .zip(b)
        .all(|(x, y)| (x[0] - y[0]).abs() < 1e-6 && (x[1] - y[1]).abs() < 1e-6)
}
fn fare_curve(n: &[[f64; 2]; 3], base: f64, rate: f64, mul: f64) -> Vec<f32> {
    if n.iter().all(|[k, v]| (v - (base + rate * k)).abs() < 1e-6) {
        return vec![];
    }
    n.iter()
        .flat_map(|[k, v]| [*k as f32, (v * mul) as f32])
        .collect()
}
pub(super) fn currency_factor(f: &Value) -> f64 {
    if f.is_object() && f["basis"] != "usd" {
        legacy_factor()
            / if f["baseline"].is_string() {
                1.
            } else {
                old_scale("US")
            }
    } else {
        1.
    }
}
pub(super) struct Fares {
    pub local: Value,
    pub market: Value,
    pub raw: Value,
}
impl Fares {
    pub fn read(f: &Value, country: &str) -> Self {
        let old = f.is_object() && f["basis"] != "usd";
        let factor = currency_factor(f);
        let mut scales = f["scales"].as_object().cloned().unwrap_or_default();
        for (id, v) in scales.iter_mut() {
            if let Some(x) = v.as_f64() {
                if old {
                    *v = json!(
                        ((x / old_scale(id) * scale(id) * 10.).round() / 10.).clamp(0.1, 19.9)
                    );
                }
            }
        }
        if let Some(v) = scales.get_mut("@") {
            if let Some(x) = v.as_f64().filter(|n| *n > 0.) {
                if f["income"].as_f64().unwrap_or(0.7) != 0.5 {
                    *v = json!(((11.6
                        * (x.powf(1. / 0.7) * gdp("IN", "usd") / gdp("US", "usd")).sqrt()
                        * 10.)
                        .round()
                        / 10.)
                        .clamp(0.1, 19.9));
                }
            }
        }
        let start = scales.get("@").and_then(Value::as_f64).filter(|x| *x > 0.);
        let every = scales.get("*").and_then(Value::as_f64);
        let default = |c: &str| {
            start.map(|n| scale(c) / n).unwrap_or_else(|| {
                every
                    .map(|n| ((n * 100.).round() / 100.).clamp(0.01, 19.9))
                    .unwrap_or_else(|| scale(c))
            })
        };
        let relative = start.is_some() || every.is_some();
        let local_scale = |c: &str| {
            scales
                .get(c)
                .and_then(Value::as_f64)
                .map(|v| {
                    if f["ref"].is_string() {
                        v.clamp(0.001, 1000.) * if relative { default(c) } else { 1. }
                    } else if relative {
                        (v * 100.).round().clamp(1., 1990.) / 100. * default(c)
                    } else {
                        (v / default(c) * 100.).round().clamp(1., 1990.) / 100. * default(c)
                    }
                })
                .unwrap_or_else(|| default(c))
        };
        let base = f["base"].as_f64().unwrap_or(0.0862) * factor;
        let per = f["perKm"].as_f64().unwrap_or(0.006895) * factor;
        let mut nodes = if f.is_null() {
            START
        } else {
            [0., 20., 100.].map(|k| [k, round4((base + per * k).max(0.)).min(1000.)])
        };
        if let Some(ns) = f["nodes"].as_array() {
            let mut ns: Vec<_> = ns
                .iter()
                .filter_map(|v| Some([v[0].as_f64()?, v[1].as_f64()? * factor]))
                .collect();
            ns.sort_by(|a, b| a[0].total_cmp(&b[0]));
            if ns.len() == 3 && ns[0][0] == 0. {
                nodes = [ns[0], ns[1], ns[2]]
            } else if ns.len() >= 2 {
                let [k0, f0] = ns[0];
                let [k1, f1] = *ns.last().unwrap();
                let k = if k1 > k0 {
                    ((f1 - f0) / (k1 - k0)).max(0.)
                } else {
                    0.
                };
                nodes = [0., 20., 100.].map(|d| [d, (f0 - k * k0).max(0.) + d * k]);
            }
        }
        nodes[1][0] = ((nodes[1][0] * 100.).round() / 100.).clamp(0.1, 4999.9);
        nodes[2][0] = ((nodes[2][0] * 100.).round() / 100.).clamp(nodes[1][0] + 0.1, 5000.);
        for i in 0..3 {
            nodes[i][1] =
                round4(nodes[i][1]).clamp(if i == 0 { 0. } else { nodes[i - 1][1] }, 1000.);
        }
        let first = |n: &[[f64; 2]; 3]| (n[0][1], (n[1][1] - n[0][1]) / n[1][0]);
        let (base, rate) =
            if !old && f["nodes"].is_array() && fare_curve(&nodes, base, per, 1.).is_empty() {
                (base, per)
            } else {
                first(&nodes)
            };
        let s = local_scale(country);
        let rounded = if scales.contains_key(country) || every.is_some() {
            None
        } else if let Some(s) = start {
            same(&nodes, &start_nodes(s)).then(|| start_nodes(scale(country)))
        } else {
            same(&nodes, &START).then(|| START.map(|[x, v]| [x, fare_round(v * scale(country))]))
        };
        let (lb, lr, lc) = if let Some(n) = rounded {
            let (b, r) = first(&n);
            (b, r, fare_curve(&n, b, r, 1.))
        } else {
            (base * s, rate * s, fare_curve(&nodes, base, rate, s))
        };
        let unit = start.unwrap_or(1.);
        let factors: serde_json::Map<String, Value> = income()["usd"]
            .as_object()
            .unwrap()
            .keys()
            .chain(scales.keys())
            .filter(|k| k.as_str() != "@" && k.as_str() != "*")
            .map(|c| (c.clone(), json!(local_scale(c) * unit / scale(c) / 0.4)))
            .collect();
        let local = json!({"base":lb,"rate":lr,"curve":lc,"weight":weight(country),"transfer":round4(f["transfer"].as_f64().unwrap_or(0.)).clamp(0.,1000.)*s});
        let market = json!({"base":base,"rate":rate,"curve":fare_curve(&nodes,base,rate,1.),"weight":weight("IN")*scale("IN")/unit*0.4,"factors":factors});
        Self {
            local,
            market,
            raw: f.clone(),
        }
    }
    pub fn multiplier(&self, id: u32, fast: bool, ferry: bool, bus: bool) -> (f32, f32) {
        let own = self.raw["lines"][id.to_string()]
            .as_f64()
            .unwrap_or(1.)
            .clamp(0., 3.);
        let own = ((own * 100.).round() / 100.) as f32;
        let kind = if ferry {
            self.raw["ferry"].as_f64().unwrap_or(1.)
        } else if bus {
            self.raw["bus"].as_f64().unwrap_or(1.)
        } else if fast {
            self.raw["hsr"].as_f64().unwrap_or(1.25)
        } else {
            1.
        };
        (
            own,
            own * ((kind.clamp(0., 5.) * 100.).round() / 100.) as f32,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn migration_and_country_terms() {
        let mut records = vec![];
        for (country, raw) in [
            ("US", json!({"base":1.5,"perKm":0.1})),
            ("ID", Value::Null),
            (
                "JP",
                json!({"baseline":"IN","basis":"usd","nodes":[[0,2],[20,4],[100,6]],"base":2,"perKm":0.1,"income":0.5,"scales":{"@":11.6,"JP":1.3},"transfer":0.25}),
            ),
            (
                "DE",
                json!({"base":1.25,"perKm":0.05,"baseline":"IN","scales":{"DE":2.3}}),
            ),
            (
                "ID",
                json!({"basis":"usd","baseline":"IN","income":0.5,"nodes":start_nodes(scale("ID")),"scales":{"@":scale("ID")}}),
            ),
        ] {
            let f = Fares::read(&raw, country);
            assert!(f.local["weight"].as_f64().unwrap() > 0.);
            records.push(
                json!({"country":country,"rawFare":raw,"localFare":f.local,"marketFare":f.market}),
            );
        }
        if let Ok(p) = std::env::var("WORLD_FARE_EXPORT") {
            std::fs::write(p, serde_json::to_string(&records).unwrap()).unwrap();
        }
    }
}
