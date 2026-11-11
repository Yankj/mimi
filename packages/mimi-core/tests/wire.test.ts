import assert from "node:assert/strict";
import { test } from "node:test";
import { orderedStarters } from "../src/copy.ts";
import {
  applicationLogoutRequest,
  applicationSessionRequest,
  assertAnonKey,
  authLogoutRequest,
  authorizeUrl,
  chatHeaders,
  credentialFromToken,
  headerModel,
  headerRoute,
  imageMime,
  inviteCodeValid,
  joinEndpoint,
  matchConversations,
  nextVisibleCount,
  PAGE_SIZE,
  parseOAuthCallback,
  parseSseData,
  deviceResultUrl,
  pkceExchangeRequest,
  plainTurnRequest,
  readPublicConfig,
  scopeFromSession,
  takeSseLines,
  splitFollowups,
  textDelta,
  textPreview,
  turnBody,
} from "../src/wire.ts";

const project = "https://abcdefghijklmnopqrst.supabase.co";
const edge = "https://abcdefghijklmnopqrst.supabase.co/functions/v1/catpaw-api";
const anon = ["sb", "publishable", "a".repeat(24)].join("_");
const config = { supabaseUrl: project, anonKey: anon, edgeOrigin: edge };

test("empty public env is unconfigured and does not invent a host", () => {
  assert.equal(readPublicConfig({}), null);
  assert.throws(() => readPublicConfig({ NEXT_PUBLIC_SUPABASE_URL: project }), /登录连接尚未配置/);
});

