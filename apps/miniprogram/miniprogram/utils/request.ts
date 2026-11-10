import { joinEndpoint } from "../vendor/mimi-core/index";
import { PLACEHOLDER_BASE_URL } from "./config";

export type RequestInput = {
  path: string;
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;
};

export type RequestResult = {
  ok: boolean;
  status: number;
  url: string;
  headers: Record<string, string>;
  body: string;
};

export type Transport = (input: RequestInput & { url: string }) => Promise<RequestResult>;

export class RequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RequestError";
  }
}

export function resolveUrl(path: string, baseUrl = PLACEHOLDER_BASE_URL) {
  return joinEndpoint(baseUrl, path);
}

export function isForbiddenUrl(url: string) {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return true;
  }
  if (parsed.protocol !== "https:") return true;
  const host = parsed.hostname.toLowerCase();
  if (host === "localhost" || host === "127.0.0.1" || host === "::1") return true;
  if (host === "supabase.co" || host.endsWith(".supabase.co") || host.includes("supabase")) return true;
  if (host.includes("catpaw")) return true;
  if (parsed.pathname.includes("/functions/v1/")) return true;
  return false;
}

export function assertSafeUrl(url: string) {
  if (isForbiddenUrl(url)) {
    throw new RequestError("小程序这一步不连接线上地址。");
  }
}

const defaultTransport: Transport = async (input) => {
  assertSafeUrl(input.url);
  throw new RequestError("这一步只用模拟数据，还没有连接真实服务。");
};

let transport: Transport = defaultTransport;

export function setTransport(next: Transport | null) {
  transport = next ?? defaultTransport;
}

/** One request helper for later real calls. This batch only talks to a mock transport and refuses production hosts. */
export async function request(input: RequestInput, baseUrl = PLACEHOLDER_BASE_URL): Promise<RequestResult> {
  const url = resolveUrl(input.path, baseUrl);
  assertSafeUrl(url);
  return transport({ ...input, url });
}
