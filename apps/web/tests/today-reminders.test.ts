import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  sharedTodayReminderSource,
  todayReminderCopy,
  todayReminderRows,
  todayReminderView,
  type TodayReminderRecord,
} from "../lib/today-reminders.ts";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const now = new Date("2026-11-15T12:00:00.000Z");
const zone = "UTC";

function record(partial: Partial<TodayReminderRecord> & Pick<TodayReminderRecord, "id" | "fireAt">): TodayReminderRecord {
  return {
    title: "游泳",
    childName: "团团",
    dueAt: "2026-11-15T13:00:00.000Z",
    ...partial,
  };
}

test("the page source is unsynced and renders an empty in-page list", () => {
  const view = todayReminderView(sharedTodayReminderSource(), now, zone);
  assert.equal(view.availability, "unsynced");
  assert.deepEqual(view.rows, []);
  assert.equal(view.empty, todayReminderCopy.emptyUnsynced);
  assert.match(view.note, /不会申请通知权限/);
  assert.match(view.boundary, /不会在浏览器里另记一份/);
});

test("a supplied list keeps only reminders that fire on the local day, in fire order", () => {
  const rows = todayReminderRows({
    kind: "listed",
    records: [
      record({ id: "later", fireAt: "2026-11-15T15:00:00.000Z", dueAt: "2026-11-15T16:00:00.000Z", title: "绘本" }),
      record({ id: "other-day", fireAt: "2026-11-16T09:00:00.000Z" }),
      record({ id: "yesterday", fireAt: "2026-11-14T09:00:00.000Z" }),
      record({ id: "blank", fireAt: "2026-11-15T08:00:00.000Z", title: "  " }),
      record({ id: "bad", fireAt: "not-a-date" }),
      record({ id: "b", fireAt: "2026-11-15T09:00:00.000Z", title: "钢琴" }),
      record({ id: "a", fireAt: "2026-11-15T09:00:00.000Z", title: "画画", childName: " 豆豆 " }),
    ],
  }, now, zone);
  assert.deepEqual(rows.map((row) => row.id), ["a", "b", "later"]);
  assert.equal(rows[0].title, "画画");
  assert.equal(rows[0].detail, "豆豆 · 将在 11月15日 09:00 提醒 · 11月15日 13:00 开始");
});

test("the web reminder files do not impersonate a system notification", () => {
  const files = [
    "lib/today-reminders.ts",
    "components/today-reminders.tsx",
    "components/app-shell.tsx",
  ];
  const banned = [
    "Notification",
    "serviceWorker",
    "PushManager",
    "showNotification",
    "requestPermission",
    "localStorage",
    "indexedDB",
    "family/v1",
  ];
  for (const file of files) {
    const text = readFileSync(join(root, file), "utf8");
    for (const token of banned) {
      assert.equal(text.includes(token), false, `${file} contains ${token}`);
    }
  }
  const shell = readFileSync(join(root, "components/app-shell.tsx"), "utf8");
  assert.equal(shell.split("<TodayReminders").length - 1, 2);
});
