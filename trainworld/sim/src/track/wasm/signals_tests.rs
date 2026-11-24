//! Signalling per piece of track (`signal.rs`, `wasm/signals.rs`).

use super::*;
use crate::track::capacity::ResKind;
use crate::track::service::Service;
use crate::track::tests::{edge, line, node, station, world};

/// Built ordinary double track A-B-C-D, 4 km a piece, stations at A, C and D, a line A-D with a
/// fixed fleet (so no edit buys trains); the edges in order.
fn three_pieces() -> (TrackApi, u32, [u32; 3]) {
    let mut w = world();
    let n = [node(&mut w, 0.0, 0.0, 0), node(&mut w, 4000.0, 0.0, 0), node(&mut w, 8000.0, 0.0, 0), node(&mut w, 12000.0, 0.0, 0)];
    let e = [edge(&mut w, n[0], n[1], &[], 0).unwrap(), edge(&mut w, n[1], n[2], &[], 0).unwrap(), edge(&mut w, n[2], n[3], &[], 0).unwrap()];
    station(&mut w, n[0], 200);
    station(&mut w, n[2], 200);
    station(&mut w, n[3], 200);
    let l = line(&mut w, &[n[0], n[2], n[3]], [12.0, 6.0, 3.0]);
    let mut d = w.net.line_data(l).unwrap();
    d.trains = Some([12, 12, 12]);
    w.apply(Op::Line { id: l, data: Some(d) }).unwrap();
    let mut a = TrackApi::new(-73.985, 40.758, true);
    a.set_cash(1e9);
    a.w = w;
    assert_eq!(a.set_line_limit(l, 0), 0);
    (a, l, e)
}

/// The section resource holding edge `e` and its first user's occupancy, s.
fn section_occ(a: &TrackApi, e: u32) -> f64 {
    let cap = &a.w.svc.cap;
    let r = (0..cap.res.len()).find(|&r| cap.res[r].kind == ResKind::Section && cap.track_edges(r).contains(&e)).expect("a section on the edge");
    cap.users_of(r).iter().map(|u| u.occ).fold(0.0, f64::max)
}

/// Everything the schedule is made of, to compare two states bit for bit.
fn schedule(a: &TrackApi) -> String {
    let mut s = format!("{:?}", a.w.svc.cap.canonical());
    for l in &a.w.svc.lines {
        s += &format!("{:?}{:?}{:?}", l.round_trip, l.trains, l.delay);
        for r in &l.runs {
            s += &format!("{:?}{:?}{:?}{:?}", r.limits, r.holds, r.stop_holds, r.prof);
        }
    }
    s
}

/// The incremental state against everything recomputed from the network.
fn same_as_global(a: &TrackApi, what: &str) {
    let mut g = Service::default();
    g.update_all(&a.w.net);
    assert_eq!(a.w.svc.cap.canonical(), g.cap.canonical(), "{what}: resources");
    for (x, y) in a.w.svc.lines.iter().zip(&g.lines) {
        assert_eq!(x.round_trip, y.round_trip, "{what}: round trip");
    }
}

#[test]
fn closed_block_is_every_pieces_and_changes_nothing() {
    let (mut a, _l, e) = three_pieces();
    assert!(a.edge_signals().is_empty());
    assert_eq!(a.edge_signal(e[0]), SIGNAL_CLOSED);
    let before = schedule(&a);
    let bytes = crate::track::save::encode(&a.w.net);
    let cost = a.w.net.total_and_built_cost();
    // set to the same: nothing; up and back: the schedule and the save to the bit
    assert_eq!(a.set_edges_signal(&[e[0], e[1]], SIGNAL_CLOSED), 1);
    assert_eq!(a.set_edges_signal(&[e[0], e[1], e[2]], SIGNAL_CBTC), 0, "{}", a.issues());
    assert_ne!(schedule(&a), before);
    assert_eq!(a.set_edges_signal(&[e[0], e[1], e[2]], SIGNAL_CLOSED), 0);
    assert_eq!(schedule(&a), before);
    assert_eq!(crate::track::save::encode(&a.w.net), bytes);
    assert_eq!(a.w.net.total_and_built_cost(), cost);
    same_as_global(&a, "back to closed");
    // the default spacing is the model's: 70 s and v / 0.6
    let occ = section_occ(&a, e[0]);
    let v = crate::track::profile::v_max(&a.w.svc.lines[0].runs[0].free.phases, 0.0, 12000.0);
    assert!(occ <= SECTION_BASE_S + v / SIGNAL_BRAKE + 1e-9 && occ > SECTION_BASE_S, "{occ}");
}

