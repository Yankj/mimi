import {
  applicationLogoutRequest,
  applicationSessionRequest,
  authErrorMessage,
  authLogoutRequest,
  ClientError,
  credentialFromToken,
  readPublicConfig,
  refreshRequest,
  scopeFromSession,
  type Credential,
  type PublicConfig,
} from "./wire";

export type StoredSession = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  userId: string;
  email: string | null;
  isAnonymous: boolean;
  scopeId: string;
};

const KEY = "mimi.web.session.v1";

export function loadStoredSession(): StoredSession | null {
  if (typeof window === "undefined") return null;
  const raw = window.sessionStorage.getItem(KEY);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as StoredSession;
    if (!value.accessToken || !value.refreshToken || !value.userId || !value.scopeId || value.isAnonymous !== false) {
      window.sessionStorage.removeItem(KEY);
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

export function saveStoredSession(session: StoredSession) {
  window.sessionStorage.setItem(KEY, JSON.stringify(session));
}

export function clearStoredSession() {
  window.sessionStorage.removeItem(KEY);
}

export function sessionFromCredential(credential: Credential, scopeId: string): StoredSession {
  if (credential.user.isAnonymous) throw new ClientError("Web 不提供体验登录，请使用 Google 或 GitHub。");
  return {
    accessToken: credential.accessToken,
    refreshToken: credential.refreshToken,
    expiresAt: credential.expiresAt,
    userId: credential.user.id,
    email: credential.user.email,
    isAnonymous: false,
    scopeId,
  };
}

async function readJson(response: Response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

export async function establishSession(config: PublicConfig, credential: Credential) {
  const request = applicationSessionRequest(config, credential.accessToken);
  const response = await fetch(request.url, { method: request.method, headers: request.headers, cache: "no-store", redirect: "manual" });
  const json = await readJson(response);
  if (response.status !== 200) {
    const code = typeof (json as { code?: unknown } | null)?.code === "string" ? (json as { code: string }).code : null;
    throw new Error(authErrorMessage(response.status, code, "other") || "登录响应未通过校验，请重新登录。");
  }
  return sessionFromCredential(credential, scopeFromSession(json, credential.user.id));
}

export function readBrowserConfig() {
  return readPublicConfig({
    NEXT_PUBLIC_SUPABASE_URL: process.env.NEXT_PUBLIC_SUPABASE_URL,
    NEXT_PUBLIC_SUPABASE_ANON_KEY: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
    NEXT_PUBLIC_CATPAW_EDGE_ORIGIN: process.env.NEXT_PUBLIC_CATPAW_EDGE_ORIGIN,
  });
}

export async function logoutStoredSession(config: PublicConfig, accessToken: string) {
  const application = applicationLogoutRequest(config, accessToken);
  const auth = authLogoutRequest(config, accessToken);
  try {
    await fetch(application.url, { method: application.method, headers: application.headers, body: application.body, cache: "no-store", redirect: "manual" });
  } catch {
    /* Local sign-out still proceeds when the edge logout cannot be reached. */
  }
  try {
    await fetch(auth.url, { method: auth.method, headers: auth.headers, body: auth.body, cache: "no-store", redirect: "manual" });
  } catch {
    /* Local sign-out still proceeds when the auth logout cannot be reached. */
  }
  clearStoredSession();
}

export async function refreshStoredSession(config: PublicConfig, session: StoredSession) {
  const request = refreshRequest(config, session.refreshToken);
  const response = await fetch(request.url, { method: request.method, headers: request.headers, body: request.body, cache: "no-store", redirect: "manual" });
  const json = await readJson(response);
  if (response.status !== 200) throw new Error("登录已失效，请重新登录。");
  const credential = credentialFromToken(json);
  if (credential.user.id !== session.userId) throw new Error("登录响应未通过校验，请重新登录。");
  return establishSession(config, credential);
}
