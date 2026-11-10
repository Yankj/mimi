import { NextResponse } from "next/server";
import { clearAuth, logoutUpstreamSession } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  await logoutUpstreamSession();
  const response = new NextResponse(null, { status: 204 });
  clearAuth(response, request);
  return response;
}
