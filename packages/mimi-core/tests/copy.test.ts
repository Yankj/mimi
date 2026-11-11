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
  assert.equal(copy.dictation, "听写");
  assert.match(copy.dictationPrivacyBody, /小咪不会把录音发到自己的服务器/);
  assert.equal(copy.deviceIcsReceipt, "已下载日历文件，请在日历应用中打开");
  assert.equal(starters.length, 6);
  assert.deepEqual(orderedStarters(8).map((item) => item.id), ["schedule", "reminder", "meeting", "nearby", "breakdown", "writing"]);
  assert.deepEqual(orderedStarters(14).map((item) => item.id), ["meeting", "schedule", "nearby", "reminder", "breakdown", "writing"]);
  assert.deepEqual(orderedStarters(20).map((item) => item.id), ["reminder", "schedule", "breakdown", "meeting", "writing", "nearby"]);
});
