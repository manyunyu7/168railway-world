//! Drawn train loads, including terminal boarding of the following run.
use crate::World;
use std::collections::HashMap;
use trainworld_sim::track::{
    boarding::{CRUSH_PER_CAR, RATED_PER_CAR, SEATS_PER_CAR},
    profile,
};

pub(super) struct Loads {
    epoch: f64,
    offsets: Vec<f32>,
    loads: Vec<f32>,
    stands: Vec<[f64; 3]>,
    /// The hour's boarding buffer and where its queues, events, pulses and arrivals start in it,
    /// for the riders at an arrival (station_life.rs, web stationLife `boardedAt`).
    board: Vec<f32>,
    at: [usize; 5],
    /// Web `queueOf`: (node, line, run) → queue.
    queues: HashMap<(u32, u32, u32), usize>,
}

struct Platforms<'a> {
    events: &'a [f32],
    arrivals: &'a [f32],
    pulses: &'a [f32],
}
impl Platforms<'_> {
    fn wait(&self, q: &[f32], t: f64) -> f64 {
        let first = q[8] as usize;
        let count = q[9] as usize;
        if count == 0 {
            return 0.;
        }
        let i = first
            + self.arrivals[first..first + count]
                .partition_point(|&x| f64::from(x) <= t)
                .saturating_sub(1);
        let departure = f64::from(self.events[2 * i]);
        let left = f64::from(self.events[2 * i + 1]);
        let grown = |e: usize, t: f64| {
            let dep = f64::from(self.events[2 * e]);
            (f64::from(self.events[2 * e + 1])
                + (t - dep) * f64::from(q[4])
                + self.pulses(q, dep, t))
            .min(5000.)
        };
        let stop = f64::from(self.arrivals[i]);
        if t >= departure {
            return grown(i, t);
        }
        if i == first || t < stop || departure - stop < 1. {
            return left;
        }
        let found = grown(i - 1, stop);
        found + (left - found) * (t - stop) / (departure - stop)
    }
    fn pulses(&self, q: &[f32], a: f64, b: f64) -> f64 {
        let i = q[10] as usize;
        let n = q[11] as usize;
        self.pulses[2 * i..2 * (i + n)]
            .chunks_exact(2)
            .filter(|p| f64::from(p[0]) > a && f64::from(p[0]) <= b)
            .map(|p| f64::from(p[1]))
            .sum()
    }
}

