import assert from "node:assert/strict";
import test from "node:test";
import { requestOrigin, sameOriginAllowed, siteOrigin } from "../lib/origin.ts";

const site = "https://mimi.example";

function call(url: string, headers: Record<string, string> = {}) {
  return new Request(url, { headers });
}

test("Origin must match the configured site", () => {
  assert.equal(sameOriginAllowed(call("https://mimi.example/api/auth/logout", { origin: site }), site), true);
  assert.equal(sameOriginAllowed(call("https://mimi.example/api/auth/logout", { origin: "https://evil.example" }), site), false);
  assert.equal(sameOriginAllowed(call("https://mimi.example/api/auth/logout"), site), false);
});

test("Referer is used only when Origin is missing", () => {
  const request = call("https://mimi.example/api/edge/catpaw", { referer: "https://mimi.example/today" });
  assert.equal(requestOrigin(request), site);
  assert.equal(sameOriginAllowed(request, site), true);
  assert.equal(sameOriginAllowed(call("https://mimi.example/api/edge/catpaw", { referer: "https://evil.example/today" }), site), false);
});

test("local loopback can omit the site url and ignores forwarded host", () => {
  const request = call("http://127.0.0.1:3111/api/auth/start", {
    origin: "http://127.0.0.1:3111",
    "x-forwarded-host": "evil.example",
    "x-forwarded-proto": "https",
  });
  assert.equal(siteOrigin(undefined, request), "http://127.0.0.1:3111");
  assert.equal(sameOriginAllowed(request, undefined), true);
  assert.equal(siteOrigin(undefined, call("https://mimi.example/api/auth/start", { origin: site })), null);
  const rewritten = call("http://localhost:3111/api/auth/lock", {
    host: "127.0.0.1:3111",
    origin: "http://127.0.0.1:3111",
    "x-forwarded-host": "evil.example",
  });
  assert.equal(siteOrigin(undefined, rewritten), "http://127.0.0.1:3111");
  assert.equal(sameOriginAllowed(rewritten, undefined), true);
  assert.equal(siteOrigin(undefined, call("https://mimi.example/api/auth/lock", { host: "127.0.0.1:3111", origin: "http://127.0.0.1:3111" })), null);
});
