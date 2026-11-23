// A separate entry and build directory keep the Android MessagePort out of the public Studio.
import { fileURLToPath } from "node:url";
import { cpSync } from "node:fs";
import { defineConfig } from "vite";

// The headless clock draws nothing. Only files read by Kotlin or required by licences are copied;
// fonts, flags and sounds belong to the browser UI.
const publicDir = fileURLToPath(new URL("./public/", import.meta.url));
const outDir = fileURLToPath(new URL("./dist-native-clock/", import.meta.url));
const keep = ["LICENSE", "NOTICE.md", "snapshots"];

export default defineConfig({
  root: fileURLToPath(new URL("./native-clock/", import.meta.url)),
  base: "./",
  publicDir: false,
  plugins: [{ name: "native-clock-public", closeBundle() { for (const f of keep) cpSync(publicDir + f, outDir + f, { recursive: true }); } }],
  worker: { format: "es" },
  define: { "import.meta.env.TW_NATIVE_CLOCK": "true", __TW_BUILD__: JSON.stringify("native-" + Date.now().toString(36)), __TW_VERSION__: JSON.stringify("native") },
  build: {
    outDir: "../dist-native-clock", emptyOutDir: true, target: "es2022",
    rolldownOptions: {
      input: { clock: fileURLToPath(new URL("./native-clock/index.html", import.meta.url)) },
      output: { assetFileNames: asset => asset.names.some(n => n.endsWith(".mjs"))
        ? "assets/[name]-[hash].js" : "assets/[name]-[hash][extname]" },
    },
  },
});
