import assert from "node:assert/strict";
import { test } from "node:test";
import { copy, orderedStarters, starters } from "../src/copy.ts";

test("shared copy keeps the iOS parenting tab and empty-state wording", () => {
  assert.equal(copy.today, "今天");
  assert.equal(copy.growth, "成长");
  assert.equal(copy.family, "我们家");
  assert.equal(copy.addChild, "添加孩子");
  assert.match(copy.addChildBlocked, /不保存、不同步任何孩子资料/);
  assert.equal(copy.emptyToday, "今天还没有安排。直接对小咪说一件事就行。");
  assert.equal(copy.todayRemindersEmpty, "今天没有要提醒的安排。");
  assert.equal(copy.todayRemindersPhone, "准时提醒请用手机。");
  assert.equal(copy.reminderPage, "这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。");
  assert.equal(starters.length, 6);
  assert.deepEqual(orderedStarters(8).map((item) => item.id), ["schedule", "reminder", "meeting", "nearby", "breakdown", "writing"]);
  assert.deepEqual(orderedStarters(14).map((item) => item.id), ["meeting", "schedule", "nearby", "reminder", "breakdown", "writing"]);
  assert.deepEqual(orderedStarters(20).map((item) => item.id), ["reminder", "schedule", "breakdown", "meeting", "writing", "nearby"]);
});
