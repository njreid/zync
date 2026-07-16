const { test, expect } = require('@playwright/test');

// The shared :web UI served by the in-memory dev server (./gradlew :server:webDevServer).
// Both the browser (central-server) and phone-WebView UX are this same :web + Datastar;
// this drives it in a real headless Chromium to verify the CLIENT side — Datastar loads,
// renders, and reacts — which Kotlin/Robolectric can't observe.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('renders seeded inbox and Datastar drives live mutations', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(BASE + '/', { waitUntil: 'networkidle' });

  // server-rendered content is present
  await expect(page.locator('#inbox')).toContainText('Buy milk');
  await expect(page.locator('#inbox')).toContainText('Read a book');

  // the Datastar runtime actually loaded + executed (no CSP/eval failure)
  const hasDatastar = await page.evaluate(() =>
    !!document.querySelector('script[src="/assets/datastar.js"]'));
  expect(hasDatastar).toBe(true);

  // the dark theme is applied (Pico dark, from stylesheet FILES — the CSP has no inline-style carve-out)
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  const [r, g, b] = bg.match(/\d+/g).map(Number);
  expect(r + g + b, `body background should be dark, got ${bg}`).toBeLessThan(150);

  // reactivity: complete "Buy milk" → Datastar @post returns an SSE patch → it drops out
  await page.locator('#inbox li', { hasText: 'Buy milk' }).getByTitle('Complete').click();
  await expect(page.locator('#inbox')).not.toContainText('Buy milk', { timeout: 5000 });
  await expect(page.locator('#inbox')).toContainText('Read a book'); // others stay

  // quick-add via the data-bind input → posts the signal → new item patched in
  await page.locator('#inbox input[placeholder="New task"]').fill('Water plants');
  await page.locator('#inbox button', { hasText: 'Add' }).click();
  await expect(page.locator('#inbox')).toContainText('Water plants', { timeout: 5000 });

  // context pill (launcher L4): selecting @errands filters to its tagged tasks
  await page.locator('.context-pill summary').click();
  await page.locator('.context-pill a', { hasText: '@errands' }).click();
  await expect(page.locator('.context-pill summary')).toContainText('@errands');
  await expect(page.locator('#inbox')).toContainText('Plan the offsite');
  await expect(page.locator('#inbox')).not.toContainText('Water plants');
  // and switching back to All contexts restores the plain inbox
  await page.locator('.context-pill summary').click();
  await page.locator('.context-pill a', { hasText: 'All contexts' }).click();
  await expect(page.locator('#inbox')).toContainText('Water plants');

  // organize controls on the detail page: set a due date, tag a context
  await page.locator('#inbox a', { hasText: 'Water plants' }).click();
  await expect(page.locator('main')).toContainText('Organize');
  await page.locator('input[type="date"]').fill('2026-07-20');
  await page.locator('button', { hasText: 'Set due' }).click();
  await expect(page.locator('main')).toContainText('due 2026-07-20', { timeout: 5000 });
  await page.locator('button', { hasText: '+ @errands' }).click();
  await expect(page.locator('button', { hasText: '@errands ✕' })).toBeVisible({ timeout: 5000 });

  // Datastar executed cleanly — no CSP 'unsafe-eval' violation or JS errors
  const csp = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/i.test(e));
  expect(csp, `page errors: ${errors.join(' | ')}`).toEqual([]);
});
