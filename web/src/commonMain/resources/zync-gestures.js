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

// --- Keyboard ---

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

document.addEventListener('keydown', (e) => {
  if (editing()) {
    if (e.key === 'Escape') document.activeElement.blur();
    return;
  }

  // g-chord: `g` then a tab letter navigates via the tab bar's data-key links.
  if (pendingG && (Date.now() - pendingG) < CHORD_MS) {
    pendingG = 0;
    const target = document.querySelector('a[data-key="' + e.key + '"]');
    if (target) { e.preventDefault(); location.href = target.getAttribute('href'); }
    return;
  }
  if (e.key === 'g') { pendingG = Date.now(); e.preventDefault(); return; }

  if (e.key === '/') {
    const search = document.querySelector('#search, input[type=search]');
    if (search) { e.preventDefault(); search.focus(); }
    return;
  }
  if (e.key === 'Escape') { pendingG = 0; setCursor(rows(), -1); cursor = -1; return; }

  const list = rows();
  if (list.length === 0) return;

  switch (e.key) {
    case 'j': e.preventDefault(); setCursor(list, Math.min(cursor + 1, list.length - 1)); break;
    case 'k': e.preventDefault(); setCursor(list, Math.max(cursor - 1, 0)); break;
    case ' ': // space → complete
      if (cursor >= 0 && cursor < list.length) { e.preventDefault(); fire(list[cursor], 'complete'); }
      break;
    case 'Delete':
    case 'Backspace':
      if (cursor >= 0 && cursor < list.length) { e.preventDefault(); fire(list[cursor], 'trash'); }
      break;
    case 'Enter':
      if (cursor >= 0 && cursor < list.length) {
        const link = list[cursor].querySelector('a[href^="/node/"]');
        if (link) { e.preventDefault(); link.click(); }
      }
      break;
    default: break;
  }
});
