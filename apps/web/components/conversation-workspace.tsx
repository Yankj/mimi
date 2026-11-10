"use client";

import Image from "next/image";
import { useEffect, useMemo, useRef, useState } from "react";
import { copy, orderedStarters } from "@/lib/copy";
import type { StoredSession } from "@/lib/browser-session";
import {
  MAX_ATTACHMENT_BYTES,
  acceptedWorkTurnsUrl,
  actionDecisionUrl,
  actionsUrl,
  artifactUrl,
  asRecord,
  attachmentCompleteUrl,
  attachmentCreateUrl,
  cancelTurnRequest,
  canonicalJson,
  chatHeaders,
  controlOperationUrl,
  controlUrl,
  conversationPath,
  enrollmentUrl,
  scopePath,
  imageMime,
  inboxUrl,
  inviteCodeValid,
  isUuid,
  joinEndpoint,
  matchConversations,
  memoryCommandsUrl,
  memoryStateUrl,
  modelConsentUrl,
  modelRoutePrivacyUrl,
  modelRoutesUrl,
  nextVisibleCount,
  numberField,
  PAGE_SIZE,
  parseSseData,
  approvalDecisionUrl,
  plainTurnRequest,
  planUrl,
  readyzUrl,
  resumeEventsUrl,
  retryBusyUrl,
  sha256Bytes,
  sha256Hex,
  stringField,
  splitFollowups,
  takeSseLines,
  textDelta,
  textPreview,
  turnBody,
  type PublicConfig,
  type RouteChoice,
} from "@/lib/wire";

type Message = {
  id: string;
  role: "user" | "assistant";
  text: string;
  turnId: string;
  complete: boolean;
  reasoning?: string;
  thinkingMs?: number;
  hidden?: boolean;
};

type Chat = {
  id: string;
  title: string;
  preview: string;
  updatedAt: string;
  revision: number;
  grantEpoch: number;
  messages: Message[];
};

type Card = { id: string; kind: string; title: string; body: string; raw: Record<string, unknown> };
type RouteInfo = { id: string; display_name: string; models: { id: string }[]; status?: string };

const chatKey = (subject: string) => `mimi.web.chats.v1.${subject}`;
const variantKey = (subject: string, conversation: string) => `mimi.web.variants.v1.${subject}.${conversation}`;
const feedbackKey = "mimi.web.feedback.v1";
const reactionKey = "mimi.web.reactions.v1";
const consentKey = (subject: string) => `mimi.web.consent.v1.${subject}`;
const routeKey = "mimi.web.route.v1";

