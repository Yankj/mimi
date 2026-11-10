import assert from "node:assert/strict";
import test from "node:test";
import { parseChatIndex, serializeChatIndex } from "../lib/chat-index.ts";
import { flushSseBuffer, uploadUrlAllowed } from "../lib/wire.ts";

const id = "11111111-1111-4111-8111-111111111111";
const host = "abcdefghijklmnopqrst.supabase.co";

test("chat index keeps ids and drops message text", () => {
  const raw = serializeChatIndex([
    { id, updatedAt: "2026-11-10T00:00:00.000Z", title: "secret title", preview: "secret preview", messages: [{ text: "hello body" }] } as { id: string; updatedAt: string },
  ]);
  assert.equal(raw.includes("hello"), false);
  assert.equal(raw.includes("secret"), false);
  assert.equal(raw.includes("text"), false);
  assert.equal(raw.includes("title"), false);
  assert.equal(raw.includes("messages"), false);
  assert.deepEqual(parseChatIndex(raw), [{ id, updatedAt: "2026-11-10T00:00:00.000Z" }]);
  assert.deepEqual(parseChatIndex(JSON.stringify([{ id: "not-a-uuid", updatedAt: "t", text: "nope" }])), []);
});

test("upload URLs stay on the configured https host", () => {
  assert.equal(uploadUrlAllowed(`https://${host}/storage/v1/object/sign/a`, [host]), true);
  assert.equal(uploadUrlAllowed(`http://${host}/storage/v1/object/sign/a`, [host]), false);
  assert.equal(uploadUrlAllowed("https://evil.example/storage", [host]), false);
  assert.equal(uploadUrlAllowed(`https://user:pass@${host}/storage`, [host]), false);
  assert.equal(uploadUrlAllowed(`https://${host}/storage`, []), false);
});

test("the last SSE line is emitted when the stream ends without a newline", () => {
  const flushed = flushSseBuffer('data: {"type":"text_delta","data":{"text":"尾"}}');
  assert.equal(flushed.lines.length, 1);
  assert.equal(flushed.lines[0].startsWith("data: "), true);
  assert.equal(flushed.rest, "");
  assert.deepEqual(flushSseBuffer(""), { lines: [], rest: "" });
});
