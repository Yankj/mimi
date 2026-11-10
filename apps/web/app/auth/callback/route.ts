import { NextResponse } from "next/server";
import { establishSession } from "@/lib/browser-session";
import { unlockOutcome } from "@/lib/lock";
import { readServerConfig } from "@/lib/server-config";
import {
  attachPending,
  attachSession,
  cookieSecure,
  externalOrigin,
  readPkce,
  readProviderCookie,
  readReauthUser,
  readSession,
} from "@/lib/server-session";
import { credentialFromToken, parseOAuthCallback, pkceExchangeRequest } from "@/lib/wire";

export const dynamic = "force-dynamic";

function redirectTo(request: Request, path: string) {
  const response = NextResponse.redirect(new URL(path, externalOrigin(request)));
  response.cookies.set("mimi_pkce", "", { httpOnly: true, sameSite: "lax", path: "/", maxAge: 0, secure: cookieSecure(request) });
  return response;
}

export async function GET(request: Request) {
  let origin: string;
  try {
    origin = externalOrigin(request);
  } catch {
    return new NextResponse(null, { status: 503 });
  }
  const fail = (notice: string) => NextResponse.redirect(new URL(`/?notice=${notice}`, origin));
  const url = new URL(request.url);
  let parsed: ReturnType<typeof parseOAuthCallback>;
  try {
    parsed = parseOAuthCallback(url.toString());
  } catch {
    return fail("invalid");
  }
  if (parsed.kind === "cancelled") return redirectTo(request, "/?notice=cancelled");
  if (parsed.kind !== "code") return fail("invalid");
  const config = readServerConfig();
  const verifier = await readPkce();
  if (!config || !verifier) return fail("invalid");
  const exchange = pkceExchangeRequest(config, parsed.code, verifier);
  const tokenResponse = await fetch(exchange.url, {
    method: exchange.method,
    headers: exchange.headers,
    body: exchange.body,
    cache: "no-store",
    redirect: "manual",
  });
  const json = await tokenResponse.json().catch(() => null);
  if (!tokenResponse.ok) return fail("invalid");
  try {
    const session = await establishSession(config, credentialFromToken(json));
    const provider = await readProviderCookie();
    if (provider) session.provider = provider;
    const current = await readSession();
    if (!session.provider && current?.provider) session.provider = current.provider;
    const lockedUser = await readReauthUser();
    if (lockedUser) {
      const outcome = unlockOutcome(lockedUser, session.userId);
      const response = redirectTo(request, outcome.wipeLocal ? "/?reauth=switched" : "/");
      attachSession(response, request, session, { clearLock: true });
      return response;
    }
    if (current && current.userId !== session.userId) {
      const response = redirectTo(request, "/?switch=1");
      attachPending(response, request, session);
      return response;
    }
    const response = redirectTo(request, "/");
    attachSession(response, request, session, { clearLock: true });
    return response;
  } catch {
    return fail("invalid");
  }
}
