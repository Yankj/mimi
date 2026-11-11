/**
 * Web substitutes for iOS device tools (W06) and in-message phone / mail / map / calendar actions.
 * No network calls: only tel:, mailto:, Apple Maps https links, and a locally generated .ics file.
 */

export const WEB_DEVICE_TOOLS = [
  "phone_call",
  "mail_compose",
  "maps_open",
  "calendar_create",
  "reminder_create",
  "sms_compose",
  "contacts_search",
  "alarm_set",
] as const;

export type WebDeviceTool = (typeof WEB_DEVICE_TOOLS)[number];

export type DeviceField = { key: string; label: string; value: string };

export type DeviceAction =
  | {
      kind: "link";
      tool: string;
      title: string;
      confirmLabel: string;
      url: string;
      fields: DeviceField[];
      footnote?: string;
      receipt: string;
    }
  | {
      kind: "ics";
      tool: string;
      title: string;
      confirmLabel: string;
      filename: string;
      ics: string;
      fields: DeviceField[];
      footnote: string;
      receipt: string;
    }
  | {
      kind: "phoneOnly";
      tool: string;
      title: string;
      confirmLabel: string;
      fields: DeviceField[];
      message: string;
    };

export type DetectedEntity =
  | { type: "phone"; text: string; start: number; end: number; url: string; label: string }
  | { type: "email"; text: string; start: number; end: number; url: string; label: string }
  | { type: "address"; text: string; start: number; end: number; url: string; label: string }
  | {
      type: "datetime";
      text: string;
      start: number;
      end: number;
      startAt: string;
      endAt: string;
      filename: string;
      ics: string;
      label: string;
    };

const MAPS_HOST = "maps.apple.com";
const PHONE_RE = /(?<!\d)(?:\+86[\s-]?)?1[3-9]\d{9}(?!\d)|(?<!\d)(?:\+86[\s-]?)?0\d{2,3}[\s-]?\d{7,8}(?!\d)|(?<!\d)\+(?:[1-9]\d{0,2})[\s-]?\d{6,12}(?!\d)/g;
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const ADDRESS_RE = /(?:[\u4e00-\u9fff]{1,8}(?:省|市|自治区|特别行政区))?(?:[\u4e00-\u9fff]{1,8}(?:市|区|县|镇|乡|街道))?[0-9\u4e00-\u9fffA-Za-z]{0,16}(?:街道|路|街|巷|弄|大道|号院|号|园|大厦|广场|中心|SOHO|Soho|soho)[0-9\u4e00-\u9fffA-Za-z-]{0,20}/g;
const ISO_RE = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?/g;
const DATE_RE = /(?:\d{4}[-/年])?\d{1,2}[-/月]\d{1,2}[日号]?(?:\s*(?:上午|下午|晚上|凌晨))?(?:\s*\d{1,2}(?:\s*[:：点]\s*\d{0,2})?(?:分)?)?/g;
const RELATIVE_RE = /(?:今天|明天|后天)(?:\s*(?:上午|下午|晚上|凌晨))?(?:\s*\d{1,2}(?:\s*[:：点]\s*\d{0,2})?(?:分)?)?/g;

const MODE_FLAG: Record<string, string> = { walk: "w", walking: "w", transit: "r", drive: "d", driving: "d" };

export function parseDeviceDate(raw: string, now = new Date()): Date | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const iso = Date.parse(trimmed);
  if (Number.isFinite(iso) && /^\d{4}-\d{2}-\d{2}/.test(trimmed)) return new Date(iso);
  const relative = parseRelativeDate(trimmed, now);
  if (relative) return relative;
  return parseChineseDate(trimmed, now);
}

export function formatDeviceDate(date: Date): string {
  return date.toISOString();
}

export function telUrl(number: string): string {
  const digits = number.replace(/[^\d+]/g, "");
  const normalized = digits.startsWith("+") ? `+${digits.slice(1).replace(/\D/g, "")}` : digits.replace(/\D/g, "");
  if (!normalized || normalized === "+") return "";
  return `tel:${normalized}`;
}

