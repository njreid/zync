import { get, post } from '../api.js';
import { pickDestination } from './pickers.js';
import { rerender } from '../app.js';

const INBOX_ID = 1;

export async function renderInbox(el) {
  const selectedContext = Number(sessionStorage.getItem('ctx') || 0) || null;
  const all = selectedContext
    ? await get(`/api/contexts/${selectedContext}/tasks`)
    : await get(`/api/nodes/${INBOX_ID}/children`);
  const tasks = all.filter(t => t.status === 'ACTIVE');
  el.innerHTML = `
    ${quickCaptureSetupNotice()}
    <section id="inbox-list"></section>
    <div id="task-action-bar" class="task-action-bar"></div>`;
  const setup = el.querySelector('#quick-capture-setup');
  if (setup) setup.onclick = () => window.ZyncCapture.openAccessibilitySettings();
  wireTaskActionBar(el);
  const list = el.querySelector('#inbox-list');
  if (!tasks.length) {
    list.innerHTML = `<p class="muted">${selectedContext ? 'No active tasks in this context' : 'Inbox zero'}</p>`;
    return;
  }
  // Fetch each task's attachments concurrently (one round-trip per task),
  // then append the cards in order.
  const cards = await Promise.all(
    tasks.map(async (t) => taskCard(t, await get(`/api/nodes/${t.id}/attachments`))),
  );
  for (const card of cards) list.append(card);
}

function quickCaptureSetupNotice() {
  const nativeCapture = window.ZyncCapture;
  if (
    !nativeCapture ||
    typeof nativeCapture.isQuickCaptureEnabled !== 'function' ||
    typeof nativeCapture.openAccessibilitySettings !== 'function' ||
    nativeCapture.isQuickCaptureEnabled()
  ) {
    return '';
  }
  return `
    <section class="capture-setup">
      <span>Volume-button shortcuts are off.</span>
      <button type="button" id="quick-capture-setup" class="secondary">Enable</button>
    </section>`;
}

function taskCard(t, attachments) {
  const card = document.createElement('article');
  card.className = 'task';
  card.innerHTML = `
    <h4><a href="#/node/${t.id}">${escapeHtml(t.title)}</a><span class="attach-badge" hidden></span></h4>
    ${t.notes ? `<p class="muted">${escapeHtml(t.notes.slice(0, 160))}</p>` : ''}
    ${attachments.length ? attachmentSummary(attachments) : ''}
    <details class="clarify"><summary role="button" class="outline secondary">Clarify</summary>
      <div role="group">
        <button data-act="done">Do ✓</button>
        <button data-act="move" class="secondary">Move…</button>
        <button data-act="project" class="secondary">Make project…</button>
        <button data-act="someday" class="secondary">Someday</button>
        <button data-act="trash" class="contrast">Trash</button>
      </div>
    </details>`;
  card.querySelector('[data-act=done]').onclick = () => act(post(`/api/nodes/${t.id}/complete`));
  card.querySelector('[data-act=someday]').onclick = () => act(post(`/api/nodes/${t.id}/move`, { parentId: 2 }));
  card.querySelector('[data-act=trash]').onclick = () => act(post(`/api/nodes/${t.id}/trash`));
  card.querySelector('[data-act=move]').onclick = async () => {
    const dest = await pickDestination();
    if (dest != null) await act(post(`/api/nodes/${t.id}/move`, { parentId: dest }));
  };
  card.querySelector('[data-act=project]').onclick = async () => {
    const folder = await pickDestination({ foldersOnly: true });
    if (folder != null) await act(post(`/api/nodes/${t.id}/convert`, { folderId: folder }));
  };
  setAttachmentBadge(attachments, card.querySelector('.attach-badge'));
  return card;
}

// Annotate a card with a 📎 badge if the node has attachments (voice notes /
// scanned docs captured via share or the widget). Driven by the attachments
// already fetched in renderInbox — no extra round-trip per card.
function setAttachmentBadge(attachments, badge) {
  if (attachments && attachments.length) {
    badge.textContent = ` 📎 ${attachments.length}`;
    badge.hidden = false;
  }
}

async function act(promise) { await promise; await rerender(); }

