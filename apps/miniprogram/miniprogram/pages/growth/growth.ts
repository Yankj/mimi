import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";

Page({
  data: {
    familyTodo: copy.familyTodo,
    title: copy.growth,
    timelineTitle: copy.timelineTitle,
    noRecords: copy.noRecords,
    goalsReview: copy.goalsReview,
    noGoals: copy.noGoals,
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
  openTimeline() {
    wx.navigateTo({ url: "/pages/timeline/timeline" });
  },
  openGoals() {
    wx.navigateTo({ url: "/pages/goals/goals" });
  },
});
