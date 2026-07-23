const { test, expect } = require('@playwright/test');

// The mobile GTD shell: the fixed categories live in a top-bar "View" dropdown (left) with a
// "Context" dropdown (right) — no bottom tab bar. Drives the same shared :web UI as the phone
// WebView in real headless Chromium.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('top-bar View dropdown navigates the fixed GTD categories', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });

  // The top bar carries the View + Context dropdowns; the old bottom tab bar is gone.
  await expect(page.locator('nav.topbar .view-menu')).toBeVisible();
  await expect(page.locator('nav.topbar .context-menu')).toBeVisible();
  await expect(page.locator('nav.tabbar')).toHaveCount(0);

  // Views live behind the View dropdown; the current view is marked active.
  await page.locator('.view-menu > summary').click();
  await expect(page.locator('.view-menu a.active')).toContainText('Inbox');
  await page.locator('.view-menu a', { hasText: 'Next' }).click();
  await expect(page).toHaveURL(/\/next$/);
  await expect(page.locator('#next')).toContainText('Launch website'); // project label
  await expect(page.locator('#next')).toContainText('Draft the launch copy'); // its next action

  // Projects → the project list, drilling into a project's detail.
  await page.locator('.view-menu > summary').click();
  await page.locator('.view-menu a', { hasText: 'Projects' }).click();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.locator('#projects')).toContainText('Launch website');
  await expect(page.locator('#projects')).toContainText('open'); // open next-action count
  // projects are collapsible items now: expand to reveal the subtask
  const proj = page.locator('#projects li.item').filter({ has: page.locator('.row-title', { hasText: 'Launch website' }) });
  await proj.locator('.row-title').click();
  await expect(proj.locator('.subtasks-list')).toContainText('Draft the launch copy');

  // The top bar (with the View dropdown) is present on the detail page too.
  await expect(page.locator('nav.topbar .view-menu')).toBeVisible();
});
