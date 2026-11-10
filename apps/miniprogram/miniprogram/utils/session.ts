import { IDLE_MS, STORAGE_KEY } from "./config";

export type Storage = {
  get(key: string): unknown;
  set(key: string, value: unknown): void;
  remove(key: string): void;
  clear(): void;
};

export type SessionSnapshot = {
  signedIn: boolean;
  locked: boolean;
  seenAt: number | null;
  displayName: string | null;
};

export type SessionHooks = {
  onLock?: () => void;
  onWipe?: () => void;
};

/** Missing or non-finite seenAt is idle, matching the Web server rule. */
export function idleExceeded(seenAt: number | null | undefined, now: number, idleMs = IDLE_MS) {
  if (typeof seenAt !== "number" || !Number.isFinite(seenAt)) return true;
  return now - seenAt >= idleMs;
}

export function emptySnapshot(): SessionSnapshot {
  return { signedIn: false, locked: false, seenAt: null, displayName: null };
}

function readSnapshot(storage: Storage): SessionSnapshot {
  const raw = storage.get(STORAGE_KEY);
  if (!raw || typeof raw !== "object") return emptySnapshot();
  const row = raw as Record<string, unknown>;
  return {
    signedIn: row.signedIn === true,
    locked: row.locked === true,
    seenAt: typeof row.seenAt === "number" && Number.isFinite(row.seenAt) ? row.seenAt : null,
    displayName: typeof row.displayName === "string" ? row.displayName : null,
  };
}

export function createMemoryStorage(initial: Record<string, unknown> = {}): Storage {
  const data = new Map<string, unknown>(Object.entries(initial));
  return {
    get: (key) => data.get(key),
    set: (key, value) => {
      data.set(key, value);
    },
    remove: (key) => {
      data.delete(key);
    },
    clear: () => {
      data.clear();
    },
  };
}

export function wxStorage(): Storage {
  return {
    get: (key) => wx.getStorageSync(key),
    set: (key, value) => wx.setStorageSync(key, value),
    remove: (key) => wx.removeStorageSync(key),
    clear: () => wx.clearStorageSync(),
  };
}

export function createSessionController(storage: Storage, hooks: SessionHooks = {}) {
  let snapshot = readSnapshot(storage);

  function persist() {
    if (!snapshot.signedIn) {
      storage.remove(STORAGE_KEY);
      return;
    }
    storage.set(STORAGE_KEY, { ...snapshot });
  }

  function wipe() {
    snapshot = emptySnapshot();
    storage.clear();
    hooks.onWipe?.();
  }

  return {
    snapshot(): SessionSnapshot {
      return { ...snapshot };
    },
    isSignedIn() {
      return snapshot.signedIn;
    },
    isLocked() {
      return snapshot.locked;
    },
    /** Mock sign-in only. Real WeChat login is not wired in this batch. */
    signInMock(now: number, displayName = "模拟用户") {
      snapshot = { signedIn: true, locked: false, seenAt: now, displayName };
      persist();
    },
    signOut() {
      wipe();
    },
    touch(now: number) {
      if (!snapshot.signedIn || snapshot.locked) return;
      snapshot = { ...snapshot, seenAt: now };
      persist();
    },
    onHide(now: number) {
      this.touch(now);
    },
    onShow(now: number) {
      if (!snapshot.signedIn) return "signed-out";
      if (snapshot.locked || idleExceeded(snapshot.seenAt, now)) {
        snapshot = { ...snapshot, locked: true };
        persist();
        hooks.onLock?.();
        return "locked";
      }
      this.touch(now);
      return "ok";
    },
    /** Mock re-confirm. Real re-auth waits on the WeChat login decision. */
    unlockMock(now: number) {
      if (!snapshot.signedIn) return;
      snapshot = { ...snapshot, locked: false, seenAt: now };
      persist();
    },
  };
}

export type SessionController = ReturnType<typeof createSessionController>;
