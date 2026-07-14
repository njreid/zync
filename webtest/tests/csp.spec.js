const { test, expect } = require('@playwright/test');

// Verifies the loopback CSP requirement empirically: Datastar evaluates its data-*
// expressions, which a strict `default-src 'self'` CSP blocks — the phone loopback must
// carve out `script-src 'unsafe-eval'`. Run the dev server with the CSP under test:
//   ZYNC_DEV_CSP="<csp>" ./gradlew :server:webDevServer
// This asserts the CURRENTLY-served page reacts (so run it against the eval-carve-out CSP).
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('Datastar reactivity works under the served CSP', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  // Self-contained: create the task this spec consumes, so it never races other
  // specs over the shared dev server's seeded state. Both the add and the complete
  // go through Datastar expressions — exactly what a too-strict CSP would block.
  await page.locator('#inbox input[placeholder="New task"]').fill('CSP probe');
  await page.locator('#inbox button', { hasText: 'Add' }).click();
  await expect(page.locator('#inbox')).toContainText('CSP probe', { timeout: 5000 });
  await page.locator('#inbox li', { hasText: 'CSP probe' }).getByTitle('Complete').click();
  // If the CSP blocks Datastar's eval, this never happens.
  await expect(page.locator('#inbox')).not.toContainText('CSP probe', { timeout: 5000 });
});
