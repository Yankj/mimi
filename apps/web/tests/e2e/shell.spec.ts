import { expect, test, type Page } from "@playwright/test";

async function mark(page: Page, value: "named" | "guest") {
  await page.context().addCookies([
    { name: "mimi_e2e", value, url: "http://127.0.0.1:3111", httpOnly: true, sameSite: "Lax" },
  ]);
}

test("signed-out pages offer Google and GitHub only", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("button", { name: "使用 Google 继续" })).toBeVisible();
  await expect(page.getByRole("button", { name: "使用 GitHub 继续" })).toBeVisible();
  await expect(page.getByText("开始使用")).toHaveCount(0);
  await expect(page.getByText("无需注册")).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "今天" })).toHaveCount(0);
  await page.goto("/auth/callback?error=access_denied&error_code=access_denied");
  await expect(page.getByText("登录已取消。")).toBeVisible();
  await expect(page.getByRole("tab", { name: "今天" })).toHaveCount(0);
});

test("an anonymous session cannot open the app", async ({ page }) => {
  await mark(page, "guest");
  await page.addInitScript(() => {
    sessionStorage.setItem("mimi.web.session.v1", JSON.stringify({ userId: "guest" }));
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "使用 Google 继续" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "今天" })).toHaveCount(0);
});

test("a named OAuth session shows the parenting shell and blocks a saved child", async ({ page }) => {
  await mark(page, "named");
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "今天" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "成长" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "我们家" })).toBeVisible();
  await expect(page.getByText("孩子的信息只存在手机上")).toBeVisible();
  await expect(page.getByText("family_v1")).toHaveCount(0);
  await expect(page.getByText("会加密保留")).toHaveCount(0);
  await expect(page.getByText("开始使用")).toHaveCount(0);
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "添加孩子" }).click();
  await expect(page.getByText("不保存、不同步任何孩子资料")).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
  await page.getByRole("button", { name: "提醒" }).click();
  await expect(page.getByText("这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。")).toBeVisible();
  await page.getByRole("button", { name: "我们家" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await expect(page.getByText("会加密保留")).toHaveCount(0);
  await expect(page.getByText("网页不保存、不同步孩子资料。")).toBeVisible();
});

test("idle lock sends the user back to the same provider", async ({ page }) => {
  await page.clock.install({ time: new Date() });
  await mark(page, "named");
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "今天" })).toBeVisible();
  await page.clock.fastForward(16 * 60 * 1000);
  const dialog = page.getByRole("dialog", { name: "重新确认登录" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "退出登录" })).toBeVisible();
  await expect.poll(async () => (await page.context().cookies()).some((cookie) => cookie.name === "mimi_locked" && cookie.httpOnly)).toBe(true);
  await page.route("https://abcdefghijklmnopqrst.supabase.co/**", (route) => route.abort());
  const authorize = page.waitForRequest((request) => request.url().includes("/auth/v1/authorize"));
  await dialog.getByRole("button", { name: "继续" }).click({ noWaitAfter: true });
  const url = new URL((await authorize).url());
  expect(url.searchParams.get("provider")).toBe("google");
  expect(url.searchParams.get("prompt")).toBe("select_account");
});

test("a lock still blocks after the old 30 minute window", async ({ page }) => {
  await page.clock.install({ time: new Date() });
  await mark(page, "named");
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "今天" })).toBeVisible();
  await page.clock.fastForward(16 * 60 * 1000);
  const dialog = page.getByRole("dialog", { name: "重新确认登录" });
  await expect(dialog).toBeVisible();
  await expect.poll(async () => {
    const cookie = (await page.context().cookies()).find((item) => item.name === "mimi_locked");
    return Boolean(cookie?.httpOnly && cookie.expires > Date.now() / 1000 + 60 * 60);
  }).toBe(true);
  await page.clock.fastForward(31 * 60 * 1000);
  await page.clock.resume();
  await page.reload();
  await expect(dialog).toBeVisible();
  const edge = await page.request.get("/api/edge/catpaw/account/v1/session");
  expect(edge.status()).toBe(401);
  expect(await edge.json()).toEqual({ error: "locked" });
});

