const { test, expect } = require('@playwright/test');

// The inbox triage EXPAND panel (GTD triage §4). The key guarantee: the panel stays
// open across the SSE morph a mutation triggers, because open state is the Datastar
// $exp signal (declared on <main>, which never morphs), not DOM state.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('tapping the title expands the read-only panel with its actions', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const row = page.locator('#inbox li.swipe-row').filter({ has: page.locator('.row-title', { hasText: 'Read a book' }) });
  await expect(row).toBeVisible();

  await row.locator('.row-title').click(); // tap the title to expand
  const panel = row.locator('.expanded');
  await expect(panel).toBeVisible();
  // The Expanded panel carries the drag handle + the File/Snooze/Edit action row.
  await expect(row.locator('.drag-handle')).toBeVisible(); // now sits left of the title in the row
  await expect(panel.locator('[data-act="file"]')).toBeVisible();
  await expect(panel.locator('[data-act="snooze"]')).toBeVisible();
});

test('expanding a parent shows its subtasks', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  // "Launch website" is a seeded inbox item with a subtask "Draft the launch copy".
  const row = page.locator('#inbox li.swipe-row').filter({ has: page.locator('.row-title', { hasText: 'Launch website' }) });
  await expect(row).toBeVisible();
  await row.locator('.row-title').click(); // expand
  const subs = row.locator('.subtasks-list');
  await expect(subs).toBeVisible();
  await expect(subs).toContainText('Draft the launch copy');
});

test('no CSP or JS errors from the triage panel', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  const row = page.locator('#inbox li.swipe-row').first();
  await row.locator('.row-title').click(); // tap the title to expand the triage panel
  const bad = errors.filter((e) => /Content Security Policy|unsafe-eval|is not defined|SyntaxError/.test(e));
  expect(bad, bad.join('\n')).toHaveLength(0);
});
