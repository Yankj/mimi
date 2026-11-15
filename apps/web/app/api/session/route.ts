import { openSession } from "@/lib/server-session";

export const dynamic = "force-dynamic";

/** Cookie session only. This handler does not call Supabase or catpaw-api,
 *  so there is no upstream request here to put a deadline on. A hung
 *  response is bounded by the client's session probe. */
export async function GET(request: Request) {
  return openSession(request);
}
