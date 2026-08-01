const { test, expect } = require('@playwright/test');

// Keyboard triage: inbox f-then-Enter files to the Projects root; Projects `s` (inline subtask)
// and `m` (staged move-mode). Desktop keyboard surface, so a wide headless window is fine.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('inbox: f then Enter files the item to the Projects root', async ({ page }) => {
  const title = 'KbdFileTarget-' + Date.now();
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(300);

  // Create our OWN inbox item (so we don't file a row other suite tests rely on), then reload.
  await page.locator('#capture-title').fill(title);
  await page.locator('#capture-save').click();
  await page.waitForTimeout(600);
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(300);

  // Isolate it with the '/' filter so the j cursor lands on exactly our row, then f-then-Enter.
  await page.keyboard.press('/');
  await page.locator('.list-search').fill(title);
  await page.locator('.list-search').evaluate((el) => el.blur());
  await page.keyboard.press('j');       // cursor to the only visible row
  await page.keyboard.press('f');       // open the file picker + arm
  await page.waitForTimeout(150);
  await page.keyboard.press('Enter');   // → file to Projects root (default target)
  await page.waitForTimeout(800);

  // The item is filed to the Projects root — it shows there as a loose task.
  await page.goto(BASE + '/projects', { waitUntil: 'domcontentloaded' });
  const projRow = page.locator('#projects li.item').filter({ has: page.locator('.row-title', { hasText: title }) });
  await expect(projRow).toHaveCount(1);
});

test('projects: s adds a subtask inline', async ({ page }) => {
  await page.goto(BASE + '/projects', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(300);

  const proj = page.locator('#projects li.item').filter({ has: page.locator('.row-title', { hasText: 'Launch website' }) });
  // Cursor onto Launch website (first project), open the inline subtask field with `s`.
  await page.keyboard.press('j');
  await page.keyboard.press('s');
  const field = proj.locator('.inline-sub input');
  await expect(field).toBeVisible();
  await field.fill('Subtask via s key');
  await page.keyboard.press('Enter');

  await expect(proj.locator('.inline-sub')).toHaveCount(0);
  // The new subtask shows when the project is expanded.
  await proj.locator('.row-title').click();
  await expect(proj.locator('.subtasks-list')).toContainText('Subtask via s key', { timeout: 7000 });
});

test('projects: m enters move-mode, j nudges, Enter commits the reorder', async ({ page }) => {
  await page.goto(BASE + '/projects', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(300);

  const order = async () => page.locator('#projects > ul > li.item .row-title').allTextContents();
  const before = (await order()).map((s) => s.trim());
  expect(before[0]).toContain('Launch website');

  await page.keyboard.press('j'); // cursor to the first project
  await page.keyboard.press('m'); // enter move-mode
  await expect(page.locator('#projects li.moving')).toBeVisible();
  await page.keyboard.press('j'); // nudge down (staged)
  await page.keyboard.press('Enter'); // commit

  await expect(page.locator('#projects li.moving')).toHaveCount(0);
  await expect
    .poll(async () => (await order()).map((s) => s.trim())[0], { timeout: 7000 })
    .toContain('Ship the mobile app'); // Launch website moved below Ship
});
