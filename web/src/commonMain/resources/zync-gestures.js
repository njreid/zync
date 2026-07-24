// zync gesture + keyboard layer (GTD triage spec §4/§8).
//
// A single delegated Pointer-Events + keydown listener on `document`, so it survives
// the SSE fragment swap that replaces the whole #inbox subtree on every mutation with
// zero re-binding. It invents no network calls: a committed swipe/keypress synthesizes
// a .click() on a hidden Datastar-bound trigger button in the row, and Datastar owns the
// POST + patch. CSP-safe: external ES module (script-src 'self'), no eval/new Function;
// drag feedback is a CSSOM custom-property write (not governed by style-src).

const HYST = 8;            // px before a move counts as a horizontal swipe
const COMMIT_FRAC = 0.35;  // fraction of row width to commit...
const COMMIT_MIN = 64;     // ...but at least this many px...
const COMMIT_MAX = 140;    // ...and never more (wide desktop rows shouldn't need a huge drag)
const CHORD_MS = 1200;     // g-chord window

function commitPx(row) {
  return Math.min(COMMIT_MAX, Math.max(COMMIT_MIN, COMMIT_FRAC * row.clientWidth));
}

let active = null; // { row, startX, startY, pointerId, moved }
let cursor = -1;   // index into the live .swipe-row NodeList
let pendingG = 0;  // timestamp of a pending `g` chord

function rows() {
  return Array.from(document.querySelectorAll('.swipe-row'));
}

function fire(row, kind) {
  const btn = row.querySelector('.swipe-fire.' + kind);
  if (btn) btn.click();
}

// --- Swipe undo window (~3s, device feedback) ---
// A committed swipe greys the row + shows Undo; the complete/delete only fires after the
// window elapses. Either swipe direction enters this state (left = delete, right = complete).
const PENDING_MS = 3000;
const pending = new Map(); // row -> { kind, url, timer }

function pendingUrl(row, kind) {
  return kind === 'complete' ? row.getAttribute('data-complete') : row.getAttribute('data-trash');
}
function startPending(row, kind) {
  cancelPending(row);
  row.classList.add('pending', 'pending-' + kind);
  const timer = setTimeout(() => { pending.delete(row); fire(row, kind); }, PENDING_MS);
  pending.set(row, { kind, url: pendingUrl(row, kind), timer });
}
function cancelPending(row) {
  const p = pending.get(row);
  if (!p) return;
  clearTimeout(p.timer);
  pending.delete(row);
  row.classList.remove('pending', 'pending-complete', 'pending-trash');
}

// Undo tap during the window.
document.addEventListener('click', (e) => {
  const u = e.target.closest && e.target.closest('[data-undo]');
  if (!u) return;
  e.preventDefault();
  const row = u.closest('.swipe-row');
  if (row) cancelPending(row);
}, true);

// Commit-on-leave (chosen behaviour): a Datastar click can't survive page unload, so POST via
// sendBeacon; the persistent SSE stream still pushes the resulting #inbox patch to other views.
function flushPending() {
  pending.forEach((p) => { clearTimeout(p.timer); if (p.url && navigator.sendBeacon) navigator.sendBeacon(p.url); });
  pending.clear();
}
window.addEventListener('pagehide', flushPending);

// --- Pointer / swipe ---

document.addEventListener('pointerdown', (e) => {
  // The drag handle owns its own pointer stream; and taps inside the expanded panel or on any
  // interactive control are taps, never swipes — tracking them lets a few px of touch drift arm
  // the trailing-click swallow and eat the tap (e.g. the File toggle would stick open).
  if (e.target.closest && e.target.closest('[data-drag], .expanded, button, a, input, textarea, select, label')) return;
  const row = e.target.closest && e.target.closest('.swipe-row');
  if (!row) return;
  // No setPointerCapture: we delegate on `document`, so moves arrive anyway, and
  // capture would retarget the trailing click to the row and break anchor taps.
  active = { row, startX: e.clientX, startY: e.clientY, pointerId: e.pointerId, moved: false };
});

// Rows are <a> links; a horizontal drag would otherwise start a native link-drag,
// which cancels the pointer stream (no pointerup/commit). Suppress it mid-swipe.
document.addEventListener('dragstart', (e) => {
  if (active && e.target.closest && e.target.closest('.swipe-row')) e.preventDefault();
});

