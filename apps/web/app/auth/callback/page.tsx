"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { copy } from "@/lib/copy";
import {
  clearStoredSession,
  establishSession,
  loadStoredSession,
  logoutStoredSession,
  readBrowserConfig,
  saveStoredSession,
} from "@/lib/browser-session";
import { ClientError, assertVerifier, credentialFromToken, parseOAuthCallback, pkceExchangeRequest } from "@/lib/wire";

const VERIFIER = "mimi.oauth.pkce";
let oauthCallbackStarted = false;

export default function AuthCallbackPage() {
  const [message, setMessage] = useState<string>(copy.entering);
  const [pending, setPending] = useState<string | null>(null);

  useEffect(() => {
    if (oauthCallbackStarted) return;
    oauthCallbackStarted = true;
    const verifier = sessionStorage.getItem(VERIFIER);
    sessionStorage.removeItem(VERIFIER);
    let parsed: ReturnType<typeof parseOAuthCallback>;
    try {
      parsed = parseOAuthCallback(window.location.href);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : copy.invalidResponse);
      return;
    }
    if (parsed.kind === "cancelled") {
      setMessage(copy.cancelled);
      return;
    }
    if (parsed.kind === "failure") {
      setMessage(copy.invalidResponse);
      return;
    }
    let config;
    try {
      config = readBrowserConfig();
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : copy.notConfigured);
      return;
    }
    if (!config) {
      setMessage(copy.notConfigured);
      return;
    }
    try {
      if (!verifier) throw new ClientError(copy.invalidResponse);
      assertVerifier(verifier);
    } catch (cause) {
      setMessage(cause instanceof Error ? cause.message : copy.invalidResponse);
      return;
    }
    const request = pkceExchangeRequest(config, parsed.code, verifier);
    void (async () => {
      try {
        const response = await fetch(request.url, { method: request.method, headers: request.headers, body: request.body, cache: "no-store", redirect: "manual" });
        const json = await response.json().catch(() => null);
        if (!response.ok) throw new ClientError(copy.invalidResponse);
        const credential = credentialFromToken(json);
        const next = await establishSession(config, credential);
        const current = loadStoredSession();
        if (current && current.userId !== next.userId) {
          sessionStorage.setItem("mimi.web.pending-oauth", JSON.stringify(next));
          setPending(next.email || next.userId);
          setMessage(copy.switchTitle);
          return;
        }
        saveStoredSession(next);
        window.location.replace("/");
      } catch (cause) {
        clearStoredSession();
        setMessage(cause instanceof Error ? cause.message : copy.invalidResponse);
      }
    })();
  }, []);

  async function confirmSwitch() {
    const raw = sessionStorage.getItem("mimi.web.pending-oauth");
    sessionStorage.removeItem("mimi.web.pending-oauth");
    const config = readBrowserConfig();
    const current = loadStoredSession();
    if (current && config) await logoutStoredSession(config, current.accessToken);
    else clearStoredSession();
    if (!raw) {
      window.location.replace("/");
      return;
    }
    saveStoredSession(JSON.parse(raw));
    window.location.replace("/");
  }

  return (
    <main className="login">
      <p className="brand">mimi</p>
      <h1>{copy.loginTitle}</h1>
      <p>{message}</p>
      {pending ? (
        <div className="stack">
          <p>{copy.signOutNamed}</p>
          <button className="primary" onClick={() => void confirmSwitch()}>{copy.switchConfirm}</button>
          <button onClick={() => { sessionStorage.removeItem("mimi.web.pending-oauth"); window.location.replace("/"); }}>{copy.cancel}</button>
        </div>
      ) : (
        <Link href="/">{copy.loginTitle}</Link>
      )}
    </main>
  );
}
