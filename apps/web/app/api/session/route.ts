import { profileFor } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const profile = await profileFor(request);
  if (!profile) return new Response(null, { status: 401, headers: { "cache-control": "no-store" } });
  return Response.json(profile, { headers: { "cache-control": "no-store" } });
}
