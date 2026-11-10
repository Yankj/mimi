import { createHmac, timingSafeEqual } from "node:crypto";

export type MetaOpen =
  | { ok: true; meta: Record<string, unknown> }
  | { ok: false; reason: "missing" | "malformed" | "signature" | "no-secret" };

function macEqual(got: Buffer, expected: Buffer) {
  const sameLength = got.length === expected.length;
  const left = sameLength ? got : expected;
  const equal = timingSafeEqual(left, expected);
  return sameLength && equal;
}

/** Sign canonical JSON. Production without a secret writes nothing (fail closed). Dev without a secret keeps unsigned JSON. */
export function sealMeta(encodedJson: string, secret: string | undefined, production: boolean) {
  const key = secret?.trim() ?? "";
  if (!key) return production ? null : encodedJson;
  const payload = Buffer.from(encodedJson, "utf8").toString("base64url");
  const mac = createHmac("sha256", key).update(payload).digest("base64url");
  return `${payload}.${mac}`;
}

export function openMeta(token: string | undefined, secret: string | undefined, production: boolean): MetaOpen {
  const key = secret?.trim() ?? "";
  if (!key && production) return { ok: false, reason: "no-secret" };
  if (!token) return { ok: false, reason: "missing" };
  if (!key) return parseMeta(token);
  const dot = token.lastIndexOf(".");
  if (dot <= 0 || dot === token.length - 1) return { ok: false, reason: "signature" };
  const payload = token.slice(0, dot);
  const mac = token.slice(dot + 1);
  const expected = createHmac("sha256", key).update(payload).digest();
  const got = Buffer.from(mac, "base64url");
  if (!macEqual(got, expected)) return { ok: false, reason: "signature" };
  try {
    return parseMeta(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    return { ok: false, reason: "malformed" };
  }
}

function parseMeta(encoded: string): MetaOpen {
  try {
    const meta = JSON.parse(encoded) as unknown;
    if (!meta || typeof meta !== "object" || Array.isArray(meta)) return { ok: false, reason: "malformed" };
    return { ok: true, meta: meta as Record<string, unknown> };
  } catch {
    return { ok: false, reason: "malformed" };
  }
}
