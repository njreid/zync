const { test, expect } = require('@playwright/test');

// The tag editor on the item edit page: typing a word + space turns it into a chip;
// clicking the chip's ✕ removes it. (Free tags are mergeable per-label ops.)
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('space commits a tag chip and the chip removes it', async ({ page }) => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const id = await page.locator('#inbox li.swipe-row').first().getAttribute('data-node');
  await page.goto(BASE + '/node/' + id, { waitUntil: 'domcontentloaded' });

  const tagInput = page.getByPlaceholder('Add a tag');
  await expect(tagInput).toBeVisible();
  await tagInput.pressSequentially('urgent '); // trailing space commits via the input handler

  const chip = page.getByRole('button', { name: /#urgent/ });
  await expect(chip).toBeVisible();       // chip appeared after the morph
  await expect(tagInput).toHaveValue('');  // input cleared

  await chip.click();                      // ✕ removes it
  await expect(page.getByRole('button', { name: /#urgent/ })).toHaveCount(0);
});