const secret = "橘色小猫画作七号";

async function dumpStorage(page: Page) {
  return page.evaluate(async () => {
    const textOf = (buffer: ArrayBuffer) => {
      const view = new Uint8Array(buffer);
      let text = "";
      for (const byte of view) text += String.fromCharCode(byte);
      return text;
    };
    const local = JSON.stringify(Object.fromEntries(Object.keys(localStorage).map((key) => [key, localStorage.getItem(key)])));
    const session = JSON.stringify(Object.fromEntries(Object.keys(sessionStorage).map((key) => [key, sessionStorage.getItem(key)])));
    const names = (await indexedDB.databases()).map((db) => db.name || "").filter(Boolean);
    const vaults: string[] = [];
    for (const name of names) {
      const rows = await new Promise<unknown[]>((resolve) => {
        const open = indexedDB.open(name);
        open.onerror = () => resolve([]);
        open.onsuccess = () => {
          const db = open.result;
          const stores = [...db.objectStoreNames];
          if (stores.length === 0) {
            db.close();
            resolve([]);
            return;
          }
          const found: unknown[] = [];
          let left = stores.length;
          for (const store of stores) {
            const request = db.transaction(store, "readonly").objectStore(store).getAll();
            request.onsuccess = () => {
              found.push(request.result);
              left -= 1;
              if (left === 0) {
                db.close();
                resolve(found);
              }
            };
            request.onerror = () => {
              left -= 1;
              if (left === 0) {
                db.close();
                resolve(found);
              }
            };
          }
        };
      });
      vaults.push(JSON.stringify({ name, rows }, (_key, value) => {
        if (value instanceof ArrayBuffer) return textOf(value);
        if (ArrayBuffer.isView(value)) return textOf(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
        if (typeof CryptoKey !== "undefined" && value instanceof CryptoKey) return `CryptoKey:${value.extractable ? "extractable" : "locked"}`;
        return value;
      }));
    }
    return { local, session, names, vaults: vaults.join("\n") };
  });
}

test("chat text is encrypted at rest and deleted on sign-out", async ({ page }) => {
  await mark(page, "named");
  await page.goto("/");
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await page.getByRole("button", { name: "高级与历史" }).click();
  await page.getByRole("textbox", { name: "问问小咪" }).fill(secret);
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByRole("article").getByText(secret)).toBeVisible();
  await expect.poll(async () => {
    const dump = await dumpStorage(page);
    return dump.names.includes("mimi.web.vault.v1") && !`${dump.local}\n${dump.session}\n${dump.vaults}`.includes(secret);
  }).toBe(true);
  await page.reload();
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await page.getByRole("button", { name: "高级与历史" }).click();
  await expect(page.getByRole("article").getByText(secret)).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
  await page.getByRole("button", { name: "退出登录" }).click();
  await page.getByRole("dialog", { name: "退出登录？" }).getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("button", { name: "使用 Google 继续" })).toBeVisible();
  await expect.poll(async () => {
    const dump = await dumpStorage(page);
    return !dump.names.includes("mimi.web.vault.v1") && !`${dump.local}\n${dump.session}\n${dump.vaults}`.includes(secret);
  }).toBe(true);
});

const E2E_USER = "11111111-1111-1111-1111-111111111111";
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

async function openAdvanced(page: Page) {
  await mark(page, "named");
  await page.goto("/");
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await page.getByRole("button", { name: "高级与历史" }).click();
  await expect(page.getByRole("textbox", { name: "问问小咪" })).toBeVisible();
}

