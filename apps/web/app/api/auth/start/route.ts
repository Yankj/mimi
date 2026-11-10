import { NextResponse } from "next/server";
import { readServerConfig } from "@/lib/server-config";
import { attachPkce, externalOrigin } from "@/lib/server-session";
import { authorizeUrl, createPkceVerifier } from "@/lib/wire";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const config = readServerConfig();
  if (!config) return NextResponse.json({ error: "登录连接尚未配置。" }, { status: 503 });
  const body = (await request.json().catch(() => null)) as { provider?: unknown } | null;
  if (body?.provider !== "google" && body?.provider !== "github") {
    return NextResponse.json({ error: "Web 不提供体验登录，请使用 Google 或 GitHub。" }, { status: 400 });
  }
  const pkce = await createPkceVerifier();
  const redirectTo = `${externalOrigin(request)}/auth/callback`;
  const response = NextResponse.json({ url: authorizeUrl(config, body.provider, pkce.challenge, redirectTo) });
  attachPkce(response, request, pkce.verifier);
  return response;
}
