// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:8199',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command:
      "ZYNC_DEV_SERVER=1 ../gradlew -p .. :app:testDebugUnitTest --tests dev.njr.zync.server.DevServer",
    url: 'http://127.0.0.1:8199/?token=dev',
    reuseExistingServer: true,
    timeout: 180 * 1000,
    cwd: __dirname,
  },
});
