import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { LoggedOutLogin, SessionChecking } from "../components/auth-gate.tsx";
import { copy } from "../lib/copy.ts";

const root = join(dirname(fileURLToPath(import.meta.url)), "../../..");

function markup(node: ReturnType<typeof createElement>) {
  return renderToStaticMarkup(node);
}

function assertBrand(html: string) {
  assert.match(html, new RegExp(copy.brand));
  assert.match(html, /小咪/);
  assert.match(html, /\/brand\/mark\.svg/);
  assert.match(html, /mascot%2Fidle\.png|mascot\/idle\.png/);
}

test("the served cat mark is the existing brand asset", () => {
  const served = readFileSync(join(root, "apps/web/public/brand/mark.svg"));
  const source = readFileSync(join(root, "docs/brand/mark.svg"));
  assert.deepEqual(served, source);
});

test("the first paint while checking the session shows the wordmark and the cat mark", () => {
  const html = markup(createElement(SessionChecking));
  assertBrand(html);
  assert.match(html, new RegExp(copy.restoring));
  assert.doesNotMatch(html, new RegExp(copy.google));
  assert.doesNotMatch(html, new RegExp(copy.github));
});

test("the logged-out login screen shows the same wordmark and cat mark", () => {
  const html = markup(createElement(LoggedOutLogin, {
    notice: null,
    busy: false,
    onGoogle: () => undefined,
    onGitHub: () => undefined,
  }));
  assertBrand(html);
  assert.match(html, new RegExp(copy.google));
  assert.match(html, new RegExp(copy.github));
  assert.doesNotMatch(html, new RegExp(copy.restoring));
});
