/**
 * Request builders for the same catpaw edge routes and headers as the iOS client.
 * Components call these; they do not own accounts or decide that work finished.
 */

export const CONTRACTS = {
  modelRoutes: "catpaw.model-routes.v1",
  approval: "catpaw.approval.v1",
  ui: "catpaw.ui.v1",
  uiKinds: "card,choices,confirm,event,form,list,place,progress,sheet,followups",
  memoryProposal: "catpaw.memory-proposal.v1",
  memory: "catpaw.memory.v1",
  inbox: "catpaw.inbox.v1",
  acceptedWork: "catpaw.accepted-work-intent.v1",
  taskAction: "catpaw.task-action.v1",
  taskControl: "catpaw.task-control.v1",
} as const;

export const PAGE_SIZE = 40;
export const INBOX_PAGE = 20;
export const AUTO_ROUTE = "auto";

export type PublicConfig = {
  supabaseUrl: string;
  anonKey: string;
  edgeOrigin: string;
};

export type AuthUser = { id: string; email: string | null; isAnonymous: boolean };
export type Credential = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  user: AuthUser;
};

export class ClientError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ClientError";
  }
}

const SUPABASE_HOST = /^[a-z0-9]{20}\.supabase\.co$/;
const PUBLISHABLE = /^sb_publishable_[A-Za-z0-9_-]{10,2000}$/;
const EDGE_PATH = /^\/functions\/v1\/[A-Za-z0-9_-]{1,64}\/?$/;

export function uploadHostsFromConfig(config: PublicConfig) {
  return [...new Set([new URL(config.supabaseUrl).hostname, new URL(config.edgeOrigin).hostname])];
}

export function uploadUrlAllowed(url: string, hosts: string[]) {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || hosts.length === 0) return false;
  return hosts.some((host) => host.toLowerCase() === parsed.hostname.toLowerCase());
}

export function flushSseBuffer(buffer: string) {
  if (!buffer) return { lines: [] as string[], rest: "" };
  const terminated = buffer.endsWith("\n") || buffer.endsWith("\r") ? buffer : `${buffer}\n`;
  return takeSseLines(terminated);
}

export function readPublicConfig(env: Record<string, string | undefined>): PublicConfig | null {
  const supabaseUrl = env.NEXT_PUBLIC_SUPABASE_URL?.trim() ?? "";
  const anonKey = env.NEXT_PUBLIC_SUPABASE_ANON_KEY?.trim() ?? "";
  const edgeOrigin = env.NEXT_PUBLIC_CATPAW_EDGE_ORIGIN?.trim() ?? "";
  if (!supabaseUrl && !anonKey && !edgeOrigin) return null;
  if (!supabaseUrl || !anonKey || !edgeOrigin) throw new ClientError("登录连接尚未配置。");
  assertPublicConfig({ supabaseUrl, anonKey, edgeOrigin });
  return { supabaseUrl: supabaseUrl.replace(/\/$/, ""), anonKey, edgeOrigin: edgeOrigin.replace(/\/$/, "") };
}

export function assertPublicConfig(config: PublicConfig) {
  let project: URL;
  let edge: URL;
  try {
    project = new URL(config.supabaseUrl);
    edge = new URL(config.edgeOrigin);
  } catch {
    throw new ClientError("登录连接尚未配置。");
  }
  if (project.protocol !== "https:" || project.username || project.password || project.search || project.hash || project.pathname !== "/") {
    throw new ClientError("登录连接尚未配置。");
  }
  if (!project.hostname || !SUPABASE_HOST.test(project.hostname)) throw new ClientError("登录连接尚未配置。");
  if (edge.protocol !== "https:" || edge.username || edge.password || edge.search || edge.hash) {
    throw new ClientError("登录连接尚未配置。");
  }
  if (!(edge.pathname === "/" || edge.pathname === "" || EDGE_PATH.test(edge.pathname))) {
    throw new ClientError("登录连接尚未配置。");
  }
  assertAnonKey(config.anonKey);
}

