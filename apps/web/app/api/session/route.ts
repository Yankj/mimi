import { openSession } from "@/lib/server-session";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  return openSession(request);
}
