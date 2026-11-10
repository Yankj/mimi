import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

// C「素白墨黑」 light tokens, same as Web / iOS (docs/brand-theme.md).
const allowed = new Set(["#FAF9F7", "#FFFFFF", "#E6E3DE", "#111111", "#5A5853", "#6F6D69", "#C9501A", "#C0352B", "#F2F0EC"]);

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (name === "vendor") return [];
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

test("miniprogram colours stay on the 素白墨黑 palette", () => {
  const root = new URL("../miniprogram", import.meta.url).pathname;
  for (const file of walk(root).filter((f) => /\.(wxss|json)$/.test(f))) {
    for (const hex of readFileSync(file, "utf8").match(/#[0-9a-fA-F]{6}\b/g) ?? []) {
      assert.ok(allowed.has(hex.toUpperCase()), `${file}: ${hex}`);
    }
  }
});

test("privacy mask uses the canvas colour", () => {
  const css = readFileSync(new URL("../miniprogram/app.wxss", import.meta.url), "utf8");
  assert.match(css, /\.privacy-mask \{[^}]*background: #FAF9F7;/);
});
