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

  // reactivity: swipe "Buy milk" right → Datastar @post returns an SSE patch → it drops out
  {
    const row = page.locator('#inbox li.swipe-row', { hasText: 'Buy milk' });
    const box = await row.boundingBox();
    const y = box.y + box.height / 2, sx = box.x + box.width / 2;
    await page.mouse.move(sx, y);
    await page.mouse.down();
    for (let i = 1; i <= 6; i++) await page.mouse.move(sx + (160 * i) / 6, y);
    await page.mouse.up();
  }
  await expect(page.locator('#inbox')).not.toContainText('Buy milk', { timeout: 5000 });
  await expect(page.locator('#inbox')).toContainText('Read a book'); // others stay

  // the inbox is a triage surface: no VISIBLE entry field (creation belongs to capture;
  // the per-row triage panels are collapsed by default) and NO context filter.
  expect(await page.locator('#inbox input:visible').count()).toBe(0);
  expect(await page.locator('.context-pill').count()).toBe(0);

  // organize controls on the detail page: set a due date, tag a context
  await page.locator('#inbox a', { hasText: 'Read a book' }).click();
  await expect(page.locator('main')).toContainText('Organize');
  await page.locator('input[type="date"]').fill('2026-07-20');
  await page.locator('button', { hasText: 'Set due' }).click();
  await expect(page.locator('main')).toContainText('due 2026-07-20', { timeout: 5000 });
  await page.locator('button', { hasText: '+ @errands' }).click();
  await expect(page.locator('button', { hasText: '@errands ✕' })).toBeVisible({ timeout: 5000 });

  // context view (launcher L4, reached by URL / native selection): pill + tagged tasks
  const untag = await page.locator('button', { hasText: '@errands ✕' }).getAttribute('data-on:click');
  const contextId = untag.match(/context=([0-9A-Za-z]+)/)[1];
  await page.goto(BASE + '/?context=' + contextId, { waitUntil: 'networkidle' });
  await expect(page.locator('.context-pill summary')).toContainText('@errands');
  await expect(page.locator('#inbox')).toContainText('Plan the offsite');
  await expect(page.locator('#inbox')).toContainText('Read a book'); // just tagged above
  // and the pill's Inbox link returns to the plain, pill-less inbox
  await page.locator('.context-pill summary').click();
  await page.locator('.context-pill a', { hasText: 'Inbox' }).click();
  await expect(page.locator('#inbox')).toContainText('Read a book');
  expect(await page.locator('.context-pill').count()).toBe(0);

  // Datastar executed cleanly — no CSP 'unsafe-eval' violation or JS errors
  const csp = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/i.test(e));
  expect(csp, `page errors: ${errors.join(' | ')}`).toEqual([]);
});
