import assert from "node:assert/strict";
import test from "node:test";
import { upstreamRequestInit } from "../lib/upstream.ts";

test("upstream fetch keeps the caller signal so Stop aborts the SSE", () => {
  const controller = new AbortController();
  const init = upstreamRequestInit("POST", new Headers({ accept: "text/event-stream" }), null, controller.signal);
  assert.equal(init.signal, controller.signal);
  assert.equal(init.method, "POST");
  controller.abort();
  assert.equal((init.signal as AbortSignal).aborted, true);
});
