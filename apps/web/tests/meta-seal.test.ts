import assert from "node:assert/strict";
import test from "node:test";
import { activityDecision, lockRecorded } from "../lib/lock.ts";
import { openMeta, sealMeta } from "../lib/meta-seal.ts";

const secret = "test-session-meta-hmac";

function payload(meta: Record<string, unknown>) {
  return JSON.stringify(meta);
}

function retarget(sealed: string, next: Record<string, unknown>) {
  const dot = sealed.lastIndexOf(".");
  const mac = sealed.slice(dot + 1);
  const body = Buffer.from(payload(next), "utf8").toString("base64url");
  return `${body}.${mac}`;
}

test("a signed session meta round-trips and a tampered seenAt stays locked", () => {
  const meta = { userId: "user-1", scopeId: "scope-1", email: null, expiresAt: 10, seenAt: 2_000, provider: "google" };
  const sealed = sealMeta(payload(meta), secret, true);
  assert.ok(sealed);
  const opened = openMeta(sealed ?? undefined, secret, true);
  assert.equal(opened.ok, true);
  if (!opened.ok) return;
  assert.equal(opened.meta.seenAt, 2_000);
  assert.equal(lockRecorded(opened.meta), false);
  assert.equal(activityDecision({ locked: false, seenAt: opened.meta.seenAt as number, now: 3_000 }).locked, false);

  const tampered = retarget(sealed ?? "", { ...meta, seenAt: Date.now() });
  const broken = openMeta(tampered, secret, true);
  assert.equal(broken.ok, false);
  assert.equal(broken.ok ? false : broken.reason, "signature");
});

test("dropping the lock flag without a matching signature stays locked", () => {
  const meta = { userId: "user-1", scopeId: "scope-1", email: null, expiresAt: 10, seenAt: 2_000, locked: true };
  const sealed = sealMeta(payload(meta), secret, true);
  assert.ok(sealed);
  const stripped = { ...meta } as { locked?: boolean };
  delete stripped.locked;
  const opened = openMeta(retarget(sealed ?? "", stripped), secret, true);
  assert.equal(opened.ok, false);
  if (!opened.ok) assert.equal(opened.reason, "signature");
});

test("a missing meta cookie and a missing production secret fail closed", () => {
  assert.deepEqual(openMeta(undefined, secret, true), { ok: false, reason: "missing" });
  const sealed = sealMeta(payload({ userId: "user-1", scopeId: "scope-1", seenAt: 1 }), secret, true);
  assert.equal(openMeta(sealed ?? undefined, undefined, true).ok, false);
  assert.equal(openMeta(sealed ?? undefined, " ", true).ok, false);
  assert.equal(openMeta("{", secret, true).ok, false);
});

test("valid signed meta still opens when the secret matches", () => {
  const meta = { userId: "user-1", scopeId: "scope-1", email: "parent@example.com", expiresAt: 99, seenAt: 50, locked: false, provider: "github" };
  const sealed = sealMeta(payload(meta), secret, true);
  const opened = openMeta(sealed ?? undefined, secret, true);
  assert.equal(opened.ok, true);
  if (!opened.ok) return;
  assert.equal(opened.meta.userId, "user-1");
  assert.equal(opened.meta.scopeId, "scope-1");
  assert.equal(opened.meta.seenAt, 50);
  assert.equal(opened.meta.locked, false);
  assert.equal(opened.meta.provider, "github");
});