/** Strip injection characters, then encode a single mailbox. Empty or invalid addresses are dropped. */
export function sanitizeEmailAddress(raw: string): string | null {
  const cleaned = raw.replace(/[?#&]/g, "").replace(/[\u0000-\u001F\u007F]/g, "").trim();
  if (!cleaned || !/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(cleaned)) return null;
  return cleaned;
}

function mailboxList(value: string | undefined): string[] {
  if (!value) return [];
  return value.replace(/[、]/g, ",").split(",").flatMap((part) => {
    const cleaned = sanitizeEmailAddress(part);
    return cleaned ? [cleaned] : [];
  });
}

export function mailtoUrl(input: { to?: string; cc?: string; subject?: string; body?: string }): string {
  const to = mailboxList(input.to);
  const cc = mailboxList(input.cc);
  if (to.length === 0 && cc.length === 0) return "";
  const params = new URLSearchParams();
  if (cc.length > 0) params.set("cc", cc.join(","));
  if (input.subject) params.set("subject", input.subject);
  if (input.body) params.set("body", input.body);
  const query = params.toString();
  return `mailto:${to.map((address) => encodeURIComponent(address)).join(",")}${query ? `?${query}` : ""}`;
}

export function validMapCoords(coords?: { lat?: number | string | null; lng?: number | string | null } | null): { lat: number; lng: number } | null {
  if (!coords) return null;
  const lat = typeof coords.lat === "number" ? coords.lat : typeof coords.lat === "string" && coords.lat.trim() ? Number(coords.lat) : Number.NaN;
  const lng = typeof coords.lng === "number" ? coords.lng : typeof coords.lng === "string" && coords.lng.trim() ? Number(coords.lng) : Number.NaN;
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return { lat, lng };
}

export function mapsUrl(query: string, mode?: string, coords?: { lat?: number | string | null; lng?: number | string | null } | null): string {
  const point = validMapCoords(coords);
  const label = query.trim();
  if (!label && !point) return "";
  const params = new URLSearchParams();
  const flag = mode ? MODE_FLAG[mode.toLowerCase()] : undefined;
  const pointText = point ? `${point.lat},${point.lng}` : "";
  if (flag) {
    params.set("daddr", label || pointText);
    params.set("dirflg", flag);
    if (point) params.set("ll", pointText);
  } else if (point) {
    params.set("ll", pointText);
    params.set("q", label || pointText);
  } else {
    params.set("q", label);
  }
  return `https://${MAPS_HOST}/?${params.toString()}`;
}

export function isSafeDeviceUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "tel:") return /^tel:\+?\d+$/i.test(url);
    if (parsed.protocol === "mailto:") {
      const names = [...parsed.searchParams.keys()].map((key) => key.toLowerCase());
      if (names.includes("bcc")) return false;
      return /^mailto:[^/?#]+(?:[?#].*)?$/i.test(url) && !/[?#&]/.test(decodeURIComponent(parsed.pathname));
    }
    return parsed.protocol === "https:" && parsed.hostname.toLowerCase() === MAPS_HOST && !parsed.username && !parsed.password;
  } catch {
    return false;
  }
}

export function icsEscape(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/\r\n|\r|\n/g, "\\n")
    .replace(/[\u0000-\u0009\u000B\u000C\u000E-\u001F\u007F]/g, "")
    .replace(/[,;]/g, (char) => `\\${char}`);
}

