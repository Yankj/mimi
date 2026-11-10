"use client";

import Image from "next/image";
import { useCallback, useEffect, useRef, useState } from "react";
import { ConversationWorkspace } from "@/components/conversation-workspace";
import { wipeBrowserState } from "@/lib/browser-wipe";
import { copy } from "@/lib/copy";
import type { WebProfile } from "@/lib/profile";

type Tab = "today" | "growth" | "family";
type Page = "home" | "settings" | "reminders" | "timeline" | "goals";

const IDLE_MS = 15 * 60 * 1000;

export function AppShell() {
  const [session, setSession] = useState<WebProfile | null>(null);
  const [phase, setPhase] = useState<"boot" | "login" | "home">("boot");
  const [notice, setNotice] = useState<string | null>(null);
  const [chat, setChat] = useState(false);
  const [busy, setBusy] = useState(false);
  const [switchOpen, setSwitchOpen] = useState(false);
  const [locked, setLocked] = useState(false);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("notice") === "cancelled") setNotice(copy.cancelled);
    if (params.get("notice") === "restart") setNotice(copy.restartNotice);
    if (params.get("notice") === "invalid") setNotice(copy.invalidResponse);
    const switching = params.get("switch") === "1";
    const switchedAccount = params.get("reauth") === "switched";
    void (async () => {
      if (switchedAccount) await wipeBrowserState();
      const response = await fetch("/api/session", { cache: "no-store" });
      if (!response.ok) {
        setPhase("login");
        return;
      }
      const profile = (await response.json()) as WebProfile;
      setSession(profile);
      setLocked(profile.locked);
      setPhase("home");
      if (switching && !switchedAccount) setSwitchOpen(true);
    })().catch(() => setPhase("login"));
  }, []);

  async function startOAuth(provider: "google" | "github") {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const response = await fetch("/api/auth/start", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ provider }),
      });
      const json = (await response.json().catch(() => null)) as { url?: unknown; error?: unknown } | null;
      if (!response.ok || typeof json?.url !== "string") {
        setNotice(typeof json?.error === "string" ? json.error : copy.notConfigured);
        setBusy(false);
        return;
      }
      window.location.assign(json.url);
    } catch {
      setBusy(false);
      setNotice(copy.unavailable);
    }
  }

  const engageLock = useCallback(() => {
    setLocked(true);
    setChat(false);
    void fetch("/api/auth/lock", { method: "POST" });
  }, []);

  async function continueLocked() {
    if (!session?.provider || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const response = await fetch("/api/auth/start", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ provider: session.provider, reauth: true }),
      });
      const json = (await response.json().catch(() => null)) as { url?: unknown; error?: unknown } | null;
      if (!response.ok || typeof json?.url !== "string") {
        setNotice(typeof json?.error === "string" ? json.error : copy.loginRequired);
        setBusy(false);
        return;
      }
      window.location.assign(json.url);
    } catch {
      setBusy(false);
      setNotice(copy.unavailable);
    }
  }

  async function signOut() {
    await fetch("/api/auth/logout", { method: "POST" });
    await wipeBrowserState();
    setSession(null);
    setChat(false);
    setLocked(false);
    setSwitchOpen(false);
    setPhase("login");
  }

  function restart() {
    void fetch("/api/auth/logout", { method: "POST" })
      .then(() => wipeBrowserState())
      .finally(() => window.location.assign("/?notice=restart"));
  }

  async function confirmSwitch() {
    const response = await fetch("/api/auth/switch", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ confirm: true }),
    });
    if (!response.ok) {
      setNotice(copy.invalidResponse);
      return;
    }
    await wipeBrowserState();
    window.location.assign("/");
  }

  async function cancelSwitch() {
    await fetch("/api/auth/switch", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ confirm: false }),
    });
    setSwitchOpen(false);
    window.history.replaceState(null, "", "/");
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
          {notice ? <p className="error">{notice}</p> : null}
          <button className="cta" disabled={busy} onClick={() => void startOAuth("google")}>{copy.google}</button>
          <button className="cta" disabled={busy} onClick={() => void startOAuth("github")}>{copy.github}</button>
        </div>
      </main>
    );
  }

  return (
    <>
    <div className="app-shell" inert={locked ? true : undefined}>
      <ParentingShell email={session.email} onSignOut={() => void signOut()} onOpenChat={() => { if (!locked) setChat(true); }} />
      {notice && !locked ? <p className="error">{notice}</p> : null}
      {chat && !locked ? (
        <ConversationWorkspace session={session} onSignOut={() => void signOut()} onRestart={restart} onClose={() => setChat(false)} />
      ) : null}
      {switchOpen ? (
        <div className="dialog-back" role="dialog" aria-label={copy.switchTitle}>
          <div className="dialog">
            <h2>{copy.switchTitle}</h2>
            <p>{copy.signOutNamed}</p>
            <button onClick={() => void cancelSwitch()}>{copy.cancel}</button>
            <button className="danger" onClick={() => void confirmSwitch()}>{copy.switchConfirm}</button>
          </div>
        </div>
      ) : null}
    </div>
    <PrivacyGate locked={locked} onLock={engageLock} onContinue={() => void continueLocked()} onSignOut={() => void signOut()} notice={notice} />
    </>
  );
}

function PrivacyGate({ locked, onLock, onContinue, onSignOut, notice }: { locked: boolean; onLock: () => void; onContinue: () => void; onSignOut: () => void; notice: string | null }) {
  const [obscured, setObscured] = useState(false);
  const [reduced, setReduced] = useState(false);
  const last = useRef(Date.now());
  const engaged = useRef(false);

  useEffect(() => {
    if (locked) engaged.current = true;
  }, [locked]);

  useEffect(() => {
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const apply = () => setReduced(media.matches);
    apply();
    media.addEventListener("change", apply);
    const mark = () => {
      last.current = Date.now();
    };
    const names = ["pointerdown", "keydown", "touchstart", "scroll"] as const;
    for (const name of names) window.addEventListener(name, mark, { passive: true });
    const trip = () => {
      if (Date.now() - last.current < IDLE_MS || engaged.current) return;
      engaged.current = true;
      onLock();
    };
    const timer = window.setInterval(trip, 15_000);
    const onVis = () => {
      if (document.visibilityState === "hidden") setObscured(true);
      else {
        setObscured(false);
        trip();
      }
    };
    document.addEventListener("visibilitychange", onVis);
    return () => {
      media.removeEventListener("change", apply);
      for (const name of names) window.removeEventListener(name, mark);
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [onLock]);

  return (
    <>
      {obscured || locked ? <div className={obscured && !locked && !reduced ? "privacy-cover blurred" : "privacy-cover"} /> : null}
      {locked ? (
        <div className="privacy-lock" role="dialog" aria-modal="true" aria-label={copy.lockTitle}>
          <p>{copy.lockBody}</p>
          {notice ? <p className="error">{notice}</p> : null}
          <button className="cta" onClick={onContinue}>{copy.continueSession}</button>
          <button className="danger" onClick={onSignOut}>{copy.signOut}</button>
        </div>
      ) : null}
    </>
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
        <p className="quiet">{copy.familyTodo}</p>
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
