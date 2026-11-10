import assert from "node:assert/strict";
import test from "node:test";
import { createChatKey, openJson, sealJson } from "../lib/chat-vault.ts";

function contains(haystack: Uint8Array, needle: Uint8Array) {
  if (needle.length === 0 || haystack.length < needle.length) return false;
  for (let index = 0; index <= haystack.length - needle.length; index += 1) {
    let matched = true;
    for (let offset = 0; offset < needle.length; offset += 1) {
      if (haystack[index + offset] !== needle[offset]) matched = false;
    }
    if (matched) return true;
  }
  return false;
}

test("chat bodies seal with a non-extractable AES-GCM key", async () => {
  const key = await createChatKey();
  assert.equal(key.extractable, false);
  assert.equal(key.algorithm.name, "AES-GCM");
  await assert.rejects(() => crypto.subtle.exportKey("raw", key));
  const text = "橘色小猫画作七号";
  const sealed = await sealJson(key, { messages: [{ text }] });
  assert.equal(contains(sealed.ciphertext, new TextEncoder().encode(text)), false);
  const opened = await openJson(key, sealed.iv, sealed.ciphertext) as { messages: { text: string }[] };
  assert.equal(opened.messages[0].text, text);
});
