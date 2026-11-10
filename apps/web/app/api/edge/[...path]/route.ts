import { NextResponse } from "next/server";
import { readServerConfig } from "@/lib/server-config";
import { attachSession, e2eFixture, readE2EMarker, readSession, refreshedSession } from "@/lib/server-session";
import { joinEndpoint, pinnedRegion } from "@/lib/wire";

export const dynamic = "force-dynamic";

const ALLOWED = new Set(["accept", "content-type", "idempotency-key"]);

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  if (e2eFixture(request) && (await readE2EMarker()) === "named") {
    return NextResponse.json({ error: "e2e" }, { status: 401 });
  }
  const config = readServerConfig();
  if (!config) return NextResponse.json({ error: "登录连接尚未配置。" }, { status: 503 });
  const segments = (await context.params).path;
  if (segments.some((part) => part === "." || part === ".." || part.includes("%"))) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }
  const route = `/${segments.join("/")}`;
  if (route !== "/readyz" && !route.startsWith("/catpaw/")) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }
  if (request.method !== "GET" && request.method !== "POST") {
    return NextResponse.json({ error: "method" }, { status: 405 });
  }
  let session = await readSession();
  if (!session) return new NextResponse(null, { status: 401 });
  let rotated: typeof session | null = null;
  if (session.expiresAt <= Date.now() + 60_000) {
    const next = await refreshedSession().catch(() => null);
    if (next) {
      session = next;
      rotated = next;
    }
  }
  const incoming = new URL(request.url);
  let target: string;
  try {
    target = joinEndpoint(config.edgeOrigin, `${route}${incoming.search}`);
  } catch {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }
  const replay = request.method === "POST" ? await request.arrayBuffer() : null;
  const send = async (accessToken: string) => {
    const headers = new Headers();
    request.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      if (lower.startsWith("x-catpaw-") || ALLOWED.has(lower)) headers.set(key, value);
    });
    headers.set("authorization", `Bearer ${accessToken}`);
    headers.set("apikey", config.anonKey);
    const region = pinnedRegion(config.edgeOrigin);
    if (region) headers.set("x-region", region);
    return fetch(target, {
      method: request.method,
      headers,
      body: replay,
      redirect: "manual",
      cache: "no-store",
    });
  };
  let upstream = await send(session.accessToken);
  if (upstream.status === 401) {
    try {
      const next = await refreshedSession();
      if (!next) return new NextResponse(null, { status: 401 });
      session = next;
      rotated = next;
      upstream = await send(session.accessToken);
    } catch {
      return new NextResponse(null, { status: 401 });
    }
  }
  const response = passthrough(upstream);
  if (rotated && response.status !== 401) attachSession(response, request, rotated);
  return response;
}

function passthrough(upstream: Response) {
  const headers = new Headers();
  const type = upstream.headers.get("content-type");
  if (type) headers.set("content-type", type);
  headers.set("cache-control", "no-store");
  return new NextResponse(upstream.body, { status: upstream.status, headers });
}

export const GET = proxy;
export const POST = proxy;
