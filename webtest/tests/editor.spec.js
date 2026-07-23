const { test, expect } = require('@playwright/test');

// The transactional item editor: text fields buffer into Datastar signals, show a green border
// (data-changed) while changed, and commit together on Save (which then returns to the list).
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('editor buffers a text field, marks it changed, and Save commits it', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row').filter({ has: page.locator('.row-title', { hasText: 'Read a book' }) });
  await row.locator('.row-title').click();
  await row.locator('[data-act="edit"]').click();
  await expect(page).toHaveURL(/\/node\//);
  const editorUrl = page.url();

  const desc = page.locator('.edit-field textarea:not(.edit-title)');
  await expect(desc).toBeVisible();
  await desc.fill('a memoir about the sea');
  // reactive changed-marker (drives the green border)
  await expect(desc).toHaveAttribute('data-changed', 'true');
  expect(errors, errors.join(' | ')).toEqual([]);

  // Save commits the buffered value and returns to the list (history.back)
  await page.locator('.save-btn').click();
  await expect(page).toHaveURL(/127\.0\.0\.1:\d+\/$/, { timeout: 5000 });

  // re-open the editor: the description persisted
  await page.goto(editorUrl, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.edit-field textarea:not(.edit-title)')).toHaveValue('a memoir about the sea');
});