#[test]
fn atp_and_cbtc_shorten_the_spacing_and_raise_the_trains_an_hour() {
    let (mut a, _l, e) = three_pieces();
    let all = [e[0], e[1], e[2]];
    let closed = section_occ(&a, e[0]);
    let tph = a.signal_info(e[0]);
    assert_eq!(tph[0], SIGNAL_CLOSED as f64);
    assert_eq!(a.set_edges_signal(&all, SIGNAL_ATP), 0);
    assert!((section_occ(&a, e[0]) - closed * 0.75).abs() < 1e-9);
    same_as_global(&a, "atp");
    assert_eq!(a.set_edges_signal(&all, SIGNAL_CBTC), 0);
    assert!((section_occ(&a, e[0]) - closed * 0.5).abs() < 1e-9);
    same_as_global(&a, "cbtc");
    // the inspector's figures: 3600 / (70 + 44.4 / 0.6) = 24.9 an hour closed at 160, a third more
    // with ATP, twice with CBTC
    let info = a.signal_info(e[0]);
    let t = |sig: usize| info[1 + 3 * sig];
    assert!((t(1) - 3600.0 / (SECTION_BASE_S + V_TOP / SIGNAL_BRAKE)).abs() < 1e-9);
    assert!((t(2) / t(1) - 4.0 / 3.0).abs() < 1e-9 && (t(3) / t(1) - 2.0).abs() < 1e-9);
    assert!(t(0) < t(1), "open block takes fewer");
    // a junction's conflict and (CBTC) a platform's margin shorten too
    let n = a.w.net.edge_b[e[1] as usize];
    assert!((a.w.net.node_margin_s(n) - PLATFORM_MARGIN_S * 0.8).abs() < 1e-9);
    assert!((a.w.net.node_conflict_s(n) - CONFLICT_S * 0.6).abs() < 1e-9);
    // a node runs at the poorest track meeting there
    assert_eq!(a.set_edges_signal(&[e[2]], SIGNAL_ATP), 0);
    assert!((a.w.net.node_conflict_s(n) - CONFLICT_S * 0.75).abs() < 1e-9);
    // a section over pieces of mixed signalling runs at its poorest
    assert_eq!(a.set_edges_signal(&[e[0]], SIGNAL_CLOSED), 0);
    assert!((section_occ(&a, e[1]) - closed).abs() < 1e-9, "{}", section_occ(&a, e[1]));
    same_as_global(&a, "mixed");
}

#[test]
fn open_block_holds_one_train_per_block_and_caps_the_speed() {
    let (mut a, l, e) = three_pieces();
    // A-C is one block (B is a plain joint), C-D another
    assert_eq!(a.signal_block(e[0]), vec![e[0], e[1]]);
    assert_eq!(a.signal_block(e[1]), vec![e[0], e[1]]);
    assert_eq!(a.signal_block(e[2]), vec![e[2]]);
    let closed = section_occ(&a, e[0]);
    assert_eq!(a.set_edges_signal(&[e[0], e[1]], SIGNAL_OPEN), 0, "{}", a.issues());
    same_as_global(&a, "open");
    let run = &a.w.svc.lines[l as usize].runs[0];
    let top = crate::track::profile::v_max(&run.free.phases, 0.0, 8000.0) * 3.6;
    assert!((top - OPEN_BLOCK_KMH).abs() < 1e-6, "{top}");
    // A to C at most 90 km/h: 8 km takes over 320 s, and the train holds the block that long and the margin
    let occ = section_occ(&a, e[0]);
    let ride = crate::track::signal::run_time(&run.free.phases, run.node_s[0], run.node_s[2]);
    assert!(ride > 8000.0 / 25.0, "{ride}");
    assert!((occ - (ride + OPEN_BLOCK_MARGIN_S)).abs() < 1e-9 && occ > closed, "{occ} {ride}");
    // so the line can run at most one train per block time
    assert!(3600.0 / occ < 10.0);
    let held = a.line_held(l);
    assert!(held[7 * LEVELS] > 0.0, "the line is held back: {held:?}");
    // back to closed block: as before
    assert_eq!(a.set_edges_signal(&[e[0], e[1]], SIGNAL_CLOSED), 0);
    assert!((section_occ(&a, e[0]) - closed).abs() < 1e-12);
}

