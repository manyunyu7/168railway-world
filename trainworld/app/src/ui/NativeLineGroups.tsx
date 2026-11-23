// Android touch has no mouse drag-and-drop. Apply the same lineGroup edit as dragging rows.
import { useState } from "preact/hooks";
import { edit } from "../game/world";
import { lineGroups, groupName } from "../game/lineGroups";
import type { LineInput } from "../game/types";

export function NativeLineGroups({ lines }: { lines: LineInput[] }) {
  const [open,setOpen] = useState(false);
  const [ids,select] = useState<number[]>([]);
  const [target,setTarget] = useState("new");
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState("");
  if (lines.length < 2) return null;
  const apply = async () => {
    setBusy(true);
    try {
      const result = await edit({ op: "lineGroup", lines: ids, group: target === "new" ? "new" : Number(target) });
      if (!result.ok) throw Error(result.issues.join(" · ") || "Perubahan grup ditolak.");
      select([]); setOpen(false); setError("");
    } catch (e) { setError(String(e)); } finally { setBusy(false); }
  };
  return <div style={{padding:"3px 8px"}}>
    <button class="btn text" aria-expanded={open} onClick={() => setOpen(!open)}>Kelola grup lini</button>
    {open && <div class="native-line-groups">
      <label>Grup <select value={target} onChange={e => setTarget(e.currentTarget.value)}>
        <option value="new">Buat grup baru</option><option value="0">Keluarkan dari grup</option>
        {[...lineGroups(lines)].map(([id,members]) => <option key={id} value={id}>{groupName(members)}</option>)}
      </select></label>
      <div style={{maxHeight:"28vh",overflowY:"auto"}}>
        {lines.map(l => <label key={l.id} style={{display:"flex",gap:"8px",alignItems:"center",minHeight:"40px"}}>
          <input type="checkbox" checked={ids.includes(Number(l.id))} onChange={e =>
            select(e.currentTarget.checked ? [...ids,Number(l.id)] : ids.filter(x=>x!==Number(l.id)))} />
          <i style={{background:l.colour,width:"12px",height:"12px",borderRadius:"50%"}} />{l.name}
        </label>)}
      </div>
      <button class="btn text" disabled={busy || ids.length < (target === "new" ? 2 : 1)} onClick={() => void apply()}>Terapkan grup</button>
      {error && <p role="status">{error}</p>}
    </div>}
  </div>;
}
