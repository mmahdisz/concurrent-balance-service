import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', workers: 1, timeout: 30000,
  use: { baseURL: process.env.UI_BASE_URL || 'http://127.0.0.1:18082', channel: 'chrome', trace: 'retain-on-failure' },
});
