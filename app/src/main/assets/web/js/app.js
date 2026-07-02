import { connectEvents } from './api.js';
import { renderInbox } from './views/inbox.js';

const routes = [
  { re: /^#\/inbox$/, render: renderInbox },
];
let lazyLoaded = false;

async function loadLazyRoutes() {
  if (lazyLoaded) return;
  const [{ renderTree }, { renderContexts }, { renderDetail }] = await Promise.all([
    import('./views/tree.js'), import('./views/contexts.js'), import('./views/detail.js'),
  ]);
  routes.push(
    { re: /^#\/tree$/, render: renderTree },
    { re: /^#\/contexts$/, render: renderContexts },
    { re: /^#\/node\/(\d+)$/, render: (el, m) => renderDetail(el, Number(m[1])) },
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
  const view = document.getElementById('view');
  const hash = location.hash || '#/inbox';
  const route = routes.find(r => r.re.test(hash)) ?? routes[0];
  try {
    await route.render(view, hash.match(route.re));
  } catch (e) {
    toast(e.message);
  }
}

window.addEventListener('hashchange', rerender);
connectEvents(rerender);
rerender();