test("anon key accepts the publishable shape and a legacy anon JWT, and refuses a secret key", () => {
  assert.doesNotThrow(() => assertAnonKey(anon));
  const header = Buffer.from(JSON.stringify({ alg: "none" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({ role: "anon" })).toString("base64url");
  assert.doesNotThrow(() => assertAnonKey(`${header}.${payload}.sig`));
  const service = Buffer.from(JSON.stringify({ role: "service_role" })).toString("base64url");
  assert.throws(() => assertAnonKey(`${header}.${service}.sig`), /登录连接尚未配置/);
  assert.throws(() => assertAnonKey("sb_secret_short"), /登录连接尚未配置/);
});

test("session and logout use the iOS account routes and there is no anonymous signup request", () => {
  const session = applicationSessionRequest(config, "access-token");
  assert.equal(session.url, `${edge}/catpaw/account/v1/session`);
  assert.equal(session.headers.Authorization, "Bearer access-token");
  assert.equal(session.headers.apikey, anon);
  const appLogout = applicationLogoutRequest(config, "access-token");
  assert.equal(appLogout.url, `${edge}/catpaw/account/v1/logout`);
  assert.equal(appLogout.body, "{}");
  const authLogout = authLogoutRequest(config, "access-token");
  assert.equal(authLogout.url, `${project}/auth/v1/logout?scope=local`);
});

test("PKCE authorize and token exchange match the iOS query", () => {
  const url = new URL(authorizeUrl(config, "google", "challenge-value", "https://web.example/auth/callback"));
  assert.equal(url.origin + url.pathname, `${project}/auth/v1/authorize`);
  assert.equal(url.searchParams.get("provider"), "google");
  assert.equal(url.searchParams.get("code_challenge_method"), "s256");
  assert.equal(url.searchParams.get("code_challenge"), "challenge-value");
  assert.equal(url.searchParams.get("redirect_to"), "https://web.example/auth/callback");
  const exchange = pkceExchangeRequest(config, "auth-code-1", "verifier-value");
  assert.equal(exchange.url, `${project}/auth/v1/token?grant_type=pkce`);
  assert.deepEqual(JSON.parse(exchange.body), { auth_code: "auth-code-1", code_verifier: "verifier-value" });
});

test("oauth callback refuses implicit tokens and accepts a code", () => {
  assert.throws(() => parseOAuthCallback("https://web.example/auth/callback#access_token=abc"), /不接受外部链接/);
  assert.equal(parseOAuthCallback("https://web.example/auth/callback?error=access_denied&error_code=access_denied").kind, "cancelled");
  assert.deepEqual(parseOAuthCallback("https://web.example/auth/callback?code=abc.def_12"), { kind: "code", code: "abc.def_12" });
});

test("token and session parsing keep a single local_output scope", () => {
  const credential = credentialFromToken(
    {
      access_token: "a",
      refresh_token: "r",
      token_type: "bearer",
      expires_in: 3600,
      user: { id: "11111111-1111-1111-1111-111111111111", email: "parent@example.com", is_anonymous: false },
    },
    1_000,
  );
  assert.equal(credential.user.isAnonymous, false);
  assert.throws(
    () =>
      credentialFromToken({
        access_token: "a",
        refresh_token: "r",
        token_type: "bearer",
        expires_in: 3600,
        user: { id: "11111111-1111-1111-1111-111111111111", email: null, is_anonymous: true },
      }),
    /不提供体验登录/,
  );
  assert.equal(credential.expiresAt, 3_601_000);
  const scope = scopeFromSession(
    {
      schema_version: 1,
      subject_id: credential.user.id,
      scopes: [
        {
          scope_id: "22222222-2222-2222-2222-222222222222",
          profile_id: "33333333-3333-3333-3333-333333333333",
          workspace_id: "44444444-4444-4444-4444-444444444444",
          permissions: { local_output: true },
        },
      ],
    },
    credential.user.id,
  );
  assert.equal(scope, "22222222-2222-2222-2222-222222222222");
  assert.throws(() => scopeFromSession({ schema_version: 1, subject_id: credential.user.id, scopes: [] }, credential.user.id));
});

test("chat headers match the iOS plain-turn set and omit device tools", () => {
  const headers = chatHeaders({
    authorization: "Bearer token",
    anonKey: anon,
    edgeOrigin: "https://example.supabase.co/functions/v1/catpaw-api",
    route: { route: "gemini", model: "gemini-flash", knownRouteIds: ["gemini"] },
    modelsByRoute: { gemini: ["gemini-flash"] },
    liveText: true,
    approval: true,
    memory: "1",
    memoryProposal: true,
    timeZone: "Asia/Shanghai",
  });
  assert.equal(headers["X-Catpaw-Route"], "gemini");
  assert.equal(headers["X-Catpaw-Model"], "gemini-flash");
  assert.equal(headers["X-Catpaw-Route-Events"], "1");
  assert.equal(headers["X-Catpaw-Reasoning-Summary"], "1");
  assert.equal(headers["X-Catpaw-Live-Text"], "1");
  assert.equal(headers["X-Catpaw-Approval-Contract"], "catpaw.approval.v1");
  assert.equal(headers["X-Catpaw-UI-Contract"], "catpaw.ui.v1");
  assert.equal(headers["X-Catpaw-UI-Kinds"], "card,choices,confirm,event,form,list,place,progress,sheet,followups");
  assert.equal(headers["X-Catpaw-Memory-Proposal-Contract"], "catpaw.memory-proposal.v1");
  assert.equal(headers["X-Catpaw-Use-Memory"], "1");
  assert.equal(headers["X-Catpaw-Time-Zone"], "Asia/Shanghai");
  assert.equal(headers["X-Catpaw-Device-Tools"], undefined);
  assert.equal(headers["X-Catpaw-Device-Contract"], undefined);
  const withDevices = chatHeaders({
    authorization: "Bearer token",
    anonKey: anon,
    edgeOrigin: "https://example.supabase.co/functions/v1/catpaw-api",
    route: { route: "auto", model: null, knownRouteIds: [] },
    modelsByRoute: {},
    liveText: true,
    approval: true,
    deviceTools: ["phone", "email", "maps", "calendar"],
  });
  assert.equal(withDevices["X-Catpaw-Device-Contract"], "catpaw.device.v1");
  assert.equal(withDevices["X-Catpaw-Device-Tools"], "phone,email,maps,calendar");
  assert.ok(!withDevices["X-Catpaw-Device-Tools"].includes("calendar_list"));
  assert.equal(
    deviceResultUrl(config, "22222222-2222-2222-2222-222222222222", "cccccccc-cccc-cccc-cccc-cccccccccccc", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 4),
    `${edge}/catpaw/chat/v1/scopes/22222222-2222-2222-2222-222222222222/conversations/cccccccc-cccc-cccc-cccc-cccccccccccc/turns/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/device-result?after_sequence=4`,
  );
  assert.equal(headerRoute({ route: "missing", model: null, knownRouteIds: ["gemini"] }), "auto");
  assert.equal(headerModel({ route: "gemini", model: "other", knownRouteIds: ["gemini"] }, { gemini: ["gemini-flash"] }), null);
});

test("a plain turn posts the iOS body, idempotency key and scope path", () => {
  const body = turnBody({
    turnId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    revision: 3,
    grantEpoch: 2,
    messageId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    text: "你好",
  });
  const request = plainTurnRequest({
    config,
    accessToken: "token",
    scopeId: "22222222-2222-2222-2222-222222222222",
    conversationId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    body,
    route: { route: "auto", model: null, knownRouteIds: [] },
    modelsByRoute: {},
    useMemory: true,
    timeZone: "Asia/Shanghai",
  });
  assert.equal(
    request.url,
    `${edge}/catpaw/chat/v1/scopes/22222222-2222-2222-2222-222222222222/conversations/cccccccc-cccc-cccc-cccc-cccccccccccc/turns`,
  );
  assert.equal(request.headers["Idempotency-Key"], "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  assert.equal(request.headers["X-Catpaw-Route"], "auto");
  assert.deepEqual(JSON.parse(request.body).message, { id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", text: "你好" });
});

test("endpoint join rejects traversal and SSE framing matches the iOS reader", () => {
  assert.throws(() => joinEndpoint(edge, "/catpaw/../readyz"));
  const framed = takeSseLines("data: {\"type\":\"text_delta\"}\r\n: keepalive\r\n\npartial");
  assert.deepEqual(framed.lines, ['data: {"type":"text_delta"}', ": keepalive", ""]);
  assert.equal(framed.rest, "partial");
  assert.equal(parseSseData(": keepalive"), null);
  assert.deepEqual(parseSseData('data: {"ok":true}'), { ok: true });
  assert.deepEqual(textDelta({ type: "text_delta", sequence: 2, data: { message_id: "m", text: "你" } }), { messageId: "m", text: "你", sequence: 2 });
  assert.deepEqual(textPreview({ type: "text_preview", data: { live: true, message_id: "m", text: "你好", offset: 0 } })?.text, "你好");
});

test("sidebar search pages 40 rows and starters follow the iOS hour order", () => {
  const records = Array.from({ length: 45 }, (_, index) => ({ id: String(index), title: index === 3 ? "买菜" : `对话 ${index}`, preview: "" }));
  assert.equal(matchConversations(records, "买菜").length, 1);
  assert.equal(nextVisibleCount(PAGE_SIZE, records.length), 45);
  assert.equal(orderedStarters(8)[0].id, "schedule");
  assert.equal(orderedStarters(14)[0].id, "meeting");
  assert.equal(orderedStarters(20)[0].id, "reminder");
  assert.equal(inviteCodeValid("cp-abcd-efgh-ijkl"), true);
  assert.equal(inviteCodeValid("short"), false);
  assert.equal(imageMime(Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10])), "image/png");
  assert.equal(imageMime(Uint8Array.from([255, 216, 255, 1])), "image/jpeg");
  assert.equal(imageMime(Uint8Array.from([1, 2, 3])), null);
  const followed = splitFollowups("正文\n```catpaw-card:followups\n明天几点？\n要不要买菜？\n```", true);
  assert.equal(followed.text, "正文");
  assert.deepEqual(followed.questions, ["明天几点？", "要不要买菜？"]);
  assert.deepEqual(splitFollowups("正文\n```catpaw-card:followups\n明天几点？", false).questions, []);
});
