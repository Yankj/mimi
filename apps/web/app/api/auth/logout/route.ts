import { NextResponse } from "next/server";
import { clearAuth, logoutUpstreamSession, rejectCrossSite } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const forbidden = rejectCrossSite(request);
  if (forbidden) return forbidden;
  await logoutUpstreamSession();
  const response = new NextResponse(null, { status: 204 });
  clearAuth(response, request);
  return response;
}