test("dictation uses a microphone and a stop control, and stays hidden without the Speech API or after 仅打字", async ({ page }) => {
  await page.addInitScript(() => {
    const world = window as unknown as { SpeechRecognition?: unknown; webkitSpeechRecognition?: unknown };
    delete world.SpeechRecognition;
    delete world.webkitSpeechRecognition;
  });
  await openAdvanced(page);
  await expect(page.getByRole("button", { name: "听写" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "停止听写" })).toHaveCount(0);
  await expect(page.getByText("转写中")).toHaveCount(0);

  await page.addInitScript(() => {
    class FakeSpeech {
      lang = "";
      continuous = false;
      interimResults = false;
      onresult: ((event: unknown) => void) | null = null;
      onerror: ((event: unknown) => void) | null = null;
      onend: (() => void) | null = null;
      start() {
        (window as unknown as { __dictation?: FakeSpeech }).__dictation = this;
      }
      stop() { this.onend?.(); }
      abort() { this.onend?.(); }
    }
    const world = window as unknown as { SpeechRecognition: typeof FakeSpeech; webkitSpeechRecognition: typeof FakeSpeech };
    world.SpeechRecognition = FakeSpeech;
    world.webkitSpeechRecognition = FakeSpeech;
  });
  await page.reload();
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await page.getByRole("button", { name: "高级与历史" }).click();
  const mic = page.getByRole("button", { name: "听写" });
  await expect(mic).toBeVisible();
  await expect(mic).toHaveAttribute("aria-pressed", "false");
  await expect(mic.locator("svg")).toBeVisible();
  await expect(mic).not.toContainText(/[◎■]/);
  await mic.click();
  const privacy = page.getByRole("dialog", { name: "使用听写前请先了解" });
  await expect(privacy).toBeVisible();
  await privacy.getByRole("button", { name: "仅打字" }).click();
  await expect(privacy).toHaveCount(0);
  await expect(page.getByRole("button", { name: "听写" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "停止听写" })).toHaveCount(0);
  await expect(page.getByText("转写中")).toHaveCount(0);
});

test("dictation writes interim text into the draft and switches the button to stop", async ({ page }) => {
  await page.addInitScript((userId) => {
    localStorage.setItem(`mimi.web.dictationConsent.v1.${userId}`, "allow");
    class FakeSpeech {
      lang = "";
      continuous = false;
      interimResults = false;
      onresult: ((event: unknown) => void) | null = null;
      onerror: ((event: unknown) => void) | null = null;
      onend: (() => void) | null = null;
      start() {
        (window as unknown as { __dictation?: FakeSpeech }).__dictation = this;
      }
      stop() { this.onend?.(); }
      abort() { this.onend?.(); }
    }
    const world = window as unknown as { SpeechRecognition: typeof FakeSpeech; webkitSpeechRecognition: typeof FakeSpeech };
    world.SpeechRecognition = FakeSpeech;
    world.webkitSpeechRecognition = FakeSpeech;
  }, E2E_USER);
  await openAdvanced(page);
  const mic = page.getByRole("button", { name: "听写" });
  await mic.click();
  const stop = page.getByRole("button", { name: "停止听写" });
  await expect(stop).toHaveAttribute("aria-pressed", "true");
  await expect(stop.locator("svg")).toBeVisible();
  await expect(stop).not.toContainText(/[◎■]/);
  await page.evaluate(() => {
    const recognition = (window as unknown as { __dictation?: { onresult: ((event: unknown) => void) | null } }).__dictation;
    if (!recognition?.onresult) throw new Error("dictation did not start");
    const finalResult = Object.assign([{ transcript: "周六春游要带" }], { isFinal: true, length: 1 });
    const interimResult = Object.assign([{ transcript: "水壶和帽子" }], { isFinal: false, length: 1 });
    recognition.onresult({ resultIndex: 0, results: [finalResult, interimResult] });
  });
  const interim = page.locator(".dictation-interim");
  await expect(interim).toHaveText("水壶和帽子");
  await expect(page.getByRole("textbox", { name: "问问小咪" })).toHaveValue("周六春游要带水壶和帽子");
  await expect(page.getByText("转写中")).toHaveCount(0);
  const colors = await interim.evaluate((node) => {
    const settled = node.parentElement?.querySelector("span");
    return {
      interim: getComputedStyle(node).color,
      settled: settled ? getComputedStyle(settled).color : "",
    };
  });
  expect(colors.interim).not.toBe(colors.settled);
});

