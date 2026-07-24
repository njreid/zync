const { test, expect } = require('@playwright/test');
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';
test('File toggles the drawer closed on second click', async ({ page }) => {
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  page.on('console', m => { if (m.type()==='error') errs.push(m.text()); });
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row').first();
  await row.locator('.row-title').click();
  const panel = row.locator('.expanded');
  const picker = panel.locator('.file-picker');
  const fileBtn = panel.locator('[data-act="file"]');
  await fileBtn.click();
  await expect(picker).toBeVisible();
  await fileBtn.click();               // second click should close it
  await expect(picker).toBeHidden();
  console.log('ERRORS:', JSON.stringify(errs));
});
