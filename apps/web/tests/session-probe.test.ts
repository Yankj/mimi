import assert from "node:assert/strict";
import test from "node:test";
import { SESSION_PROBE_TIMEOUT_MS, probeSession } from "../lib/session-probe.ts";

test("the session check waits 8 seconds", () => {
  assert.equal(SESSION_PROBE_TIMEOUT_MS, 8_000);
});

test("a profile that arrives in time is kept", async () => {
  const result = await probeSession(async () => ({ userId: "parent" }), { timeoutMs: 1_000 });
  assert.deepEqual(result, { ok: true, profile: { userId: "parent" } });
});

test("an http failure and a thrown error both fall back to signed out", async () => {
  assert.deepEqual(await probeSession(async () => null, { timeoutMs: 1_000 }), { ok: false });
  assert.deepEqual(
    await probeSession(async () => {
      throw new Error("network");
    }, { timeoutMs: 1_000 }),
    { ok: false },
  );
});

test("a check that outlasts the deadline falls back to signed out", async () => {
  const result = await probeSession(
    (signal) => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => reject(new Error("aborted")));
    }),
    { timeoutMs: 30 },
  );
  assert.deepEqual(result, { ok: false });
});

test("the deadline wins even when the loader ignores the signal", async () => {
  let late: ReturnType<typeof setTimeout> | undefined;
  const result = await probeSession(
    () => new Promise((resolve) => {
      late = setTimeout(() => resolve({ userId: "late" }), 200);
    }),
    { timeoutMs: 20 },
  );
  if (late) clearTimeout(late);
  assert.deepEqual(result, { ok: false });
});

test("cancelling the check falls back without waiting out the deadline", async () => {
  const parent = new AbortController();
  const pending = probeSession(
    (signal) => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => reject(new Error("aborted")));
    }),
    { signal: parent.signal, timeoutMs: 60_000 },
  );
  parent.abort();
  assert.deepEqual(await pending, { ok: false });
});
