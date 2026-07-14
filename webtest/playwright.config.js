// @ts-check
const { defineConfig, devices } = require('@playwright/test');

const PORT = process.env.ZYNC_DEV_PORT || 8099;
const BASE = process.env.ZYNC_BASE || `http://127.0.0.1:${PORT}`;

module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: BASE,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: '../gradlew -p .. :server:webDevServer',
    url: `${BASE}/`,
    // In CI a stray process must fail the run, not silently mask a broken launcher.
    reuseExistingServer: !process.env.CI,
    timeout: 180 * 1000,
    cwd: __dirname,
  },
});
