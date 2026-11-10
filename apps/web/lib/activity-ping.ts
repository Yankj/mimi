import { activityDecision } from "./lock";

/** Local activity refreshes seenAt at most this often. */
export const ACTIVITY_PING_MS = 5 * 60 * 1000;

export type LocalActivity = {
  lastPingAt: number;
  locked: boolean;
};

/** A ping is sent only after local activity, and never while the session is locked. */
export function noteLocalActivity(state: LocalActivity, now: number, pingMs = ACTIVITY_PING_MS) {
  if (state.locked || now - state.lastPingAt < pingMs) return { state, ping: false };
  return { state: { ...state, lastPingAt: now }, ping: true };
}

/** Replay pointer/key/scroll/touch/visibility events. The boot session read is lastPingAt/seenAt. */
export function replayLocalActivity(events: number[], seenAt = 0, lastPingAt = 0) {
  let currentSeen = seenAt;
  let state: LocalActivity = { lastPingAt, locked: false };
  let pings = 0;
  for (const now of events) {
    const noted = noteLocalActivity(state, now);
    if (!noted.ping) continue;
    pings += 1;
    const decision = activityDecision({ locked: state.locked, seenAt: currentSeen, now });
    state = { lastPingAt: noted.state.lastPingAt, locked: decision.locked };
    if (decision.touch && decision.seenAt != null) currentSeen = decision.seenAt;
  }
  const end = events.length ? events[events.length - 1] : seenAt;
  const final = activityDecision({ locked: state.locked, seenAt: currentSeen, now: end });
  return { locked: final.locked, seenAt: currentSeen, pings };
}
