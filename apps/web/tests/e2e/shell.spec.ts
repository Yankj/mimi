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
