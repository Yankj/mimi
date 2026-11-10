import assert from "node:assert/strict";
import { test } from "node:test";
import { IDLE_MS } from "../miniprogram/utils/config.ts";
import { createIdleWatch } from "../miniprogram/utils/idle-watch.ts";
import { createPrivacyMask } from "../miniprogram/utils/privacy-mask.ts";
import { createMemoryStorage, createSessionController } from "../miniprogram/utils/session.ts";

function fakeTimers() {
  let fn: (() => void) | null = null;
  return {
    timers: {
      setInterval: (cb: () => void) => {
        fn = cb;
        return 1;
      },
      clearInterval: () => {
        fn = null;
      },
    },
    fire: () => fn?.(),
    active: () => fn !== null,
  };
}

test("an untouched foreground screen locks at fifteen minutes without onShow", () => {
  let clock = 10_000_000;
  let locks = 0;
  let routed = 0;
  const session = createSessionController(createMemoryStorage(), { onLock: () => (locks += 1) });
  session.signInMock(clock);
  const t = fakeTimers();
  const watch = createIdleWatch({ session, onLocked: () => (routed += 1), now: () => clock, timers: t.timers });
  watch.start();
  clock += IDLE_MS - 1;
  t.fire();
  assert.equal(session.isLocked(), false);
  clock += 1;
  t.fire();
  assert.equal(session.isLocked(), true);
  assert.equal(locks, 1);
  assert.equal(routed, 1);
  assert.equal(t.active(), false);
});

test("foreground checks do not count as activity, but sending does", () => {
  let clock = 20_000_000;
  const session = createSessionController(createMemoryStorage());
  session.signInMock(clock);
  const t = fakeTimers();
  const watch = createIdleWatch({ session, onLocked: () => {}, now: () => clock, timers: t.timers });
  watch.start();
  clock += IDLE_MS - 10;
  t.fire();
  session.touch(clock);
  clock += IDLE_MS - 10;
  t.fire();
  assert.equal(session.isLocked(), false);
  clock += 10;
  t.fire();
  assert.equal(session.isLocked(), true);
});

test("privacy mask covers every page on hide and reveals on show", () => {
  const pages = [0, 1].map(() => {
    const page = { data: {} as Record<string, unknown>, setData(d: Record<string, unknown>) { Object.assign(page.data, d); } };
    return page;
  });
  const mask = createPrivacyMask(() => pages);
  mask.cover();
  assert.equal(mask.isMasked(), true);
  assert.deepEqual(pages.map((p) => p.data.privacyMask), [true, true]);
  mask.reveal();
  assert.deepEqual(pages.map((p) => p.data.privacyMask), [false, false]);
});

test("every page template carries the privacy mask", async () => {
  const { readdirSync, readFileSync } = await import("node:fs");
  const root = new URL("../miniprogram/pages/", import.meta.url);
  for (const name of readdirSync(root)) {
    const wxml = readFileSync(new URL(`${name}/${name}.wxml`, root), "utf8");
    assert.match(wxml, /wx:if="\{\{privacyMask\}\}" class="privacy-mask"/, name);
  }
});
