package dev.njr.zync.server.auth.webauthn

/**
 * The passwordless login page served at `/login`. Runs the WebAuthn ceremonies in the
 * browser via `navigator.credentials` and posts the results to the `/auth/webauthn`
 * endpoints. This is
 * the one piece that genuinely needs a real browser (or a virtual authenticator) to exercise
 * end-to-end; the server-side verification it drives is covered by the emulator tests.
 */
fun loginPageHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>zync — sign in</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 26rem; margin: 4rem auto; padding: 0 1rem; }
    button { font-size: 1rem; padding: .6rem 1rem; cursor: pointer; }
    .row { margin: 1rem 0; }
    input { font-size: 1rem; padding: .5rem; width: 100%; box-sizing: border-box; }
    #status { color: #555; min-height: 1.4rem; }
    details { margin-top: 2rem; color: #666; }
  </style>
</head>
<body>
  <h1>zync</h1>
  <div class="row"><button id="signin">Sign in with a passkey</button></div>
  <div id="status" class="row" role="status"></div>
  <details>
    <summary>Enrol a new passkey</summary>
    <div class="row"><input id="regtoken" type="password" placeholder="Registration token" autocomplete="off"></div>
    <div class="row"><button id="register">Register passkey</button></div>
  </details>
  <script>
    const b64uToBuf = (s) => {
      s = s.replace(/-/g, '+').replace(/_/g, '/');
      const pad = s.length % 4 ? '='.repeat(4 - (s.length % 4)) : '';
      const bin = atob(s + pad);
      const buf = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
      return buf.buffer;
    };
    const bufToB64u = (buf) => {
      let bin = '';
      const bytes = new Uint8Array(buf);
      for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
      return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
    };
    const setStatus = (m) => { document.getElementById('status').textContent = m; };

    async function signIn() {
      setStatus('Requesting challenge…');
      const opts = await (await fetch('/auth/webauthn/assert/options')).json();
      const publicKey = {
        challenge: b64uToBuf(opts.challenge),
        rpId: opts.rpId,
        timeout: opts.timeout,
        userVerification: opts.userVerification,
        allowCredentials: opts.allowCredentials.map(c => ({ type: c.type, id: b64uToBuf(c.id) })),
      };
      const cred = await navigator.credentials.get({ publicKey });
      const body = {
        id: cred.id, rawId: bufToB64u(cred.rawId), type: cred.type,
        response: {
          clientDataJSON: bufToB64u(cred.response.clientDataJSON),
          authenticatorData: bufToB64u(cred.response.authenticatorData),
          signature: bufToB64u(cred.response.signature),
          userHandle: cred.response.userHandle ? bufToB64u(cred.response.userHandle) : null,
        },
      };
      const r = await fetch('/auth/webauthn/assert', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (r.ok) { setStatus('Signed in.'); location.href = '/'; }
      else setStatus('Sign-in failed.');
    }

    async function register() {
      const token = document.getElementById('regtoken').value;
      setStatus('Requesting registration challenge…');
      const opts = await (await fetch('/auth/webauthn/register/options', { headers: { 'X-Registration-Token': token } })).json();
      const publicKey = {
        challenge: b64uToBuf(opts.challenge),
        rp: opts.rp,
        user: { id: b64uToBuf(opts.user.id), name: opts.user.name, displayName: opts.user.displayName },
        pubKeyCredParams: opts.pubKeyCredParams.map(p => ({ type: p.type, alg: p.alg })),
        timeout: opts.timeout,
        attestation: opts.attestation,
        authenticatorSelection: opts.authenticatorSelection,
      };
      const cred = await navigator.credentials.create({ publicKey });
      const body = {
        id: cred.id, rawId: bufToB64u(cred.rawId), type: cred.type,
        response: {
          clientDataJSON: bufToB64u(cred.response.clientDataJSON),
          attestationObject: bufToB64u(cred.response.attestationObject),
        },
      };
      const r = await fetch('/auth/webauthn/register', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Registration-Token': token }, body: JSON.stringify(body) });
      setStatus(r.ok ? 'Passkey registered — you can sign in now.' : 'Registration failed.');
    }

    document.getElementById('signin').addEventListener('click', () => signIn().catch(e => setStatus(e.message)));
    document.getElementById('register').addEventListener('click', () => register().catch(e => setStatus(e.message)));
  </script>
</body>
</html>
""".trimIndent()
