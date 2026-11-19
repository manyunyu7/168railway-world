import type { WorldView } from "./types";

/** Read actual topology, rather than completing the guide after clicks or two isolated stations. */
export function firstTrainFacts(w: WorldView) {
  const stations = w.save.stations.filter(s => !s.off && !s.port);
  const edges = w.edges.filter(e => !e.off && !e.crossover);
  function components(built: boolean) {
    const parent = new Map<number, number>();
    const find = (n: number): number => {
      const p = parent.get(n);
      if (p === undefined) { parent.set(n, n); return n; }
      if (p !== n) parent.set(n, find(p));
      return parent.get(n)!;
    };
    for (const e of edges) if (!built || e.built) parent.set(find(e.a), find(e.b));
    const groups = new Map<number, Set<string>>();
    for (const s of stations) {
      if (built && !s.built) continue;
      // A station without any enabled rail connection does not count.
      if (!parent.has(s.num)) continue;
      const key = find(s.num), group = groups.get(key) ?? new Set<string>();
      group.add(s.id); groups.set(key, group);
    }
    return [...groups.values()];
  }
  const planned = components(false), built = components(true);
  const count = (groups: Set<string>[]) => Math.max(0, ...groups.map(g => g.size));
  const lines = w.save.lines.filter(l => !l.twinOf && !w.lineStats[l.id]?.bus);
  const connectedLine = lines.find(l => w.lineStats[l.id]?.status === "running" &&
    built.some(g => new Set(l.stops.filter(id => g.has(id))).size >= 2));
  return { track: edges.length > 0, connectedStations: count(planned),
    builtStations: count(built), line: lines.length > 0, connectedLine };
}
