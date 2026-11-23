//! Compact columns passed to the clock. Hash original values before narrowing them.
use super::*;

#[wasm_bindgen]
#[derive(Default)]
pub struct CorridorColumns {
    rows: u32,
    hash: u32,
    wide: bool,
    from16: Vec<u16>,
    to16: Vec<u16>,
    from32: Vec<u32>,
    to32: Vec<u32>,
    longer: Vec<f32>,
    commute_at: Vec<u32>,
    commute_legs: Vec<f64>,
    air: Vec<u16>,
    car: Vec<u16>,
}

#[wasm_bindgen]
impl CorridorColumns {
    pub fn rows(&self) -> u32 {
        self.rows
    }
    pub fn hash(&self) -> u32 {
        self.hash
    }
    pub fn wide(&self) -> bool {
        self.wide
    }
    pub fn take_from16(&mut self) -> Vec<u16> {
        std::mem::take(&mut self.from16)
    }
    pub fn take_to16(&mut self) -> Vec<u16> {
        std::mem::take(&mut self.to16)
    }
    pub fn take_from32(&mut self) -> Vec<u32> {
        std::mem::take(&mut self.from32)
    }
    pub fn take_to32(&mut self) -> Vec<u32> {
        std::mem::take(&mut self.to32)
    }
    pub fn take_longer(&mut self) -> Vec<f32> {
        std::mem::take(&mut self.longer)
    }
    pub fn take_commute_at(&mut self) -> Vec<u32> {
        std::mem::take(&mut self.commute_at)
    }
    pub fn take_commute_legs(&mut self) -> Vec<f64> {
        std::mem::take(&mut self.commute_legs)
    }
    pub fn take_air(&mut self) -> Vec<u16> {
        std::mem::take(&mut self.air)
    }
    pub fn take_car(&mut self) -> Vec<u16> {
        std::mem::take(&mut self.car)
    }
}

fn share_code(value: f32) -> u16 {
    if value.is_nan() {
        return u16::MAX;
    }
    (f64::from(value.clamp(0., 1.)) * 65534.).round() as u16
}

#[wasm_bindgen]
impl IntercityApi {
    pub fn corridor_columns(&self, make: bool) -> CorridorColumns {
        let n = self.n_rows();
        let mut out = CorridorColumns {
            rows: n as u32,
            ..Default::default()
        };
        if make {
            out.longer.reserve_exact(n);
            out.air.reserve_exact(n);
            out.car.reserve_exact(n);
        }
        let mut h = 0x811c9dc5u32;
        let mut word = |w: u32| {
            h = (h ^ w).wrapping_mul(0x01000193);
        };
        let pairs = self.corridor_rows_pairs();
        out.wide = pairs.iter().any(|&v| v > u16::MAX.into());
        for &v in pairs.iter().step_by(2) {
            word(v);
        }
        for &v in pairs.iter().skip(1).step_by(2) {
            word(v);
        }
        if make {
            if out.wide {
                (out.from32, out.to32) = pairs.chunks_exact(2).map(|p| (p[0], p[1])).unzip();
            } else {
                (out.from16, out.to16) = pairs
                    .chunks_exact(2)
                    .map(|p| (p[0] as u16, p[1] as u16))
                    .unzip();
            }
        }
        drop(pairs);
        for (_, _, _, trips) in self.table_rows() {
            word(trips.to_bits() as u32);
            word((trips.to_bits() >> 32) as u32);
            if make {
                out.longer.push(trips as f32);
            }
        }
        let legs = self.corridor_rows_commutes();
        for i in 0..n {
            let value = legs.get(i).copied().unwrap_or(0.);
            let bits = value.to_bits();
            word(bits as u32);
            word((bits >> 32) as u32);
            if make && bits != 0 {
                out.commute_at.push(i as u32);
                out.commute_legs.push(value);
            }
        }
        drop(legs);
        let modes = self.corridor_rows_modes();
        for &air in modes.iter().step_by(2) {
            word(air.to_bits());
            if make {
                out.air.push(share_code(air));
            }
        }
        for &car in modes.iter().skip(1).step_by(2) {
            word(car.to_bits());
            if make {
                out.car.push(share_code(car));
            }
        }
        out.hash = h;
        out
    }
}
