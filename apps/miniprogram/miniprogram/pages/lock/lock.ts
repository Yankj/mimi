import { copy } from "../../vendor/mimi-core/index";
import type { MimiApp } from "../../app";

Page({
  data: {
    title: copy.lockTitle,
    body: copy.lockBody,
    continueSession: copy.continueSession,
    signOut: copy.signOut,
    pending: "继续只是模拟重新确认。真实的微信重新登录还没接上。",
  },
  onShow() {
    const app = getApp<MimiApp>();
    if (!app.session.isSignedIn()) {
      wx.reLaunch({ url: "/pages/login/login" });
      return;
    }
    if (!app.session.isLocked()) {
      wx.switchTab({ url: "/pages/today/today" });
    }
  },
  onContinue() {
    const app = getApp<MimiApp>();
    app.session.unlockMock(Date.now());
    app.idleWatch.start();
    wx.switchTab({ url: "/pages/today/today" });
  },
  onSignOut() {
    const app = getApp<MimiApp>();
    app.idleWatch.stop();
    app.session.signOut();
    wx.reLaunch({ url: "/pages/login/login" });
  },
});
