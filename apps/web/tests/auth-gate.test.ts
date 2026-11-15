import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { LoggedOutLogin, SessionChecking } from "../components/auth-gate.tsx";
import { copy } from "../lib/copy.ts";
import { readBrandAssets } from "../lib/read-brand-assets.ts";

const root = join(dirname(fileURLToPath(import.meta.url)), "../../..");
const webRoot = join(root, "apps/web");
const brand = readBrandAssets(webRoot);

function markup(node: ReturnType<typeof createElement>) {
  return renderToStaticMarkup(node);
}

function assertInlinedBrand(html: string) {
  assert.match(html, new RegExp(copy.brand));
  assert.match(html, /小咪/);
  assert.ok(html.includes(brand.markPath), "cat mark path is in the first markup");
  assert.match(html, new RegExp(`fill="${brand.markFill}"`));
  assert.match(html, /<svg[^>]*class="brand-mark"/);
  assert.doesNotMatch(html, /\/brand\/mark\.svg/);
  const src = html.match(/src="(data:image\/png;base64,[^"]+)"/);
  assert.ok(src, "mascot pixels are in the img src");
  const bytes = Buffer.from(src[1].slice("data:image/png;base64,".length), "base64");
  const png = readFileSync(join(webRoot, "public/mascot/idle.png"));
  assert.ok(bytes.equals(png));
  assert.equal(html.includes('decoding="sync"'), true);
  assert.doesNotMatch(html, /\/_next\/image/);
  assert.doesNotMatch(html, /src="\/mascot\/idle\.png"/);
}

test("the served cat mark is the existing brand asset", () => {
  const served = readFileSync(join(webRoot, "public/brand/mark.svg"));
  const source = readFileSync(join(root, "docs/brand/mark.svg"));
  assert.deepEqual(served, source);
  const fromFile = source.toString("utf8").match(/\sd="([^"]+)"/)?.[1];
  assert.equal(brand.markPath, fromFile);
  assert.match(source.toString("utf8"), new RegExp(`fill="${brand.markFill}"`));
});

test("the first paint while checking the session inlines the wordmark, cat mark, and mascot", () => {
  const html = markup(createElement(SessionChecking, { brand }));
  assertInlinedBrand(html);
  assert.match(html, new RegExp(copy.restoring));
  assert.doesNotMatch(html, new RegExp(copy.google));
  assert.doesNotMatch(html, new RegExp(copy.github));
});

test("the logged-out login screen inlines the same wordmark, cat mark, and mascot", () => {
  const html = markup(createElement(LoggedOutLogin, {
    brand,
    notice: null,
    busy: false,
    onGoogle: () => undefined,
    onGitHub: () => undefined,
  }));
  assertInlinedBrand(html);
  assert.match(html, new RegExp(copy.google));
  assert.match(html, new RegExp(copy.github));
  assert.doesNotMatch(html, new RegExp(copy.restoring));
});
