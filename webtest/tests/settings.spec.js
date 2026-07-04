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

    // Simulate the native side calling back with a scanned payload, as if the phone's camera
    // had just scanned a desktop's pairing QR. Per spec §8b the desktop originates the nonce and
    // its own Ed25519 keypair; approveScanned() only *records* the pubkey (it verifies no
    // signature at this stage — that happens later, in the challenge-response session handshake),
    // so a syntactically valid-looking base64 pubkey is sufficient here without generating a real
    // keypair in JS. This is a static, fixed constant — not a real device's key.
    const fakeDevicePubkey = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=';
    const payload = JSON.stringify({
      devicePubkey: fakeDevicePubkey,
      deviceName: 'Test Desktop',
      nonce: 'test-nonce-e2e',
    });
    await page.evaluate((p) => window.__zyncQrScanResult(p, null), payload);
    await settle(page);
    // approveScanned() is now self-sufficient (no prior beginPairing() needed), so this succeeds
    // and the phone displays the confirm code the user compares against the desktop's screen.
    await expect(page.locator('#pair-result')).toContainText('Confirm code:');
  });
});
