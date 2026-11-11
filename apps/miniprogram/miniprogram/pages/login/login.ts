import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";

Page({
  data: {
    brand: copy.brand,
    brandSubtitle: copy.brandSubtitle,
    headline: copy.headline,
    subtitle: copy.subtitle,
    loginTitle: copy.loginTitle,
    wechatLogin: copy.wechatLogin,
    loginPending: copy.loginPending,
    notice: "",
  },
  onShow() {
    const app = getApp<MimiApp>();
    app.session.onShow(Date.now());
    if (app.session.isSignedIn() && app.session.isLocked()) {
      wx.redirectTo({ url: "/pages/lock/lock" });
      return;
    }
    if (app.session.isSignedIn()) {
      wx.switchTab({ url: "/pages/today/today" });
    }
  },
  onMockLogin() {
    const app = getApp<MimiApp>();
    app.session.signInMock(Date.now());
    app.idleWatch.start();
    wx.switchTab({ url: "/pages/today/today" });
  },
});
