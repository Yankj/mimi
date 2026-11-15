import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { copy } from "../lib/copy.ts";
import {
  reminderFire,
  sharedTodayReminderSource,
  todayReminderView,
  type ConfirmedArrangement,
} from "../lib/today-reminders.ts";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const zone = "Asia/Shanghai";
const now = new Date("2026-11-15T04:00:00.000Z");

function at(hour: number, minute: number, day = 15): string {
  const utcHour = hour - 8;
  const date = utcHour >= 0 ? day : day - 1;
  const hh = String((utcHour + 24) % 24).padStart(2, "0");
  return `2026-11-${String(date).padStart(2, "0")}T${hh}:${String(minute).padStart(2, "0")}:00.000Z`;
}

function item(id: string, title: string, dueAt: string): ConfirmedArrangement {
  return { id, title, dueAt };
}

test("family sync off hides the today-reminder block", () => {
  const view = todayReminderView(sharedTodayReminderSource(), now, zone);
  assert.equal(view.visible, false);
});

test("sync on with nothing today keeps the title path empty and does not invent rows", () => {
  const view = todayReminderView({ kind: "synced", arrangements: [] }, now, zone);
  assert.equal(view.visible, true);
  assert.deepEqual(view.rows, []);
  assert.equal(view.empty, copy.todayRemindersEmpty);
  assert.equal(copy.todayRemindersPhone, "准时提醒请用手机。");
  assert.match(copy.todayRemindersFootnote, /关页后不能保证按时送达/);
});

test("a 21:30 fire moves to 20:30 the same evening and the day still lists at most two", () => {
  const quiet = reminderFire(new Date(at(22, 30)), "hour1", zone);
  assert.ok(quiet);
  assert.equal(quiet.moved, true);
  assert.equal(quiet.at.toISOString(), "2026-11-15T12:30:00.000Z");

  const view = todayReminderView({
    kind: "synced",
    lead: "hour1",
    arrangements: [
      item("book", "读绘本", at(22, 30)),
      item("pickup", "接放学", at(17, 0)),
      item("blank", "  ", at(18, 0)),
      item("past", "已过", at(11, 0)),
    ],
  }, now, zone);

  assert.equal(view.visible, true);
  assert.deepEqual(view.rows.map((row) => row.id), ["pickup", "book"]);
  assert.equal(view.rows[0].detail, "16:00 前提醒 · 每天最多 2 条");
  assert.equal(view.rows[1].detail, "本应 21:30，已挤到今晚不发，改在前一晚 20:30 提醒");

  const crowded = todayReminderView({
    kind: "synced",
    lead: "hour1",
    arrangements: [
      item("book", "读绘本", at(22, 30)),
      item("pickup", "接放学", at(17, 0)),
      item("extra", "钢琴", at(19, 0)),
    ],
  }, now, zone);
  assert.deepEqual(crowded.rows.map((row) => row.id), ["pickup", "extra"]);

  const afterQuietMove = todayReminderView({
    kind: "synced",
    lead: "hour1",
    arrangements: [item("book", "读绘本", at(22, 30))],
  }, new Date("2026-11-15T13:00:00.000Z"), zone);
  assert.equal(afterQuietMove.rows[0].detail, "本应 21:30，已挤到今晚不发，改在前一晚 20:30 提醒过");
});

test("three reminders on the same day keep the first two", () => {
  const view = todayReminderView({
    kind: "synced",
    lead: "hour1",
    arrangements: [
      item("c", "第三件", at(18, 0)),
      item("a", "第一件", at(15, 0)),
      item("b", "第二件", at(16, 0)),
    ],
  }, now, zone);
  assert.deepEqual(view.rows.map((row) => row.title), ["第一件", "第二件"]);
  assert.equal(view.rows[0].detail, "14:00 前提醒 · 每天最多 2 条");
  assert.equal(view.rows[1].detail, "15:00 前提醒 · 每天最多 2 条");
});

test("08:00 is outside quiet hours and 21:00 moves to 20:30 the same evening", () => {
  const morning = reminderFire(new Date(at(9, 0)), "hour1", zone);
  const night = reminderFire(new Date(at(22, 0)), "hour1", zone);
  const early = reminderFire(new Date(at(8, 30)), "hour1", zone);
  assert.equal(morning?.moved, false);
  assert.equal(morning?.at.toISOString(), at(8, 0));
  assert.equal(night?.moved, true);
  assert.equal(night?.at.toISOString(), "2026-11-15T12:30:00.000Z");
  assert.equal(early?.moved, true);
  assert.equal(early?.at.toISOString(), "2026-11-14T12:30:00.000Z");
});

test("the reminder files do not impersonate a system notification", () => {
  const files = [
    "lib/today-reminders.ts",
    "components/today-reminders.tsx",
    "components/app-shell.tsx",
  ];
  const banned = ["Notification", "serviceWorker", "PushManager", "showNotification", "requestPermission", "localStorage", "indexedDB"];
  for (const file of files) {
    const text = readFileSync(join(root, file), "utf8");
    for (const token of banned) assert.equal(text.includes(token), false, `${file} contains ${token}`);
  }
  const component = readFileSync(join(root, "components/today-reminders.tsx"), "utf8");
  assert.match(component, /if \(!view\.visible\) return hiddenNote/);
  assert.equal(component.includes("todayReminders"), true);
  const shell = readFileSync(join(root, "components/app-shell.tsx"), "utf8");
  assert.match(shell, /hiddenNote=\{<p className="card reminder-note">\{copy\.reminderPage\}<\/p>\}/);
  assert.equal(component.includes("role=\"alert\""), false);
  assert.equal(shell.split("<TodayReminders").length - 1, 2);
});
