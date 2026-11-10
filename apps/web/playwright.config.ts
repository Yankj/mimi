import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "tests/e2e",
  fullyParallel: false,
  retries: 0,
  reporter: "line",
  use: { baseURL: "http://127.0.0.1:3111", trace: "off" },
  webServer: {
    command: "npm run build && npm run start -- --hostname 127.0.0.1 --port 3111",
    url: "http://127.0.0.1:3111",
    reuseExistingServer: false,
    timeout: 300_000,
    env: {
      ...process.env,
      NEXT_PUBLIC_SUPABASE_URL: "",
      NEXT_PUBLIC_SUPABASE_ANON_KEY: "",
      NEXT_PUBLIC_CATPAW_EDGE_ORIGIN: "",
      NEXT_TELEMETRY_DISABLED: "1",
      MIMI_WEB_E2E: "1",
      MIMI_WEB_SITE_URL: "",
      MIMI_WEB_SESSION_SECRET: "e2e-session-meta-hmac",
    },
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
});
