import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { establishSession, refreshStoredSession, type StoredSession } from "./browser-session";
import { SESSION_MAX_AGE_SEC, activityDecision, lockRecorded } from "./lock";
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
  locked?: boolean;
  /** Previous builds stored a timestamp. Any recorded value still means locked. */
  lockedUntil?: number;
  seenAt?: number;
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
  if (typeof session.seenAt === "number" && Number.isFinite(session.seenAt)) meta.seenAt = session.seenAt;
  if (!options?.clearLock && session.locked) meta.locked = true;
  return meta;
}

function stamp(response: NextResponse, request: Request, name: string, value: string, maxAge: number) {
  response.cookies.set(name, value, cookieOptions(request, maxAge));
}

export function attachSession(response: NextResponse, request: Request, session: StoredSession, options?: { clearLock?: boolean }) {
  const stored: StoredSession = options?.clearLock ? { ...session, locked: false, seenAt: Date.now() } : session;
  stamp(response, request, ACCESS, stored.accessToken, SESSION_MAX_AGE_SEC);
  stamp(response, request, REFRESH, stored.refreshToken, SESSION_MAX_AGE_SEC);
  stamp(response, request, META, JSON.stringify(metaFrom(stored, options)), SESSION_MAX_AGE_SEC);
  stamp(response, request, PENDING, "", 0);
  stamp(response, request, PROVIDER, "", 0);
  if (options?.clearLock) {
    stamp(response, request, LOCKED, "", 0);
    stamp(response, request, REAUTH, "", 0);
  }
}

export function noteActivity(response: NextResponse, request: Request, session: StoredSession) {
  stamp(response, request, META, JSON.stringify(metaFrom({ ...session, locked: false, seenAt: Date.now() }, { clearLock: true })), SESSION_MAX_AGE_SEC);
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
  stamp(response, request, LOCKED, userId, SESSION_MAX_AGE_SEC);
  if (!session) return;
  stamp(response, request, META, JSON.stringify(metaFrom({ ...session, locked: true })), SESSION_MAX_AGE_SEC);
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

export async function readLockedUser(now = Date.now()) {
  const jar = await cookies();
  const marker = jar.get(LOCKED)?.value ?? "";
  if (marker) return marker;
  const session = await readSession();
  if (!session) return null;
  const decision = activityDecision({ locked: session.locked === true, seenAt: session.seenAt, now });
  return decision.locked ? session.userId : null;
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
      locked: lockRecorded(meta),
      seenAt: typeof meta.seenAt === "number" && Number.isFinite(meta.seenAt) ? meta.seenAt : undefined,
    };
  } catch {
    return null;
  }
}

function profileFrom(session: StoredSession, locked: boolean): WebProfile {
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

export async function profileFor(request: Request): Promise<WebProfile | null> {
  const now = Date.now();
  if (e2eFixture(request)) {
    const marker = await readE2EMarker();
    if (marker === "guest") return null;
    if (marker === "named") return { ...E2E_PROFILE, locked: Boolean(await readLockedUser(now)) };
  }
  const session = await readSession();
  if (!session || session.isAnonymous !== false) return null;
  const decision = activityDecision({ locked: session.locked === true, seenAt: session.seenAt, now });
  return profileFrom(session, decision.locked);
}

/** Session read that also persists a lock or a fresh last-activity time. */
export async function openSession(request: Request) {
  const now = Date.now();
  if (e2eFixture(request)) {
    const marker = await readE2EMarker();
    if (marker === "guest") return new NextResponse(null, { status: 401, headers: { "cache-control": "no-store" } });
    if (marker === "named") {
      const profile = { ...E2E_PROFILE, locked: Boolean(await readLockedUser(now)) };
      return NextResponse.json(profile, { headers: { "cache-control": "no-store" } });
    }
  }
  const session = await readSession();
  if (!session || session.isAnonymous !== false) return new NextResponse(null, { status: 401, headers: { "cache-control": "no-store" } });
  const decision = activityDecision({ locked: session.locked === true, seenAt: session.seenAt, now });
  const response = NextResponse.json(profileFrom(session, decision.locked), { headers: { "cache-control": "no-store" } });
  if (decision.locked) attachLock(response, request, session.userId, session);
  else noteActivity(response, request, session);
  return response;
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
