import { NextResponse } from "next/server";
import { attachLock, e2eFixture, readE2EMarker, readSession, rejectCrossSite } from "@/lib/server-session";

export const dynamic = "force-dynamic";

const E2E_USER = "11111111-1111-1111-1111-111111111111";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  if (e2eFixture(request) && (await readE2EMarker()) === "named") {
    const response = new NextResponse(null, { status: 204 });
    attachLock(response, request, E2E_USER);
    return response;
  }
  const session = await readSession();
  if (!session) return new NextResponse(null, { status: 401 });
  const response = new NextResponse(null, { status: 204 });
  attachLock(response, request, session.userId, session);
  return response;
}
