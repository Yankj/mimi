import assert from "node:assert/strict";
import test from "node:test";
import { BrowserWipeError, awaitDatabaseDelete, type DatabaseDeleteHandle } from "../lib/browser-wipe.ts";

function handle(): DatabaseDeleteHandle {
  return { onsuccess: null, onerror: null, onblocked: null };
}

test("a blocked database delete waits for success instead of resolving immediately", async () => {
  const request = handle();
  let settled = false;
  const pending = awaitDatabaseDelete(request, 200).then(
    () => {
      settled = true;
      return "ok" as const;
    },
    () => {
      settled = true;
      return "err" as const;
    },
  );
  request.onblocked?.();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(settled, false);
  request.onsuccess?.();
  assert.equal(await pending, "ok");
});

test("a blocked database delete that never finishes rejects", async () => {
  const request = handle();
  const pending = awaitDatabaseDelete(request, 30);
  request.onblocked?.();
  await assert.rejects(pending, (error: unknown) => error instanceof BrowserWipeError && error.reason === "blocked");
});
