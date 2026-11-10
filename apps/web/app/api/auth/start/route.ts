import { NextResponse } from "next/server";
import { reauthPrompt } from "@/lib/lock";
import { readServerConfig } from "@/lib/server-config";
import {
  attachPkce,
  attachProvider,
  attachReauth,
  e2eFixture,
  externalOrigin,
  readE2EMarker,
  readSession,
  rejectCrossSite,
} from "@/lib/server-session";
import { authorizeUrl, createPkceVerifier } from "@/lib/wire";

export const dynamic = "force-dynamic";

const E2E_USER = "11111111-1111-1111-1111-111111111111";
const E2E_AUTHORIZE = "https://abcdefghijklmnopqrst.supabase.co";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  const body = (await request.json().catch(() => null)) as { provider?: unknown; reauth?: unknown } | null;
  const reauth = body?.reauth === true;
  const requested = body?.provider === "github" ? "github" : body?.provider === "google" ? "google" : null;
  if (!reauth && !requested) {
    return NextResponse.json({ error: "Web 不提供体验登录，请使用 Google 或 GitHub。" }, { status: 400 });
  }
  let redirectTo: string;
  try {
    redirectTo = `${externalOrigin(request)}/auth/callback`;
  } catch {
    return NextResponse.json({ error: "登录连接尚未配置。" }, { status: 503 });
  }
  if (e2eFixture(request) && reauth && (await readE2EMarker()) === "named") {
    const url = new URL("/auth/v1/authorize", E2E_AUTHORIZE);
    url.searchParams.set("provider", "google");
    url.searchParams.set("prompt", reauthPrompt("google"));
    url.searchParams.set("redirect_to", redirectTo);
    url.searchParams.set("code_challenge", "e2e-challenge");
    url.searchParams.set("code_challenge_method", "s256");
    const response = NextResponse.json({ url: url.toString() });
    attachReauth(response, request, E2E_USER);
    return response;
  }
  const config = readServerConfig();
  if (!config) return NextResponse.json({ error: "登录连接尚未配置。" }, { status: 503 });
  const session = reauth ? await readSession() : null;
  const provider = reauth ? session?.provider : requested;
  if (provider !== "google" && provider !== "github") {
    return NextResponse.json({ error: reauth ? "登录已失效，请重新登录。" : "Web 不提供体验登录，请使用 Google 或 GitHub。" }, { status: reauth ? 401 : 400 });
  }
  const pkce = await createPkceVerifier();
  const response = NextResponse.json({
    url: authorizeUrl(config, provider, pkce.challenge, redirectTo, reauth ? reauthPrompt(provider) : undefined),
  });
  attachPkce(response, request, pkce.verifier);
  attachProvider(response, request, provider);
  if (reauth && session) attachReauth(response, request, session.userId);
  return response;
}
