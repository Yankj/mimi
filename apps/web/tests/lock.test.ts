import assert from "node:assert/strict";
import test from "node:test";
import { lockIsActive, reauthPrompt, unlockDecision, unlockOutcome } from "../lib/lock.ts";
import { authorizeUrl } from "../lib/wire.ts";

const config = {
  supabaseUrl: "https://abcdefghijklmnopqrst.supabase.co",
  anonKey: ["sb", "publishable", "a".repeat(24)].join("_"),
  edgeOrigin: "https://abcdefghijklmnopqrst.supabase.co/functions/v1/catpaw-api",
};

test("unlock accepts only the same user and wipes a different account", () => {
  const same = "11111111-1111-1111-1111-111111111111";
  assert.equal(unlockDecision(same, same.toUpperCase()), "same-user");
  assert.equal(unlockOutcome(same, same).wipeLocal, false);
  const other = "22222222-2222-2222-2222-222222222222";
  assert.equal(unlockDecision(same, other), "different-user");
  assert.deepEqual(unlockOutcome(same, other), { decision: "different-user", userId: other, wipeLocal: true });
  assert.equal(unlockDecision("", same), "different-user");
});

test("re-auth asks Google and GitHub to show an account chooser", () => {
  assert.equal(reauthPrompt("google"), "select_account");
  assert.equal(reauthPrompt("github"), "select_account");
  const url = new URL(authorizeUrl(config, "google", "challenge-value", "https://web.example/auth/callback", reauthPrompt("google")));
  assert.equal(url.searchParams.get("prompt"), "select_account");
  assert.equal(url.searchParams.get("provider"), "google");
  const plain = new URL(authorizeUrl(config, "github", "challenge-value", "https://web.example/auth/callback"));
  assert.equal(plain.searchParams.get("prompt"), null);
});

test("a lock marker stays active until its short window ends", () => {
  assert.equal(lockIsActive(2_000, 1_000), true);
  assert.equal(lockIsActive(1_000, 1_000), false);
  assert.equal(lockIsActive(undefined, 1_000), false);
});
