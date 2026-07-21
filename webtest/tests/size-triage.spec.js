const { test, expect } = require('@playwright/test');

// The inbox triage EXPAND panel (GTD triage §4). The key guarantee: the panel stays
// open across the SSE morph a mutation triggers, because open state is the Datastar
// $exp signal (declared on <main>, which never morphs), not DOM state.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('expand, set size, and the panel survives the SSE morph', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row', { hasText: 'Read a book' });
  await expect(row).toBeVisible();

  // Expand ▸ → the triage panel becomes visible.
  await row.getByTitle('Expand').click();
  const panel = row.locator('.triage');
  await expect(panel).toBeVisible();

  // Set size M → the row gets an SSE morph (chip-on + size badge appears)...
  await panel.locator('.size-chips button', { hasText: 'M' }).click();
  await expect(row.locator('.size-badge')).toHaveText('M', { timeout: 5000 });
  // ...and the panel is STILL open after that morph ($exp survived).
  await expect(panel).toBeVisible();
});

test('no CSP or JS errors from the triage panel', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  const row = page.locator('#inbox li.swipe-row').first();
  await row.getByTitle('Expand').click();
  const bad = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/.test(e));
  expect(bad, bad.join('\n')).toHaveLength(0);
});
