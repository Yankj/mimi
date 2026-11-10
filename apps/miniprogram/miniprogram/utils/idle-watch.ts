import type { SessionController } from "./session";

export const IDLE_CHECK_MS = 30 * 1000;

type Timers = {
  setInterval: (fn: () => void, ms: number) => unknown;
  clearInterval: (handle: unknown) => void;
};

/**
 * While the miniprogram is in the foreground, check the idle deadline on a timer,
 * so an untouched open screen still locks after 15 minutes (A07 / MP-A6).
 */
export function createIdleWatch(input: {
  session: Pick<SessionController, "checkIdle">;
  onLocked: () => void;
  now?: () => number;
  intervalMs?: number;
  timers?: Timers;
}) {
  const now = input.now ?? (() => Date.now());
  const intervalMs = input.intervalMs ?? IDLE_CHECK_MS;
  const timers: Timers = input.timers ?? {
    setInterval: (fn, ms) => setInterval(fn, ms),
    clearInterval: (handle) => clearInterval(handle as ReturnType<typeof setInterval>),
  };
  let handle: unknown = null;

  function tick() {
    if (input.session.checkIdle(now()) === "locked") {
      stop();
      input.onLocked();
    }
  }

  function stop() {
    if (handle !== null) timers.clearInterval(handle);
    handle = null;
  }

  return {
    start() {
      stop();
      handle = timers.setInterval(tick, intervalMs);
    },
    stop,
    tick,
    running: () => handle !== null,
  };
}
