// Original tool pictograms for the build palette, drawn on a shared 36-unit grid.
export function BuildGlyph({ tool }: { tool: string }) {
  const art = {
    track: <g stroke="currentColor" stroke-width="3.4" stroke-linecap="round"><path d="M12 5 6 31M24 5l6 26M11 10h14M9 17h18M7 25h22"/></g>,
    station: <><path fill="currentColor" d="M3 15 18 5l15 10v3H3zm4 5h22v10H7z"/><path fill="var(--glyph-light)" d="M11 21h4v6h-4zm9 0h5v9h-5z"/><circle cx="18" cy="13" r="2.5" fill="var(--glyph-light)"/></>,
    bus: <><rect x="6" y="5" width="24" height="24" rx="6" fill="currentColor"/><path fill="currentColor" d="M8 26h5v5H8zm15 0h5v5h-5z"/><rect x="9" y="10" width="18" height="9" rx="2" fill="var(--glyph-light)"/><path stroke="currentColor" stroke-width="2" d="M18 10v9"/><path stroke="var(--glyph-light)" stroke-width="3" stroke-linecap="round" d="M10 24h2m12 0h2"/></>,
    ferry: <><path d="M7 18v-7h6V6h10v5h5v7l4 1-6 8-8 2-9-2-6-8z" fill="currentColor"/><path fill="var(--glyph-light)" d="M11 13h6v4h-6zm8 0h5v4h-5z"/><path stroke="currentColor" stroke-linecap="round" stroke-width="2.8" d="M3 30q4 3 8 0 4 3 8 0 4 3 8 0 3 2 6 0"/></>,
    blueprint: <><path d="M5 16V6h16M5 23v5h7M26 6h4v6" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-dasharray="5 4"/><path fill="currentColor" d="m13 12 18 10-8 2 3 7-4 2-4-8-5 5z"/></>,
    delete: <><path fill="currentColor" d="M8 13h20l-2 16q0 2-3 2H13q-3 0-3-2zM6 8h7l2-3h6l2 3h7v4H6z"/><path stroke="var(--glyph-light)" stroke-width="2.5" stroke-linecap="round" d="m15 17 1 9m5-9-1 9"/></>,
  };
  return <span class="build-token" aria-hidden="true"><svg viewBox="0 0 36 36" fill="none">{art[tool as keyof typeof art]}</svg></span>;
}
