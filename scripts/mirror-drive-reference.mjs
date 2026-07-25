#!/usr/bin/env node
// Mirror the top two levels of the Google Drive folder structure into the zync Reference tree.
// IDEMPOTENT: each folder gets a deterministic node id (hash of its path), and we set title +
// parent via setField/move (last-write-wins). Re-running converges to the same tree — no dupes —
// and a human rename later wins over this bot (human-beats-bot merge), so re-runs won't clobber it.
// Node 18+ (global fetch).
//
//   ZYNC_URL=https://dev.choosh.ai ZYNC_BOT_TOKEN=<bot token with setField+move> \
//     node scripts/mirror-drive-reference.mjs
//
// Captured from Drive on 2026-07-25 (the numbered 01–10 PARA scheme; legacy top-level folders
// omitted). Add legacy areas to TREE and re-run to extend.

import { createHash } from 'node:crypto';

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

// Deterministic ULID from a stable path — mirrors core Ulid's Crockford encode (2 pad bits, so the
// leading char is always ≤ 7 and Ulid.parse round-trips it).
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
function ulidFor(path) {
  const h = createHash('sha256').update('zync-ref:' + path).digest();
  let acc = 0, bits = 2, out = '';
  for (let i = 0; i < 16; i++) {
    acc = (acc << 8) | h[i];
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += CROCKFORD[(acc >>> bits) & 31];
      acc &= (1 << bits) - 1;
    }
  }
  return out;
}

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
}

const intents = [];
let areas = 0, subs = 0;
for (const [area, children] of Object.entries(TREE)) {
  const aid = ulidFor(area);
  intents.push({ op: 'setField', target: aid, field: 'title', value: area });
  intents.push({ op: 'move', target: aid, parent: 'reference' });
  areas++;
  for (const sub of children) {
    const sid = ulidFor(area + '/' + sub);
    intents.push({ op: 'setField', target: sid, field: 'title', value: sub });
    intents.push({ op: 'move', target: sid, parent: aid });
    subs++;
  }
}
await ops(intents);
console.log(`Mirrored ${areas} areas + ${subs} subfolders into Reference (idempotent — safe to re-run).`);
