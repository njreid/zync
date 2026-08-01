const { test, expect } = require('@playwright/test');

// The inline view-tab row (shown when the window is wide) + Ctrl+N view jumps.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('wide window shows view tabs; clicking one navigates', async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 800 });
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });

  const tabs = page.locator('.view-tabs');
  await expect(tabs).toBeVisible();
  await expect(tabs.getByText('Inbox', { exact: true })).toBeVisible();
  await expect(tabs.getByText('Projects', { exact: true })).toBeVisible();

  await tabs.getByText('Projects', { exact: true }).click();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.locator('.view-tab.active')).toHaveText('Projects');
});

test('narrow window hides the tab row (dropdown instead)', async ({ page }) => {
  await page.setViewportSize({ width: 480, height: 800 });
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.view-tabs')).toBeHidden();
  await expect(page.locator('.view-menu')).toBeVisible();
});

test('Ctrl+N jumps to the Nth view', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.keyboard.press('Control+4'); // VIEWS = [Inbox, Today, Next, Projects, Reference]
  await expect(page).toHaveURL(/\/projects$/);
  await page.keyboard.press('Control+1');
  await expect(page).toHaveURL(new RegExp(BASE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/$'));
});
