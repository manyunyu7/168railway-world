// SPDX-License-Identifier: AGPL-3.0-only
//! Web `JunctionInspector.tsx` (SPEC 8, T-031): the routes through a junction, the trains an hour
//! on each, the wait where they cross, and its flyover. Plus web `nearestJunction`
//! (capacityMarkers.ts) and the map's junction pick (pick.ts) for opening it.
use super::World;
use jni::{
    objects::JClass,
    sys::{jboolean, jdouble, jint, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};

/// Web `COMPASS` (places.ts), in Indonesian.
const COMPASS: [&str; 8] = ["timur", "timur laut", "utara", "barat laut", "barat", "barat daya", "selatan", "tenggara"];

fn compass(dx: f64, dy: f64) -> &'static str {
    let a = dy.atan2(dx);
    COMPASS[(((a / (std::f64::consts::PI / 4.)).round() as i64 + 8) % 8) as usize]
}

/// Web game/periods.ts: high 06-20, medium 20-24, low 00-06, in local time.
fn level_at(local: f64) -> usize {
    match (local.rem_euclid(86400.) / 3600.) as u32 {
        6..=19 => 0,
        20..=23 => 1,
        _ => 2,
    }
}

impl World {
    /// The junction (a node where three or more tracks meet, not a station: the web's junction
    /// dots, network.ts) nearest world (x, y), within `radius` m; any distance when `radius` <= 0.
    fn junction_at(&self, x: f64, y: f64, radius: f64) -> Option<u32> {
        let nodes = self.api.nodes_info();
        nodes
            .chunks_exact(10)
            .filter(|n| n[4] >= 3. && n[7] == 0.)
            .map(|n| (n[0] as u32, (n[1] - x).hypot(n[2] - y)))
            .filter(|&(_, d)| radius <= 0. || d <= radius)
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(id, _)| id)
    }

    /// Web `stationTowards` (places.ts): the first station along the track leaving `node` by
    /// `edge`, through plain nodes; None when the track ends or splits first.
    fn station_towards(&self, edge: u32, end: u32) -> Option<String> {
        let net = &self.api.world().net;
        let mut e = edge;
        let mut from = if end == 0 { net.edge_a[e as usize] } else { net.edge_b[e as usize] };
        for _ in 0..200 {
            let (a, b) = (net.edge_a[e as usize], net.edge_b[e as usize]);
            let next = if a == from { b } else { a };
            if net.is_station(next) {
                return Some(self.api.node_name(next));
            }
            let others: Vec<u32> = net.node_ports[next as usize].ends().iter().map(|c| c >> 1).filter(|&x| x != e).collect();
            if others.len() != 1 {
                return None;
            }
            from = next;
            e = others[0];
        }
        None
    }

    /// Everything the junction inspector shows, at the demand level in force at `now`.
    pub(super) fn junction(&mut self, node: u32, now: f64) -> Option<Value> {
        let net = &self.api.world().net;
        if !net.node_ok(node) {
            return None;
        }
        let i = node as usize;
        let ports_n = net.node_ports[i].n as usize;
        if ports_n < 3 {
            return None;
        }
        let built = net.built_ports(node) >= 3;
        let flying = net.node_flying[i];
        let level = net.node_level[i];
        // a crossover's end keeps its shape, so no flyover there
        let at_crossover = net.node_ports[i].ends().iter().any(|&c| net.edge_crossover[(c >> 1) as usize]);
        let lev = level_at(now + super::local_time::day_shift(&self.header));
        let d = self.api.junction_info(node);
        let np = d.first().copied().unwrap_or(0.) as usize;
        let mut ports: Vec<(String, f64, f64)> = (0..np)
            .map(|k| {
                let r = &d[1 + 4 * k..5 + 4 * k];
                let (dx, dy) = (r[2], r[3]);
                (self.station_towards(r[0] as u32, r[1] as u32).unwrap_or_else(|| compass(dx, dy).to_owned()), dx, dy)
            })
            .collect();
        // the same name twice (two ends towards one station): add the direction
        let names: Vec<String> = ports.iter().map(|p| p.0.clone()).collect();
        for (k, p) in ports.iter_mut().enumerate() {
            if names.iter().enumerate().any(|(j, n)| j != k && *n == names[k]) {
                p.0 = format!("{} ({})", p.0, compass(p.1, p.2));
            }
        }
        let rows = self.api.lines_info();
        let line = |id: u32| {
            let color = rows.chunks_exact(22).find(|r| r[0] as u32 == id).map_or(0x888888 as f64, |r| r[1]);
            json!({"id":id,"name":self.api.line_name(id),"color":color})
        };
        let mut at = 2 + 4 * np;
        let nm = d.get(1 + 4 * np).copied().unwrap_or(0.) as usize;
        let mut moves = vec![];
        let (mut trains, mut wait, mut worst) = (0., 0., 0f64);
        for _ in 0..nm {
            let (from, to) = (d[at] as usize, d[at + 1] as usize);
            let (tph, delay, rho) = (d[at + 2 + lev], d[at + 5 + lev], d[at + 8 + lev]);
            at += 11;
            let nl = d[at] as usize;
            let lines: Vec<Value> = d[at + 1..at + 1 + nl].iter().map(|&l| line(l as u32)).collect();
            at += 1 + nl;
            let nc = d[at] as usize;
            let crosses: Vec<usize> = d[at + 1..at + 1 + nc].iter().map(|&c| c as usize).collect();
            at += 1 + nc;
            trains += tph;
            wait += tph * delay;
            worst = worst.max(rho);
            let label = format!("{} ke {}", ports.get(from).map_or("", |p| p.0.as_str()), ports.get(to).map_or("", |p| p.0.as_str()));
            moves.push(json!({"from":from,"to":to,"label":label,"tph":tph,"delay":delay,"rho":rho,"lines":lines,"crosses":crosses}));
        }
        let crossing = moves.iter().any(|m| m["crosses"].as_array().is_some_and(|c| !c.is_empty()));
        // The waiting a flyover saves: all the waiting at the junction, per train through it.
        let per_train = if trains > 0. { wait / trains } else { 0. };
        let price = if flying || at_crossover { 0. } else { self.api.flyover_cost(node) };
        let ports: Vec<Value> = ports.into_iter().map(|(name, dx, dy)| json!({"name":name,"dx":dx,"dy":dy})).collect();
        Some(json!({"node":node,"level":level,"ports":ports_n,"flying":flying,"built":built,"atCrossover":at_crossover,
            "demand":lev,"price":price,"perTrain":per_train,"worst":worst,"crossing":crossing,"portList":ports,"moves":moves}))
    }

    /// Web `edit({op: "flying"})`: a flyover paid at once on a constructed junction, added to
    /// the blueprint otherwise; or taken off a planned one.
    fn set_junction_flying(&mut self, node: u32, flying: bool, clock: f64) -> Result<Value, String> {
        let clock = self.native_clock(clock);
        if !clock.is_finite() || !self.api.world().net.node_ok(node) {
            return Err("Persimpangan sudah tidak ada.".into());
        }
        super::finance::tick(self, clock);
        self.api.set_now(clock);
        if self.api.set_flying(node, flying) != 0 {
            return Err(format!("Flyover ditolak: {}", crate::issue_text::describe(&self.api.issue_kinds())));
        }
        let charge = self.api.last_charge();
        self.header["cash"] = self.api.cash().into();
        super::finance::book_charge(&mut self.header, clock, charge, 0.);
        self.hour = i64::MIN;
        self.operating = None;
        Ok(json!({"changed":true,"charge":charge,"cash":self.api.cash()}))
    }
}

