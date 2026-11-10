import { createChatStore } from "./utils/mock-chat";
import { createSessionController, wxStorage, type SessionController } from "./utils/session";

export type MimiApp = {
  session: SessionController;
  chat: ReturnType<typeof createChatStore>;
};

function createAppState(): MimiApp {
  const chat = createChatStore();
  const session = createSessionController(wxStorage(), {
    onLock: () => chat.clear(),
    onWipe: () => chat.clear(),
  });
  return { session, chat };
}

App<MimiApp>({
  ...createAppState(),
  onLaunch() {
    this.session.onShow(Date.now());
  },
  onShow() {
    this.session.onShow(Date.now());
  },
  onHide() {
    this.session.onHide(Date.now());
  },
});
