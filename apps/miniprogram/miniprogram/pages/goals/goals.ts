import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";

Page({
  data: { back: copy.growth, title: copy.goalsReview, body: copy.noGoals },
  onShow() {
    const app = getApp<MimiApp>();
    applySessionRoute(app.session);
  },
  goBack() {
    wx.navigateBack();
  },
});
