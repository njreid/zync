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

test('add a folder, drill in, and open its long-press context menu', async ({ page }) => {
  await page.goto(BASE + '/reference', { waitUntil: 'domcontentloaded' });

  const name = 'PW Folder ' + Date.now();
  await page.getByPlaceholder('New folder…').fill(name);
  await page.getByRole('button', { name: 'Add folder' }).click();

  const row = page.locator('.ref-row .ref-link', { hasText: name });
  await expect(row).toBeVisible();
  await expect(row).toHaveAttribute('data-ref-kind', 'folder');
  await page.waitForTimeout(400); // settle after the add-folder reload before pressing (a user would)

  // A real ~600ms press (no drag) opens the context menu via the long-press handler.
  const box = await row.boundingBox();
  await page.mouse.move(box.x + 6, box.y + box.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(650);
  await page.mouse.up();
  const menu = page.locator('.ref-ctx');
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('button', { name: 'Add folder' })).toBeVisible(); // folder-only action
  await expect(menu.getByRole('button', { name: 'Delete' })).toBeVisible();
  await menu.getByRole('button', { name: 'Close' }).click();

  // Drill into the folder.
  await row.click();
  await expect(page).toHaveURL(/\/reference\/[0-9A-Z]{26}$/);
  await expect(page.locator('.ref-crumb')).toContainText(name);
});
