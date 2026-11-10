import { isUuid } from "./wire";

export function chatIndexKey(subject: string) {
  return `mimi.web.index.v1.${subject}`;
}

export function accountPreferenceKey(kind: "feedback" | "reactions" | "route", subject: string) {
  return `mimi.web.${kind}.v1.${subject}`;
}

export function legacyChatKey(subject: string) {
  return `mimi.web.chats.v1.${subject}`;
}

/** Ids and timestamps only. Message text, titles and previews stay in memory. */
export function serializeChatIndex(chats: { id: string; updatedAt: string }[]) {
  const rows = chats.slice(0, 80).flatMap((chat) => (isUuid(chat.id) && chat.updatedAt ? [{ id: chat.id, updatedAt: chat.updatedAt }] : []));
  return JSON.stringify(rows);
}

export function parseChatIndex(raw: string | null) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.flatMap((row) => {
      const record = row as { id?: unknown; updatedAt?: unknown };
      if (typeof record.id !== "string" || typeof record.updatedAt !== "string" || !isUuid(record.id)) return [];
      return [{ id: record.id, updatedAt: record.updatedAt }];
    });
  } catch {
    return [];
  }
}
