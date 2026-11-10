import type { SessionController } from "./session";

export type SessionRoute = "login" | "lock" | "ok";

export function routeForSession(session: SessionController, now = Date.now()): SessionRoute {
  if (!session.isSignedIn()) return "login";
  const state = session.onShow(now);
  if (state === "locked") return "lock";
  if (state === "signed-out") return "login";
  return "ok";
}

export function applySessionRoute(session: SessionController, now = Date.now()): SessionRoute {
  const route = routeForSession(session, now);
  if (route === "login") wx.reLaunch({ url: "/pages/login/login" });
  if (route === "lock") wx.redirectTo({ url: "/pages/lock/lock" });
  return route;
}
