const { test, expect } = require('@playwright/test');

// Verifies the loopback CSP requirement empirically: Datastar evaluates its data-*
// expressions, which a strict `default-src 'self'` CSP blocks — the phone loopback must
// carve out `script-src 'unsafe-eval'`. Run the dev server with the CSP under test:
//   ZYNC_DEV_CSP="<csp>" ./gradlew :server:webDevServer
// This asserts the CURRENTLY-served page reacts (so run it against the eval-carve-out CSP).
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('Datastar reactivity works under the served CSP', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  // Completes its own dedicated seeded task by swiping it right (the inbox has no
  // visible buttons — swipe fires a hidden Datastar-bound trigger). The complete goes
  // through a Datastar expression — exactly what a too-strict CSP would block.
  const row = page.locator('#inbox li.swipe-row', { hasText: 'CSP probe task' });
  await expect(row).toBeVisible();
  const box = await row.boundingBox();
  const y = box.y + box.height / 2, sx = box.x + box.width / 2;
  await page.mouse.move(sx, y);
  await page.mouse.down();
  for (let i = 1; i <= 6; i++) await page.mouse.move(sx + (160 * i) / 6, y);
  await page.mouse.up();
  // If the CSP blocks Datastar's eval, this never happens (allow >3s for the swipe undo window).
  await expect(page.locator('#inbox')).not.toContainText('CSP probe task', { timeout: 7000 });
});
