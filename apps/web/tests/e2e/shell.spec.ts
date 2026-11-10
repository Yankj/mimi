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