fn string(env: &JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_JunctionInspector_at(_: JNIEnv, _: JClass, handle: jlong, x: jdouble, y: jdouble, radius: jdouble) -> jint {
    unsafe { (handle as *const World).as_ref() }
        .and_then(|w| w.junction_at(x, y, radius))
        .map_or(-1, |n| n as jint)
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_JunctionInspector_read(env: JNIEnv, _: JClass, handle: jlong, node: jint, now: jdouble) -> jstring {
    let data = unsafe { (handle as *mut World).as_mut() }.and_then(|w| w.junction(node as u32, now));
    string(&env, data.unwrap_or(Value::Null))
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_JunctionInspector_flying(env: JNIEnv, _: JClass, handle: jlong, node: jint, flying: jboolean, clock: jdouble) -> jstring {
    let result = unsafe { (handle as *mut World).as_mut() }
        .ok_or_else(|| "Dunia belum siap.".to_string())
        .and_then(|w| w.set_junction_flying(node as u32, flying != 0, clock));
    string(&env, result.unwrap_or_else(|e| json!({"error":e})))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A main line west-east with a branch leaving it to the north-east, as the sim's
    /// `junction_inspector_and_delay_split`: two lines both ways through one junction.
    fn branch_world() -> (World, u32) {
        let mut w = World::workshop();
        let t = 111600.;
        w.build_service([0., 0.], [6000., 0.], "Utama", t).unwrap();
        let a = &mut w.api;
        let e = a.edges_info()[0];
        let ends = [2.0, e, 3000.0, 5.0, 0.0, 0.0, 0.0, 5000.0, 2500.0, 0.0];
        assert_eq!(a.add_route(&ends, &[], 2, false), 0, "{}", a.issue_kinds());
        let j = a.nodes_info().chunks(10).find(|c| c[4] == 3.0).unwrap()[0] as u32;
        let west = a.nodes_info().chunks(10).filter(|c| c[7] > 0.).min_by(|p, q| p[1].total_cmp(&q[1])).unwrap()[0] as u32;
        let branch = a.nodes_info().chunks(10).find(|c| (c[1] - 5000.).abs() < 1. && (c[2] - 2500.).abs() < 1.).unwrap()[0] as u32;
        assert_eq!(a.add_station(1, branch, 0.0, 0.0, 200, "Cabang".into()), 0, "{}", a.issue_kinds());
        let l = a.new_line_id();
        assert_eq!(a.set_line_with_trains(l, &[west, branch], 6, 4, 2, 30.0, 180.0, 4, 0x0000ff, "Cabang".into()), 0, "{}", a.issue_kinds());
        a.construct_all();
        (w, j)
    }

    #[test]
    fn junction_inspector_lists_routes_lines_and_the_flyover() {
        let (mut w, j) = branch_world();
        let t = 111600.;
        let (x, y) = {
            let n = &w.api.world().net;
            (n.node_x[j as usize], n.node_y[j as usize])
        };
        assert_eq!(w.junction_at(x + 5., y, 50.), Some(j));
        assert_eq!(w.junction_at(x + 500., y, 50.), None);
        assert_eq!(w.junction_at(x + 500., y, 0.), Some(j), "nearest at any distance, as nearestJunction");
        let v = w.junction(j, t).unwrap();
        println!("junction {v}");
        assert_eq!(v["ports"], 3);
        assert_eq!(v["flying"], false);
        assert_eq!(v["portList"].as_array().unwrap().len(), 3);
        let names: Vec<&str> = v["portList"].as_array().unwrap().iter().map(|p| p["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"Cabang"), "a port named by the station it leads to: {names:?}");
        let moves = v["moves"].as_array().unwrap();
        assert_eq!(moves.len(), 4, "each line both ways");
        assert!(moves.iter().all(|m| m["lines"].as_array().unwrap().len() == 1 && m["label"].as_str().unwrap().contains(" ke ")));
        assert!(moves.iter().any(|m| m["lines"][0]["name"] == "Cabang"));
        assert!(v["crossing"].as_bool().unwrap(), "the branch crosses the other way's track at grade");
        assert!(v["worst"].as_f64().unwrap() > 0.);
        let price = v["price"].as_f64().unwrap();
        assert!(price > 0.);
        // a constructed junction pays for its flyover at once
        let cash = w.api.cash();
        let done = w.set_junction_flying(j, true, t).unwrap();
        assert!((done["charge"].as_f64().unwrap() - price).abs() < 1e-6, "{done}");
        assert!((cash - w.api.cash() - price).abs() < 1e-6);
        let after = w.junction(j, t).unwrap();
        assert_eq!(after["flying"], true);
        assert_eq!(after["crossing"], false, "a flying junction has no crossing paths");
        assert_eq!(after["price"], 0.);
        // only a junction: a station or plain node is none
        assert!(w.junction(0, t).is_none_or(|v| v["ports"].as_u64().unwrap() >= 3));
    }
}