function wireTaskActionBar(el) {
  const bar = el.querySelector('#task-action-bar');
  const nativeCapture = window.ZyncCapture;

  const renderActions = () => {
    bar.innerHTML = `
      <button type="button" class="bar-action" data-mode="text" aria-label="New text task">${faIcon('keyboard')}<span>Text</span></button>
      <button type="button" class="bar-action" data-mode="voice" aria-label="Record voice note">${faIcon('microphone')}<span>Voice</span></button>
      <button type="button" class="bar-action" data-mode="scan" aria-label="Scan document">${faIcon('file-lines')}<span>Scan</span></button>
      <button type="button" class="bar-action" data-mode="upload" aria-label="Upload document">${faIcon('file-arrow-up')}<span>Upload</span></button>`;
    bar.querySelector('[data-mode=text]').onclick = renderText;
    bar.querySelector('[data-mode=voice]').onclick = renderVoice;
    bar.querySelector('[data-mode=scan]').onclick = () => nativeCapture?.startDocumentScan?.();
    bar.querySelector('[data-mode=upload]').onclick = () => nativeCapture?.startDocumentUpload?.();
  };

  const renderText = () => {
    bar.innerHTML = `
      <form id="quick-add" class="bar-compose">
        <input id="quick-title" placeholder="New task…" autocomplete="off" autofocus>
        <button type="submit" aria-label="Add task">${faIcon('check')}<span>OK</span></button>
        <button type="button" class="secondary" data-cancel aria-label="Cancel">${faIcon('xmark')}<span>Cancel</span></button>
      </form>`;
    const input = bar.querySelector('#quick-title');
    input.focus();
    bar.querySelector('#quick-add').onsubmit = async (e) => {
      e.preventDefault();
      const title = input.value.trim();
      if (title) {
        await post('/api/inbox', { title });
        await rerender();
      } else {
        renderActions();
      }
    };
    bar.querySelector('[data-cancel]').onclick = renderActions;
  };

  const renderVoice = () => {
    const onDone = async () => {
      window.removeEventListener('zync-capture-saved', onDone);
      window.removeEventListener('zync-capture-discarded', onCancel);
      await rerender();
    };
    const onCancel = () => {
      window.removeEventListener('zync-capture-saved', onDone);
      window.removeEventListener('zync-capture-discarded', onCancel);
      renderActions();
    };
    window.addEventListener('zync-capture-saved', onDone);
    window.addEventListener('zync-capture-discarded', onCancel);
    bar.innerHTML = `
      <div class="bar-recording" role="group" aria-label="Voice recording controls">
        <button type="button" data-save aria-label="Save voice note">${faIcon('floppy-disk')}<span>Save</span></button>
        <button type="button" class="secondary" data-restart aria-label="Restart recording">${faIcon('rotate-right')}<span>Restart</span></button>
        <button type="button" class="contrast" data-discard aria-label="Discard recording">${faIcon('trash')}<span>Discard</span></button>
      </div>`;
    nativeCapture?.startVoiceNote?.();
    bar.querySelector('[data-save]').onclick = () => nativeCapture?.saveVoiceNote?.();
    bar.querySelector('[data-restart]').onclick = () => nativeCapture?.restartVoiceNote?.();
    bar.querySelector('[data-discard]').onclick = () => {
      nativeCapture?.discardVoiceNote?.();
      renderActions();
    };
  };
  renderActions();
}

function attachmentSummary(attachments) {
  const label = attachments.length === 1 ? '1 attachment' : `${attachments.length} attachments`;
  return `<p class="muted">${escapeHtml(label)}</p>`;
}

