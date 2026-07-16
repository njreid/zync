const { test, expect } = require('@playwright/test');

// Verifies the loopback CSP requirement empirically: Datastar evaluates its data-*
// expressions, which a strict `default-src 'self'` CSP blocks — the phone loopback must
// carve out `script-src 'unsafe-eval'`. Run the dev server with the CSP under test:
//   ZYNC_DEV_CSP="<csp>" ./gradlew :server:webDevServer
// This asserts the CURRENTLY-served page reacts (so run it against the eval-carve-out CSP).
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('Datastar reactivity works under the served CSP', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  // Completes its own dedicated seeded task (the inbox is a triage surface — no
  // entry field). The complete goes through a Datastar expression — exactly what
  // a too-strict CSP would block.
  await expect(page.locator('#inbox')).toContainText('CSP probe task');
  await page.locator('#inbox li', { hasText: 'CSP probe task' }).getByTitle('Complete').click();
  // If the CSP blocks Datastar's eval, this never happens.
  await expect(page.locator('#inbox')).not.toContainText('CSP probe task', { timeout: 5000 });
});
