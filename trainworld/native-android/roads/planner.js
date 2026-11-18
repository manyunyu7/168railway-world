var Roads = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
  var __publicField = (obj, key, value) => __defNormalProp(obj, typeof key !== "symbol" ? key + "" : key, value);

  // trainworld/native-android/roads/planner.ts
  var planner_exports = {};
  __export(planner_exports, {
    addTile: () => addTile,
    plan: () => plan
  });

  // trainworld/app/src/map/roadGraph.ts
  var EXTENT = 4096;
  var ZOOM = 14;
  var WORLD = EXTENT * 2 ** ZOOM;
  var EQUATOR_M = 40075016686e-3;
  var JOIN = 3;
  var HOP_M = 40;
  var HOP_MIN_M = 4;
  var PARALLEL_COS = 0.9;
  var MOTORWAY = 2;
  var TRUNK = 1.5;
  var MOTOR_EXTRA_M = 30;
  var lngOf = (gx) => gx / WORLD * 360 - 180;
  var latOf = (gy) => 360 / Math.PI * Math.atan(Math.exp(Math.PI * (1 - 2 * gy / WORLD))) - 90;
  var gxOf = (lng) => (lng + 180) / 360 * WORLD;
  var gyOf = (lat) => {
    const s = Math.sin(lat * Math.PI / 180);
    return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * WORLD;
  };
  var metresPerUnit = (gy) => EQUATOR_M / WORLD * Math.cos(latOf(gy) * Math.PI / 180);
  var RoadGraph = class _RoadGraph {
    constructor() {
      /** bumped whenever a tile is added: a search or snap made before is stale */
      __publicField(this, "version", 0);
      __publicField(this, "nodes", 0);
      __publicField(this, "gx", new Int32Array(1024));
      __publicField(this, "gy", new Int32Array(1024));
      __publicField(this, "head", new Int32Array(1024).fill(-1));
      __publicField(this, "edges", 0);
      __publicField(this, "ea", new Int32Array(1024));
      __publicField(this, "eb", new Int32Array(1024));
      /** ground metres */
      __publicField(this, "elen", new Float32Array(1024));
      /** 1 for a motorway (its line's weight 2 or more), 2 for a link (`LINK`), which a bus may drive and a click does not land on, 3 for a trunk road (`TRUNK`), else 0 */
      __publicField(this, "emotor", new Uint8Array(1024));
      /** 0 both ways, 1 from `ea` to `eb`, 2 from `eb` to `ea` (OpenMapTiles `oneway`). `path` keeps to it; `turnPath` does not. */
      __publicField(this, "eway", new Uint8Array(1024));
      /** whether `path` keeps to `eway`; off (the default), every street runs both ways, as before T-3480 */
      __publicField(this, "oneWay", false);
      __publicField(this, "arcNext", new Int32Array(2048));
      __publicField(this, "inner", /* @__PURE__ */ new Map());
      __publicField(this, "borderV", /* @__PURE__ */ new Map());
      __publicField(this, "borderH", /* @__PURE__ */ new Map());
      /** per tile (`tileKey`), its edges [first, end) */
      __publicField(this, "tiles", /* @__PURE__ */ new Map());
      __publicField(this, "tg", new Float64Array(0));
      __publicField(this, "tfrom", new Int32Array(0));
      __publicField(this, "tseen", new Int32Array(0));
      __publicField(this, "tdone", new Int32Array(0));
      __publicField(this, "tstamp", 0);
      // ---------------------------------------------------------------- search
      __publicField(this, "g", new Float64Array(0));
      __publicField(this, "from", new Int32Array(0));
      __publicField(this, "seen", new Int32Array(0));
      __publicField(this, "done", new Int32Array(0));
      __publicField(this, "stamp", 0);
      __publicField(this, "hf", new Float64Array(256));
      __publicField(this, "hn", new Int32Array(256));
      __publicField(this, "hs", 0);
      /** Remove the smallest; its key is left in `popF`. */
      __publicField(this, "popF", 0);
    }
    static tileKey(tx, ty) {
      return tx * 16384 + ty;
    }
    has(tx, ty) {
      return this.tiles.has(_RoadGraph.tileKey(tx, ty));
    }
    get tileCount() {
      return this.tiles.size;
    }
    clear() {
      this.nodes = 0;
      this.edges = 0;
      this.head.fill(-1);
      this.inner.clear();
      this.borderV.clear();
      this.borderH.clear();
      this.tiles.clear();
      this.version++;
    }
    growNodes() {
      const n = this.gx.length * 2;
      const grow = (a) => {
        const b = new a.constructor(n);
        b.set(a);
        return b;
      };
      this.gx = grow(this.gx);
      this.gy = grow(this.gy);
      const h = new Int32Array(n).fill(-1);
      h.set(this.head);
      this.head = h;
    }
    growEdges() {
      const n = this.ea.length * 2;
      const ea = new Int32Array(n), eb = new Int32Array(n), len = new Float32Array(n), next = new Int32Array(n * 2), mo = new Uint8Array(n), way = new Uint8Array(n);
      ea.set(this.ea);
      eb.set(this.eb);
      len.set(this.elen);
      next.set(this.arcNext);
      mo.set(this.emotor);
      way.set(this.eway);
      this.ea = ea;
      this.eb = eb;
      this.elen = len;
      this.arcNext = next;
      this.emotor = mo;
      this.eway = way;
    }
    newNode(x, y) {
      if (this.nodes === this.gx.length) this.growNodes();
      const i = this.nodes++;
      this.gx[i] = x;
      this.gy[i] = y;
      this.head[i] = -1;
      return i;
    }
    /** The node at a grid point; on a tile edge, the one within `JOIN` of it along that edge. */
    node(x, y, onV, onH) {
      if (!onV && !onH) {
        const k = x * WORLD + y;
        let i = this.inner.get(k);
        if (i === void 0) this.inner.set(k, i = this.newNode(x, y));
        return i;
      }
      const lists = [];
      if (onV) lists.push([this.borderV, x, y]);
      if (onH) lists.push([this.borderH, y, x]);
      let found = -1;
      for (const [m, line, pos] of lists) {
        let best = JOIN + 1;
        for (const [p, n] of m.get(line) ?? []) {
          const d = Math.abs(p - pos);
          if (d < best) best = d, found = n;
        }
        if (found >= 0) break;
      }
      if (found < 0) found = this.newNode(x, y);
      for (const [m, line, pos] of lists) {
        const l = m.get(line);
        if (l) l.push([pos, found]);
        else m.set(line, [[pos, found]]);
      }
      return found;
    }
    /** Whether a bus runs edge `e` at highway speed: a trunk road or a motorway (links are not). */
    fastEdge(e) {
      return this.emotor[e] === 3 || this.emotor[e] === 1;
    }
    /** Whether the shortest edge a bus may drive from `a` to `b` is a highway-speed road. */
    edgeTrunk(a, b) {
      let best = Infinity, trunk = false;
      for (let arc = this.head[a]; arc >= 0; arc = this.arcNext[arc]) {
        const e = arc >> 1, towardB = (arc & 1) === 0, m = towardB ? this.eb[e] : this.ea[e];
        if (m === b && this.drives(e, towardB) && this.elen[e] < best) best = this.elen[e], trunk = this.fastEdge(e);
      }
      return trunk;
    }
    /** A bus may run edge `e` from its `a` end toward `b` when `towardB`, else the other way. */
    drives(e, towardB) {
      if (!this.oneWay) return true;
      const w = this.eway[e];
      return w === 0 || w === 1 === towardB;
    }
    /** `way` is the line's OpenMapTiles `oneway`: 1 with a→b, -1 against it, else both ways. Both arcs stay
     * in the adjacency lists so a tram or rail search can still run either way. */
    addEdge(a, b, m, kind, way = 0) {
      if (a === b) return;
      if (this.edges === this.ea.length) this.growEdges();
      const e = this.edges++;
      this.ea[e] = a;
      this.eb[e] = b;
      this.elen[e] = m;
      this.emotor[e] = kind;
      this.eway[e] = way === 1 ? 1 : way === -1 ? 2 : 0;
      this.arcNext[2 * e] = this.head[a];
      this.head[a] = 2 * e;
      this.arcNext[2 * e + 1] = this.head[b];
      this.head[b] = 2 * e + 1;
    }
    /** Add tile (tx, ty)'s road lines (a tile already added is not added again). */
    addTile(tx, ty, rl) {
      const key = _RoadGraph.tileKey(tx, ty);
      if (this.tiles.has(key)) return;
      const first = this.edges;
      const ext = rl.extent, x0 = tx * EXTENT, y0 = ty * EXTENT;
      const scale = EXTENT / ext;
      const mpu = metresPerUnit(y0 + EXTENT / 2);
      const seg = [0, 0, 0, 0];
      rl.lines.forEach((line, li) => {
        const w = rl.weights?.[li] ?? 1, kind = w >= MOTORWAY ? 1 : w === LINK ? 2 : w === TRUNK ? 3 : 0;
        let pa = -1, pax = NaN, pay = NaN;
        for (let i = 2; i + 1 < line.length; i += 2) {
          if (!clipSegment(line[i - 2] * scale, line[i - 1] * scale, line[i] * scale, line[i + 1] * scale, seg)) continue;
          const [ax, ay, bx, by] = seg;
          const a = pa >= 0 && pax === ax && pay === ay ? pa : this.node(x0 + ax, y0 + ay, ax === 0 || ax === EXTENT, ay === 0 || ay === EXTENT);
          const b = this.node(x0 + bx, y0 + by, bx === 0 || bx === EXTENT, by === 0 || by === EXTENT);
          this.addEdge(a, b, Math.hypot(bx - ax, by - ay) * mpu, kind, rl.ways?.[li] ?? 0);
          pa = b;
          pax = bx;
          pay = by;
        }
      });
      this.tiles.set(key, [first, this.edges]);
      this.version++;
    }
    /** Every road within `maxUnits` of a grid point: the nearest point of each edge, nearest first;
     * with `street`, no motorway (a tram's, T-2927). */
    snapsWithin(x, y, maxUnits, street = false) {
      const out = [];
      const tx0 = Math.floor((x - maxUnits) / EXTENT), tx1 = Math.floor((x + maxUnits) / EXTENT);
      const ty0 = Math.floor((y - maxUnits) / EXTENT), ty1 = Math.floor((y + maxUnits) / EXTENT);
      for (let tx = tx0; tx <= tx1; tx++)
        for (let ty = ty0; ty <= ty1; ty++) {
          const r = this.tiles.get(_RoadGraph.tileKey(tx, ty));
          if (!r) continue;
          for (let e = r[0]; e < r[1]; e++) {
            if (this.emotor[e] === 2 || street && this.emotor[e] === 1) continue;
            const a = this.ea[e], b = this.eb[e];
            const ax = this.gx[a], ay = this.gy[a], dx = this.gx[b] - ax, dy = this.gy[b] - ay;
            const l2 = dx * dx + dy * dy;
            const t = l2 > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2)) : 0;
            const px = ax + t * dx, py = ay + t * dy, d = Math.hypot(px - x, py - y);
            if (d <= maxUnits) out.push({ edge: e, t, x: px, y: py, d });
          }
        }
      return out.sort((p, q) => this.snapCost(p) - this.snapCost(q));
    }
    /** How far a click is taken as being from a road, grid units: a motorway counts 3 times as far and
     * `MOTOR_EXTRA_M` more (T-2469), so a click by a street lands on the street, not the motorway over it. */
    snapCost(s) {
      return this.emotor[s.edge] === 1 ? 3 * s.d + MOTOR_EXTRA_M / metresPerUnit(s.y) : s.d;
    }
    /** The road a click lands on within `maxUnits` of a grid point (the least `snapCost`), or null. */
    snap(x, y, maxUnits, street = false) {
      return this.snapsWithin(x, y, maxUnits, street)[0] ?? null;
    }
    /** T-2927: the road a tram follows from one of the snapped points `starts` to one of `ends` (each
     * costing `pen` metres more: the nearest road can be a stub joined to nothing): shortest with
     * `turnM` metres more for each turn sharper than `minTurn` (radians) where two road segments meet,
     * never turning back more than `maxTurn` at a node or within `backM` metres of road, and no motorway (unless `motorways`, T-3129: rail).
     * A search over directed segments (each state is a segment and the way it is run), A* on the
     * straight distance to the first end; null if none is under `capM`. `turns` counts the turns paid
     * for; `from` and `to` are the ends taken. `startDir` (a unit grid vector), where the route carries on
     * from track: leaving a start against it is a turn there, so never back the way the track came (T-2997).
     * One-way streets are not kept to: a tram, and rail snapping to a road, follow the road's shape. `path` is the bus search that does. */
    turnPath(starts, ends, pen, capM, turnM, minTurn, maxTurn, backM, startDir = null, hardTurn = maxTurn, motorways = false) {
      if (!starts.length || !ends.length) return null;
      let direct = null;
      const cosBack = Math.cos(maxTurn);
      for (const s of starts)
        for (const d2 of ends) {
          if (s.edge !== d2.edge) continue;
          if (startDir) {
            const e = s.edge, u2 = [this.gx[this.eb[e]] - this.gx[this.ea[e]], this.gy[this.eb[e]] - this.gy[this.ea[e]]], l = Math.hypot(u2[0], u2[1]) || 1, sg = d2.t >= s.t ? 1 : -1;
            if (sg * (u2[0] * startDir[0] + u2[1] * startDir[1]) / l < cosBack) continue;
          }
          const m = Math.abs(d2.t - s.t) * this.elen[s.edge] + pen(s) + pen(d2);
          if (m < capM && (!direct || m < direct.m)) direct = { m, s, d: d2 };
        }
      const d = ends[0];
      const arcs = 2 * this.edges;
      if (this.tg.length < arcs) {
        const n = Math.max(arcs, this.tg.length * 2);
        this.tg = new Float64Array(n);
        this.tfrom = new Int32Array(n);
        this.tseen = new Int32Array(n);
        this.tdone = new Int32Array(n);
        this.tstamp = 0;
      }
      const stamp = ++this.tstamp;
      this.hs = 0;
      const mpu = metresPerUnit(d.y);
      const cosMin = Math.cos(minTurn), cosMax = Math.cos(maxTurn), cosHard = Math.cos(hardTurn);
      const head = (a2) => (a2 & 1) === 0 ? this.eb[a2 >> 1] : this.ea[a2 >> 1];
      const tail = (a2) => (a2 & 1) === 0 ? this.ea[a2 >> 1] : this.eb[a2 >> 1];
      const dir = (a2) => {
        const p = tail(a2), q = head(a2), dx = this.gx[q] - this.gx[p], dy = this.gy[q] - this.gy[p], l = Math.hypot(dx, dy) || 1;
        return [dx / l, dy / l];
      };
      let slack = 0;
      for (const c of ends) slack = Math.max(slack, Math.hypot(c.x - d.x, c.y - d.y));
      const h = (n) => Math.max(0, Math.hypot(this.gx[n] - d.x, this.gy[n] - d.y) - slack) * mpu;
      const turnCost = (u2, v2, strict = false) => {
        const c = u2[0] * v2[0] + u2[1] * v2[1];
        if (c < (strict ? cosMax : cosHard)) return Infinity;
        if (c < cosMax) return turnM * (1 + (Math.acos(Math.max(-1, Math.min(1, c))) - maxTurn) / (hardTurn - maxTurn));
        return c < cosMin ? turnM : 0;
      };
      const doubles = (a2, v2) => {
        let m = this.elen[a2 >> 1];
        for (let p = this.tfrom[a2]; p >= 0 && m < backM; p = this.tfrom[p]) {
          const u2 = dir(p);
          if (u2[0] * v2[0] + u2[1] * v2[1] < cosHard) return true;
          m += this.elen[p >> 1];
        }
        return false;
      };
      const open = (a2, g, from2) => {
        if (this.tdone[a2] === stamp || this.tseen[a2] === stamp && this.tg[a2] <= g) return;
        this.tseen[a2] = stamp;
        this.tg[a2] = g;
        this.tfrom[a2] = from2;
        this.push(g + h(head(a2)), a2);
      };
      starts.forEach((s, i) => {
        const l0 = this.elen[s.edge], p = pen(s);
        for (const [a2, m] of [[2 * s.edge, (1 - s.t) * l0], [2 * s.edge + 1, s.t * l0]]) {
          const tc = startDir ? turnCost(startDir, dir(a2), true) : 0;
          if (tc !== Infinity) open(a2, p + m + tc, -1 - i);
        }
      });
      const endsAt = /* @__PURE__ */ new Map();
      for (const c of ends) {
        const u2 = dir(2 * c.edge), l = this.elen[c.edge], p = pen(c);
        for (const [n, v2, rest] of [[this.ea[c.edge], u2, c.t * l + p], [this.eb[c.edge], [-u2[0], -u2[1]], (1 - c.t) * l + p]]) {
          const list = endsAt.get(n);
          if (list) list.push({ c, v: v2, rest });
          else endsAt.set(n, [{ c, v: v2, rest }]);
        }
      }
      let best = direct ? direct.m : capM, end = -1, to = d, states = 0;
      while (this.hs > 0) {
        const a2 = this.pop();
        if (this.popF >= best) break;
        if (this.tdone[a2] === stamp) continue;
        this.tdone[a2] = stamp;
        states++;
        const n = head(a2), g = this.tg[a2], u2 = dir(a2);
        for (const x of endsAt.get(n) ?? []) {
          if (a2 >> 1 === x.c.edge || doubles(a2, x.v)) continue;
          const tot = g + x.rest + turnCost(u2, x.v);
          if (tot < best) best = tot, end = a2, to = x.c, direct = null;
        }
        for (let arc = this.head[n]; arc >= 0; arc = this.arcNext[arc]) {
          const e = arc >> 1;
          if (e === a2 >> 1 || this.emotor[e] === 1 && !motorways) continue;
          const b = arc;
          const tc = turnCost(u2, dir(b));
          if (tc === Infinity || doubles(a2, dir(b))) continue;
          const ng = g + this.elen[e] + tc;
          if (ng + h(head(b)) >= best) continue;
          open(b, ng, a2);
        }
      }
      if (direct) return { pts: [direct.s.x, direct.s.y, direct.d.x, direct.d.y], m: Math.abs(direct.d.t - direct.s.t) * this.elen[direct.s.edge], from: direct.s, to: direct.d, turns: 0, states };
      if (end < 0) return null;
      const chain = [];
      let a = end;
      for (; a >= 0; a = this.tfrom[a]) chain.push(a);
      const from = starts[-1 - a];
      chain.reverse();
      const pts = [from.x, from.y];
      let turns = 0;
      for (let i = 0; i < chain.length; i++) {
        const n = head(chain[i]);
        pts.push(this.gx[n], this.gy[n]);
        if (i > 0) {
          const u2 = dir(chain[i - 1]), v2 = dir(chain[i]);
          if (u2[0] * v2[0] + u2[1] * v2[1] < cosMin) turns++;
        }
      }
      pts.push(to.x, to.y);
      const v = endsAt.get(head(end)).find((x) => x.c === to).v, u = dir(end);
      if (u[0] * v[0] + u[1] * v[1] < cosMin) turns++;
      return { pts, m: best - turns * turnM - pen(from) - pen(to), from, to, turns, states };
    }
    push(f, n) {
      if (this.hs === this.hf.length) {
        const f2 = new Float64Array(this.hs * 2), n2 = new Int32Array(this.hs * 2);
        f2.set(this.hf);
        n2.set(this.hn);
        this.hf = f2;
        this.hn = n2;
      }
      let i = this.hs++;
      while (i > 0) {
        const p = i - 1 >> 1;
        if (this.hf[p] <= f) break;
        this.hf[i] = this.hf[p];
        this.hn[i] = this.hn[p];
        i = p;
      }
      this.hf[i] = f;
      this.hn[i] = n;
    }
    pop() {
      const top = this.hn[0];
      this.popF = this.hf[0];
      const last = --this.hs;
      if (last > 0) {
        const f = this.hf[last], n = this.hn[last];
        let i = 0;
        for (; ; ) {
          let c = 2 * i + 1;
          if (c >= last) break;
          if (c + 1 < last && this.hf[c + 1] < this.hf[c]) c++;
          if (this.hf[c] >= f) break;
          this.hf[i] = this.hf[c];
          this.hn[i] = this.hn[c];
          i = c;
        }
        this.hf[i] = f;
        this.hn[i] = n;
      }
      return top;
    }
    /** The roads running beside a snapped point that its click could cross to: on each side, the nearest
     * point on a roughly parallel road 4 to `HOP_M` metres away (the other carriageway of a divided
     * road). `d` of each is the hop's length in grid units. */
    across(s) {
      const mpu = metresPerUnit(s.y), max = HOP_M / mpu, min = HOP_MIN_M / mpu;
      const a = this.ea[s.edge], b = this.eb[s.edge];
      let dx = this.gx[b] - this.gx[a], dy = this.gy[b] - this.gy[a];
      const l = Math.hypot(dx, dy);
      if (l === 0) return [];
      dx /= l;
      dy /= l;
      const best = [null, null];
      const tx0 = Math.floor((s.x - max) / EXTENT), tx1 = Math.floor((s.x + max) / EXTENT);
      const ty0 = Math.floor((s.y - max) / EXTENT), ty1 = Math.floor((s.y + max) / EXTENT);
      for (let tx = tx0; tx <= tx1; tx++)
        for (let ty = ty0; ty <= ty1; ty++) {
          const r = this.tiles.get(_RoadGraph.tileKey(tx, ty));
          if (!r) continue;
          for (let e = r[0]; e < r[1]; e++) {
            if (e === s.edge || this.emotor[e] === 2) continue;
            const p = this.ea[e], q = this.eb[e];
            const ex = this.gx[q] - this.gx[p], ey = this.gy[q] - this.gy[p], l2 = ex * ex + ey * ey;
            if (l2 === 0) continue;
            const el = Math.sqrt(l2);
            if (Math.abs((ex * dx + ey * dy) / el) < PARALLEL_COS) continue;
            const t = Math.max(0, Math.min(1, ((s.x - this.gx[p]) * ex + (s.y - this.gy[p]) * ey) / l2));
            const px = this.gx[p] + t * ex, py = this.gy[p] + t * ey, hx = px - s.x, hy = py - s.y, h = Math.hypot(hx, hy);
            if (h < min || h > max) continue;
            if (Math.abs((hx * dx + hy * dy) / h) > 0.5) continue;
            const side = hx * -dy + hy * dx > 0 ? 0 : 1;
            if (!best[side] || h < best[side].d) best[side] = { edge: e, t, x: px, y: py, d: h };
          }
        }
      return best.filter((x) => x !== null);
    }
    /** The shortest road between two snapped points, or null if none is under `capM` metres. A one-way
     * street is run only the way it goes. Either point may cross to the other carriageway of a divided
     * road first (`across`), the hop counted at its length, so a route that turns on a divided road does
     * not run to the next junction and back. `alts` are other places the path may end instead of `d`
     * (T-2467: other roads near the click), each costing `penM` metres more than its road; `froms` are
     * other places it may start instead of `s`, costed the same way, so a free start is searched in the
     * direction the bus will run rather than found backward and reversed. `to` is the end taken. On one
     * edge, with no `froms`, the stretch is returned even past `capM`, as before. */
    path(s, d, capM, alts = [], penM = () => 0, froms = []) {
      const mpu = metresPerUnit(d.y);
      const along = (e2, fromT, toT) => fromT === toT || this.drives(e2, toT >= fromT);
      if (froms.length === 0 && s.edge === d.edge && along(s.edge, s.t, d.t)) {
        return { pts: [s.x, s.y, d.x, d.y], m: Math.abs(d.t - s.t) * this.elen[s.edge], to: d, fast: [this.fastEdge(s.edge)] };
      }
      if (this.g.length < this.nodes) {
        const n = Math.max(this.nodes, this.g.length * 2);
        this.g = new Float64Array(n);
        this.from = new Int32Array(n);
        this.seen = new Int32Array(n);
        this.done = new Int32Array(n);
        this.stamp = 0;
      }
      const stamp = ++this.stamp;
      this.hs = 0;
      let slack = 2 * HOP_M / mpu;
      for (const c of alts) slack = Math.max(slack, 2 * HOP_M / mpu + Math.hypot(c.x - d.x, c.y - d.y));
      const h = (n) => Math.max(0, Math.hypot(this.gx[n] - d.x, this.gy[n] - d.y) - slack) * mpu;
      const hopM = (c) => c.d * mpu;
      const viaStart = /* @__PURE__ */ new Map();
      const start = (n, off, origin2) => {
        if (this.seen[n] === stamp && this.g[n] <= off) return;
        this.seen[n] = stamp;
        this.g[n] = off;
        this.from[n] = -1;
        viaStart.set(n, origin2);
        this.push(off + h(n), n);
      };
      const startFrom = (c, hop, via, snap, pen) => {
        const l = this.elen[c.edge], origin2 = { snap, via, pen };
        if (this.drives(c.edge, false)) start(this.ea[c.edge], pen + hop + c.t * l, origin2);
        if (this.drives(c.edge, true)) start(this.eb[c.edge], pen + hop + (1 - c.t) * l, origin2);
      };
      const begin = (c, pen, hop) => {
        startFrom(c, 0, null, c, pen);
        if (!hop) return [];
        const hopped = this.across(c);
        for (const h2 of hopped) startFrom(h2, hopM(h2), [h2.x, h2.y], c, pen);
        return hopped;
      };
      const sAcross = begin(s, 0, true);
      for (const c of froms) begin(c, penM(c), false);
      const ends = /* @__PURE__ */ new Map();
      const endAt = (c, hop, via, to = d, pen = 0) => {
        const l = this.elen[c.edge];
        const seats = [
          [this.ea[c.edge], pen + hop + c.t * l, true],
          [this.eb[c.edge], pen + hop + (1 - c.t) * l, false]
        ];
        for (const [n, off, towardB] of seats) {
          if (!this.drives(c.edge, towardB)) continue;
          const had = ends.get(n);
          if (!had || off < had.off) ends.set(n, { off, via, to, pen });
        }
      };
      endAt(d, 0, null);
      const dAcross = this.across(d);
      for (const c of dAcross) endAt(c, hopM(c), [c.x, c.y]);
      let best = capM, end = -1;
      let direct = null, directTo = d, directPen = 0;
      const noteDirect = (pts2, m, to, pen) => {
        if (m < best) best = m, direct = pts2, directTo = to, directPen = pen, end = -1;
      };
      for (const c of sAcross) {
        if (c.edge !== d.edge || !along(d.edge, c.t, d.t)) continue;
        noteDirect([s.x, s.y, c.x, c.y, d.x, d.y], hopM(c) + Math.abs(c.t - d.t) * this.elen[d.edge], d, 0);
      }
      for (const c of dAcross) {
        if (c.edge !== s.edge || !along(s.edge, s.t, c.t)) continue;
        noteDirect([s.x, s.y, c.x, c.y, d.x, d.y], hopM(c) + Math.abs(c.t - s.t) * this.elen[s.edge], d, 0);
      }
      for (const c of alts) {
        const pen = penM(c);
        if (c.edge === s.edge && along(s.edge, s.t, c.t)) noteDirect([s.x, s.y, c.x, c.y], pen + Math.abs(c.t - s.t) * this.elen[s.edge], c, pen);
        else endAt(c, 0, null, c, pen);
      }
      for (const c of froms) {
        const pen = penM(c);
        if (c.edge === d.edge && along(c.edge, c.t, d.t)) noteDirect([c.x, c.y, d.x, d.y], pen + Math.abs(d.t - c.t) * this.elen[c.edge], d, pen);
      }
      while (this.hs > 0) {
        const n = this.pop();
        if (this.popF >= best) break;
        if (this.done[n] === stamp) continue;
        this.done[n] = stamp;
        const gn = this.g[n];
        const te = ends.get(n);
        if (te && gn + te.off < best) best = gn + te.off, end = n, direct = null, directTo = d, directPen = 0;
        for (let a = this.head[n]; a >= 0; a = this.arcNext[a]) {
          const e2 = a >> 1, towardB = (a & 1) === 0;
          if (!this.drives(e2, towardB)) continue;
          const m = towardB ? this.eb[e2] : this.ea[e2];
          const ng = gn + this.elen[e2];
          if (this.done[m] === stamp || this.seen[m] === stamp && this.g[m] <= ng) continue;
          const f = ng + h(m);
          if (f >= best) continue;
          this.seen[m] = stamp;
          this.g[m] = ng;
          this.from[m] = n;
          this.push(f, m);
        }
      }
      if (direct) return { pts: direct, m: best - directPen, to: directTo };
      if (end < 0) return null;
      const chain = [];
      for (let n = end; n >= 0; n = this.from[n]) chain.push(n);
      const origin = viaStart.get(chain[chain.length - 1]);
      if (!origin) return null;
      const pts = [origin.snap.x, origin.snap.y], fast = [];
      if (origin.via) pts.push(origin.via[0], origin.via[1]), fast.push(false);
      for (let i = chain.length - 1; i >= 0; i--) {
        pts.push(this.gx[chain[i]], this.gy[chain[i]]);
        fast.push(i === chain.length - 1 ? this.fastEdge(origin.snap.edge) : this.edgeTrunk(chain[i + 1], chain[i]));
      }
      const e = ends.get(end);
      if (e.via) pts.push(e.via[0], e.via[1]), fast.push(false);
      pts.push(e.to.x, e.to.y);
      fast.push(this.fastEdge(e.to.edge));
      return { pts, m: best - e.pen - origin.pen, to: e.to, fast };
    }
  };
  function clipSegment(x0, y0, x1, y1, out) {
    const inside = x0 >= 0 && x0 <= EXTENT && y0 >= 0 && y0 <= EXTENT && x1 >= 0 && x1 <= EXTENT && y1 >= 0 && y1 <= EXTENT;
    if (inside) {
      out[0] = Math.round(x0);
      out[1] = Math.round(y0);
      out[2] = Math.round(x1);
      out[3] = Math.round(y1);
      return out[0] !== out[2] || out[1] !== out[3];
    }
    const dx = x1 - x0, dy = y1 - y0;
    let t0 = 0, t1 = 1;
    const test = (p, q) => {
      if (p === 0) return q >= 0;
      const r = q / p;
      if (p < 0) {
        if (r > t1) return false;
        if (r > t0) t0 = r;
      } else {
        if (r < t0) return false;
        if (r < t1) t1 = r;
      }
      return true;
    };
    if (!(test(-dx, x0) && test(dx, EXTENT - x0) && test(-dy, y0) && test(dy, EXTENT - y0))) return false;
    const px = (t) => Math.min(EXTENT, Math.max(0, Math.round(x0 + t * dx)));
    const py = (t) => Math.min(EXTENT, Math.max(0, Math.round(y0 + t * dy)));
    out[0] = px(t0);
    out[1] = py(t0);
    out[2] = px(t1);
    out[3] = py(t1);
    return out[0] !== out[2] || out[1] !== out[3];
  }
  var JUNCTION_GAP_M = 3;
  var NEAR_UNITS = 2;
  var ALONG_COS = 0.8;
  var JOIN_CELL = 128;
  var LINK = 0.5;
  var LINK_MAX_M = 60;
  function joinRoads(rl, mpu) {
    const JUNCTION_GAP = JUNCTION_GAP_M / mpu;
    const lines = [], src = [];
    const scale = EXTENT / rl.extent, seg = [0, 0, 0, 0];
    rl.lines.forEach((line, li) => {
      let cur = null;
      const flush = () => {
        if (cur) lines.push(Int32Array.from(cur)), src.push(li);
      };
      for (let i = 2; i + 1 < line.length; i += 2) {
        if (!clipSegment(line[i - 2] * scale, line[i - 1] * scale, line[i] * scale, line[i + 1] * scale, seg)) continue;
        if (cur && cur[cur.length - 2] === seg[0] && cur[cur.length - 1] === seg[1]) cur.push(seg[2], seg[3]);
        else flush(), cur = [...seg];
      }
      flush();
    });
    const level = (l) => rl.levels?.[src[l]] ?? 0;
    const motor = (l) => (rl.weights?.[src[l]] ?? 1) >= MOTORWAY;
    const sLine = [], sAt = [];
    const cells = /* @__PURE__ */ new Map();
    const cellKey = (cx, cy) => (cx + 8) * 1024 + cy + 8;
    lines.forEach((l, li) => {
      for (let i = 0; i + 3 < l.length; i += 2) {
        const s = sLine.length;
        sLine.push(li);
        sAt.push(i);
        const cx1 = Math.floor((Math.max(l[i], l[i + 2]) + JUNCTION_GAP) / JOIN_CELL), cy1 = Math.floor((Math.max(l[i + 1], l[i + 3]) + JUNCTION_GAP) / JOIN_CELL);
        for (let cx = Math.floor((Math.min(l[i], l[i + 2]) - JUNCTION_GAP) / JOIN_CELL); cx <= cx1; cx++)
          for (let cy = Math.floor((Math.min(l[i + 1], l[i + 3]) - JUNCTION_GAP) / JOIN_CELL); cy <= cy1; cy++) {
            const k = cellKey(cx, cy), c = cells.get(k);
            if (c) c.push(s);
            else cells.set(k, [s]);
          }
      }
    });
    const isLink = (l) => rl.weights?.[src[l]] === LINK;
    const gap2 = JUNCTION_GAP * JUNCTION_GAP;
    const onStreet = (li, x, y) => {
      if (x === 0 || y === 0 || x === EXTENT || y === EXTENT) return true;
      for (const s of cells.get(cellKey(Math.floor(x / JOIN_CELL), Math.floor(y / JOIN_CELL))) ?? []) {
        const lj = sLine[s];
        if (lj === li || isLink(lj) || motor(lj) || level(lj) !== 0) continue;
        const m = lines[lj], j = sAt[s], ax = m[j], ay = m[j + 1], dx = m[j + 2] - ax, dy = m[j + 3] - ay;
        const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
        const ex = ax + t * dx - x, ey = ay + t * dy - y;
        if (ex * ex + ey * ey <= gap2) return true;
      }
      return false;
    };
    const dropped = /* @__PURE__ */ new Set();
    lines.forEach((l, li) => {
      if (!isLink(li)) return;
      let units = 0;
      for (let i = 0; i + 3 < l.length; i += 2) units += Math.hypot(l[i + 2] - l[i], l[i + 3] - l[i + 1]);
      if (units * mpu > LINK_MAX_M || !onStreet(li, l[0], l[1]) || !onStreet(li, l[l.length - 2], l[l.length - 1])) dropped.add(li);
    });
    const adds = /* @__PURE__ */ new Map();
    const add = (s, x, y) => {
      const l = lines[sLine[s]], i = sAt[s], ax = l[i], ay = l[i + 1], bx = l[i + 2], by = l[i + 3];
      if (x === ax && y === ay || x === bx && y === by) return;
      const dx = bx - ax, dy = by - ay, t = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy);
      const a = adds.get(s);
      if (a) a.push(t, x, y);
      else adds.set(s, [t, x, y]);
    };
    lines.forEach((l, li) => {
      if (dropped.has(li)) return;
      for (let i = 0; i < l.length; i += 2) {
        const x = l[i], y = l[i + 1];
        if (x === 0 || y === 0 || x === EXTENT || y === EXTENT) continue;
        const end = i === 0 || i === l.length - 2;
        if (!end && (level(li) !== 0 || motor(li))) continue;
        for (const s of cells.get(cellKey(Math.floor(x / JOIN_CELL), Math.floor(y / JOIN_CELL))) ?? []) {
          const lj = sLine[s];
          if (lj === li || dropped.has(lj) || motor(lj) && !motor(li)) continue;
          if (level(lj) !== level(li) && !(end && !motor(li) && level(li) !== 0 && level(lj) === 0)) continue;
          const m = lines[lj], j = sAt[s], ax = m[j], ay = m[j + 1], dx = m[j + 2] - ax, dy = m[j + 3] - ay;
          const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
          const ex = ax + t * dx - x, ey = ay + t * dy - y;
          const d2 = ex * ex + ey * ey;
          if (d2 <= NEAR_UNITS * NEAR_UNITS) add(s, x, y);
          else if (end && d2 <= gap2) {
            const bi = i === 0 ? 2 : l.length - 4, hx = x - l[bi], hy = y - l[bi + 1];
            if (Math.abs(hx * dx + hy * dy) <= ALONG_COS * Math.hypot(hx, hy) * Math.hypot(dx, dy)) add(s, x, y);
          }
        }
      }
    });
    for (const [k, c] of cells) {
      for (let p = 0; p < c.length; p++) {
        const lp = sLine[c[p]];
        if (level(lp) !== 0 || motor(lp) || dropped.has(lp)) continue;
        const A = lines[lp], i = sAt[c[p]], a0 = A[i], a1 = A[i + 1], ax = A[i + 2] - a0, ay = A[i + 3] - a1;
        for (let q = p + 1; q < c.length; q++) {
          const lq = sLine[c[q]];
          if (lq === lp || level(lq) !== 0 || motor(lq) || dropped.has(lq)) continue;
          const B = lines[lq], j = sAt[c[q]], b0 = B[j], b1 = B[j + 1], bx = B[j + 2] - b0, by = B[j + 3] - b1;
          const det = ax * by - ay * bx;
          if (det === 0) continue;
          const u = ((b0 - a0) * by - (b1 - a1) * bx) / det, v = ((b0 - a0) * ay - (b1 - a1) * ax) / det;
          if (u <= 0 || u >= 1 || v <= 0 || v >= 1) continue;
          const px = Math.round(a0 + u * ax), py = Math.round(a1 + u * ay);
          if (cellKey(Math.floor(px / JOIN_CELL), Math.floor(py / JOIN_CELL)) !== k) continue;
          add(c[p], px, py);
          add(c[q], px, py);
        }
      }
    }
    const out = lines.slice();
    const byLine = /* @__PURE__ */ new Map();
    for (const s of adds.keys()) {
      const b = byLine.get(sLine[s]);
      if (b) b.push(s);
      else byLine.set(sLine[s], [s]);
    }
    for (const [li, segs] of byLine) {
      segs.sort((p, q) => sAt[p] - sAt[q]);
      const l = lines[li], pts = [];
      let copied = 0;
      for (const s of segs) {
        const upto = sAt[s] + 2;
        for (let i = copied; i < upto; i++) pts.push(l[i]);
        copied = upto;
        const a = adds.get(s), order = Array.from({ length: a.length / 3 }, (_, n) => n).sort((p, q) => a[3 * p] - a[3 * q]);
        for (const n of order) {
          const x = a[3 * n + 1], y = a[3 * n + 2];
          if (pts[pts.length - 2] !== x || pts[pts.length - 1] !== y) pts.push(x, y);
        }
      }
      for (let i = copied; i < l.length; i++) pts.push(l[i]);
      out[li] = Int32Array.from(pts);
    }
    const kept = out.map((_, li) => li).filter((li) => !dropped.has(li));
    return {
      extent: EXTENT,
      lines: kept.map((li) => out[li]),
      weights: rl.weights && kept.map((li) => rl.weights[src[li]]),
      levels: rl.levels && kept.map((li) => rl.levels[src[li]]),
      ways: rl.ways && kept.map((li) => rl.ways[src[li]])
    };
  }
  function thin(pts, tol) {
    const n = pts.length / 2;
    if (n <= 2) return pts;
    const keep = thinKeep(pts, tol), out = [];
    for (let i = 0; i < n; i++) if (keep[i]) out.push(pts[2 * i], pts[2 * i + 1]);
    return out;
  }
  function thinFlagged(pts, flags, tol) {
    const n = pts.length / 2;
    if (n <= 2) return { pts, flags };
    const keep = thinKeep(pts, tol), out = [pts[0], pts[1]], got = [];
    let on = 0, all = 0;
    for (let i = 1; i < n; i++) {
      const d = Math.hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1]);
      all += d;
      if (flags[i - 1]) on += d;
      if (!keep[i]) continue;
      out.push(pts[2 * i], pts[2 * i + 1]);
      got.push(on > all / 2);
      on = all = 0;
    }
    return { pts: out, flags: got };
  }
  function thinKeep(pts, tol) {
    const n = pts.length / 2;
    const keep = new Uint8Array(n);
    keep[0] = keep[n - 1] = 1;
    const stack = [[0, n - 1]];
    while (stack.length) {
      const [a, b] = stack.pop();
      const ax = pts[2 * a], ay = pts[2 * a + 1], dx = pts[2 * b] - ax, dy = pts[2 * b + 1] - ay, l2 = dx * dx + dy * dy;
      let worst = 0, at = -1;
      for (let i = a + 1; i < b; i++) {
        const px = pts[2 * i] - ax, py = pts[2 * i + 1] - ay;
        const t = l2 > 0 ? Math.max(0, Math.min(1, (px * dx + py * dy) / l2)) : 0;
        const d = Math.hypot(px - t * dx, py - t * dy);
        if (d > worst) worst = d, at = i;
      }
      if (worst > tol && at >= 0) {
        keep[at] = 1;
        stack.push([a, at], [at, b]);
      }
    }
    return keep;
  }

  // trainworld/app/src/map/roadTiles.ts
  var BUS_CLASSES = /* @__PURE__ */ new Set(["trunk", "primary", "secondary", "tertiary", "minor", "busway"]);
  var DRIVE_CLASSES = /* @__PURE__ */ new Set([...BUS_CLASSES, "service", "track", "bus_guideway"]);
  var PEDESTRIAN = "pedestrian";
  var UTF8 = new TextDecoder();
  var Reader = class {
    constructor(b, end = b.length) {
      __publicField(this, "pos", 0);
      __publicField(this, "b");
      __publicField(this, "end");
      this.b = b;
      this.end = end;
    }
    varint() {
      let r = 0, mul = 1, byte;
      do {
        byte = this.b[this.pos++];
        r += (byte & 127) * mul;
        mul *= 128;
      } while (byte & 128);
      return r;
    }
    /** a length-delimited field's end */
    len() {
      const n = this.varint();
      return this.pos + n;
    }
    skip(wire) {
      if (wire === 0) this.varint();
      else if (wire === 1) this.pos += 8;
      else if (wire === 2) this.pos = this.len();
      else if (wire === 5) this.pos += 4;
      else throw new Error("bad wire type");
    }
    string(end) {
      const s = UTF8.decode(this.b.subarray(this.pos, end));
      this.pos = end;
      return s;
    }
  };
  function valueString(r, end) {
    let out = null;
    while (r.pos < end) {
      const tag = r.varint(), f = tag >> 3, w = tag & 7;
      if (f === 1 && w === 2) out = r.string(r.len());
      else if (w === 0) {
        const n = r.varint();
        out = String(f === 6 ? (n & 1) === 1 ? -(n + 1) / 2 : n / 2 : n);
      } else r.skip(w);
    }
    return out;
  }
  function layerName(r, end) {
    while (r.pos < end) {
      const tag = r.varint(), f = tag >> 3, w = tag & 7;
      if (f === 1 && w === 2) return r.string(r.len());
      r.skip(w);
    }
    return "";
  }
  function decodeRoads(buf, keep = (c) => BUS_CLASSES.has(c)) {
    const b = new Uint8Array(buf);
    const t = new Reader(b);
    while (t.pos < t.end) {
      const tag = t.varint(), f = tag >> 3, w = tag & 7;
      if (f !== 3 || w !== 2) {
        t.skip(w);
        continue;
      }
      const end = t.len();
      const r = new Reader(b, end);
      r.pos = t.pos;
      if (layerName(r, end) !== "transportation") {
        t.pos = end;
        continue;
      }
      r.pos = t.pos;
      let name = "", extent = 4096;
      const feats = [], keys = [], values = [];
      while (r.pos < end) {
        const ft = r.varint(), ff = ft >> 3, fw = ft & 7;
        if (ff === 1 && fw === 2) name = r.string(r.len());
        else if (ff === 2 && fw === 2) {
          const e = r.len();
          feats.push([r.pos, e]);
          r.pos = e;
        } else if (ff === 3 && fw === 2) keys.push(r.string(r.len()));
        else if (ff === 4 && fw === 2) {
          const e = r.len();
          values.push(valueString(r, e));
          r.pos = e;
        } else if (ff === 5 && fw === 0) extent = r.varint();
        else r.skip(fw);
      }
      t.pos = end;
      if (name !== "transportation") continue;
      const classKey = keys.indexOf("class"), brunnelKey = keys.indexOf("brunnel"), layerKey = keys.indexOf("layer"), serviceKey = keys.indexOf("service"), accessKey = keys.indexOf("access"), subclassKey = keys.indexOf("subclass"), onewayKey = keys.indexOf("oneway");
      const lines = [], weights = [], levels = [], ways = [];
      for (const [s, e] of feats) {
        const fr = new Reader(b, e);
        fr.pos = s;
        let type = 0, geomStart = -1, geomEnd = -1, cls = null, brunnel = null, layer = 0;
        let service = null, access = null, subclass = null, oneway = 0;
        while (fr.pos < e) {
          const ft = fr.varint(), ff = ft >> 3, fw = ft & 7;
          if (ff === 2 && fw === 2) {
            const te = fr.len();
            while (fr.pos < te) {
              const k = fr.varint(), v = fr.varint();
              if (k === classKey) cls = values[v] ?? null;
              else if (k === brunnelKey) brunnel = values[v] ?? null;
              else if (k === layerKey) layer = Number(values[v]) || 0;
              else if (k === serviceKey) service = values[v] ?? null;
              else if (k === accessKey) access = values[v] ?? null;
              else if (k === subclassKey) subclass = values[v] ?? null;
              else if (k === onewayKey) oneway = values[v] === "1" ? 1 : values[v] === "-1" ? -1 : 0;
            }
          } else if (ff === 3 && fw === 0) type = fr.varint();
          else if (ff === 4 && fw === 2) {
            geomEnd = fr.len();
            geomStart = fr.pos;
            fr.pos = geomEnd;
          } else fr.skip(fw);
        }
        if (type !== 2 || geomStart < 0 || cls === null) continue;
        const kept = keep(cls, brunnel, service, access, subclass);
        const weight = kept === true ? 1 : kept === false ? 0 : kept;
        if (!(weight > 0)) continue;
        const level = (brunnel === "bridge" ? 1e3 : brunnel === "tunnel" ? 2e3 : 0) + layer;
        const g = new Reader(b, geomEnd);
        g.pos = geomStart;
        let x = 0, y = 0, cur = null;
        const push = () => {
          if (cur && cur.length >= 4) lines.push(Int32Array.from(cur)), weights.push(weight), levels.push(level), ways.push(oneway);
        };
        while (g.pos < geomEnd) {
          const cmd = g.varint(), id = cmd & 7;
          let count = cmd >> 3;
          if (id === 7) continue;
          while (count--) {
            const dx = g.varint(), dy = g.varint();
            x += dx >> 1 ^ -(dx & 1);
            y += dy >> 1 ^ -(dy & 1);
            if (id === 1) {
              push();
              cur = [x, y];
            } else cur?.push(x, y);
          }
        }
        push();
      }
      return { extent, lines, weights, levels, ways };
    }
    return { extent: 4096, lines: [], weights: [] };
  }

  // trainworld/native-android/roads/planner.ts
  var graph = new RoadGraph();
  graph.oneWay = true;
  function addTile(x, y, bytes) {
    const streets = decodeRoads(bytes, (kind, _brunnel, _service, _access, subclass) => kind === "trunk" ? TRUNK : DRIVE_CLASSES.has(kind) || kind === "path" && subclass === PEDESTRIAN ? 1 : kind === "motorway" ? MOTORWAY : 0);
    graph.addTile(x, y, joinRoads(streets, metresPerUnit((y + 0.5) * EXTENT)));
  }
  function plan(input) {
    const points = JSON.parse(input);
    const out = [];
    for (let i = 1; i < points.length; i++) {
      const [a, b] = [points[i - 1], points[i]].map(([lon, lat]) => [gxOf(lon), gyOf(lat)]);
      const m = metresPerUnit((a[1] + b[1]) / 2);
      const from = graph.snap(a[0], a[1], 100 / m), to = graph.snap(b[0], b[1], 100 / m);
      if (!from || !to) return JSON.stringify({ error: "Pilih titik maksimal 100 meter dari jalan yang dapat dilalui bus." });
      const direct = Math.hypot(a[0] - b[0], a[1] - b[1]) * m;
      const route = graph.path(from, to, Math.max(direct * 3, direct + 1e3));
      if (!route) return JSON.stringify({ error: "Tidak ada sambungan jalan sesuai arah kendaraan. Coba titik lain." });
      const path = route.fast ? thinFlagged(route.pts, route.fast, 2.5 / m) : { pts: thin(route.pts, 2.5 / m), flags: [] };
      for (let k = i === 1 ? 0 : 2; k < path.pts.length; k += 2) {
        out.push([lngOf(path.pts[k]), latOf(path.pts[k + 1]), (k === 0 ? 0 : 2) | (path.flags[k / 2 - 1] ? 4 : 0) | (k === 0 || k === path.pts.length - 2 ? 1 : 0)]);
      }
    }
    return JSON.stringify({ points: out });
  }
  return __toCommonJS(planner_exports);
})();
