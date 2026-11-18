// SPDX-License-Identifier: AGPL-3.0-only
// The same tile decoder, tile joins, vehicle snapping, directed search and simplifier as the web tool.
import { RoadGraph, EXTENT, MOTORWAY, TRUNK, gxOf, gyOf, latOf, lngOf, joinRoads, metresPerUnit, thin, thinFlagged } from '../../app/src/map/roadGraph';
import { decodeRoads, DRIVE_CLASSES, PEDESTRIAN } from '../../app/src/map/roadTiles';

const graph = new RoadGraph();
graph.oneWay = true;
export function addTile(x:number,y:number,bytes:ArrayBuffer){
  const streets = decodeRoads(bytes,(kind,_brunnel,_service,_access,subclass)=>
    kind === 'trunk'?TRUNK:DRIVE_CLASSES.has(kind)||(kind==='path'&&subclass===PEDESTRIAN)?1:kind==='motorway'?MOTORWAY:0);
  graph.addTile(x,y,joinRoads(streets,metresPerUnit((y+.5)*EXTENT)));
}

export function plan(input:string):string {
  const points:[number,number][]=JSON.parse(input);
  const out:number[][]=[];
  for(let i=1;i<points.length;i++){
    const [a,b]=[points[i-1],points[i]].map(([lon,lat])=>[gxOf(lon),gyOf(lat)]);
    const m=metresPerUnit((a[1]+b[1])/2);
    const from=graph.snap(a[0],a[1],100/m), to=graph.snap(b[0],b[1],100/m);
    if(!from||!to)return JSON.stringify({error:'Pilih titik maksimal 100 meter dari jalan yang dapat dilalui bus.'});
    const direct=Math.hypot(a[0]-b[0],a[1]-b[1])*m;
    const route=graph.path(from,to,Math.max(direct*3,direct+1000));
    if(!route)return JSON.stringify({error:'Tidak ada sambungan jalan sesuai arah kendaraan. Coba titik lain.'});
    const path=route.fast?thinFlagged(route.pts,route.fast,2.5/m):{pts:thin(route.pts,2.5/m),flags:[]};
    for(let k=i===1?0:2;k<path.pts.length;k+=2){
      out.push([lngOf(path.pts[k]),latOf(path.pts[k+1]),(k===0?0:2)|(path.flags[k/2-1]?4:0)|(k===0||k===path.pts.length-2?1:0)]);
    }
  }
  return JSON.stringify({points:out});
}
