// Desktop quick-capture bar (Layout.captureBar): drag-drop files and/or type a title, then Save
// posts one multipart request to /capture (server-only) that creates a single inbox item with the
// files attached. No inline JS — the loopback CSP forbids it — so this vendored module wires the
// stable ids the bar lays out. Loaded on every page; a no-op where the bar isn't rendered.

const bar = document.getElementById('capture-bar');
if (bar) {
  const drop = document.getElementById('capture-drop');
  const fileInput = document.getElementById('capture-files');
  const chips = document.getElementById('capture-chips');
  const title = document.getElementById('capture-title');
  const save = document.getElementById('capture-save');
  const clear = document.getElementById('capture-clear');
  const hint = drop ? drop.querySelector('.capture-hint') : null;
  const defaultHint = hint ? hint.textContent : '';

  // Our own staging list — a file <input> can't be appended to across separate drops/picks.
  let staged = [];

  const visible = () => getComputedStyle(bar).display !== 'none';

  function renderChips() {
    chips.textContent = '';
    staged.forEach((file, i) => {
      const chip = document.createElement('span');
      chip.className = 'capture-chip';
      chip.title = file.name;
      chip.append(document.createTextNode(file.name));
      const x = document.createElement('button');
      x.type = 'button';
      x.setAttribute('aria-label', 'Remove ' + file.name);
      x.textContent = '×';
      x.addEventListener('click', () => { staged.splice(i, 1); renderChips(); });
      chip.append(x);
      chips.append(chip);
    });
  }

  function addFiles(list) {
    for (const f of list) staged.push(f);
    renderChips();
  }

  function reset() {
    staged = [];
    renderChips();
    title.value = '';
    fileInput.value = '';
    if (hint) hint.textContent = defaultHint;
  }

  // Click the drop zone to browse; picked files merge into the staging list.
  fileInput.addEventListener('change', () => {
    if (fileInput.files) addFiles(fileInput.files);
    fileInput.value = ''; // allow re-picking the same file
  });

  // Drag-drop: highlight while hovering, stage on drop. Bound at the window so a drop anywhere on
  // a desktop page captures (and never lets the browser navigate to the dropped file).
  const hasFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
  window.addEventListener('dragover', (e) => {
    if (!visible() || !hasFiles(e)) return;
    e.preventDefault();
    drop.classList.add('dragover');
  });
  window.addEventListener('dragleave', (e) => {
    if (e.relatedTarget === null) drop.classList.remove('dragover');
  });
  window.addEventListener('drop', (e) => {
    if (!visible() || !hasFiles(e)) return;
    e.preventDefault();
    drop.classList.remove('dragover');
    if (e.dataTransfer.files && e.dataTransfer.files.length) addFiles(e.dataTransfer.files);
  });

  async function submit() {
    const text = title.value.trim();
    if (!staged.length && !text) return;
    const body = new FormData();
    body.append('title', text);
    staged.forEach((f) => body.append('files', f, f.name));
    save.setAttribute('aria-busy', 'true');
    try {
      const r = await fetch('/capture', { method: 'POST', body });
      if (r.ok) {
        // The open /updates SSE stream live-patches #inbox with the new item; just clear the bar.
        reset();
      } else if (hint) {
        hint.textContent = r.status === 413 ? 'File too large' : 'Save failed — try again';
      }
    } catch (_) {
      if (hint) hint.textContent = 'Save failed — offline?';
    } finally {
      save.removeAttribute('aria-busy');
    }
  }

  save.addEventListener('click', submit);
  clear.addEventListener('click', reset);
  // Enter in the title box saves (unless files still uploading).
  title.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); submit(); }
  });
}
