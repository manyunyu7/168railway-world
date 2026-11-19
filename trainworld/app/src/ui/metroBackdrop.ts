// 168Railway: the start menu's backdrop, a metro map drawing itself (octilinear coloured lines, stations where
// they cross, trains stopping at them), ported from PPKA Simulator's opening screen (ppka-wannabe-2
// src/ui/intro.ts, 168Railway's own code) without its sounds. Decoration only, apart from the game's map.

interface Titik { x: number; y: number }

interface KeretaMetro {
  d: number;
  arah: 1 | -1;
  v: number;
  vmax: number;
  tunggu: number;                 // sisa detik berhenti di stasiun (0 = jalan)
}

interface JalurMetro {
  pts: Titik[];
  seglen: number[];
  total: number;
  warna: string;
  stasiun: number[];              // jarak-jarak stasiun sepanjang jalur (terurut)
  kereta: KeretaMetro[];
  delay: number;                  // detik sebelum jalur mulai "menggambar diri"
}

const WARNA_METRO = ['#e6402e', '#f2a900', '#2e77e6', '#2ea84f', '#8e4fd1', '#00b2c9', '#e05fa0', '#7a8a2e'];
const DURASI_TUMBUH = 1.5;        // detik animasi jalur tergambar
const AKSEL = 55;                 // px/s² kereta
const DESEL = 95;
const AMP_PAR = 22;               // amplitudo geser parallax mengikuti kursor (px)

// ---------- geometri jalur ----------

function posDi(j: JalurMetro, d: number): { x: number; y: number; ang: number } {
  let acc = 0;
  for (let i = 0; i < j.seglen.length; i++) {
    if (d <= acc + j.seglen[i]) {
      const t = j.seglen[i] > 0 ? (d - acc) / j.seglen[i] : 0;
      const a = j.pts[i], b = j.pts[i + 1];
      return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, ang: Math.atan2(b.y - a.y, b.x - a.x) };
    }
    acc += j.seglen[i];
  }
  const a = j.pts[j.pts.length - 2], b = j.pts[j.pts.length - 1];
  return { x: b.x, y: b.y, ang: Math.atan2(b.y - a.y, b.x - a.x) };
}

/** Rangkaian titik oktilinear melintasi layar: ruas lurus + diagonal 45° pendek/panjang. */
function titikOktilinear(w: number, h: number, orient: 'h' | 'v', posBand: number): Titik[] {
  const pts: Titik[] = [];
  // sumbu u = arah maju, sumbu s = samping (dijaga dalam layar)
  const maju = orient === 'h' ? w : h;
  const lebar = orient === 'h' ? h : w;
  let u = -80;
  let s = posBand + (Math.random() - 0.5) * 60;
  const push = () => pts.push(orient === 'h' ? { x: u, y: s } : { x: s, y: u });
  push();
  while (u < maju + 80) {
    u += 80 + Math.random() * 200;               // ruas lurus
    push();
    if (u >= maju + 80) break;
    // tekukan diagonal 45°: kebanyakan pendek, kadang menyeberang jauh
    const jauh = Math.random() < 0.3;
    let ds = (jauh ? 90 + Math.random() * 150 : 30 + Math.random() * 55) * (Math.random() < 0.5 ? -1 : 1);
    if (s + ds < 50 || s + ds > lebar - 50) ds = -ds;
    if (s + ds < 50 || s + ds > lebar - 50) ds = 0; // band terlalu sempit — lurus saja
    if (ds !== 0) {
      u += Math.abs(ds);
      s += ds;
      push();
    }
  }
  return pts;
}

function potongRuas(a1: Titik, a2: Titik, b1: Titik, b2: Titik): Titik | null {
  const d1x = a2.x - a1.x, d1y = a2.y - a1.y;
  const d2x = b2.x - b1.x, d2y = b2.y - b1.y;
  const den = d1x * d2y - d1y * d2x;
  if (Math.abs(den) < 1e-6) return null;
  const t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / den;
  const u = ((b1.x - a1.x) * d1y - (b1.y - a1.y) * d1x) / den;
  if (t < 0.06 || t > 0.94 || u < 0.06 || u > 0.94) return null; // hindari ujung/sudut
  return { x: a1.x + d1x * t, y: a1.y + d1y * t };
}

// ---------- animasi utama ----------

