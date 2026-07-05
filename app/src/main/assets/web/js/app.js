import { connectEvents, get } from './api.js';
import { renderInbox } from './views/inbox.js';

const routes = [
  { re: /^#\/inbox$/, render: renderInbox },
];
let lazyLoaded = false;

async function loadLazyRoutes() {
  if (lazyLoaded) return;
  const [{ renderTree }, { renderContexts }, { renderDetail }, { renderSettings }] = await Promise.all([
    import('./views/tree.js'), import('./views/contexts.js'), import('./views/detail.js'),
    import('./views/settings.js'),
  ]);
  routes.push(
    { re: /^#\/tree$/, render: renderTree },
    { re: /^#\/contexts$/, render: renderContexts },
    { re: /^#\/node\/(\d+)$/, render: (el, m) => renderDetail(el, Number(m[1])) },
    { re: /^#\/settings$/, render: renderSettings },
  );
  lazyLoaded = true;
}

export function toast(msg) {
  document.getElementById('toast-msg').textContent = msg;
  document.getElementById('toast').showModal();
}
document.getElementById('toast-ok').onclick = () => document.getElementById('toast').close();

export async function rerender() {
  await loadLazyRoutes();
  await renderContextFilter();
  const view = document.getElementById('view');
  const hash = location.hash || '#/inbox';
  const route = routes.find(r => r.re.test(hash)) ?? routes[0];
  try {
    await route.render(view, hash.match(route.re));
  } catch (e) {
    toast(e.message);
  }
}

async function renderContextFilter() {
  const bar = document.getElementById('context-filter-bar');
  if (!bar) return;
  const contexts = await get('/api/contexts');
  const selected = sessionStorage.getItem('ctx') || '';
  bar.innerHTML = `
    <label class="context-filter">
      <span>Context</span>
      <select id="context-filter">
        <option value="">Inbox</option>
        ${contexts.map(c => `<option value="${c.id}" ${String(c.id) === selected ? 'selected' : ''}>@${escapeHtml(c.name)}</option>`).join('')}
      </select>
    </label>`;
  bar.querySelector('#context-filter').onchange = (e) => {
    sessionStorage.setItem('ctx', e.target.value);
    if (location.hash !== '#/inbox') location.hash = '#/inbox';
    else rerender();
  };
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

window.addEventListener('hashchange', rerender);
connectEvents(rerender);
rerender();
