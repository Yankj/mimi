#!/usr/bin/env node
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(new URL("../package.json", import.meta.url)));
const srcDir = join(root, "src");
const destDir = join(root, "../../apps/miniprogram/miniprogram/vendor/mimi-core");

mkdirSync(destDir, { recursive: true });
for (const name of readdirSync(destDir)) {
  if (name.endsWith(".ts")) rmSync(join(destDir, name));
}

const header = "/* synced from packages/mimi-core/src — do not edit. Run: npm run sync-miniprogram-vendor */\n";
for (const name of readdirSync(srcDir).filter((file) => file.endsWith(".ts")).sort()) {
  writeFileSync(join(destDir, name), header + readFileSync(join(srcDir, name), "utf8"));
}

writeFileSync(
  join(destDir, "README.md"),
  "# vendor/mimi-core\n\nGenerated from `packages/mimi-core/src`. Do not edit these copies. Run `npm run sync-miniprogram-vendor` in `packages/mimi-core` after changing the source.\n",
);

console.log(`synced ${srcDir} -> ${destDir}`);
