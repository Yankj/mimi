export function upstreamRequestInit(method: string, headers: Headers, body: ArrayBuffer | null, signal: AbortSignal): RequestInit {
  return {
    method,
    headers,
    body: body ?? undefined,
    redirect: "manual",
    cache: "no-store",
    signal,
  };
}
