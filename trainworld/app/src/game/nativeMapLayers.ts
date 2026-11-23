// Read-only map figures for Android's Canvas. Snapshot rows use the world frame from the archive.
import { cellModes, cityCells, demandView } from "./demand";
import { activeCity, getWorldFrame, isCity } from "./cities";
import { complexes } from "./complexView";
import { NO_PROFIT, stretchMoney } from "./stretchMoney";
import { F_BLUEPRINT, speedAt, SPEED_LEVEL, type NetworkBuffers } from "../map/network";
import {toLngLat} from "../map/geo";
import { world } from "./world";

type Send = (value: object) => void;

export async function publishMapLayers(kind: string, zone: string | undefined, send: Send, net?: NetworkBuffers): Promise<boolean> {
  const frame = getWorldFrame();
  const rows = (name: string, data: number[], stride: number) => {
    const batch = stride * 128;
    for (let i = 0; i < data.length; i += batch)
      send({type:"overlayPart", section:name, rows:data.slice(i, i + batch)});
  };
  if (kind === "trips") {
    const city = zone && isCity(zone) ? zone : activeCity.peek();
    const [cells, modes] = await Promise.all([cityCells(city), cellModes("all",city)]);
    if (!cells || !modes) return false;
    const data: number[] = [];
    for (let i = 0; i < cells.n; i++) {
      const counts = [modes.homeRail[i], modes.homeWalk[i], modes.homeDrive[i], modes.homeNone[i],
        modes.workRail[i], modes.workWalk[i], modes.workDrive[i], modes.workNone[i]];
      if (counts.some(v => v > 0)) data.push(...frame.toLonLat(cells.xy[2*i], cells.xy[2*i+1]), ...counts.map(v => v || 0));
    }
    rows("cells", data, 10);
  } else {
    const w = world.peek(), dv = demandView.peek();
    if (!w || !net || (!dv && w.save.lines.some(l => w.lineStats[l.id]?.status === "running"))) return false;
    const circles = new Map<string,[number,number,number,number,number]>();
    const groups = complexes.peek().of;
    for (const s of w.save.stations) {
      if (!s.built) continue;
      const key = groups.has(s.id) ? "c"+groups.get(s.id) : s.id;
      const row = circles.get(key) ?? [0,0,0,0,0];
      const riders = dv?.stations.get(s.id);
      row[0] += s.x; row[1] += s.y; row[2]++; row[3] += riders?.boardings ?? 0;
      row[4] += Math.min(riders?.boardings ?? 0, riders?.transfers ?? 0);
      circles.set(key, row);
    }
    const stops: number[] = [];
    for (const [x,y,n,total,xfer] of circles.values()) if (total > 0) stops.push(...frame.toLonLat(x/n,y/n),total,xfer);
    rows("stops",stops,4);
    const rail: number[] = [], tr = net.track;
    for (let i=0; i<tr.count; i++) {
      if (tr.flags[i] & F_BLUEPRINT) continue;
      const xy = (j:number) => toLngLat(tr.seg[4*i+j],tr.seg[4*i+j+1],net.anchor);
      const train = tr.trainSpeed?.[i] ?? -1;
      const level = tr.level[i];
      rail.push(...xy(0),...xy(2),(Math.floor(level/256)+(level%256))/64-4,train >= 0 ? train : (tr.speed?.[i] ?? 160));
    }
    rows("rail",rail,6);
    const money = stretchMoney.peek(), edges: number[] = [], spans: number[] = [], road: number[] = [];
    for (const e of w.edges) {
      const ratio = money?.ratio[e.id];
      if (ratio !== undefined && ratio < NO_PROFIT) edges.push(e.id,ratio);
    }
    rows("edges",edges,2);
    for (const l of w.save.lines) {
      const st = w.lineStats[l.id], loads = dv?.lines.get(l.id)?.loads;
      if (!st || st.status !== "running") continue;
      const dist = st.stopS[0], n = dist.length-1, length = dist[n];
      if (!(length > 0)) continue;
      for (let k = 0; k < n; k++) {
        if (st.bus) {
          const parts = Math.max(1,Math.ceil((dist[k+1]-dist[k])/125));
          for (let j=0; j<parts; j++) {
            const f=(j+.5)/parts;
            let speed=-1;
            for (const run of [0,1]) {
              const m=((l.num*2+run)*3+SPEED_LEVEL)*4, first=net.meta[m], count=net.meta[m+1];
              const gap=run===0 ? k : n-1-k, g=run===0 ? f : 1-f, ds=st.stopS[run];
              if (count>0 && ds.length===n+1) speed=Math.max(speed,speedAt(net.phases,first,count,ds[gap]+g*(ds[gap+1]-ds[gap])));
            }
            const lengthHere=dist[k+1]-dist[k];
            road.push(l.num,(dist[k]+j/parts*lengthHere)/length,(dist[k]+(j+1)/parts*lengthHere)/length,speed);
          }
        }
        let riders = 0;
        for (const period of loads ?? []) if (period.length === 2*n) riders += period[k] + period[2*n-1-k];
        const profit = money?.buses.get(l.num)?.ratio[k];
        spans.push(l.num, dist[k]/length, dist[k+1]/length, riders, profit !== undefined && profit < NO_PROFIT ? profit : -1);
      }
    }
    rows("spans",spans,5);
    rows("road",road,4);
  }
  send({type:"overlayEnd"});
  return true;
}
