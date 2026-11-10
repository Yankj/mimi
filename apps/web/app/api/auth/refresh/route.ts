import { NextResponse } from "next/server";
import { activityDecision } from "@/lib/lock";
import { attachLock, attachSession, clearAuth, e2eFixture, readE2EMarker, readSession, refreshedSession, rejectCrossSite } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  if (e2eFixture(request) && (await readE2EMarker()) === "named") return new NextResponse(null, { status: 204 });
  const current = await readSession();
  if (current && activityDecision({ locked: current.locked === true, seenAt: current.seenAt, now: Date.now() }).locked) {
    const response = new NextResponse(null, { status: 401 });
    attachLock(response, request, current.userId, current);
    return response;
  }
  try {
    const next = await refreshedSession();
    if (!next) return new NextResponse(null, { status: 401 });
    const response = new NextResponse(null, { status: 204 });
    attachSession(response, request, { ...next, provider: next.provider ?? current?.provider, locked: false, seenAt: current?.seenAt });
    return response;
  } catch {
    const response = new NextResponse(null, { status: 401 });
    clearAuth(response, request);
    return response;
  }
}
