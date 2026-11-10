import { copy } from "../../vendor/mimi-core/index";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";

Page({
  data: { back: copy.family, title: copy.reminders, body: copy.reminderPage },
  onShow() {
    const app = getApp<MimiApp>();
    applySessionRoute(app.session);
  },
  goBack() {
    wx.navigateBack();
  },
});
