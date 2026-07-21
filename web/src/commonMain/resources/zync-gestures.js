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
  if (committed) fire(row, dx > 0 ? 'complete' : 'trash');
  if (moved) {
    // Any horizontal swipe (committed or not) was a gesture, not a tap — swallow the
    // trailing click so the row's <a> never navigates.
    document.addEventListener('click', function swallow(ev) {
      ev.preventDefault();
      ev.stopPropagation();
      document.removeEventListener('click', swallow, true);
    }, true);
  }
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
    const target = document.querySelector('nav.tabbar a[data-key="' + e.key + '"]');
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
