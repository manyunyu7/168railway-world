// The Android map requests only live display data. Read the same per-trip boarding account and
// operating profile as the Studio inspector, including boarding its next run at a terminal.
import { readBoardHour, tripLoad, trainStands, gauge } from "./boarding";
import { vehicleCars } from "./vehicle";
import { world } from "./world";
import { profileS, type NetworkBuffers } from "../map/network";
import type { ClockClient } from "../workers/clockClient";

export function nativeTrainLoads(client:ClockClient,send:(message:object)=>void) {
  let net:NetworkBuffers|undefined;
  type Hour = { data:Float32Array; ends:Float32Array; board:ReturnType<typeof readBoardHour>; stands:Float32Array };
  const hours = new Map<number,Hour>();
  client.onState = state => {
    if (state.net) net = state.net;
    if (net && state.service) { net.phases = state.render.phases; net.meta = state.render.meta; }
  };
  client.onTrips = (epoch,_version,data,raw,ends,nexts) => {
    if (!raw || !ends) { hours.delete(epoch); return; }
    const board = readBoardHour(epoch,raw);
    if (board.trips === data.length/2) hours.set(epoch,{data,ends,board,stands:trainStands(board,data,nexts ?? null)});
  };
  let last = -1;
  return (time:number) => {
    const epoch = Math.floor(time/3600)*3600;
    if (last !== epoch) {
      last = epoch;
      client.setEpoch(epoch);
      for (const key of hours.keys()) if (key < epoch) hours.delete(key);
    }
    const hour = hours.get(epoch), game = world.peek();
    if (!net || !game || !hour) return;
    const { data,ends,board,stands } = hour;
    const rows:number[] = [];
    const flush = () => { if (rows.length) send({type:"trainLoads",epoch,rows:rows.splice(0)}); };
    for (let i=0;i<ends.length;i++) {
      const p=data[i*2],dep=data[i*2+1],elapsed=time-epoch-dep;
      if (elapsed < 0 || time >= epoch+ends[i] || board.offs[i*2]<0) continue;
      const st=game.lineStats[String(Math.floor(p/6))],run=Math.floor(p/3)%2;
      const stops=st?.stopS[run];
      if (!st || !stops || stops.length<2) continue;
      let aboard:number|null;
      if (elapsed >= board.offs[i*2+1]) {
        const k=4*i;
        const fraction=Math.max(0,Math.min(1,(time-epoch-stands[k])/Math.max(stands[k+1]-stands[k],1)));
        aboard=fraction*(stands[k+2]>=0?board.loads[stands[k+2]]:Math.max(0,stands[k+3]));
      } else {
        const distance=profileS(net,p,elapsed);
        let seg=0;
        while (seg+1<stops.length-1 && stops[seg+1]<=distance+1) seg++;
        aboard=tripLoad(board,i,run===0?seg:stops.length-2-seg);
      }
      if (aboard === null || !Number.isFinite(aboard)) continue;
      rows.push(p,Math.round(dep*100),Math.round(100*gauge(aboard,vehicleCars(st))));
      if (rows.length>=1536) flush();
    }
    flush();
  };
}
