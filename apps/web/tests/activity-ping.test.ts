import assert from "node:assert/strict";
import test from "node:test";
import { noteLocalActivity, replayLocalActivity } from "../lib/activity-ping.ts";
import { IDLE_MS, activityDecision } from "../lib/lock.ts";

test("continuous local activity for more than 15 minutes does not lock", () => {
  const events: number[] = [];
  for (let now = 60_000; now <= 20 * 60_000; now += 60_000) events.push(now);
  const replay = replayLocalActivity(events);
  assert.equal(replay.locked, false);
  assert.ok(replay.pings >= 3);
  assert.ok(replay.seenAt >= 15 * 60_000);
});

test("a ping does not unlock a session that is already locked", () => {
  assert.equal(noteLocalActivity({ lastPingAt: 0, locked: true }, IDLE_MS).ping, false);
  const decision = activityDecision({ locked: true, seenAt: 1_000, now: 1_000 + IDLE_MS });
  assert.equal(decision.locked, true);
  assert.equal(decision.touch, false);
  assert.equal(decision.seenAt, 1_000);
});

test("no local activity for 15 minutes still locks and sends no ping", () => {
  const quiet = replayLocalActivity([]);
  assert.equal(quiet.pings, 0);
  assert.equal(activityDecision({ locked: false, seenAt: 0, now: IDLE_MS }).locked, true);
  const returned = replayLocalActivity([IDLE_MS + 60_000]);
  assert.equal(returned.pings, 1);
  assert.equal(returned.locked, true);
});
