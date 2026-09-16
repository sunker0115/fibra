import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "tests",
  outputDir: "target/test-results",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: { baseURL: "http://127.0.0.1:4179", browserName: "chromium", headless: true },
  webServer: { command: "node serve.mjs", url: "http://127.0.0.1:4179", reuseExistingServer: false },
});
