export const LOCK_WINDOW_MS = 30 * 60 * 1000;

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

export function lockIsActive(lockedUntil: number | null | undefined, now: number) {
  return typeof lockedUntil === "number" && Number.isFinite(lockedUntil) && lockedUntil > now;
}

/** Google shows the account chooser. GitHub only honors a prompt when it chooses to; select_account is the request we can make. */
export function reauthPrompt(provider: "google" | "github") {
  return provider === "google" ? "select_account" : "select_account";
}
