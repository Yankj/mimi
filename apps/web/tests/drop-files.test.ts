import assert from "node:assert/strict";
import { test } from "node:test";
import { firstDroppedImage, hasFileDrag, isImageFile } from "../lib/drop-files.ts";

test("the composer only accepts the first jpeg or png from a drop", () => {
  assert.equal(hasFileDrag(["Files", "text/plain"]), true);
  assert.equal(hasFileDrag(["text/plain"]), false);
  assert.equal(hasFileDrag({ contains: (type: string) => type === "Files", length: 1, 0: "Files" }), true);
  assert.equal(isImageFile({ type: "image/png", name: "a.png" }), true);
  assert.equal(isImageFile({ type: "image/jpeg", name: "a.jpg" }), true);
  assert.equal(isImageFile({ type: "", name: "photo.PNG" }), true);
  assert.equal(isImageFile({ type: "application/pdf", name: "doc.pdf" }), false);
  const picked = firstDroppedImage([
    { type: "application/pdf", name: "note.pdf" },
    { type: "image/jpeg", name: "one.jpg" },
    { type: "image/png", name: "two.png" },
  ]);
  assert.equal(picked?.file.name, "one.jpg");
  assert.equal(picked?.extraImages, 1);
  assert.equal(firstDroppedImage([{ type: "text/plain", name: "a.txt" }]), null);
});
