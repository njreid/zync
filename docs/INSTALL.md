# Installing zync (Android)

zync is distributed as a **signed APK attached to each GitHub Release** — there
is no Play Store listing. Two ways to install:

## Option A — Obtainium (recommended: auto-updates)

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from
GitHub Releases and keeps them updated.

1. Install Obtainium (from its own GitHub Releases, or F-Droid/IzzyOnDroid).
2. In Obtainium: **Add App** → paste this repo's URL
   (`https://github.com/njreid/zync`).
3. Obtainium finds the latest release APK; tap **Install**. New releases are
   offered automatically thereafter.

## Option B — Manual sideload

1. Download the `.apk` from the latest release:
   <https://github.com/njreid/zync/releases/latest>
2. Open it on the phone; approve **Install unknown apps** for your browser/file
   manager if prompted.

> Because zync is self-signed (not Play-signed), updates only install over an
> existing install if they're signed with the **same** key. The CI release
> workflow guarantees this by reusing one keystore.

---

## For maintainers: cutting a release

Releases are built and signed by `.github/workflows/release.yml`, triggered by
pushing a `v*` tag.

### One-time setup

Run the helper script — it generates the keystore (with a strong random
password), writes the git-ignored `key.properties` for local signed builds, and
prints (or, with an authenticated `gh` CLI, sets) the GitHub Actions secrets:

```sh
scripts/make-release-keystore.sh
```

Keep `zync-release.jks` and its password backed up somewhere safe — losing them
means you can never publish an update that installs over existing installs.

<details><summary>Or do it by hand</summary>

1. Generate a release keystore:

   ```sh
   keytool -genkeypair -v -keystore zync-release.jks \
     -alias zync -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=zync, O=njr"
   ```

2. Add these **repository secrets** (Settings → Secrets and variables →
   Actions):
   - `ZYNC_KEYSTORE_BASE64` — `base64 -w0 zync-release.jks`
   - `ZYNC_KEYSTORE_PASSWORD`, `ZYNC_KEY_ALIAS` (`zync`), `ZYNC_KEY_PASSWORD`

</details>

### Each release

```sh
git tag v1.2.0
git push origin v1.2.0
```

The workflow builds `assembleRelease` (versionName from the tag, versionCode
from the run number), verifies the signature with `apksigner`, and attaches the
APK to the GitHub Release.

### Local signed build (optional)

Copy `key.properties.example` to `key.properties` (git-ignored), point it at
your keystore, then:

```sh
./gradlew assembleRelease
```

Without `key.properties` or the CI env vars, `assembleRelease` still builds but
produces an **unsigned** APK.
