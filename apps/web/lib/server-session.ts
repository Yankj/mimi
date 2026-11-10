import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { establishSession, refreshStoredSession, type StoredSession } from "./browser-session";
import { LOCK_WINDOW_MS, lockIsActive } from "./lock";
import { sameOriginAllowed, siteOrigin } from "./origin";
import { decodePending, encodePending } from "./pending";
import type { WebProfile } from "./profile";
import { readServerConfig, serverUploadHosts } from "./server-config";
import { applicationLogoutRequest, authLogoutRequest, type PublicConfig } from "./wire";

const ACCESS = "mimi_access";
const REFRESH = "mimi_refresh";
const META = "mimi_meta";
const PKCE = "mimi_pkce";
const PENDING = "mimi_pending";
const LOCKED = "mimi_locked";
const REAUTH = "mimi_reauth";
const PROVIDER = "mimi_provider";
const E2E = "mimi_e2e";
const AUTH = [ACCESS, REFRESH, META, PKCE, PENDING, LOCKED, REAUTH, PROVIDER];

type Meta = {
  userId: string;
  email: string | null;
  scopeId: string;
  expiresAt: number;
  provider?: "google" | "github";
  lockedUntil?: number;
};

export function cookieSecure(request: Request) {
  const site = siteOrigin(process.env.MIMI_WEB_SITE_URL, request);
  return new URL(site ?? request.url).protocol === "https:";
}

function cookieOptions(request: Request, maxAge: number) {
  return { httpOnly: true, sameSite: "lax" as const, path: "/", secure: cookieSecure(request), maxAge };
}

export function rejectCrossSite(request: Request) {
  if (!sameOriginAllowed(request, process.env.MIMI_WEB_SITE_URL)) return new NextResponse(null, { status: 403 });
  return null;
}

export function externalOrigin(request: Request) {
  const site = siteOrigin(process.env.MIMI_WEB_SITE_URL, request);
  if (!site) throw new Error("site origin is not configured");
  return site;
}

function loopbackRequest(request: Request) {
  const host = (request.headers.get("host") ?? new URL(request.url).host).toLowerCase();
  return host.startsWith("127.0.0.1:") || host.startsWith("localhost:") || host === "127.0.0.1" || host === "localhost";
}

export function e2eFixture(request: Request) {
  return process.env.MIMI_WEB_E2E === "1" && loopbackRequest(request);
}

const E2E_PROFILE: WebProfile = {
  userId: "11111111-1111-1111-1111-111111111111",
  email: "parent@example.com",
  scopeId: "22222222-2222-2222-2222-222222222222",
  uploadHosts: [],
  provider: "google",
  locked: false,
};

function metaFrom(session: StoredSession, options?: { clearLock?: boolean }): Meta {
  const meta: Meta = { userId: session.userId, email: session.email, scopeId: session.scopeId, expiresAt: session.expiresAt };
  if (session.provider === "google" || session.provider === "github") meta.provider = session.provider;
  if (!options?.clearLock && lockIsActive(session.lockedUntil, Date.now())) meta.lockedUntil = session.lockedUntil;
  return meta;
}

function stamp(response: NextResponse, request: Request, name: string, value: string, maxAge: number) {
  response.cookies.set(name, value, cookieOptions(request, maxAge));
}

export function attachSession(response: NextResponse, request: Request, session: StoredSession, options?: { clearLock?: boolean }) {
  const maxAge = 60 * 60 * 24 * 14;
  stamp(response, request, ACCESS, session.accessToken, maxAge);
  stamp(response, request, REFRESH, session.refreshToken, maxAge);
  stamp(response, request, META, JSON.stringify(metaFrom(session, options)), maxAge);
  stamp(response, request, PENDING, "", 0);
  stamp(response, request, PROVIDER, "", 0);
  if (options?.clearLock) {
    stamp(response, request, LOCKED, "", 0);
    stamp(response, request, REAUTH, "", 0);
  }
}

export function attachPending(response: NextResponse, request: Request, session: StoredSession) {
  stamp(response, request, PENDING, encodePending({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    expiresAt: session.expiresAt,
    userId: session.userId,
    email: session.email,
    scopeId: session.scopeId,
    provider: session.provider,
  }), 600);
}

