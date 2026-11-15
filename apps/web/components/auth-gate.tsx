"use client";

// Node tests compile this module with the classic JSX runtime.
import React from "react";
import Image from "next/image";
import type { BrandAssets } from "@/lib/brand";
import { copy } from "@/lib/copy";

/** Wordmark, inlined cat mark, and inlined mascot.
 *  A hard refresh paints text before a second image response. The mark is the
 *  SVG path itself, and the mascot bytes sit on the img, so neither waits on
 *  /brand/mark.svg or the image optimizer.
 */
export function BrandLockup({ brand }: { brand: BrandAssets }) {
  return (
    <div>
      <div className="brand-row">
        <svg className="brand-mark" viewBox="0 0 512 512" width="40" height="40" aria-hidden="true">
          <path fill={brand.markFill} fillRule="evenodd" d={brand.markPath} />
        </svg>
        <p className="brand">{copy.brand} <span className="brand-sub">小咪</span></p>
      </div>
      <p className="brand-sub">{copy.brandSubtitle}</p>
    </div>
  );
}

function BrandMascot({ brand }: { brand: BrandAssets }) {
  return (
    <div className="mascot-wrap">
      <div className="mascot-orb">
        <Image src={brand.mascotSrc} alt="" width={180} height={180} decoding="sync" />
      </div>
    </div>
  );
}

export function SessionChecking({ brand }: { brand: BrandAssets }) {
  return (
    <main className="login" aria-busy="true">
      <BrandLockup brand={brand} />
      <div>
        <BrandMascot brand={brand} />
      </div>
      <p role="status">{copy.restoring}</p>
    </main>
  );
}

export function LoggedOutLogin({
  brand,
  notice,
  busy,
  onGoogle,
  onGitHub,
}: {
  brand: BrandAssets;
  notice: string | null;
  busy: boolean;
  onGoogle: () => void;
  onGitHub: () => void;
}) {
  return (
    <main className="login">
      <BrandLockup brand={brand} />
      <div>
        <BrandMascot brand={brand} />
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