#[test]
fn signalling_is_priced_paid_on_built_track_and_upkept() {
    let (mut a, _l, e) = three_pieces();
    let cash = a.cash();
    let upkeep = a.upkeep()[1];
    let len = a.w.net.edge_len[e[0] as usize];
    // CBTC: 40% of ground double track's 37.5 a km
    let want = len / 1000.0 * BASE_COST_PER_KM * 0.5 * 0.40;
    assert_eq!(a.signal_quote(&[e[0]], SIGNAL_CBTC), vec![want, 0.0, len / 1000.0 * UPKEEP_GROUND_KM * 0.4 * ECONOMY / 1e6]);
    assert_eq!(a.set_edges_signal(&[e[0]], SIGNAL_CBTC), 0);
    assert!((cash - a.cash() - want).abs() < 1e-9, "paid {}", cash - a.cash());
    assert!((a.upkeep()[1] - upkeep - len / 1000.0 * UPKEEP_GROUND_KM * 0.4 * ECONOMY / 1e6).abs() < 1e-12);
    // down to ATP: no refund, nothing paid
    let cash = a.cash();
    assert_eq!(a.set_edges_signal(&[e[0]], SIGNAL_ATP), 0);
    assert_eq!(a.cash(), cash);
    // short of cash: refused, nothing changes
    a.set_cash(0.0);
    assert_ne!(a.set_edges_signal(&[e[1]], SIGNAL_CBTC), 0);
    assert_eq!(a.edge_signal(e[1]), SIGNAL_CLOSED);
    // open block is free
    assert_eq!(a.set_edges_signal(&[e[1]], SIGNAL_OPEN), 0);
}

#[test]
fn signalling_undoes_survives_a_split_and_a_removal_undone_and_stops_a_join() {
    let (mut a, _l, e) = three_pieces();
    assert_eq!(a.set_edges_signal(&[e[2]], SIGNAL_ATP), 0);
    assert_eq!(a.undo(), 0);
    assert_eq!(a.edge_signal(e[2]), SIGNAL_CLOSED);
    assert_eq!(a.redo(), 0);
    assert_eq!(a.edge_signal(e[2]), SIGNAL_ATP);
    // a split's far half keeps it
    let node = a.w.net.alloc_node();
    let new_edge = a.w.net.alloc_edge();
    a.w.apply(Op::Split { edge: e[2], s: 2000.0, node, new_edge }).unwrap();
    assert_eq!((a.edge_signal(e[2]), a.edge_signal(new_edge)), (SIGNAL_ATP, SIGNAL_ATP));
    a.w.undo().unwrap().unwrap();
    // a removal undone puts it back
    a.w.apply(Op::DeleteEdge { id: e[2] }).unwrap();
    assert!(!a.w.net.edge_ok(e[2]));
    a.w.undo().unwrap().unwrap();
    assert_eq!(a.edge_signal(e[2]), SIGNAL_ATP);
    a.w.apply(Op::Edge { id: e[2], data: None }).unwrap();
    a.w.undo().unwrap().unwrap();
    assert_eq!(a.edge_signal(e[2]), SIGNAL_ATP);
    // pieces of unlike signalling never join; alike ones do
    assert_eq!(a.set_edges_signal(&[e[1]], SIGNAL_CBTC), 0);
    assert!(a.unjoin_near(e[0], 3900.0, 500.0).is_none());
    assert_eq!(a.set_edges_signal(&[e[0]], SIGNAL_CBTC), 0);
    assert!(a.unjoin_near(e[0], 3900.0, 500.0).is_some());
}

#[test]
fn signalling_saves_and_loads_and_a_network_without_it_saves_as_before() {
    let (mut a, l, e) = three_pieces();
    let before = crate::track::save::encode(&a.w.net);
    assert!(!before.windows(4).any(|x| x == b"LSG1"));
    assert_eq!(a.set_edges_signal(&[e[0], e[1]], SIGNAL_OPEN), 0);
    assert_eq!(a.set_edges_signal(&[e[2]], SIGNAL_CBTC), 0);
    let bytes = crate::track::save::encode(&a.w.net);
    assert!(bytes.windows(4).any(|x| x == b"LSG1"));
    assert_eq!(&bytes[..before.len()], &before[..], "the block is only added at the end");
    let (net, unread) = crate::track::save::decode_read(&bytes).unwrap();
    assert_eq!(unread, 0);
    let back = crate::track::world::TrackWorld::new(net);
    assert_eq!(back.net.edge_signal[e[0] as usize], SIGNAL_OPEN);
    assert_eq!(back.net.edge_signal[e[2] as usize], SIGNAL_CBTC);
    assert_eq!(crate::track::save::encode(&back.net), bytes);
    assert_eq!(back.svc.lines[l as usize].round_trip, a.w.svc.lines[l as usize].round_trip);
    assert_eq!(back.net.edge_cost[e[2] as usize], a.w.net.edge_cost[e[2] as usize]);
    // through the API's own save and load
    let saved = a.save();
    let mut b = TrackApi::new(-73.985, 40.758, true);
    assert!(b.load(&saved));
    assert_eq!(b.edge_signals(), a.edge_signals());
    assert_eq!(a.set_edges_signal(&[e[0], e[1], e[2]], SIGNAL_CLOSED), 0);
    assert_eq!(crate::track::save::encode(&a.w.net), before);
}
