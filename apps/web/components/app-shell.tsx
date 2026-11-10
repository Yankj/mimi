"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import { ConversationWorkspace } from "@/components/conversation-workspace";
import { copy } from "@/lib/copy";
import {
  loadStoredSession,
  logoutStoredSession,
  readBrowserConfig,
  refreshStoredSession,
  saveStoredSession,
  clearStoredSession,
  type StoredSession,
} from "@/lib/browser-session";
import { authorizeUrl, createPkceVerifier, type PublicConfig } from "@/lib/wire";

type Tab = "today" | "growth" | "family";
type Page = "home" | "settings" | "reminders" | "timeline" | "goals";

export function AppShell() {
  const [session, setSession] = useState<StoredSession | null>(null);
  const [config, setConfig] = useState<PublicConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [phase, setPhase] = useState<"boot" | "login" | "home">("boot");
  const [notice, setNotice] = useState<string | null>(null);
  const [chat, setChat] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let configValue: PublicConfig | null = null;
    try {
      configValue = readBrowserConfig();
      setConfig(configValue);
    } catch (cause) {
      setConfigError(cause instanceof Error ? cause.message : copy.notConfigured);
    }
    if (sessionStorage.getItem("mimi.web.restart") === "1") {
      sessionStorage.removeItem("mimi.web.restart");
      setNotice(copy.restartNotice);
    }
    const stored = loadStoredSession();
    if (!stored) {
      setPhase("login");
      return;
    }
    if (!configValue || stored.expiresAt > Date.now() + 60_000) {
      setSession(stored);
      setPhase("home");
      if (!configValue) setConfigError(copy.notConfigured);
      return;
    }
    void refreshStoredSession(configValue, stored)
      .then((next) => {
        saveStoredSession(next);
        setSession(next);
        setPhase("home");
      })
      .catch(() => {
        clearStoredSession();
        sessionStorage.setItem("mimi.web.restart", "1");
        setNotice(copy.restartNotice);
        setPhase("login");
      });
  }, []);

  async function startOAuth(provider: "google" | "github") {
    if (!config || busy) {
      setNotice(configError || copy.notConfigured);
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const pkce = await createPkceVerifier();
      sessionStorage.setItem("mimi.oauth.pkce", pkce.verifier);
      const redirectTo = `${window.location.origin}/auth/callback`;
      window.location.assign(authorizeUrl(config, provider, pkce.challenge, redirectTo));
    } catch (cause) {
      setBusy(false);
      setNotice(cause instanceof Error ? cause.message : copy.invalidResponse);
    }
  }

  async function authorize() {
    if (!config || !session) throw new Error(copy.loginRequired);
    if (session.expiresAt > Date.now() + 60_000) return session.accessToken;
    const next = await refreshStoredSession(config, session);
    saveStoredSession(next);
    setSession(next);
    return next.accessToken;
  }

  async function signOut() {
    const current = session;
    if (config && current) await logoutStoredSession(config, current.accessToken);
    else clearStoredSession();
    setSession(null);
    setChat(false);
    setPhase("login");
  }

  function restart() {
    clearStoredSession();
    sessionStorage.setItem("mimi.web.restart", "1");
    setSession(null);
    setChat(false);
    setNotice(copy.restartNotice);
    setPhase("login");
  }

  if (phase === "boot") {
    return <main className="login"><p>{copy.restoring}</p></main>;
  }

  if (phase === "login" || !session) {
    return (
      <main className="login">
        <div>
          <p className="brand">mimi <span className="brand-sub">小咪</span></p>
          <p className="brand-sub">{copy.brandSubtitle}</p>
        </div>
        <div>
          <div className="mascot-wrap"><div className="mascot-orb"><Image src="/mascot/idle.png" alt="" width={180} height={180} priority /></div></div>
          <h1 className="headline">{copy.headline}</h1>
          <p className="quiet">{copy.subtitle}</p>
        </div>
        <div className="stack">
          <h2>{copy.loginTitle}</h2>
          <p className="quiet">{copy.oauthOnly}</p>
          {notice || configError ? <p className="error">{notice || configError}</p> : null}
          <button className="cta" disabled={!config || busy} onClick={() => void startOAuth("google")}>{copy.google}</button>
          <button className="cta" disabled={!config || busy} onClick={() => void startOAuth("github")}>{copy.github}</button>
        </div>
      </main>
    );
  }

  return (
    <div className="app-shell">
      <ParentingShell
        email={session.email}
        onSignOut={() => void signOut()}
        onOpenChat={() => {
          if (!config) setNotice(copy.notConfigured);
          else setChat(true);
        }}
      />
      {notice || configError ? <p className="error">{notice || configError}</p> : null}
      {chat && config ? (
        <ConversationWorkspace
          config={config}
          session={session}
          authorize={authorize}
          onSignOut={() => void signOut()}
          onRestart={restart}
          onClose={() => setChat(false)}
        />
      ) : null}
    </div>
  );
}

