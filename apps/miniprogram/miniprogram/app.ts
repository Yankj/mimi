import { createIdleWatch } from "./utils/idle-watch";
import { createChatStore } from "./utils/mock-chat";
import { createPrivacyMask } from "./utils/privacy-mask";
import { createSessionController, wxStorage, type SessionController } from "./utils/session";

export type MimiApp = {
  session: SessionController;
  chat: ReturnType<typeof createChatStore>;
  idleWatch: ReturnType<typeof createIdleWatch>;
  privacyMask: ReturnType<typeof createPrivacyMask>;
};

function createAppState(): MimiApp {
  const chat = createChatStore();
  const session = createSessionController(wxStorage(), {
    onLock: () => chat.clear(),
    onWipe: () => chat.clear(),
  });
  const idleWatch = createIdleWatch({
    session,
    onLocked: () => wx.reLaunch({ url: "/pages/lock/lock" }),
  });
  const privacyMask = createPrivacyMask(() => getCurrentPages());
  return { session, chat, idleWatch, privacyMask };
}

App<MimiApp>({
  ...createAppState(),
  onLaunch() {
    this.session.onShow(Date.now());
  },
  onShow() {
    const state = this.session.onShow(Date.now());
    if (state === "locked") {
      wx.reLaunch({ url: "/pages/lock/lock" });
    }
    this.privacyMask.reveal();
    if (state === "ok") this.idleWatch.start();
  },
  onHide() {
    this.privacyMask.cover();
    this.idleWatch.stop();
    this.session.onHide(Date.now());
  },
});
