"use client";

// Node tests compile this module with the classic JSX runtime.
import React from "react";
import Image from "next/image";
import { copy } from "@/lib/copy";

/** Wordmark, cat mark, and mascot shared by the session check and the logged-out gate.
 *  The mark is the existing asset at docs/brand/mark.svg, served unchanged.
 *  The mascot is the same /mascot/idle.png the login page already uses.
 */
export function BrandLockup() {
  return (
    <div>
      <div className="brand-row">
        <Image className="brand-mark" src="/brand/mark.svg" alt="" width={40} height={40} priority />
        <p className="brand">{copy.brand} <span className="brand-sub">小咪</span></p>
      </div>
      <p className="brand-sub">{copy.brandSubtitle}</p>
    </div>
  );
}

function BrandMascot() {
  return (
    <div className="mascot-wrap">
      <div className="mascot-orb">
        <Image src="/mascot/idle.png" alt="" width={180} height={180} priority />
      </div>
    </div>
  );
}

export function SessionChecking() {
  return (
    <main className="login" aria-busy="true">
      <BrandLockup />
      <div>
        <BrandMascot />
      </div>
      <p role="status">{copy.restoring}</p>
    </main>
  );
}

export function LoggedOutLogin({
  notice,
  busy,
  onGoogle,
  onGitHub,
}: {
  notice: string | null;
  busy: boolean;
  onGoogle: () => void;
  onGitHub: () => void;
}) {
  return (
    <main className="login">
      <BrandLockup />
      <div>
        <BrandMascot />
        <h1 className="headline">{copy.headline}</h1>
        <p className="quiet">{copy.subtitle}</p>
      </div>
      <div className="stack">
        <h2>{copy.loginTitle}</h2>
        <p className="quiet">{copy.oauthOnly}</p>
        {notice ? <p className="error">{notice}</p> : null}
        <button className="cta" type="button" disabled={busy} onClick={onGoogle}>{copy.google}</button>
        <button className="cta" type="button" disabled={busy} onClick={onGitHub}>{copy.github}</button>
      </div>
    </main>
  );
}
