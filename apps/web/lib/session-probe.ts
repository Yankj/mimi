/** How long the first screen waits for `GET /api/session`.
 *
 * A cold start usually finishes in a couple of seconds. Eight seconds still
 * covers a slow function start, and gives up well before the ~30s hang that
 * left the gate stuck. The route only opens the sealed cookie; it does not
 * call Supabase or catpaw-api, so there is no upstream fetch on that path
 * to bound. The client deadline is what ends the wait.
 */
export const SESSION_PROBE_TIMEOUT_MS = 8_000;

export type SessionProbe<T> =
  | { ok: true; profile: T }
  | { ok: false };

type ProbeOptions = {
  timeoutMs?: number;
  signal?: AbortSignal;
};

/** Resolve a session read, or give up on timeout, abort, or error. */
export async function probeSession<T>(
  load: (signal: AbortSignal) => Promise<T | null>,
  options: ProbeOptions = {},
): Promise<SessionProbe<T>> {
  const timeoutMs = options.timeoutMs ?? SESSION_PROBE_TIMEOUT_MS;
  const controller = new AbortController();
  const stopParent = () => controller.abort();
  if (options.signal) {
    if (options.signal.aborted) controller.abort();
    else options.signal.addEventListener("abort", stopParent, { once: true });
  }
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const loaded = load(controller.signal).then(
    (profile) => ({ failed: false as const, profile }),
    () => ({ failed: true as const, profile: null }),
  );
  const aborted = new Promise<{ failed: true; profile: null }>((resolve) => {
    if (controller.signal.aborted) {
      resolve({ failed: true, profile: null });
      return;
    }
    controller.signal.addEventListener("abort", () => resolve({ failed: true, profile: null }), { once: true });
  });
  try {
    const outcome = await Promise.race([loaded, aborted]);
    if (outcome.failed || outcome.profile == null) return { ok: false };
    return { ok: true, profile: outcome.profile };
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", stopParent);
  }
}
