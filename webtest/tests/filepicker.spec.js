const { test, expect } = require('@playwright/test');

// The File button in the expanded triage panel opens a picker with two ranked sections —
// Projects and Reference — each listing path-labeled destinations (GTD triage §6). The
// Reference root is itself a destination; Projects has no single root node.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('File opens Projects + Reference sections', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row').first();
  await row.locator('.row-title').click(); // expand
  const panel = row.locator('.expanded');
  await panel.locator('[data-act="file"]').click(); // toggle the picker open

  const picker = panel.locator('.file-picker');
  await expect(picker).toBeVisible();
  await expect(picker.locator('.fp-head', { hasText: 'Projects' })).toBeVisible();
  await expect(picker.locator('.fp-head', { hasText: 'Reference' })).toBeVisible();
  // Contexts/roots render in the mono voice — the head carries the .ctx class.
  await expect(picker.locator('.fp-head.ctx').first()).toBeVisible();
});

test('opening File closes any open Snooze', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row').first();
  await row.locator('.row-title').click();
  const panel = row.locator('.expanded');

  await panel.locator('[data-act="snooze"]').click();
  await expect(panel.locator('.snooze-menu')).toBeVisible();
  await panel.locator('[data-act="file"]').click(); // File must close Snooze
  await expect(panel.locator('.snooze-menu')).toBeHidden();
  await expect(panel.locator('.file-picker')).toBeVisible();
});
