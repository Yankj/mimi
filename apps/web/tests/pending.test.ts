import assert from "node:assert/strict";
import test from "node:test";
import { decodePending, encodePending, PENDING_LIMIT } from "../lib/pending.ts";

const session = {
  accessToken: "a".repeat(24),
  refreshToken: "r".repeat(24),
  expiresAt: 1_700_000_000_000,
  userId: "11111111-1111-1111-1111-111111111111",
  email: "parent@example.com",
  scopeId: "22222222-2222-2222-2222-222222222222",
  provider: "google" as const,
};

test("a pending session cookie stays well under 4KB", () => {
  const raw = encodePending(session);
  const size = new TextEncoder().encode(raw).byteLength;
  assert.ok(size < PENDING_LIMIT);
  assert.ok(size < 4096);
  assert.equal(raw.includes("parent@example.com"), true);
  assert.deepEqual(decodePending(raw), session);
});

test("an oversized pending session is refused", () => {
  assert.throws(() => encodePending({ ...session, accessToken: "a".repeat(PENDING_LIMIT) }), /too large/);
  assert.equal(decodePending("x".repeat(PENDING_LIMIT + 1)), null);
});
