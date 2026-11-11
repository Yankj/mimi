"use client";

import { useEffect, useRef, useState } from "react";
import { copy } from "@/lib/copy";
import {
  canStartDictation,
  createDictationSession,
  isDictationSupported,
  readDictationConsent,
  speechRecognitionCtor,
  writeDictationConsent,
  type DictationConsent,
} from "@/lib/dictation";

export function ComposerDictationButton({
  subject,
  draft,
  onDraftChange,
}: {
  subject: string;
  draft: string;
  onDraftChange: (value: string) => void;
}) {
  const [supported, setSupported] = useState(false);
  const [consent, setConsent] = useState<DictationConsent>("unset");
  const [listening, setListening] = useState(false);
  const [privacyOpen, setPrivacyOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const sessionRef = useRef<ReturnType<typeof createDictationSession> | null>(null);
  const draftRef = useRef(draft);
  draftRef.current = draft;

  useEffect(() => {
    setSupported(isDictationSupported(window));
    setConsent(readDictationConsent(window.localStorage, subject));
  }, [subject]);

  useEffect(() => () => { sessionRef.current?.abort(); }, []);

  useEffect(() => {
    const stop = () => { if (sessionRef.current) sessionRef.current.stop(); };
    const onHide = () => { if (document.visibilityState === "hidden") stop(); };
    document.addEventListener("visibilitychange", onHide);
    return () => document.removeEventListener("visibilitychange", onHide);
  }, []);

  if (!supported || consent === "deny") return null;

  function showNotice(message: string) {
    setNotice(message);
    window.setTimeout(() => setNotice((current) => (current === message ? null : current)), 4000);
  }

  function decide(next: DictationConsent) {
    writeDictationConsent(window.localStorage, subject, next);
    setConsent(next);
    setPrivacyOpen(false);
    if (next === "allow") startListening();
  }

  function startListening() {
    const ctor = speechRecognitionCtor(window);
    if (!ctor || !canStartDictation("allow", true)) return;
    sessionRef.current?.abort();
    const session = createDictationSession(ctor, {
      onTranscript: (value) => onDraftChange(value.draft),
      onError: (error) => {
        setListening(false);
        showNotice(error === "not-allowed" ? copy.dictationPermission : copy.dictationFailed);
      },
      onEnd: () => setListening(false),
    });
    sessionRef.current = session;
    try {
      session.start(draftRef.current);
      setListening(true);
      setNotice(null);
    } catch {
      setListening(false);
      showNotice(copy.dictationFailed);
    }
  }

  function toggle() {
    if (listening) {
      sessionRef.current?.stop();
      setListening(false);
      return;
    }
    if (consent !== "allow") {
      setPrivacyOpen(true);
      return;
    }
    startListening();
  }

  return (
    <>
      <span className="dictation-wrap">
        <button
          type="button"
          className="icon-button"
          aria-label={listening ? copy.dictationStop : copy.dictation}
          aria-pressed={listening}
          onClick={toggle}
        >
          <span aria-hidden="true">{listening ? "■" : "◎"}</span>
        </button>
        {notice ? <p className="error dictation-notice" role="status">{notice}</p> : null}
      </span>
      {privacyOpen ? (
        <div className="dialog-back" role="dialog" aria-modal="true" aria-labelledby="dictation-privacy-title">
          <div className="dialog">
            <h2 id="dictation-privacy-title">{copy.dictationPrivacyTitle}</h2>
            <p>{copy.dictationPrivacyBody}</p>
            <div className="actions">
              <button className="primary" type="button" onClick={() => decide("allow")}>{copy.dictationAllow}</button>
              <button type="button" onClick={() => decide("deny")}>{copy.dictationTypeOnly}</button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
