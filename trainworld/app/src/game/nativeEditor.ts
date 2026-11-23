import { signal } from "@preact/signals";

// Editing can continue with the clock held when offline demand data is missing.
export const editingWithoutDemand = signal(false);

// The visible Android editor imports its single TWG1 from an already-established local MessagePort.
// The public Studio neither receives this archive nor exposes a way to connect to this bridge.
export const inNativeEditor = typeof location !== "undefined" &&
  location.origin === "https://studio-clock.invalid" && location.pathname === "/clock/editor.html";
let incoming: ArrayBuffer | null = null;
export let editorHome: string | undefined;
export function rememberEditorHome(id: string) { editorHome ??= id; }
export const requestEditorSave = () => window.dispatchEvent(new Event("studio-editor-save"));
export function supplyEditorArchive(bytes: ArrayBuffer, home?: string) {
  if (!inNativeEditor || incoming) throw Error("Editor lokal tidak tersedia.");
  incoming = bytes;
  editorHome = home;
}
export function takeEditorArchive() {
  const bytes = incoming;
  incoming = null;
  return bytes;
}
