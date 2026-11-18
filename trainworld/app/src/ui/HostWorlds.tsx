import {useRef, useState} from "preact/hooks";
import {exportForShare} from "../workers/clockClient";

const api = "/api/web/studio";
type Session = {authenticated:boolean; userId:string|null; csrfToken:string|null; loginUrl:string};
type World = {id:string; name:string; version:number; size:number; updatedAt:string};
type Props = {active?:boolean; openFile:(file:File)=>Promise<void>};
const hostFetch = (path:string, init:RequestInit = {}) => fetch(api + path, {
  ...init, credentials:"same-origin", cache:"no-store",
  headers:{Accept:"application/json", ...init.headers},
});
async function check(response:Response) {
  if (response.ok) return;
  const failure = await response.json().catch(()=>({}));
  if(response.status===401 || response.status===419) throw Error("Sesi berakhir. Masuk kembali ke 168Railway.");
  if(response.status===409) throw Error(failure.code==="account_changed" ? "Akun di browser berubah. Tutup lalu buka kembali panel ini." : "Akun atau versi simpanan berubah. Muat daftar terbaru atau simpan sebagai dunia baru.");
  if(response.status===413) throw Error("Ukuran berkas melewati batas akun.");
  throw Error("Permintaan gagal ("+response.status+"). Simpanan lokal tetap tersedia.");
}
export function HostWorlds({active=false,openFile}:Props) {
  const [session,setSession]=useState<Session|null>(null),[worlds,setWorlds]=useState<World[]>([]);
  const [name,setName]=useState(""),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
  const working=useRef(false);
  async function action(fn:()=>Promise<void>) {
    if(working.current)return;
    working.current=true;setBusy(true);setMessage("");
    try {await fn();} catch(err) {setMessage(err instanceof Error ? err.message : "Koneksi gagal.");}
    finally{working.current=false;setBusy(false);}
  }
  async function list(s:Session) {
    const r=await hostFetch("/worlds",{headers:{"X-Studio-User":s.userId!}});await check(r);
    setWorlds((await r.json()).worlds);
  }
  async function refresh(){
    setWorlds([]);setSession(null);
    const r=await hostFetch("/session");await check(r);
    const s:Session=await r.json();setSession(s);if(s.authenticated)await list(s);
  }
  async function put(world?:World) {
    const s=session;if(!s?.authenticated || !s.userId || !s.csrfToken)return;
    if(world && !window.confirm("Ganti isi “"+world.name+"” versi "+world.version+" dengan dunia yang sedang dimainkan?"))return;
    const worldName=world?.name || name.trim();
    if(!worldName)throw Error("Isi nama simpanan terlebih dahulu.");
    const bytes=await exportForShare();if(!bytes)throw Error("Buka dunia lokal terlebih dahulu.");
    if(bytes.byteLength>8*1024*1024)throw Error("Dunia melebihi batas 8 MiB untuk simpanan akun.");
    const r=await hostFetch("/worlds/"+(world?.id||crypto.randomUUID()),{
      method:"PUT", headers:{"Content-Type":"application/octet-stream","X-Studio-User":s.userId,
        "X-CSRF-TOKEN":s.csrfToken,"X-World-Version":String(world?.version||0),"X-World-Name":encodeURIComponent(worldName)},
      body:new Blob([new Uint8Array(bytes)]),
    });
    await check(r);const saved=await r.json();setMessage("Tersimpan · versi "+saved.world.version);
    await list(s);
  }
  async function open(w:World){
    if(!session?.userId)return;
    const r=await hostFetch("/worlds/"+w.id,{headers:{"X-Studio-User":session.userId}});await check(r);
    const file=new File([await r.blob()],w.name+".save",{type:"application/octet-stream"});
    await openFile(file);
  }
  let login="/login";
  if(session?.loginUrl){const u=new URL(session.loginUrl,location.origin);if(u.origin===location.origin)login=u.pathname+u.search;}
  return <details class="host-worlds" onToggle={e=>{
    if(e.currentTarget.open)void action(refresh);
  }}><summary>Simpanan akun 168Railway</summary>
    <div class="host-cloud-body"><p aria-live="polite">{message || (busy?"Memuat…":session?.authenticated?"Akun 168Railway · "+session.userId:"Simpan di akun untuk membuka dunia pada perangkat lain.")}</p>
    {session && !session.authenticated && <a class="btn" href={login}>Masuk ke 168Railway</a>}
    {session?.authenticated && <>
      {active && <div class="host-save-row"><input aria-label="Nama dunia di akun" maxLength={90} placeholder="Nama dunia" value={name} onInput={e=>setName(e.currentTarget.value)}/>
        <button class="btn" disabled={busy} onClick={()=>void action(()=>put())}>Simpan sebagai dunia baru</button></div>}
      <button class="btn" disabled={busy} onClick={()=>void action(refresh)}>Muat ulang daftar</button>
      {!worlds.length&&!busy&&<p>Belum ada simpanan di akun ini.</p>}
      <ul>{worlds.map(w=><li key={w.id}><strong>{w.name}</strong> · v{w.version}
        <button class="btn" disabled={busy} onClick={()=>void action(()=>open(w))}>Buka salinan lokal</button>
        {active&&<button class="btn" disabled={busy} onClick={()=>void action(()=>put(w))}>Perbarui</button>}</li>)}</ul>
    </>}</div>
  </details>;
}
