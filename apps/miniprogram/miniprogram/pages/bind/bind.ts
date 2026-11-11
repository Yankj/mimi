import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";
import { isBindCodeFormat, normalizeBindCode } from "../../utils/bind-code";
import { applySessionRoute } from "../../utils/guard";

/** UI only. No request is made; real binding waits on the WeChat login decision. */
Page({
  data: {
    title: copy.bindTitle,
    body: copy.bindBody,
    field: copy.bindField,
    placeholder: copy.bindPlaceholder,
    submit: copy.bindSubmit,
    pending: copy.bindPending,
    code: "",
    error: "",
    notice: "",
  },
  onShow() {
    const app = getApp<MimiApp>();
    applySessionRoute(app.session);
  },
  onInput(event: { detail: { value: string } }) {
    this.setData({ code: event.detail.value, error: "", notice: "" });
  },
  onSubmit() {
    const app = getApp<MimiApp>();
    if (applySessionRoute(app.session) !== "ok") return;
    app.session.touch(Date.now());
    if (!isBindCodeFormat(this.data.code)) {
      this.setData({ error: copy.bindFormatBad, notice: "" });
      return;
    }
    this.setData({ code: normalizeBindCode(this.data.code), error: "", notice: copy.bindPending });
  },
});
