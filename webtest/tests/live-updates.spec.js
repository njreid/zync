const { test, expect } = require('@playwright/test');

// Regression guard for the data-init SSE fix: a <div> never fires a DOM `load` event, so the old
// data-on:load="@get('/updates')" hook was dead and broadcast/live updates never reached the
// browser. data-init opens the stream on setup, so a change made anywhere patches every open view.
const BASE = process.env.ZYNC_BASE || 'http://127.0.0.1:8099';

test('a change in one client live-updates another via the /updates SSE', async ({ browser }) => {
  const a = await browser.newPage();
  const updatesOpened = [];
  a.on('request', (r) => { if (r.url().includes('/updates')) updatesOpened.push(r.url()); });

  // NB: domcontentloaded, not networkidle — the SSE stream is a perpetual connection, so the
  // network is never idle once data-init opens it.
  await a.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await a.waitForTimeout(1000); // let A's /updates stream open
  expect(updatesOpened.length, 'A should open the /updates SSE on load').toBeGreaterThan(0);

  // B captures an item without ever touching A.
  const b = await browser.newPage();
  await b.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await b.evaluate(async () => {
    const fd = new FormData();
    fd.append('title', 'LiveFromOtherClient');
    await fetch('/capture', { method: 'POST', body: fd });
  });

  // A receives it purely over its open SSE stream.
  await expect(a.locator('#inbox')).toContainText('LiveFromOtherClient', { timeout: 6000 });
});