/** Start the backdrop on the canvas; the function returned stops it. */
export function startMetroBackdrop(canvas: HTMLCanvasElement): () => void {
  const ctx = canvas.getContext('2d')!;
  const kurangGerak = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  let ptrX = 0, ptrY = 0;         // posisi kursor ternormal [-1,1] dari pusat layar
  let parX = 0, parY = 0;         // offset parallax teranimasi (px)
  const onMove = (e: PointerEvent) => {
    const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
    ptrX = (e.clientX / w - 0.5) * 2;
    ptrY = (e.clientY / h - 0.5) * 2;
  };
  let jalur: JalurMetro[] = [];
  let transit: Titik[] = [];      // stasiun transit di persilangan antar-jalur
  let sungai: Titik[] = [];
  let hidup = true;
  let mulai = performance.now();
  let lalu = mulai;

  function bangun() {
    const w = canvas.clientWidth || window.innerWidth;
    const h = canvas.clientHeight || window.innerHeight;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // sungai samar melintang miring (dekorasi khas peta metro)
    sungai = titikOktilinear(w, h, 'h', h * (0.3 + Math.random() * 0.4));

    jalur = [];
    const nH = Math.max(3, Math.min(6, Math.round(h / 180)));
    const nV = Math.max(2, Math.min(4, Math.round(w / 420)));
    const spek: { orient: 'h' | 'v'; band: number }[] = [];
    for (let i = 0; i < nH; i++) spek.push({ orient: 'h', band: ((i + 0.65) / (nH + 0.3)) * h });
    for (let i = 0; i < nV; i++) spek.push({ orient: 'v', band: ((i + 0.75) / (nV + 0.5)) * w });
    // selang-seling H/V supaya warna berdampingan tidak seragam arahnya
    spek.sort(() => Math.random() - 0.5);

    spek.forEach((sp, i) => {
      const pts = titikOktilinear(w, h, sp.orient, sp.band);
      const seglen: number[] = [];
      let total = 0;
      for (let k = 0; k < pts.length - 1; k++) {
        const L = Math.hypot(pts[k + 1].x - pts[k].x, pts[k + 1].y - pts[k].y);
        seglen.push(L);
        total += L;
      }
      const stasiun: number[] = [];
      for (let d = 130 + Math.random() * 130; d < total - 110; d += 210 + Math.random() * 170) stasiun.push(d);
      const kereta: KeretaMetro[] = Array.from({ length: total > 900 ? 2 : 1 }, () => ({
        d: Math.random() * total,
        arah: (Math.random() < 0.5 ? 1 : -1) as 1 | -1,
        v: 0,
        vmax: 60 + Math.random() * 40,
        tunggu: 0,
      }));
      jalur.push({ pts, seglen, total, warna: WARNA_METRO[i % WARNA_METRO.length], stasiun, kereta, delay: i * 0.13 });
    });

    // stasiun transit: persilangan antar-jalur, dijaga saling berjauhan
    transit = [];
    for (let a = 0; a < jalur.length; a++) {
      for (let b = a + 1; b < jalur.length; b++) {
        const A = jalur[a].pts, B = jalur[b].pts;
        for (let i = 0; i < A.length - 1; i++) {
          for (let k = 0; k < B.length - 1; k++) {
            const p = potongRuas(A[i], A[i + 1], B[k], B[k + 1]);
            if (p && transit.every((q) => Math.hypot(q.x - p.x, q.y - p.y) > 90)) transit.push(p);
          }
        }
      }
    }
    transit = transit.slice(0, 14);
  }

  function jalurPath(pts: Titik[]) {
    ctx.beginPath();
    ctx.moveTo(pts[0].x, pts[0].y);
    for (let i = 1; i < pts.length - 1; i++) ctx.arcTo(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, 13);
    ctx.lineTo(pts[pts.length - 1].x, pts[pts.length - 1].y);
  }

  // kereta berhenti-jalan: melambat mendekati stasiun berikut, berhenti sejenak, lalu berangkat
  function langkahKereta(j: JalurMetro, k: KeretaMetro, dt: number) {
    if (k.tunggu > 0) {
      k.tunggu -= dt;
      if (k.tunggu <= 0) {
        k.tunggu = 0;
      }
      return;
    }
    // target berikut searah: stasiun terdekat di depan, atau ujung jalur
    let next = k.arah > 0 ? j.total : 0;
    let ujung = true;
    for (const d of j.stasiun) {
      if ((d - k.d) * k.arah > 4 && Math.abs(d - k.d) < Math.abs(next - k.d)) { next = d; ujung = false; }
    }
    const sisa = Math.abs(next - k.d);
    const vIzin = Math.sqrt(2 * DESEL * Math.max(0, sisa));
    k.v = Math.min(k.vmax, k.v + AKSEL * dt, vIzin);
    k.d += k.v * k.arah * dt;
    if (sisa <= k.v * dt + 0.5) {
      k.d = next;
      k.v = 0;
      if (ujung) {
        k.arah = (k.arah * -1) as 1 | -1;
        k.tunggu = 0.5;
      } else {
        k.tunggu = 0.8 + Math.random() * 1.0;
      }
    }
  }

  function frame(now: number) {
    if (!hidup) return;
    const dt = Math.min(0.05, (now - lalu) / 1000);
    lalu = now;
    const t = (now - mulai) / 1000;
    const gelap = document.documentElement.dataset.shade === 'dark';
    const w = canvas.clientWidth, h = canvas.clientHeight;
    ctx.clearRect(0, 0, w, h);

    // parallax mengikuti kursor; sedikit zoom agar tepi tak bolong saat digeser
    const lerp = Math.min(1, dt * 6);
    parX += ((kurangGerak ? 0 : -ptrX * AMP_PAR) - parX) * lerp;
    parY += ((kurangGerak ? 0 : -ptrY * AMP_PAR) - parY) * lerp;
    const fgX = parX * 0.5, fgY = parY * 0.5;   // lapisan depan bergerak lebih → kedalaman
    ctx.save();
    ctx.translate(w / 2, h / 2); ctx.scale(1.05, 1.05); ctx.translate(-w / 2, -h / 2);
    ctx.translate(parX, parY);

    // sungai di lapisan paling bawah
    ctx.save();
    ctx.strokeStyle = gelap ? 'rgba(90, 140, 190, 0.09)' : 'rgba(110, 160, 210, 0.2)';
    ctx.lineWidth = 46;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    jalurPath(sungai);
    ctx.stroke();
    ctx.restore();

    for (const j of jalur) {
      // progres "jalur menggambar diri" dengan easing keluar-kubik
      const p = Math.max(0, Math.min(1, (t - j.delay) / DURASI_TUMBUH));
      const ease = 1 - Math.pow(1 - p, 3);
      const grown = j.total * ease;
      if (grown <= 0) continue;

      ctx.save();
      ctx.strokeStyle = j.warna;
      ctx.lineWidth = 7;
      ctx.lineCap = 'round';
      ctx.globalAlpha = gelap ? 0.85 : 0.9;
      ctx.setLineDash([grown * 1.06, 1e6]);       // 1.06 ≈ kompensasi sudut membundar
      jalurPath(j.pts);
      ctx.stroke();
      ctx.setLineDash([]);

      // stasiun muncul (membesar) begitu ujung jalur melewatinya
      for (const d of j.stasiun) {
        const lahir = Math.max(0, Math.min(1, (grown - d) / 50));
        if (lahir <= 0) continue;
        const s = posDi(j, d);
        ctx.beginPath();
        ctx.arc(s.x, s.y, 5.5 * (1 - Math.pow(1 - lahir, 2)), 0, Math.PI * 2);
        ctx.fillStyle = gelap ? '#14181d' : '#f7f8fa';
        ctx.fill();
        ctx.lineWidth = 3;
        ctx.strokeStyle = gelap ? '#e8edf2' : '#2a333d';
        ctx.stroke();
      }

      // kereta kapsul berhenti-jalan setelah jalurnya jadi
      if (p >= 1) {
        for (const k of j.kereta) {
          if (!kurangGerak) langkahKereta(j, k, dt);
          const s = posDi(j, k.d);
          ctx.save();
          ctx.translate(s.x + fgX, s.y + fgY);
          ctx.rotate(s.ang);
          ctx.beginPath();
          ctx.roundRect(-14, -5.5, 28, 11, 5);
          ctx.fillStyle = j.warna;
          ctx.fill();
          ctx.lineWidth = 2.5;
          ctx.strokeStyle = gelap ? '#14181d' : '#f7f8fa';
          ctx.stroke();
          ctx.restore();
        }
      }
      ctx.restore();
    }

    // stasiun transit (persilangan) muncul setelah seluruh jaringan tergambar
    const alfaTransit = Math.max(0, Math.min(1, (t - (jalur.length * 0.13 + DURASI_TUMBUH)) / 0.6));
    if (alfaTransit > 0) {
      ctx.save();
      ctx.globalAlpha = alfaTransit;
      ctx.translate(fgX, fgY);
      for (const q of transit) {
        ctx.beginPath();
        ctx.arc(q.x, q.y, 8.5, 0, Math.PI * 2);
        ctx.fillStyle = gelap ? '#f2f5f8' : '#ffffff';
        ctx.fill();
        ctx.lineWidth = 3.5;
        ctx.strokeStyle = gelap ? '#0d1116' : '#2a333d';
        ctx.stroke();
      }
      ctx.restore();
    }

    ctx.restore();   // tutup wrap parallax
    requestAnimationFrame(frame);
  }

  if (!kurangGerak) window.addEventListener('pointermove', onMove);
  const onResize = () => { bangun(); mulai = performance.now(); };
  window.addEventListener('resize', onResize);
  bangun();
  requestAnimationFrame(frame);

  return () => {
    hidup = false;
    window.removeEventListener('resize', onResize);
    window.removeEventListener('pointermove', onMove);
  };
}