export function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function faIcon(name) {
  const paths = {
    keyboard: 'M64 96C28.7 96 0 124.7 0 160L0 416c0 35.3 28.7 64 64 64l384 0c35.3 0 64-28.7 64-64l0-256c0-35.3-28.7-64-64-64L64 96zM96 192l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm80 16c0-8.8 7.2-16 16-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16zm112-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm80 16c0-8.8 7.2-16 16-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16zM96 272l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm80 16c0-8.8 7.2-16 16-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16zm112-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm80 16c0-8.8 7.2-16 16-16l32 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-32 0c-8.8 0-16-7.2-16-16zM160 352l192 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-192 0c-8.8 0-16-7.2-16-16s7.2-16 16-16z',
    microphone: 'M256 0C203 0 160 43 160 96l0 128c0 53 43 96 96 96s96-43 96-96l0-128c0-53-43-96-96-96zM96 176c0-13.3-10.7-24-24-24s-24 10.7-24 24l0 48c0 105.9 79.3 193.4 181.8 206.2l0 33.8-45.8 0c-13.3 0-24 10.7-24 24s10.7 24 24 24l144 0c13.3 0 24-10.7 24-24s-10.7-24-24-24l-50.2 0 0-33.8C380.3 417.4 459.6 329.9 459.6 224l0-48c0-13.3-10.7-24-24-24s-24 10.7-24 24l0 48c0 86-69.7 155.6-155.6 155.6S100.4 310 100.4 224l0-48z',
    'file-lines': 'M64 0C28.7 0 0 28.7 0 64L0 448c0 35.3 28.7 64 64 64l288 0c35.3 0 64-28.7 64-64l0-288L256 0 64 0zM256 48l0 112 112 0L256 48zM112 256l192 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-192 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm0 64l192 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-192 0c-8.8 0-16-7.2-16-16s7.2-16 16-16zm0 64l128 0c8.8 0 16 7.2 16 16s-7.2 16-16 16l-128 0c-8.8 0-16-7.2-16-16s7.2-16 16-16z',
    'file-arrow-up': 'M64 0C28.7 0 0 28.7 0 64L0 448c0 35.3 28.7 64 64 64l288 0c35.3 0 64-28.7 64-64l0-288L256 0 64 0zM256 48l0 112 112 0L256 48zM216 408l0-102.1-31 31c-9.4 9.4-24.6 9.4-33.9 0s-9.4-24.6 0-33.9l72-72c9.4-9.4 24.6-9.4 33.9 0l72 72c9.4 9.4 9.4 24.6 0 33.9s-24.6 9.4-33.9 0l-31-31L264 408c0 13.3-10.7 24-24 24s-24-10.7-24-24z',
    check: 'M438.6 105.4c12.5 12.5 12.5 32.8 0 45.3l-256 256c-12.5 12.5-32.8 12.5-45.3 0l-128-128c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0L160 338.7 393.4 105.4c12.5-12.5 32.8-12.5 45.3 0z',
    xmark: 'M376.6 84.5c12.5-12.5 32.8-12.5 45.3 0s12.5 32.8 0 45.3L301.3 250.3 421.9 370.9c12.5 12.5 12.5 32.8 0 45.3s-32.8 12.5-45.3 0L256 295.6 135.4 416.2c-12.5 12.5-32.8 12.5-45.3 0s-12.5-32.8 0-45.3L210.7 250.3 90.1 129.7c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0L256 205.1 376.6 84.5z',
    'floppy-disk': 'M64 32C28.7 32 0 60.7 0 96L0 416c0 35.3 28.7 64 64 64l320 0c35.3 0 64-28.7 64-64l0-242.7c0-17-6.7-33.3-18.7-45.3L352 50.7C340 38.7 323.7 32 306.7 32L64 32zm0 64c0-17.7 14.3-32 32-32l192 0c17.7 0 32 14.3 32 32l0 80c0 17.7-14.3 32-32 32L96 208c-17.7 0-32-14.3-32-32L64 96zM224 288a64 64 0 1 1 0 128 64 64 0 1 1 0-128z',
    'rotate-right': 'M463.5 224C463.5 100.3 363.2 0 239.5 0 161.5 0 92.8 39.8 52.7 100.2c-10.2 15.3-6.1 36 9.2 46.2s36 6.1 46.2-9.2C136.4 94.6 184.8 66.5 239.5 66.5c86.9 0 157.5 70.5 157.5 157.5s-70.5 157.5-157.5 157.5c-57 0-106.9-30.3-134.6-75.8-9.6-15.7-30.1-20.7-45.8-11.1s-20.7 30.1-11.1 45.8C87.3 405.5 158.4 448 239.5 448c123.7 0 224-100.3 224-224zM448 0c-17.7 0-32 14.3-32 32l0 96-96 0c-17.7 0-32 14.3-32 32s14.3 32 32 32l128 0c17.7 0 32-14.3 32-32l0-128c0-17.7-14.3-32-32-32z',
    trash: 'M135.2 17.7C140.6 6.8 151.7 0 163.8 0L284.2 0c12.1 0 23.2 6.8 28.6 17.7L328 48l88 0c13.3 0 24 10.7 24 24s-10.7 24-24 24L32 96C18.7 96 8 85.3 8 72S18.7 48 32 48l88 0 15.2-30.3zM53.2 467c-1.7-26.1-8.5-136.8-12.8-207.5L37.2 208 410.8 208l-3.2 51.5c-4.3 70.7-11.1 181.4-12.8 207.5-1.6 25.5-22.7 45-48.3 45L101.5 512c-25.6 0-46.7-19.5-48.3-45z'
  };
  const viewBox = ['keyboard', 'microphone', 'xmark'].includes(name) ? '0 0 512 512' : '0 0 448 512';
  return `<svg class="fa-icon" viewBox="${viewBox}" aria-hidden="true"><path d="${paths[name]}"></path></svg>`;
}