impl Loads {
    pub(super) fn new(world: &mut World, time: f64) -> Option<Self> {
        let epoch = world.load_trip_hour(time);
        let b = world.api.boarding_hour(epoch);
        if b.len() < 5 {
            return None;
        }
        let n = b[0] as usize;
        if n != world.trips.len() / 4 {
            return None;
        }
        let o = 5 + 2 * n;
        let q0 = o + b[1] as usize;
        let e0 = q0 + 12 * b[2] as usize;
        let p0 = e0 + 2 * b[3] as usize;
        let a0 = p0 + 2 * b[4] as usize;
        let platforms = Platforms {
            events: &b[e0..p0],
            pulses: &b[p0..a0],
            arrivals: &b[a0..a0 + b[3] as usize],
        };
        let queues = &b[q0..e0];
        let mut first = HashMap::<(u32, u32), &[f32]>::new();
        for q in queues.chunks_exact(12) {
            let key = (q[1] as u32, q[2] as u32);
            let top = first.entry(key).or_insert(q);
            if (key.1 == 0 && q[3] < top[3]) || (key.1 == 1 && q[3] > top[3]) {
                *top = q;
            }
        }
        let round = |v: f32| (f64::from(v) + 0.5).floor() as i64;
        let index: HashMap<_, _> = world
            .trips
            .chunks_exact(4)
            .enumerate()
            .map(|(i, t)| ((t[0] as u32, round(t[1])), i))
            .collect();
        let mut stands = vec![[-1., -1., 0.]; n];
        for (i, t) in world.trips.chunks_exact(4).enumerate() {
            if !t[3].is_finite() {
                continue;
            }
            let p = t[0] as u32;
            let run = 1 - (p / 3) % 2;
            let Some(&q) = first.get(&(p / 6, run)) else {
                continue;
            };
            let j = q[8] as usize;
            let end = j + q[9] as usize;
            let Some(e) = (j..end).find(|&e| platforms.events[2 * e] >= t[3] - 0.5) else {
                continue;
            };
            let dep = f64::from(platforms.events[2 * e]);
            let stop = f64::from(platforms.arrivals[e]);
            if dep > f64::from(t[3]) + 0.5 {
                continue;
            }
            let aboard = (0..3)
                .rev()
                .filter_map(|lv| index.get(&(p / 6 * 6 + run * 3 + lv, round(t[3]))))
                .map(|&k| b[5 + 2 * k])
                .find(|&off| off >= 0.)
                .map(|off| f64::from(b[o + off as usize]))
                .unwrap_or_else(|| {
                    (platforms.wait(q, stop - 0.001)
                        + f64::from(q[4]) * (dep - stop)
                        + platforms.pulses(q, stop, dep)
                        - f64::from(platforms.events[2 * e + 1]))
                    .max(0.)
                });
            stands[i] = [stop, dep, aboard];
        }
        let mut by_key = HashMap::new();
        for (k, q) in queues.chunks_exact(12).enumerate() {
            by_key.entry((q[0] as u32, q[1] as u32, q[2] as u32)).or_insert(k);
        }
        let end = a0 + b[3] as usize;
        Some(Self {
            epoch,
            offsets: b[5..o].to_vec(),
            loads: b[o..q0].to_vec(),
            stands,
            queues: by_key,
            at: [q0, e0, p0, a0, end],
            board: b,
        })
    }
    pub(super) fn rows(&self, world: &World, time: f64) -> Vec<i32> {
        let now = time - self.epoch;
        let model = world.api.world();
        let mut rows = Vec::new();
        for (i, t) in world.trips.chunks_exact(4).enumerate() {
            let elapsed = now - f64::from(t[1]);
            let offset = self.offsets[i * 2];
            if elapsed < 0. || now >= f64::from(t[2]) || offset < 0. {
                continue;
            }
            let p = t[0] as usize;
            let run = p / 3 % 2;
            let service = &model.svc.lines[p / 6];
            let r = &service.runs[run];
            if r.stop_s.len() < 2 {
                continue;
            }
            let aboard = if elapsed >= f64::from(self.offsets[i * 2 + 1]) {
                let [a, b, load] = self.stands[i];
                ((now - a) / (b - a).max(1.)).clamp(0., 1.) * load
            } else {
                let (dist, _) = profile::state_at(&r.prof[p % 3].phases, elapsed);
                let last = r.stop_s.len() - 2;
                let seg = r
                    .stop_s
                    .partition_point(|&x| x <= dist + 1.)
                    .saturating_sub(1)
                    .min(last);
                f64::from(self.loads[offset as usize + if run == 0 { seg } else { last - seg }])
            };
            let cars = if service.ferry {
                2.
            } else if service.bus.is_some() {
                1. / 3.
            } else {
                service.cars as f64
            };
            let (seats, rated, crush) = (
                SEATS_PER_CAR * cars,
                RATED_PER_CAR * cars,
                CRUSH_PER_CAR * cars,
            );
            let gauge = if aboard <= seats {
                if seats > 0. {
                    0.5 * aboard.max(0.) / seats
                } else {
                    0.
                }
            } else if aboard <= rated {
                0.5 + 0.5 * (aboard - seats) / (rated - seats)
            } else if aboard < crush - 0.5 {
                (1. + (aboard - rated) / (crush - rated)).min(1.99)
            } else {
                2.
            };
            rows.extend([
                p as i32,
                (f64::from(t[1]) * 100. + 0.5).floor() as i32,
                (gauge * 100.).round() as i32,
            ]);
        }
        rows
    }
    /// The index in `World::trips` of the trip `profile` leaving `dep` s into the hour.
    pub(super) fn trip(&self, world: &World, profile: usize, dep: f64) -> Option<usize> {
        world
            .trips
            .chunks_exact(4)
            .position(|t| t[0] as usize == profile && (f64::from(t[1]) - dep).abs() < 0.5)
            .filter(|&i| self.offsets.get(i * 2).is_some_and(|&o| o >= 0.))
    }
    /// Riders aboard trip `i` on stretch `k` (run 0's numbering, web `tripLoad`).
    pub(super) fn stretch(&self, i: usize, k: usize) -> Option<f64> {
        let o = *self.offsets.get(i * 2)?;
        (o >= 0.)
            .then(|| self.loads.get(o as usize + k).copied().map(f64::from))
            .flatten()
    }
    /// Web stationLife `boardedAt`: at the platform where `line`'s run `run` stops at `node`, the
    /// riders who got on a train arriving at game time `time`, the average time the riders
    /// waiting had waited, s, and how many the train before left behind. The newcomers arrived
    /// evenly since that train left (half the gap each); those it left behind add the gap before
    /// it, again half on average. Nothing is known of the hour's first arrival: 0. None for a
    /// platform the account has no queue for.
    pub(super) fn boarded_at(&self, node: u32, line: u32, run: u32, time: f64) -> Option<(f64, f64, f64)> {
        let &k = self.queues.get(&(node, line, run))?;
        let [q0, e0, p0, a0, end] = self.at;
        let b = &self.board;
        let p = Platforms { events: &b[e0..p0], pulses: &b[p0..a0], arrivals: &b[a0..end] };
        let q = &b[q0 + 12 * k..q0 + 12 * (k + 1)];
        let (ev, arr) = (p.events, p.arrivals);
        let t = time - self.epoch;
        let first = q[8] as usize;
        let last = first + q[9] as usize;
        let dep = |e: usize| f64::from(ev[2 * e]);
        let mut e = first;
        // events are departures; the one a train arriving at `t` makes comes a little after it
        while e < last && dep(e) < t - 8. {
            e += 1;
        }
        // the crowd just before it stopped (T-1123: it boards as the train stops)
        let stop = if e < last { t.min(f64::from(arr[e])) } else { t };
        let waiting = p.wait(q, stop - 1e-3);
        let boarded = if e < last {
            (waiting + f64::from(q[4]) * (dep(e) - stop).max(0.) + p.pulses(q, stop, dep(e))
                - f64::from(ev[2 * e + 1]))
            .max(0.)
        } else {
            0.
        };
        if e == first {
            return Some((boarded, 0., 0.));
        }
        let prev = e - 1;
        let gap = (stop - dep(prev)).max(0.);
        let left = f64::from(ev[2 * prev + 1]);
        let before = if prev > first { dep(prev) - dep(prev - 1) } else { gap };
        let fresh = (waiting - left).max(0.);
        let riders = left + fresh;
        let waited = if riders > 0. { (left * (gap + before / 2.) + fresh * gap / 2.) / riders } else { 0. };
        Some((boarded, waited, left))
    }
    pub(super) fn current(&self, time: f64) -> bool {
        time >= self.epoch && time < self.epoch + 3600.
    }
}

#[no_mangle]
pub extern "system" fn Java_world_nativegame_NativeRidership_trainLoads(
    mut env: jni::JNIEnv,
    _: jni::objects::JClass,
    archive: jni::objects::JByteArray,
    spec: jni::objects::JString,
    time: f64,
) -> jni::sys::jintArray {
    let result = (|| {
        let bytes = env.convert_byte_array(archive).ok()?;
        let manifest: String = env.get_string(&spec).ok()?.into();
        let files: crate::demand::DemandFiles = serde_json::from_str(&manifest).ok()?;
        let mut world = World::open(&bytes)?;
        files.read().ok()?.account(&bytes, &mut world).ok()?;
        let rows = Loads::new(&mut world, time)?.rows(&world, time);
        let out = env.new_int_array(i32::try_from(rows.len()).ok()?).ok()?;
        env.set_int_array_region(&out, 0, &rows).ok()?;
        Some(out.into_raw())
    })();
    result.unwrap_or(std::ptr::null_mut())
}
