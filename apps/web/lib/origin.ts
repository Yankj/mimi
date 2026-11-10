const LOOPBACK = new Set(["127.0.0.1", "localhost", "::1"]);

export function siteOrigin(configuredValue: string | undefined, request: Request): string | null {
  const configured = configuredValue?.trim() ?? "";
  if (configured) {
    try {
      const url = new URL(configured);
      if (url.username || url.password) return null;
      if (url.protocol !== "https:" && url.protocol !== "http:") return null;
      return url.origin;
    } catch {
      return null;
    }
  }
  return loopbackOrigin(request);
}

function loopbackOrigin(request: Request): string | null {
  const url = new URL(request.url);
  if (!LOOPBACK.has(url.hostname)) return null;
  const host = request.headers.get("host")?.trim() ?? "";
  if (host) {
    try {
      const fromHost = new URL(`${url.protocol}//${host}`);
      if (LOOPBACK.has(fromHost.hostname)) return fromHost.origin;
    } catch {
      return url.origin;
    }
  }
  return url.origin;
}

export function requestOrigin(request: Request): string | null {
  const origin = request.headers.get("origin");
  if (origin) return origin;
  const referer = request.headers.get("referer");
  if (!referer) return null;
  try {
    return new URL(referer).origin;
  } catch {
    return null;
  }
}

export function sameOriginAllowed(request: Request, configuredValue: string | undefined) {
  const site = siteOrigin(configuredValue, request);
  const sent = requestOrigin(request);
  if (!site || !sent) return false;
  return sent === site;
}
