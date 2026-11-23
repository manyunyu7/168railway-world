// A separate entry and build directory keep the Android MessagePort out of the public Studio.
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  root: fileURLToPath(new URL("./native-clock/", import.meta.url)),
  base: "./",
  publicDir: fileURLToPath(new URL("./public/", import.meta.url)),
  worker: { format: "es" },
  define: { "import.meta.env.TW_NATIVE_CLOCK": "true", __TW_BUILD__: JSON.stringify("native-" + Date.now().toString(36)), __TW_VERSION__: JSON.stringify("native") },
  build: {
    outDir: "../dist-native-clock", emptyOutDir: true, target: "es2022",
    rolldownOptions: {
      input: { clock: fileURLToPath(new URL("./native-clock/index.html", import.meta.url)), editor: fileURLToPath(new URL("./native-clock/editor.html", import.meta.url)) },
      output: { assetFileNames: asset => asset.names.some(n => n.endsWith(".mjs"))
        ? "assets/[name]-[hash].js" : "assets/[name]-[hash][extname]" },
    },
  },
});
