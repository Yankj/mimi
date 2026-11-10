import { copy } from "../../vendor/mimi-core/index";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";

Page({
  data: {
    back: copy.family,
    title: copy.settings,
    settingsSubtitle: copy.settingsSubtitle,
    account: copy.account,
    currentAccount: copy.currentAccount,
    displayName: "",
    signOut: copy.signOut,
    reviewHints: copy.reviewHints,
    showHints: copy.showHints,
    hintsBody: copy.hintsBody,
    dataBoundary: copy.dataBoundary,
    localLedger: copy.localLedger,
    noCalendar: copy.noCalendar,
    chatBoundary: copy.chatBoundary,
    reread: copy.reread,
    eraseAll: copy.eraseAll,
    advanced: copy.advanced,
    advancedBody: copy.advancedBody,
    about: copy.about,
    version: `${copy.version} 0.1.0`,
    build: `${copy.build} miniprogram-skeleton`,
    signOutOpen: false,
    signOutTitle: copy.signOutTitle,
    signOutNamed: "退出后需重新登录。这一步的模拟会话和对话草稿会从本机清掉。",
    cancel: copy.cancel,
  },
  onShow() {
    const app = getApp<MimiApp>();
    if (applySessionRoute(app.session) !== "ok") return;
    this.setData({ displayName: app.session.snapshot().displayName || "mimi" });
  },
  goBack() {
    wx.navigateBack();
  },
  openChat() {
    wx.navigateTo({ url: "/pages/chat/chat" });
  },
  openSignOut() {
    this.setData({ signOutOpen: true });
  },
  closeSignOut() {
    this.setData({ signOutOpen: false });
  },
  confirmSignOut() {
    const app = getApp<MimiApp>();
    app.session.signOut();
    wx.reLaunch({ url: "/pages/login/login" });
  },
});
