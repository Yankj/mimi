import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildEventIcs,
  deviceActionFromTool,
  findEntities,
  isSafeDeviceUrl,
  mailtoUrl,
  mapsUrl,
  parseDeviceDate,
  telUrl,
  WEB_DEVICE_TOOLS,
} from "../lib/device-actions.ts";

const now = new Date("2026-11-11T08:00:00+08:00");

test("tel, mailto and maps URLs match the iOS schemes on the web", () => {
  assert.equal(telUrl("138-0013-8000"), "tel:13800138000");
  assert.equal(telUrl("+86 13800138000"), "tel:+8613800138000");
  assert.equal(mailtoUrl({ to: "ada@example.com", subject: "你好", body: "在吗" }), "mailto:ada@example.com?subject=%E4%BD%A0%E5%A5%BD&body=%E5%9C%A8%E5%90%97");
  assert.equal(mapsUrl("望京 SOHO"), "https://maps.apple.com/?q=%E6%9C%9B%E4%BA%AC+SOHO");
  assert.equal(mapsUrl("望京 SOHO", "walk"), "https://maps.apple.com/?daddr=%E6%9C%9B%E4%BA%AC+SOHO&dirflg=w");
  assert.equal(isSafeDeviceUrl("tel:13800138000"), true);
  assert.equal(isSafeDeviceUrl("mailto:ada@example.com"), true);
  assert.equal(isSafeDeviceUrl("https://maps.apple.com/?q=park"), true);
  assert.equal(isSafeDeviceUrl("javascript:alert(1)"), false);
  assert.equal(isSafeDeviceUrl("https://evil.example/?q=park"), false);
});

test("assistant text yields phone, mail, map and calendar actions", () => {
  const text = "请打 +86 13800138000 给 Ada（ada@example.com）。明天下午 3 点在望京 SOHO 见面。";
  const found = findEntities(text, now);
  const types = found.map((item) => item.type);
  assert.deepEqual(types, ["phone", "email", "datetime", "address"]);
  const phone = found.find((item) => item.type === "phone");
  const email = found.find((item) => item.type === "email");
  const address = found.find((item) => item.type === "address");
  const when = found.find((item) => item.type === "datetime");
  assert.ok(phone && phone.url === "tel:+8613800138000");
  assert.ok(email && email.url.startsWith("mailto:ada@example.com"));
  assert.ok(address && address.type === "address" && address.url.includes("maps.apple.com"));
  assert.ok(when && when.type === "datetime");
  if (when?.type === "datetime") {
    const start = new Date(when.startAt);
    assert.equal(start.getHours(), 15);
    assert.equal(start.getDate(), 12);
    assert.match(when.ics, /BEGIN:VCALENDAR/);
    assert.match(when.ics, /BEGIN:VEVENT/);
    assert.match(when.ics, /SUMMARY:安排/);
    assert.doesNotMatch(when.ics, /已加入日历|已写入系统/);
  }
});

test("relative and ISO dates parse in the Asia/Shanghai civil day", () => {
  const tomorrow = parseDeviceDate("明天上午 10 点", now);
  assert.ok(tomorrow);
  assert.equal(tomorrow?.getFullYear(), 2026);
  assert.equal(tomorrow?.getMonth(), 10);
  assert.equal(tomorrow?.getDate(), 12);
  assert.equal(tomorrow?.getHours(), 10);
  const iso = parseDeviceDate("2026-11-13T18:00:00+08:00", now);
  assert.equal(iso?.toISOString(), "2026-11-13T10:00:00.000Z");
  const chinese = parseDeviceDate("2026年11月13日 18:00", now);
  assert.equal(chinese?.getDate(), 13);
  assert.equal(chinese?.getHours(), 18);
});

test("device tools map to confirm-then-open actions and never claim a system write", () => {
  const call = deviceActionFromTool("phone_call", { name: "Ada", number: "010-12345678" });
  assert.equal(call.kind, "link");
  if (call.kind === "link") assert.equal(call.url, "tel:01012345678");

  const mail = deviceActionFromTool("mail_compose", { to: "ada@example.com", subject: "周报" });
  assert.equal(mail.kind, "link");
  if (mail.kind === "link") assert.match(mail.url, /^mailto:ada@example.com/);

  const map = deviceActionFromTool("maps_open", { query: "望京 SOHO", mode: "transit" });
  assert.equal(map.kind, "link");
  if (map.kind === "link") assert.match(map.url, /dirflg=r/);

  const calendar = deviceActionFromTool("calendar_create", {
    title: "评审会",
    start: "2026-11-13T18:00:00+08:00",
    end: "2026-11-13T19:00:00+08:00",
    location: "望京 SOHO",
  }, now);
  assert.equal(calendar.kind, "ics");
  if (calendar.kind === "ics") {
    assert.equal(calendar.title, "加入日历");
    assert.equal(calendar.confirmLabel, "加入");
    assert.match(calendar.footnote, /不会直接写入系统日历/);
    assert.equal(calendar.receipt, "已下载日历文件，请在日历应用中打开");
    assert.match(calendar.ics, /SUMMARY:评审会/);
    assert.match(calendar.ics, /LOCATION:望京 SOHO/);
    assert.doesNotMatch(calendar.receipt, /已加入日历|已写入系统/);
  }

  const reminder = deviceActionFromTool("reminder_create", { title: "回电话", due: "2026-11-12T10:00:00+08:00" }, now);
  assert.equal(reminder.kind, "ics");

  for (const tool of ["sms_compose", "contacts_search", "alarm_set"] as const) {
    const action = deviceActionFromTool(tool, { to: "10086", query: "Ada", time: "2026-11-12T07:00:00+08:00", label: "起床" });
    assert.equal(action.kind, "phoneOnly");
    if (action.kind === "phoneOnly") assert.equal(action.message, "请在手机上完成");
  }

  assert.deepEqual([...WEB_DEVICE_TOOLS], [
    "phone_call", "mail_compose", "maps_open", "calendar_create",
    "reminder_create", "sms_compose", "contacts_search", "alarm_set",
  ]);
});

test("an ics document folds long lines and escapes commas", () => {
  const long = "安排：" + "内容，分号;反斜线\\以及很长的说明。".repeat(8);
  const ics = buildEventIcs({ title: long, start: new Date("2026-11-13T10:00:00Z"), stamp: new Date("2026-11-13T01:00:00Z") });
  assert.match(ics, /\\,|\\;|\\\\/);
  for (const line of ics.split("\r\n")) {
    if (!line) continue;
    assert.ok(Buffer.from(line, "utf8").length <= 75, line);
  }
});
