import { copy } from "../../utils/mp-copy";
import type { MimiApp } from "../../app";
import { applySessionRoute } from "../../utils/guard";
import { sendMockTurn, type ChatMessage } from "../../utils/mock-chat";

Page({
  data: {
    ask: copy.ask,
    send: copy.send,
    draft: "",
    busy: false,
    hint: copy.chatHint,
    messages: [] as ChatMessage[],
  },
  onShow() {
    const app = getApp<MimiApp>();
    if (applySessionRoute(app.session) !== "ok") return;
    this.setData({ messages: app.chat.list() });
  },
  onDraft(event: { detail: { value: string } }) {
    this.setData({ draft: event.detail.value });
  },
  async onSend() {
    const app = getApp<MimiApp>();
    if (applySessionRoute(app.session) !== "ok") return;
    const text = this.data.draft.trim();
    if (!text || this.data.busy) return;
    app.session.touch(Date.now());
    app.chat.add("user", text);
    const assistant = app.chat.add("assistant", "");
    this.setData({ draft: "", busy: true, messages: app.chat.list() });
    const deltas = await sendMockTurn(text);
    for (const delta of deltas) {
      app.chat.append(assistant.id, delta.text);
      this.setData({ messages: app.chat.list() });
    }
    this.setData({ busy: false, messages: app.chat.list() });
  },
});
