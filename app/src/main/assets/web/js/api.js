async function request(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const get = (p) => request('GET', p);
export const post = (p, b) => request('POST', p, b);
export const patch = (p, b) => request('PATCH', p, b);
export const put = (p, b) => request('PUT', p, b);

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30000;
let reconnectDelay = RECONNECT_BASE_MS;

export function connectEvents(onChanged) {
  const wsScheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${wsScheme}//${location.host}/api/events`);
  ws.onopen = () => { reconnectDelay = RECONNECT_BASE_MS; };
  ws.onmessage = (e) => { if (JSON.parse(e.data).type === 'changed') onChanged(); };
  ws.onclose = () => {
    // Exponential backoff with jitter, capped at RECONNECT_MAX_MS, reset to RECONNECT_BASE_MS
    // on the next successful open (above) — avoids a thundering herd of reconnects and avoids
    // hammering a server that's briefly unreachable.
    const delay = reconnectDelay;
    const jitter = delay * (0.5 + Math.random() * 0.5);
    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
    setTimeout(() => connectEvents(onChanged), jitter);
  };
}