export function attachLock(response: NextResponse, request: Request, userId: string, session?: StoredSession | null) {
  const lockedUntil = Date.now() + LOCK_WINDOW_MS;
  stamp(response, request, LOCKED, userId, LOCK_WINDOW_MS / 1000);
  if (!session) return;
  stamp(response, request, META, JSON.stringify({ ...metaFrom(session), lockedUntil }), 60 * 60 * 24 * 14);
}

export function attachReauth(response: NextResponse, request: Request, userId: string) {
  stamp(response, request, REAUTH, userId, 600);
}

export function attachProvider(response: NextResponse, request: Request, provider: "google" | "github") {
  stamp(response, request, PROVIDER, provider, 600);
}

export function attachPkce(response: NextResponse, request: Request, verifier: string) {
  stamp(response, request, PKCE, verifier, 600);
}

export function clearAuth(response: NextResponse, request: Request) {
  for (const name of AUTH) stamp(response, request, name, "", 0);
}

export async function readE2EMarker() {
  const jar = await cookies();
  return jar.get(E2E)?.value ?? null;
}

export async function readPkce() {
  const jar = await cookies();
  return jar.get(PKCE)?.value ?? null;
}

export async function readPending(): Promise<StoredSession | null> {
  const jar = await cookies();
  const payload = decodePending(jar.get(PENDING)?.value ?? "");
  if (!payload) return null;
  return { ...payload, isAnonymous: false };
}

export async function readReauthUser() {
  const jar = await cookies();
  const value = jar.get(REAUTH)?.value ?? "";
  return value || null;
}

export async function readProviderCookie(): Promise<"google" | "github" | null> {
  const jar = await cookies();
  const value = jar.get(PROVIDER)?.value;
  return value === "google" || value === "github" ? value : null;
}

export async function readLockedUser() {
  const jar = await cookies();
  const marker = jar.get(LOCKED)?.value ?? "";
  if (marker) return marker;
  const session = await readSession();
  if (session && lockIsActive(session.lockedUntil, Date.now())) return session.userId;
  return null;
}

export async function readSession(): Promise<StoredSession | null> {
  const jar = await cookies();
  const accessToken = jar.get(ACCESS)?.value;
  const refreshToken = jar.get(REFRESH)?.value;
  const raw = jar.get(META)?.value;
  if (!accessToken || !refreshToken || !raw) return null;
  try {
    const meta = JSON.parse(raw) as Meta;
    if (!meta.userId || !meta.scopeId) return null;
    return {
      accessToken,
      refreshToken,
      expiresAt: meta.expiresAt,
      userId: meta.userId,
      email: meta.email,
      isAnonymous: false,
      scopeId: meta.scopeId,
      provider: meta.provider,
      lockedUntil: meta.lockedUntil,
    };
  } catch {
    return null;
  }
}

export async function profileFor(request: Request): Promise<WebProfile | null> {
  const locked = Boolean(await readLockedUser());
  if (e2eFixture(request)) {
    const marker = await readE2EMarker();
    if (marker === "guest") return null;
    if (marker === "named") return { ...E2E_PROFILE, locked };
  }
  const session = await readSession();
  if (!session || session.isAnonymous !== false) return null;
  const config = readServerConfig();
  return {
    userId: session.userId,
    email: session.email,
    scopeId: session.scopeId,
    uploadHosts: config ? serverUploadHosts(config) : [],
    provider: session.provider ?? null,
    locked,
  };
}

export async function refreshedSession() {
  const session = await readSession();
  const config = readServerConfig();
  if (!session || !config) return null;
  return refreshStoredSession(config, session);
}

async function logoutUpstream(config: PublicConfig, accessToken: string) {
  const application = applicationLogoutRequest(config, accessToken);
  const auth = authLogoutRequest(config, accessToken);
  try {
    await fetch(application.url, { method: application.method, headers: application.headers, body: application.body, cache: "no-store", redirect: "manual" });
  } catch {
    /* Cookies are cleared even when the edge logout cannot be reached. */
  }
  try {
    await fetch(auth.url, { method: auth.method, headers: auth.headers, body: auth.body, cache: "no-store", redirect: "manual" });
  } catch {
    /* Cookies are cleared even when the auth logout cannot be reached. */
  }
}

export async function logoutUpstreamSession() {
  const session = await readSession();
  const config = readServerConfig();
  if (session && config) await logoutUpstream(config, session.accessToken);
}

export { establishSession };
