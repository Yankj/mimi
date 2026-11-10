import { NextResponse } from "next/server";
import { attachSession, clearAuth, e2eFixture, readE2EMarker, refreshedSession, rejectCrossSite } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  if (e2eFixture(request) && (await readE2EMarker()) === "named") return new NextResponse(null, { status: 204 });
  try {
    const next = await refreshedSession();
    if (!next) return new NextResponse(null, { status: 401 });
    const response = new NextResponse(null, { status: 204 });
    attachSession(response, request, next);
    return response;
  } catch {
    const response = new NextResponse(null, { status: 401 });
    clearAuth(response, request);
    return response;
  }
}
