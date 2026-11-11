import assert from "node:assert/strict";
import { test } from "node:test";
import { createChatStore, mockChatHeaders, mockReplyText, mockTurnRequest, parseMockStream, sendMockTurn } from "../miniprogram/utils/mock-chat.ts";
import { setTransport } from "../miniprogram/utils/request.ts";

test("mock chat headers match the shared iOS/Web contract and omit device tools", () => {
  const headers = mockChatHeaders();
  assert.equal(headers["X-Catpaw-Route"], "auto");
  assert.equal(headers["X-Catpaw-Route-Events"], "1");
  assert.equal(headers["X-Catpaw-Reasoning-Summary"], "1");
  assert.equal(headers["X-Catpaw-Live-Text"], "1");
  assert.equal(headers["X-Catpaw-Approval-Contract"], "catpaw.approval.v1");
  assert.equal(headers["X-Catpaw-UI-Contract"], "catpaw.ui.v1");
  assert.equal(headers["X-Catpaw-UI-Kinds"], "card,choices,confirm,event,form,list,place,progress,sheet,followups");
  assert.equal(headers["X-Catpaw-Memory-Proposal-Contract"], "catpaw.memory-proposal.v1");
  assert.equal(headers["X-Catpaw-Use-Memory"], "1");
  assert.equal(headers["X-Catpaw-Time-Zone"], "Asia/Shanghai");
  assert.equal(headers["X-Catpaw-Device-Tools"], undefined);
  assert.equal(headers["X-Catpaw-Device-Contract"], undefined);
  assert.equal(headers["X-Catpaw-Model"], undefined);
  const request = mockTurnRequest("你好");
  assert.equal(request.headers["Idempotency-Key"], "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  assert.equal(JSON.parse(request.body).message.text, "你好");
  assert.match(request.path, /\/turns$/);
});

test("the mock stream parses as SSE text deltas and goes through the request helper", async () => {
  const urls: string[] = [];
  setTransport(async (input) => {
    urls.push(input.url);
    assert.equal(input.headers?.["X-Catpaw-Live-Text"], "1");
    assert.equal(input.headers?.["X-Catpaw-Device-Tools"], undefined);
    return { ok: true, status: 200, url: input.url, headers: input.headers ?? {}, body: "" };
  });
  const deltas = await sendMockTurn("你好");
  setTransport(null);
  assert.equal(urls.length, 1);
  assert.match(urls[0] ?? "", /^https:\/\/mimi-miniprogram-api\.example\.invalid\//);
  assert.ok(!urls[0]?.includes("supabase"));
  const text = deltas.map((item) => item.text).join("");
  assert.equal(text, mockReplyText("你好"));
  assert.deepEqual([...parseMockStream("你好")].map((item) => item.text).join(""), text);
});

test("locking or signing out can wipe the in-memory transcript", () => {
  const chat = createChatStore();
  chat.add("user", "你好");
  chat.add("assistant", "收到了");
  assert.equal(chat.list().length, 2);
  chat.clear();
  assert.deepEqual(chat.list(), []);
});
