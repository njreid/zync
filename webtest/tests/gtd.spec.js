const { test, expect } = require('@playwright/test');

const uniq = (label) => `${label} ${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function withToken(page) {
  await page.goto('/?token=dev');
}

// The app re-renders on every local action *and* again ~100ms later via its own
// websocket "changed" notification. Settling on network-idle after a mutating
// click avoids racing a click against that second, self-triggered re-render.
async function settle(page) {
  await page.waitForLoadState('networkidle');
  // The server also re-notifies over its own websocket ~100ms after any DB write
  // (Room's invalidation tracker is debounced), triggering a second, self-inflicted
  // re-render that networkidle alone doesn't account for. Give it room to land
  // before the next interaction, so we don't fill/click into a DOM about to be replaced.
  await page.waitForTimeout(350);
}

async function quickAdd(page, title) {
  await page.locator('#task-action-bar [data-mode=text]').click();
  await page.locator('#quick-title').fill(title);
  await page.locator('#quick-add button[type=submit]').click();
  await settle(page);
}

// Toggling a <details> open by clicking the <summary> is pixel-position-sensitive
// (nested links/buttons intercept the click) and racy against the app's own
// re-renders. Open it at the DOM level instead and let the app's real 'toggle'
// listener do the lazy-load, exactly as it would for a genuine user click.
// The Contexts chip filter remembers the selected context in sessionStorage across
// navigations, so clicking the chip is a *toggle*, not a "select". Make selection
// idempotent so tests don't accidentally deselect a context that's already active.
async function selectContext(page, ctxName) {
  const chip = page.locator('#chips button', { hasText: `@${ctxName}` });
  await expect(chip).toBeVisible();
  if ((await chip.getAttribute('aria-pressed')) !== 'true') {
    await chip.click();
    await settle(page);
  }
}

async function expand(detailsLocator) {
  await detailsLocator.evaluate((el) => {
    if (!el.open) {
      el.open = true;
      el.dispatchEvent(new Event('toggle', { bubbles: false }));
    }
  });
}

test.describe('GTD web functional suite', () => {
  test('a. first load shows the Inbox', async ({ page }) => {
    await withToken(page);
    await expect(page.locator('nav a[href="#/inbox"]')).toBeVisible();
    await expect(page.locator('nav a[href="#/tree"]')).toBeVisible();
    await expect(page.locator('nav a[href="#/contexts"]')).toBeVisible();
    await expect(page.locator('#context-filter')).toBeVisible();
    await expect(page.locator('#task-action-bar [data-mode=text]')).toBeVisible();
    await expect(page.locator('#task-action-bar [data-mode=voice]')).toBeVisible();
    await expect(page.locator('#task-action-bar [data-mode=scan]')).toBeVisible();
    await expect(page.locator('#task-action-bar [data-mode=upload]')).toBeVisible();
  });

  test('b. quick-add appears without reload', async ({ page }) => {
    await withToken(page);
    const title = uniq('buy milk');
    await quickAdd(page, title);
    await expect(page.locator('article.task', { hasText: title })).toBeVisible();
  });

  test('c. Clarify -> Done removes task from Inbox', async ({ page }) => {
    await withToken(page);
    const title = uniq('clarify done task');
    await quickAdd(page, title);
    const card = page.locator('article.task', { hasText: title });
    await expect(card).toBeVisible();

    await expect(async () => {
      await card.locator('details.clarify summary').click();
      await card.locator('[data-act=done]').click({ timeout: 1000 });
    }).toPass({ timeout: 15000 });
    await settle(page);

    await expect(page.locator('article.task', { hasText: title })).toHaveCount(0);
  });

  test('d. Clarify -> Someday removes from Inbox, appears in Tree > Someday', async ({ page }) => {
    await withToken(page);
    const title = uniq('clarify someday task');
    await quickAdd(page, title);
    const card = page.locator('article.task', { hasText: title });

    await expect(async () => {
      await card.locator('details.clarify summary').click();
      await card.locator('[data-act=someday]').click({ timeout: 1000 });
    }).toPass({ timeout: 15000 });
    await settle(page);

    await expect(page.locator('article.task', { hasText: title })).toHaveCount(0);

    await page.goto('/#/tree');
    await settle(page);
    const somedayDetails = page.locator('#tree > details', { hasText: 'Someday' });
    await expect(async () => {
      await expand(somedayDetails);
      await expect(somedayDetails.getByText(title)).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 15000 });
  });

  test('e. Tree: new folder, project, task via prompts', async ({ page }) => {
    await withToken(page);
    const folderName = uniq('Folder');
    const projectName = uniq('Project');
    const taskName = uniq('Task');

    await page.goto('/#/tree');
    await settle(page);

    // create folder at root.
    page.once('dialog', (d) => d.accept(folderName));
    await page.locator('#new-folder').click();
    await settle(page);
    const folder = () => page.locator('#tree > details', { hasText: folderName });
    await expect(folder()).toBeVisible();

    // the "+" lives in the summary and is clickable whether or not the node is expanded.
    page.once('dialog', (d) => d.accept(projectName));
    await folder().locator('> summary [data-add]').click();
    await settle(page);

    // expand the folder to lazy-load its (now one) child.
    const project = () => folder().locator('> .tree-children > details', { hasText: projectName });
    await expect(async () => {
      await expand(folder());
      await expect(project()).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 15000 });

    page.once('dialog', (d) => d.accept(taskName));
    await project().locator('> summary [data-add]').click();
    await settle(page);

    // creating the task triggers a full tree re-render; re-expand folder then project.
    await expect(async () => {
      await expand(folder());
      await expand(project());
      await expect(project().getByText(taskName)).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 15000 });
  });

  test('f. Contexts: create context, toggle chip on a task, filter shows it', async ({ page }) => {
    await withToken(page);
    const ctxName = uniq('ctx').replace(/\s/g, '');
    const title = uniq('context task');
    await quickAdd(page, title);
    const card = page.locator('article.task', { hasText: title });
    await card.locator('h4 a').click();
    await expect(page).toHaveURL(/#\/node\/\d+/);

    await page.goto('/#/contexts');
    await settle(page);
    page.once('dialog', (d) => d.accept(ctxName));
    await page.locator('#new-context').click();
    await settle(page);
    const chip = page.locator('#chips button', { hasText: `@${ctxName}` });
    await expect(chip).toBeVisible();

    // go back to the task detail and toggle the context chip on.
    await page.goto('/#/inbox');
    await settle(page);
    await page.locator('article.task', { hasText: title }).locator('h4 a').click();
    const detailChip = page.locator('#f-chips button', { hasText: `@${ctxName}` });
    await detailChip.click();
    await settle(page);
    await expect(detailChip).toHaveAttribute('aria-pressed', 'true');

    await page.goto('/#/contexts');
    await settle(page);
    await selectContext(page, ctxName);
    await expect(page.locator('#ctx-tasks .task', { hasText: title })).toBeVisible();
  });

  test('g. Detail: edit + persist title, defer hides/clear restores in context list', async ({ page }) => {
    await withToken(page);
    const ctxName = uniq('deferctx').replace(/\s/g, '');
    const original = uniq('defer task');
    const renamed = uniq('renamed task');

    await quickAdd(page, original);
    await page.locator('article.task', { hasText: original }).locator('h4 a').click();
    await expect(page).toHaveURL(/#\/node\/(\d+)/);
    const url = page.url();
    const nodeId = url.match(/#\/node\/(\d+)/)[1];

    // create + attach context so we can observe it in the Contexts list.
    await page.goto('/#/contexts');
    await settle(page);
    page.once('dialog', (d) => d.accept(ctxName));
    await page.locator('#new-context').click();
    await settle(page);
    await page.goto(`/#/node/${nodeId}`);
    await settle(page);
    await page.locator('#f-chips button', { hasText: `@${ctxName}` }).click();
    await settle(page);

    // edit title, save, navigate away and back -> persisted.
    await page.locator('#f-title').fill(renamed);
    await page.locator('#edit button[type=submit]').click();
    await settle(page);
    await page.goto('/#/inbox');
    await settle(page);
    await page.goto(`/#/node/${nodeId}`);
    await settle(page);
    await expect(page.locator('#f-title')).toHaveValue(renamed);

    // defer +1 week hides task from the context list.
    await page.locator('[data-defer="7"]').click();
    await settle(page);
    await page.goto('/#/contexts');
    await settle(page);
    await selectContext(page, ctxName);
    await expect(page.locator('#ctx-tasks .task', { hasText: renamed })).toHaveCount(0);

    // clear defer restores it.
    await page.goto(`/#/node/${nodeId}`);
    await settle(page);
    await page.locator('[data-defer="clear"]').click();
    await settle(page);
    await page.goto('/#/contexts');
    await settle(page);
    await selectContext(page, ctxName);
    await settle(page);
    await expect(page.locator('#ctx-tasks .task', { hasText: renamed })).toBeVisible();
  });

  test('i. Detail: unsaved title survives a chip-toggle rerender, then Save persists it', async ({ page }) => {
    await withToken(page);
    const ctxName = uniq('dirtyctx').replace(/\s/g, '');
    const original = uniq('dirty task');
    const typed = uniq('typed but unsaved title');

    await quickAdd(page, original);
    await page.locator('article.task', { hasText: original }).locator('h4 a').click();
    await expect(page).toHaveURL(/#\/node\/(\d+)/);
    const url = page.url();
    const nodeId = url.match(/#\/node\/(\d+)/)[1];

    // create a context to toggle from the detail view.
    await page.goto('/#/contexts');
    await settle(page);
    page.once('dialog', (d) => d.accept(ctxName));
    await page.locator('#new-context').click();
    await settle(page);
    await page.goto(`/#/node/${nodeId}`);
    await settle(page);

    // type into the title WITHOUT saving, then toggle a context chip.
    await page.locator('#f-title').fill(typed);
    const detailChip = page.locator('#f-chips button', { hasText: `@${ctxName}` });
    await detailChip.click();
    await settle(page);

    // the rerender triggered by the chip toggle must not discard the typed title,
    // and the chip toggle itself must have taken effect.
    await expect(page.locator('#f-title')).toHaveValue(typed);
    await expect(detailChip).toHaveAttribute('aria-pressed', 'true');

    // now Save, navigate away and back -> the typed title persisted.
    await page.locator('#edit button[type=submit]').click();
    await settle(page);
    await page.goto('/#/inbox');
    await settle(page);
    await page.goto(`/#/node/${nodeId}`);
    await settle(page);
    await expect(page.locator('#f-title')).toHaveValue(typed);
  });

  test('h. unauthenticated load fails and UI does not render', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const response = await page.goto('/');
    expect(response.status()).toBe(401);
    await expect(page.locator('nav')).toHaveCount(0);
    await context.close();
  });
});
