#!/usr/bin/env node
// Mirror the top two levels of the Google Drive folder structure into the zync Reference tree.
// One-time seed (NOT idempotent — running twice makes duplicates). Node 18+ (global fetch).
//
//   ZYNC_URL=https://dev.choosh.ai ZYNC_BOT_TOKEN=<bot token with create capability> \
//     node scripts/mirror-drive-reference.mjs
//
// Captured from Drive on 2026-07-25 (the numbered 01–10 PARA scheme; legacy top-level folders
// — Personal, Family Docs, Reading, Projects, Unsorted Files, Colab Notebooks, Archive — omitted).

const ZYNC_URL = process.env.ZYNC_URL;
const TOKEN = process.env.ZYNC_BOT_TOKEN;
if (!ZYNC_URL || !TOKEN) {
  console.error('set ZYNC_URL and ZYNC_BOT_TOKEN');
  process.exit(1);
}

// Area → its subfolders. Numeric prefixes kept so Reference (title-sorted) preserves the order.
const TREE = {
  '01 Personal Admin': ['Health', 'Legal and ID', 'Finance and Tax'],
  '02 Career and Job Search': ['Resumes', 'Applications and Cover Letters'],
  '03 Employers and Work': ['Old Companies', 'Archive Old Jobs', 'Google', 'Amazon', 'Vidyo Era', 'SevOne Era'],
  '04 Real Estate and Home': ['Winslow Grove', 'Finance', 'Renovations and Contractors', 'UK Properties', 'Seattle Properties', 'Philly Properties'],
  '05 Development and Tech': ['Projects', 'CS Theory and Papers', 'Development'],
  '06 Family and Kids': ['Schools and Activities', 'Jasper', 'Poppy'],
  '07 Media and Memories': ['Music', 'Movies', 'Photos', 'Sophie Archive', 'Hobbies', 'Wedding'],
  '08 Inbox to Sort': [],
  '09 Topics': ['Psychology', 'Category Theory'],
  '10 Friends & Correspondence': ['Amfo - Science Book', 'Damian & Nicole'],
};

async function ops(intents) {
  const r = await fetch(ZYNC_URL + '/api/ops', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify({ intents }),
  });
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + (await r.text()));
  const body = await r.json();
  const bad = (body.results || []).find((x) => x.status === 'error');
  if (bad) throw new Error('intent error: ' + JSON.stringify(bad));
  return body.results;
}

const areas = Object.keys(TREE);
// Level 1: create the areas directly under the Reference root.
const l1 = await ops(areas.map((title) => ({ op: 'create', title, parent: 'reference' })));
const idOf = Object.fromEntries(areas.map((t, i) => [t, l1[i].nodeId]));

// Level 2: create each area's subfolders under it.
const subs = [];
for (const area of areas) for (const sub of TREE[area]) subs.push({ op: 'create', title: sub, parent: idOf[area] });
if (subs.length) await ops(subs);

console.log(`Created ${areas.length} areas + ${subs.length} subfolders under Reference.`);