test("calendar and reminder cards download a file and do not say 加入", async ({ page }) => {
  await page.route("**/api/edge/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path.endsWith("/model-route")) {
      return json({ route_hash: "e2e-route", ui: { title: "确认", body: "说明" } });
    }
    if (path.endsWith("/model-consent")) {
      return json({ consent: { decision: "allow", route_hash: "e2e-route", revision: 1 } });
    }
    if (path.includes("/conversations/") && !path.includes("/turns") && route.request().method() === "GET") {
      return json({ conversation: { state: "active", policy: "conversation-local-output-v1", revision: 0, grant_epoch: 0 } });
    }
    if (path.endsWith("/turns") && route.request().method() === "POST") {
      return json({
        turn: {
          events: [
            {
              type: "device_call",
              sequence: 1,
              data: {
                id: "cal-1",
                tool: "calendar_create",
                args: { title: "春游出发", start: "2026-11-16T10:00:00+08:00", end: "2026-11-16T11:00:00+08:00", location: "望京街10号" },
              },
            },
            {
              type: "device_call",
              sequence: 2,
              data: { id: "rem-1", tool: "reminder_create", args: { title: "带水壶", due: "2026-11-16T09:00:00+08:00" } },
            },
            {
              type: "device_call",
              sequence: 3,
              data: { id: "phone-1", tool: "phone_call", args: { name: "园长", number: "01055550000" } },
            },
            { type: "turn_completed", sequence: 4, data: {} },
          ],
        },
      });
    }
    return route.fallback();
  });
  await openAdvanced(page);
  await page.getByRole("textbox", { name: "问问小咪" }).fill("帮我把春游安排落到这台设备上");
  await page.getByRole("button", { name: "发送" }).click();
  const cards = page.locator("section.device-call");
  await expect(cards).toHaveCount(3);
  for (const index of [0, 1]) {
    const card = cards.nth(index);
    await expect(card.getByRole("button", { name: "下载日历文件" })).toBeVisible();
    await expect(card).toContainText("在网页上会下载一个日历文件，不会直接写入系统日历。");
    await expect(card).not.toContainText("加入");
    await expect(card.getByRole("button", { name: "加入" })).toHaveCount(0);
    await expect(card.getByRole("button", { name: "创建" })).toHaveCount(0);
    await expect(card.getByRole("button", { name: "新建提醒" })).toHaveCount(0);
  }
  await expect(cards.nth(2)).toContainText("打电话");
  await expect(cards.nth(2).getByRole("button", { name: "呼叫" })).toBeVisible();
  await expect(page.locator(".sheet")).not.toContainText("加入");
});

test("file upload shows 上传中… and then 已上传，待发送", async ({ page }) => {
  let release = () => {};
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/edge/**/attachments**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "POST" && path.endsWith("/attachments")) {
      await gate;
      await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
      return;
    }
    if (route.request().method() === "POST" && path.endsWith("/complete")) {
      await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
      return;
    }
    await route.fallback();
  });
  await openAdvanced(page);
  const input = page.locator('input[type="file"]');
  await input.setInputFiles({ name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
  await expect(page.getByRole("status")).toContainText("只接受 JPEG 或 PNG。");
  await input.setInputFiles({ name: "春游路线.png", mimeType: "image/png", buffer: PNG });
  try {
    await expect(page.getByText("上传中…")).toBeVisible();
    await expect(page.getByRole("img", { name: "春游路线.png" })).toBeVisible();
  } finally {
    release();
  }
  await expect(page.getByText("已上传，待发送")).toBeVisible();
  await expect(page.locator("img.attach-thumb")).toHaveAttribute("alt", "春游路线.png");
  await expect(page.getByRole("status").filter({ hasText: "只接受 JPEG 或 PNG。" })).toHaveCount(0);
});
