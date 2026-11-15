import assert from "node:assert/strict";
import { test } from "node:test";
import { wipeWebStorage } from "../lib/browser-wipe.ts";
import {
  canStartDictation,
  dictationConsentKey,
  createDictationSession,
  dictationPartsFromResult,
  isDictationSupported,
  mergeDictationDraft,
  readDictationConsent,
  speechRecognitionCtor,
  transcriptFromResult,
  writeDictationConsent,
  type SpeechRecognitionLike,
  type SpeechRecognitionResultEventLike,
} from "../lib/dictation.ts";

test("dictation is off until the first-use privacy notice is accepted", () => {
  const storage = memoryStorage();
  assert.equal(readDictationConsent(storage, "user-1"), "unset");
  assert.equal(canStartDictation("unset", true), false);
  assert.equal(canStartDictation("deny", true), false);
  assert.equal(canStartDictation("allow", false), false);
  assert.equal(canStartDictation("allow", true), true);
  writeDictationConsent(storage, "user-1", "allow");
  assert.equal(readDictationConsent(storage, "user-1"), "allow");
  writeDictationConsent(storage, "user-1", "deny");
  assert.equal(canStartDictation(readDictationConsent(storage, "user-1"), true), false);
});

test("sign-out clears the dictation consent record", () => {
  const storage = memoryStorage();
  writeDictationConsent(storage, "user-1", "allow");
  assert.equal(readDictationConsent(storage, "user-1"), "allow");
  assert.ok(storage.getItem(dictationConsentKey("user-1"))?.startsWith("allow"));
  wipeWebStorage(storage);
  assert.equal(storage.getItem(dictationConsentKey("user-1")), null);
  assert.equal(readDictationConsent(storage, "user-1"), "unset");
  assert.equal(canStartDictation(readDictationConsent(storage, "user-1"), true), false);
});

test("feature detection uses SpeechRecognition or webkitSpeechRecognition and hides otherwise", () => {
  assert.equal(isDictationSupported({}), false);
  assert.equal(isDictationSupported(undefined), false);
  assert.ok(speechRecognitionCtor({ webkitSpeechRecognition: FakeRecognition }) === FakeRecognition);
  assert.equal(isDictationSupported({ SpeechRecognition: FakeRecognition }), true);
});

test("a session writes transcripts into the existing draft and does not start without start()", () => {
  const events: string[] = [];
  const session = createDictationSession(FakeRecognition, {
    onTranscript: (value) => events.push(value.draft),
    onError: (error) => events.push(`error:${error}`),
    onEnd: () => events.push("end"),
  });
  assert.equal(session.recognition.lang, "zh-CN");
  assert.equal(session.recognition.continuous, true);
  assert.equal((session.recognition as FakeRecognition).started, false);
  session.start("先写好的草稿");
  assert.equal((session.recognition as FakeRecognition).started, true);
  (session.recognition as FakeRecognition).emit("今天下午接孩子");
  assert.equal(events.at(-1), "先写好的草稿 今天下午接孩子");
  session.stop();
  assert.ok(events.includes("end"));
});

test("recognition results concatenate and merge with a draft prefix", () => {
  const event: SpeechRecognitionResultEventLike = {
    resultIndex: 0,
    results: [
      Object.assign([{ transcript: "今天" }], { isFinal: true, length: 1 }),
      Object.assign([{ transcript: "去公园" }], { isFinal: false, length: 1 }),
    ],
  };
  assert.equal(transcriptFromResult(event), "今天去公园");
  assert.deepEqual(dictationPartsFromResult(event), { finalText: "今天", interimText: "去公园", transcript: "今天去公园" });
  assert.equal(mergeDictationDraft("", "今天"), "今天");
  assert.equal(mergeDictationDraft("已有", "补充"), "已有 补充");
  assert.equal(mergeDictationDraft("已有 ", "补充"), "已有 补充");
});

test("interim text is written into the draft and there is no separate transcribing phase", () => {
  const seen: { draft: string; interim: string }[] = [];
  const session = createDictationSession(FakeRecognition, {
    onTranscript: (value) => seen.push({ draft: value.draft, interim: value.interim }),
    onError: () => undefined,
    onEnd: () => undefined,
  });
  session.start("周六春游要带");
  (session.recognition as FakeRecognition).emitParts([
    { transcript: "水壶", isFinal: true },
    { transcript: "和帽子", isFinal: false },
  ]);
  assert.deepEqual(seen.at(-1), { draft: "周六春游要带 水壶和帽子", interim: "和帽子" });
  assert.equal("transcribing" in session, false);
});

class FakeRecognition implements SpeechRecognitionLike {
  lang = "";
  continuous = false;
  interimResults = false;
  onresult: ((event: SpeechRecognitionResultEventLike) => void) | null = null;
  onerror: ((event: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  started = false;
  start() { this.started = true; }
  stop() { this.started = false; this.onend?.(); }
  abort() { this.started = false; this.onend?.(); }
  emit(transcript: string, isFinal = false) {
    this.emitParts([{ transcript, isFinal }]);
  }
  emitParts(parts: { transcript: string; isFinal: boolean }[]) {
    this.onresult?.({
      resultIndex: 0,
      results: parts.map((part) => Object.assign([{ transcript: part.transcript }], { isFinal: part.isFinal, length: 1 })),
    });
  }
}

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() { return data.size; },
    clear() { data.clear(); },
    getItem(key: string) { return data.has(key) ? data.get(key)! : null; },
    key(index: number) { return [...data.keys()][index] ?? null; },
    removeItem(key: string) { data.delete(key); },
    setItem(key: string, value: string) { data.set(key, value); },
  };
}
