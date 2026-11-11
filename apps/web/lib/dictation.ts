/** Optional Web Speech API dictation. Off by default; never starts without an explicit first-use consent. */

export type DictationConsent = "unset" | "allow" | "deny";

export type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: SpeechRecognitionResultEventLike) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
};

export type SpeechRecognitionResultEventLike = {
  resultIndex: number;
  results: ArrayLike<{ isFinal: boolean; length: number } & { [index: number]: { transcript: string } }>;
};

type SpeechWindow = {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
};

export function dictationConsentKey(subject: string) {
  return `mimi.web.dictationConsent.v1.${subject}`;
}

export function readDictationConsent(storage: Pick<Storage, "getItem">, subject: string): DictationConsent {
  const value = storage.getItem(dictationConsentKey(subject));
  if (value === "allow" || value === "deny") return value;
  return "unset";
}

export function writeDictationConsent(storage: Pick<Storage, "setItem" | "removeItem">, subject: string, value: DictationConsent) {
  const key = dictationConsentKey(subject);
  if (value === "unset") storage.removeItem(key);
  else storage.setItem(key, value);
}

export function speechRecognitionCtor(world: object | undefined | null): (new () => SpeechRecognitionLike) | null {
  if (!world) return null;
  const record = world as SpeechWindow;
  return record.SpeechRecognition ?? record.webkitSpeechRecognition ?? null;
}

export function isDictationSupported(world: object | undefined | null = typeof window === "undefined" ? undefined : window) {
  return speechRecognitionCtor(world) !== null;
}

/** Dictation may start only after the user has accepted the first-use privacy notice. */
export function canStartDictation(consent: DictationConsent, supported: boolean) {
  return supported && consent === "allow";
}

export function transcriptFromResult(event: SpeechRecognitionResultEventLike, fromIndex = 0) {
  let text = "";
  for (let index = fromIndex; index < event.results.length; index += 1) {
    const result = event.results[index];
    text += result[0]?.transcript ?? "";
  }
  return text;
}

export function mergeDictationDraft(prefix: string, transcript: string) {
  if (!prefix) return transcript;
  if (!transcript) return prefix;
  const gap = prefix.endsWith(" ") || transcript.startsWith(" ") ? "" : " ";
  return `${prefix}${gap}${transcript}`;
}

export function createDictationSession(
  ctor: new () => SpeechRecognitionLike,
  options: {
    lang?: string;
    onTranscript: (text: { prefix: string; transcript: string; draft: string; isFinal: boolean }) => void;
    onError: (error: string) => void;
    onEnd: () => void;
  },
) {
  const recognition = new ctor();
  recognition.lang = options.lang ?? "zh-CN";
  recognition.continuous = true;
  recognition.interimResults = true;
  const prefix = { value: "" };
  recognition.onresult = (event) => {
    const transcript = transcriptFromResult(event, 0);
    const last = event.results[event.results.length - 1];
    options.onTranscript({
      prefix: prefix.value,
      transcript,
      draft: mergeDictationDraft(prefix.value, transcript),
      isFinal: last?.isFinal === true,
    });
  };
  recognition.onerror = (event) => options.onError(event.error);
  recognition.onend = () => options.onEnd();
  return {
    recognition,
    setPrefix(value: string) {
      prefix.value = value;
    },
    start(currentDraft: string) {
      prefix.value = currentDraft;
      recognition.start();
    },
    stop() {
      recognition.stop();
    },
    abort() {
      recognition.abort();
    },
  };
}
