# zync — Style Guide

Design conventions for zync's user-facing surfaces. Keep this in sync with the
CSS it describes; the CSS is the source of truth for exact values.

> **🧭 Direction (2026-07-08):** under the target architecture these tokens must be
> **one design language across the seam** — applied to *both* the native **Compose**
> theme (phone launcher/capture screens) and the shared **web** module (kotlinx.html
> + Datastar, served by the server and the phone's loopback Ktor). The Geist/Inter
> choice below is the source of truth for both. See
> `docs/superpowers/specs/2026-07-08-kotlin-kmp-target-architecture.md`.

## Typography

| Role | Typeface | Weights bundled | Fallback stack |
|------|----------|-----------------|----------------|
| Headings (`h1`–`h6`, `hgroup` titles) | **Geist** | 400, 500, 600, 700 | `"Geist", "Inter", system-ui, sans-serif` |
| Body / UI text | **Inter** | 400, 500, 600, 700 | `"Inter", system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif` |
| Code / monospace | system monospace | — | `ui-monospace, monospace` |

Headings use a slight negative tracking (`letter-spacing: -0.01em`) to match
Geist's display proportions.

### Why self-hosted

The phone-served web UI runs under a strict Content-Security-Policy
(`default-src 'self'`, see `app/src/main/assets/web/index.html`). Google Fonts
and any other CDN are therefore **blocked** — the browser would silently fail
to load them. All fonts must be self-hosted so they are served same-origin by
the phone. `font-src` inherits `default-src 'self'`, so no CSP change is needed.

### Where the files live

- Fonts: `app/src/main/assets/web/fonts/{inter,geist}-{400,500,600,700}.woff2`
  (latin subset only, `woff2`, to keep the bundle small — ~245 KB total).
- `@font-face` + family rules: top of `app/src/main/assets/web/custom.css`,
  which loads **after** `vendor/pico.min.css` so the overrides win.
- Body font is applied by overriding Pico's `--pico-font-family-sans-serif`
  token at `:root` (Pico derives `--pico-font-family` from it, so every default
  surface inherits Inter). Headings are re-pointed at Geist with an explicit
  `h1…h6` rule.
- `font-display: swap` so text renders immediately in the fallback and swaps to
  the web font when ready.

### Updating / adding weights

Fonts are vendored from the Fontsource npm packages (OFL-1.1). To refresh or add
a weight:

```sh
npm install @fontsource/inter @fontsource/geist-sans   # in a scratch dir
# copy files/inter-latin-<weight>-normal.woff2  ->  web/fonts/inter-<weight>.woff2
# copy files/geist-sans-latin-<weight>-normal.woff2 -> web/fonts/geist-<weight>.woff2
```

Then add a matching `@font-face` block in `custom.css`. Only add weights the UI
actually uses — every weight is a separate download.

### Licensing

Both families are licensed under the SIL Open Font License 1.1. The license text
ships alongside the fonts:

- `app/src/main/assets/web/fonts/Inter-OFL.txt`
- `app/src/main/assets/web/fonts/Geist-OFL.txt`

## Scope / not yet applied

**Shipped (v0.2):** this typography is implemented in the phone-hosted vanilla-JS
web UI (`app/src/main/assets/web/`). The desktop pre-pairing shell (`desktop/ui/`)
still uses Pico defaults.

**Target:** the same Geist/Inter tokens apply to (1) the shared **web** module and
(2) the native **Compose** theme, so the native launcher and the WebView content
read as one app. When the rebuild lands, re-point this guide at the shared `web`
module's CSS and the Compose theme; the shipped-v0.2 specifics above will retire
along with the vanilla-JS UI and the Tauri desktop shell.
