export const IDLE_MS = 15 * 60 * 1000;

/** Same lifetime as the HttpOnly session cookies. A lock must not expire sooner. */
export const SESSION_MAX_AGE_SEC = 60 * 60 * 24 * 14;

export type UnlockDecision = "same-user" | "different-user";

export function unlockDecision(lockedUserId: string, nextUserId: string): UnlockDecision {
  if (!lockedUserId || !nextUserId) return "different-user";
  return lockedUserId.toLowerCase() === nextUserId.toLowerCase() ? "same-user" : "different-user";
}

/** Same user keeps this browser's encrypted chats. A different user must not see them. */
export function unlockOutcome(lockedUserId: string, nextUserId: string) {
  const decision = unlockDecision(lockedUserId, nextUserId);
  return {
    decision,
    userId: nextUserId.toLowerCase(),
    wipeLocal: decision === "different-user",
  };
}

/** A stored lock flag stays until same-user re-auth or sign-out. An old lockedUntil value is still a lock after its timestamp. */
export function lockRecorded(meta: { locked?: boolean; lockedUntil?: number | null }) {
  if (meta.locked === true) return true;
  return typeof meta.lockedUntil === "number" && Number.isFinite(meta.lockedUntil);
}

/** Missing or non-finite seenAt is idle. A deleted field must not look like a fresh session. */
export function idleExceeded(seenAt: number | null | undefined, now: number, idleMs = IDLE_MS) {
  if (typeof seenAt !== "number" || !Number.isFinite(seenAt)) return true;
  return now - seenAt >= idleMs;
}

/** Recent activity moves seenAt forward. Idle or an existing lock stays locked and does not refresh seenAt. */
export function activityDecision(input: { locked: boolean; seenAt?: number | null; now: number }, idleMs = IDLE_MS) {
  const seenAt = typeof input.seenAt === "number" && Number.isFinite(input.seenAt) ? input.seenAt : null;
  if (input.locked || idleExceeded(seenAt, input.now, idleMs)) {
    return { locked: true as const, seenAt, touch: false };
  }
  return { locked: false as const, seenAt: input.now, touch: true };
}

/** Google shows the account chooser. GitHub only honors a prompt when it chooses to; select_account is the request we can make. */
export function reauthPrompt(provider: "google" | "github") {
  return provider === "google" ? "select_account" : "select_account";
}
