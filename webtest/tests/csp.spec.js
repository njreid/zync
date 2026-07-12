const { test, expect } = require('@playwright/test');

// Verifies the loopback CSP requirement empirically: Datastar evaluates its data-*
// expressions, which a strict `default-src 'self'` CSP blocks — the phone loopback must
// carve out `script-src 'unsafe-eval'`. Run the dev server with the CSP under test:
//   ZYNC_DEV_CSP="<csp>" ./gradlew :server:webDevServer
// This asserts the CURRENTLY-served page reacts (so run it against the eval-carve-out CSP).
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('Datastar reactivity works under the served CSP', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('#inbox')).toContainText('Buy milk');
  await page.locator('#inbox li', { hasText: 'Buy milk' }).getByTitle('Complete').click();
  // If the CSP blocks Datastar's eval, this never happens.
  await expect(page.locator('#inbox')).not.toContainText('Buy milk', { timeout: 5000 });
});
