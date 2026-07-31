const { test, expect } = require('@playwright/test');

// The desktop-only bottom capture bar (Layout.captureBar + zync-capture.js + server /capture).
// Headless Chromium is a wide, mouse-driven (non-touch) viewport, so the bar shows here.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('capture bar: typing a title and Save creates an inbox item, then resets', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('#capture-bar')).toBeVisible(); // desktop-only, shown headless

  await page.locator('#capture-title').fill('Captured from the bar');
  await page.locator('#capture-save').click();

  // The new item streams into the inbox (SSE notifyChanged) and the bar clears itself.
  await expect(page.locator('#inbox')).toContainText('Captured from the bar', { timeout: 7000 });
  await expect(page.locator('#capture-title')).toHaveValue('');

  const csp = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/i.test(e));
  expect(csp, `page errors: ${errors.join(' | ')}`).toEqual([]);
});

test('capture bar: a chosen file stages a chip and attaches on Save', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });

  // setInputFiles drives the same staging path a drop does (change → addFiles → chip).
  await page.locator('#capture-files').setInputFiles({
    name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('hello zync'),
  });
  await expect(page.locator('.capture-chip')).toContainText('note.txt');

  await page.locator('#capture-title').fill('Item with a file');
  await page.locator('#capture-save').click();

  // A 2xx from /capture resets the bar (chips + title cleared); the item lands in the inbox.
  await expect(page.locator('.capture-chip')).toHaveCount(0, { timeout: 7000 });
  await expect(page.locator('#capture-title')).toHaveValue('');
  await expect(page.locator('#inbox')).toContainText('Item with a file', { timeout: 7000 });

  const csp = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/i.test(e));
  expect(csp, `page errors: ${errors.join(' | ')}`).toEqual([]);
});
