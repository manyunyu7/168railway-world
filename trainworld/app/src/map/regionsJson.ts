// regions.json (about 1.5 MB, the outlines of noritetsu's regions), fetched once for the page and the
// real-route worker, which gets the outlines in a message (T-2575).
export interface RegionsJson {
  /** the base that served it, which serves the regions' pmtiles too */
  base: string;
  regions: Record<string, { parts: [number, number][][] }>;
}

// The pinned provider release omits the Noritetsu overlay. Its separate PMTiles
// and rights are unavailable on this host, so the overlay stays unavailable.
export function regionsJson(): Promise<RegionsJson | null> {
  return Promise.resolve(null);
}
