import { copy } from "../../vendor/mimi-core/index";
import type { MimiApp } from "../../app";

Page({
  data: {
    brand: copy.brand,
    brandSubtitle: copy.brandSubtitle,
    headline: copy.headline,
    subtitle: copy.subtitle,
    loginTitle: copy.loginTitle,
    wechatLogin: "微信登录（模拟）",
    loginPending:
      "真实的微信登录还没接上，要等 AppID、已备案的转发域名和账号绑定方式定了再做。现在点按钮只会进入模拟会话，方便看界面。",
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
    wx.switchTab({ url: "/pages/today/today" });
  },
});
