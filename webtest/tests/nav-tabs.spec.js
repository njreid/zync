const { test, expect } = require('@playwright/test');

// The mobile GTD shell: a fixed bottom tab bar exposes the four fixed categories
// (Inbox / Next / Projects / Reference) from every page. Drives the same shared :web
// UI as the phone WebView in real headless Chromium.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('bottom tab bar navigates the fixed GTD categories', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });

  // The fixed bottom bar is present on the inbox with all four categories,
  // and the current tab is marked active.
  const tabbar = page.locator('nav.tabbar');
  await expect(tabbar).toBeVisible();
  await expect(tabbar.locator('a.tab')).toHaveCount(4);
  await expect(tabbar.locator('a.tab.active')).toContainText('Inbox');

  // Next → each project's first completable action, grouped under the project label
  // (spec §5). Assert on the stable project-grouped action (specs share one seeded
  // dev server; loose-task ordering shifts as other specs complete items).
  await tabbar.locator('a', { hasText: 'Next' }).click();
  await expect(page).toHaveURL(/\/next$/);
  await expect(page.locator('#next')).toContainText('Launch website'); // project label
  await expect(page.locator('#next')).toContainText('Draft the launch copy'); // its next action
  await expect(page.locator('nav.tabbar a.tab.active')).toContainText('Next');

  // Projects → the project list, drilling into a project's detail.
  await page.locator('nav.tabbar a', { hasText: 'Projects' }).click();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.locator('#projects')).toContainText('Launch website');
  await expect(page.locator('#projects')).toContainText('open'); // open next-action count
  await page.locator('#projects a', { hasText: 'Launch website' }).click();
  await expect(page.locator('main')).toContainText('Draft the launch copy'); // its subtask

  // The bar is still there on the detail page — one tap back to any category.
  await expect(page.locator('nav.tabbar')).toBeVisible();
});