function loadChats(subject: string): Chat[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(chatKey(subject)) || "[]") as Chat[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveChats(subject: string, chats: Chat[]) {
  localStorage.setItem(chatKey(subject), JSON.stringify(chats.slice(0, 80)));
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

export function ConversationWorkspace({
  config,
  session,
  authorize,
  onSignOut,
  onRestart,
  onClose,
}: {
  config: PublicConfig;
  session: StoredSession;
  authorize: () => Promise<string>;
  onSignOut: () => void;
  onRestart: () => void;
  onClose: () => void;
}) {
  const [chats, setChats] = useState<Chat[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [visible, setVisible] = useState(PAGE_SIZE);
  const [sidebar, setSidebar] = useState(false);
  const [panel, setPanel] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [routes, setRoutes] = useState<RouteInfo[]>([]);
  const [route, setRoute] = useState("auto");
  const [model, setModel] = useState<string | null>(null);
  const [connection, setConnection] = useState<string>(copy.disconnected);
  const [useMemory, setUseMemory] = useState(true);
  const [consent, setConsent] = useState<{ title: string; body: string; routeHash: string; revision: number } | null>(null);
  const [menu, setMenu] = useState<{ id: string; text: string; role: "user" | "assistant"; x: number; y: number } | null>(null);
  const [feedback, setFeedback] = useState<Record<string, "up" | "down">>({});
  const [reactions, setReactions] = useState<Record<string, string>>({});
  const [variants, setVariants] = useState<Record<string, { ids: string[]; index: number }>>({});
  const [cards, setCards] = useState<Card[]>([]);
  const [detail, setDetail] = useState<string>("");
  const [reduceMotion, setReduceMotion] = useState(false);
  const [speaking, setSpeaking] = useState<string | null>(null);
  const [signOutOpen, setSignOutOpen] = useState(false);
  const [invite, setInvite] = useState("");
  const [inviteMessage, setInviteMessage] = useState<string | null>(null);
  const [memoryState, setMemoryState] = useState<unknown>(null);
  const [inbox, setInbox] = useState<{ items: Record<string, unknown>[]; cursor: string | null }>({ items: [], cursor: null });
  const [workText, setWorkText] = useState("");
  const abortRef = useRef<AbortController | null>(null);
  const sequenceRef = useRef(0);
  const variantRoot = useRef<string | null>(null);
  const turnRef = useRef<string | null>(null);
  const active = chats.find((chat) => chat.id === activeId) ?? null;
  const choice: RouteChoice = { route, model, knownRouteIds: routes.map((item) => item.id) };
  const modelsByRoute = Object.fromEntries(routes.map((item) => [item.id, item.models.map((entry) => entry.id)]));
  const shown = useMemo(() => matchConversations(chats.map((chat) => ({ id: chat.id, title: chat.title || copy.untitled, preview: chat.preview })), query), [chats, query]);
  const page = shown.slice(0, visible);
  const hour = new Date().getHours();

  useEffect(() => {
    const stored = loadChats(session.userId);
    setChats(stored);
    setActiveId(stored[0]?.id ?? null);
    try {
      setFeedback(JSON.parse(localStorage.getItem(feedbackKey) || "{}"));
      setReactions(JSON.parse(localStorage.getItem(reactionKey) || "{}"));
      const saved = JSON.parse(localStorage.getItem(routeKey) || "{}") as { route?: string; model?: string | null };
      if (saved.route) setRoute(saved.route);
      if (saved.model) setModel(saved.model);
    } catch {
      /* local preference is optional */
    }
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const apply = () => setReduceMotion(media.matches);
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [session.userId]);

  useEffect(() => {
    if (!activeId) return;
    try {
      setVariants(JSON.parse(localStorage.getItem(variantKey(session.userId, activeId)) || "{}"));
    } catch {
      setVariants({});
    }
  }, [activeId, session.userId]);

  useEffect(() => {
    void (async () => {
      try {
        const token = await authorize();
        const headers = chatHeaders({ authorization: `Bearer ${token}`, anonKey: config.anonKey, edgeOrigin: config.edgeOrigin, route: choice, modelsByRoute });
        const [readyResponse, routeResponse] = await Promise.all([
          fetch(readyzUrl(config), { headers, cache: "no-store" }),
          fetch(modelRoutesUrl(config), { headers, cache: "no-store" }),
        ]);
        const ready = asRecord(await readJson(readyResponse));
        setConnection(ready?.ready === true ? copy.connected : copy.connectionDown);
        const catalog = asRecord(await readJson(routeResponse));
        if (stringField(catalog, "contract") === "catpaw.model-routes.v1" && Array.isArray(catalog?.routes)) {
          setRoutes(catalog.routes.filter((item) => asRecord(item)?.id) as RouteInfo[]);
        }
      } catch (cause) {
        setConnection(copy.disconnected);
        setError(cause instanceof Error ? cause.message : copy.connectionDown);
      }
    })();
    // Route catalog is loaded once per open; later picker changes must not refetch in a loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, session.scopeId]);

  function persist(next: Chat[]) {
    setChats(next);
    saveChats(session.userId, next);
  }

  function visibleMessage(message: Message) {
    if (message.hidden) return false;
    for (const group of Object.values(variants)) {
      if (!group.ids.includes(message.id)) continue;
      return group.ids[group.index] === message.id;
    }
    return true;
  }

  async function authedHeaders(extra?: Partial<Parameters<typeof chatHeaders>[0]>) {
    const token = await authorize();
    return chatHeaders({
      authorization: `Bearer ${token}`,
      anonKey: config.anonKey,
      edgeOrigin: config.edgeOrigin,
      route: choice,
      modelsByRoute,
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      memory: useMemory ? "1" : "0",
      ...extra,
    });
  }

  async function ensureConversation(id: string) {
    const headers = await authedHeaders();
    const url = joinEndpoint(config.edgeOrigin, conversationPath(session.scopeId, id));
    let response = await fetch(url, { headers, cache: "no-store" });
    if (response.status === 404) {
      response = await fetch(joinEndpoint(config.edgeOrigin, `${scopePath(session.scopeId)}/conversations`), {
        method: "POST",
        headers,
        body: JSON.stringify({ conversation_id: id }),
      });
    }
    const json = asRecord(await readJson(response));
    const conversation = asRecord(json?.conversation);
    if (response.status < 200 || response.status >= 300 || !conversation) throw new Error("暂时无法打开工作区，本机对话仍会保留。");
    if (stringField(conversation, "state") !== "active" || stringField(conversation, "policy") !== "conversation-local-output-v1") {
      throw new Error("服务返回了不匹配的对话或工具结果");
    }
    return { revision: numberField(conversation, "revision"), grantEpoch: numberField(conversation, "grant_epoch") };
  }

  async function ensureConsent(conversationId: string) {
    const token = await authorize();
    const headers = chatHeaders({ authorization: `Bearer ${token}`, anonKey: config.anonKey, edgeOrigin: config.edgeOrigin, route: choice, modelsByRoute });
    const privacy = asRecord(await readJson(await fetch(modelRoutePrivacyUrl(config), { headers, cache: "no-store" })));
    const ui = asRecord(privacy?.ui);
    const routeHash = stringField(privacy, "route_hash");
    const stored = asRecord(await readJson(await fetch(modelConsentUrl(config, session.scopeId, conversationId), { headers, cache: "no-store" })));
    const prior = asRecord(stored?.consent);
    const revision = Number.isInteger(numberField(prior, "revision")) ? numberField(prior, "revision") : 0;
    const remembered = localStorage.getItem(consentKey(session.userId));
    if (prior?.decision === "allow" && stringField(prior, "route_hash") === routeHash) return;
    if (prior?.decision !== "deny" && remembered === routeHash && routeHash) {
      const carried = await fetch(modelConsentUrl(config, session.scopeId, conversationId), {
        method: "POST",
        headers,
        body: JSON.stringify({ decision: "allow", expected_revision: revision, route_hash: routeHash }),
      });
      if (carried.ok) return;
    }
    if (!stringField(ui, "title") || !stringField(ui, "body") || !routeHash) throw new Error("确认发送许可");
    setConsent({ title: stringField(ui, "title"), body: stringField(ui, "body"), routeHash, revision });
    throw new Error("consent");
  }

  async function allowConsent() {
    if (!consent || !activeId) return;
    const headers = await authedHeaders();
    const response = await fetch(modelConsentUrl(config, session.scopeId, activeId), {
      method: "POST",
      headers,
      body: JSON.stringify({ decision: "allow", expected_revision: consent.revision, route_hash: consent.routeHash }),
    });
    if (!response.ok) {
      setError("登录响应未通过校验，请重新登录。");
      return;
    }
    localStorage.setItem(consentKey(session.userId), consent.routeHash);
    setConsent(null);
  }

  async function consumeStream(response: Response, chatId: string, turnId: string) {
    if (!response.body || !response.ok) {
      const json = await readJson(response.clone());
      const problem = asRecord(asRecord(json)?.error) ?? asRecord(json);
      const code = stringField(problem, "code");
      throw new Error(code || (response.ok ? "服务返回了不匹配的对话或工具结果" : "网络请求未完成。请检查连接后再读取进展。"));
    }
    const mime = response.headers.get("content-type") || "";
    if (mime.includes("application/json")) {
      const json = asRecord(await readJson(response));
      const events = asRecord(json?.turn)?.events;
      if (Array.isArray(events)) for (const event of events) applyEvent(chatId, turnId, event);
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      const taken = takeSseLines(buffer);
      buffer = taken.rest;
      for (const line of taken.lines) {
        const event = parseSseData(line);
        if (event) applyEvent(chatId, turnId, event);
      }
    }
  }

  function applyEvent(chatId: string, turnId: string, event: unknown) {
    const row = asRecord(event);
    const type = stringField(row, "type");
    const sequence = numberField(row, "sequence");
    if (Number.isFinite(sequence)) sequenceRef.current = Math.max(sequenceRef.current, sequence);
    const delta = textDelta(event);
    const preview = textPreview(event);
    if (delta || preview) {
      const messageId = (delta ?? preview)!.messageId;
      setChats((current) => {
        const next = current.map((chat) => {
          if (chat.id !== chatId) return chat;
          const messages = chat.messages.slice();
          let index = messages.findIndex((message) => message.id === messageId);
          const created = index < 0;
          if (created) {
            messages.push({ id: messageId, role: "assistant", text: "", turnId, complete: false });
            index = messages.length - 1;
          }
          const message = { ...messages[index] };
          if (delta) message.text += delta.text;
          if (preview && !delta) message.text = message.text.slice(0, preview.offset) + preview.text;
          messages[index] = message;
          if (created) rememberVariant(messageId);
          return { ...chat, messages, preview: messages.at(-1)?.text.slice(0, 80) || chat.preview, updatedAt: new Date().toISOString() };
        });
        saveChats(session.userId, next);
        return next;
      });
    }
    if (type === "reasoning_summary") {
      const data = asRecord(row?.data);
      const messageId = stringField(data, "message_id");
      setChats((current) => current.map((chat) => chat.id !== chatId ? chat : {
        ...chat,
        messages: chat.messages.map((message) => message.id === messageId ? { ...message, reasoning: stringField(data, "text"), thinkingMs: numberField(data, "thinking_ms") } : message),
      }));
    }
    if (type === "turn_completed") {
      setChats((current) => current.map((chat) => chat.id !== chatId ? chat : { ...chat, messages: chat.messages.map((message) => message.turnId === turnId ? { ...message, complete: true } : message) }));
    }
    if (type === "ui.block.update" || type === "ui.block.resolve") {
      const data = asRecord(row?.data) ?? {};
      const id = stringField(data, "id");
      setCards((current) => current.map((card) => card.id === id ? { ...card, title: stringField(data, "title") || card.title, body: stringField(data, "body") || card.body, raw: { ...card.raw, ...data } } : card));
    }
    if (type === "approval_requested" || type === "ui.block" || type === "tool_result") {
      const data = asRecord(row?.data) ?? {};
      const id = stringField(data, "id") || stringField(data, "interrupt_id") || stringField(data, "interruptId") || `${turnId}:${sequence}`;
      setCards((current) => {
        const card: Card = {
          id,
          kind: type === "ui.block" ? stringField(data, "kind") || type : type,
          title: stringField(data, "summary") || stringField(data, "title") || stringField(data, "name") || type,
          body: stringField(data, "body") || JSON.stringify(data).slice(0, 800),
          raw: { ...data, turn_id: stringField(data, "turn_id") || turnId },
        };
        return current.some((item) => item.id === id) ? current.map((item) => item.id === id ? card : item) : [...current, card];
      });
    }
    if (type === "model_route") {
      const data = asRecord(row?.data);
      const name = stringField(data, "model") || stringField(data, "route");
      if (name) setConnection(`${copy.connected} · ${name}`);
    }
    if (type === "turn_failed") setError("需要处理");
  }

  function rememberVariant(messageId: string) {
    const root = variantRoot.current;
    const chatId = activeId;
    if (!root || !chatId || messageId === root) return;
    setVariants((current) => {
      const group = current[root] ?? { ids: [root], index: 0 };
      if (group.ids.includes(messageId)) return current;
      const next = { ...current, [root]: { ids: [...group.ids, messageId], index: group.ids.length } };
      localStorage.setItem(variantKey(session.userId, chatId), JSON.stringify(next));
      return next;
    });
  }

  async function send(text: string, variantOf?: string) {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    setError(null);
    const chatId = activeId ?? crypto.randomUUID();
    const turnId = crypto.randomUUID();
    const userId = crypto.randomUUID();
    const userMessage: Message = { id: userId, role: "user", text: trimmed, turnId, complete: true, hidden: Boolean(variantOf) };
    const existing = chats.find((chat) => chat.id === chatId);
    const nextChat: Chat = existing ?? { id: chatId, title: trimmed.slice(0, 24), preview: "", updatedAt: new Date().toISOString(), revision: 0, grantEpoch: 0, messages: [] };
    const withUser = { ...nextChat, messages: [...nextChat.messages, userMessage], updatedAt: new Date().toISOString(), preview: "草稿" };
    const nextChats = existing ? chats.map((chat) => (chat.id === chatId ? withUser : chat)) : [withUser, ...chats];
    persist(nextChats);
    setActiveId(chatId);
    setDraft("");
    variantRoot.current = variantOf ?? null;
    turnRef.current = turnId;
    setBusy(true);
    sequenceRef.current = 0;
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await ensureConsent(chatId);
      const opened = await ensureConversation(chatId);
      const body = turnBody({ turnId, revision: opened.revision, grantEpoch: opened.grantEpoch, messageId: userId, text: trimmed });
      const request = plainTurnRequest({
        config,
        accessToken: await authorize(),
        scopeId: session.scopeId,
        conversationId: chatId,
        body,
        route: choice,
        modelsByRoute,
        useMemory,
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      });
      const response = await fetch(request.url, { method: request.method, headers: request.headers, body: request.body, signal: controller.signal });
      await consumeStream(response, chatId, turnId);
      setChats((current) => current.map((chat) => chat.id === chatId ? { ...chat, revision: opened.revision + 1, grantEpoch: opened.grantEpoch, preview: "" } : chat));
    } catch (cause) {
      if (cause instanceof Error && cause.message !== "consent" && cause.name !== "AbortError") setError(cause.message);
    } finally {
      setBusy(false);
      abortRef.current = null;
    }
  }

  async function stop() {
    abortRef.current?.abort();
    if (!active) return;
    const turnId = [...active.messages].reverse().find((message) => !message.complete)?.turnId;
    if (!turnId) return;
    const request = cancelTurnRequest(config, await authorize(), session.scopeId, active.id, turnId, choice, modelsByRoute);
    await fetch(request.url, { method: request.method, headers: request.headers, body: request.body });
  }

  async function resume(kind: "events" | "busy") {
    if (!active) return;
    const message = [...active.messages].reverse().find((item) => item.role === "assistant");
    if (!message) return;
    setBusy(true);
    try {
      const headers = await authedHeaders({ liveText: true, approval: true, memoryProposal: true });
      const url = kind === "events" ? resumeEventsUrl(config, session.scopeId, active.id, message.turnId, sequenceRef.current) : retryBusyUrl(config, session.scopeId, active.id, message.turnId);
      const response = await fetch(url, { method: kind === "events" ? "GET" : "POST", headers, body: kind === "busy" ? "{}" : undefined });
      await consumeStream(response, active.id, message.turnId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : copy.connectionDown);
    } finally {
      setBusy(false);
    }
  }

  async function upload(file: File) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const mime = imageMime(bytes);
    if (!mime) return setError(copy.attachmentType);
    if (bytes.byteLength > MAX_ATTACHMENT_BYTES) return setError(copy.attachmentTooBig);
    const id = crypto.randomUUID();
    const headers = await authedHeaders();
    const created = await fetch(attachmentCreateUrl(config, session.scopeId), {
      method: "POST",
      headers,
      body: JSON.stringify({ attachment_id: id, mime_type: mime, size_bytes: bytes.byteLength, sha256: await sha256Bytes(bytes) }),
    });
    const json = asRecord(await readJson(created));
    const upload = asRecord(json?.upload);
    if (upload?.method === "PUT" && typeof upload.url === "string" && upload.url.startsWith("https://")) {
      const putHeaders = asRecord(upload.headers) ?? {};
      await fetch(upload.url, { method: "PUT", headers: Object.fromEntries(Object.entries(putHeaders).map(([key, value]) => [key, String(value)])), body: bytes });
    }
    const ready = await fetch(attachmentCompleteUrl(config, session.scopeId, id), { method: "POST", headers, body: "{}" });
    if (!ready.ok) return setError("暂时连不上服务，请稍后重试");
    setDraft((current) => current);
    setError(null);
    sessionStorage.setItem("mimi.web.pendingAttachment", id);
  }

  async function sendWithAttachment() {
    const attachment = sessionStorage.getItem("mimi.web.pendingAttachment");
    if (!activeId || !draft.trim()) return send(draft);
    if (!attachment) return send(draft);
    sessionStorage.removeItem("mimi.web.pendingAttachment");
    const chatId = activeId;
    const turnId = crypto.randomUUID();
    const messageId = crypto.randomUUID();
    setBusy(true);
    try {
      const opened = await ensureConversation(chatId);
      const body = turnBody({ turnId, revision: opened.revision, grantEpoch: opened.grantEpoch, messageId, text: draft.trim(), attachmentIds: [attachment] });
      const request = plainTurnRequest({ config, accessToken: await authorize(), scopeId: session.scopeId, conversationId: chatId, body, route: choice, modelsByRoute, useMemory, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone });
      const response = await fetch(request.url, { method: "POST", headers: request.headers, body: request.body });
      await consumeStream(response, chatId, turnId);
      setDraft("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : copy.attachmentType);
    } finally {
      setBusy(false);
    }
  }

  async function loadInbox(cursor: string | null) {
    const headers = await authedHeaders();
    const response = await fetch(inboxUrl(config, session.scopeId, cursor), { headers, cache: "no-store" });
    const json = asRecord(await readJson(response));
    const items = Array.isArray(json?.items) ? json.items.flatMap((item) => { const row = asRecord(item); return row ? [row] : []; }) : [];
    setInbox((current) => ({ items: cursor ? [...current.items, ...items] : items, cursor: stringField(json, "next_cursor") || null }));
  }

  async function openArtifact(artifactId: string, version: number) {
    const headers = await authedHeaders();
    const json = asRecord(await readJson(await fetch(artifactUrl(config, session.scopeId, artifactId, version), { headers })));
    const artifact = asRecord(json?.artifact);
    setDetail(`${stringField(artifact, "body") || JSON.stringify(json, null, 2)}`);
    setPanel("detail");
  }

  async function loadPlan(runId: string) {
    const headers = await authedHeaders();
    setDetail(JSON.stringify(await readJson(await fetch(planUrl(config, session.scopeId, runId), { headers })), null, 2));
    setPanel("detail");
  }

  async function loadActions(runId: string) {
    const headers = await authedHeaders();
    const json = await readJson(await fetch(actionsUrl(config, session.scopeId, runId), { headers }));
    setDetail(JSON.stringify(json, null, 2));
    setPanel("actions");
    sessionStorage.setItem("mimi.web.actionRun", runId);
  }

  async function decide(actionId: string, decision: "approve" | "deny", revision: number, digest: string, grantEpoch: number) {
    const runId = sessionStorage.getItem("mimi.web.actionRun");
    if (!runId || !isUuid(actionId)) return;
    const headers = await authedHeaders();
    await fetch(actionDecisionUrl(config, session.scopeId, runId, actionId), {
      method: "POST",
      headers,
      body: JSON.stringify({ operation_id: crypto.randomUUID(), decision, expected_revision: revision, action_digest: digest, expected_grant_epoch: grantEpoch }),
    });
    await loadActions(runId);
  }

  async function loadControl(runId: string) {
    const headers = await authedHeaders();
    const json = await readJson(await fetch(controlUrl(config, session.scopeId, runId), { headers }));
    sessionStorage.setItem("mimi.web.control", JSON.stringify(json));
    setDetail(JSON.stringify(json, null, 2));
    setPanel("control");
  }

  async function submitControl(action: "pause" | "resume" | "revoke") {
    const parsed = asRecord(JSON.parse(sessionStorage.getItem("mimi.web.control") || "null"));
    const control = asRecord(parsed?.control);
    const plan = asRecord(control?.plan_reference);
    const runId = stringField(asRecord(control?.binding) ?? control, "run_id") || stringField(control, "run_id");
    if (!control || !runId) return;
    const headers = await authedHeaders();
    await fetch(controlOperationUrl(config, session.scopeId, runId), {
      method: "POST",
      headers,
      body: JSON.stringify({
        operation_id: crypto.randomUUID(),
        action,
        expected_execution_revision: numberField(control, "execution_revision"),
        expected_snapshot_hash: stringField(control, "snapshot_hash"),
        expected_grant_epoch: numberField(control, "grant_epoch"),
        expected_plan_revision: numberField(plan, "revision"),
        expected_plan_hash: stringField(plan, "plan_hash"),
      }),
    });
    await loadControl(runId);
  }

  async function submitApproval(card: Card, decision: "approve" | "deny" | "approve_always") {
    if (!active) return;
    const interrupt = stringField(card.raw, "interrupt_id") || card.id;
    const turnId = stringField(card.raw, "turn_id") || active.messages.at(-1)?.turnId;
    if (!turnId) return;
    const headers = await authedHeaders({ liveText: true });
    const response = await fetch(approvalDecisionUrl(config, session.scopeId, active.id, turnId, interrupt, sequenceRef.current), {
      method: "POST",
      headers,
      body: JSON.stringify({ operation_id: crypto.randomUUID(), decision, args_hash: stringField(card.raw, "args_hash") }),
    });
    await consumeStream(response, active.id, turnId);
  }

  async function submitUi(card: Card, decision: "submit" | "dismiss" | "report_error", responseBody?: Record<string, unknown>) {
    if (!active) return;
    const interrupt = stringField(card.raw, "interruptId") || stringField(card.raw, "interrupt_id");
    const turnId = stringField(card.raw, "turn_id") || turnRef.current || active.messages.at(-1)?.turnId || "";
    if (!interrupt || !turnId) return;
    const headers = await authedHeaders({ liveText: true, approval: true });
    const body: Record<string, unknown> = {
      operation_id: crypto.randomUUID(),
      decision,
      args_hash: stringField(card.raw, "argsHash") || stringField(card.raw, "args_hash"),
    };
    if (decision !== "dismiss") body.response = responseBody;
    const response = await fetch(approvalDecisionUrl(config, session.scopeId, active.id, turnId, interrupt, sequenceRef.current), {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    await consumeStream(response, active.id, turnId);
  }

  async function submitAcceptedWork() {
    if (!active || !workText.trim()) return;
    const headers = await authedHeaders();
    const privacy = asRecord(await readJson(await fetch(modelRoutePrivacyUrl(config), { headers })));
    const opened = await ensureConversation(active.id);
    if (!(opened.revision > 0) || !(opened.grantEpoch > 0)) {
      setError("已接受的工作需要服务端已确认的对话修订，这次没有提交。");
      return;
    }
    const routeHash = stringField(privacy, "route_hash");
    if (!/^[a-f0-9]{64}$/.test(routeHash)) {
      setError("模型路线尚未确认，这次工作没有提交。");
      return;
    }
    const turnId = crypto.randomUUID();
    const messageId = crypto.randomUUID();
    const turn = turnBody({ turnId, revision: opened.revision, grantEpoch: opened.grantEpoch, messageId, text: workText.trim() });
    const inputHash = await sha256Hex(canonicalJson(turn));
    const notAfter = new Date(Date.now() + 30 * 60 * 1000).toISOString();
    const wrapper = {
      schema_version: 1,
      contract: "catpaw.accepted-work-intent.v1",
      turn,
      work: {
        decision_id: crypto.randomUUID(),
        input_hash: inputHash,
        route_hash: routeHash,
        tool_policy: "local_output_v1",
        not_after: notAfter,
        execution_budget_ms: 60000,
        limits: { due: 1, model: 1, source: 0 },
      },
    };
    const response = await fetch(acceptedWorkTurnsUrl(config, session.scopeId, active.id), { method: "POST", headers, body: JSON.stringify(wrapper) });
    await consumeStream(response, active.id, turnId);
    setPanel(null);
  }

  async function loadMemory() {
    const headers = await authedHeaders();
    setMemoryState(await readJson(await fetch(memoryStateUrl(config, session.scopeId), { headers })));
    setPanel("memory");
  }

  async function submitMemory(command: Record<string, unknown>) {
    const generation = numberField(asRecord(memoryState), "memory_generation");
    if (!Number.isInteger(generation) || generation < 1) {
      setError("了解你还没有可提交的版本，这次没有发送。");
      return;
    }
    const headers = await authedHeaders({ memoryProposal: true });
    await fetch(memoryCommandsUrl(config, session.scopeId), {
      method: "POST",
      headers,
      body: JSON.stringify({ schema_version: 1, contract: "catpaw.memory.v1", operation_id: crypto.randomUUID(), expected_memory_generation: generation, command }),
    });
    await loadMemory();
  }

  async function submitInvite() {
    if (!inviteCodeValid(invite)) return;
    const token = await authorize();
    const response = await fetch(enrollmentUrl(config, "enroll"), {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ invite_code: invite }),
    });
    setInviteMessage(response.status === 200 ? copy.inviteOk : response.status === 422 ? copy.inviteBad : response.status === 401 ? copy.inviteSignedOut : copy.inviteDown);
    if (response.ok) setInvite("");
  }

  function speak(id: string, text: string) {
    const synth = window.speechSynthesis;
    if (speaking === id) {
      synth.cancel();
      setSpeaking(null);
      return;
    }
    synth.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = /[\u4e00-\u9fff]/.test(text) ? "zh-CN" : "en-US";
    utterance.onend = () => setSpeaking(null);
    setSpeaking(id);
    synth.speak(utterance);
  }

  async function copyText(text: string) {
    await navigator.clipboard.writeText(text);
    setError(copy.copied);
  }

  async function shareText(text: string) {
    if (navigator.share) await navigator.share({ text });
    else await copyText(text);
  }

  function moveVariant(id: string, direction: -1 | 1) {
    if (!activeId) return;
    setVariants((current) => {
      const group = current[id];
      if (!group) return current;
      const index = Math.min(group.ids.length - 1, Math.max(0, group.index + direction));
      const next = { ...current, [id]: { ...group, index } };
      localStorage.setItem(variantKey(session.userId, activeId), JSON.stringify(next));
      return next;
    });
  }

  function rate(id: string, up: boolean) {
    const next = { ...feedback };
    const value = up ? "up" : "down";
    if (next[id] === value) delete next[id];
    else next[id] = value;
    setFeedback(next);
    localStorage.setItem(feedbackKey, JSON.stringify(next));
  }

  const lastAssistant = [...(active?.messages ?? [])].reverse().find((message) => message.role === "assistant" && visibleMessage(message));

  return (
    <div className="sheet" role="dialog" aria-label={copy.advanced}>
      <aside className={sidebar ? "sidebar open" : "sidebar"}>
        <div className="stack">
          <strong>{session.email || "mimi"}</strong>
          <button className="list-button" onClick={() => void loadInbox(null)}>{copy.tasks}</button>
          <button className="list-button" onClick={() => { void loadInbox(null); setPanel("results"); }}>{copy.results}</button>
          <button className="list-button" onClick={() => void loadMemory()}>{copy.memory}</button>
          <div className="quiet">{copy.conversations}</div>
          {page.length === 0 ? <p className="quiet">{query.trim() ? copy.noSearchHits : copy.noConversations}</p> : null}
          {page.map((record) => (
            <button key={record.id} className="list-button" aria-current={record.id === activeId ? "true" : undefined} onClick={() => { setActiveId(record.id); setSidebar(false); }}>
              <span>{record.title || copy.untitled}</span>
              <span className="quiet">{record.preview ? copy.draft : ""}</span>
            </button>
          ))}
          {visible < shown.length ? <button className="text-button" onClick={() => setVisible(nextVisibleCount(visible, shown.length))}>{copy.loadMore}</button> : null}
        </div>
        <div className="bottom-bar">
          <button className="icon-button" aria-label={copy.settings} onClick={() => setPanel("settings")}>⚙</button>
          <label className="search">
            <span className="quiet">{copy.search}</span>
            <input aria-label={copy.searchConversations} value={query} onChange={(event) => { setQuery(event.target.value); setVisible(PAGE_SIZE); }} />
          </label>
          <button className="round ink" aria-label={copy.newChat} onClick={() => { const id = crypto.randomUUID(); const chat = { id, title: copy.newChat, preview: "", updatedAt: new Date().toISOString(), revision: 0, grantEpoch: 0, messages: [] }; persist([chat, ...chats]); setActiveId(id); }}>✎</button>
        </div>
      </aside>
      <section className="transcript">
        <header style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
          <button className="text-button" aria-label={copy.openSidebar} onClick={() => setSidebar(true)}>☰</button>
          <button className="text-button" onClick={() => setPanel("routes")}>{route === "auto" ? copy.auto : route}</button>
          <span className="quiet">{connection}</span>
          <button className="text-button" onClick={onClose}>{copy.close}</button>
        </header>
        {error ? <p className={error === copy.copied ? "ok" : "error"}>{error}</p> : null}
        {!active || active.messages.length === 0 ? (
          <div>
            <Image src="/mascot/idle.png" alt="" width={160} height={160} />
            <h2>{copy.newChat}</h2>
            <div className="starters" aria-label={copy.tryThese}>
              {orderedStarters(hour).map((starter) => (
                <button key={starter.id} className="starter" aria-label={starter.prompt} title={copy.tapToSend} disabled={busy} onClick={() => void send(starter.prompt)}>
                  <strong>{starter.title}</strong>
                  <div className="quiet">{starter.prompt}</div>
                </button>
              ))}
            </div>
          </div>
        ) : null}
        {active?.messages.filter(visibleMessage).map((message) => {
          const streaming = !message.complete && message.role === "assistant";
          const parsed = splitFollowups(message.text, message.complete);
          const tail = !reduceMotion && streaming ? parsed.text.slice(-24) : "";
          const head = tail ? parsed.text.slice(0, -tail.length) : parsed.text;
          return (
            <article key={message.id} className={message.role === "user" ? "bubble user" : "bubble"} onContextMenu={(event) => { event.preventDefault(); setMenu({ id: message.id, text: message.text, role: message.role, x: event.clientX, y: event.clientY }); }} onPointerDown={(event) => {
              if (event.pointerType === "mouse") return;
              const hold = window.setTimeout(() => setMenu({ id: message.id, text: message.text, role: message.role, x: event.clientX, y: event.clientY }), 500);
              const stopHold = () => window.clearTimeout(hold);
              event.currentTarget.addEventListener("pointerup", stopHold, { once: true });
              event.currentTarget.addEventListener("pointercancel", stopHold, { once: true });
            }}>
              {message.reasoning ? <details><summary>{copy.thought}{message.thinkingMs ? ` · ${Math.round(message.thinkingMs / 1000)}s` : ""}</summary><p className="quiet">{message.reasoning}</p></details> : null}
              <p>{head}{tail ? <span className="stream-tail">{tail}</span> : null}</p>
              {message.id === lastAssistant?.id && parsed.questions.length > 0 ? (
                <div className="actions" aria-label={copy.followups}>
                  {parsed.questions.map((question) => <button key={question} disabled={busy} onClick={() => void send(question)}>{question}</button>)}
                </div>
              ) : null}
              {reactions[message.id] ? <button onClick={() => { const next = { ...reactions }; delete next[message.id]; setReactions(next); localStorage.setItem(reactionKey, JSON.stringify(next)); }}>{reactions[message.id]}</button> : null}
              {message.role === "assistant" && message.id === lastAssistant?.id ? (
                <div className="actions">
                  <button aria-label={copy.copyReply} onClick={() => void copyText(message.text)}>⧉</button>
                  <button aria-label={copy.shareReply} onClick={() => void shareText(message.text)}>↑</button>
                  <button aria-label={copy.helpful} aria-pressed={feedback[message.id] === "up"} onClick={() => rate(message.id, true)}>👍</button>
                  <button aria-label={copy.notHelpful} aria-pressed={feedback[message.id] === "down"} onClick={() => rate(message.id, false)}>👎</button>
                  <button aria-label={speaking === message.id ? copy.stopSpeak : copy.speak} onClick={() => speak(message.id, message.text)}>♪</button>
                  <button aria-label={copy.regenerate} disabled={busy} onClick={() => {
                    const index = active.messages.findIndex((item) => item.id === message.id);
                    const prompt = [...active.messages.slice(0, index)].reverse().find((item) => item.role === "user");
                    if (prompt) void send(prompt.text, message.id);
                  }}>↻</button>
                  {variants[message.id] && variants[message.id].ids.length > 1 ? (
                    <span>
                      <button aria-label={copy.previousAnswer} onClick={() => moveVariant(message.id, -1)}>‹</button>
                      {variants[message.id].index + 1}/{variants[message.id].ids.length}
                      <button aria-label={copy.nextAnswer} onClick={() => moveVariant(message.id, 1)}>›</button>
                    </span>
                  ) : null}
                </div>
              ) : null}
            </article>
          );
        })}
        {cards.map((card) => (
          <section key={card.id} className="card">
            <strong>{card.title}</strong>
            <p>{card.body}</p>
            <BlockControls card={card} onDecision={(decision, response) => void submitUi(card, decision, response)} />
            {card.kind === "approval_requested" ? (
              <div className="actions">
                <button className="primary" onClick={() => void submitApproval(card, "approve")}>{copy.approve}</button>
                <button onClick={() => void submitApproval(card, "deny")}>{copy.deny}</button>
                <button onClick={() => void submitApproval(card, "approve_always")}>{copy.approveAlways}</button>
              </div>
            ) : null}
            {stringField(card.raw, "run_id") ? (
              <div className="actions">
                <button onClick={() => void loadPlan(stringField(card.raw, "run_id"))}>{copy.plan}</button>
                <button onClick={() => void loadActions(stringField(card.raw, "run_id"))}>{copy.approval}</button>
                <button onClick={() => void loadControl(stringField(card.raw, "run_id"))}>{copy.control}</button>
              </div>
            ) : null}
          </section>
        ))}
        {busy ? <button onClick={() => void stop()}>{copy.stop}</button> : null}
        {error && !busy ? (
          <div className="actions">
            <button onClick={() => void resume("events")}>{copy.continueFetch}</button>
            <button onClick={() => void resume("busy")}>{copy.retryNow}</button>
          </div>
        ) : null}
        <form className="composer" onSubmit={(event) => { event.preventDefault(); const pending = sessionStorage.getItem("mimi.web.pendingAttachment"); if (pending) void sendWithAttachment(); else void send(draft); }}>
          <label className="icon-button" aria-label={copy.attachment}>
            +
            <input type="file" accept="image/png,image/jpeg" hidden onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); }} />
          </label>
          <textarea aria-label={busy ? copy.continueAsk : copy.ask} placeholder={busy ? copy.continueAsk : copy.ask} value={draft} rows={1} onChange={(event) => setDraft(event.target.value)} />
          <button className="round ink" type="submit" disabled={busy || !draft.trim()} aria-label={copy.send}>↑</button>
        </form>
      </section>
      {menu ? (
        <div className="menu" style={{ left: Math.min(menu.x, window.innerWidth - 240), top: menu.y }} role="menu">
          {[copy.reply, copy.edit, copy.copy, copy.select, speaking === menu.id ? copy.stopSpeak : copy.speak].map((label) => (
            <button key={label} onClick={() => {
              if (label === copy.reply || label === copy.edit) setDraft(menu.text);
              if (label === copy.copy) void copyText(menu.text);
              if (label === copy.select) { setDetail(menu.text); setPanel("select"); }
              if (label === copy.speak || label === copy.stopSpeak) speak(menu.id, menu.text);
              setMenu(null);
            }}>{label}</button>
          ))}
          <button disabled title={copy.webHard}>{copy.savePhoto}</button>
          <button onClick={() => setMenu(null)}>{copy.close}</button>
        </div>
      ) : null}
      {consent ? (
        <div className="dialog-back" role="dialog" aria-label={copy.consentTitle}>
          <div className="dialog">
            <h2>{consent.title}</h2>
            <p>{consent.body}</p>
            <button className="primary" onClick={() => void allowConsent()}>{copy.allowSend}</button>
            <button onClick={() => setConsent(null)}>{copy.deferSend}</button>
          </div>
        </div>
      ) : null}
      {panel ? (
        <div className="dialog-back" role="dialog">
          <div className="dialog">
            <button className="text-button" onClick={() => setPanel(null)}>{copy.close}</button>
            {panel === "settings" ? (
              <div className="stack">
                <h2>{copy.settings}</h2>
                <label><input type="checkbox" checked={useMemory} onChange={(event) => setUseMemory(event.target.checked)} /> {copy.useMemory}</label>
                <button onClick={() => setPanel("invite")}>{copy.invite}</button>
                <button onClick={() => setPanel("connection")}>{copy.connection}</button>
                <button onClick={() => setPanel("work")}>{copy.acceptedWork}</button>
                <p className="quiet">{copy.version} 0.1.0 · {copy.build} web-batch-1</p>
                <button onClick={onRestart}>{copy.restart}</button>
                <button className="danger" onClick={() => setSignOutOpen(true)}>{copy.signOut}</button>
              </div>
            ) : null}
            {panel === "invite" ? (
              <div className="stack">
                <h2>{copy.invite}</h2>
                <p className="quiet">{copy.inviteHint}</p>
                <input className="field" aria-label={copy.inviteField} placeholder={copy.invitePlaceholder} value={invite} onChange={(event) => setInvite(event.target.value)} />
                <button className="primary" disabled={!inviteCodeValid(invite)} onClick={() => void submitInvite()}>{copy.submit}</button>
                {inviteMessage ? <p>{inviteMessage}</p> : null}
              </div>
            ) : null}
            {panel === "connection" ? <div><h2>{copy.connection}</h2><p>{connection}</p><button onClick={() => setPanel("routes")}>{copy.modelRoute}</button></div> : null}
            {panel === "routes" ? (
              <div className="stack">
                <h2>{copy.modelRoute}</h2>
                <button onClick={() => { setRoute("auto"); setModel(null); localStorage.setItem(routeKey, JSON.stringify({ route: "auto", model: null })); }}>{copy.auto}</button>
                {routes.map((item) => (
                  <button key={item.id} onClick={() => { setRoute(item.id); setModel(item.models[0]?.id ?? null); localStorage.setItem(routeKey, JSON.stringify({ route: item.id, model: item.models[0]?.id ?? null })); }}>
                    {item.display_name} · {item.status === "online" ? copy.connected : copy.disconnected}
                  </button>
                ))}
              </div>
            ) : null}
            {panel === "memory" ? (
              <MemoryPanel state={memoryState} conversationId={activeId} onSubmit={(command) => void submitMemory(command)} />
            ) : null}
            {panel === "results" || panel === "tasks" ? (
              <div className="stack">
                <h2>{panel === "results" ? copy.results : copy.tasks}</h2>
                {inbox.items.length === 0 ? <p>{copy.inboxEmpty}</p> : null}
                {inbox.items.filter((item) => panel === "results" ? Array.isArray(item.artifacts) && item.artifacts.length > 0 : true).map((item) => (
                  <article key={stringField(asRecord(item.binding), "run_id") || stringField(item, "title")}>
                    <strong>{stringField(item, "title")}</strong>
                    <div className="actions">
                      <button onClick={() => void loadPlan(stringField(asRecord(item.binding), "run_id"))}>{copy.plan}</button>
                      <button onClick={() => void loadActions(stringField(asRecord(item.binding), "run_id"))}>{copy.approval}</button>
                      <button onClick={() => void loadControl(stringField(asRecord(item.binding), "run_id"))}>{copy.control}</button>
                      {Array.isArray(item.artifacts) ? item.artifacts.map((artifact) => {
                        const row = asRecord(artifact);
                        const id = stringField(row, "artifact_id");
                        return <button key={id} onClick={() => void openArtifact(id, numberField(row, "version"))}>{copy.artifact}</button>;
                      }) : null}
                    </div>
                  </article>
                ))}
                {inbox.cursor ? <button onClick={() => void loadInbox(inbox.cursor)}>{copy.loadMore}</button> : null}
              </div>
            ) : null}
            {panel === "control" ? (
              <div className="stack">
                <h2>{copy.control}</h2>
                <pre>{detail}</pre>
                <button onClick={() => void submitControl("pause")}>{copy.pause}</button>
                <button onClick={() => void submitControl("resume")}>{copy.resume}</button>
                <button onClick={() => void submitControl("revoke")}>{copy.revoke}</button>
              </div>
            ) : null}
            {panel === "actions" ? (
              <div className="stack">
                <h2>{copy.approval}</h2>
                <pre>{detail}</pre>
                <ActionButtons detail={detail} onDecide={(id, decision, revision, digest, grant) => void decide(id, decision, revision, digest, grant)} />
              </div>
            ) : null}
            {panel === "detail" || panel === "select" ? (
              <div>
                <h2>{panel === "select" ? copy.selectText : copy.artifact}</h2>
                <p style={{ whiteSpace: "pre-wrap", userSelect: "text" }}>{detail}</p>
                {panel === "select" ? <button onClick={() => setPanel(null)}>{copy.done}</button> : null}
              </div>
            ) : null}
            {panel === "work" ? (
              <div className="stack">
                <h2>{copy.acceptedWork}</h2>
                <p className="quiet">{copy.acceptedWorkHint}</p>
                <textarea className="field" value={workText} onChange={(event) => setWorkText(event.target.value)} />
                <button className="primary" disabled={!workText.trim() || !active} onClick={() => void submitAcceptedWork()}>{copy.submit}</button>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
      {signOutOpen ? (
        <div className="dialog-back" role="dialog" aria-label={copy.signOutTitle}>
          <div className="dialog">
            <h2>{copy.signOutTitle}</h2>
            <p>{copy.signOutChatNamed}</p>
            <button onClick={() => setSignOutOpen(false)}>{copy.cancel}</button>
            <button className="danger" onClick={onSignOut}>{copy.signOut}</button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function BlockControls({ card, onDecision }: { card: Card; onDecision: (decision: "submit" | "dismiss" | "report_error", response?: Record<string, unknown>) => void }) {
  const interrupt = stringField(card.raw, "interruptId") || stringField(card.raw, "interrupt_id");
  const status = stringField(card.raw, "status");
  const [selected, setSelected] = useState<string[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  if (!interrupt || status || !["choices", "confirm", "form", "sheet"].includes(card.kind)) return null;
  const items = Array.isArray(card.raw.items) ? card.raw.items : [];
  const actions = Array.isArray(card.raw.actions) ? card.raw.actions : [];
  const fields = Array.isArray(card.raw.fields) ? card.raw.fields : [];
  const respondedAt = new Date().toISOString();
  return (
    <div className="stack">
      {card.kind === "choices" ? items.map((item) => {
        const row = asRecord(item);
        const id = stringField(row, "id");
        return <button key={id} aria-pressed={selected.includes(id)} onClick={() => setSelected(card.raw.selection === "multiple" ? (selected.includes(id) ? selected.filter((value) => value !== id) : [...selected, id]) : [id])}>{stringField(row, "label")}</button>;
      }) : null}
      {card.kind === "form" ? fields.map((field) => {
        const row = asRecord(field);
        const id = stringField(row, "id");
        return <label key={id}>{stringField(row, "label")}<input className="field" value={values[id] ?? ""} onChange={(event) => setValues({ ...values, [id]: event.target.value })} /></label>;
      }) : null}
      <div className="actions">
        {(actions.length > 0 ? actions : [{ id: "submit", label: copy.submit }]).map((action) => {
          const row = asRecord(action) ?? { id: "submit", label: copy.submit };
          const id = stringField(row, "id") || "submit";
          return <button key={id} className="primary" onClick={() => onDecision("submit", { type: "ui.response", blockId: card.id, actionId: card.kind === "choices" ? undefined : id, selected, values, respondedAt })}>{stringField(row, "label") || copy.submit}</button>;
        })}
        <button onClick={() => onDecision("dismiss")}>{copy.close}</button>
      </div>
    </div>
  );
}

function MemoryPanel({ state, conversationId, onSubmit }: { state: unknown; conversationId: string | null; onSubmit: (command: Record<string, unknown>) => void }) {
  const snapshot = asRecord(state);
  const claims = Array.isArray(snapshot?.claims) ? snapshot.claims : [];
  const [text, setText] = useState("");
  const [predicate, setPredicate] = useState("prefers");
  const [value, setValue] = useState("");
  return (
    <div className="stack">
      <h2>{copy.memory}</h2>
      <label><input type="checkbox" checked={snapshot?.enabled === true} onChange={(event) => onSubmit({ type: "set_enabled", enabled: event.target.checked })} /> {copy.useMemory}</label>
      {claims.length === 0 ? <p>{copy.memoryEmpty}</p> : null}
      {claims.map((claim) => {
        const row = asRecord(claim);
        return (
          <article key={stringField(row, "id")}>
            <strong>{stringField(row, "predicate")}</strong>
            <p>{JSON.stringify(row?.value)}</p>
            <button onClick={() => onSubmit({ type: "forget", target_id: stringField(row, "id"), expected_version: numberField(row, "version"), no_relearn: true })}>{copy.forget}</button>
          </article>
        );
      })}
      <textarea className="field" value={text} onChange={(event) => setText(event.target.value)} placeholder={copy.remember} />
      <input className="field" value={predicate} onChange={(event) => setPredicate(event.target.value)} />
      <input className="field" value={value} onChange={(event) => setValue(event.target.value)} />
      <button className="primary" onClick={() => onSubmit({
        type: "remember",
        conversation_id: conversationId,
        source_text: text,
        allow_relearn: false,
        sensitive_consent: false,
        claim: { kind: "preference", predicate, value, conditions: {}, assertion: "explicit", evidence: { span_start: 0, span_end: text.length } },
      })} disabled={!conversationId || !text.trim()}>{copy.remember}</button>
    </div>
  );
}

function ActionButtons({ detail, onDecide }: { detail: string; onDecide: (id: string, decision: "approve" | "deny", revision: number, digest: string, grant: number) => void }) {
  let actions: Record<string, unknown>[] = [];
  try {
    const parsed = asRecord(JSON.parse(detail));
    const list = parsed?.actions ?? parsed?.action;
    actions = Array.isArray(list) ? list.flatMap((item) => { const row = asRecord(item); return row ? [row] : []; }) : [];
  } catch {
    actions = [];
  }
  return (
    <div className="stack">
      {actions.map((action) => {
        const id = stringField(action, "id") || stringField(asRecord(action.frozen), "id");
        return (
          <div key={id} className="actions">
            <span>{id}</span>
            <button onClick={() => onDecide(id, "approve", numberField(action, "revision"), stringField(action, "action_digest"), numberField(action, "grant_epoch") || numberField(asRecord(action.frozen), "grant_epoch"))}>{copy.approve}</button>
            <button onClick={() => onDecide(id, "deny", numberField(action, "revision"), stringField(action, "action_digest"), numberField(action, "grant_epoch") || numberField(asRecord(action.frozen), "grant_epoch"))}>{copy.deny}</button>
          </div>
        );
      })}
    </div>
  );
}