document.addEventListener('pointermove', (e) => {
  if (!active || e.pointerId !== active.pointerId) return;
  const dx = e.clientX - active.startX;
  const dy = e.clientY - active.startY;
  if (!active.moved && Math.abs(dx) > HYST && Math.abs(dx) > Math.abs(dy)) {
    active.moved = true;
    active.row.classList.add('swiping');
  }
  if (active.moved) {
    e.preventDefault();
    active.row.style.setProperty('--swipe-dx', dx + 'px');
  }
});

document.addEventListener('pointerup', (e) => {
  if (!active || e.pointerId !== active.pointerId) return;
  const row = active.row;
  const dx = e.clientX - active.startX;
  const moved = active.moved;
  const committed = moved && Math.abs(dx) >= commitPx(row);
  row.classList.remove('swiping');
  row.style.removeProperty('--swipe-dx');
  active = null;
  if (committed) startPending(row, dx > 0 ? 'complete' : 'trash');
  if (moved) {
    // Any horizontal swipe (committed or not) was a gesture, not a tap — swallow the
    // trailing click so the row's <a> never navigates. On touch a swipe often fires NO
    // trailing click, so auto-disarm shortly after rather than eating the user's next tap.
    const swallow = (ev) => { ev.preventDefault(); ev.stopPropagation(); disarm(); };
    const disarm = () => { document.removeEventListener('click', swallow, true); clearTimeout(t); };
    document.addEventListener('click', swallow, true);
    const t = setTimeout(disarm, 400);
  }
});

// A cancelled pointer (OS gesture, scroll takeover, contextmenu) leaves no pointerup —
// reset so the row isn't stuck mid-swipe.
document.addEventListener('pointercancel', (e) => {
  if (!active || e.pointerId !== active.pointerId) return;
  active.row.classList.remove('swiping');
  active.row.style.removeProperty('--swipe-dx');
  active = null;
});

// --- Pointer-based drag reorder (the .drag-handle in an expanded item) ---
// HTML5 `draggable` does NOT fire on touch devices, so reorder with pointer events instead.
// The swipe handler above ignores pointerdowns that begin on a [data-drag] handle.
let pdrag = null;
function clearDrag() {
  if (pdrag) pdrag.item.classList.remove('dragging');
  document.querySelectorAll('.drag-over').forEach((el) => el.classList.remove('drag-over'));
  pdrag = null;
}
document.addEventListener('pointerdown', (e) => {
  const handle = e.target.closest && e.target.closest('[data-drag]');
  if (!handle) return;
  const item = handle.closest('li');
  if (!item) return;
  pdrag = { item, id: item.getAttribute('data-node'), pointerId: e.pointerId, over: null };
  item.classList.add('dragging');
  if (handle.setPointerCapture) handle.setPointerCapture(e.pointerId);
  e.preventDefault();
});
document.addEventListener('pointermove', (e) => {
  if (!pdrag || e.pointerId !== pdrag.pointerId) return;
  e.preventDefault();
  const el = document.elementFromPoint(e.clientX, e.clientY);
  const over = el && el.closest && el.closest('li');
  document.querySelectorAll('.drag-over').forEach((x) => x.classList.remove('drag-over'));
  pdrag.over = (over && over !== pdrag.item) ? over : null;
  if (pdrag.over) pdrag.over.classList.add('drag-over');
});
document.addEventListener('pointerup', (e) => {
  if (!pdrag || e.pointerId !== pdrag.pointerId) return;
  const over = pdrag.over;
  if (over && pdrag.id) {
    const beforeId = over.getAttribute('data-node'); // drop the moved item just before the target
    if (beforeId) {
      if (over.parentNode) over.parentNode.insertBefore(pdrag.item, over); // reflect immediately
      fetch('/node/' + pdrag.id + '/reorder-before?before=' + beforeId, { method: 'POST' });
    }
  }
  clearDrag();
});
document.addEventListener('pointercancel', (e) => { if (pdrag && e.pointerId === pdrag.pointerId) clearDrag(); });

