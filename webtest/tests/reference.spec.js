const { test, expect } = require('@playwright/test');

// The Reference tab + keyword search (GTD triage §7). The dev server seeds a filed
// "Old tax return 2024" item.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('reference tab shows the filed tree and search patches results', async ({ page }) => {
  await page.goto(BASE + '/reference', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('h2')).toHaveText('Reference');
  await expect(page.locator('#reference-results')).toContainText('Old tax return', { timeout: 5000 });

  // Typing debounces a @get that patches #reference-results over SSE.
  await page.locator('#search').fill('tax');
  await expect(page.locator('#reference-results')).toContainText('Old tax return 2024', { timeout: 5000 });
  await page.locator('#search').fill('zzznope');
  await expect(page.locator('#reference-results')).toContainText('No matches.', { timeout: 5000 });
});

test('g-chord r switches to Reference', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.keyboard.press('g');
  await page.keyboard.press('r');
  await expect(page).toHaveURL(/\/reference$/);
});
