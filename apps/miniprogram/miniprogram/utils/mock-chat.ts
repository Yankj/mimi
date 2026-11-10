import { AUTO_ROUTE, chatHeaders, parseSseData, takeSseLines, textDelta, turnBody } from "../vendor/mimi-core/index";
import { PLACEHOLDER_BASE_URL } from "./config";
import { request } from "./request";

const MOCK_ANON = ["sb", "publishable", "a".repeat(24)].join("_");
const MOCK_TOKEN = "mock-access-token";

export type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  text: string;
};

export function mockChatHeaders() {
  return chatHeaders({
    authorization: `Bearer ${MOCK_TOKEN}`,
    anonKey: MOCK_ANON,
    edgeOrigin: PLACEHOLDER_BASE_URL,
    route: { route: AUTO_ROUTE, model: null, knownRouteIds: [] },
    modelsByRoute: {},
    liveText: true,
    approval: true,
    memory: "1",
    memoryProposal: true,
    timeZone: "Asia/Shanghai",
  });
}

export function mockTurnRequest(text: string) {
  const body = turnBody({
    turnId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    revision: 1,
    grantEpoch: 1,
    messageId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    text,
  });
  return {
    path: "/catpaw/chat/v1/scopes/22222222-2222-2222-2222-222222222222/conversations/cccccccc-cccc-cccc-cccc-cccccccccccc/turns",
    method: "POST" as const,
    headers: {
      ...mockChatHeaders(),
      "Idempotency-Key": String(body.turn_id),
    },
    body: JSON.stringify(body),
  };
}

export function mockReplyText(userText: string) {
  const trimmed = userText.trim() || "（空白）";
  return `收到了。这是模拟回复，还没有连接真实服务。你刚才说：${trimmed}`;
}

function frameDelta(messageId: string, sequence: number, text: string) {
  return `data: ${JSON.stringify({ type: "text_delta", sequence, data: { message_id: messageId, text } })}\n`;
}

/** Builds an SSE-looking buffer and yields parsed text deltas, the same shape a live stream would use. */
export function* parseMockStream(userText: string, chunkSize = 2) {
  const reply = mockReplyText(userText);
  const messageId = "mock-message";
  let buffer = "";
  let sequence = 0;
  for (let index = 0; index < reply.length; index += chunkSize) {
    const chunk = reply.slice(index, index + chunkSize);
    sequence += 1;
    buffer += frameDelta(messageId, sequence, chunk);
    const framed = takeSseLines(buffer);
    buffer = framed.rest;
    for (const line of framed.lines) {
      const event = parseSseData(line);
      const delta = event ? textDelta(event) : null;
      if (delta) yield delta;
    }
  }
  const rest = takeSseLines(buffer.endsWith("\n") ? buffer : `${buffer}\n`);
  for (const line of rest.lines) {
    const event = parseSseData(line);
    const delta = event ? textDelta(event) : null;
    if (delta) yield delta;
  }
}

export async function sendMockTurn(userText: string) {
  const prepared = mockTurnRequest(userText);
  // The request helper is the only network door. It refuses production hosts and, in this batch, does not send.
  await request(prepared).catch(() => undefined);
  return [...parseMockStream(userText)];
}

export function createChatStore() {
  const messages: ChatMessage[] = [];
  let nextId = 1;
  return {
    list() {
      return messages.map((item) => ({ ...item }));
    },
    clear() {
      messages.splice(0, messages.length);
    },
    add(role: ChatMessage["role"], text: string) {
      const item = { id: String(nextId), role, text };
      nextId += 1;
      messages.push(item);
      return item;
    },
    append(id: string, text: string) {
      const item = messages.find((row) => row.id === id);
      if (item) item.text += text;
    },
  };
}
