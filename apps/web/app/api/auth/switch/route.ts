import { NextResponse } from "next/server";
import { attachSession, cookieSecure, logoutUpstreamSession, readPending, rejectCrossSite } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  const body = (await request.json().catch(() => null)) as { confirm?: unknown } | null;
  if (body?.confirm !== true) {
    const response = new NextResponse(null, { status: 204 });
    response.cookies.set("mimi_pending", "", { httpOnly: true, sameSite: "lax", path: "/", maxAge: 0, secure: cookieSecure(request) });
    return response;
  }
  const pending = await readPending();
  if (!pending) return new NextResponse(null, { status: 401 });
  await logoutUpstreamSession();
  const response = NextResponse.json({ ok: true });
  attachSession(response, request, pending, { clearLock: true });
  return response;
}