export function icsDate(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`;
}

export function foldIcsLine(line: string): string {
  const encoder = new TextEncoder();
  if (encoder.encode(line).length <= 75) return line;
  const units = [...line];
  const lines: string[] = [];
  let current = "";
  let bytes = 0;
  const limit = () => (lines.length === 0 ? 75 : 74);
  for (const unit of units) {
    const size = encoder.encode(unit).length;
    if (current && bytes + size > limit()) {
      lines.push(current);
      current = unit;
      bytes = size;
    } else {
      current += unit;
      bytes += size;
    }
  }
  if (current) lines.push(current);
  return lines.map((part, index) => (index === 0 ? part : ` ${part}`)).join("\r\n");
}

export function buildEventIcs(input: {
  title: string;
  start: Date;
  end?: Date;
  location?: string;
  notes?: string;
  uid?: string;
  stamp?: Date;
}): string {
  const end = input.end && input.end > input.start ? input.end : new Date(input.start.getTime() + 60 * 60 * 1000);
  const uid = input.uid ?? `${icsDate(input.start)}-${Math.abs(hashString(input.title + input.start.toISOString()))}@mimi.web`;
  const stamp = input.stamp ?? new Date();
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//mimi//web//ZH",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    "BEGIN:VEVENT",
    `UID:${uid}`,
    `DTSTAMP:${icsDate(stamp)}`,
    `DTSTART:${icsDate(input.start)}`,
    `DTEND:${icsDate(end)}`,
    `SUMMARY:${icsEscape(input.title || "安排")}`,
  ];
  if (input.location) lines.push(`LOCATION:${icsEscape(input.location)}`);
  if (input.notes) lines.push(`DESCRIPTION:${icsEscape(input.notes)}`);
  lines.push("END:VEVENT", "END:VCALENDAR");
  return `${lines.map(foldIcsLine).join("\r\n")}\r\n`;
}

export function icsFilename(title: string): string {
  const slug = (title || "event").replace(/[\\/:*?"<>|]+/g, "").replace(/\s+/g, "-").slice(0, 40);
  return `${slug || "event"}.ics`;
}

export function argText(args: Record<string, unknown> | undefined, key: string): string {
  if (!args) return "";
  const value = args[key];
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Array.isArray(value)) return value.map((item) => (typeof item === "string" ? item : JSON.stringify(item))).join("、");
  return "";
}

const TOOL_COPY: Record<string, { title: string; confirm: string; fields: { key: string; label: string }[] }> = {
  calendar_create: { title: "加入日历", confirm: "加入", fields: [
    { key: "title", label: "标题" }, { key: "start", label: "开始" }, { key: "end", label: "结束" },
    { key: "location", label: "地点" }, { key: "notes", label: "备注" },
  ] },
  reminder_create: { title: "新建提醒", confirm: "创建", fields: [
    { key: "title", label: "内容" }, { key: "due", label: "时间" }, { key: "list", label: "列表" },
  ] },
  mail_compose: { title: "写邮件", confirm: "去发送", fields: [
    { key: "to", label: "收件人" }, { key: "cc", label: "抄送" }, { key: "subject", label: "主题" }, { key: "body", label: "内容" },
  ] },
  phone_call: { title: "打电话", confirm: "呼叫", fields: [
    { key: "name", label: "联系人" }, { key: "number", label: "号码" },
  ] },
  maps_open: { title: "打开地图", confirm: "打开", fields: [
    { key: "query", label: "地点" }, { key: "mode", label: "出行方式" },
  ] },
  sms_compose: { title: "发短信", confirm: "去发送", fields: [
    { key: "to", label: "收件人" }, { key: "body", label: "内容" },
  ] },
  alarm_set: { title: "设闹钟", confirm: "设置", fields: [
    { key: "time", label: "时间" }, { key: "label", label: "标签" },
  ] },
  contacts_search: { title: "查联系人", confirm: "查找", fields: [
    { key: "query", label: "找谁" },
  ] },
};

export const DEVICE_PHONE_ONLY = "请在手机上完成";
export const DEVICE_ICS_FOOTNOTE = "在网页上会下载一个日历文件，不会直接写入系统日历。";
export const DEVICE_ICS_RECEIPT = "已下载日历文件，请在日历应用中打开";
export const DEVICE_HANDOFF_RECEIPT = "已请求打开";
export const DEVICE_MAIL_NOTE = "会打开系统邮件页，由你点发送。";
export const DEVICE_PHONE_NOTE = "系统还会再确认一次。";
export const DEVICE_ICS_MODEL_MESSAGE = "只下载了一个日历文件（.ics），没有写入系统日历。不要对用户说「已加入日历」。";

export function icsDeviceResultData() {
  return { via: "ics" as const, message: DEVICE_ICS_MODEL_MESSAGE };
}

export function unsupportedOnWebData(tool: string) {
  return {
    reason: "unsupported_on_web" as const,
    tool,
    retryable: false,
    message: `这台网页无法执行 ${tool}。不要重试，直接告诉用户请在手机上完成。`,
  };
}

function argNumber(args: Record<string, unknown>, key: string): number | undefined {
  const value = args[key];
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return undefined;
}

export function deviceActionFromTool(tool: string, args: Record<string, unknown> = {}, now = new Date()): DeviceAction {
  const meta = TOOL_COPY[tool] ?? { title: "在这台设备上执行", confirm: "执行", fields: Object.keys(args).map((key) => ({ key, label: key })) };
  const fields = meta.fields
    .map((field) => ({ key: field.key, label: field.label, value: displayArg(argText(args, field.key), field.key) }))
    .filter((field) => field.value);
  if (tool === "phone_call") {
    const url = telUrl(argText(args, "number"));
    if (!url) return { kind: "phoneOnly", tool, title: meta.title, confirmLabel: meta.confirm, fields, message: DEVICE_PHONE_ONLY };
    return { kind: "link", tool, title: meta.title, confirmLabel: meta.confirm, url, fields, footnote: DEVICE_PHONE_NOTE, receipt: DEVICE_HANDOFF_RECEIPT };
  }
  if (tool === "mail_compose") {
    const url = mailtoUrl({ to: argText(args, "to"), cc: argText(args, "cc"), subject: argText(args, "subject"), body: argText(args, "body") });
    if (!url) return { kind: "phoneOnly", tool, title: meta.title, confirmLabel: meta.confirm, fields, message: DEVICE_PHONE_ONLY };
    return {
      kind: "link",
      tool,
      title: meta.title,
      confirmLabel: meta.confirm,
      url,
      fields,
      footnote: DEVICE_MAIL_NOTE,
      receipt: DEVICE_HANDOFF_RECEIPT,
    };
  }
  if (tool === "maps_open") {
    const url = mapsUrl(argText(args, "query"), argText(args, "mode") || undefined, { lat: argNumber(args, "lat"), lng: argNumber(args, "lng") });
    if (!url) return { kind: "phoneOnly", tool, title: meta.title, confirmLabel: meta.confirm, fields, message: DEVICE_PHONE_ONLY };
    return {
      kind: "link",
      tool,
      title: meta.title,
      confirmLabel: meta.confirm,
      url,
      fields,
      receipt: DEVICE_HANDOFF_RECEIPT,
    };
  }
  if (tool === "calendar_create" || tool === "reminder_create") {
    const start = parseDeviceDate(argText(args, tool === "reminder_create" ? "due" : "start"), now) ?? new Date(now.getTime() + 60 * 60 * 1000);
    const end = parseDeviceDate(argText(args, "end"), now) ?? undefined;
    const title = argText(args, "title") || "安排";
    return {
      kind: "ics",
      tool,
      title: meta.title,
      confirmLabel: meta.confirm,
      filename: icsFilename(title),
      ics: buildEventIcs({ title, start, end, location: argText(args, "location") || undefined, notes: argText(args, "notes") || undefined }),
      fields,
      footnote: DEVICE_ICS_FOOTNOTE,
      receipt: DEVICE_ICS_RECEIPT,
    };
  }
  return {
    kind: "phoneOnly",
    tool,
    title: meta.title,
    confirmLabel: meta.confirm,
    fields,
    message: DEVICE_PHONE_ONLY,
  };
}

export function findEntities(text: string, now = new Date()): DetectedEntity[] {
  const found: DetectedEntity[] = [];
  for (const match of matchAll(text, PHONE_RE)) {
    const url = telUrl(match[0]);
    if (!url) continue;
    found.push({ type: "phone", text: match[0], start: match.index, end: match.index + match[0].length, url, label: `拨打 ${match[0]}` });
  }
  for (const match of matchAll(text, EMAIL_RE)) {
    const url = mailtoUrl({ to: match[0] });
    if (!url) continue;
    found.push({ type: "email", text: match[0], start: match.index, end: match.index + match[0].length, url, label: `写邮件给 ${match[0]}` });
  }
  for (const match of matchAll(text, ADDRESS_RE)) {
    const value = match[0].trim();
    if (value.length < 3) continue;
    found.push({ type: "address", text: value, start: match.index, end: match.index + match[0].length, url: mapsUrl(value), label: `在地图中查看「${value}」` });
  }
  for (const match of [...matchAll(text, ISO_RE), ...matchAll(text, RELATIVE_RE), ...matchAll(text, DATE_RE)]) {
    const value = match[0].trim();
    const start = parseDeviceDate(value, now);
    if (!start) continue;
    const end = new Date(start.getTime() + 60 * 60 * 1000);
    found.push({
      type: "datetime",
      text: value,
      start: match.index,
      end: match.index + match[0].length,
      startAt: start.toISOString(),
      endAt: end.toISOString(),
      filename: icsFilename(value),
      ics: buildEventIcs({ title: "安排", start, end, notes: value }),
      label: `下载日程（${value}）`,
    });
  }
  return dedupeEntities(found);
}

export function downloadTextFile(filename: string, text: string, mime = "text/calendar;charset=utf-8") {
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = "noopener";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function openDeviceUrl(url: string) {
  if (!isSafeDeviceUrl(url)) return false;
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.rel = "noopener noreferrer";
  if (url.startsWith("https:")) anchor.target = "_blank";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  return true;
}

function displayArg(value: string, key: string): string {
  if (!value) return "";
  if (key === "start" || key === "end" || key === "due" || key === "time") {
    const date = parseDeviceDate(value);
    if (date) {
      return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(date);
    }
  }
  return value;
}

function matchAll(text: string, pattern: RegExp): RegExpExecArray[] {
  const rows: RegExpExecArray[] = [];
  const copy = new RegExp(pattern.source, pattern.flags.includes("g") ? pattern.flags : `${pattern.flags}g`);
  let current: RegExpExecArray | null;
  while ((current = copy.exec(text))) {
    if (current[0] === "") {
      copy.lastIndex += 1;
      continue;
    }
    rows.push(current);
  }
  return rows;
}

function dedupeEntities(found: DetectedEntity[]): DetectedEntity[] {
  const ranked = [...found].sort((a, b) => a.start - b.start || b.end - b.start - (a.end - a.start) || rank(a.type) - rank(b.type));
  const kept: DetectedEntity[] = [];
  for (const item of ranked) {
    if (kept.some((existing) => overlaps(existing, item))) continue;
    kept.push(item);
  }
  return kept.sort((a, b) => a.start - b.start);
}

function overlaps(a: DetectedEntity, b: DetectedEntity) {
  return a.start < b.end && b.start < a.end;
}

function rank(type: DetectedEntity["type"]) {
  return { datetime: 0, email: 1, phone: 2, address: 3 }[type];
}

function parseRelativeDate(text: string, now: Date): Date | null {
  const match = text.match(/^(今天|明天|后天)(?:\s*(上午|下午|晚上|凌晨))?(?:\s*(\d{1,2})(?:\s*[:：点]\s*(\d{0,2}))?(?:分)?)?$/);
  if (!match) return null;
  const date = new Date(now.getTime());
  date.setSeconds(0, 0);
  const offset = match[1] === "明天" ? 1 : match[1] === "后天" ? 2 : 0;
  date.setDate(date.getDate() + offset);
  applyClock(date, match[2], match[3], match[4]);
  return date;
}

function parseChineseDate(text: string, now: Date): Date | null {
  const match = text.match(/^(?:(\d{4})[-/年])?(\d{1,2})[-/月](\d{1,2})[日号]?(?:\s*(上午|下午|晚上|凌晨))?(?:\s*(\d{1,2})(?:\s*[:：点]\s*(\d{0,2}))?(?:分)?)?$/);
  if (!match) return null;
  const year = match[1] ? Number(match[1]) : now.getFullYear();
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const date = new Date(year, month - 1, day, 9, 0, 0, 0);
  applyClock(date, match[4], match[5], match[6]);
  return Number.isNaN(date.getTime()) ? null : date;
}

function applyClock(date: Date, period?: string, hourRaw?: string, minuteRaw?: string) {
  if (!hourRaw && !period) {
    date.setHours(9, 0, 0, 0);
    return;
  }
  let hour = hourRaw ? Number(hourRaw) : period === "晚上" ? 19 : period === "下午" ? 14 : period === "凌晨" ? 6 : 9;
  const minute = minuteRaw ? Number(minuteRaw || "0") : 0;
  if (period === "下午" || period === "晚上") {
    if (hour > 0 && hour < 12) hour += 12;
  }
  if (period === "凌晨" && hour === 12) hour = 0;
  date.setHours(hour, Number.isFinite(minute) ? minute : 0, 0, 0);
}

function hashString(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) hash = (hash * 31 + value.charCodeAt(index)) | 0;
  return hash;
}
