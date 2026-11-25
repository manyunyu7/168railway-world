// SPDX-License-Identifier: AGPL-3.0-only
// The same tile decoder, tile joins, vehicle snapping, directed search and simplifier as the web tool.
import { RoadGraph, EXTENT, MOTORWAY, TRUNK, gxOf, gyOf, latOf, lngOf, joinRoads, metresPerUnit, thin, thinFlagged } from '../../app/src/map/roadGraph';
import { decodeRoads, DRIVE_CLASSES, PEDESTRIAN } from '../../app/src/map/roadTiles';

const graph = new RoadGraph();
graph.oneWay = true;
export function addTile(x:number,y:number,bytes:ArrayBuffer, rail=false){
  const streets = decodeRoads(bytes,(kind,_brunnel,_service,_access,subclass)=>
    rail ? (kind==='motorway'?MOTORWAY:kind==='trunk'?TRUNK:['primary','secondary','tertiary'].includes(kind)?1:0) :
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

// StreetSnap's tramLeg, for a candidate assembled from downloaded tiles rather than
// the map's live tile cache. A rail profile only loads arterial roads into this graph.
export function follow(input:string, rail:boolean, designSpeed:number):string {
  graph.oneWay=false;
  const points:[number,number][]=JSON.parse(input), out:number[]=[];
  let previous:[number,number]|null=null;
  for(let i=1;i<points.length;i++) {
    const [ax,ay]=previous ?? [gxOf(points[i-1][0]),gyOf(points[i-1][1])];
    const bx=gxOf(points[i][0]),by=gyOf(points[i][1]),m=metresPerUnit((ay+by)/2);
    function near(x:number,y:number){
      const result=[],seen=new Set<number>();
      for(const s of graph.snapsWithin(x,y,100/m,!rail)){
        const a=graph.ea[s.edge], b=graph.eb[s.edge], skip=seen.has(a)||seen.has(b);
        seen.add(a).add(b);
        if(!skip) result.push(s);
        if(result.length===12) break;
      }
      return result;
    }
    const a=near(ax,ay),b=near(bx,by);
    if(!a.length||!b.length)return JSON.stringify({error:'Pilih titik maksimal 100 meter dari jalan yang sesuai.'});
    const direct=Math.hypot(a[0].x-b[0].x,a[0].y-b[0].y)*m;
    const turn=rail?Math.min(600,Math.max(10,Math.max(0,designSpeed/3.6-12.2)**2)):200;
    let direction:[number,number]|null=null;
    if(out.length>=4){
      const dx=ax-gxOf(out[out.length-4]),dy=ay-gyOf(out[out.length-3]),len=Math.hypot(dx,dy);
      if(len>0)direction=[dx/len,dy/len];
    }
    const path=graph.turnPath(a,b,s=>3*s.d*m,Math.max(direct*3,direct+1000)+20*turn,
      turn,Math.PI/6,3*Math.PI/4,60,direction,11*Math.PI/12,rail);
    if(!path)return JSON.stringify({error:'Tidak ada jalan tersambung dengan radius belok yang sesuai.'});
    const pts=thin(path.pts,1/m);
    if(pts.length<4 || (previous && Math.hypot(pts[0]-previous[0],pts[1]-previous[1])*m>2))
      return JSON.stringify({error:'Sambungan jalan terputus di titik perantara.'});
    for(let j=i===1?0:2;j<pts.length;j+=2)out.push(lngOf(pts[j]),latOf(pts[j+1]));
    previous=[pts[pts.length-2],pts[pts.length-1]];
  }
  return JSON.stringify({line:out});
}
