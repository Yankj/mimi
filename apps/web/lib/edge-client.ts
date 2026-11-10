import type { PublicConfig } from "./wire";

/** Path-only stand-in. The browser never learns the real edge origin or anon key. */
export const pathConfig: PublicConfig = {
  supabaseUrl: "https://abcdefghijklmnopqrst.supabase.co",
  anonKey: "client-omitted",
  edgeOrigin: "https://edge.invalid",
};

export async function edgeFetch(url: string, init: RequestInit = {}) {
  const parsed = new URL(url);
  if (parsed.origin !== pathConfig.edgeOrigin) throw new Error("登录连接尚未配置。");
  const headers = new Headers(init.headers);
  headers.delete("authorization");
  headers.delete("apikey");
  headers.delete("x-region");
  return fetch(`/api/edge${parsed.pathname}${parsed.search}`, {
    method: init.method ?? "GET",
    headers,
    body: init.body,
    signal: init.signal,
    cache: "no-store",
    redirect: "manual",
  });
}
