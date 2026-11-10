import assert from "node:assert/strict";
import { test } from "node:test";
import { PLACEHOLDER_BASE_URL } from "../miniprogram/utils/config.ts";
import { assertSafeUrl, isForbiddenUrl, request, resolveUrl, setTransport } from "../miniprogram/utils/request.ts";

test("the default base URL is a non-routable placeholder", () => {
  assert.equal(PLACEHOLDER_BASE_URL, "https://mimi-miniprogram-api.example.invalid");
  assert.equal(isForbiddenUrl(PLACEHOLDER_BASE_URL + "/readyz"), false);
  assert.equal(resolveUrl("/readyz"), "https://mimi-miniprogram-api.example.invalid/readyz");
});

test("request refuses supabase, catpaw and non-https hosts", () => {
  assert.equal(isForbiddenUrl("https://abcdefghijklmnopqrst.supabase.co/auth/v1/token"), true);
  assert.equal(isForbiddenUrl("https://dbyklgxdvjbutxzsjeth.supabase.co/functions/v1/catpaw-api"), true);
  assert.equal(isForbiddenUrl("https://example.catpaw.dev/v1"), true);
  assert.equal(isForbiddenUrl("http://mimi-miniprogram-api.example.invalid/readyz"), true);
  assert.equal(isForbiddenUrl("https://127.0.0.1/readyz"), true);
  assert.throws(() => assertSafeUrl("https://abcdefghijklmnopqrst.supabase.co/"), /不连接线上地址/);
});

test("request uses the injected transport and never invents a live call", async () => {
  const seen: string[] = [];
  setTransport(async (input) => {
    seen.push(input.url);
    return { ok: true, status: 200, url: input.url, headers: input.headers ?? {}, body: "{}" };
  });
  const result = await request({ path: "/readyz", method: "GET" });
  assert.equal(result.status, 200);
  assert.deepEqual(seen, ["https://mimi-miniprogram-api.example.invalid/readyz"]);
  setTransport(null);
  await assert.rejects(() => request({ path: "/readyz" }), /模拟数据/);
});

test("functions/v1 is blocked without a trailing slash, in any case, and when encoded", () => {
  assert.equal(isForbiddenUrl("https://proxy.example.com/functions/v1"), true);
  assert.equal(isForbiddenUrl("https://proxy.example.com/FUNCTIONS/V1/x"), true);
  assert.equal(isForbiddenUrl("https://proxy.example.com/functions%2Fv1/x"), true);
  assert.equal(isForbiddenUrl("https://proxy.example.com/functions/v10"), false);
});
