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
  onInterimChange,
}: {
  subject: string;
  draft: string;
  onDraftChange: (value: string) => void;
  onInterimChange?: (value: string) => void;
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
      onTranscript: (value) => {
        onDraftChange(value.draft);
        onInterimChange?.(value.interim);
      },
      onError: (error) => {
        setListening(false);
        onInterimChange?.("");
        showNotice(error === "not-allowed" ? copy.dictationPermission : copy.dictationFailed);
      },
      onEnd: () => {
        setListening(false);
        onInterimChange?.("");
      },
    });
    sessionRef.current = session;
    try {
      session.start(draftRef.current);
      setListening(true);
      setNotice(null);
    } catch {
      setListening(false);
      onInterimChange?.("");
      showNotice(copy.dictationFailed);
    }
  }

  function toggle() {
    if (listening) {
      sessionRef.current?.stop();
      setListening(false);
      onInterimChange?.("");
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
          {listening ? <StopIcon /> : <MicIcon />}
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

function MicIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="9" y="3.5" width="6" height="10" rx="3" />
      <path d="M6.5 11.5a5.5 5.5 0 0 0 11 0M12 17v3.5M9 20.5h6" />
    </svg>
  );
}

function StopIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor" />
    </svg>
  );
}
