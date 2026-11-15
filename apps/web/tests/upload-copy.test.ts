import assert from "node:assert/strict";
import { test } from "node:test";
import { copy } from "../lib/copy.ts";

test("upload states use the confirmed composer copy", () => {
  assert.equal(copy.attachmentUploading, "上传中…");
  assert.equal(copy.attachmentReady, "已上传，待发送");
  assert.equal(copy.attachmentType, "只接受 JPEG 或 PNG。");
  assert.equal(copy.attachmentTooBig, "图片超过 5 MB，未上传。");
  assert.doesNotMatch(Object.values(copy).join("\n"), /转写中|加入/);
});
