import assert from "node:assert/strict";
import { test } from "node:test";
import { IDLE_MS } from "../miniprogram/utils/config.ts";
import { routeForSession } from "../miniprogram/utils/guard.ts";
import { createMemoryStorage, createSessionController, idleExceeded } from "../miniprogram/utils/session.ts";

test("idle is fifteen minutes and a missing seenAt is already idle", () => {
  assert.equal(IDLE_MS, 15 * 60 * 1000);
  const now = 1_000_000;
  assert.equal(idleExceeded(now - IDLE_MS, now), true);
  assert.equal(idleExceeded(now - IDLE_MS + 1, now), false);
  assert.equal(idleExceeded(null, now), true);
  assert.equal(idleExceeded(undefined, now), true);
});

test("onHide then a late onShow locks, and sign-out clears storage", () => {
  const storage = createMemoryStorage();
  let locked = 0;
  let wiped = 0;
  const session = createSessionController(storage, { onLock: () => (locked += 1), onWipe: () => (wiped += 1) });
  const start = 5_000_000;
  session.signInMock(start, "模拟用户");
  session.onHide(start + 1_000);
  assert.equal(session.onShow(start + 1_000 + IDLE_MS), "locked");
  assert.equal(session.isLocked(), true);
  assert.equal(locked, 1);
  assert.equal(routeForSession(session, start + 1_000 + IDLE_MS + 10), "lock");
  session.signOut();
  assert.equal(session.isSignedIn(), false);
  assert.equal(session.snapshot().displayName, null);
  assert.equal(storage.get("mimi.miniprogram.session.v1"), undefined);
  assert.equal(wiped, 1);
});

test("activity within the idle window stays unlocked", () => {
  const session = createSessionController(createMemoryStorage());
  const start = 8_000_000;
  session.signInMock(start);
  session.onHide(start + 2_000);
  assert.equal(session.onShow(start + 2_000 + IDLE_MS - 1), "ok");
  assert.equal(session.isLocked(), false);
});
