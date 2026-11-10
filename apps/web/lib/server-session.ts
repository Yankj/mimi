import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { establishSession, refreshStoredSession, type StoredSession } from "./browser-session";
import type { WebProfile } from "./profile";
import { readServerConfig, serverUploadHosts } from "./server-config";
import { applicationLogoutRequest, authLogoutRequest, type PublicConfig } from "./wire";

const ACCESS = "mimi_access";
const REFRESH = "mimi_refresh";
const META = "mimi_meta";
const PKCE = "mimi_pkce";
const PENDING = "mimi_pending";
const E2E = "mimi_e2e";
const AUTH = [ACCESS, REFRESH, META, PKCE, PENDING];

type Meta = { userId: string; email: string | null; scopeId: string; expiresAt: number };

export function cookieSecure(request: Request) {
  const url = new URL(request.url);
  const proto = request.headers.get("x-forwarded-proto") ?? url.protocol.replace(":", "");
  const host = (request.headers.get("x-forwarded-host") ?? url.hostname).toLowerCase();
  const loopback = host === "127.0.0.1" || host === "localhost" || host.startsWith("127.0.0.1:") || host.startsWith("localhost:");
  return proto === "https" || !loopback;
}

function cookieOptions(request: Request, maxAge: number) {
  return { httpOnly: true, sameSite: "lax" as const, path: "/", secure: cookieSecure(request), maxAge };
}

export function externalOrigin(request: Request) {
  const url = new URL(request.url);
  const proto = request.headers.get("x-forwarded-proto") ?? url.protocol.replace(":", "");
  const host = request.headers.get("x-forwarded-host") ?? url.host;
  return `${proto}://${host}`;
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
};

function metaFrom(session: StoredSession): Meta {
  return { userId: session.userId, email: session.email, scopeId: session.scopeId, expiresAt: session.expiresAt };
}

function stamp(response: NextResponse, request: Request, name: string, value: string, maxAge: number) {
  response.cookies.set(name, value, cookieOptions(request, maxAge));
}

export function attachSession(response: NextResponse, request: Request, session: StoredSession) {
  const maxAge = 60 * 60 * 24 * 14;
  stamp(response, request, ACCESS, session.accessToken, maxAge);
  stamp(response, request, REFRESH, session.refreshToken, maxAge);
  stamp(response, request, META, JSON.stringify(metaFrom(session)), maxAge);
  stamp(response, request, PENDING, "", 0);
}

export function attachPending(response: NextResponse, request: Request, session: StoredSession) {
  stamp(response, request, PENDING, JSON.stringify(session), 600);
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
  const raw = jar.get(PENDING)?.value;
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as StoredSession;
    if (!value.accessToken || !value.refreshToken || value.isAnonymous !== false || !value.userId || !value.scopeId) return null;
    return value;
  } catch {
    return null;
  }
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
    return { accessToken, refreshToken, expiresAt: meta.expiresAt, userId: meta.userId, email: meta.email, isAnonymous: false, scopeId: meta.scopeId };
  } catch {
    return null;
  }
}

export async function profileFor(request: Request): Promise<WebProfile | null> {
  if (e2eFixture(request)) {
    const marker = await readE2EMarker();
    if (marker === "guest") return null;
    if (marker === "named") return E2E_PROFILE;
  }
  const session = await readSession();
  if (!session || session.isAnonymous !== false) return null;
  const config = readServerConfig();
  return { userId: session.userId, email: session.email, scopeId: session.scopeId, uploadHosts: config ? serverUploadHosts(config) : [] };
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
