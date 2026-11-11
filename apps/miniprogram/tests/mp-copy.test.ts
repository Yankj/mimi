import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { isBindCodeFormat, normalizeBindCode } from "../miniprogram/utils/bind-code.ts";
import { copy } from "../miniprogram/utils/mp-copy.ts";

const root = new URL("../miniprogram", import.meta.url).pathname;

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (name === "vendor") return [];
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const pageSources = walk(join(root, "pages")).concat(walk(join(root, "components"))).filter((f) => f.endsWith(".ts"));

test("pages take copy from the miniprogram layer, not straight from mimi-core", () => {
  for (const file of pageSources) {
    const src = readFileSync(file, "utf8");
    if (src.includes("copy.")) assert.match(src, /from "\.\.\/\.\.\/utils\/mp-copy"/, file);
    assert.doesNotMatch(src, /import \{ copy \} from "\.\.\/\.\.\/vendor/, file);
  }
});

test("copy shown in the miniprogram does not speak as the Web app", () => {
  const used = new Set<string>();
  for (const file of pageSources) for (const m of readFileSync(file, "utf8").matchAll(/copy\.([a-zA-Z]+)/g)) used.add(m[1]);
  assert.ok(used.size > 30);
  for (const key of used) {
    const value = (copy as Record<string, string>)[key];
    assert.equal(typeof value, "string", key);
    assert.doesNotMatch(value, /浏览器|标签页|Google|GitHub|网页不|手机上的小咪/, key);
    if (key !== "bindBody") assert.doesNotMatch(value, /网页/, key);
  }
});

test("bind code format is checked locally only", () => {
  assert.equal(normalizeBindCode(" mimi-7k2q-9x4c "), "MIMI-7K2Q-9X4C");
  assert.equal(isBindCodeFormat("mimi-7k2q-9x4c"), true);
  assert.equal(isBindCodeFormat("MIMI-7K2Q"), false);
  assert.equal(isBindCodeFormat("CP-1234-5678-9ABC"), false);
  const bind = readFileSync(join(root, "pages/bind/bind.ts"), "utf8");
  assert.doesNotMatch(bind, /wx\.request|request\(|fetch\(/);
});