export function assertAnonKey(key: string) {
  if (!key || key.startsWith("sb_secret_") || /\bservice_role\b/.test(key)) {
    throw new ClientError("登录连接尚未配置。");
  }
  if (PUBLISHABLE.test(key)) return;
  const parts = key.split(".");
  if (parts.length !== 3) throw new ClientError("登录连接尚未配置。");
  try {
    const payload = JSON.parse(decodeBase64Url(parts[1])) as { role?: string };
    if (payload.role !== "anon") throw new ClientError("登录连接尚未配置。");
  } catch (error) {
    if (error instanceof ClientError) throw error;
    throw new ClientError("登录连接尚未配置。");
  }
}

function decodeBase64Url(value: string) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (value.length % 4)) % 4);
  if (typeof atob === "function") return atob(padded);
  return Buffer.from(padded, "base64").toString("utf8");
}

export function joinEndpoint(base: string, route: string) {
  if (!route.startsWith("/") || route.startsWith("//")) throw new ClientError("登录连接尚未配置。");
  const pathOnly = route.split(/[?#]/)[0];
  if (pathOnly.split("/").some((part) => part === "." || part === ".." || part.toLowerCase() === "%2e" || part.toLowerCase() === "%2e%2e")) {
    throw new ClientError("登录连接尚未配置。");
  }
  const relative = new URL(route, "https://catpaw.invalid");
  if (relative.username || relative.password || relative.hash) throw new ClientError("登录连接尚未配置。");
  if (relative.pathname.split("/").some((part) => part === "." || part === "..")) throw new ClientError("登录连接尚未配置。");
  const origin = new URL(base);
  const prefix = origin.pathname.endsWith("/") ? origin.pathname.slice(0, -1) : origin.pathname;
  origin.pathname = `${prefix}${relative.pathname}`;
  origin.search = relative.search;
  return origin.toString();
}

export function pinnedRegion(edgeOrigin: string) {
  const url = new URL(edgeOrigin);
  const pinned =
    url.protocol === "https:" &&
    url.hostname.toLowerCase() === "dbyklgxdvjbutxzsjeth.supabase.co" &&
    (url.pathname === "/functions/v1/catpaw-api" || url.pathname === "/functions/v1/catpaw-api/");
  return pinned ? "us-east-1" : null;
}

export type RouteChoice = { route: string; model: string | null; knownRouteIds: string[] };

export function headerRoute(choice: RouteChoice) {
  if (!choice.route || choice.route === AUTO_ROUTE) return AUTO_ROUTE;
  if (choice.knownRouteIds.length === 0 || choice.knownRouteIds.includes(choice.route)) return choice.route;
  return AUTO_ROUTE;
}

export function headerModel(choice: RouteChoice, modelsByRoute: Record<string, string[]>) {
  const route = headerRoute(choice);
  if (route === AUTO_ROUTE || !choice.model) return null;
  const allowed = modelsByRoute[route] ?? [];
  return allowed.includes(choice.model) ? choice.model : null;
}

export function chatHeaders(input: {
  authorization: string;
  anonKey: string;
  edgeOrigin: string;
  route: RouteChoice;
  modelsByRoute: Record<string, string[]>;
  liveText?: boolean;
  approval?: boolean;
  memory?: "0" | "1" | null;
  memoryProposal?: boolean;
  timeZone?: string;
}) {
  if (!input.authorization.startsWith("Bearer ") || input.authorization.length <= 7) {
    throw new ClientError("登录已失效，请重新登录。");
  }
  const headers: Record<string, string> = {
    Authorization: input.authorization,
    apikey: input.anonKey,
    Accept: "text/event-stream, application/json",
    "Content-Type": "application/json",
    "X-Catpaw-Route": headerRoute(input.route),
    "X-Catpaw-Route-Events": "1",
    "X-Catpaw-Reasoning-Summary": "1",
  };
  const model = headerModel(input.route, input.modelsByRoute);
  if (model) headers["X-Catpaw-Model"] = model;
  const region = pinnedRegion(input.edgeOrigin);
  if (region) headers["x-region"] = region;
  if (input.liveText) headers["X-Catpaw-Live-Text"] = "1";
  if (input.approval) {
    headers["X-Catpaw-Approval-Contract"] = CONTRACTS.approval;
    headers["X-Catpaw-UI-Contract"] = CONTRACTS.ui;
    headers["X-Catpaw-UI-Kinds"] = CONTRACTS.uiKinds;
  }
  if (input.memoryProposal) headers["X-Catpaw-Memory-Proposal-Contract"] = CONTRACTS.memoryProposal;
  if (input.memory) headers["X-Catpaw-Use-Memory"] = input.memory;
  if (input.timeZone) headers["X-Catpaw-Time-Zone"] = input.timeZone;
  return headers;
}

export function authHeaders(anonKey: string, accessToken?: string) {
  const headers: Record<string, string> = { apikey: anonKey, Accept: "application/json", "Content-Type": "application/json" };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return headers;
}

export function refreshRequest(config: PublicConfig, refreshToken: string) {
  return {
    url: joinEndpoint(config.supabaseUrl, "/auth/v1/token?grant_type=refresh_token"),
    method: "POST" as const,
    headers: authHeaders(config.anonKey),
    body: JSON.stringify({ refresh_token: refreshToken }),
  };
}

export function pkceExchangeRequest(config: PublicConfig, code: string, verifier: string) {
  return {
    url: joinEndpoint(config.supabaseUrl, "/auth/v1/token?grant_type=pkce"),
    method: "POST" as const,
    headers: authHeaders(config.anonKey),
    body: JSON.stringify({ auth_code: code, code_verifier: verifier }),
  };
}

export function authLogoutRequest(config: PublicConfig, accessToken: string) {
  return {
    url: joinEndpoint(config.supabaseUrl, "/auth/v1/logout?scope=local"),
    method: "POST" as const,
    headers: authHeaders(config.anonKey, accessToken),
    body: "{}",
  };
}

export function applicationSessionRequest(config: PublicConfig, accessToken: string) {
  return {
    url: joinEndpoint(config.edgeOrigin, "/catpaw/account/v1/session"),
    method: "GET" as const,
    headers: authHeaders(config.anonKey, accessToken),
  };
}

export function applicationLogoutRequest(config: PublicConfig, accessToken: string) {
  return {
    url: joinEndpoint(config.edgeOrigin, "/catpaw/account/v1/logout"),
    method: "POST" as const,
    headers: authHeaders(config.anonKey, accessToken),
    body: "{}",
  };
}

const UNRESERVED = /^[A-Za-z0-9._~-]+$/;

export async function createPkceVerifier() {
  const bytes = new Uint8Array(48);
  crypto.getRandomValues(bytes);
  const verifier = base64Url(bytes);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return { verifier, challenge: base64Url(new Uint8Array(digest)), method: "s256" as const };
}

export function assertVerifier(verifier: string) {
  if (verifier.length < 43 || verifier.length > 128 || !UNRESERVED.test(verifier)) throw new ClientError("登录连接尚未配置。");
}

export function authorizeUrl(config: PublicConfig, provider: "google" | "github", challenge: string, redirectTo: string) {
  const url = new URL(joinEndpoint(config.supabaseUrl, "/auth/v1/authorize"));
  url.searchParams.set("provider", provider);
  url.searchParams.set("redirect_to", redirectTo);
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "s256");
  return url.toString();
}

export function parseOAuthCallback(url: string) {
  const parsed = new URL(url);
  if (!parsed.pathname.endsWith("/auth/callback")) throw new ClientError("当前登录方式不接受外部链接中的会话。");
  const names = [...parsed.searchParams.keys(), ...new URLSearchParams(parsed.hash.replace(/^#/, "")).keys()];
  if (new Set(names).size !== names.length) throw new ClientError("登录响应未通过校验，请重新登录。");
  const query = parsed.searchParams;
  const fragment = new URLSearchParams(parsed.hash.replace(/^#/, ""));
  if (query.get("access_token") || query.get("refresh_token") || fragment.get("access_token") || fragment.get("refresh_token")) {
    throw new ClientError("当前登录方式不接受外部链接中的会话。");
  }
  const error = query.get("error") ?? fragment.get("error");
  if (error) {
    const raw = query.get("error_code") ?? fragment.get("error_code") ?? error;
    const code = /^[a-z0-9_]{1,64}$/.test(raw) ? raw : null;
    return code === "access_denied" ? ({ kind: "cancelled" } as const) : ({ kind: "failure", code } as const);
  }
  const code = query.get("code") ?? "";
  if (!/^[A-Za-z0-9._-]{8,512}$/.test(code)) throw new ClientError("登录响应未通过校验，请重新登录。");
  return { kind: "code", code } as const;
}

export function credentialFromToken(json: unknown, now = Date.now()): Credential {
  const row = asRecord(json);
  const user = asRecord(row?.user);
  const access = stringField(row, "access_token");
  const refresh = stringField(row, "refresh_token");
  const type = stringField(row, "token_type").toLowerCase();
  const expires = numberField(row, "expires_in");
  const id = stringField(user, "id");
  if (type !== "bearer" || !access || !refresh || !isUuid(id) || expires < 1 || expires > 86400) {
    throw new ClientError("登录响应未通过校验，请重新登录。");
  }
  if (user?.is_anonymous !== false) throw new ClientError("Web 不提供体验登录，请使用 Google 或 GitHub。");
  return {
    accessToken: access,
    refreshToken: refresh,
    expiresAt: now + expires * 1000,
    user: { id: id.toLowerCase(), email: typeof user?.email === "string" ? user.email : null, isAnonymous: false },
  };
}

export function scopeFromSession(json: unknown, subjectId: string) {
  const row = asRecord(json);
  if (numberField(row, "schema_version") !== 1) throw new ClientError("登录响应未通过校验，请重新登录。");
  if (stringField(row, "subject_id").toLowerCase() !== subjectId.toLowerCase()) throw new ClientError("登录响应未通过校验，请重新登录。");
  const scopes = Array.isArray(row?.scopes) ? row.scopes : [];
  const valid = scopes.flatMap((item) => {
    const scope = asRecord(item);
    const permissions = asRecord(scope?.permissions);
    if (permissions?.local_output !== true) return [];
    const scopeId = stringField(scope, "scope_id");
    if (!isUuid(scopeId) || !isUuid(stringField(scope, "profile_id")) || !isUuid(stringField(scope, "workspace_id"))) return [];
    return [scopeId.toLowerCase()];
  });
  if (valid.length !== 1) throw new ClientError("登录响应未通过校验，请重新登录。");
  return valid[0];
}

export function scopePath(scopeId: string) {
  return `/catpaw/chat/v1/scopes/${scopeId.toLowerCase()}`;
}

export function conversationPath(scopeId: string, conversationId: string) {
  return `${scopePath(scopeId)}/conversations/${conversationId.toLowerCase()}`;
}

export function turnBody(input: { turnId: string; revision: number; grantEpoch: number; messageId: string; text: string; attachmentIds?: string[] }) {
  const message: Record<string, unknown> = { id: input.messageId.toLowerCase(), text: input.text };
  const body: Record<string, unknown> = {
    turn_id: input.turnId.toLowerCase(),
    expected_revision: input.revision,
    expected_grant_epoch: input.grantEpoch,
    message,
    context: [],
  };
  if (input.attachmentIds && input.attachmentIds.length > 0) {
    body.schema_version = 2;
    message.attachment_ids = input.attachmentIds.map((id) => id.toLowerCase());
  }
  return body;
}

export function plainTurnRequest(input: {
  config: PublicConfig;
  accessToken: string;
  scopeId: string;
  conversationId: string;
  body: Record<string, unknown>;
  route: RouteChoice;
  modelsByRoute: Record<string, string[]>;
  useMemory: boolean;
  timeZone: string;
}) {
  return {
    url: joinEndpoint(input.config.edgeOrigin, `${conversationPath(input.scopeId, input.conversationId)}/turns`),
    method: "POST" as const,
    headers: {
      ...chatHeaders({
        authorization: `Bearer ${input.accessToken}`,
        anonKey: input.config.anonKey,
        edgeOrigin: input.config.edgeOrigin,
        route: input.route,
        modelsByRoute: input.modelsByRoute,
        liveText: true,
        approval: true,
        memory: input.useMemory ? "1" : "0",
        memoryProposal: true,
        timeZone: input.timeZone,
      }),
      "Idempotency-Key": String(input.body.turn_id),
    },
    body: JSON.stringify(input.body),
  };
}

export function cancelTurnRequest(config: PublicConfig, accessToken: string, scopeId: string, conversationId: string, turnId: string, route: RouteChoice, modelsByRoute: Record<string, string[]>) {
  return {
    url: joinEndpoint(config.edgeOrigin, `${conversationPath(scopeId, conversationId)}/turns/${turnId.toLowerCase()}/cancel`),
    method: "POST" as const,
    headers: chatHeaders({ authorization: `Bearer ${accessToken}`, anonKey: config.anonKey, edgeOrigin: config.edgeOrigin, route, modelsByRoute }),
    body: "{}",
  };
}

export function resumeEventsUrl(config: PublicConfig, scopeId: string, conversationId: string, turnId: string, afterSequence: number) {
  return joinEndpoint(config.edgeOrigin, `${conversationPath(scopeId, conversationId)}/turns/${turnId.toLowerCase()}/events?after_sequence=${afterSequence}`);
}

export function retryBusyUrl(config: PublicConfig, scopeId: string, conversationId: string, turnId: string) {
  return joinEndpoint(config.edgeOrigin, `${conversationPath(scopeId, conversationId)}/turns/${turnId.toLowerCase()}/retry-busy`);
}

export function modelRoutesUrl(config: PublicConfig) {
  return joinEndpoint(config.edgeOrigin, "/catpaw/model-routes/v1");
}

export function readyzUrl(config: PublicConfig) {
  return joinEndpoint(config.edgeOrigin, "/readyz");
}

export function modelRoutePrivacyUrl(config: PublicConfig) {
  return joinEndpoint(config.edgeOrigin, "/catpaw/privacy/v1/model-route");
}

export function modelConsentUrl(config: PublicConfig, scopeId: string, conversationId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/privacy/v1/scopes/${scopeId.toLowerCase()}/conversations/${conversationId.toLowerCase()}/model-consent`);
}

export function inboxUrl(config: PublicConfig, scopeId: string, cursor: string | null) {
  const url = new URL(joinEndpoint(config.edgeOrigin, `/catpaw/inbox/v1/scopes/${scopeId.toLowerCase()}/items`));
  url.searchParams.set("limit", String(INBOX_PAGE));
  if (cursor) url.searchParams.set("cursor", cursor);
  return url.toString();
}

export function memoryStateUrl(config: PublicConfig, scopeId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/memory/v1/scopes/${scopeId.toLowerCase()}/state`);
}

export function memoryCommandsUrl(config: PublicConfig, scopeId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/memory/v1/scopes/${scopeId.toLowerCase()}/commands`);
}

export function enrollmentUrl(config: PublicConfig, path: "me" | "enroll") {
  return joinEndpoint(config.edgeOrigin, `/catpaw/features/v1/${path}`);
}

export function attachmentCreateUrl(config: PublicConfig, scopeId: string) {
  return joinEndpoint(config.edgeOrigin, `${scopePath(scopeId)}/attachments`);
}

export function attachmentCompleteUrl(config: PublicConfig, scopeId: string, attachmentId: string) {
  return joinEndpoint(config.edgeOrigin, `${scopePath(scopeId)}/attachments/${attachmentId.toLowerCase()}/complete`);
}

export function artifactUrl(config: PublicConfig, scopeId: string, artifactId: string, version: number) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/v1/scopes/${scopeId.toLowerCase()}/artifacts/${artifactId}/versions/${version}`);
}

export function planUrl(config: PublicConfig, scopeId: string, runId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/plans/v1/scopes/${scopeId.toLowerCase()}/runs/${runId.toLowerCase()}/plan`);
}

export function actionsUrl(config: PublicConfig, scopeId: string, runId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/actions/v1/scopes/${scopeId.toLowerCase()}/runs/${runId.toLowerCase()}/actions`);
}

export function actionDecisionUrl(config: PublicConfig, scopeId: string, runId: string, actionId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/actions/v1/scopes/${scopeId.toLowerCase()}/runs/${runId.toLowerCase()}/actions/${actionId}/decisions`);
}

export function controlUrl(config: PublicConfig, scopeId: string, runId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/controls/v1/scopes/${scopeId.toLowerCase()}/runs/${runId.toLowerCase()}/control`);
}

export function controlOperationUrl(config: PublicConfig, scopeId: string, runId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/controls/v1/scopes/${scopeId.toLowerCase()}/runs/${runId.toLowerCase()}/control/operations`);
}

export function approvalDecisionUrl(config: PublicConfig, scopeId: string, conversationId: string, turnId: string, interruptId: string, afterSequence: number) {
  return joinEndpoint(
    config.edgeOrigin,
    `${conversationPath(scopeId, conversationId)}/turns/${turnId.toLowerCase()}/interrupts/${interruptId.toLowerCase()}/decision?after_sequence=${afterSequence}`,
  );
}

export function acceptedWorkTurnsUrl(config: PublicConfig, scopeId: string, conversationId: string) {
  return joinEndpoint(config.edgeOrigin, `/catpaw/accepted-work/v1/scopes/${scopeId.toLowerCase()}/conversations/${conversationId.toLowerCase()}/turns`);
}

export type SidebarRecord = { id: string; title: string; preview?: string };

export function matchConversations(records: SidebarRecord[], query: string) {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return records;
  return records.filter((record) => record.title.toLocaleLowerCase().includes(needle) || (record.preview ?? "").toLocaleLowerCase().includes(needle));
}

export function pageConversations<T>(records: T[], visibleCount: number) {
  return records.slice(0, visibleCount);
}

export function nextVisibleCount(current: number, total: number) {
  if (current >= total) return current;
  return Math.min(total, current + PAGE_SIZE);
}

export function inviteCodeValid(code: string) {
  const compact = code.toUpperCase().replace(/[^A-Z0-9]/g, "");
  const body = compact.startsWith("CP") ? compact.slice(2) : compact;
  return body.length === 12;
}

export function imageMime(bytes: Uint8Array) {
  if (bytes.length >= 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 && bytes[4] === 13 && bytes[5] === 10 && bytes[6] === 26 && bytes[7] === 10) return "image/png";
  if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return "image/jpeg";
  return null;
}

export const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

/** SSE lines match the iOS reader: `data: {json}`, blank and `:` comments ignored. */
export function takeSseLines(buffer: string) {
  const lines: string[] = [];
  let rest = "";
  let current = "";
  let afterCR = false;
  for (let index = 0; index < buffer.length; index += 1) {
    const char = buffer[index];
    if (afterCR) {
      afterCR = false;
      if (char === "\n") continue;
    }
    if (char === "\n" || char === "\r") {
      lines.push(current);
      current = "";
      afterCR = char === "\r";
    } else {
      current += char;
    }
  }
  rest = current;
  return { lines, rest };
}

export function parseSseData(line: string): unknown | null {
  if (!line || line.startsWith(":")) return null;
  if (!line.startsWith("data: ")) throw new ClientError("服务返回了不匹配的对话或工具结果");
  return JSON.parse(line.slice(6));
}

export function canonicalJson(value: unknown): string {
  return encodeCanonical(value);
}

function encodeCanonical(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isInteger(value)) throw new ClientError("登录响应未通过校验，请重新登录。");
    return String(value);
  }
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map((item) => encodeCanonical(item)).join(",")}]`;
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const keys = Object.keys(record).filter((key) => record[key] !== undefined).sort();
    return `{${keys.map((key) => `${JSON.stringify(key)}:${encodeCanonical(record[key])}`).join(",")}}`;
  }
  throw new ClientError("登录响应未通过校验，请重新登录。");
}

export async function sha256Hex(text: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function sha256Bytes(bytes: Uint8Array) {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const digest = await crypto.subtle.digest("SHA-256", copy);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function base64Url(bytes: Uint8Array) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const source = typeof btoa === "function" ? btoa(binary) : Buffer.from(bytes).toString("base64");
  return source.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

export function stringField(value: Record<string, unknown> | null, key: string) {
  const field = value?.[key];
  return typeof field === "string" ? field : "";
}

export function numberField(value: Record<string, unknown> | null, key: string) {
  const field = value?.[key];
  return typeof field === "number" && Number.isFinite(field) ? field : NaN;
}

export function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

export function authErrorMessage(status: number, code: string | null, route: "signup" | "token" | "logout" | "other") {
  if (code === "anonymous_provider_disabled" || code === "anonymous_sign_ins_disabled") return "Web 不提供体验登录，请使用 Google 或 GitHub。";
  if (status === 429) return "请求过于频繁，请稍后再试。";
  if (status === 401 || status === 403) return "登录已失效，请重新登录。";
  if ((status === 400 || status === 422) && route !== "logout") return "登录已失效，请重新登录。";
  if (status === 204 && route === "logout") return "";
  if (status >= 200 && status < 300) return "";
  return "登录服务暂不可用，请稍后重试。";
}

export function textDelta(event: unknown) {
  const row = asRecord(event);
  if (stringField(row, "type") !== "text_delta") return null;
  const data = asRecord(row?.data);
  const text = stringField(data, "text");
  const messageId = stringField(data, "message_id");
  if (!text || !messageId) return null;
  return { messageId, text, sequence: numberField(row, "sequence") };
}

export function splitFollowups(text: string, closed: boolean) {
  const marker = "```catpaw-card:followups";
  const start = text.indexOf(marker);
  if (start < 0) return { text, questions: [] as string[] };
  const prose = text.slice(0, start).trimEnd();
  const body = text.slice(start + marker.length);
  const end = body.indexOf("```");
  if (!closed || end < 0) return { text: prose, questions: [] as string[] };
  const questions: string[] = [];
  for (const raw of body.slice(0, end).split(/\r?\n/)) {
    const line = raw.replace(/^([-*•]|\d+[.、)])\s*/, "").trim().replace(/^[ "“”「」]+|[ "“”「」]+$/g, "");
    if (!line || [...line].length > 30 || line.includes("```") || questions.includes(line)) continue;
    questions.push(line);
    if (questions.length === 3) break;
  }
  return { text: prose, questions: questions.length >= 2 ? questions : [] };
}

export function textPreview(event: unknown) {
  const row = asRecord(event);
  if (stringField(row, "type") !== "text_preview") return null;
  const data = asRecord(row?.data);
  if (data?.live !== true) return null;
  const text = typeof data.text === "string" ? data.text : "";
  const messageId = stringField(data, "message_id");
  const offset = numberField(data, "offset");
  if (!messageId || !Number.isInteger(offset) || offset < 0) return null;
  return { messageId, text, offset };
}
