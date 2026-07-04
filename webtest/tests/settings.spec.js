const { test, expect } = require('@playwright/test');

async function withToken(page) {
  await page.goto('/?token=dev');
}

async function settle(page) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(350);
}

test.describe('Settings view', () => {
  test('device list renders from /devices, and revoke posts + updates the row', async ({ page }) => {
    await withToken(page);
    await page.goto('/#/settings');
    await settle(page);

    const row = page.locator('.device-row', { hasText: 'Test Laptop' });
    await expect(row).toBeVisible();
    await expect(row).toContainText('Last seen:');

    // Idempotent w.r.t. a previous run against the same dev server instance: only click
    // Revoke if the device isn't already revoked from an earlier pass.
    const revokeBtn = row.locator('button[data-act="revoke"]');
    if (await revokeBtn.count() > 0) {
      await revokeBtn.click();
      await settle(page);
    }

    await expect(row).toContainText('(revoked)');
    await expect(row.locator('button[data-act="revoke"]')).toHaveCount(0);
  });

  test('"Pair a browser" degrades gracefully with no native bridge', async ({ page }) => {
    await withToken(page);
    await page.goto('/#/settings');
    await settle(page);

    // window.ZyncNative genuinely does not exist in a plain headless browser — this exercises
    // that real-world path, not a contrived one.
    await page.locator('#pair-scan').click();
    await expect(page.locator('#pair-result')).toHaveText('QR scanning is only available in the zync app.');
  });

  test('"Pair a browser" invokes a stubbed native bridge', async ({ page }) => {
    await page.addInitScript(() => {
      window.ZyncNative = {
        scanPairingQr: () => { window.__zyncScanCalled = true; },
      };
    });
    await withToken(page);
    await page.goto('/#/settings');
    await settle(page);

    await page.locator('#pair-scan').click();
    await expect(page.locator('#pair-result')).toHaveText('Scanning…');
    expect(await page.evaluate(() => window.__zyncScanCalled)).toBe(true);

    // Simulate the native side calling back with a scanned payload; since no pairing is in
    // progress on the (Robolectric-hosted) dev server, /pair/approve correctly rejects it —
    // this only proves the JS round-trip (native callback -> POST /pair/approve -> UI update)
    // wires up, not that a full end-to-end pairing succeeds (that needs a real phone/desktop).
    await page.evaluate(() => window.__zyncQrScanResult('not-real-json', null));
    await settle(page);
    await expect(page.locator('#pair-result')).toContainText('Pairing failed');
  });
});
