import { expect, test } from "@playwright/test";

const namedSession = {
  accessToken: "oauth",
  refreshToken: "refresh",
  expiresAt: Date.now() + 60 * 60 * 1000,
  userId: "11111111-1111-1111-1111-111111111111",
  email: "parent@example.com",
  isAnonymous: false,
  scopeId: "22222222-2222-2222-2222-222222222222",
};

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
  await page.addInitScript(() => {
    sessionStorage.setItem(
      "mimi.web.session.v1",
      JSON.stringify({
        accessToken: "guest",
        refreshToken: "guest",
        expiresAt: Date.now() + 60 * 60 * 1000,
        userId: "11111111-1111-1111-1111-111111111111",
        email: null,
        isAnonymous: true,
        scopeId: "22222222-2222-2222-2222-222222222222",
      }),
    );
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "使用 Google 继续" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "今天" })).toHaveCount(0);
});

test("a named OAuth session shows the parenting shell and blocks a saved child", async ({ page }) => {
  await page.addInitScript((session) => {
    sessionStorage.setItem("mimi.web.session.v1", JSON.stringify(session));
  }, namedSession);
  await page.goto("/");
  await expect(page.getByRole("tab", { name: "今天" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "成长" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "我们家" })).toBeVisible();
  await expect(page.getByText("family_v1")).toBeVisible();
  await expect(page.getByText("开始使用")).toHaveCount(0);
  await page.getByRole("tab", { name: "我们家" }).click();
  await page.getByRole("button", { name: "添加孩子" }).click();
  await expect(page.getByText("不保存、不同步任何孩子资料")).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
  await page.getByRole("button", { name: "提醒" }).click();
  await expect(page.getByText("这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。")).toBeVisible();
});