// --- List search ('/' everywhere): filter the visible rows by text ---
const listSearch = document.querySelector('.list-search');
if (listSearch) {
  listSearch.addEventListener('input', () => {
    const q = listSearch.value.trim().toLowerCase();
    document.querySelectorAll('main li').forEach((li) => {
      const t = (li.querySelector('.row-title, a')?.textContent || li.textContent || '').toLowerCase();
      li.style.display = (!q || t.includes(q)) ? '' : 'none';
    });
  });
  listSearch.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { listSearch.value = ''; listSearch.dispatchEvent(new Event('input')); listSearch.classList.remove('show'); listSearch.blur(); }
  });
}

// --- Keyboard (desktop; VIM/Gmail-ish) ---

function editing() {
  const el = document.activeElement;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}
function setCursor(list, i) {
  list.forEach((r) => r.classList.remove('cursor'));
  if (i >= 0 && i < list.length) {
    cursor = i;
    list[i].classList.add('cursor');
    list[i].scrollIntoView({ block: 'nearest' });
  }
}
function reorderKey(row, dir) {
  const id = row.getAttribute('data-node');
  if (id) fetch('/node/' + id + '/rank?dir=' + dir, { method: 'POST' });
}
// Trigger a row's action button (File/Snooze/Waiting), expanding the panel first if collapsed.
function actOn(row, act) {
  const btn = row.querySelector('[data-act="' + act + '"]');
  if (!btn) return;
  const panel = row.querySelector('.expanded');
  if (panel && panel.offsetParent === null) row.querySelector('.row-title')?.click();
  btn.click();
}
// On a page with no row list (the Edit page), act on a unique [data-act] button.
function pageAction(key) {
  const map = { x: 'done', '#': 'delete', f: 'file', s: 'snooze', w: 'waiting', e: 'edit' };
  const btn = map[key] && document.querySelector('.actions [data-act="' + map[key] + '"]');
  if (btn) btn.click();
}
function toggleHelp() { document.querySelector('.kbd-help')?.classList.toggle('show'); }

document.addEventListener('keydown', (e) => {
  if (editing()) { if (e.key === 'Escape') document.activeElement.blur(); return; }

  if (e.key === '/') { if (listSearch) { e.preventDefault(); listSearch.classList.add('show'); listSearch.focus(); } return; }
  if (e.key === '?') { e.preventDefault(); toggleHelp(); return; }

  // g-chord: `g` then a view letter (i/t/n/p/r) navigates via the View menu's data-key links.
  if (pendingG && (Date.now() - pendingG) < CHORD_MS) {
    pendingG = 0;
    const target = document.querySelector('a[data-key="' + e.key + '"]');
    if (target) { e.preventDefault(); location.href = target.getAttribute('href'); }
    return;
  }
  if (e.key === 'g') { pendingG = Date.now(); e.preventDefault(); return; }
  if (e.key === 'Escape') { pendingG = 0; document.querySelector('.kbd-help')?.classList.remove('show'); setCursor(rows(), -1); cursor = -1; return; }

  const list = rows();
  if (list.length === 0) { pageAction(e.key); return; } // Edit page: act on its buttons
  const cur = (cursor >= 0 && cursor < list.length) ? list[cursor] : null;

  switch (e.key) {
    case 'j': e.preventDefault(); setCursor(list, Math.min(cursor + 1, list.length - 1)); break;
    case 'k': e.preventDefault(); setCursor(list, Math.max(cursor - 1, 0)); break;
    case 'J': if (cur) { e.preventDefault(); reorderKey(cur, 'down'); } break; // Shift+J = move down
    case 'K': if (cur) { e.preventDefault(); reorderKey(cur, 'up'); } break;   // Shift+K = move up
    case 'o':
    case 'Enter': if (cur) { e.preventDefault(); cur.querySelector('.row-title')?.click(); } break; // expand
    case 'x': if (cur) { e.preventDefault(); startPending(cur, 'complete'); } break; // Done (undo window)
    case '#':
    case 'Delete':
    case 'Backspace': if (cur) { e.preventDefault(); startPending(cur, 'trash'); } break;
    case 'e': if (cur) { const a = cur.querySelector('[data-act="edit"]'); if (a) { e.preventDefault(); location.href = a.getAttribute('href'); } } break;
    case 'f': if (cur) { e.preventDefault(); actOn(cur, 'file'); } break;
    case 's': if (cur) { e.preventDefault(); actOn(cur, 'snooze'); } break;
    case 'w': if (cur) { e.preventDefault(); actOn(cur, 'waiting'); } break;
    default: break;
  }
});
