import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const testsDir = dirname(fileURLToPath(import.meta.url));
const appRoot = dirname(testsDir);
const srcDir = join(appRoot, "../../packages/mimi-core/src");
const vendorDir = join(appRoot, "miniprogram/vendor/mimi-core");
const header = "/* synced from packages/mimi-core/src — do not edit. Run: npm run sync-miniprogram-vendor */\n";

test("miniprogram vendor copies stay in sync with packages/mimi-core/src", () => {
  const sources = readdirSync(srcDir).filter((name) => name.endsWith(".ts")).sort();
  const vendors = readdirSync(vendorDir).filter((name) => name.endsWith(".ts")).sort();
  assert.deepEqual(vendors, sources);
  for (const name of sources) {
    const source = readFileSync(join(srcDir, name), "utf8");
    const vendor = readFileSync(join(vendorDir, name), "utf8");
    assert.equal(vendor, header + source, name);
  }
});
