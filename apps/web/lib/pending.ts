export const PENDING_LIMIT = 3500;

export type PendingPayload = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  userId: string;
  email: string | null;
  scopeId: string;
  provider?: "google" | "github";
};

export function encodePending(session: PendingPayload) {
  const raw = JSON.stringify({
    a: session.accessToken,
    r: session.refreshToken,
    e: session.expiresAt,
    u: session.userId,
    m: session.email,
    s: session.scopeId,
    p: session.provider ?? "",
  });
  if (new TextEncoder().encode(raw).byteLength > PENDING_LIMIT) throw new Error("pending session is too large");
  return raw;
}

export function decodePending(raw: string): PendingPayload | null {
  if (!raw || new TextEncoder().encode(raw).byteLength > PENDING_LIMIT) return null;
  try {
    const value = JSON.parse(raw) as { a?: unknown; r?: unknown; e?: unknown; u?: unknown; m?: unknown; s?: unknown; p?: unknown };
    if (typeof value.a !== "string" || typeof value.r !== "string" || typeof value.u !== "string" || typeof value.s !== "string") return null;
    if (typeof value.e !== "number" || !Number.isFinite(value.e)) return null;
    const provider = value.p === "google" || value.p === "github" ? value.p : undefined;
    return {
      accessToken: value.a,
      refreshToken: value.r,
      expiresAt: value.e,
      userId: value.u,
      email: typeof value.m === "string" ? value.m : null,
      scopeId: value.s,
      provider,
    };
  } catch {
    return null;
  }
}
