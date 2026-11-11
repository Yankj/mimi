import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";

Page({
  data: {
    familyTodo: copy.familyTodo,
    title: copy.family,
    reminders: copy.reminders,
    reminderRow: copy.reminderRow,
    settings: copy.settings,
    settingsSubtitle: copy.settingsSubtitle,
    addOpen: false,
    addTitle: copy.addChildTitle,
    addBody: copy.addChildBody,
    addBlocked: copy.addChildBlocked,
    closeLabel: copy.close,
  },
  onShow() {
    const app = getApp<MimiApp>();
    if (applySessionRoute(app.session) !== "ok") return;
  },
  onAddChild() {
    this.setData({ addOpen: true });
  },
  onCloseAdd() {
    this.setData({ addOpen: false });
  },
  openReminders() {
    wx.navigateTo({ url: "/pages/reminders/reminders" });
  },
  openSettings() {
    wx.navigateTo({ url: "/pages/settings/settings" });
  },
});