function ParentingShell({ email, onSignOut, onOpenChat }: { email: string | null; onSignOut: () => void; onOpenChat: () => void }) {
  const [tab, setTab] = useState<Tab>("today");
  const [page, setPage] = useState<Page>("home");
  const [addOpen, setAddOpen] = useState(false);
  const [signOutOpen, setSignOutOpen] = useState(false);

  return (
    <>
      <main className="page">
        <p className="todo">{copy.familyTodo}</p>
        {page === "home" ? (
          <>
            <h1>{tab === "today" ? copy.today : tab === "growth" ? copy.growth : copy.family}</h1>
            <EmptyChild onAdd={() => setAddOpen(true)} />
            {tab === "today" ? <p className="quiet">{copy.emptyToday}</p> : null}
            {tab === "growth" ? (
              <div className="stack">
                <button className="row-link" onClick={() => setPage("timeline")}><strong>{copy.timelineTitle}</strong><span className="quiet">{copy.noRecords}</span></button>
                <button className="row-link" onClick={() => setPage("goals")}><strong>{copy.goalsReview}</strong><span className="quiet">{copy.noGoals}</span></button>
              </div>
            ) : null}
            {tab === "family" ? (
              <div className="stack">
                <button className="row-link" onClick={() => setPage("reminders")}><strong>{copy.reminders}</strong><span className="quiet">{copy.reminderRow}</span></button>
                <button className="row-link" onClick={() => setPage("settings")}><strong>{copy.settings}</strong><span className="quiet">{copy.settingsSubtitle}</span></button>
              </div>
            ) : null}
          </>
        ) : null}
        {page === "timeline" ? (
          <section>
            <button className="text-button" onClick={() => setPage("home")}>{copy.growth}</button>
            <h1>{copy.timelineTitle}</h1>
            <p>{copy.timelineEmpty}</p>
          </section>
        ) : null}
        {page === "goals" ? (
          <section>
            <button className="text-button" onClick={() => setPage("home")}>{copy.growth}</button>
            <h1>{copy.goalsReview}</h1>
            <p>{copy.noGoals}</p>
          </section>
        ) : null}
        {page === "reminders" ? (
          <section>
            <button className="text-button" onClick={() => setPage("home")}>{copy.family}</button>
            <h1>{copy.reminders}</h1>
            <p>{copy.reminderPage}</p>
          </section>
        ) : null}
        {page === "settings" ? (
          <section className="stack">
            <button className="text-button" onClick={() => setPage("home")}>{copy.family}</button>
            <h1>{copy.settings}</h1>
            <p className="quiet">{copy.settingsSubtitle}</p>
            <section className="card">
              <h2>{copy.account}</h2>
              <p>{copy.currentAccount}</p>
              <p>{email || "mimi"}</p>
              <button className="danger" onClick={() => setSignOutOpen(true)}>{copy.signOut}</button>
            </section>
            <section className="card">
              <h2>{copy.reviewHints}</h2>
              <label><input type="checkbox" disabled /> {copy.showHints}</label>
              <p className="quiet">{copy.hintsBody}</p>
            </section>
            <section className="card">
              <h2>{copy.dataBoundary}</h2>
              <p>{copy.localLedger}</p>
              <p>{copy.noCalendar}</p>
              <p>{copy.chatBoundary}</p>
              <button disabled>{copy.reread}</button>
              <button disabled>{copy.eraseAll}</button>
            </section>
            <button className="row-link" onClick={onOpenChat}>
              <strong>{copy.advanced}</strong>
              <span className="quiet">{copy.advancedBody}</span>
            </button>
            <section className="card">
              <h2>{copy.about}</h2>
              <p>{copy.version} 0.1.0</p>
              <p>{copy.build} web-batch-1</p>
            </section>
          </section>
        ) : null}
      </main>
      {page === "home" ? (
        <nav className="tabbar" aria-label={copy.family}>
          {(["today", "growth", "family"] as Tab[]).map((item) => (
            <button key={item} role="tab" aria-selected={tab === item} onClick={() => setTab(item)}>
              {item === "today" ? copy.today : item === "growth" ? copy.growth : copy.family}
            </button>
          ))}
        </nav>
      ) : null}
      {addOpen ? (
        <div className="dialog-back" role="dialog" aria-label={copy.addChild}>
          <div className="dialog">
            <h2>{copy.addChildTitle}</h2>
            <p>{copy.addChildBody}</p>
            <p>{copy.addChildBlocked}</p>
            <p className="todo">{copy.familyTodo}</p>
            <button onClick={() => setAddOpen(false)}>{copy.close}</button>
          </div>
        </div>
      ) : null}
      {signOutOpen ? (
        <div className="dialog-back" role="dialog" aria-label={copy.signOutTitle}>
          <div className="dialog">
            <h2>{copy.signOutTitle}</h2>
            <p>{copy.signOutNamed}</p>
            <button onClick={() => setSignOutOpen(false)}>{copy.cancel}</button>
            <button className="danger" onClick={onSignOut}>{copy.signOut}</button>
          </div>
        </div>
      ) : null}
    </>
  );
}

function EmptyChild({ onAdd }: { onAdd: () => void }) {
  return (
    <section className="glass-card" aria-labelledby="empty-child">
      <h2 id="empty-child">{copy.addChildTitle}</h2>
      <p>{copy.addChildBody}</p>
      <button className="primary" onClick={onAdd}>{copy.addChild}</button>
    </section>
  );
}
