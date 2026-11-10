import { closeTrackedVaults } from "./chat-vault";

export const WIPE_TIMEOUT_MS = 4_000;

export class BrowserWipeError extends Error {
  readonly reason: "blocked" | "timeout" | "failed";

  constructor(reason: "blocked" | "timeout" | "failed") {
    super(reason);
    this.name = "BrowserWipeError";
    this.reason = reason;
  }
}

export type DatabaseDeleteHandle = {
  onsuccess: (() => void) | null;
  onerror: (() => void) | null;
  onblocked: (() => void) | null;
};

/** onblocked only means another connection is still open. The delete counts when onsuccess fires. */
export function awaitDatabaseDelete(request: DatabaseDeleteHandle, timeoutMs = WIPE_TIMEOUT_MS) {
  return new Promise<void>((resolve, reject) => {
    let settled = false;
    let blocked = false;
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn();
    };
    const timer = setTimeout(() => finish(() => reject(new BrowserWipeError(blocked ? "blocked" : "timeout"))), timeoutMs);
    request.onsuccess = () => finish(() => resolve());
    request.onerror = () => finish(() => reject(new BrowserWipeError("failed")));
    request.onblocked = () => {
      blocked = true;
    };
  });
}

export async function wipeBrowserState() {
  const drop = (storage: Storage) => {
    const keys: string[] = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key?.startsWith("mimi.web.")) keys.push(key);
    }
    for (const key of keys) storage.removeItem(key);
  };
  drop(window.localStorage);
  drop(window.sessionStorage);
  if (typeof caches !== "undefined") {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => name.startsWith("mimi.web.")).map((name) => caches.delete(name)));
  }
  closeTrackedVaults();
  const known = ["mimi.web.vault.v1"];
  const listed = indexedDB.databases ? await indexedDB.databases() : [];
  for (const db of listed) if (db.name) known.push(db.name);
  const names = [...new Set(known)].filter((name) => name.startsWith("mimi.web."));
  for (const name of names) {
    const request = indexedDB.deleteDatabase(name);
    await awaitDatabaseDelete(request as unknown as DatabaseDeleteHandle);
  }
}
