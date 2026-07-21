const { test, expect } = require('@playwright/test');

// Exercises the vendored gesture helper (zync-gestures.js) in a real browser:
// swipe-right completes, swipe-left trashes, taps still open, under-threshold does
// nothing, and the keyboard cursor + g-chords work. Pointer Events fire for
// page.mouse, so no touch emulation is needed. Runs against :server:webDevServer,
// which seeds "Swipe me done" / "Swipe me gone".
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

async function rowBox(page, text) {
  const row = page.locator('#inbox li.swipe-row', { hasText: text });
  await expect(row).toBeVisible();
  return { row, box: await row.boundingBox() };
}

async function drag(page, box, dx) {
  const y = box.y + box.height / 2;
  const startX = box.x + box.width / 2;
  await page.mouse.move(startX, y);
  await page.mouse.down();
  // Several steps so pointermove crosses the hysteresis + commit thresholds.
  for (let i = 1; i <= 6; i++) await page.mouse.move(startX + (dx * i) / 6, y);
  await page.mouse.up();
}

test('swipe-right completes a row', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const { box } = await rowBox(page, 'Swipe me done');
  await drag(page, box, 160);
  await expect(page.locator('#inbox')).not.toContainText('Swipe me done', { timeout: 5000 });
});

test('swipe-left trashes a row', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const { box } = await rowBox(page, 'Swipe me gone');
  await drag(page, box, -160);
  await expect(page.locator('#inbox')).not.toContainText('Swipe me gone', { timeout: 5000 });
});

test('a tap opens the item (swipe suppression does not break navigation)', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row', { hasText: 'Buy milk' });
  await row.getByRole('link', { name: 'Buy milk' }).click();
  await expect(page).toHaveURL(/\/node\//);
});

test('an under-threshold drag does not act', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const { box } = await rowBox(page, 'Read a book');
  await drag(page, box, 20); // below the commit threshold
  await expect(page).toHaveURL(BASE + '/');
  await expect(page.locator('#inbox')).toContainText('Read a book');
});

test('keyboard cursor + space completes', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.locator('body').click({ position: { x: 2, y: 2 } });
  await page.keyboard.press('j'); // cursor to first row
  const cursorRow = page.locator('#inbox li.swipe-row.cursor');
  await expect(cursorRow).toHaveCount(1);
  const title = (await cursorRow.locator('a[href^="/node/"]').innerText()).trim();
  await page.keyboard.press(' '); // complete the cursor row
  await expect(page.locator('#inbox')).not.toContainText(title, { timeout: 5000 });
});

test('g-chord switches tabs', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.keyboard.press('g');
  await page.keyboard.press('n');
  await expect(page).toHaveURL(/\/next$/);
});

test('no CSP or JS errors from the gesture helper', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await expect(page.locator('script[src="/assets/zync-gestures.js"]')).toHaveCount(1);
  const bad = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/.test(e));
  expect(bad, bad.join('\n')).toHaveLength(0);
});
